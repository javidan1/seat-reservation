package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * "Persist bookings durably; a restart loses nothing." Proven by actually restarting: the whole
 * Spring context is torn down and a fresh one is booted against the same database. Bookings
 * survive, an in-flight hold is still confirmable, and a hold that lapsed while the service was
 * down is reclaimable by someone else.
 */
class RestartDurabilityIT {

  static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  @BeforeAll
  static void startDb() {
    postgres.start();
  }

  @AfterAll
  static void stopDb() {
    postgres.stop();
  }

  private ConfigurableApplicationContext boot() {
    // Command-line-style args: highest precedence, so application.yaml cannot override them.
    return new SpringApplicationBuilder(SeatReservationApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--seatres.sweeper.enabled=false",
            "--seatres.hold-ttl=60s");
  }

  @Test
  void restartLosesNothing() {
    UUID bookingId;
    UUID inFlightHoldId;
    List<UUID> lapsedSeats;

    // --- First life of the service ---
    try (ConfigurableApplicationContext ctx = boot()) {
      Api api = new Api(ctx);
      List<UUID> rowE = api.seatsOfRow("E");

      // A completed booking.
      UUID doneHold = api.holdOk("alice", rowE.subList(0, 2));
      bookingId = api.confirmOk(doneHold);

      // A hold still in flight when the service dies.
      inFlightHoldId = api.holdOk("carol", rowE.subList(2, 4));

      // A hold that will lapse while the service is down (time-travel instead of downtime).
      lapsedSeats = rowE.subList(4, 6);
      UUID lapsedHold = api.holdOk("dave", lapsedSeats);
      api.shiftHoldExpiry(lapsedHold, -1);
    }

    // --- Second life: same database, brand-new process state ---
    try (ConfigurableApplicationContext ctx = boot()) {
      Api api = new Api(ctx);

      // The booking survived, with its seats.
      assertThat(api.get("/api/bookings/" + bookingId).status()).isEqualTo(200);
      assertThat(api.soldSeatCount()).isEqualTo(2);

      // The in-flight hold survived and still confirms.
      assertThat(api.get("/api/holds/" + inFlightHoldId).body()).contains("ACTIVE");
      api.confirmOk(inFlightHoldId);

      // The lapsed hold's seats are reclaimable by a new customer, no sweeper involved.
      UUID reclaimed = api.holdOk("erin", lapsedSeats);
      api.confirmOk(reclaimed);

      assertThat(api.soldSeatCount()).isEqualTo(6);
      assertThat(api.doubleSold()).isZero();
    }
  }

  /** Minimal client bound to one application context (its port, mapper, and datasource). */
  private static final class Api {
    final RestClient http;
    final ObjectMapper json;
    final JdbcClient jdbc;

    Api(ConfigurableApplicationContext ctx) {
      int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
      this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
      this.json = ctx.getBean(ObjectMapper.class);
      this.jdbc = ctx.getBean(JdbcClient.class);
    }

    record Response(int status, String body) {}

    Response get(String path) {
      return http.get()
          .uri(path)
          .exchange((rq, rs) -> new Response(rs.getStatusCode().value(), body(rs.getBody())));
    }

    UUID holdOk(String user, List<UUID> seats) {
      Response r =
          http.post()
              .uri("/api/events/" + EVENT + "/holds")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("userRef", user, "seatIds", seats))
              .exchange((rq, rs) -> new Response(rs.getStatusCode().value(), body(rs.getBody())));
      assertThat(r.status()).as("hold: %s", r.body()).isEqualTo(201);
      return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }

    UUID confirmOk(UUID holdId) {
      Response r =
          http.post()
              .uri("/api/holds/" + holdId + "/confirm")
              .exchange((rq, rs) -> new Response(rs.getStatusCode().value(), body(rs.getBody())));
      assertThat(r.status()).as("confirm: %s", r.body()).isEqualTo(201);
      return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }

    List<UUID> seatsOfRow(String row) {
      return jdbc.sql(
              "SELECT id FROM seat WHERE event_id = :e AND row_label = :r ORDER BY seat_number")
          .param("e", EVENT)
          .param("r", row)
          .query(UUID.class)
          .list();
    }

    void shiftHoldExpiry(UUID holdId, double seconds) {
      jdbc.sql("UPDATE hold SET expires_at = now() + make_interval(secs => :s) WHERE id = :id")
          .param("s", seconds)
          .param("id", holdId)
          .update();
      jdbc.sql(
              "UPDATE seat SET hold_expires_at = now() + make_interval(secs => :s)"
                  + " WHERE hold_id = :id AND status = 'HELD'")
          .param("s", seconds)
          .param("id", holdId)
          .update();
    }

    long soldSeatCount() {
      return jdbc.sql("SELECT count(*) FROM seat WHERE status = 'SOLD'").query(Long.class).single();
    }

    long doubleSold() {
      return jdbc.sql(
              "SELECT count(*) FROM (SELECT seat_id FROM booking_seat GROUP BY seat_id"
                  + " HAVING count(*) > 1) d")
          .query(Long.class)
          .single();
    }

    private static String body(java.io.InputStream in) {
      try {
        return new String(in.readAllBytes());
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }
}

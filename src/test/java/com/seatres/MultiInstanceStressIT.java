package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
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
 * Stretch evidence: the no-double-sell guarantee holds when the service runs as TWO real
 * processes-worth of application contexts sharing one Postgres, with every worker alternating
 * between instances per request. Nothing in the correctness path is instance-local — DB clock, row
 * CAS, schema constraints — so this must pass without any coordination code.
 */
class MultiInstanceStressIT {

  static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
  static ConfigurableApplicationContext instanceA;
  static ConfigurableApplicationContext instanceB;
  static RestClient clientA;
  static RestClient clientB;
  static ObjectMapper json;
  static JdbcClient jdbc;

  @BeforeAll
  static void bootTwoInstances() {
    postgres.start();
    instanceA = boot();
    instanceB = boot(); // second instance: same DB, its own sweeper, its own port
    clientA = clientFor(instanceA);
    clientB = clientFor(instanceB);
    json = instanceA.getBean(ObjectMapper.class);
    jdbc = instanceA.getBean(JdbcClient.class);
  }

  @AfterAll
  static void shutDown() {
    if (instanceA != null) {
      instanceA.close();
    }
    if (instanceB != null) {
      instanceB.close();
    }
    postgres.stop();
  }

  private static ConfigurableApplicationContext boot() {
    return new SpringApplicationBuilder(SeatReservationApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--seatres.hold-ttl=2s",
            "--seatres.sweeper.enabled=true",
            "--seatres.sweeper.interval=200ms");
  }

  private static RestClient clientFor(ConfigurableApplicationContext ctx) {
    int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }

  record Response(int status, String body) {}

  private static Response call(RestClient client, String method, String path, Object body) {
    var spec =
        switch (method) {
          case "POST" ->
              body == null
                  ? client.post().uri(path)
                  : client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
          case "DELETE" -> client.delete().uri(path);
          default -> client.get().uri(path);
        };
    return spec.exchange(
        (rq, rs) -> {
          try {
            return new Response(
                rs.getStatusCode().value(), new String(rs.getBody().readAllBytes()));
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  @Test
  void stormAcrossTwoInstancesNeverDoubleSells() throws Exception {
    List<UUID> allSeats =
        jdbc.sql("SELECT id FROM seat WHERE event_id = :e ORDER BY row_label, seat_number")
            .param("e", EVENT)
            .query(UUID.class)
            .list();
    List<UUID> hotBlock = allSeats.subList(0, 30);
    var ledger = new ConcurrentHashMap<UUID, Set<UUID>>();

    int workers = 40;
    int attemptsPerWorker = 10;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Object>> futures =
          IntStream.range(0, workers)
              .mapToObj(
                  worker ->
                      executor.submit(
                          () -> {
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            for (int attempt = 0; attempt < attemptsPerWorker; attempt++) {
                              // Alternate instances per request: hold may land on A, confirm on B.
                              RestClient holdOn = (worker + attempt) % 2 == 0 ? clientA : clientB;
                              RestClient confirmOn =
                                  (worker + attempt) % 2 == 0 ? clientB : clientA;
                              Set<UUID> pick = new HashSet<>();
                              int size = 1 + random.nextInt(3);
                              while (pick.size() < size) {
                                List<UUID> pool = random.nextInt(100) < 80 ? hotBlock : allSeats;
                                pick.add(pool.get(random.nextInt(pool.size())));
                              }
                              Response held =
                                  call(
                                      holdOn,
                                      "POST",
                                      "/api/events/" + EVENT + "/holds",
                                      Map.of(
                                          "userRef", "w" + worker, "seatIds", List.copyOf(pick)));
                              if (held.status() != 201) {
                                continue;
                              }
                              String holdId = json.readTree(held.body()).get("id").asString();
                              if (random.nextInt(100) < 70) {
                                Response confirmed =
                                    call(
                                        confirmOn,
                                        "POST",
                                        "/api/holds/" + holdId + "/confirm",
                                        null);
                                if (confirmed.status() == 201) {
                                  var booking = json.readTree(confirmed.body());
                                  UUID bookingId = UUID.fromString(booking.get("id").asString());
                                  for (var seatNode : booking.get("seatIds")) {
                                    ledger
                                        .computeIfAbsent(
                                            UUID.fromString(seatNode.asString()),
                                            k -> ConcurrentHashMap.newKeySet())
                                        .add(bookingId);
                                  }
                                }
                              } else if (random.nextInt(2) == 0) {
                                call(confirmOn, "DELETE", "/api/holds/" + holdId, null);
                              } // else abandon: a sweeper on either instance may reap it
                            }
                            return null;
                          }))
              .toList();
      for (Future<Object> future : futures) {
        future.get();
      }
    }

    ledger.forEach(
        (seat, bookings) ->
            assertThat(bookings)
                .as("seat %s booked more than once across instances", seat)
                .hasSizeLessThanOrEqualTo(1));
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM (SELECT seat_id FROM booking_seat GROUP BY seat_id"
                        + " HAVING count(*) > 1) d")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM hold h WHERE h.status = 'CONFIRMED' AND h.seat_count <>"
                        + " (SELECT count(*) FROM booking b JOIN booking_seat bs"
                        + "   ON bs.booking_id = b.id WHERE b.hold_id = h.id)")
                .query(Long.class)
                .single())
        .isZero();
    long bookings = jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single();
    System.out.printf(
        "MULTI-INSTANCE STRESS: %d workers alternating across 2 instances -> %d bookings,"
            + " 0 double-sells (verified)%n",
        workers, bookings);
    assertThat(bookings).isPositive();
  }
}

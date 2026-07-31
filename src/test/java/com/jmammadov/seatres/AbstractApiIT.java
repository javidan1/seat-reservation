package com.jmammadov.seatres;

import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.HoldView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for API-level integration tests against a real Postgres (Testcontainers).
 *
 * <p>The sweeper is disabled here on purpose: several tests prove that correctness holds without
 * it. Tests never sleep to reach expiry — they time-travel by rewriting {@code expires_at} in the
 * database, which is legitimate precisely because the DB clock is the single time authority.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"seatres.sweeper.enabled=false", "seatres.hold-ttl=60s"})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractApiIT {

  public static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @LocalServerPort int port;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected ObjectMapper json;

  protected RestClient client;

  public record ApiResponse(int status, String body) {
    public boolean is2xx() {
      return status >= 200 && status < 300;
    }
  }

  @BeforeEach
  void setUpClientAndResetData() {
    client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    jdbc.sql("DELETE FROM booking_seat").update();
    jdbc.sql("DELETE FROM booking").update();
    jdbc.sql("DELETE FROM waitlist").update();
    jdbc.sql(
            "UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL,"
                + " version = 0")
        .update();
    jdbc.sql("DELETE FROM hold").update();
  }

  // --- HTTP helpers (never throw on 4xx/5xx: tests assert on status codes) ---

  protected ApiResponse post(String path, Object body) {
    var spec = client.post().uri(path);
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange((req, res) -> toApiResponse(res.getStatusCode().value(), res.getBody()));
  }

  protected ApiResponse get(String path) {
    return client
        .get()
        .uri(path)
        .exchange((req, res) -> toApiResponse(res.getStatusCode().value(), res.getBody()));
  }

  protected ApiResponse delete(String path) {
    return client
        .delete()
        .uri(path)
        .exchange((req, res) -> toApiResponse(res.getStatusCode().value(), res.getBody()));
  }

  private static ApiResponse toApiResponse(int status, java.io.InputStream body) {
    try {
      return new ApiResponse(status, new String(body.readAllBytes()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected <T> T parse(ApiResponse response, Class<T> type) {
    try {
      return json.readValue(response.body(), type);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Cannot parse as " + type + ": " + response.body(), e);
    }
  }

  // --- Domain helpers ---

  protected ApiResponse holdSeats(String userRef, List<UUID> seatIds) {
    return post("/api/events/" + EVENT + "/holds", Map.of("userRef", userRef, "seatIds", seatIds));
  }

  protected HoldView holdSeatsOk(String userRef, List<UUID> seatIds) {
    ApiResponse response = holdSeats(userRef, seatIds);
    if (response.status() != 201) {
      throw new AssertionError(
          "Expected 201 hold, got " + response.status() + ": " + response.body());
    }
    return parse(response, HoldView.class);
  }

  protected ApiResponse confirm(UUID holdId) {
    return post("/api/holds/" + holdId + "/confirm", null);
  }

  protected Booking confirmOk(UUID holdId) {
    ApiResponse response = confirm(holdId);
    if (response.status() != 201 && response.status() != 200) {
      throw new AssertionError(
          "Expected confirm to succeed, got " + response.status() + ": " + response.body());
    }
    return parse(response, Booking.class);
  }

  /** Seat ids of a whole row, in seat-number order. */
  protected List<UUID> seatsOfRow(String rowLabel) {
    return jdbc.sql(
            "SELECT id FROM seat WHERE event_id = :e AND row_label = :r ORDER BY seat_number")
        .param("e", EVENT)
        .param("r", rowLabel)
        .query(UUID.class)
        .list();
  }

  /**
   * Time-travel: move a hold's expiry (hold row AND its seats' copies, as one atomic change) to
   * now() + offsetSeconds. Negative = already lapsed.
   */
  protected void shiftHoldExpiry(UUID holdId, double offsetSeconds) {
    jdbc.sql("UPDATE hold SET expires_at = now() + make_interval(secs => :s) WHERE id = :id")
        .param("s", offsetSeconds)
        .param("id", holdId)
        .update();
    jdbc.sql(
            "UPDATE seat SET hold_expires_at = now() + make_interval(secs => :s)"
                + " WHERE hold_id = :id AND status = 'HELD'")
        .param("s", offsetSeconds)
        .param("id", holdId)
        .update();
  }

  // --- Invariant queries ---

  protected long doubleSoldSeatCount() {
    return jdbc.sql(
            "SELECT count(*) FROM (SELECT seat_id FROM booking_seat"
                + " GROUP BY seat_id HAVING count(*) > 1) d")
        .query(Long.class)
        .single();
  }

  protected long soldSeatsWithoutBooking() {
    return jdbc.sql(
            "SELECT count(*) FROM seat s WHERE s.status = 'SOLD' AND NOT EXISTS"
                + " (SELECT 1 FROM booking_seat bs WHERE bs.seat_id = s.id)")
        .query(Long.class)
        .single();
  }

  /** Tripwire for the stranded-seat race: a WAITING entry must always watch a HELD seat. */
  protected long waitersOnNonHeldSeats() {
    return jdbc.sql(
            "SELECT count(*) FROM waitlist w JOIN seat s ON s.id = w.seat_id"
                + " WHERE w.status = 'WAITING' AND s.status <> 'HELD'")
        .query(Long.class)
        .single();
  }

  protected long partiallyConfirmedHolds() {
    return jdbc.sql(
            "SELECT count(*) FROM hold h WHERE h.status = 'CONFIRMED' AND h.seat_count <>"
                + " (SELECT count(*) FROM booking b JOIN booking_seat bs ON bs.booking_id = b.id"
                + "   WHERE b.hold_id = h.id)")
        .query(Long.class)
        .single();
  }
}

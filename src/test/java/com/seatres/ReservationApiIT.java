package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.domain.HoldView;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** API contract: validation, error shapes, idempotency, and the all-or-nothing 409. */
class ReservationApiIT extends AbstractApiIT {

  @Test
  void validationRejectsBadHoldRequests() {
    List<UUID> row = seatsOfRow("A");
    assertThat(holdSeats("alice", Collections.emptyList()).status()).isEqualTo(400);
    assertThat(holdSeats("alice", row.subList(0, 9)).status()).isEqualTo(400); // > max 8
    assertThat(holdSeats("  ", row.subList(0, 1)).status()).isEqualTo(400);
    assertThat(holdSeats("x".repeat(65), row.subList(0, 1)).status()).isEqualTo(400); // > 64 chars
    assertThat(
            post(
                    "/api/events/" + EVENT + "/waitlist",
                    java.util.Map.of("userRef", "x".repeat(65), "seatId", row.get(0)))
                .status())
        .isEqualTo(400);
  }

  @Test
  void unknownIdsAreCleanFourOhFours() {
    UUID nope = UUID.randomUUID();
    assertThat(holdSeats("alice", List.of(nope)).status()).isEqualTo(409); // unknown seat
    assertThat(
            post(
                    "/api/events/" + nope + "/holds",
                    java.util.Map.of("userRef", "a", "seatIds", List.of(nope)))
                .status())
        .isEqualTo(404); // unknown event
    assertThat(confirm(nope).status()).isEqualTo(404);
    assertThat(get("/api/holds/" + nope).status()).isEqualTo(404);
    assertThat(get("/api/bookings/" + nope).status()).isEqualTo(404);
  }

  @Test
  void duplicateSeatIdsInOneRequestAreDeduplicated() {
    UUID seat = seatsOfRow("A").get(5);
    HoldView hold = holdSeatsOk("alice", List.of(seat, seat));
    assertThat(hold.seatIds()).containsExactly(seat);
  }

  @Test
  void partialOverlapConflictNamesExactlyTheContestedSeatsAndClaimsNothing() {
    List<UUID> row = seatsOfRow("A");
    UUID s1 = row.get(0);
    UUID s2 = row.get(1);
    UUID s3 = row.get(2);
    holdSeatsOk("alice", List.of(s1, s2));

    ApiResponse bob = holdSeats("bob", List.of(s2, s3));
    assertThat(bob.status()).isEqualTo(409);
    assertThat(bob.body()).contains(s2.toString()).doesNotContain(s3.toString());

    // All-or-nothing: the uncontested seat was NOT claimed by the failed request.
    String s3Status =
        jdbc.sql("SELECT status FROM seat WHERE id = :id")
            .param("id", s3)
            .query(String.class)
            .single();
    assertThat(s3Status).isEqualTo("AVAILABLE");
  }

  @Test
  void releaseReturnsSeatsAndIsIdempotent() {
    List<UUID> seats = seatsOfRow("A").subList(3, 5);
    HoldView hold = holdSeatsOk("alice", seats);

    assertThat(get("/api/events/" + EVENT + "/seats").body()).contains("HELD");
    assertThat(delete("/api/holds/" + hold.id()).status()).isEqualTo(204);
    assertThat(delete("/api/holds/" + hold.id()).status()).isEqualTo(204); // again: still 204

    assertThat(get("/api/events/" + EVENT + "/seats").body()).doesNotContain("HELD");
    assertThat(holdSeatsOk("bob", seats).seatIds()).containsExactlyInAnyOrderElementsOf(seats);
  }

  @Test
  void confirmedHoldCannotBeReleasedBackIntoThePool() {
    List<UUID> seats = seatsOfRow("A").subList(6, 8);
    HoldView hold = holdSeatsOk("alice", seats);
    confirmOk(hold.id());

    assertThat(delete("/api/holds/" + hold.id()).status()).isEqualTo(204); // no-op by design
    assertThat(
            jdbc.sql("SELECT count(*) FROM seat WHERE status = 'SOLD'").query(Long.class).single())
        .isEqualTo(2);
    assertThat(holdSeats("bob", seats).status()).isEqualTo(409);
  }
}

package com.jmammadov.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmammadov.seatres.application.SweepService;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.WaitlistStatus;
import com.jmammadov.seatres.domain.WaitlistView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The waitlist's promise, proven: freed seats go to waiters in strict arrival order, waiters
 * outrank direct claims (even on lapsed holds), and a sold seat leaves no zombies in the queue. The
 * scheduler is disabled here; the lapse path drives {@link SweepService} directly — grant
 * *liveness* rides the sweep by design (documented), while every correctness assertion holds
 * without it.
 */
class WaitlistIT extends AbstractApiIT {

  @Autowired SweepService sweepService;

  // --- helpers ---

  private ApiResponse joinWaitlist(String userRef, UUID seatId) {
    return post("/api/events/" + EVENT + "/waitlist", Map.of("userRef", userRef, "seatId", seatId));
  }

  private WaitlistView joinOk(String userRef, UUID seatId) {
    ApiResponse response = joinWaitlist(userRef, seatId);
    if (response.status() != 201) {
      throw new AssertionError(
          "Expected 201 join, got " + response.status() + ": " + response.body());
    }
    return parse(response, WaitlistView.class);
  }

  private WaitlistView entry(UUID entryId) {
    return parse(get("/api/waitlist/" + entryId), WaitlistView.class);
  }

  // --- tests ---

  @Test
  void fifoGrantOnRelease() {
    UUID seat = seatsOfRow("H").get(0);
    HoldView holder = holdSeatsOk("holder", List.of(seat));

    WaitlistView w1 = joinOk("w1", seat);
    WaitlistView w2 = joinOk("w2", seat);
    assertThat(w1.position()).isEqualTo(1);
    assertThat(w2.position()).isEqualTo(2);

    delete("/api/holds/" + holder.id());

    WaitlistView w1After = entry(w1.id());
    assertThat(w1After.status()).isEqualTo(WaitlistStatus.GRANTED);
    assertThat(w1After.grantedHoldId()).isNotNull();
    WaitlistView w2After = entry(w2.id());
    assertThat(w2After.status()).isEqualTo(WaitlistStatus.WAITING);
    assertThat(w2After.position()).isEqualTo(1); // moved up

    // The granted hold is a completely normal hold: w1 checks out with it.
    HoldView granted = parse(get("/api/holds/" + w1After.grantedHoldId()), HoldView.class);
    assertThat(granted.userRef()).isEqualTo("w1");
    assertThat(granted.seatIds()).containsExactly(seat);
    assertThat(confirm(granted.id()).status()).isEqualTo(201);

    // Seat sold: w2's wait is over, honestly.
    assertThat(entry(w2.id()).status()).isEqualTo(WaitlistStatus.CANCELLED);
    assertThat(waitersOnNonHeldSeats()).isZero();
  }

  @Test
  void waiterBeatsDirectClaimOnLapsedHold() {
    UUID seat = seatsOfRow("H").get(2);
    HoldView alice = holdSeatsOk("alice", List.of(seat));
    WaitlistView waiter = joinOk("waiter", seat);

    shiftHoldExpiry(alice.id(), -1);

    // The hold is lapsed and unswept — normally anyone could reclaim. Not with a waiter queued.
    assertThat(holdSeats("carol", List.of(seat)).status()).isEqualTo(409);

    sweepService.sweepOnce();

    WaitlistView granted = entry(waiter.id());
    assertThat(granted.status()).isEqualTo(WaitlistStatus.GRANTED);
    assertThat(holdSeats("carol", List.of(seat)).status()).isEqualTo(409); // now held by waiter
    assertThat(confirm(granted.grantedHoldId()).status()).isEqualTo(201);
    assertThat(doubleSoldSeatCount()).isZero();
  }

  @Test
  void cancelHeadThenReleaseGrantsNext() {
    UUID seat = seatsOfRow("H").get(4);
    HoldView holder = holdSeatsOk("holder", List.of(seat));
    WaitlistView w1 = joinOk("w1", seat);
    WaitlistView w2 = joinOk("w2", seat);

    assertThat(delete("/api/waitlist/" + w1.id()).status()).isEqualTo(204);
    assertThat(delete("/api/waitlist/" + w1.id()).status()).isEqualTo(204); // idempotent

    delete("/api/holds/" + holder.id());

    assertThat(entry(w1.id()).status()).isEqualTo(WaitlistStatus.CANCELLED);
    assertThat(entry(w2.id()).status()).isEqualTo(WaitlistStatus.GRANTED);
  }

  @Test
  void cancelLastWaiterThenReleasePoolsTheSeat() {
    UUID seat = seatsOfRow("H").get(6);
    HoldView holder = holdSeatsOk("holder", List.of(seat));
    WaitlistView only = joinOk("only", seat);
    delete("/api/waitlist/" + only.id());

    delete("/api/holds/" + holder.id());

    // No waiters left: back to the pool, claimable by anyone.
    assertThat(holdSeatsOk("stranger", List.of(seat)).seatIds()).containsExactly(seat);
    assertThat(waitersOnNonHeldSeats()).isZero();
  }

  @Test
  void confirmCancelsWaitersAndGrantedEntryCannotBeCancelled() {
    UUID seat = seatsOfRow("H").get(8);
    HoldView alice = holdSeatsOk("alice", List.of(seat));
    WaitlistView waiter = joinOk("waiter", seat);

    confirmOk(alice.id());
    assertThat(entry(waiter.id()).status()).isEqualTo(WaitlistStatus.CANCELLED);

    // And a GRANTED entry elsewhere refuses cancellation (the hold already exists).
    UUID seat2 = seatsOfRow("H").get(10);
    HoldView bob = holdSeatsOk("bob", List.of(seat2));
    WaitlistView w = joinOk("w", seat2);
    delete("/api/holds/" + bob.id());
    assertThat(entry(w.id()).status()).isEqualTo(WaitlistStatus.GRANTED);
    assertThat(delete("/api/waitlist/" + w.id()).status()).isEqualTo(409);
  }

  @Test
  void joinValidation() {
    UUID availableSeat = seatsOfRow("I").get(0);
    assertThat(joinWaitlist("u", availableSeat).status()).isEqualTo(409); // claim it instead
    assertThat(joinWaitlist("u", UUID.randomUUID()).status()).isEqualTo(404);

    HoldView alice = holdSeatsOk("alice", List.of(availableSeat));
    confirmOk(alice.id());
    ApiResponse soldJoin = joinWaitlist("u", availableSeat);
    assertThat(soldJoin.status()).isEqualTo(409);
    assertThat(soldJoin.body()).contains("sold");

    UUID heldSeat = seatsOfRow("I").get(2);
    holdSeatsOk("bob", List.of(heldSeat));
    joinOk("u", heldSeat);
    assertThat(joinWaitlist("u", heldSeat).status()).isEqualTo(409); // no duplicate live entries
  }

  @Test
  void concurrentJoinStormExactlyOneGrantInArrivalOrder() throws Exception {
    UUID seat = seatsOfRow("J").get(0);
    HoldView holder = holdSeatsOk("holder", List.of(seat));

    // 30 join attempts from 10 users (3 each): the partial unique index must dedup.
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> futures =
          IntStream.range(0, 30)
              .mapToObj(i -> executor.submit(() -> joinWaitlist("user-" + (i % 10), seat).status()))
              .toList();
      for (Future<Integer> f : futures) {
        assertThat(f.get()).isIn(201, 409);
      }
    }

    long liveEntries =
        jdbc.sql("SELECT count(*) FROM waitlist WHERE seat_id = :s AND status = 'WAITING'")
            .param("s", seat)
            .query(Long.class)
            .single();
    assertThat(liveEntries).isEqualTo(10);

    delete("/api/holds/" + holder.id());

    // Exactly one GRANTED, and it is the minimum committed position — strict arrival order.
    List<String> grantedUsers =
        jdbc.sql("SELECT user_ref FROM waitlist WHERE seat_id = :s AND status = 'GRANTED'")
            .param("s", seat)
            .query(String.class)
            .list();
    assertThat(grantedUsers).hasSize(1);
    String minPositionUser =
        jdbc.sql(
                "SELECT user_ref FROM waitlist WHERE seat_id = :s AND status <> 'CANCELLED'"
                    + " ORDER BY position LIMIT 1")
            .param("s", seat)
            .query(String.class)
            .single();
    assertThat(grantedUsers.get(0)).isEqualTo(minPositionUser);
    assertThat(waitersOnNonHeldSeats()).isZero();
  }
}

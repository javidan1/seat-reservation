package com.jmammadov.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmammadov.seatres.domain.HoldView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The hidden hard part of this brief: the race between a confirm and the reclaim of a lapsed hold.
 * The sweeper is DISABLED for all of these tests — that is the point. Expiry must be enforced by
 * the transition statements themselves, not by a background job that might not run.
 */
class ExpiryConfirmRaceIT extends AbstractApiIT {

  @Test
  void confirmOfLapsedHoldIsRejected() {
    List<UUID> seats = seatsOfRow("B").subList(0, 2);
    HoldView hold = holdSeatsOk("alice", seats);

    shiftHoldExpiry(hold.id(), -1);

    ApiResponse response = confirm(hold.id());
    assertThat(response.status()).isEqualTo(410);
    assertThat(response.body()).contains("hold_no_longer_valid");
    assertThat(doubleSoldSeatCount()).isZero();
    assertThat(soldSeatsWithoutBooking()).isZero();
  }

  @Test
  void lapsedSeatsAreClaimableWithoutTheSweeper() {
    List<UUID> seats = seatsOfRow("B").subList(2, 4);
    HoldView alice = holdSeatsOk("alice", seats);
    shiftHoldExpiry(alice.id(), -1);

    // No sweeper ran; the seats still read HELD in the table. Bob must win them anyway.
    HoldView bob = holdSeatsOk("bob", seats);
    assertThat(bob.seatIds()).containsExactlyInAnyOrderElementsOf(seats);
    assertThat(confirmOk(bob.id()).seatIds()).containsExactlyInAnyOrderElementsOf(seats);

    // Alice's hold now reads EXPIRED (lazy truth), and her confirm is cleanly refused.
    HoldView aliceAfter = parse(get("/api/holds/" + alice.id()), HoldView.class);
    assertThat(aliceAfter.status().name()).isEqualTo("EXPIRED");
    assertThat(confirm(alice.id()).status()).isEqualTo(410);

    assertThat(doubleSoldSeatCount()).isZero();
    assertThat(partiallyConfirmedHolds()).isZero();
  }

  /**
   * Fires confirm and a competing claim simultaneously at the expiry boundary, many rounds. The
   * database, not luck, must guarantee that both never win the same seat.
   */
  @Test
  void confirmAndReclaimRacingAtTheBoundaryNeverBothWin() throws Exception {
    List<UUID> seats = seatsOfRow("C");
    int rounds = Math.min(seats.size(), 20);
    int aliceWins = 0;
    int bobWins = 0;

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < rounds; round++) {
        final int r = round;
        UUID seat = seats.get(round);
        HoldView alice = holdSeatsOk("alice-" + round, List.of(seat));
        // Expiry lands ~25ms in the future: both racers arrive right at the boundary.
        shiftHoldExpiry(alice.id(), 0.025);

        CountDownLatch start = new CountDownLatch(1);
        Future<ApiResponse> confirmF =
            pool.submit(
                () -> {
                  start.await();
                  return confirm(alice.id());
                });
        Future<ApiResponse> claimF =
            pool.submit(
                () -> {
                  start.await();
                  return holdSeats("bob-" + r, List.of(seat));
                });
        start.countDown();

        boolean aliceWon = confirmF.get().is2xx();
        boolean bobWon = claimF.get().is2xx();

        assertThat(aliceWon && bobWon)
            .as("round %d: confirm and reclaim must never both win seat %s", round, seat)
            .isFalse();
        if (aliceWon) {
          aliceWins++;
        }
        if (bobWon) {
          bobWins++;
        }
      }
    }

    // Sanity: the race was actually exercised, not decided one way by accident of timing.
    assertThat(aliceWins + bobWins).as("some rounds should produce a winner").isPositive();
    assertThat(doubleSoldSeatCount()).isZero();
    assertThat(soldSeatsWithoutBooking()).isZero();
    assertThat(partiallyConfirmedHolds()).isZero();
  }
}

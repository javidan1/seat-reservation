package com.jmammadov.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmammadov.seatres.domain.HoldView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A hold whose seats were partially reclaimed must never confirm a subset — the nastiest anomaly in
 * this domain (a customer charged for one of their two seats).
 */
class PartialConfirmGuardIT extends AbstractApiIT {

  @Test
  void confirmRollsBackEntirelyWhenOneSeatWasReclaimed() {
    List<UUID> seats = seatsOfRow("D").subList(0, 2);
    UUID stolen = seats.get(0);
    UUID remaining = seats.get(1);

    HoldView alice = holdSeatsOk("alice", seats);
    shiftHoldExpiry(alice.id(), -1);

    // Bob reclaims only ONE of Alice's lapsed seats.
    HoldView bob = holdSeatsOk("bob", List.of(stolen));
    assertThat(bob.seatIds()).containsExactly(stolen);

    // Alice's confirm must fail atomically: no booking, and the remaining seat is NOT sold.
    ApiResponse response = confirm(alice.id());
    assertThat(response.status()).isEqualTo(410);

    assertThat(
            jdbc.sql("SELECT count(*) FROM booking WHERE user_ref = 'alice'")
                .query(Long.class)
                .single())
        .isZero();
    String remainingStatus =
        jdbc.sql("SELECT status FROM seat WHERE id = :id")
            .param("id", remaining)
            .query(String.class)
            .single();
    assertThat(remainingStatus).isNotEqualTo("SOLD");

    // And the world remains fully consistent: bob can still buy the seat he reclaimed.
    assertThat(confirm(bob.id()).status()).isEqualTo(201);
    assertThat(doubleSoldSeatCount()).isZero();
    assertThat(partiallyConfirmedHolds()).isZero();
  }
}

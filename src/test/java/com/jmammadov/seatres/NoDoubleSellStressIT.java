package com.jmammadov.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.HoldView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headline evidence: many buyers fighting over the same seat block, with short TTLs so holds
 * lapse and are reclaimed mid-run, and the sweeper racing everyone. After the storm, the database
 * must show zero double-sells, zero partial confirms, and a booking for exactly every 2xx confirm
 * the clients saw.
 *
 * <p>{@code shortStress} runs in CI; {@code fullStress} is the @Tag("load") full-size run
 * (./gradlew stressTest, optionally -Pstress.threads=400).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "seatres.hold-ttl=2s",
      "seatres.sweeper.enabled=true",
      "seatres.sweeper.interval=200ms"
    })
class NoDoubleSellStressIT extends AbstractApiIT {

  @Test
  void shortStress() throws Exception {
    runStorm(40, 12);
  }

  @Test
  @Tag("load")
  void fullStress() throws Exception {
    runStorm(Integer.getInteger("stress.threads", 200), 30);
  }

  private void runStorm(int workers, int attemptsPerWorker) throws Exception {
    List<UUID> allSeats =
        jdbc.sql("SELECT id FROM seat WHERE event_id = :e ORDER BY row_label, seat_number")
            .param("e", EVENT)
            .query(UUID.class)
            .list();
    List<UUID> hotBlock = allSeats.subList(0, 30); // 80% of picks fight over these

    var ledger = new ConcurrentHashMap<UUID, Set<UUID>>(); // seat -> booking ids seen by clients
    var holds = new LongAdder();
    var conflicts = new LongAdder();
    var confirms = new LongAdder();
    var gone = new LongAdder();
    var releases = new LongAdder();
    var abandons = new LongAdder();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Object>> futures =
          IntStream.range(0, workers)
              .mapToObj(
                  worker ->
                      executor.submit(
                          () -> {
                            storm(
                                worker,
                                attemptsPerWorker,
                                allSeats,
                                hotBlock,
                                ledger,
                                holds,
                                conflicts,
                                confirms,
                                gone,
                                releases,
                                abandons);
                            return null;
                          }))
              .toList();
      for (Future<?> future : futures) {
        future.get();
      }
    }

    // Client-side ledger: no seat was ever part of two successful confirms.
    ledger.forEach(
        (seat, bookings) ->
            assertThat(bookings)
                .as("seat %s must not appear in more than one booking", seat)
                .hasSizeLessThanOrEqualTo(1));

    // Database invariants.
    assertThat(doubleSoldSeatCount()).as("seats booked twice").isZero();
    assertThat(soldSeatsWithoutBooking()).as("SOLD seats with no booking").isZero();
    assertThat(partiallyConfirmedHolds()).as("holds that sold a subset of their seats").isZero();
    long bookingSeatsOnUnsold =
        jdbc.sql(
                "SELECT count(*) FROM booking_seat bs JOIN seat s ON s.id = bs.seat_id"
                    + " WHERE s.status <> 'SOLD'")
            .query(Long.class)
            .single();
    assertThat(bookingSeatsOnUnsold).as("booking rows pointing at unsold seats").isZero();
    long dbBookings = jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single();
    assertThat(dbBookings)
        .as("every client-visible confirm has exactly one booking row")
        .isEqualTo(confirms.sum());

    System.out.printf(
        "STRESS %d workers x %d attempts: holds=%d conflicts409=%d confirms=%d gone410=%d"
            + " releases=%d abandons=%d -> double-sells=0 (verified)%n",
        workers,
        attemptsPerWorker,
        holds.sum(),
        conflicts.sum(),
        confirms.sum(),
        gone.sum(),
        releases.sum(),
        abandons.sum());
  }

  private void storm(
      int worker,
      int attempts,
      List<UUID> allSeats,
      List<UUID> hotBlock,
      ConcurrentHashMap<UUID, Set<UUID>> ledger,
      LongAdder holds,
      LongAdder conflicts,
      LongAdder confirms,
      LongAdder gone,
      LongAdder releases,
      LongAdder abandons) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int attempt = 0; attempt < attempts; attempt++) {
      Set<UUID> pick = new HashSet<>();
      int size = 1 + random.nextInt(3);
      while (pick.size() < size) {
        List<UUID> pool = random.nextInt(100) < 80 ? hotBlock : allSeats;
        pick.add(pool.get(random.nextInt(pool.size())));
      }
      ApiResponse held = holdSeats("worker-" + worker, List.copyOf(pick));
      if (held.status() != 201) {
        conflicts.increment();
        continue;
      }
      holds.increment();
      HoldView hold = parse(held, HoldView.class);
      int dice = random.nextInt(100);
      if (dice < 60) {
        ApiResponse confirmed = confirm(hold.id());
        if (confirmed.status() == 201) {
          confirms.increment();
          Booking booking = parse(confirmed, Booking.class);
          for (UUID seat : booking.seatIds()) {
            ledger.computeIfAbsent(seat, k -> ConcurrentHashMap.newKeySet()).add(booking.id());
          }
        } else {
          gone.increment();
        }
      } else if (dice < 80) {
        delete("/api/holds/" + hold.id());
        releases.increment();
      } else {
        abandons.increment(); // let the TTL lapse; sweeper or a reclaim will free the seats
      }
    }
  }
}

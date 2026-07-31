package com.jmammadov.seatres.application.port;

import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.SeatChange;
import com.jmammadov.seatres.domain.SeatView;
import com.jmammadov.seatres.domain.WaitlistView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every mutating method here is a single conditional SQL statement (predicate CAS) — the row counts
 * and RETURNING clauses are the correctness mechanism, not an implementation detail. See
 * DECISIONS.md.
 */
public interface ReservationRepository {

  // Reads
  List<EventInfo> listEvents();

  Optional<EventInfo> findEvent(UUID eventId);

  List<SeatView> seatMap(UUID eventId);

  /** Of the requested seats, the ones claimable right now (used to explain a 409). */
  List<UUID> claimableNow(UUID eventId, List<UUID> seatIds);

  Optional<HoldView> findHold(UUID holdId);

  Optional<Booking> findBooking(UUID bookingId);

  Optional<Booking> findBookingByHold(UUID holdId);

  // Transitions (each one conditional statement; callers assert row counts)
  void insertHold(UUID holdId, UUID eventId, String userRef, int seatCount, double ttlSeconds);

  /**
   * AVAILABLE→HELD for every requested seat that is claimable, including seats whose previous hold
   * has lapsed — correctness must not depend on the sweeper. Seats with live waiters are NOT
   * claimable: the waitlist has first claim. Locks rows in sorted id order.
   */
  List<SeatChange> claimSeats(UUID holdId, UUID eventId, List<UUID> seatIds, double ttlSeconds);

  /** hold ACTIVE→CONFIRMED iff unexpired; the serialization point for confirm. */
  Optional<HoldCas> casConfirmHold(UUID holdId);

  /** hold ACTIVE→RELEASED; returns affected row count (0 or 1). */
  int casReleaseHold(UUID holdId);

  /** seats HELD→SOLD for this hold. Caller must assert count == seatCount or roll back. */
  List<SeatChange> markSeatsSold(UUID holdId);

  void insertBooking(UUID bookingId, UUID holdId, UUID eventId, String userRef);

  /** Links this hold's SOLD seats to the booking; caller asserts count == seatCount. */
  int insertBookingSeats(UUID bookingId, UUID holdId);

  /** hold ACTIVE→EXPIRED for due holds (SKIP LOCKED); returns the expired hold ids. */
  List<UUID> expireDueHolds(int limit);

  // Seat routing (the freeing routine; all called inside the transaction that won the hold CAS)

  /** Ids of the seats still linked to this hold, in id order (matches claim's lock order). */
  List<UUID> seatIdsOfHold(UUID holdId);

  /**
   * Locks the freed seat (FOR UPDATE) iff it still belongs to the old hold; 0 rows means a direct
   * claim lazily reclaimed it first — nothing to route. The seat row is the serialization point
   * between freeing, joining, and claiming.
   */
  int lockFreedSeat(UUID seatId, UUID oldHoldId);

  /** Seat back to the pool (no waiters). */
  SeatChange poolSeat(UUID seatId);

  /** Seat handed to the granted waiter's fresh hold; stays HELD with a new expiry. */
  SeatChange assignSeatToHold(UUID seatId, UUID holdId, double ttlSeconds);

  /** Orphan cleanup when a grant CAS lost to a concurrent cancel. */
  void deleteHold(UUID holdId);

  // Waitlist

  /**
   * Locks the seat row (FOR SHARE) and returns its post-lock state. Joining must serialize against
   * the freeing routine, or a join racing a release strands the seat as AVAILABLE-with-waiters —
   * unclaimable forever under the waiter-priority claim predicate.
   */
  Optional<LockedSeat> lockSeatForJoin(UUID seatId);

  void insertWaitlistEntry(UUID entryId, UUID seatId, UUID eventId, String userRef);

  Optional<WaitlistView> findWaitlistEntry(UUID entryId);

  /**
   * Head of the queue: minimum WAITING position. Plain read — no lock, no SKIP LOCKED (fairness).
   */
  Optional<HeadWaiter> headWaiter(UUID seatId);

  /** WAITING→GRANTED with the hold that delivers the promise; 0 rows = concurrently cancelled. */
  int casGrantWaitlist(UUID entryId, UUID holdId);

  /** WAITING→CANCELLED; 0 rows = not cancellable (granted, already cancelled, or unknown). */
  int casCancelWaitlist(UUID entryId);

  /** Sold seats keep no queue: WAITING→CANCELLED for all entries on these seats. */
  int cancelWaitingOnSeats(List<UUID> seatIds);

  record HoldCas(int seatCount, UUID eventId, String userRef) {}

  record HeadWaiter(UUID entryId, UUID eventId, String userRef) {}

  record LockedSeat(String status, UUID eventId) {}
}

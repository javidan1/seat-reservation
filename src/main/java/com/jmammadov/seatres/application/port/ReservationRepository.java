package com.jmammadov.seatres.application.port;

import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.SeatChange;
import com.jmammadov.seatres.domain.SeatView;
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
   * has lapsed — correctness must not depend on the sweeper. Locks rows in sorted id order.
   */
  List<SeatChange> claimSeats(UUID holdId, UUID eventId, List<UUID> seatIds, double ttlSeconds);

  /** hold ACTIVE→CONFIRMED iff unexpired; the serialization point for confirm. */
  Optional<HoldCas> casConfirmHold(UUID holdId);

  /** hold ACTIVE→RELEASED; returns affected row count (0 or 1). */
  int casReleaseHold(UUID holdId);

  /** seats HELD→SOLD for this hold. Caller must assert count == seatCount or roll back. */
  List<SeatChange> markSeatsSold(UUID holdId);

  /** seats HELD→AVAILABLE for this hold. */
  List<SeatChange> releaseSeats(UUID holdId);

  void insertBooking(UUID bookingId, UUID holdId, UUID eventId, String userRef);

  /** Links this hold's SOLD seats to the booking; caller asserts count == seatCount. */
  int insertBookingSeats(UUID bookingId, UUID holdId);

  /** hold ACTIVE→EXPIRED for due holds (SKIP LOCKED); returns the expired hold ids. */
  List<UUID> expireDueHolds(int limit);

  List<SeatChange> releaseSeatsOfHolds(List<UUID> holdIds);

  record HoldCas(int seatCount, UUID eventId, String userRef) {}
}

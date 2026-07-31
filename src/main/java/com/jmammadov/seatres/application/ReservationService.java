package com.jmammadov.seatres.application;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.ConfirmResult;
import com.jmammadov.seatres.domain.EventNotFoundException;
import com.jmammadov.seatres.domain.HoldInvalidatedException;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.SeatChange;
import com.jmammadov.seatres.domain.SeatsUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All lifecycle transitions live here, each in one explicit transaction. The rules this class
 * enforces (rather than hopes for):
 *
 * <ul>
 *   <li>holds are all-or-nothing — a partial claim rolls back entirely,
 *   <li>a confirm either sells every seat of the hold or nothing (rowcount guard),
 *   <li>expiry is decided by the database clock inside each statement, never by this JVM.
 * </ul>
 */
@Service
public class ReservationService {

  static final int MAX_SEATS_PER_HOLD = 8;
  static final int MAX_USER_REF_LENGTH = 64;

  private final ReservationRepository repo;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;
  private final SeatresProperties properties;
  private final SeatReleaseService seatRelease;

  public ReservationService(
      ReservationRepository repo,
      TransactionTemplate tx,
      ApplicationEventPublisher events,
      SeatresProperties properties,
      SeatReleaseService seatRelease) {
    this.repo = repo;
    this.tx = tx;
    this.events = events;
    this.properties = properties;
    this.seatRelease = seatRelease;
  }

  public HoldView hold(UUID eventId, String userRef, List<UUID> seatIds) {
    List<UUID> requested = List.copyOf(new TreeSet<>(seatIds));
    if (requested.isEmpty() || requested.size() > MAX_SEATS_PER_HOLD) {
      throw new IllegalArgumentException(
          "A hold must cover between 1 and " + MAX_SEATS_PER_HOLD + " seats");
    }
    requireValidUserRef(userRef);
    repo.findEvent(eventId).orElseThrow(() -> new EventNotFoundException(eventId));

    double ttlSeconds = properties.holdTtl().toSeconds();
    // Sorted FOR UPDATE locking makes deadlocks near-impossible; one retry covers the rest.
    PessimisticLockingFailureException deadlock = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return tx.execute(
            status -> {
              UUID holdId = UUID.randomUUID();
              repo.insertHold(holdId, eventId, userRef, requested.size(), ttlSeconds);
              var claimed = repo.claimSeats(holdId, eventId, requested, ttlSeconds);
              if (claimed.size() != requested.size()) {
                throw new SeatsUnavailableException(eventId, requested);
              }
              events.publishEvent(new SeatChangedEvent(claimed));
              return repo.findHold(holdId).orElseThrow();
            });
      } catch (PessimisticLockingFailureException e) {
        deadlock = e;
      }
    }
    throw deadlock;
  }

  public ConfirmResult confirm(UUID holdId) {
    return tx.execute(
        status -> {
          var cas = repo.casConfirmHold(holdId);
          if (cas.isPresent()) {
            var sold = repo.markSeatsSold(holdId);
            if (sold.size() != cas.get().seatCount()) {
              // A seat was reclaimed after this hold lapsed: never sell a subset.
              throw new HoldInvalidatedException(holdId);
            }
            UUID bookingId = UUID.randomUUID();
            repo.insertBooking(bookingId, holdId, cas.get().eventId(), cas.get().userRef());
            if (repo.insertBookingSeats(bookingId, holdId) != cas.get().seatCount()) {
              throw new HoldInvalidatedException(holdId);
            }
            // Sold seats keep no queue: anyone still waiting learns it's over.
            repo.cancelWaitingOnSeats(sold.stream().map(SeatChange::seatId).toList());
            events.publishEvent(new SeatChangedEvent(sold));
            return new ConfirmResult.Confirmed(repo.findBooking(bookingId).orElseThrow());
          }
          Optional<Booking> existing = repo.findBookingByHold(holdId);
          if (existing.isPresent()) {
            return new ConfirmResult.AlreadyConfirmed(existing.get());
          }
          return repo.findHold(holdId)
              .<ConfirmResult>map(h -> new ConfirmResult.HoldGone(h.status()))
              .orElseGet(ConfirmResult.NotFound::new);
        });
  }

  /** Idempotent: releasing an unknown, expired, or already-released hold is a no-op. */
  public void release(UUID holdId) {
    tx.executeWithoutResult(
        status -> {
          if (repo.casReleaseHold(holdId) == 1) {
            events.publishEvent(new SeatChangedEvent(seatRelease.routeFreedSeats(holdId)));
          }
        });
  }

  public Optional<HoldView> hold(UUID holdId) {
    return repo.findHold(holdId);
  }

  /** Caller-supplied identity is untrusted input: bounded, never just non-blank. */
  public static void requireValidUserRef(String userRef) {
    if (userRef == null || userRef.isBlank()) {
      throw new IllegalArgumentException("userRef is required");
    }
    if (userRef.length() > MAX_USER_REF_LENGTH) {
      throw new IllegalArgumentException(
          "userRef must be at most " + MAX_USER_REF_LENGTH + " characters");
    }
  }

  public Optional<Booking> booking(UUID bookingId) {
    return repo.findBooking(bookingId);
  }
}

package com.seatres.application;

import com.seatres.application.port.ReservationRepository;
import com.seatres.domain.EventNotFoundException;
import com.seatres.domain.SeatNotFoundException;
import com.seatres.domain.WaitlistJoinRejectedException;
import com.seatres.domain.WaitlistJoinRejectedException.Reason;
import com.seatres.domain.WaitlistStatus;
import com.seatres.domain.WaitlistView;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Joining locks the seat row (FOR SHARE) and trusts only the post-lock status. Without that, a join
 * racing a release can commit a WAITING entry against a seat that just went AVAILABLE — which the
 * waiter-priority claim predicate would then freeze forever. The seat row is the serialization
 * point; this is the waitlist's version of the hold-row CAS.
 */
@Service
public class WaitlistService {

  private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

  private final ReservationRepository repo;
  private final TransactionTemplate tx;

  public WaitlistService(
      ReservationRepository repo, TransactionTemplate tx, SeatresProperties properties) {
    this.repo = repo;
    this.tx = tx;
    if (!properties.sweeper().enabled()) {
      // Correctness survives without the sweeper; waitlist grants for LAPSED holds do not.
      log.warn(
          "Waitlist is active but the sweeper is disabled: seats of lapsed holds with waiters"
              + " will not be granted until a sweep runs.");
    }
  }

  public WaitlistView join(UUID eventId, UUID seatId, String userRef) {
    ReservationService.requireValidUserRef(userRef);
    repo.findEvent(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    return tx.execute(
        status -> {
          var seat =
              repo.lockSeatForJoin(seatId).orElseThrow(() -> new SeatNotFoundException(seatId));
          if (!seat.eventId().equals(eventId)) {
            throw new SeatNotFoundException(seatId);
          }
          switch (seat.status()) {
            case "AVAILABLE" -> throw new WaitlistJoinRejectedException(Reason.SEAT_AVAILABLE);
            case "SOLD" -> throw new WaitlistJoinRejectedException(Reason.SEAT_SOLD);
            default -> {} // HELD: joinable
          }
          UUID entryId = UUID.randomUUID();
          try {
            repo.insertWaitlistEntry(entryId, seatId, eventId, userRef);
          } catch (DuplicateKeyException e) {
            throw new WaitlistJoinRejectedException(Reason.ALREADY_WAITING);
          }
          return repo.findWaitlistEntry(entryId).orElseThrow();
        });
  }

  public Optional<WaitlistView> get(UUID entryId) {
    return repo.findWaitlistEntry(entryId);
  }

  /**
   * Idempotent for repeat cancels; a GRANTED entry is not cancellable (the hold already exists).
   */
  public CancelOutcome cancel(UUID entryId) {
    if (repo.casCancelWaitlist(entryId) == 1) {
      return CancelOutcome.CANCELLED;
    }
    return repo.findWaitlistEntry(entryId)
        .map(
            entry ->
                entry.status() == WaitlistStatus.GRANTED
                    ? CancelOutcome.ALREADY_GRANTED
                    : CancelOutcome.CANCELLED) // already cancelled: idempotent success
        .orElse(CancelOutcome.NOT_FOUND);
  }

  public enum CancelOutcome {
    CANCELLED,
    ALREADY_GRANTED,
    NOT_FOUND
  }
}

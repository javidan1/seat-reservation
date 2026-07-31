package com.jmammadov.seatres.application;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.SeatChange;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The freeing routine: routes every seat of a just-freed hold either to the head of that seat's
 * waitlist (as a fresh, normal 1-seat hold) or back to the pool.
 *
 * <p>Must run inside the transaction that already won the hold-row CAS (release or expiry) — that
 * CAS guarantees exactly one router per hold. Each seat is locked FOR UPDATE before its waitlist is
 * read: the seat row is the serialization point against concurrent joins (FOR SHARE) and lazy
 * reclaims, which is what makes a stranded AVAILABLE-with-waiters state unreachable. Seats are
 * processed in id order, matching the claim statement's lock order, so the two multi-lockers cannot
 * deadlock.
 */
@Service
public class SeatReleaseService {

  private final ReservationRepository repo;
  private final SeatresProperties properties;

  public SeatReleaseService(ReservationRepository repo, SeatresProperties properties) {
    this.repo = repo;
    this.properties = properties;
  }

  /** Routes all seats still linked to the freed hold. Caller owns the transaction. */
  public List<SeatChange> routeFreedSeats(UUID freedHoldId) {
    List<SeatChange> changes = new ArrayList<>();
    for (UUID seatId : repo.seatIdsOfHold(freedHoldId)) {
      if (repo.lockFreedSeat(seatId, freedHoldId) == 0) {
        continue; // a direct claim lazily reclaimed it first — nothing left to route
      }
      changes.add(grantOrPool(seatId));
    }
    return changes;
  }

  private SeatChange grantOrPool(UUID seatId) {
    double ttlSeconds = properties.holdTtl().toSeconds();
    while (true) {
      var head = repo.headWaiter(seatId);
      if (head.isEmpty()) {
        return repo.poolSeat(seatId);
      }
      // Hold first (the entry's granted_hold_id references it), then the entry CAS.
      UUID holdId = UUID.randomUUID();
      repo.insertHold(holdId, head.get().eventId(), head.get().userRef(), 1, ttlSeconds);
      if (repo.casGrantWaitlist(head.get().entryId(), holdId) == 0) {
        repo.deleteHold(holdId); // lost to a concurrent cancel; try the next waiter
        continue;
      }
      return repo.assignSeatToHold(seatId, holdId, ttlSeconds);
    }
  }
}

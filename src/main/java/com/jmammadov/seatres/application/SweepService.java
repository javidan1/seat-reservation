package com.jmammadov.seatres.application;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.SeatChange;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One sweep pass: expire due holds (SKIP LOCKED — safe on many instances) and route their seats,
 * atomically. Expiry and routing MUST share a transaction: an EXPIRED hold whose waiter-watched
 * seats were never routed would be untouchable forever (the sweeper only scans ACTIVE, and waiter
 * priority blocks lazy reclaim).
 *
 * <p>Always a bean — tests drive it directly; the schedule wrapper is {@link ExpirySweeper}.
 */
@Service
public class SweepService {

  private static final int BATCH_SIZE = 100;

  private final ReservationRepository repo;
  private final SeatReleaseService seatRelease;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  public SweepService(
      ReservationRepository repo,
      SeatReleaseService seatRelease,
      TransactionTemplate tx,
      ApplicationEventPublisher events) {
    this.repo = repo;
    this.seatRelease = seatRelease;
    this.tx = tx;
    this.events = events;
  }

  /** Returns the number of holds expired in this pass. */
  public int sweepOnce() {
    Integer expired =
        tx.execute(
            status -> {
              List<UUID> due = repo.expireDueHolds(BATCH_SIZE);
              if (due.isEmpty()) {
                return 0;
              }
              List<SeatChange> changes = new ArrayList<>();
              for (UUID holdId : due) {
                changes.addAll(seatRelease.routeFreedSeats(holdId));
              }
              events.publishEvent(new SeatChangedEvent(changes));
              return due.size();
            });
    return expired == null ? 0 : expired;
  }
}

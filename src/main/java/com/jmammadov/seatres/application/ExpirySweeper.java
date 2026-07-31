package com.jmammadov.seatres.application;

import com.jmammadov.seatres.application.port.ReservationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases seats of lapsed holds so the seat map and SSE stream stay fresh. Correctness never
 * depends on this running: every transition re-checks expiry in its own WHERE clause, and claims
 * reclaim expired-held seats directly. Safe to run on many instances at once (SKIP LOCKED + CAS).
 */
@Component
@ConditionalOnProperty(
    prefix = "seatres.sweeper",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ExpirySweeper {

  private static final int BATCH_SIZE = 100;

  private final ReservationRepository repo;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  public ExpirySweeper(
      ReservationRepository repo, TransactionTemplate tx, ApplicationEventPublisher events) {
    this.repo = repo;
    this.tx = tx;
    this.events = events;
  }

  @Scheduled(fixedDelayString = "${seatres.sweeper.interval:1s}")
  public void sweep() {
    tx.executeWithoutResult(
        status -> {
          List<UUID> expired = repo.expireDueHolds(BATCH_SIZE);
          if (!expired.isEmpty()) {
            events.publishEvent(new SeatChangedEvent(repo.releaseSeatsOfHolds(expired)));
          }
        });
  }
}

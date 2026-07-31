package com.seatres.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedule wrapper around {@link SweepService}. For plain holds the sweeper is still only UI
 * freshness — every transition re-checks expiry itself. With the waitlist it gains one liveness
 * duty: a lapsed hold on a waiter-watched seat is claimable by no one until a sweep routes it
 * (waiter priority blocks lazy reclaim). Correctness — no double-sell, no partial confirm — never
 * depends on it either way.
 */
@Component
@ConditionalOnProperty(
    prefix = "seatres.sweeper",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ExpirySweeper {

  private final SweepService sweep;

  public ExpirySweeper(SweepService sweep) {
    this.sweep = sweep;
  }

  @Scheduled(fixedDelayString = "${seatres.sweeper.interval:1s}")
  public void sweep() {
    sweep.sweepOnce();
  }
}

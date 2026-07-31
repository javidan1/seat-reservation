package com.jmammadov.seatres.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A seat as the outside world sees it: {@code status} is the effective status — a seat whose hold
 * has lapsed reads AVAILABLE even before the sweeper has released the row.
 */
public record SeatView(
    UUID id,
    String rowLabel,
    int seatNumber,
    SeatStatus status,
    Instant holdExpiresAt,
    long version) {}

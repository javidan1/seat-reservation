package com.jmammadov.seatres.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One committed seat-state transition, as pushed to live clients. {@code version} increases on
 * every transition; clients must drop changes older than the version they already have.
 */
public record SeatChange(
    UUID eventId,
    UUID seatId,
    String rowLabel,
    int seatNumber,
    SeatStatus status,
    Instant holdExpiresAt,
    long version) {}

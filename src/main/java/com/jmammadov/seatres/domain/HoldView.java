package com.jmammadov.seatres.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A hold as the outside world sees it: {@code status} is effective (an ACTIVE hold past its expiry
 * reads EXPIRED), and {@code remainingSeconds} is computed against the database clock so clients
 * never need a synchronized clock of their own.
 */
public record HoldView(
    UUID id,
    UUID eventId,
    String userRef,
    HoldStatus status,
    List<UUID> seatIds,
    Instant expiresAt,
    long remainingSeconds) {}

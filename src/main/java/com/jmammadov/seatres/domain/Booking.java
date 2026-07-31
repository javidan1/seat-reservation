package com.jmammadov.seatres.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Booking(
    UUID id, UUID holdId, UUID eventId, String userRef, Instant confirmedAt, List<UUID> seatIds) {}

package com.seatres.domain;

import java.util.UUID;

/**
 * A waitlist entry as the outside world sees it. {@code position} is the live queue position (1 =
 * next in line) while WAITING, 0 otherwise. {@code grantedHoldId} is set once GRANTED — the waiter
 * adopts that hold and checks out normally.
 */
public record WaitlistView(
    UUID id,
    UUID seatId,
    String seatLabel,
    UUID eventId,
    String userRef,
    WaitlistStatus status,
    long position,
    UUID grantedHoldId) {}

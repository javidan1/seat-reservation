package com.seatres.domain;

/**
 * GRANTED is terminal: the queue's promise — first claim on the freed seat — was delivered as a
 * normal hold. Whether the waiter confirms it is the ordinary hold game from there.
 */
public enum WaitlistStatus {
  WAITING,
  GRANTED,
  CANCELLED
}

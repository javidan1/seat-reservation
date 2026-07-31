package com.seatres.domain;

/** Outcome of attempting to confirm a hold. Exactly one of these happens, atomically. */
public sealed interface ConfirmResult {

  /** The hold was ACTIVE and unexpired; every seat transitioned to SOLD. */
  record Confirmed(Booking booking) implements ConfirmResult {}

  /** The hold was already confirmed earlier; the original booking is returned (idempotency). */
  record AlreadyConfirmed(Booking booking) implements ConfirmResult {}

  /** The hold exists but can no longer be confirmed (expired or released). */
  record HoldGone(HoldStatus statusAtFailure) implements ConfirmResult {}

  /** No such hold. */
  record NotFound() implements ConfirmResult {}
}

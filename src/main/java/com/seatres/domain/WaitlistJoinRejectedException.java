package com.seatres.domain;

/** The seat cannot be waited on right now (available, sold, or the user already queues). */
public class WaitlistJoinRejectedException extends RuntimeException {

  public enum Reason {
    SEAT_AVAILABLE,
    SEAT_SOLD,
    ALREADY_WAITING
  }

  private final Reason reason;

  public WaitlistJoinRejectedException(Reason reason) {
    super(
        switch (reason) {
          case SEAT_AVAILABLE -> "Seat is available — claim it directly instead of waiting";
          case SEAT_SOLD -> "Seat is sold; it will not come back";
          case ALREADY_WAITING -> "Already waiting for this seat";
        });
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}

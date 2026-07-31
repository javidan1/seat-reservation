package com.jmammadov.seatres.domain;

import java.util.UUID;

public class SeatNotFoundException extends RuntimeException {

  public SeatNotFoundException(UUID seatId) {
    super("No such seat: " + seatId);
  }
}

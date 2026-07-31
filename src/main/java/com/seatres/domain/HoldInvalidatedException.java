package com.seatres.domain;

import java.util.UUID;

/**
 * Thrown inside the confirm transaction when the hold's seats no longer all belong to it (e.g. one
 * was reclaimed after expiry). Rolls the confirm back entirely — never a partial sale.
 */
public class HoldInvalidatedException extends RuntimeException {

  public HoldInvalidatedException(UUID holdId) {
    super("Hold " + holdId + " no longer controls all of its seats");
  }
}

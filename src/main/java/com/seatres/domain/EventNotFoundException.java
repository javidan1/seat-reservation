package com.seatres.domain;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {

  public EventNotFoundException(UUID eventId) {
    super("No such event: " + eventId);
  }
}

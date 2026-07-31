package com.seatres.domain;

import java.util.List;
import java.util.UUID;

/**
 * Thrown inside the claim transaction when not every requested seat could be claimed; rolls the
 * whole hold back (all-or-nothing).
 */
public class SeatsUnavailableException extends RuntimeException {

  private final UUID eventId;
  private final List<UUID> requestedSeatIds;

  public SeatsUnavailableException(UUID eventId, List<UUID> requestedSeatIds) {
    super("Not all requested seats could be claimed");
    this.eventId = eventId;
    this.requestedSeatIds = List.copyOf(requestedSeatIds);
  }

  public UUID eventId() {
    return eventId;
  }

  public List<UUID> requestedSeatIds() {
    return requestedSeatIds;
  }
}

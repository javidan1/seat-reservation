package com.jmammadov.seatres.application;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.EventNotFoundException;
import com.jmammadov.seatres.domain.SeatView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Availability is a plain MVCC snapshot read — deliberately lock-free. "Fast, accurate-enough" is
 * the requirement; the moment of truth is the claim, not the read.
 */
@Service
public class AvailabilityService {

  private final ReservationRepository repo;

  public AvailabilityService(ReservationRepository repo) {
    this.repo = repo;
  }

  public List<EventInfo> events() {
    return repo.listEvents();
  }

  public List<SeatView> seatMap(UUID eventId) {
    repo.findEvent(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    return repo.seatMap(eventId);
  }

  /** Which of these seats are not claimable right now (explains a rejected hold). */
  public List<UUID> unavailableOf(UUID eventId, List<UUID> requestedSeatIds) {
    List<UUID> claimable = repo.claimableNow(eventId, requestedSeatIds);
    List<UUID> unavailable = new ArrayList<>(requestedSeatIds);
    unavailable.removeAll(claimable);
    return unavailable;
  }
}

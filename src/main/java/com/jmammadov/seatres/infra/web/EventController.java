package com.jmammadov.seatres.infra.web;

import com.jmammadov.seatres.application.AvailabilityService;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.SeatView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

  private final AvailabilityService availability;

  public EventController(AvailabilityService availability) {
    this.availability = availability;
  }

  @GetMapping
  public List<EventInfo> events() {
    return availability.events();
  }

  @GetMapping("/{eventId}/seats")
  public List<SeatView> seatMap(@PathVariable UUID eventId) {
    return availability.seatMap(eventId);
  }
}

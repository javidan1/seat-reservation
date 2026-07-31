package com.jmammadov.seatres.infra.web;

import com.jmammadov.seatres.application.AvailabilityService;
import com.jmammadov.seatres.infra.sse.SeatMapBroadcaster;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SeatStreamController {

  private final SeatMapBroadcaster broadcaster;
  private final AvailabilityService availability;

  public SeatStreamController(SeatMapBroadcaster broadcaster, AvailabilityService availability) {
    this.broadcaster = broadcaster;
    this.availability = availability;
  }

  @GetMapping(path = "/api/events/{eventId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID eventId) {
    availability.seatMap(eventId); // 404 for unknown events before upgrading the connection
    return broadcaster.subscribe(eventId);
  }
}

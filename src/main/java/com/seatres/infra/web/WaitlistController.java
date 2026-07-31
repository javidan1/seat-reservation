package com.seatres.infra.web;

import com.seatres.application.WaitlistService;
import com.seatres.domain.WaitlistView;
import com.seatres.infra.web.dto.ApiError;
import com.seatres.infra.web.dto.WaitlistRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WaitlistController {

  private final WaitlistService waitlist;

  public WaitlistController(WaitlistService waitlist) {
    this.waitlist = waitlist;
  }

  @PostMapping("/events/{eventId}/waitlist")
  public ResponseEntity<WaitlistView> join(
      @PathVariable UUID eventId, @RequestBody WaitlistRequest req) {
    WaitlistView entry = waitlist.join(eventId, req.seatId(), req.userRef());
    return ResponseEntity.status(HttpStatus.CREATED).body(entry);
  }

  @GetMapping("/waitlist/{entryId}")
  public ResponseEntity<WaitlistView> get(@PathVariable UUID entryId) {
    return ResponseEntity.of(waitlist.get(entryId));
  }

  @DeleteMapping("/waitlist/{entryId}")
  public ResponseEntity<Object> cancel(@PathVariable UUID entryId) {
    return switch (waitlist.cancel(entryId)) {
      case CANCELLED -> ResponseEntity.noContent().build();
      case ALREADY_GRANTED ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  ApiError.of(
                      "already_granted",
                      "The seat was already granted to you — you have an active hold"));
      case NOT_FOUND -> ResponseEntity.notFound().build();
    };
  }
}

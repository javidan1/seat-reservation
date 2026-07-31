package com.seatres.infra.web;

import com.seatres.application.ReservationService;
import com.seatres.domain.Booking;
import com.seatres.domain.ConfirmResult;
import com.seatres.domain.HoldStatus;
import com.seatres.domain.HoldView;
import com.seatres.infra.web.dto.ApiError;
import com.seatres.infra.web.dto.HoldRequest;
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
public class HoldController {

  private final ReservationService reservations;

  public HoldController(ReservationService reservations) {
    this.reservations = reservations;
  }

  @PostMapping("/events/{eventId}/holds")
  public ResponseEntity<HoldView> hold(@PathVariable UUID eventId, @RequestBody HoldRequest req) {
    HoldView hold = reservations.hold(eventId, req.userRef(), req.seatIds());
    return ResponseEntity.status(HttpStatus.CREATED).body(hold);
  }

  @GetMapping("/holds/{holdId}")
  public ResponseEntity<HoldView> get(@PathVariable UUID holdId) {
    return ResponseEntity.of(reservations.hold(holdId));
  }

  @PostMapping("/holds/{holdId}/confirm")
  public ResponseEntity<Object> confirm(@PathVariable UUID holdId) {
    return switch (reservations.confirm(holdId)) {
      case ConfirmResult.Confirmed(Booking booking) ->
          ResponseEntity.status(HttpStatus.CREATED).body(booking);
      case ConfirmResult.AlreadyConfirmed(Booking booking) -> ResponseEntity.ok(booking);
      case ConfirmResult.HoldGone(HoldStatus status) ->
          ResponseEntity.status(HttpStatus.GONE)
              .body(ApiError.of("hold_no_longer_valid", "Hold is " + status));
      case ConfirmResult.NotFound() -> ResponseEntity.notFound().build();
    };
  }

  @DeleteMapping("/holds/{holdId}")
  public ResponseEntity<Void> release(@PathVariable UUID holdId) {
    reservations.release(holdId);
    return ResponseEntity.noContent().build();
  }
}

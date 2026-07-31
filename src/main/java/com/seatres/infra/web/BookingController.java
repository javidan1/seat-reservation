package com.seatres.infra.web;

import com.seatres.application.ReservationService;
import com.seatres.domain.Booking;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

  private final ReservationService reservations;

  public BookingController(ReservationService reservations) {
    this.reservations = reservations;
  }

  @GetMapping("/{bookingId}")
  public ResponseEntity<Booking> get(@PathVariable UUID bookingId) {
    return ResponseEntity.of(reservations.booking(bookingId));
  }
}

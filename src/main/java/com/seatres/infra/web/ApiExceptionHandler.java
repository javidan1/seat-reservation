package com.seatres.infra.web;

import com.seatres.application.AvailabilityService;
import com.seatres.domain.EventNotFoundException;
import com.seatres.domain.HoldInvalidatedException;
import com.seatres.domain.SeatNotFoundException;
import com.seatres.domain.SeatsUnavailableException;
import com.seatres.domain.WaitlistJoinRejectedException;
import com.seatres.infra.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private final AvailabilityService availability;

  public ApiExceptionHandler(AvailabilityService availability) {
    this.availability = availability;
  }

  /**
   * The claim transaction has already rolled back; we re-query outside it so the caller learns
   * exactly which seats were contested. The list is informational — by the time they read it, the
   * world may have moved again.
   */
  @ExceptionHandler(SeatsUnavailableException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ApiError seatsUnavailable(SeatsUnavailableException e) {
    return new ApiError(
        "seats_unavailable",
        "Some requested seats are no longer available",
        availability.unavailableOf(e.eventId(), e.requestedSeatIds()));
  }

  @ExceptionHandler(HoldInvalidatedException.class)
  @ResponseStatus(HttpStatus.GONE)
  public ApiError holdInvalidated(HoldInvalidatedException e) {
    return ApiError.of("hold_no_longer_valid", e.getMessage());
  }

  @ExceptionHandler(EventNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ApiError eventNotFound(EventNotFoundException e) {
    return ApiError.of("event_not_found", e.getMessage());
  }

  @ExceptionHandler(SeatNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ApiError seatNotFound(SeatNotFoundException e) {
    return ApiError.of("seat_not_found", e.getMessage());
  }

  @ExceptionHandler(WaitlistJoinRejectedException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ApiError waitlistJoinRejected(WaitlistJoinRejectedException e) {
    return ApiError.of("waitlist_join_rejected", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiError badRequest(IllegalArgumentException e) {
    return ApiError.of("invalid_request", e.getMessage());
  }
}

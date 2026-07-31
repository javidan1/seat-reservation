package com.jmammadov.seatres.infra.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<UUID> unavailableSeatIds) {

  public static ApiError of(String error, String message) {
    return new ApiError(error, message, null);
  }
}

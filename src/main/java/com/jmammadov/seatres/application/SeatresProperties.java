package com.jmammadov.seatres.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatres")
public record SeatresProperties(Duration holdTtl, Sweeper sweeper) {

  public record Sweeper(boolean enabled, Duration interval) {}
}

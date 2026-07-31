package com.seatres.domain;

import java.time.Instant;
import java.util.UUID;

public record EventInfo(UUID id, String name, Instant startsAt) {}

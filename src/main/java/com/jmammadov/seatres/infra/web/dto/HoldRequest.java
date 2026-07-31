package com.jmammadov.seatres.infra.web.dto;

import java.util.List;
import java.util.UUID;

public record HoldRequest(String userRef, List<UUID> seatIds) {}

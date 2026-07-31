package com.jmammadov.seatres.infra.web.dto;

import java.util.UUID;

public record WaitlistRequest(String userRef, UUID seatId) {}

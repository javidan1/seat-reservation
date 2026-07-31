package com.jmammadov.seatres.application;

import com.jmammadov.seatres.domain.SeatChange;
import java.util.List;

/**
 * Published inside a transaction; consumed AFTER_COMMIT by the SSE broadcaster so clients only ever
 * see committed state.
 */
public record SeatChangedEvent(List<SeatChange> changes) {}

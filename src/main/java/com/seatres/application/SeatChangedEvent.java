package com.seatres.application;

import com.seatres.domain.SeatChange;
import java.util.List;

/**
 * Published inside a transaction; consumed AFTER_COMMIT by the SSE broadcaster so clients only ever
 * see committed state.
 */
public record SeatChangedEvent(List<SeatChange> changes) {}

-- Waitlist: per-seat FIFO queue for seats currently HELD.
--
-- "Defensible order" = the position column: GENERATED ALWAYS AS IDENTITY, so it
-- is server-assigned, monotonic, and cannot be supplied by a client. Grants
-- always take the minimum WAITING position (see SeatReleaseService).

CREATE TABLE waitlist (
    id              uuid PRIMARY KEY,
    seat_id         uuid NOT NULL REFERENCES seat (id),
    event_id        uuid NOT NULL REFERENCES event (id),
    user_ref        text NOT NULL,
    status          text NOT NULL CHECK (status IN ('WAITING', 'GRANTED', 'CANCELLED')),
    position        bigint GENERATED ALWAYS AS IDENTITY,
    granted_hold_id uuid REFERENCES hold (id),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- A user queues at most once per seat (live entries only).
CREATE UNIQUE INDEX idx_waitlist_one_live_per_user ON waitlist (seat_id, user_ref)
    WHERE status = 'WAITING';

-- Head-of-queue lookup.
CREATE INDEX idx_waitlist_head ON waitlist (seat_id, position) WHERE status = 'WAITING';

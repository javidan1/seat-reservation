-- Seat reservation schema.
--
-- Correctness rests on three layers (see DECISIONS.md):
--   1. every state transition is a single conditional UPDATE (predicate CAS),
--   2. the hold row serializes confirm/release/expire for a whole hold,
--   3. this schema makes double-selling structurally impossible:
--      booking_seat.seat_id is the PRIMARY KEY, so a second booking of the
--      same seat is rejected by Postgres even if the application has a bug.

CREATE TABLE event (
    id        uuid PRIMARY KEY,
    name      text NOT NULL,
    starts_at timestamptz NOT NULL
);

CREATE TABLE hold (
    id         uuid PRIMARY KEY,
    event_id   uuid NOT NULL REFERENCES event (id),
    user_ref   text NOT NULL,
    status     text NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    seat_count int  NOT NULL CHECK (seat_count > 0),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seat (
    id              uuid PRIMARY KEY,
    event_id        uuid NOT NULL REFERENCES event (id),
    row_label       text NOT NULL,
    seat_number     int  NOT NULL,
    status          text NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    hold_id         uuid REFERENCES hold (id),
    hold_expires_at timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    UNIQUE (event_id, row_label, seat_number),
    CHECK (status <> 'HELD' OR (hold_id IS NOT NULL AND hold_expires_at IS NOT NULL)),
    CHECK (status <> 'AVAILABLE' OR hold_id IS NULL)
);

CREATE TABLE booking (
    id           uuid PRIMARY KEY,
    -- One booking per hold: the idempotency anchor for confirm.
    hold_id      uuid NOT NULL UNIQUE REFERENCES hold (id),
    event_id     uuid NOT NULL REFERENCES event (id),
    user_ref     text NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE booking_seat (
    -- seat_id as PRIMARY KEY is the structural no-double-sell guarantee.
    seat_id    uuid PRIMARY KEY REFERENCES seat (id),
    booking_id uuid NOT NULL REFERENCES booking (id)
);

CREATE INDEX idx_seat_event ON seat (event_id);
CREATE INDEX idx_seat_live_hold ON seat (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX idx_hold_active ON hold (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_booking_seat_booking ON booking_seat (booking_id);

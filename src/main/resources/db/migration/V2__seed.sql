-- Demo data: one event with 10 rows (A–J) of 20 seats each.
-- Fixed event id so the UI and demo scripts can rely on it.

INSERT INTO event (id, name, starts_at)
VALUES ('00000000-0000-0000-0000-000000000001',
        'The Midnight Orchestra — Live',
        now() + interval '7 days');

INSERT INTO seat (id, event_id, row_label, seat_number)
SELECT gen_random_uuid(),
       '00000000-0000-0000-0000-000000000001',
       chr(64 + r),
       n
FROM generate_series(1, 10) AS r,
     generate_series(1, 20) AS n;

---
name: stress
description: Run the full-size concurrency stress suite (no-double-sell evidence) and summarize the invariant results. Use after any change to claim/confirm/release/expiry SQL, transaction boundaries, or the schema.
---

# Stress

Run the full-size load-tagged suite:

```bash
./gradlew stressTest              # default size
./gradlew stressTest -Pstress.threads=400   # heavier
```

What it proves (the invariants checked after the run):
1. No seat appears in two confirmed bookings (client ledger cross-check).
2. In the database, every SOLD seat has exactly one `booking_seat` row and no live hold.
3. All-or-nothing holds: no hold ever ends up with a subset of its seats.

After the run, report: threads, total attempts, confirmed bookings, conflicts (409s), expired holds, and an explicit "0 double-sells" line quoting the invariant queries. If any invariant fails, that is a release-blocking bug — do not rationalize it as test flakiness; reproduce and fix.

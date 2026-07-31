# DECISIONS

## The hard part, as I read it

The brief's visible requirement is "never sell a seat twice." The hidden one is the **hold
lifecycle**: the three-way race between a customer confirming, their hold lapsing, and the seats
being reclaimed — on a **multi-seat** hold. Naive designs fail here in two ugly ways: a partial
confirm (customer charged for one of their two seats) or a resurrected hold (expired seats sold
anyway). Everything below is shaped by that reading. A second, quieter trap: "fast,
accurate-enough availability" licenses plain snapshot reads — wrapping the seat map in locks would
be solving a problem the brief explicitly didn't pose.

## Core architecture: the database is the only arbiter

Postgres is the single source of truth and the single clock. Correctness rests on three
independent layers, any one of which suffices:

1. **Predicate CAS.** Every state transition is one conditional `UPDATE` whose `WHERE` clause
   decides the race atomically (`JdbcReservationRepository` is the whole story, ~6 statements).
   The claim predicate also accepts seats whose hold has lapsed (`hold_expires_at <= now()`), so
   expired inventory is sellable **even if the background sweeper never runs** — the sweeper is a
   janitor for UI freshness, not a correctness dependency.
2. **The hold row is the serialization point.** Confirm starts with a single-row CAS
   (`ACTIVE → CONFIRMED WHERE expires_at > now()`), then cascades to seats asserting
   `rowcount == seat_count` — if even one seat was reclaimed meanwhile, the whole transaction
   rolls back. No partial sales, ever.
3. **The schema itself.** `booking_seat.seat_id` is the *primary key*. If layers 1–2 both had
   bugs, Postgres would still refuse the second sale. This also makes the stress test's core
   invariant a one-line query.

Supporting stances:

- **Time authority is `now()`** — transaction-stable, identical across app instances, and it lets
  tests time-travel by rewriting `expires_at` instead of sleeping.
- **READ COMMITTED, explicitly.** After a row-lock wait Postgres re-evaluates the `UPDATE`'s
  predicate against the newly committed row, so the loser of a race simply matches zero rows and
  gets a clean 409/410. REPEATABLE READ would abort the loser with a serialization error and force
  retry machinery for zero benefit.
- **Deadlock-proof claims.** Multi-seat claims lock rows via an `ORDER BY id FOR UPDATE` subquery
  (deterministic order), with one retry as insurance.
- **Fairness = first-committed-wins.** Postgres row-lock waits are FIFO, losers get an immediate,
  deterministic 409 naming exactly the contested seats, and all-or-nothing claims mean no partial
  grabs. Predictable beats elaborate.
- **Multi-instance safety falls out for free** because every mechanism above lives in the
  database. `MultiInstanceStressIT` runs two full app processes against one Postgres and re-checks
  the invariants.

## Alternatives considered and rejected

- **Redis / distributed locks / in-memory holds.** The contended state *is* durable state; a
  second locking system adds a consistency boundary (and a failure mode) without removing the
  database write. Row locks are the lock service.
- **JPA.** The correctness mechanism here is "conditional UPDATE, check the row count." An ORM
  buries exactly that under session caching and dirty checking. `JdbcClient` keeps the six
  critical statements visible, reviewable artifacts. (Spring Data JDBC rejected for the same
  reason at smaller scale: aggregate mapping buys nothing here.)
- **SERIALIZABLE / REPEATABLE READ.** See above — strictly worse ergonomics for this access
  pattern.
- **WebSockets.** One-directional fan-out doesn't need them; SSE is plain HTTP, auto-reconnects,
  and halves the moving parts. Clients drop events older than the per-seat `version`, so ordering
  and duplicates are non-issues; a snapshot re-fetch on reconnect heals gaps. One deliberate
  wrinkle: pure expiry emits no event (nothing was written), so clients flip lapsed seats locally.
- **A frontend framework.** The UI is a seat grid, a countdown, and three buttons — one page of
  vanilla JS served from Spring's static resources. A React+Vite toolchain would be résumé-driven
  scope.

## Testing strategy

Every claim the code can't show by reading gets a runnable proof; tests never sleep to reach
expiry, they time-travel through the DB clock. The three that matter most: **ExpiryConfirmRaceIT**
(sweeper disabled throughout — lapsed holds are rejected, their seats claimable with no background
job, and 20 rounds of confirm-vs-reclaim at the boundary never double-win),
**NoDoubleSellStressIT** (a virtual-thread storm over a hot seat block; client-side booking ledger
and DB invariants must agree: 200 workers / 6,000 attempts / 0 double-sells), and
**RestartDurabilityIT** (the Spring context is genuinely torn down and rebooted against the same
database; bookings survive, in-flight holds still confirm, holds lapsed during downtime are
reclaimable). `PartialConfirmGuardIT` pins the nastiest anomaly; `ArchitectureTest` (ArchUnit)
enforces the layer rules in CLAUDE.md instead of trusting intentions. A short storm runs in CI on
every push; `./gradlew stressTest` runs the full one.

## Consciously cut (and why)

- **Waitlist** (stretch 2): a real feature, not an afternoon one. Sketch: `waitlist` table with
  arrival order, claim-on-release via `FOR UPDATE SKIP LOCKED` consumer, same CAS discipline.
- **Auth**: the hold UUID is a capability token; real identity is orthogonal to the concurrency
  problem being assessed.
- **Hold TTL renewal**: it's where the deliberate `hold.expires_at` / `seat.hold_expires_at`
  duplication would bite (they'd have to move together); excluded rather than half-done.
- **Cross-instance SSE fan-out** (`LISTEN/NOTIFY`): clients of instance A don't see instance B's
  commits live; the snapshot-on-reconnect heals it. Correctness is unaffected.
- **Payments/pricing, seat categories**: out of scope for the assessed problem.

## Cloud deployment (bonus)

`infra/` is a complete Terraform path from nothing to running on AWS: VPC → RDS Postgres 16
(private subnets, password generated into SSM — never in a task definition) → ECS Fargate on
ARM64 → ALB. Deliberate demo-stack shortcuts are listed in `infra/README.md` rather than hidden
(HTTP-only, single-AZ, no NAT, local state). Because correctness lives entirely in the database,
`app_count = 2` runs the multi-instance guarantee in the cloud unchanged.

## With more time

Waitlist done properly; `LISTEN/NOTIFY` fan-out; a jqwik model-based state-machine test (random
interleavings of hold/confirm/release/expire checked against a reference model); metrics
(claim-conflict rate is the interesting one); and idempotency keys on `POST /holds` so a client
retry after a network timeout can't double-hold.

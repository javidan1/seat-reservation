# DECISIONS

## The hard part, as I read it

The brief's visible requirement is "never sell a seat twice." The hidden one is the **hold
lifecycle**: the three-way race between a customer confirming, their hold lapsing, and the seats
being reclaimed — on a **multi-seat** hold. Naive designs fail as a partial confirm (a customer
charged for one of two seats) or a resurrected hold (expired seats sold
anyway). Everything below is shaped by that reading. The quieter second trap: "fast,
accurate-enough availability" licenses plain snapshot reads — locking the seat map would solve a
problem the brief didn't pose.

## Core architecture: the database is the only arbiter

Postgres is the single source of truth and the single clock. Correctness rests on three
independent layers, any one of which suffices:

1. **Predicate CAS.** Every state transition is one conditional `UPDATE` whose `WHERE` clause
   decides the race atomically (`JdbcReservationRepository`, ~6 statements).
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

- **Time authority is `now()`** — transaction-stable, identical across instances, and it lets
  tests time-travel by rewriting `expires_at`.
- **READ COMMITTED, explicitly.** After a row-lock wait Postgres re-evaluates the `UPDATE`'s
  predicate against the newly committed row, so a race's loser simply matches zero rows and gets a
  clean 409/410; REPEATABLE READ would abort it with a serialization error and force retry
  machinery for nothing.
- **Deadlock-proof claims.** Multi-seat claims lock rows via an `ORDER BY id FOR UPDATE` subquery
  (deterministic order), with one retry as insurance.
- **Fairness = first-committed-wins.** Row-lock waits are FIFO; losers get an immediate 409
  naming the contested seats; all-or-nothing claims mean no partial grabs. Predictable beats
  elaborate.
- **Multi-instance safety falls out for free** — every mechanism lives in the database;
  `MultiInstanceStressIT` re-checks the invariants across two app processes sharing one Postgres.

## Alternatives considered and rejected

- **Redis / distributed locks / in-memory holds.** Contended state *is* durable state; a second
  locking system adds a consistency boundary and a failure mode without removing the database
  write. Row locks are the lock service.
- **JPA.** The correctness mechanism here is "conditional UPDATE, check the row count" — an ORM
  buries exactly that under session caching and dirty checking. `JdbcClient` keeps the critical
  statements visible, reviewable artifacts. (Spring Data JDBC: same reason, smaller scale.)
- **SERIALIZABLE / REPEATABLE READ.** See above — strictly worse ergonomics for this access
  pattern.
- **WebSockets.** SSE is plain HTTP, auto-reconnects, and halves the moving parts. Versioned
  events make ordering and duplicates non-issues; a snapshot re-fetch on reconnect heals gaps;
  pure expiry (no write, hence no event) is flipped client-side on the countdown.
- **A frontend framework.** A seat grid, a countdown, three buttons — one page of vanilla JS
  from Spring's static resources; a React toolchain would be résumé-driven scope.

## Testing strategy

Every claim the code can't show by reading gets a runnable proof; tests never sleep to reach
expiry, they time-travel through the DB clock. The core evidence: **ExpiryConfirmRaceIT** (sweeper
disabled throughout — lapsed holds rejected, their seats claimable with no background job, 20
boundary rounds of confirm-vs-reclaim never double-win), **NoDoubleSellStressIT** (virtual-thread
storm; client-side booking ledger and DB invariants must agree: 200 workers / 6,000 attempts /
0 double-sells), **RestartDurabilityIT** (the context genuinely torn down and rebooted against the
same database), and **WaitlistIT** (FIFO under a concurrent join storm; waiter priority over
lapsed-hold reclaims). `PartialConfirmGuardIT` pins the nastiest anomaly; ArchUnit enforces the
layer rules instead of trusting intentions. Exactly **one unit test** exists — the deadlock-retry
branch, undrivable deterministically through the API — deliberately: everywhere else the defects
that matter (races, predicates, rowcounts, locks) live below the mock line, so tests run against
real Postgres or not at all. A short storm runs in CI; `./gradlew stressTest` runs the full one.

## The waitlist (stretch 2)

Per-seat FIFO queues, joinable while a seat is HELD. "Defensible order" = a server-assigned
identity-column position; grants take the minimum committed position (joins are observable only at
commit, so the order is linearizable). A freed seat becomes a **normal 1-seat hold** for the head
waiter — confirm/release/expiry and the double-sell guarantees apply unchanged — and direct claims
(including lazy reclaims) are blocked while anyone waits. The hard part: a race caught in design review
before implementation — a join racing a release could commit a WAITING entry against a just-freed
seat, freezing it forever under the waiter-priority predicate. The fix makes the **seat row the
serialization point**: joins take `FOR SHARE` and trust only the post-lock status; the freeing
routine locks each seat `FOR UPDATE` (in id order, matching the claim's lock order) *before*
reading its queue. Stated trade-off: lapsed-hold grant *liveness* rides the 1s sweeper;
correctness stays sweeper-independent (proven with the scheduler disabled).

## Consciously cut (and why)

- **Auth**: hold UUIDs are capability tokens; identity is orthogonal to the assessed
  concurrency problem.
- **Hold TTL renewal**: the one place the deliberate `expires_at` duplication would bite;
  excluded rather than half-done.
- **Cross-instance SSE fan-out** (`LISTEN/NOTIFY`): instance A's clients miss B's commits live;
  snapshot-on-reconnect heals it; correctness unaffected.
- **Payments/pricing, seat categories**: out of scope for the assessed problem.

## Cloud deployment (bonus)

`infra/` is a complete Terraform path from nothing to running on AWS: VPC → RDS Postgres 16
(private subnets; the password is RDS-managed in Secrets Manager, so no Terraform state ever
contains it) → ECS Fargate on ARM64 (non-root container) → ALB → CloudFront TLS edge
(browser-trusted HTTPS without owning a domain; the ALB admits only CloudFront's IP range).
State is encrypted, versioned S3. Remaining shortcuts stay listed in `infra/README.md`
(edge→origin HTTP, single-AZ, no NAT). Because correctness lives entirely in the database,
`app_count = 2` runs the multi-instance guarantee in the cloud unchanged.

## With more time — and one thing done differently

With more time: `LISTEN/NOTIFY` SSE fan-out (~half a day); a jqwik model-based state-machine
test — random interleavings against a reference model (~a day); metrics, starting with
claim-conflict rate (~hours); idempotency keys on `POST /holds` so a client retry can't
double-hold (~half a day).

Done differently: a standing agent rule from day one — now in CLAUDE.md — that **the agent finds
risks, but only a human accepts them**. An early draft classified HTTP-only transport as an
"accepted risk" on the agent's own authority; challenged, it was fixed in an evening (CloudFront
TLS edge). Assumed acceptance hides cheap fixes; acceptance is a decision, not a default.

# seat-reservation

Seat reservation service: hold and book event seats; never sell the same seat twice.
Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · JdbcClient (no JPA — deliberate, see DECISIONS.md).

## Commands

```bash
docker compose up -d          # Postgres 16 on :5432 (seatres/seatres)
./gradlew bootRun             # app + UI on http://localhost:8080
./gradlew test                # fast suite (Testcontainers; excludes @Tag("load"))
./gradlew stressTest          # full-size concurrency stress suite
./gradlew spotlessApply       # format (google-java-format); CI runs spotlessCheck
```

## Invariants — do not violate, in any change

1. **Every seat/hold state transition is a single conditional UPDATE** (predicate CAS) in
   `JdbcReservationRepository`. Never read-modify-write seat state in Java. Never add a
   transition without a rowcount assertion.
2. **Time authority is Postgres `now()`**. Never compare expiry against `Instant.now()` in
   Java for a correctness decision — `ArchitectureTest` bans `Instant.now()` and
   `System.currentTimeMillis()` in main code outright. Tests time-travel by mutating
   `expires_at`, not by sleeping.
3. **Correctness must never depend on the sweeper running.** The claim predicate reclaims
   expired holds (`status='HELD' AND hold_expires_at <= now()`); the sweeper is UI freshness plus
   ONE liveness duty — granting waitlisted seats of lapsed holds. Never give it a correctness duty.
4. **The seat row serializes waitlist traffic.** Joins lock it FOR SHARE and trust only the
   post-lock status; the freeing routine locks it FOR UPDATE before reading the queue. Breaking
   this recreates the stranded AVAILABLE-with-waiters seat. Never lock or SKIP LOCKED the
   waitlist head SELECT (fairness).
5. **Multi-seat claims and the freeing routine lock seats in sorted id order** to prevent
   deadlocks. Keep it that way.
6. **Isolation is READ COMMITTED** — the design depends on Postgres re-evaluating UPDATE
   predicates after lock waits. Do not add custom isolation levels.
7. **SSE events are published only after commit** (`@TransactionalEventListener(AFTER_COMMIT)`)
   and carry the seat `version`; clients drop stale versions.
8. Layer rules are enforced by `ArchitectureTest` (ArchUnit): `domain` depends on nothing;
   SQL lives only in `infra.persistence`; `infra.web` never touches persistence directly.

## Decision protocol — the agent finds, a human accepts

Never classify a risk, limitation, or scope cut as "accepted" on your own authority. Surface it
with severity, cost-to-fix-now, and cost-to-fix-later, then let the human decide. Anything
labeled "accepted" in the docs means accepted *by a named human decision*, never assumed. When
deadline pressure collides with a security or correctness gap, present the trade — do not
resolve it silently. (Origin: an early draft called HTTP-only transport an "accepted risk";
challenged, it was fixed in an evening. Assumed acceptance hides cheap fixes.)

## Definition of done

- `./gradlew spotlessCheck test` green.
- Any claim about concurrency/correctness has a test in the evidence suite
  (`ExpiryConfirmRaceIT`, `NoDoubleSellStressIT`, `RestartDurabilityIT`, …). "It works" is not evidence.
- Commits are small and conventional (`feat:`, `fix:`, `test:`, `chore:`, `docs:`).

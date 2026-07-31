reqs not read

# seat-reservation

A seat reservation service for a ticketing platform: browse a live seat map, hold seats while you
check out, confirm a booking — and **never sell the same seat twice**, which this repo proves
under load rather than asserts (see [DECISIONS.md](DECISIONS.md) and the evidence suite below).

Built for the Solovis engineering take-home (Project 4). Java 21 · Spring Boot 4.1 · PostgreSQL 16
· Flyway · `JdbcClient` · vanilla-JS UI over SSE.

## Run it

Prereqs: Docker, and a JDK to run Gradle (the build itself provisions JDK 21 via the toolchain).

```bash
docker compose up -d      # Postgres 16 on localhost:5433 (avoids clashing with a local 5432)
./gradlew bootRun         # migrates, seeds one event (10 rows × 20 seats), serves the app
open http://localhost:8080
```

Best demo: open **two browser windows side by side**, fight over the same seats (loser gets a 409
naming the contested seats), then hold seats in one window and dawdle — the hold expires on
screen and the seats return to the pool in both windows.

## Verify it

```bash
./gradlew test            # fast suite: race, durability, contract, sweeper, ArchUnit (Testcontainers)
./gradlew stressTest      # full no-double-sell storm (default 200 virtual threads; -Pstress.threads=400)
```

The tests that carry the correctness claims:

| Test | Proves |
|---|---|
| `ExpiryConfirmRaceIT` | Lapsed holds can't confirm; their seats are claimable **with the sweeper disabled**; confirm racing a reclaim at the expiry boundary never double-wins |
| `NoDoubleSellStressIT` | Hundreds of buyers storming a hot seat block: client booking ledger and DB agree, zero double-sells, zero partial confirms |
| `RestartDurabilityIT` | Full context teardown + reboot against the same DB: bookings survive, in-flight holds still confirm |
| `PartialConfirmGuardIT` | A hold that lost one seat never sells a subset |
| `MultiInstanceStressIT` | Two app processes, one Postgres: the storm invariants still hold |
| `WaitlistIT` | FIFO grants in strict arrival order (concurrent-join storm), waiters outrank direct claims even on lapsed holds, sold seats leave no zombie queues |
| `ArchitectureTest` | The layer rules in CLAUDE.md, enforced by ArchUnit |

## API

| | |
|---|---|
| `GET /api/events` · `GET /api/events/{id}/seats` | events, live seat map (effective statuses) |
| `POST /api/events/{id}/holds` `{userRef, seatIds}` | all-or-nothing hold → 201, or 409 naming contested seats |
| `GET /api/holds/{id}` · `DELETE /api/holds/{id}` | inspect (DB-clock `remainingSeconds`) / release (idempotent 204) |
| `POST /api/holds/{id}/confirm` | 201 booking · 200 same booking on retry · 410 if lapsed |
| `GET /api/bookings/{id}` | booking with its seats |
| `POST /api/events/{id}/waitlist` `{userRef, seatId}` | queue for a HELD seat → 201 with live position |
| `GET /api/waitlist/{id}` · `DELETE /api/waitlist/{id}` | poll status/position/granted hold · leave the queue |
| `GET /api/events/{id}/stream` | SSE: versioned committed seat changes |

## Cloud deployment (bonus)

The service runs on AWS with all infrastructure defined in Terraform — see
[infra/README.md](infra/README.md) for the one-command path (VPC, RDS Postgres 16 with an
RDS-managed password that never touches Terraform state, ECS Fargate on ARM64 as non-root,
ALB behind a CloudFront TLS edge) and its deliberately documented demo-stack shortcuts. The
live demo stack is torn down after review (`terraform destroy`) rather than left idling;
`terraform apply` reproduces it from scratch in ~10 minutes. Dependency hygiene is automated:
Dependabot watches four ecosystems and CI submits the resolved Gradle graph so alerts cover
transitives.

## How this was built

Authored agentically with Claude Code as the primary mode of work, directed by the repo's own
guardrails: [CLAUDE.md](CLAUDE.md) pins the invariants the agent may not violate (single-statement
transitions, DB-clock-only expiry, sweeper independence), `.claude/skills/` holds the working
verification loops (`/verify`, `/stress`), and ArchUnit + Spotless + CI enforce the standards
instead of trusting them. [docs/AGENT_NOTES.md](docs/AGENT_NOTES.md) documents the process
honestly — including the agent-introduced errors that review caught, and where the design review
changed the design. Commits are incremental and co-authored transparently.

**Time spent:** roughly 6 hours across one evening — planning and adversarial design review
(~45 min), core + evidence suite + UI + docs (~2.5 h), cloud deployment (~1 h), the waitlist
stretch (~1.25 h), and review/polish in between.

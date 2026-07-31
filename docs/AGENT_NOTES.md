# Agent notes — how this was actually built

This repo was authored with Claude Code as the primary mode of work: I directed, the agent wrote
most of the code, and the process below kept it honest. Full session transcripts available on
request — nothing here is reconstructed after the fact.

## The setup

- **Plan first, adversarially.** Before any code, a planning pass produced the architecture; a
  second, independent review pass was explicitly prompted to *attack* it ("find the holes — don't
  just approve"). That review materially changed the design (see catch #1).
- **Guardrails in the repo, not in my head.** [CLAUDE.md](../CLAUDE.md) pins the invariants the
  agent may not violate (single-statement transitions, DB-clock expiry, sweeper independence,
  sorted locking, READ COMMITTED). ArchUnit turns the layer rules into failing tests;
  Spotless + CI enforce the rest. An agent with standing rules drifts far less than one
  re-instructed per prompt.
- **Working skills, not decorative ones.** `.claude/skills/verify` (format + fast suite before
  every commit) and `.claude/skills/stress` (full storm after any change to transition SQL) are
  the loops that actually ran.
- **Small commits, transparent co-authorship.** The history is the audit trail.

## Agent errors caught — and what they generalize to

1. **The sweeper-independence hole (design review, before any code).** The first design claimed
   "correctness never depends on the background sweeper" — but its claim predicate only accepted
   `status='AVAILABLE'` seats. If the sweeper ever stopped, expired-held seats would be unsellable
   forever: the claim was false as specified. The adversarial review pass caught it; the fix
   (claim predicate also reclaims lapsed holds) became the load-bearing line of SQL in the system
   and the first thing `ExpiryConfirmRaceIT` proves. *Lesson: make a second, fresh-context agent
   attack the plan; the author-agent will happily agree with itself.*
2. **Framework drift vs. model memory.** Spring Initializr handed back Spring Boot 4.1, newer
   than the agent's strongest priors. Three assumptions broke in one hour: Jackson moved to
   `tools.jackson.*` with unchecked exceptions, Testcontainers 2 made `PostgreSQLContainer`
   non-generic (the agent "fixed" the generated scaffold to add generics — the compiler vetoed
   it), and a deprecated Spring exception needed its replacement. *Lesson: on fresh major
   versions, trust the build output over the model; keep the compile-test loop tight so drift
   surfaces in minutes.*
3. **A test caught the agent's API misuse.** `RestartDurabilityIT` booted its throwaway contexts
   with `SpringApplicationBuilder.properties(...)` — which sets *default* properties that
   `application.yaml` overrides, so the "random port" instruction was silently ignored and the
   test collided with the dev server on 8080. The fix is CLI-style args (highest precedence).
   The test failing loudly is exactly why the restart proof exists as a test and not a claim.
4. **Environment reality over agent assumption.** First boot failed: a natively installed
   Postgres owned `127.0.0.1:5432`, shadowing the Docker container. Rather than touching the
   local install, the compose port moved to 5433 — which also protects whoever clones this repo
   from the same collision.
5. **The stranded-seat race, caught before a line of code.** The waitlist (attempted after an
   initial conscious cut, on an explicit go decision with a revert branch as the safety net) went
   through the same adversarial design review as the original architecture. The review found that
   the obvious design — "join checks the seat is HELD, then inserts" — loses a race against a
   concurrent release: the entry commits against a seat that just went AVAILABLE, and the new
   waiter-priority claim predicate then freezes that seat *forever*. It also caught a
   foreign-key-ordering bug in the grant loop. Both were fixed in the design (seat row as the
   serialization point, hold inserted before the entry references it) before implementation
   started. *Lesson: the more a change touches load-bearing semantics, the more the review pass
   pays — this one turned a would-be production incident into a paragraph in DECISIONS.md.*

## Trust heuristics I applied

- **Trust by default:** mechanical/boilerplate code, tests written against invariants I stated,
  formatting, refactors with green tests behind them.
- **Verify by hand:** every SQL statement in `JdbcReservationRepository` (the WHERE clauses *are*
  the product), transaction boundaries, anything where the agent cites framework behavior on a
  version newer than its knowledge, and any claim of the form "this can't happen" — those become
  tests (`PartialConfirmGuardIT` exists because of exactly such a claim).

## Representative prompts (condensed)

> *"Design pressure-test, don't approve: lazy expiry + conditional-UPDATE claims, hold row as
> serialization point. Find the holes — TTL renewal? partial-hold rollback? sweeper vs confirm
> interleavings? deadlock risk with `id = ANY(?)`? What breaks under two instances?"*

> *"Write ExpiryConfirmRaceIT first — sweeper disabled throughout; if the design needs the
> sweeper for correctness, I want the test to expose that today, not the grader."*

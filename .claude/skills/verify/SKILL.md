---
name: verify
description: Run the full verification gate (format check + fast test suite) and interpret failures. Use before every commit and whenever asked whether the build is green.
---

# Verify

Run the same gate CI runs:

```bash
./gradlew spotlessCheck test
```

- Requires Docker (Testcontainers starts postgres:16-alpine automatically); `docker info` first if unsure.
- `spotlessCheck` failure → run `./gradlew spotlessApply`, re-run the gate, include the formatting in the same commit as the change that caused it.
- Test failure → read the report under `build/reports/tests/test/index.html`; never mark work done or commit with a red gate.
- The fast suite excludes `@Tag("load")`; if the change touches claim/confirm/release/expiry SQL or transaction boundaries, also run `./gradlew stressTest` before calling it done.

Report the result as: gate green/red, failing tests if any, and what you changed to fix them.

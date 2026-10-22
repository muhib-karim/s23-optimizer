# TASKS — v1.1.0

Status of the frozen acceptance checks. `[x]` = done with evidence; `[ ]` = open.

- [x] 1. Tests: per-action tests and Shizuku command-builder tests (pure Kotlin, modelled shell); Robolectric smoke test; Kover report in CI.
- [x] 2. Safety: journal + one-tap Restore defaults; dry-run preview of exact commands; Shizuku absent/denied/unsupported states with tests.
- [x] 3. Measured value: before/after drain %/h and cold start from on-device counters, CSV export; README "measured vs not".
- [x] 4. CI gates: ktlint, detekt, unit + Robolectric, assembleRelease (signed on tag via existing secrets), gitleaks, Dependabot, CodeQL.
- [ ] 5. Pages: landing + emulator screenshots + features + safety + download; homepage URL set; HTTP 200 verified.
- [x] 6. README homepage, CHANGELOG [1.1.0] - 2026-10-22, versionName 1.1.0 / versionCode 3, SKILLS_USED.md, TASKS.md, HANDOFF.md.
- [ ] 7. Branch pushed via the gated push script, CI green on its head.

## Log
- 2026-10-22: first push (a9bf34b) green on the first run: CI (lint, 71 tests, build), CodeQL, Secret scan.

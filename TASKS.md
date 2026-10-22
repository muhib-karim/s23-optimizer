# TASKS — v1.1.0

Status of the frozen acceptance checks. `[x]` = done with evidence; `[ ]` = open.

- [x] 1. Tests: per-action tests and Shizuku command-builder tests (pure Kotlin, modelled shell); Robolectric smoke test; Kover report in CI.
- [x] 2. Safety: journal + one-tap Restore defaults; dry-run preview of exact commands; Shizuku absent/denied/unsupported states with tests.
- [x] 3. Measured value: before/after drain %/h and cold start from on-device counters, CSV export; README "measured vs not".
- [x] 4. CI gates: ktlint, detekt, unit + Robolectric, assembleRelease (signed on tag via existing secrets), gitleaks, Dependabot, CodeQL.
- [x] 5. Pages: landing + emulator screenshots + features + safety + download; homepage URL set; HTTP 200 verified (curl 200 on the homepage and on screenshots/dashboard.png).
- [x] 6. README homepage, CHANGELOG [1.1.0] - 2026-10-22, versionName 1.1.0 / versionCode 3, SKILLS_USED.md, TASKS.md, HANDOFF.md.
- [ ] 7. Branch pushed via the gated push script, CI green on its head.

## Log
- 2026-10-22: first push (a9bf34b) green on the first run: CI 39725256806 (lint, 71 tests, build), CodeQL, Secret scan. Kover: core 98.7 % lines, total 47.2 %.
- 2026-10-22: 7aa30a2 green incl. first Pages run 39725810056. Its Audit screenshot showed the launcher: a v1.0.0 crash (Long/Int comparator, ClassCastException). Root cause reproduced in AppOrderTest, fixed in 2434351.
- 2026-10-22: review fixes 1639c3a green on all four workflows (CI 39726941937, CodeQL, Secret scan, Pages 39726941883); screenshot job now asserts the app is in front.
- 2026-10-22: Pages enabled (Actions), `upgrade/*` deployment policy added, repository homepage set; homepage returns 200.
- 2026-10-22: coordinator review: CodeQL alert #1 (java/constant-comparison, core/Measured.kt:44) fixed in code; `upgrade/**` removed from the pages.yml trigger.

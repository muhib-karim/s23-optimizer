# PROJECT_BRAIN — S23 Optimizer
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
v1.1.0 on branch `upgrade/v1.1.0`: tests + coverage, safety (restore defaults, dry run, Shizuku absent/denied paths), measured before/after panel with export, CI gates (ktlint, detekt, tests, CodeQL, gitleaks, Dependabot), Pages landing, README homepage. CI green on the branch head; the coordinator merges.

## Status
- Version / branch: 1.1.0 in progress on upgrade/v1.1.0 (from main @ ca08715).
- CI: not yet run for this branch.
- Working: v1.0.0 audit, journal/undo engine, PDF report.
- Broken / open: none known.

## Next step
1. Follow docs/superpowers/plans/2026-10-22-v1.1.0.md task by task.

## Decisions (why things are the way they are)
- 2026-10-22: versionCode 3 for 1.1.0 — code 2 is already used by the separate v2.0.0-alpha1 pre-release (agent-mode branch, left untouched).
- 2026-10-22: lint runs as ktlint/detekt CLI jars in CI, not Gradle plugins — keeps the Kotlin 2.0 compiler out of detekt's classpath and lets agents lint without Gradle.
- 2026-10-22: no Room schema change; battery samples are ordinary snapshots (role SAMPLE) and app-start timings live in SharedPreferences.

## Gotchas
- Gradle, Android builds and emulators never run on the shared dev box; every build/test signal comes from GitHub Actions.
- `workflow_dispatch` only works for workflows on the default branch, so the Pages workflow also triggers on pushes to `upgrade/**`.

## Session log (one line per session, append only)

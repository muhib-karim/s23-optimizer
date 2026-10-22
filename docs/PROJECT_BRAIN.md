# PROJECT_BRAIN — S23 Optimizer
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
v1.1.0 on branch `upgrade/v1.1.0`: tests + coverage, safety (restore defaults, dry run, Shizuku absent/denied paths), measured before/after panel with export, CI gates (ktlint, detekt, tests, CodeQL, gitleaks, Dependabot), Pages landing, README homepage. CI green on the branch head; the coordinator merges.

## Status
- Version / branch: 1.1.0 in progress on upgrade/v1.1.0 (from main @ ca08715).
- CI: green on a9bf34b (CI 71 tests incl. Robolectric, core line coverage 98.7%, total 47.2%; CodeQL; gitleaks).
- Working: Tasks 1-5 of the plan (tests, access gate, measured panel, UI, CI gates). Pages enabled (Actions source), `upgrade/*` allowed to deploy to github-pages.
- Broken / open: emulator screenshot job untested; README/CHANGELOG/docs not written yet.

## Next step
1. Push site + pages.yml; download the emulator-screenshots artifact and commit it into site/screenshots.
2. README homepage, CHANGELOG, SKILLS_USED, TASKS, HANDOFF; set repo homepage; curl 200.

## Decisions (why things are the way they are)
- 2026-10-22: versionCode 3 for 1.1.0 — code 2 is already used by the separate v2.0.0-alpha1 pre-release (agent-mode branch, left untouched).
- 2026-10-22: lint runs as ktlint/detekt CLI jars in CI, not Gradle plugins — keeps the Kotlin 2.0 compiler out of detekt's classpath and lets agents lint without Gradle.
- 2026-10-22: no Room schema change; battery samples are ordinary snapshots (role SAMPLE) and app-start timings live in SharedPreferences.

## Gotchas
- Core tests can be run locally without Gradle: compile `core/` + core tests with a standalone kotlinc 2.0.21 and run JUnit (about 0.5 GB heap).
- Adding a constructor parameter after a lambda parameter steals the trailing-lambda slot; ChangeEngine's `access` sits before `policy` for that reason.
- Gradle, Android builds and emulators never run on the shared dev box; every build/test signal comes from GitHub Actions.
- `workflow_dispatch` only works for workflows on the default branch, so the Pages workflow also triggers on pushes to `upgrade/**`.

## Session log (one line per session, append only)

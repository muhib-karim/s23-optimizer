# PROJECT_BRAIN — S23 Optimizer
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
v1.1.0 on branch `upgrade/v1.1.0`: tests + coverage, safety (restore defaults, dry run, Shizuku absent/denied paths), measured before/after panel with export, CI gates (ktlint, detekt, tests, CodeQL, gitleaks, Dependabot), Pages landing, README homepage. CI green on the branch head; the coordinator merges.

## Status
- Version / branch: 1.1.0 on upgrade/v1.1.0 (not merged, not tagged).
- CI: all four workflows green on 1639c3a; final docs/screenshots commit verified before PU_DONE.
- Working: every plan task. Pages live and 200; repo homepage set; github-pages env allows `upgrade/*` (remove after merge, see HANDOFF).
- Broken / open: no physical-device run with Shizuku yet.
- Screenshots in site/screenshots come from Pages run 39726941883 (emulator, no Shizuku).

## Next step
1. Coordinator: merge, tag v1.1.0, remove the `upgrade/*` Pages branch policy.

## Decisions (why things are the way they are)
- 2026-10-22: versionCode 3 for 1.1.0 — code 2 is already used by the separate v2.0.0-alpha1 pre-release (agent-mode branch, left untouched).
- 2026-10-22: lint runs as ktlint/detekt CLI jars in CI, not Gradle plugins — keeps the Kotlin 2.0 compiler out of detekt's classpath and lets agents lint without Gradle.
- 2026-10-22: no Room schema change; battery samples are ordinary snapshots (role SAMPLE) and app-start timings live in SharedPreferences.

## Gotchas
- `foregroundMs ?: -1` in a comparator mixed Long/Int and crashed Audit details; sort lives in core/AppOrder with -1L.
- Core tests can be run locally without Gradle: compile `core/` + core tests with a standalone kotlinc 2.0.21 and run JUnit (about 0.5 GB heap).
- Adding a constructor parameter after a lambda parameter steals the trailing-lambda slot; ChangeEngine's `access` sits before `policy` for that reason.
- Gradle, Android builds and emulators never run on the shared dev box; every build/test signal comes from GitHub Actions.
- `workflow_dispatch` only works for workflows on the default branch, so the Pages workflow also triggers on pushes to `upgrade/**`.

## Session log (one line per session, append only)
- 2026-10-22 05:25Z Claude Code/claude-opus-5-5: v1.1.0 built on upgrade/v1.1.0 (tests, access gate, restore, dry run, measured panel, CI gates, Pages, README); Audit crash found by CI screenshots and fixed; next: coordinator merge + tag v1.1.0, remove upgrade/* Pages policy.

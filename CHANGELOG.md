# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-10-22

### Added
- **Restore defaults**: one tap undoes every pending journaled change, newest first, with a *Preview restore commands* dry run. Values changed by hand since stay untouched and pending.
- **Dry run** in the confirm dialog: the exact read, apply, verify and undo commands for each change, copyable as text, before anything runs.
- **Shizuku access states**: not installed, not running, permission needed, permission denied and unsupported mode, each with its own message and action button (Get Shizuku, Open Shizuku, Grant permission, Check again).
- **Measured screen**: battery drain in %/h and this app's cold-start time, before vs after the first applied tweak, from on-device counters; optional hourly battery samples (off by default); **CSV export** with every raw sample.
- Homepage on GitHub Pages with screenshots captured from the debug build on an emulator in CI, and an interactive, source-linked architecture diagram.
- Tests: exact-argv tests for the Shizuku command builder, an end-to-end test per optimizer action against a modelled device, access-state and engine-gate tests, measurement and CSV tests, and a Robolectric UI smoke test. Coverage with Kover in CI.
- CI gates: ktlint, detekt, CodeQL (java-kotlin), gitleaks (full history), Dependabot for Gradle and Actions.
- Project docs: `AGENTS.md`, `docs/PROJECT_BRAIN.md`, `SKILLS_USED.md`, `TASKS.md`, `HANDOFF.md`, design spec and plan under `docs/superpowers/`.

### Changed
- `ChangeEngine` refuses review, apply, undo and restore without Shizuku access, before any shell call or journal write.
- *Undo all pending changes* is now **Restore defaults**; History and the snapshot pickers hide battery-sample rows.
- `release.yml` runs only for tags and shares the Android setup action; branch builds moved to `ci.yml`. The signing steps are unchanged.
- GitHub Actions moved to their Node 24 majors.
- README rewritten as the project homepage: tweak table with undo, measured vs not measured, Shizuku install steps, architecture, FAQ.
- versionName 1.1.0, versionCode 3 (code 2 belongs to the separate v2.0.0-alpha1 pre-release).

### Fixed
- History no longer crashes on a journal row whose command cannot be rebuilt; it shows the record as not restorable automatically.

## [1.0.0] - 2026-10-17

### Added
- Read-only audit with a measured condition score, before/after comparison and PDF export.
- Journaled, reversible Shizuku changes: background app-op, standby bucket, allowlisted disable and animation scales.
- Samsung settings shortcuts, optional daily audit, signed release workflow.

[Unreleased]: https://github.com/muhib-karim/s23-optimizer/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/muhib-karim/s23-optimizer/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/muhib-karim/s23-optimizer/releases/tag/v1.0.0

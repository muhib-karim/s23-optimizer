# AGENTS.md — project rules for coding agents (Codex, Claude Code, others)

## Project
- What it is: a no-root Android app that audits a Galaxy S23 Ultra (any Android 10+ phone), applies a few reviewed, journaled and reversible Shizuku tweaks, and shows measured before/after numbers.
- Stack: Kotlin 2.0, Jetpack Compose (Material 3), Room, WorkManager, Shizuku API 13; Gradle 8.10.2 + AGP 8.7.
- Entry points: `app/src/main/java/dev/muhib/s23optimizer/` — `core/` (pure Kotlin, no Android), `data/` (Room), `platform/` (Android + Shizuku), `ui/` (Compose). Landing page in `site/`.

## Commands
- Build: `gradle --no-daemon assembleDebug` — CI only (GitHub Actions). Never run Gradle, the Android build or an emulator on the shared dev box.
- Test: `gradle --no-daemon testDebugUnitTest koverXmlReportDebug` (JVM + Robolectric) — CI only.
- Lint/format: `ktlint "app/src/**/*.kt"` and `detekt-cli --config config/detekt/detekt.yml --input app/src` (CLI tools, light; also the CI `lint` job).
- Release: push a `vMAJOR.MINOR.PATCH` tag; `.github/workflows/release.yml` builds the signed APK from repository secrets.

## Rules
- Tests first; CI green before any merge or tag. Never force-push.
- Run a secret scan (gitleaks) before every push. No secrets, tokens or machine paths in the repo.
- Keep changes small and committed: one working step = one commit, so a killed session loses one step at most.
- Every shell mutation goes through `ChangeEngine`: read prior value, journal it, mutate, verify. No new command without an exact undo in `Commands.undo`.
- Never claim a gain that was not measured on the device; unknown values stay "unavailable", never zero.
- Licence notes: Apache-2.0. Shizuku API is Apache-2.0. Do not copy code from GPL/AGPL projects.

## Memory
- `docs/PROJECT_BRAIN.md` is this repo's persistent memory. Read it first. Update Status / Next step after each meaningful step, and append a Session log line before you stop. Commit it with your work.
- Decisions that should outlive this repo go to the `memory` MCP tool, named by project.

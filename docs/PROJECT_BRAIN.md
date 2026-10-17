# PROJECT_BRAIN — S23 Optimizer

## Goal
Implement the approved Agent Mode plan with fixture-first tests, confirmed journaled actions, encrypted credentials, and a matching MCP companion.

## Status
- Branch: agent-mode; target version 2.0.0-alpha1 (code 2).
- Fixture-first JVM contracts, power parsers, typed proposal-only model loop, Keystore settings and Agent UI implemented.
- Force-stop is journaled before execution, excluded from Undo, and clearly labeled non-revertible.
- Companion parser, validation, journal and protocol tests: 18 passed locally; required `node --test mcp/` also exits successfully.
- Independent review completed; fixed overflow PSS becoming zero and UID appop modes being mistaken for package undo state, with regressions.
- README, MCP pairing guide, CI checks and version bump are complete. Existing release workflow is unchanged.
- JVM and Android verification must run in CI; local Gradle is prohibited.

## Next step
1. Run CI JVM tests and assembleDebug before installing; compilation remains unverified locally by instruction.
2. Smoke-test Shizuku, Keystore settings, provider tool calls, confirm/undo and measured deltas on a phone.

## Decisions
- 2026-10-17: Keep existing app eligibility checks and prior-state checks; force-stop is explicitly non-revertible.
- 2026-10-17: Use HttpURLConnection and Keystore AES-GCM; no model SDK or remote bridge.
- 2026-10-17: Fixtures are hand-written representative transcripts, not claimed captures from an attached device.

## Gotchas
- No local Android SDK; do not claim JVM tests or compilation passed without CI.
- No commits or pushes for this task.
- This sandbox does not relay Node child-process pipe output; stream tests exercise MCP framing in-process, with a direct shell handshake as a smoke check.
- The Gradle launcher was not executable; its executable bit is now set for the required CI `./gradlew` commands.

## Session log
- 2026-10-17 22:35Z Codex: reviewed architecture and test contract; next: fixtures and tests.
- 2026-10-17 22:37Z Codex: implemented Agent Mode, shared fixtures/tests, MCP companion, docs and CI; 18 Node tests pass, JVM/build and device smoke tests remain for CI/device; no commits or pushes.
- 2026-10-17 22:40Z: Codex pass 1 (agent mode build) done: power parsers + fixtures, typed agent tools, proposal-only model clients, Agent tab, journal/undo integration, MCP companion server (18 Node tests pass). Gradle not run locally; CI to confirm. Not yet tested on a real phone.
- 2026-10-17 22:53Z: CI green on PR #1; release.yml now accepts pre-release tags (non-latest); v2.0.0-alpha1 pre-release published with signed APK. Device test still pending.

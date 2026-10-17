# Project rules

## Project
- S23 Optimizer: Android diagnostics and explicitly confirmed power changes.
- Kotlin, Jetpack Compose, Shizuku, Room; companion Node ESM MCP server.
- Read docs/PROJECT_BRAIN.md, docs/AGENT_MODE_PLAN.md and docs/TESTS_FIRST.md first.

## Commands
- Android tests/build: `./gradlew testDebugUnitTest assembleDebug` in CI only. Do not run Gradle locally.
- Companion tests: `node --test mcp/` (Node 20 or newer).
- Existing tagged release workflow: .github/workflows/release.yml.

## Rules
- Tests first. No arbitrary shell strings from models. Confirm writes individually.
- Apache-2.0; no code copied from GPL, AGPL, LGPL or unlicensed projects.
- No secrets, personal emails or absolute machine paths in tracked files.
- This task leaves changes in the working tree: do not commit or push.

## Memory
- Update Status and Next step in docs/PROJECT_BRAIN.md after meaningful steps.
- Append a short Session log entry before stopping; keep it free of secrets and personal details.

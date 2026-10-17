# Build pass 1 test contract

The JVM contract tests and synthetic fixtures were written before production Kotlin sources.
They cover strict shell and battery-stat parsing, missing data, Android UID decoding,
14-day idle evidence, critical-app exclusions, exact allowlisting and opt-in, command
validation, journal recovery and reverse/idempotent undo, measured-only scoring,
and report comparisons with changed sources or cumulative counters.

Local execution is intentionally prohibited for this build pass. No red or green run
is claimed. CI must run `gradle testDebugUnitTest assembleDebug` before installation.
See `CoreContractTest.kt` and `JournalEngineTest.kt` for executable acceptance cases.

## Agent Mode contract

The shared power fixtures and `PowerParserTest`, `AgentContractTest`,
`AgentJournalTest`, and `AgentReadToolsTest` were written before their respective
production implementations. They exercise Android UID decoding, shared UID
accounting, partial wakelocks, alarm/job counts, Samsung variants, unavailable
fields, typed tool validation, strict model JSON, native provider tool envelopes,
bounded snapshots, read-only collection, cancellation and safe measured deltas.
Force-stop tests require durable insertion before execution and forbid fake undo.

`mcp/` uses the same fixture files for Node parser tests, plus tool-validation,
durable-journal and MCP stream tests. Its directory entry supports `node --test mcp/`
on Node 20+. Node tests may run locally; they use no real adb connection. Fixtures
are realistic synthetic transcripts, explicitly identified as such in their README.

No local JVM red/green run or Android compilation is claimed. CI must run both
`./gradlew testDebugUnitTest` and `./gradlew assembleDebug` before installation.
Keystore encryption and Compose/device behavior require an actual Android smoke
test: save/reload/forget a key, rotate during analysis, deny Shizuku permission,
confirm/skip an eligible action, undo it, confirm the non-revertible force-stop
warning, and inspect measured deltas after a charge-window reset.

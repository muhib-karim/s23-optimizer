# Build pass 1 test contract

The JVM contract tests and synthetic fixtures were written before production Kotlin sources.
They cover strict shell and battery-stat parsing, missing data, Android UID decoding,
14-day idle evidence, critical-app exclusions, exact allowlisting and opt-in, command
validation, journal recovery and reverse/idempotent undo, measured-only scoring,
and report comparisons with changed sources or cumulative counters.

Local execution is intentionally prohibited for this build pass. No red or green run
is claimed. CI must run `gradle testDebugUnitTest assembleDebug` before installation.
See `CoreContractTest.kt` and `JournalEngineTest.kt` for executable acceptance cases.

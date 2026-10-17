# S23 Optimizer

An Android app that audits a Samsung Galaxy S23 Ultra (or any Android 10+ phone), makes reviewed power changes through Shizuku, and shows a **measured before/after report**. Agent Mode adds optional model-assisted analysis. No root or PC is required for the app.

## What it does

- **Audit (read-only).** Battery drain per app (`dumpsys batterystats --charged` via Shizuku, plus UsageStats), app standby buckets, storage, RAM, thermal status and CPU frequency (`/sys/devices/system/cpu`). Each audit gives a score.
- **Before/after report.** Run an audit, apply changes, use the phone normally, audit again. The app compares the two snapshots and exports the report as a PDF.
- **Reviewed changes (Shizuku).** Every change is shown on a confirm screen first. Reversible settings save their previous value in a local journal for one-tap undo. Agent Mode can also propose a separately confirmed, explicitly non-revertible force-stop.
- **Samsung settings shortcuts.** Opens Adaptive battery, Protect battery (85%), Performance profile (Light), Motion smoothness (adaptive refresh) and Deep sleeping apps. You make those changes yourself in Samsung's own screens.

## Honest limits

- **Shizuku must be paired again after every reboot** (wireless debugging is turned off when the phone restarts). With Shizuku stopped, the audit still runs with less detail, and changes are disabled.
- **The app never claims gains it did not measure.** The report shows only the difference between two real audits. Battery figures depend on how you use the phone between audits.
- Some readings (wakelocks, some thermal zones) are hidden by Android or Samsung on some firmware. The app shows these as "unavailable" and does not guess.
- No root tricks, no kernel tweaks, no "RAM cleaner" or force-stop sweeps. Agent Mode can propose stopping one app; the owner must confirm and the UI says it cannot be undone.

## Install

1. Open [Releases](../../releases/latest) on your phone and download `S23-Optimizer-v1.0.0.apk` (a `.sha256` file is next to it).
2. Allow "Install unknown apps" for your browser when Android asks, then install.
3. Open S23 Optimizer. Grant **Usage access** when asked (needed for per-app usage).

## Pair Shizuku (on the phone, no PC)

1. Install **Shizuku** from Google Play.
2. Settings > About phone > Software information: tap **Build number** 7 times to turn on Developer options.
3. Settings > Developer options: turn on **Wireless debugging** (Wi-Fi must be on).
4. In Shizuku, tap **Pairing**, then in Wireless debugging tap **Pair device with pairing code**. Enter the code in Shizuku's notification.
5. In Shizuku, tap **Start**. Back in S23 Optimizer, allow it when Shizuku asks.

After a reboot, repeat steps 3 and 5 (pairing is remembered; you only need to start it again).

## Agent Mode (2.0.0-alpha1)

Agent Mode reads real battery data, explains it, and returns **proposals only**.
It can propose a rare/restricted standby bucket, background restriction, or
force-stop for an installed package. It cannot submit arbitrary shell commands,
disable/uninstall packages, or change settings outside those three tools.

### On the phone

1. Pair and start Shizuku using the steps above, then grant this app permission.
2. Open **Agent → Model settings** (also available in Settings). Select Anthropic
   or OpenAI-compatible, enter the model ID, HTTPS API base URL including its
   version path (usually `/v1`), and your API key. The endpoint must support tool
   calls and a named tool choice. Keys are encrypted with Android Keystore AES-GCM;
   changing provider or endpoint requires entering the key again.
3. Tap **Analyze**. Only this action sends a bounded summary of measurements,
   package names and process activity to the chosen provider. No automatic model
   calls run in the background. API keys are never logged or included in exports/PDFs.
4. Read the findings. Tap **Apply** on an individual proposal to open the existing
   exact-command confirm screen, or **Skip**. Current installed-package, protected-app
   and 14-day idle-evidence checks still apply; model suggestions cannot bypass them.
5. Reversible changes have **History / Undo**. **Force-stop cannot be undone**;
   it interrupts work and notifications until you reopen the app. Its execution
   is journaled but never represented as a reversible setting.
6. Tap **Re-measure** later under comparable usage. Before/after deltas come from
   two observations; cumulative counters are compared only within the same known
   charge window without resets. They show additional consumption, not savings
   caused by an action. The Agent baseline lasts for this app session.

Measurements cover battery overview, UID drain estimates, partial wakelocks,
alarm/job counts, standby buckets, background appops and three-hour process PSS.
The model snapshot is capped at 6,000 UTF-8 bytes (conservatively below the intended
roughly 6k-token budget), with per-tool truncation markers. Background appops sample
up to 30 user-installed apps, prioritized by estimated UID drain. Missing or denied
data remains unavailable; memory use is not a measure of energy. Model advice can
be wrong and never serves as evidence of a measured gain.

### From a computer with MCP

Install Node 20+ and Android platform-tools, then enable wireless debugging on
the phone. With both devices on the same Wi-Fi network, use the pairing port from
**Pair device with pairing code**, then the connection port from the main
Wireless debugging screen:

```sh
adb pair PHONE_IP:PAIRING_PORT
adb connect PHONE_IP:DEBUGGING_PORT
adb devices
claude mcp add s23-power -- node mcp/server.mjs
```

Run the registration command and client from this repository root. No npm install
is required. The companion exposes the same seven read tools and three allowed
write tools. Each write requires `confirm: true` and owner approval in the client,
and prints its exact adb command. It keeps a separate durable local journal with
manual restore commands. See [mcp/README.md](mcp/README.md) for device selection,
reconnection, approval behavior and undo limits.

## Every shell command the app may run (through Shizuku)

Read-only:
- `dumpsys battery`
- `dumpsys batterystats --charged`
- `dumpsys procstats --hours 3`
- `dumpsys package <pkg>`
- `cmd appops get --user 0 <pkg> RUN_ANY_IN_BACKGROUND`
- `am get-standby-bucket --user 0 [<pkg>]`
- `settings get global <window_animation_scale|transition_animation_scale|animator_duration_scale>`
- `cat <file under /sys/devices/system/cpu>`

Changes (each one only after you confirm it):
- `cmd appops set --user 0 <pkg> RUN_ANY_IN_BACKGROUND ignore` (stop background running for that app)
- `am set-standby-bucket --user 0 <pkg> rare|restricted`
- `pm disable-user --user 0 <pkg>`, **only** for packages on the built-in allowlist: `com.facebook.appmanager`, `com.facebook.services`, `com.facebook.system`, `com.samsung.android.app.spage`, `com.samsung.android.arzone`, `com.samsung.android.bixby.agent`, `com.samsung.android.game.gamehome`
- `settings put global <animation scale key> 0.5`
- `am force-stop --user 0 <pkg>` (Agent Mode only, explicitly non-revertible)

## Undo

Tap **Undo / retry this change** on any journaled change, or **Undo all pending changes**. The app writes back the saved value:
- app op: `cmd appops set --user 0 <pkg> RUN_ANY_IN_BACKGROUND <previous mode>`
- standby: `am set-standby-bucket --user 0 <pkg> <previous bucket>`
- disabled app: `pm enable --user 0 <pkg>` (and `pm default-state --user 0 <pkg>` if it was in its default state)
- animation: `settings put global <key> <previous value>`, or `settings delete global <key>` if it was unset

You can also undo by hand: re-enable a disabled app in Settings > Apps (filter "Disabled"), and reset animation scales in Developer options.

Force-stop journal entries have no Undo button and are excluded from Undo all.

## Build

CI builds and tests on GitHub Actions. The existing `.github/workflows/release.yml`
continues to handle stable `vMAJOR.MINOR.PATCH` releases. Agent Mode pushes and PRs
run `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and `node --test mcp/`
in `.github/workflows/agent-mode.yml`; debug APKs and JVM reports are artifacts.
For this build pass, do not run Gradle locally: CI is the compiler (JDK 17, SDK 35,
Gradle 8.10.2). Node fixture tests may run locally without a phone or Android SDK.

## License

Apache License 2.0. See [LICENSE](LICENSE). Not affiliated with Samsung or Shizuku.

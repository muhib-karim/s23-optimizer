# S23 Optimizer

An Android app that audits a Samsung Galaxy S23 Ultra (or any Android 10+ phone), makes a small set of **reversible** speed and battery changes through Shizuku, and shows a **measured before/after report**. No root, no PC.

## What it does

- **Audit (read-only).** Battery drain per app (`dumpsys batterystats --charged` via Shizuku, plus UsageStats), app standby buckets, storage, RAM, thermal status and CPU frequency (`/sys/devices/system/cpu`). Each audit gives a score.
- **Before/after report.** Run an audit, apply changes, use the phone normally, audit again. The app compares the two snapshots and exports the report as a PDF.
- **Reversible changes (Shizuku).** Every change is shown on a confirm screen first. The previous value is saved in a local journal before anything changes, so one tap undoes it.
- **Samsung settings shortcuts.** Opens Adaptive battery, Protect battery (85%), Performance profile (Light), Motion smoothness (adaptive refresh) and Deep sleeping apps. You make those changes yourself in Samsung's own screens.

## Honest limits

- **Shizuku must be paired again after every reboot** (wireless debugging is turned off when the phone restarts). With Shizuku stopped, the audit still runs with less detail, and changes are disabled.
- **The app never claims gains it did not measure.** The report shows only the difference between two real audits. Battery figures depend on how you use the phone between audits.
- Some readings (wakelocks, some thermal zones) are hidden by Android or Samsung on some firmware. The app shows these as "unavailable" and does not guess.
- No root tricks, no kernel tweaks, no "RAM cleaner". It never force-stops or kills apps.

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

## Every shell command the app may run (through Shizuku)

Read-only:
- `dumpsys batterystats --charged`
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

## Undo

Tap **Undo / retry this change** on any journaled change, or **Undo all pending changes**. The app writes back the saved value:
- app op: `cmd appops set --user 0 <pkg> RUN_ANY_IN_BACKGROUND <previous mode>`
- standby: `am set-standby-bucket --user 0 <pkg> <previous bucket>`
- disabled app: `pm enable --user 0 <pkg>` (and `pm default-state --user 0 <pkg>` if it was in its default state)
- animation: `settings put global <key> <previous value>`, or `settings delete global <key>` if it was unset

You can also undo by hand: re-enable a disabled app in Settings > Apps (filter "Disabled"), and reset animation scales in Developer options.

## Build

CI builds and tests on GitHub Actions (`.github/workflows/release.yml`). A `v*` tag builds a signed release APK and attaches it to the GitHub Release. Locally: JDK 17, Android SDK 35, then `gradle testDebugUnitTest assembleDebug`.

## License

Apache License 2.0. See [LICENSE](LICENSE). Not affiliated with Samsung or Shizuku.

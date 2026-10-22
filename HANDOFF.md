# HANDOFF — s23-optimizer v1.1.0

For the coordinator who reviews and merges `upgrade/v1.1.0` into `main`. Nothing here was merged, tagged or released.

## State
- Branch `upgrade/v1.1.0`, based on `main` @ ca08715 (v1.0.0). The `agent-mode` branch and the `v2.0.0-alpha1` tag were not touched.
- versionName 1.1.0, versionCode 3 (2 is taken by the v2.0.0-alpha1 pre-release build).
- CI on the branch head: CI (ktlint, detekt, 76 tests (75 JVM + 1 Robolectric), Kover, debug + release APKs), CodeQL with 0 open alerts, Secret scan. Pages ran on earlier branch commits and now runs from main only. See TASKS.md for run IDs.
- Homepage: https://muhib-karim.github.io/s23-optimizer/ (HTTP 200), set as the repository website.

## Repository settings changed (outside git)
1. GitHub Pages enabled with **GitHub Actions** as the source.
2. `github-pages` environment: a deployment branch policy `upgrade/*` was added next to `main`, so this branch could deploy once and the 200 could be verified before merge. `pages.yml` now triggers on `main` only (the `'upgrade/**'` trigger was dropped in the CodeQL-fix commit), so no branch other than main tries to deploy. **The coordinator removes the `upgrade/*` policy after merge** (Settings → Environments → github-pages). The live site is the last deploy from this branch (Pages run 39727775346) until the first main deploy replaces it.
3. Repository homepage set to the Pages URL.

## To release v1.1.0 after merge
1. Merge `upgrade/v1.1.0` into `main` and wait for CI green on `main`.
2. Tag `v1.1.0` on the merge commit and push the tag. `release.yml` runs the tests, builds the signed APK from the existing secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`; the steps are unchanged) and attaches the APK and its `.sha256` to the GitHub Release.
3. The homepage's Download button points to `releases/latest`, which shows v1.0.0 until that tag exists.

## Review findings and what happened to them
An independent review of ca08715..7fd601f returned "with fixes":
- Fixed: the before/after split vanished after Restore defaults (now a window that closes at the restore); unbounded hourly samples decoded on the main thread (decode on Default, 30-day pruning via a DAO delete query, no schema change); Restore and undo stayed enabled without Shizuku (now disabled, with the access panel); cold-start panel refreshed one resume late; README FAQ wording on restore conflicts; emulator battery staging is now disclosed in the captions; screenshots committed; HANDOFF written.
- Resolved after the coordinator's review: `upgrade/**` no longer deploys to Pages (trigger dropped; see above). The double sample when hourly sampling is switched on (one manual, one from the first periodic run) is harmless.
- CodeQL alert #1 `java/constant-comparison` ("Test is always true", `core/Measured.kt:44`): `DrainMeter.rate` mutated `millis` inside a `zipWithNext { }` lambda, and CodeQL saw only the captured local's initial `0L`. Fixed in code (no dismissal): the qualifying windows are now computed as a value and summed, with no mutation inside a lambda. Behaviour is unchanged and pinned by the existing `MeasuredTest` cases (removing the no-level-rise condition makes one fail).
- Found separately by the CI emulator screenshots: Audit details crashed with `ClassCastException` whenever Usage access was granted (a v1.0.0 bug). Fixed with a regression test; the screenshot job now fails if the app is not in front.

## Known limits
- No device test on a physical S23 Ultra in this pass; CI covers the JVM, Robolectric and an emulator without Shizuku. Real Shizuku paths (apply, verify, restore on One UI) still need a phone run.
- Whole-app line coverage is 47.6%: `core/` is at 98.5%, while the Android adapters (`platform/`, `data/`) are exercised only on devices.
- ktlint and detekt are tuned to the existing compact style; the relaxed rules and their reasons are in `.editorconfig` and `config/detekt/detekt.yml`.
- The screenshots show emulator values (battery set to 78%, discharging) and no Shizuku.

## Where things are
- Plan and spec: `docs/superpowers/plans/2026-10-22-v1.1.0.md`, `docs/superpowers/specs/2026-10-22-v1.1.0-design.md`.
- Agent memory: `AGENTS.md`, `docs/PROJECT_BRAIN.md`.
- Skills: `SKILLS_USED.md`. Acceptance status: `TASKS.md`.

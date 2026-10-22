# Skills used

Which agent skill drove which change, per release. Skills are listed only where they changed the work.

## v1.1.0

| Skill | What it drove |
|---|---|
| superpowers: brainstorming | Confirmed the frozen task already answered the discovery questions; recorded the one build call (keep Kotlin/Compose/Room/Shizuku; Gradle only on Actions). |
| superpowers: writing-plans | `docs/superpowers/specs/2026-10-22-v1.1.0-design.md` and `docs/superpowers/plans/2026-10-22-v1.1.0.md`: seven tasks with interfaces, review focus (Shizuku dying mid-apply, hand-changed values, hidden charges, one-sided data, metacharacters in previews). |
| superpowers: test-driven-development | Every `core/` change went red → green locally with a standalone kotlinc + JUnit run (no Gradle): `CommandsTest`, `OptimizerActionsTest` with the `DeviceShell` device model, `DryRunTest`, `AccessTest`, `MeasuredTest`, `StartLogTest`. The red runs also caught two bugs in the test fake and one in a test's pivot arithmetic before any production code was written. |
| superpowers: systematic-debugging | Used on CI failures; see TASKS.md for each root cause and fix. |
| superpowers: verification-before-completion | Nothing is reported as done without its evidence: CI run IDs, Kover numbers from the downloaded report, `curl` status of the homepage. |
| superpowers: requesting-code-review | Independent review of the branch diff before the final push; findings and dispositions in HANDOFF.md. |
| ui-ux-pro-max | Design-system query for the landing page (Swiss minimal, dark + light, trust-first structure, pre-delivery checklist: contrast, focus, reduced motion, 375–1440 px). |
| impeccable | Landing-page direction and craft floor (no eyebrows, no card grids, real commands as the only monospace, themed selection/focus/scrollbar, one authored motion moment). Its detector flagged an overused display face, a cream light background and a flush table edge; all three were changed (Archivo, cool teal paper, inset). |
| archify | `site/architecture.html`: repository-backed architecture diagram pinned to commit 7aa30a2 with source line ranges on every component; passed validate, deliver, strict check and browser-check. The README mermaid mirrors it. |

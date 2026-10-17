# Agent Mode plan (v2, personal use)

Goal: an AI agent reads the phone's real power data, explains what is draining the
battery, and applies fixes only after the owner confirms each one, with one-tap undo.
Personal use first; no public store release.

## Prior art reviewed (ideas only; no code copied)

| Project | Licence | What we take from it |
|---|---|---|
| [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) | Apache-2.0 | MCP tool shape for driving Android over ADB from Claude / any MCP client |
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) (formerly droidrun) | MIT | LLM-agnostic agent loop: observe, plan, act, verify |
| [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp) | MIT | MCP server running *on the phone*, token-lean tool outputs |
| [minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use) | Apache-2.0 | Separating "read" tools from "act" tools, step verification |
| [google/battery-historian](https://github.com/google/battery-historian) | Apache-2.0 | Which batterystats sections matter: wakelocks, job/alarm counts, per-UID drain, radio/mobile active time |
| [asksven/BetterBatteryStats](https://github.com/asksven/BetterBatteryStats) | no licence file | Reference-point diffs (since unplugged / since last check) and kernel vs partial wakelock split. Ideas only |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | Already our privilege layer (adb-level shell without root) |
| [samolego/Canta](https://github.com/samolego/Canta) | LGPL-3.0 | UX of reversible per-app actions via Shizuku. Ideas only |

Nobody we found combines (a) batterystats analysis, (b) an LLM that explains it, and
(c) reversible fixes behind a confirm. Automation agents (mobilerun, mobile-use) tap the UI;
battery tools (Historian, BBS) only report. Agent Mode joins the two.

## Options considered

1. **MCP server on a PC + wireless adb.** Claude Code / Claude Desktop on a laptop talks
   to the phone over Wi-Fi debugging. Strong models, no key on the phone. Needs the
   laptop on the same network and wireless debugging re-paired after reboots.
2. **In-app agent loop.** The app calls a model API with a fixed tool set that runs through
   Shizuku. Works anywhere, phone only. Needs the owner's own API key stored in the
   Android Keystore.
3. **Remote bridge to a hosted agent.** The phone exposes tools over a tunnel so a remote
   session drives it. Rejected for now: an internet-reachable adb-level shell is too much
   attack surface for the gain.

## Recommendation

Build **one tool layer** and expose it two ways:

- **Primary: in-app Agent Mode (option 2).** Lives in the existing app, reuses Shizuku,
  the change journal and one-tap undo. Model provider is configurable (Anthropic or any
  OpenAI-compatible endpoint), key kept in Keystore-backed storage, never logged.
- **Companion: `mcp/` server (option 1).** A small Node MCP server with the same read tools
  and the same allow-listed write actions over `adb`, so Claude Code on a PC can run the
  identical analysis. Writes still require explicit confirmation in the client.

## Tool set (identical in both)

Read-only:
- `battery_overview` – level, health, temperature, screen-on/off drain since charge (`dumpsys battery`, `dumpsys batterystats --charged`)
- `top_drainers` – per-UID estimated mAh, mapped to package names
- `wakelocks` – top partial wakelocks by held time and count
- `alarms_jobs` – apps with most alarms / job runs
- `standby_buckets` – `am get-standby-bucket` for user apps
- `background_restrictions` – `appops get <pkg> RUN_ANY_IN_BACKGROUND`
- `procstats` – memory-heavy background processes (`dumpsys procstats --hours 3`)

Write (each one: proposal → confirm screen → apply → journal → one-tap undo):
- `set_standby_bucket(pkg, rare|restricted)`
- `restrict_background(pkg)` (appops RUN_ANY_IN_BACKGROUND ignore)
- `force_stop(pkg)` (undo = nothing to revert; marked as such)

Explicitly not allowed: uninstall/disable packages, settings outside the allow-list,
any arbitrary shell string from the model. The model can only name a tool and its typed
arguments; the app validates package names against the installed list.

## Agent loop

1. Collect a snapshot with the read tools (bounded output, summarised per tool).
2. Send snapshot + system prompt to the model; it returns findings and proposed actions as JSON.
3. Show findings in plain words; each action card has Apply / Skip.
4. Apply runs through the existing ChangeEngine (journalled, undoable).
5. Re-measure later and show before/after. No gain is claimed until measured.

## Connecting the phone

- In-app: start Shizuku (wireless debugging), open Agent Mode, paste an API key once.
- PC/MCP: enable wireless debugging, `adb pair` then `adb connect`, add the server to
  Claude Code (`claude mcp add s23-power -- node mcp/server.mjs`).

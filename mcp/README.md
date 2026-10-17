# S23 Power MCP companion

Node **20+**, Android platform-tools (`adb`) on PATH, and a phone with wireless
debugging enabled. No npm install or API key is needed by this server. Your MCP
client manages its model connection. This is a stdio server, not a network bridge.

## Pair and connect

Keep the computer and phone on the same Wi-Fi network. On the phone, enable
Developer options, open **Wireless debugging**, then **Pair device with pairing code**.
Use the pairing address and port shown in that dialog:

```sh
adb pair PHONE_IP:PAIRING_PORT
```

Enter the displayed pairing code when adb prompts. Close the pairing dialog and
use the **IP address & Port** on the main Wireless debugging screen (a different
port) to connect:

```sh
adb connect PHONE_IP:DEBUGGING_PORT
adb devices
```

Run these commands from the repository root, and use the MCP client from that
directory so the relative script path resolves:

```sh
claude mcp add s23-power -- node mcp/server.mjs
claude mcp list
```

With multiple devices, set `ANDROID_SERIAL` to the exact device listed by
`adb devices` in the MCP server environment, for example:

```sh
claude mcp add s23-power -e ANDROID_SERIAL=PHONE_IP:DEBUGGING_PORT -- node mcp/server.mjs
```

Wireless debugging must be re-enabled after reboot; reconnect using the current
port and pair again if Android has forgotten the computer. The companion uses
owner-profile packages (`--user 0`) and does not require Shizuku or the Android app.

## Tools and confirmation

| Read tool | Data |
|---|---|
| `battery_overview` | Charge level, OS health flag, temperature, screen-on/off discharge since charge |
| `top_drainers` | Estimated mAh per UID, with installed package mapping |
| `wakelocks` | Partial lock name, UID, total held milliseconds, count |
| `alarms_jobs` | Wakeup alarm and job counts per UID |
| `standby_buckets` | User-app standby buckets |
| `background_restrictions` | Appops for up to 30 user apps ranked by estimated drain |
| `procstats` | Sampled process PSS over the last three hours |

Read tools accept `{}`. Outputs are bounded and mark unavailable or truncated
data. Empty sections are unknown, not proof of no activity. A UID may belong to
multiple packages; its energy estimate is not counted separately for each app.

Write examples (client tool arguments, not shell text):

```json
{"pkg":"org.example.sync","bucket":"rare","confirm":true}
```

The object above is for `set_standby_bucket`; `bucket` must be `rare` or
`restricted`. `restrict_background` and `force_stop` accept only `pkg` and
`confirm`. The package must be currently installed for user 0. Extra arguments,
raw shell strings and truthy substitutes such as `"true"` are rejected.

Keep per-call write approvals enabled in your MCP client. `confirm: true` is an
explicit gate, not proof that a human clicked approval; the client must ask the
owner before supplying it. No server can independently verify that client UI.
Restricting apps can delay notifications, syncing and jobs. The Android app also
enforces its existing idle-evidence and protected-app policy; the PC companion
requires the owner to assess the selected installed package.

Before each write, the server saves a `PREPARED` journal event and prints the
**exact adb command** to stderr. Stdout contains only MCP JSON-RPC messages.
Command acceptance is not claimed as a verified gain. A failed or timed-out write
is `NEEDS_REVIEW` and is never automatically retried.

The journal defaults to `~/.local/state/s23-power/journal.jsonl`. Set
`S23_POWER_JOURNAL` in the server environment to choose a different location.
The file is created with private permissions and records prior values and manual
restore commands; failure to save the intent prevents the write. Preserve it
until all intended reversions are complete. It is separate from the Android
app's journal: changes made by the PC do not appear in the app's Undo screen.

For a reversible action, the result includes `before` and `undoCommand`. Check
current state and restore that saved value manually, in reverse order if several
changes were made. A `PREPARED` event without a completion event means execution
is uncertain; inspect state before retrying. **Force-stop cannot be undone**;
reopen the app yourself when needed. Never claim that relaunching restores its
previous process state.

## Test

```sh
node --test mcp/
```

The directory entry runs the parser, validation, journaling, argv, and protocol
stream tests. All fixtures are shared with `app/src/test/resources/fixtures/power`.
They are representative synthetic transcripts, not captures from a device
attached to this build environment. Test doubles replace adb; tests never alter
a connected phone.

The implementation is original Apache-2.0 code and has zero dependencies.

# MIDI Comp HTTP API (v1)

The extension serves a JSON API on the loopback interface. All Bitwig access goes through the
extension; clients see snapshots of the state and send commands.

## Discovery and authentication

When the extension starts, it writes `~/.midicomp/endpoint.json` (directory `0700`, file `0600`):

```json
{"url": "http://127.0.0.1:8765", "token": "3f9c…", "apiVersion": 1, "pid": 12345}
```

The file is deleted when the extension stops. A new token is generated on every start, so clients
re-read the file after a `401` or a connection error.

Every request sends:

- `X-Token: <token>`
- `Host: 127.0.0.1:<port>` or `localhost:<port>` (HTTP clients set this automatically)

A wrong token gets `401`, another `Host` gets `403`.

## Concepts

- **Session**: the clip being comped. Starting a session captures the clip's notes as the first
  take, switches overdub on, and from then on rewrites the clip whenever the comp changes.
- **Takes**: copies of notes: loop passes recorded into the session clip, plus clips copied with
  `addTake`. A take does not change when its source clip changes.
- **Segments**: ranges of the comp and the take that plays there; gaps are silent.

Positions and lengths are **grid steps**. `stepsPerBeat` converts to beats (quarter notes);
`barSteps` is one bar in the project's time signature. Positions are relative to the clip start.

## Endpoints

### `GET /api/v1/info`

```json
{"name": "MIDI Comp", "extensionVersion": "0.1.0", "apiVersion": 1}
```

### `GET /api/v1/state?since=<v>&wait=<seconds>`

Returns the state document. With `since`, returns `204 No Content` while the version is still
`since`; with `wait` (at most 30) the request blocks until the version changes or the time runs
out. A client loop:

```
v = -1
loop:
  r = GET /api/v1/state?since=v&wait=25
  if r.status == 200: state = r.json; v = state.v; redraw()
```

State document:

```jsonc
{
  "v": 42,                          // version, increases on every change
  "stepsPerBeat": 128,
  "barSteps": 512,
  "length": 2048,                   // comp length = longest take
  "launcherSel": "Keys / Clip 3",   // selected launcher clip, or null
  "arrangerSel": "Keys (arranger)", // selected arranger clip, or null
  "canUndoEdit": true,              // undoEdit available
  "canRevert": true,                // revert available
  "message": "Take 2: 3.1 to 5.1",  // result of the last command
  "session": {                      // null without a session
    "source": "arranger",           // "launcher" | "arranger"
    "label": "Keys (arranger)",
    "available": true,              // the clip is under its cursor (arranger: selected)
    "recording": false,             // Bitwig is recording into the clip
    "overdub": true,                // overdub is on; without it Bitwig records into a new clip
    "pending": false,               // comp changes wait to be written
    "truncated": false,             // the comp is longer than the clip
    "passes": 3,                    // loop passes recorded in this session
    "singlePass": false             // recording stops at the end of the current pass
  },
  "log": ["14:02:11  Loop wrapped (position 15.98 -> 0.02, step 2046 -> 2)", ...],
  "takes": [
    {"id": 1, "name": "Pass 1", "source": "arranger", "length": 2048,
     "notes": [[x, key, durationSteps, velocity, muted, -1], ...]}
  ],
  "segments": [[from, to, takeId], ...],
  "comp": [[x, key, durationSteps, velocity, muted, takeId], ...]
}
```

Note arrays: `x` start step, `key` MIDI key (0-127), `durationSteps` (may be fractional),
`velocity` 0-1, `muted` 0/1, `takeId` (only in `comp`; `-1` in takes).

### `POST /api/v1/<command>?<parameters>`

Parameters go in the query string; the body is ignored. Every command returns
`{"ok": true|false, "message": "…"}` with status `200`, also when `ok` is false. `504` means
Bitwig did not process the command within 5 s.

| Command      | Parameters                  | Effect |
|--------------|-----------------------------|--------|
| `start`      | `source=launcher\|arranger` | Start a session on the selected clip; ends a running session. |
| `end`        |                             | End the session; the clip keeps the comp. |
| `record`     | `stopAtEnd=true\|false`     | Arranger sessions: record over the loop region from its start. With `stopAtEnd` (default) recording stops when the loop wraps and the pass becomes one take; otherwise every repetition becomes a take until `stopRecording`. |
| `stopRecording` |                          | Stop the transport and arranger recording. |
| `revert`     |                             | Restore the clip as it was before the session and end it. |
| `addTake`    | `source=launcher\|arranger` | Copy the selected clip's notes as a new take. |
| `removeTake` | `take`                      | Remove a take and its segments. |
| `assign`     | `take`, `from`, `to`        | Use `take` for `[from, to)`. |
| `clear`      | `from`, `to`                | Make `[from, to)` silent. |
| `useTake`    | `take`                      | Use `take` for the whole comp. |
| `undoEdit`   |                             | Revert the last segment change. |

## Behavior

- The session clip is rewritten whenever the comp changes. Writes are diffs: removed notes are
  cleared, new notes created, changed notes updated in place. Nothing is written past the end of
  the clip.
- Launcher sessions pin a cursor to the clip, so writing does not depend on the selection.
  Arranger clips cannot be pinned: changes wait (`pending`) until the clip is selected again.
- After a selection change the extension waits until Bitwig has stopped reporting note updates
  for 150 ms before it writes.
- While Bitwig records into the clip nothing is written. Each wrap of the loop (transport jump,
  or the clip's playing step jumping back for launcher clips) and the end of recording close a
  pass. New notes are assigned to passes by when they appeared in the clip; a note reported just
  after a wrap but lying in the second half of the loop still belongs to the pass before. Passes
  are collected once the clip is under its cursor, so the comp clip should stay selected while
  recording in the arranger. Afterwards the comp is written over the recording.
- `log` lists recent capture events (recording start and stop, wraps, collected passes); the
  same lines go to Bitwig's controller console.

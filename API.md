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
  take and from then on rewrites the clip whenever the comp changes. Overdub is left alone;
  without it Bitwig records into a new clip on the same track, which the session follows.
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
  "project": "My song",             // name of the open project
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
    "pending": false,               // comp changes wait to be written
    "truncated": false,             // the comp is longer than the clip
    "clipLength": 2048,             // clip length in steps, -1 while it is not under its cursor
    "passes": 3,                    // loop passes recorded in this session
    "singlePass": false,            // recording stops at the end of the current pass
    "recordingPass": 0,             // loop pass being recorded (1-based), 0 when not recording
    "collecting": false,            // a recording waits for a clip on the track to be selected
    "track": "Keys",                // the comp clip's track
    "preview": [                    // provisional takes while recording (arranger), from the
      {"pass": 1, "notes": [...]}   // keyboard notes; replaced by the takes once assembled
    ],
    "fingerprint": "3f2a9c01d4e5b677"  // the clip's notes when the session started, see below
  },
  "log": ["14:02:11  Loop wrapped (position 15.98 -> 0.02, step 2046 -> 2)", ...],
  "takes": [
    {"id": 1, "name": "Pass 1", "source": "arranger", "length": 2048, "controls": 12,
     "notes": [[x, key, durationSteps, velocity, muted, -1], ...]}
  ],
  "segments": [[from, to, takeId], ...],
  "comp": [[x, key, durationSteps, velocity, muted, takeId], ...]
}
```

Note arrays: `x` start step, `key` MIDI key (0-127), `durationSteps` (may be fractional),
`velocity` 0-1, `muted` 0/1, `takeId` (only in `comp`; `-1` in takes).

### `POST /api/v1/<command>?<parameters>`

Parameters go in the query string; only `import` and `played` read the body. Every command returns
`{"ok": true|false, "message": "…"}` with status `200`, also when `ok` is false. `504` means
Bitwig did not process the command within 5 s.

| Command      | Parameters                  | Effect |
|--------------|-----------------------------|--------|
| `start`      | `source=launcher\|arranger` | Start a session on the selected clip; ends a running session. The grid resolution is chosen for the clip; if it changes, the clip is re-read first and the reply says "Preparing". |
| `end`        |                             | End the session; the clip keeps the comp. |
| `newVersion` |                             | Launcher sessions: duplicate the session clip into another slot and continue on the copy with the same takes and segments; the original keeps its comp. The session ends at once and starts on the copy when Bitwig selects it, after renaming it with a version suffix. If Bitwig does not select the copy within 4 s, the log says so. Fails for arranger sessions. |
| `record`     | `stopAtEnd=true\|false`     | Arranger sessions, with a clip on the comp track selected: empty the clip, then record from the loop start. Passes become takes (assembled with the keyboard notes from `played`, see below); the comp is written back when recording ends. With `stopAtEnd` (default) recording stops when the loop wraps. |
| `stopRecording` |                          | Stop the transport and arranger recording. |
| `loadIntoBitwig` |                       | Write the comp, notes and control messages, as a MIDI file (`~/.midicomp/midi/<title>.mid`) and load it: into the session clip's slot for launcher clips (the notes are written again afterwards, as MIDI files lack per-note values like timbre), else into the first free launcher slot of the comp track. |
| `playInLauncher` |                       | Arranger sessions with the arranger loop on: load the comp into a free launcher slot, loop it over the arranger loop region (launch quantization one bar), continue the session in that clip and launch it in the loop's last bar, so it plays in sync with the arrangement. |
| `transport`  | `action=play\|stop\|toggle` | Start or stop Bitwig's transport. |
| `seek`       | `step`                      | Arranger sessions: move the playhead to a step of the session clip (sets the play start, and jumps there while playing). Needs the clip's position in the arrangement, which the extension learns while the clip plays. |
| `revert`     |                             | Restore the clip as it was before the session and end it. |
| `addTake`    | `source=launcher\|arranger` | Copy the selected clip's notes as a new take. |
| `removeTake` | `take`                      | Remove a take and its segments. |
| `renameTake` | `take`, `name`              | Rename a take (trimmed, at most 60 characters; not part of the edit undo). |
| `assign`     | `take`, `from`, `to`        | Use `take` for `[from, to)`. |
| `clear`      | `from`, `to`                | Make `[from, to)` silent. |
| `useTake`    | `take`                      | Use `take` for the whole comp. |
| `undoEdit`   |                             | Revert the last segment change. |
| `import`     | body: a document from `export` | Replace the session's takes and segments with a saved comp. |
| `played`     | body: keyboard messages     | Store note messages played on the keyboard, for assembling loop recordings. Does not change the status or the state version. |

The body of `played`:

```jsonc
{"now": 1234568000000,                       // sender's monotonic clock at sending, µs
 "events": [[1234567890123, 144, 60, 100],   // [time µs, status, data1, data2]
            [1234567990123, 128, 60, 0]]}
```

Times are on the monotonic clock (`CLOCK_MONOTONIC` on Linux, `mach_absolute_time` on macOS,
`QueryPerformanceCounter` on Windows), which Java's `System.nanoTime()` also uses. The extension
estimates the offset between the clocks as the smallest difference between its time of arrival
and `now` over the last minute, so a different clock base still works. Note-on and note-off
messages, control changes, channel pressure and pitch bend are used; the companion takes the time of each message from the MIDI driver's
timestamps rather than from its arrival. Messages are kept for 15 minutes.

### Fingerprints

`fingerprint` (in the session state and in `export`) identifies a clip's content: the first 16
hex digits of a SHA-256 over the sorted `channel:key:position` of its notes, with the position
in beats rounded to 1/960 beat. The session's value describes the clip when the session
started; the export's describes the comp. A clip that holds a saved comp, such as a copy of the
comp clip, has the fingerprint of that comp.

### `GET /api/v1/playhead`

The playhead in the session clip, kept out of the state because it changes many times a second
(clients poll it, the companion about 20 times a second):

```json
{"playing": true, "step": 1834, "stepsPerSecond": 512.0, "canSeek": true,
 "transportPlaying": true}
```

`step` is the step being played in the session clip, or `-1` outside it. For arranger clips
whose position is known (learned while the clip plays: transport position minus playing step),
it also follows the transport while the clip is not selected, and shows the play start position
while stopped. `canSeek` says whether `seek` works; `transportPlaying` whether Bitwig plays at
all. While Bitwig records into a hidden arranger clip, it is derived from the
transport position and the loop start in the clip measured at the last recording.
`stepsPerSecond` lets clients interpolate between polls.

### `GET /api/v1/export`

Returns the session's comp at full fidelity, for saving: every note attribute, the segments, and
the step size, so `import` can rescale positions if the timing resolution changed. Returns
`{"ok": false, ...}` without a session.

```jsonc
{"format": 1, "project": "My song", "clip": "Keys (arranger)", "source": "arranger",
 "stepSize": 0.0078125, "fingerprint": "3f2a9c01d4e5b677",
 "takes": [{"name": "Pass 1", "source": "arranger", "length": 2048,
            "controls": [[x, status, data1, data2], ...],   // CC, pressure, pitch bend
            "notes": [{"ch": 0, "x": 0, "key": 60, "vel": 0.8, "rel": 0.5, "dur": 0.5,
                       "pan": 0, "timbre": 0, "pressure": 0, "gain": 0, "transpose": 0,
                       "chanceOn": false, "chance": 1, "occurrenceOn": false, "occurrence": null,
                       "recurrenceOn": false, "recurrenceLength": 1, "recurrenceMask": 1,
                       "repeatOn": false, "repeatCount": 1, "repeatCurve": 0,
                       "repeatVelEnd": 0, "repeatVelCurve": 0, "spread": 0, "muted": false}]}],
 "segments": [[0, 2048, 0]]}    // [from, to, take index]
```

## Behavior

- The session clip is rewritten whenever the comp changes. Writes are diffs: removed notes are
  cleared, new notes created, changed notes updated in place. Nothing is written past the end of
  the clip.
- Each write is checked once the clip has settled: notes left in the written range that should
  have been removed (for example ones Bitwig reported only after the write) are removed, and
  missing ones written again, up to three times per change. The log reports each repair.
- Launcher sessions pin a cursor to the clip, so writing does not depend on the selection.
  Arranger clips cannot be pinned: changes wait (`pending`) until the clip is selected again.
- After a selection change the extension waits until Bitwig has stopped reporting note updates
  for 150 ms before it writes.
- While Bitwig records into the clip nothing is written. Each wrap of the loop (transport jump,
  or the clip's playing step jumping back for launcher clips) and the end of recording close a
  pass. New notes are assigned to passes by when they appeared in the clip; a note reported just
  after a wrap but lying in the second half of the loop still belongs to the pass before. The
  pass that ends the recording waits until the clip's notes have settled and retries for up to
  3 s, since Bitwig may add recorded notes only after recording stops. Passes are collected once
  a clip on the comp clip's track is selected. Afterwards the comp is written over the recording.
- Bitwig hides arranger clips from extensions while recording, and without overdub keeps only
  the most recent pass at each loop position. For arranger recordings the extension samples the
  transport position against `System.nanoTime()`; each pass is fitted as a line with the
  tempo's slope, which averages out the screen-rate updates. Each keyboard note from `played` is
  placed in a pass and loop position with it. When recording ends, recorded notes are matched to
  played notes per key, in order of loop position (no crossings), within 80 ms; of played notes
  about equally close, the later pass wins, since Bitwig keeps the latest pass at each position.
  A hit on a key that is still held (drum pads often release after a fixed time) ends the
  previous note there. Matched recorded
  notes go to their played note's pass unchanged; unmatched played notes (overwritten passes) are
  rebuilt with attributes from a recorded note; unmatched recorded notes go to the last pass. The
  offset between played and recorded positions is estimated from the recording; rounded to a
  sixteenth note it is the loop start in the clip (of offsets that fit about equally well, as with
  regular hi-hats, the one nearest the previous recording's), and the rest is a constant delay that is
  removed from the played notes before passes are assigned. The log reports the counts, the
  delay, the remaining deviation and the clock offset. A possible refinement is to time the
  transport by Bitwig's MIDI clock output (timestamped by the companion's MIDI driver like the
  notes) instead of the screen-rate position updates. Wraps within 300 ms of the recording start
  are ignored (Bitwig reports a jump to the loop start there).
- `log` lists recent capture events (recording start and stop, wraps, collected passes); the
  same lines go to Bitwig's controller console.

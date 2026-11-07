# MIDI Comp

Comping for MIDI clips in Bitwig Studio: record loop passes into a clip, then cut the passes
together in a companion app while the clip updates live.

Two parts:

- **Extension** (`MidiComp.bwextension`): a Bitwig controller extension that captures takes,
  writes the comp into the clip, and serves a local HTTP API ([API.md](API.md)).
- **Companion app** (`midicomp-companion`): a native editor (Dear ImGui) that shows the takes and
  the comp. It follows the colors of the Bitwig Theme Editor.

![Comp session, Rose Pine Dawn theme](docs/screenshots/session-rose-pine-dawn.png)

## Workflow

1. **Pick the clip.** Select a MIDI clip in Bitwig (launcher or arranger). The start screen
   shows what is selected; press *Comp this clip*. The clip's current notes become the first take.

   ![Start screen](docs/screenshots/start.png)

2. **Record.** Set the loop region over the clip and press *Record* in the companion (arranger
   clips; a clip on the comp track must be selected). It empties the clip, so the comp is not
   heard, and records from the loop start; overdub is left as you set it. With *Stop at
   loop end* (default) recording stops after one pass; without it, it continues until you press
   *Stop*, and every loop pass becomes a take. When recording stops, the comp is written back.

   Bitwig hides the arranger clip from extensions while it records and merges all passes into
   it, so the passes are sorted afterwards. While recording, the extension notes which pass and
   loop position each key was played at, and then matches Bitwig's recorded notes to them. The
   takes always consist of Bitwig's recorded notes (exact timing, all attributes); the observed
   notes only decide the pass. They come from the extension's MIDI input if your keyboard is
   assigned to it, otherwise from the notes the comp track plays (no setup; clip playback on that
   track would be mixed in, which the log reports). Notes played within a few milliseconds of the
   same key in another pass may swap passes. Overdub is left as you set it: without it Bitwig records
   into a new clip on top of the comp clip, which then becomes the comp clip. *Log* shows what the
   extension saw (recording, loop wraps, collected passes), which helps when a pass does not show
   up.

   ![Waiting for the first pass](docs/screenshots/record.png)

3. **Comp.** Drag across a take lane to use that take for the range (snapped to the *Snap* grid);
   drag across the comp lane to make a range silent. The clip is rewritten as you edit. While the
   comp is still a single take, each new pass replaces it, as with Bitwig's audio takes; once
   takes are cut together, new passes only add lanes.

4. **Finish.** *Done* ends the session; the clip keeps the comp. *Revert* restores the clip as it
   was before the session and ends it.

Takes recorded elsewhere (another track or launcher slot) can be added with *Add take*, which
copies the selected clip's notes. They must start at the same position as the comp clip. A take is
a copy: recording over or editing the source clip later does not change it, so the same clip can
be recorded again and added as another take.

**Editing keys** (pointer over the lanes): `1`-`9` use the n-th take for the grid cell under the
pointer, `0` or `Delete` clears it, `Ctrl+Z` undoes the last edit, `Ctrl`+wheel zooms,
`Shift`+wheel scrolls. Take lanes scroll vertically below the fixed comp lane.

**Overwriting.** The comp clip is always overwritten with the comp: edits made to it directly in
Bitwig are replaced at the next comp change, and recorded passes are replaced once recording
stops (by then they are takes). Comps are built from the full notes (every attribute the API
exposes); the companion only displays a reduced form.

**Selection (arranger).** Bitwig only lets extensions read and write the *selected* arranger
clip, and arranger clips cannot be pinned. Any clip on the comp clip's track counts, so selecting
something on another track pauses writing ("Changes waiting") until a clip on that track is
selected again. Launcher clips are pinned by the session and have no such limit.

**Saved comps.** The companion saves the current comp shortly after every change, one file per
session, keeping the ten newest per clip. When you comp a clip with a saved comp in the same
project, a banner offers *Restore*, and the start screen notes it. Files live in
`~/.local/share/midicomp/comps` (Linux, or `$XDG_DATA_HOME`), `~/Library/Application
Support/midicomp/comps` (macOS) or `%APPDATA%\midicomp\comps` (Windows); `--store-dir`
changes it. Saved comps contain every note attribute and are restored at full fidelity, also
after a change of the timing resolution.

## Installation

Download the extension and the companion for your platform from the
[releases](../../releases) or the latest CI run, or build them (below).

1. Copy `MidiComp.bwextension` to `~/Bitwig Studio/Extensions/` (Windows:
   `Documents\Bitwig Studio\Extensions`).
2. In Bitwig: *Dashboard > Settings > Controllers > Add controller > Utility > MIDI Comp*. Its MIDI
   input is optional: if you select your keyboard there, its notes time the loop passes most
   directly (the extension forwards the keyboard as a note input, so tracks set to *All ins* keep
   receiving it; with the keyboard on two controllers, set the recording track's input to one of
   them). Without it, the notes played on the comp track are used. On Linux the keyboard can be
   duplicated with `snd-virmidi` and `aconnect` to use it on both controllers.
3. Start `midicomp-companion`. It finds the extension through `~/.midicomp/endpoint.json`, which
   the extension writes on startup, and reconnects when the extension restarts.

### Companion options

- `--theme-dir DIR`: Bitwig Theme Editor directory (default `~/.bitwig-theme-editor`).
- `--endpoint FILE`: discovery file (default `~/.midicomp/endpoint.json`).
- `--store-dir DIR`: where saved comps are kept.
- `--screenshot FILE`: render for three seconds, save the window as PPM and exit.
- `MIDICOMP_FONT=/path/to/font.ttf`: UI font. Release builds embed Roboto; the Nix build uses Inter.

### Theme

Colors come from the Bitwig Theme Editor: `bte_config.txt` selects the global `theme.bte` or the
per-version theme, layered over the newest `versions/*/default.bte`. Lanes use the timeline
background and header colors, grid lines the grid colors, drag previews the time-selection colors,
and takes the panel color set. Missing entries fall back to Bitwig's stock dark colors. The files
are re-read once a second, so changes in the theme editor apply without a restart.

![Comp session, Bitwig default colors](docs/screenshots/session-bitwig-default.png)

## Building

The repository is a Nix flake. If the checkout sits inside another git repository where it is
untracked, use `path:.` instead of `.` so that Nix sees all files.

```sh
nix develop                      # or: direnv allow
mvn package                      # extension + tests -> target/MidiComp.bwextension
nix build .#companion            # companion -> result/bin/midicomp-companion
```

Without Nix, the companion needs CMake 3.25, a C++23 compiler and OpenGL. GLFW, Dear ImGui,
nlohmann/json and cpp-httplib are used when installed and downloaded otherwise:

```sh
cmake -S companion -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
ctest --test-dir build -C Release
```

GitHub Actions (`.github/workflows/build.yml`) builds the extension, the companion for Linux,
macOS and Windows, and the Nix package; tags `v*` publish a release with all artifacts.

**clangd.** The Nix compiler wrapper passes dependency include paths through environment
variables, not `compile_commands.json`. Start clangd inside `nix develop` with
`--query-driver=/nix/store/**/bin/*`.

### Regenerating screenshots

The images in `docs/screenshots` are rendered headless with Xvfb and Mesa's software renderer
against a mock of the API:

```sh
python3 tools/mock_api.py 18799 /tmp/mock-endpoint.json session &   # or: start, record
xvfb-run -s "-screen 0 1280x760x24" \
  ./companion/build/midicomp-companion --endpoint /tmp/mock-endpoint.json --screenshot out.ppm
```

On NixOS, point `LD_LIBRARY_PATH` at Mesa's `lib` directory and set
`__GLX_VENDOR_LIBRARY_NAME=mesa`. Pass `--theme-dir` with an empty directory for stock colors.

## Limitations

- **Timing resolution.** The API exposes notes through a step grid (16384 steps here); a note's
  start is the step it falls in. Each session picks the finest step at which its clip fits, from
  1/4096 beat down to 1/64 beat: an 8-beat clip gets 1/2048 beat (0.25 ms at 120 BPM), a 32-bar
  clip 1/512 beat (1 ms). Clips longer than 256 bars cannot be fully accessed.
- **Copied attributes.** Velocity, release velocity, duration, pan, timbre, pressure, gain,
  transpose, chance, occurrence, recurrence, repeats and mute. Drawn per-note expression curves and
  clip automation (CC, pitch bend) are not exposed by the API and are not copied.
- **Arranger clips** are identified by track; the API exposes no clip position. A clip on the comp
  clip's track must be selected for changes to be written (see Selection). Clips must not be
  trimmed on the left edge, and clip loops must not repeat inside the comp.
- **Clip length.** Nothing is written past the end of the comp clip; the status shows when the
  comp is longer than the clip.
- **Undo.** Each note edit is a separate step in Bitwig's undo history. *Undo edit* in the
  companion reverts comp edits; *Revert* restores the clip's original content.

### Untested in Bitwig

These behaviors come from the API documentation and have not been verified in Bitwig yet:

- Recording state is read from the transport (arranger) and the clip slot (launcher); loop passes
  are detected from the transport position and the clip's playing step.
- When a cursor moves to another clip, the host reports `Empty` for the old notes and `NoteOn`
  for the new ones within the 150 ms settle window.
- A 16384 x 128 grid does not cost much memory or CPU in the host.

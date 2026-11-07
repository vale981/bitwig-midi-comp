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

2. **Record.** Set the loop region over the clip and press *Record* in the companion: it plays
   from the loop start and records into the clip. With *Stop at loop end* (default) it stops when
   the loop wraps and the pass becomes one take; without it every repetition becomes a take until
   you press *Stop*. Recording in Bitwig directly works as well (each loop pass becomes a take);
   launcher clips are recorded that way. Keep the comp clip selected while recording in the
   arranger.

   The session switches Bitwig's overdub on (arranger or launcher, matching the clip) so that
   Bitwig records into the clip instead of creating a new clip on top of it; the previous setting
   is restored when the session ends. *Log* shows what the extension saw (recording, loop wraps,
   collected passes), which helps when a pass does not show up.

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
stops (by then they are takes).

## Installation

Download the extension and the companion for your platform from the
[releases](../../releases) or the latest CI run, or build them (below).

1. Copy `MidiComp.bwextension` to `~/Bitwig Studio/Extensions/` (Windows:
   `Documents\Bitwig Studio\Extensions`).
2. In Bitwig: *Dashboard > Settings > Controllers > Add controller > Utility > MIDI Comp*. The
   extension has no MIDI ports.
3. Start `midicomp-companion`. It finds the extension through `~/.midicomp/endpoint.json`, which
   the extension writes on startup, and reconnects when the extension restarts.

The controller preferences contain the timing resolution (see Limitations).

### Companion options

- `--theme-dir DIR`: Bitwig Theme Editor directory (default `~/.bitwig-theme-editor`).
- `--endpoint FILE`: discovery file (default `~/.midicomp/endpoint.json`).
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

- **Timing resolution.** Notes are accessed through a grid of 16384 steps; note starts are rounded
  to one step. The *Timing resolution* preference trades precision against maximum clip length:
  1/64 beat (7.8 ms at 120 BPM, 64 bars), 1/128 beat (3.9 ms, 32 bars, default), 1/256 beat
  (2.0 ms, 16 bars).
- **Copied attributes.** Velocity, release velocity, duration, pan, timbre, pressure, gain,
  transpose, chance, occurrence, recurrence, repeats and mute. Drawn per-note expression curves and
  clip automation (CC, pitch bend) are not exposed by the API and are not copied.
- **Arranger clips** are identified by track; the API exposes no clip position. The comp clip must
  stay selected in the arranger for changes to be written (the status shows when it is not).
  Clips must not be trimmed on the left edge, and clip loops must not repeat inside the comp.
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

Comping for MIDI clips in Bitwig Studio: record loop passes, then cut them together in the
companion app while the clip updates live. See the [README](https://github.com/vale981/bitwig-midi-comp#readme)
for the workflow.

**This is an alpha.** It has been used on Linux with arranger clips and, since this release,
the clip launcher; macOS and Windows are built by CI but barely tested. Saved comps live in the companion's data directory,
so a broken session can be restored from there. Please report problems with the extension log
(*Log* in the companion).

### New in this alpha

- **Pedals and controllers:** CC (hi-hat pedal, sustain, mod wheel), channel pressure and pitch
  bend are recorded with every take, follow the cuts and show as curves in the lanes. *Load
  into Bitwig* brings them into Bitwig as a MIDI file.
- **Comping in the clip launcher:** *Play in launcher* plays the comped loop from the launcher in
  sync with the arrangement and reloads it with its controllers after each edit; *Back to
  arranger* brings the edits back.
- **Transport from the companion:** a playhead, play and stop (`Space`), and moving the playhead
  by clicking the ruler or with the arrow keys.
- **Takes:** rename them by double-clicking; fast repeated hits on one key (closed hi-hats) are
  kept.
- **Linux:** `midi_copies.py` and its NixOS module give every MIDI device its own virtual copy,
  so Bitwig and the companion can use a keyboard at the same time.

### Files

- `MidiComp.bwextension`: copy to `~/Bitwig Studio/Extensions/` (Windows:
  `Documents\Bitwig Studio\Extensions`), then add *Utility > MIDI Comp* under *Settings >
  Controllers*. Requires Bitwig Studio with extension API 18 or newer.
- `midicomp-companion-<platform>.zip`: the companion app.
  - Linux: needs GLFW 3 and ALSA (`libglfw3`, `libasound2` on Debian and Ubuntu). Bitwig
    and the companion cannot both open a keyboard; see *MIDI copies on Linux* in the README.
  - macOS: the app is not signed. After unzipping, run
    `xattr -d com.apple.quarantine midicomp-companion` once.
  - Windows: not signed, so SmartScreen asks for confirmation. Classic MIDI drivers allow one
    program per device; see *Sharing the keyboard* in the README.
- `midi_copies.py`: gives every MIDI device a virtual copy on Linux (see the README).

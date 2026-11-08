Comping for MIDI clips in Bitwig Studio: record loop passes, then cut them together in the
companion app and hear the comp while you edit. See the [README](https://github.com/vale981/bitwig-midi-comp#readme)
for the workflow.

**This is an alpha.** It has been used on Linux with arranger clips and the clip launcher;
macOS and Windows are built by CI but barely tested, and Preview has only been tried on Linux.
Saved comps live in the companion's data directory, so a broken session can be restored from
there. Please report problems with the extension log (*Log* in the companion).

### New in this alpha

- **Preview:** the companion plays the comp, pedals and controllers included, as live MIDI into
  the comp track while Bitwig plays, so every edit is heard at once; ending the preview writes
  the comp into the clip. Timing follows Bitwig's MIDI clock when it is sent to the companion's
  port. It needs a MIDI port into Bitwig: on Linux the port's virtual copy, on macOS a virtual
  port, on Windows a [loopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html) port
  named "MIDI Comp". Delete the clip's automation lanes before previewing a comp with
  controllers; the companion reminds you. *Play in launcher* stays as the fallback.
- **MIDI setup in the companion:** *MIDI* in the toolbar holds the keyboard choice and checks the
  preview setup: which ports to pick in Bitwig (by the names Bitwig shows), whether the comp
  track plays the preview, and whether the MIDI clock arrives.
- **Simpler toolbar:** two rows, with Preview next to Record and less used actions (*Add take*,
  *Load into Bitwig*, *New version*, *Play in launcher*, *Revert*) in a *Clip* menu.
- **Current recording and comp:** the toolbar shows and renames both, and *Saved comps* marks
  them.
- **Controllers at cuts:** a controller that a take never moved returns to rest (0, pitch bend
  centered) at that take's cuts, also in MIDI files from *Load into Bitwig*.
- **Fixed:** every full write into the clip was done twice ("missing ... writing again" in the
  log).

### Files

- `MidiComp.bwextension`: copy to `~/Bitwig Studio/Extensions/` (Windows:
  `Documents\Bitwig Studio\Extensions`), then add *Utility > MIDI Comp* under *Settings >
  Controllers*. Requires Bitwig Studio with extension API 18 or newer.
- `midicomp-companion-<platform>.zip`: the companion app.
  - Linux: needs GLFW 3 and ALSA (`libglfw3`, `libasound2` on Debian and Ubuntu). Bitwig
    and the companion cannot both open a MIDI controller; see *MIDI copies on Linux* in the
    README.
  - macOS: the app is not signed. After unzipping, run
    `xattr -d com.apple.quarantine midicomp-companion` once.
  - Windows: not signed, so SmartScreen asks for confirmation. Classic MIDI drivers allow one
    program per device; see *Sharing a MIDI controller* in the README.
- `midi_copies.py`: gives every MIDI device (and the companion's preview port) a virtual copy on
  Linux (see the README).

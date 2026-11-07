package midicomp;

import java.util.UUID;

import com.bitwig.extension.controller.api.ControllerHost;

/**
 * Variant of the extension with a MIDI input for the keyboard used for recording.
 *
 * The keyboard is forwarded to tracks as a note input, and the notes played on it time the loop
 * passes of a recording, which is more direct than the comp track's notes. Useful when the
 * keyboard can be assigned to this controller (for example duplicated with ``snd-virmidi`` on
 * Linux).
 */
public class MidiCompKeyboardExtensionDefinition extends MidiCompExtensionDefinition
{
   private static final UUID ID = UUID.fromString("01ddc940-74d9-493f-9c75-292de995a90b");

   @Override
   public String getName()
   {
      return "MIDI Comp (keyboard input)";
   }

   @Override
   public UUID getId()
   {
      return ID;
   }

   @Override
   public String getHardwareModel()
   {
      return "MIDI Comp (keyboard input)";
   }

   @Override
   public int getNumMidiInPorts()
   {
      return 1;
   }

   @Override
   public MidiCompExtension createInstance(final ControllerHost host)
   {
      return new MidiCompExtension(this, host, true);
   }
}

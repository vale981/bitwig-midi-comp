package midicomp;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

/**
 * Registers the MIDI comping extension with Bitwig Studio.
 *
 * Added via *Settings > Controllers > Add controller > Utility > MIDI Comp*. Its MIDI input takes
 * the keyboard used for recording; the extension forwards it to tracks.
 */
public class MidiCompExtensionDefinition extends ControllerExtensionDefinition
{
   private static final UUID ID = UUID.fromString("a5e66c8e-2896-418a-86c9-a2ecc12b5467");

   @Override
   public String getName()
   {
      return "MIDI Comp";
   }

   @Override
   public String getAuthor()
   {
      return "vboettcher";
   }

   @Override
   public String getVersion()
   {
      return "0.1.0";
   }

   @Override
   public UUID getId()
   {
      return ID;
   }

   @Override
   public String getHardwareVendor()
   {
      return "Utility";
   }

   @Override
   public String getHardwareModel()
   {
      return "MIDI Comp";
   }

   @Override
   public int getRequiredAPIVersion()
   {
      // NoteStep attribute accessors (chance, repeats, recurrence, mute) exist since API 14.
      return 18;
   }

   @Override
   public int getNumMidiInPorts()
   {
      // The keyboard: forwarded to tracks, and watched while recording to sort loop passes.
      return 1;
   }

   @Override
   public int getNumMidiOutPorts()
   {
      return 0;
   }

   @Override
   public void listAutoDetectionMidiPortNames(
      final AutoDetectionMidiPortNamesList list,
      final PlatformType platformType)
   {
      // No hardware to detect.
   }

   @Override
   public MidiCompExtension createInstance(final ControllerHost host)
   {
      return new MidiCompExtension(this, host);
   }
}

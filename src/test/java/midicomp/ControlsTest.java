package midicomp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Control messages (hi-hat pedal and the like) in takes, comps and MIDI files. */
class ControlsTest
{
   private static final int HIHAT_PEDAL = 0xB9; // CC on channel 10

   private static ControlEvent pedal(final int x, final int value)
   {
      return new ControlEvent(x, HIHAT_PEDAL, 4, value);
   }

   @Test
   void eachCutStartsWithItsTakesControllerState()
   {
      final CompModel model = new CompModel();
      final Take closed = model.addTake("Closed", Take.Source.ARRANGER, 400, List.of(),
         List.of(pedal(0, 127), pedal(150, 120)));
      final Take open = model.addTake("Open", Take.Source.ARRANGER, 400, List.of(),
         List.of(pedal(0, 0), pedal(50, 10), pedal(250, 5)));
      model.assign(0, 400, closed.id());
      model.assign(100, 200, open.id());

      final List<ControlEvent> comp = model.renderControls();
      // 0-100 closed: 127 at 0. 100-200 open: its state before 100 (10, from step 50) at 100.
      // 200-400 closed: its state before 200 (120, from step 150) at 200.
      assertEquals(List.of(pedal(0, 127), pedal(100, 10), pedal(200, 120)), comp);
   }

   @Test
   void midiFilePutsControlsBeforeNotesAtTheSamePosition()
   {
      final NoteData hat = new NoteData(9, 128, 42, 100 / 127.0, 0, 0, 0.125, 0, 0, 0, 0.5, 0,
         false, 1, false, null, false, 1, 1, false, 1, 0, 1, 0, false, false);
      final byte[] file = MidiFile.write(List.of(hat), List.of(pedal(128, 0)), 1.0 / 128, 512);

      // One tick per grid step: 128 per beat.
      assertArrayEquals(new byte[] { 'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0, (byte) 128 },
         java.util.Arrays.copyOf(file, 14));
      // Track (no tempo event): at tick 128 (step 128) the pedal CC before the note-on.
      final byte[] track = java.util.Arrays.copyOfRange(file, 22, file.length);
      assertArrayEquals(new byte[] { (byte) 0x81, 0x00, (byte) 0xB9, 4, 0, 0, (byte) 0x99, 42,
         100 }, java.util.Arrays.copyOf(track, 9));
      // Ends with end-of-track at the comp length (4 beats).
      assertArrayEquals(new byte[] { (byte) 0xFF, 0x2F, 0 },
         java.util.Arrays.copyOfRange(file, file.length - 3, file.length));
   }

   @Test
   void controlsArePlacedInPassesWithTheNotesDelay()
   {
      final TransportTimeline timeline = new TransportTimeline();
      timeline.start(Long.MIN_VALUE);
      final long second = 1_000_000_000L;
      // 120 BPM, loop of 4 beats from beat 8: two seconds per pass.
      for (long t = 0; t < 5 * second; t += second / 100)
         timeline.add(t, 8 + LoopTakes.wrap(t * 2e-9, 4));
      final KeyboardEvents keyboard = new KeyboardEvents();
      keyboard.add(-second, HIHAT_PEDAL, 4, 127);    // held closed before recording
      keyboard.add(second, HIHAT_PEDAL, 4, 0);       // pass 1, beat 2
      keyboard.add(3 * second, HIHAT_PEDAL, 4, 64);  // pass 2, beat 2

      final List<KeyboardEvents.PlayedControl> played =
         keyboard.controls(0, 4 * second, timeline, 8, 4, 120);
      assertEquals(3, played.size());
      assertEquals(127, played.get(0).data2());
      assertEquals(0.0, played.get(0).loopBeat(), 1e-9);

      // Two passes, loop start at clip beat 0, played timing 0.0625 beats late.
      final LoopTakes.Result result = new LoopTakes.Result(List.of(List.of(), List.of()), 0, 0, 0,
         0, -0.0625, 0, 0, List.of(0, 1), 0);
      final List<List<ControlEvent>> placed =
         LoopTakes.placeControls(played, result, 1.0 / 128, 4);
      assertEquals(List.of(pedal(0, 127), pedal(248, 0)), placed.get(0));
      assertEquals(List.of(pedal(248, 64)), placed.get(1));
   }

   @Test
   void controlsSurviveExportAndImport()
   {
      final CompModel model = new CompModel();
      final Take take = model.addTake("Pass 1", Take.Source.ARRANGER, 256, List.of(),
         List.of(pedal(10, 127), new ControlEvent(64, 0xE0, 0, 80)));
      model.assign(0, 256, take.id());
      final String json = CompExport.write(model,
         new ClipRef(Take.Source.ARRANGER, 0, "Drums", -1, "Drums (arranger)"), "Song", 1.0 / 128);
      // Imported at twice the resolution.
      final CompExport.Imported imported = CompExport.read(json, 1.0 / 256);
      assertEquals(List.of(pedal(20, 127), new ControlEvent(128, 0xE0, 0, 80)),
         imported.takes().get(0).controls());
      assertTrue(json.contains("\"controls\":[[10,185,4,127]"));
   }

   @Test
   void controlCountsAppearInTheState()
   {
      final CompModel model = new CompModel();
      model.addTake("Pass 1", Take.Source.ARRANGER, 256, List.of(), new ArrayList<>(List.of(pedal(0, 1))));
      final String state = StateJson.build(1, model, 1.0 / 128, 512, "Song",
         new StateJson.Selection(null, null), null, false, "", List.of());
      assertTrue(state.contains("\"controls\":1"));
   }
}

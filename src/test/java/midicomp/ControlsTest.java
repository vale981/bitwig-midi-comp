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
   void controllersATakeNeverSetReturnToRest()
   {
      final CompModel model = new CompModel();
      final Take pedalled = model.addTake("Pedal", Take.Source.ARRANGER, 400, List.of(),
         List.of(pedal(0, 127), new ControlEvent(10, 0xE0, 0, 100)));
      final Take plain = model.addTake("Plain", Take.Source.ARRANGER, 400, List.of(), List.of());
      model.assign(0, 400, plain.id());
      model.assign(100, 200, pedalled.id());

      // 0-100 plain: the pedal at 0 and pitch bend centered. 100-200: the pedalled take's
      // state. 200-400 plain again: back to rest.
      final ControlEvent center = new ControlEvent(0, 0xE0, 0, 64);
      assertEquals(List.of(pedal(0, 0), center, pedal(100, 127), new ControlEvent(100, 0xE0, 0, 100),
         pedal(200, 0), center.at(200)), model.renderControls());
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

   @Test
   void croppingKeepsTheLoopRegionWithItsControllerState()
   {
      final CompModel model = new CompModel();
      final NoteData hat = new NoteData(9, 0, 42, 0.8, 0, 0, 0.5, 0, 0, 0, 0.5, 0, false, 1,
         false, null, false, 1, 1, false, 1, 0, 1, 0, false, false);
      final Take take = model.addTake("Pass 1", Take.Source.ARRANGER, 1024,
         List.of(hat.withX(100), hat.withX(300), hat.withX(700)),
         List.of(pedal(50, 127), pedal(350, 0)));
      model.assign(0, 1024, take.id());

      // Loop region: steps 256 to 768 (one beat is 128 steps).
      final CompModel loop = model.cropped(256, 512, 1.0 / 128);
      assertEquals(512, loop.takes().get(0).length());
      final List<Integer> xs = loop.takes().get(0).notes().stream().map(NoteData::x).toList();
      assertEquals(List.of(44, 444), xs); // steps 300 and 700; step 100 lies before the region
      // The note at 444 has 68 steps (0.53 beats) left in the region, more than its 0.5 beats.
      assertEquals(0.5, loop.takes().get(0).notes().get(1).duration(), 1e-9);
      // The pedal was closed (step 50) before the region and opens at step 350.
      assertEquals(List.of(pedal(0, 127), pedal(94, 0)), loop.takes().get(0).controls());
      assertEquals(0, loop.segments().get(0).from());
      assertEquals(512, loop.segments().get(0).to());
   }

   @Test
   void editsOfTheLoopRegionMergeBackIntoTheFullComp()
   {
      final CompModel full = new CompModel();
      final Take a = full.addTake("Pass 1", Take.Source.ARRANGER, 1024, List.of(), List.of());
      final Take b = full.addTake("Pass 2", Take.Source.ARRANGER, 1024, List.of(), List.of());
      full.assign(0, 1024, a.id());
      final String exported = CompExport.write(full,
         new ClipRef(Take.Source.ARRANGER, 0, "Keys", -1, "Keys (arranger)"), "Song", 1.0 / 128);

      // Loop region 256..768, edited in the launcher: pass 2 for 128..384, take renamed.
      final CompModel region = full.cropped(256, 512, 1.0 / 128);
      region.assign(128, 384, region.takes().get(1).id());
      region.renameTake(region.takes().get(1).id(), "Chorus");

      final CompModel merged = CompModel.mergeRegion(CompExport.read(exported, 1.0 / 128), region,
         256, 512);
      assertEquals("Chorus", merged.takes().get(1).name());
      final int first = merged.takes().get(0).id();
      final int second = merged.takes().get(1).id();
      assertEquals(List.of(new CompModel.Segment(0, 384, first),
         new CompModel.Segment(384, 640, second), new CompModel.Segment(640, 1024, first)),
         merged.segments());
      assertTrue(b.id() > 0);
   }
}

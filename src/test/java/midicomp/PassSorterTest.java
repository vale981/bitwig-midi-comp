package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PassSorterTest
{
   private static final double STEP = 1.0 / 128;
   private static final double LOOP = 8.0;
   private static final double TOLERANCE = 0.16;

   /** Three passes of the same phrase with human timing, as one merged recording. */
   @Test
   void overlappingPassesAreSeparatedByTiming()
   {
      final Random random = new Random(7);
      final int[] keys = { 60, 62, 64, 65, 67, 65, 64, 62 };
      // The clip starts two beats before the loop region.
      final double clipOffset = 2.0;

      final List<NoteData> recorded = new ArrayList<>();
      final List<PassSorter.Played> played = new ArrayList<>();
      final List<List<Integer>> expectedX = new ArrayList<>();
      for (int pass = 0; pass < 3; pass++)
      {
         final List<Integer> xs = new ArrayList<>();
         for (int i = 0; i < keys.length; i++)
         {
            // Each pass plays slightly differently: up to 40 ms early or late at 120 BPM.
            final double beat = i + (random.nextDouble() - 0.5) * 0.16;
            final int x = (int) Math.round((clipOffset + beat) / STEP);
            recorded.add(CompModelTest.note(x, keys[i], 0.4));
            xs.add(x);
            // What the extension saw: loop position measured with up to 10 ms of jitter.
            final double seen = PassSorter.wrap(beat + (random.nextDouble() - 0.5) * 0.04, LOOP);
            played.add(new PassSorter.Played(keys[i], pass, seen));
         }
         expectedX.add(xs);
      }

      final PassSorter.Result result = PassSorter.sort(recorded, played, STEP, LOOP, TOLERANCE);
      assertEquals(3, result.passes().size());
      assertEquals(24, result.matched());
      assertEquals(0, result.unmatched());
      assertEquals(clipOffset, result.offsetBeats(), 0.05);
      // A note may only end up in another pass if that pass played the same key at nearly the
      // same time (closer than the observation jitter); then the swap is inaudible.
      int misplaced = 0;
      for (int pass = 0; pass < 3; pass++)
      {
         for (final NoteData n : result.passes().get(pass))
         {
            if (expectedX.get(pass).contains(n.x()))
               continue;
            misplaced++;
            final int trueX = n.x();
            final boolean nearTwin = expectedX.get(pass).stream()
               .anyMatch(x -> Math.abs(x - trueX) * STEP <= 0.05);
            assertTrue(nearTwin, "note at " + n.x() + " misplaced without a near twin");
         }
      }
      // Each swap misplaces two notes; allow at most two swaps among 24 notes.
      assertTrue(misplaced <= 4, "misplaced notes: " + misplaced);
   }

   @Test
   void unmatchedNotesGoToTheLastPass()
   {
      final List<NoteData> recorded = List.of(CompModelTest.note(0, 60, 0.5),
         CompModelTest.note(512, 72, 0.5));
      final List<PassSorter.Played> played = List.of(new PassSorter.Played(60, 0, 0.0),
         new PassSorter.Played(61, 1, 4.0));
      final PassSorter.Result result = PassSorter.sort(recorded, played, STEP, LOOP, TOLERANCE);
      assertEquals(2, result.passes().size());
      assertEquals(1, result.matched());
      assertEquals(1, result.unmatched());
      assertTrue(result.passes().get(1).stream().anyMatch(n -> n.key() == 72));
   }

   @Test
   void withoutPlayedNotesEverythingIsOnePass()
   {
      final List<NoteData> recorded = List.of(CompModelTest.note(0, 60, 0.5));
      final PassSorter.Result result = PassSorter.sort(recorded, List.of(), STEP, LOOP, TOLERANCE);
      assertEquals(1, result.passes().size());
      assertEquals(1, result.unmatched());
   }

   @Test
   void notesPlayedAcrossTheLoopEndMatchTheirWrappedPosition()
   {
      // Played 10 ms before the loop end, recorded at the very end of the clip.
      final List<NoteData> recorded = List.of(CompModelTest.note((int) Math.round(7.99 / STEP), 60, 0.1));
      final List<PassSorter.Played> played = List.of(new PassSorter.Played(60, 0, 0.01),
         new PassSorter.Played(60, 1, 7.98));
      final PassSorter.Result result = PassSorter.sort(recorded, played, STEP, LOOP, TOLERANCE);
      assertEquals(1, result.matched());
      assertEquals(1, result.passes().size());
   }

   @Test
   void nearlySimultaneousNotesOfTwoPassesAreNotSwapped()
   {
      // Pass 1 at 1.00 and pass 2 at 1.03 beats; observations jittered towards each other.
      final List<NoteData> recorded = List.of(CompModelTest.note((int) Math.round(1.00 / STEP), 60, 0.2),
         CompModelTest.note((int) Math.round(1.03 / STEP), 60, 0.2),
         CompModelTest.note(0, 50, 0.2));
      final List<PassSorter.Played> played = List.of(new PassSorter.Played(60, 0, 1.02),
         new PassSorter.Played(60, 1, 1.025), new PassSorter.Played(50, 0, 0.0));
      final PassSorter.Result result = PassSorter.sort(recorded, played, STEP, LOOP, TOLERANCE);
      assertEquals(2, result.passes().size());
      assertTrue(result.passes().get(0).stream().anyMatch(n -> n.x() == (int) Math.round(1.00 / STEP)));
      assertTrue(result.passes().get(1).stream().anyMatch(n -> n.x() == (int) Math.round(1.03 / STEP)));
   }
}

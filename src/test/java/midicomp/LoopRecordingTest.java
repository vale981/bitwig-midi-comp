package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Timeline, keyboard events and take assembly for continuous loop recordings. */
class LoopRecordingTest
{
   private static final double TEMPO = 120;
   private static final double LOOP_START = 16.0;
   private static final double LOOP = 8.0;
   private static final double STEP = 1.0 / 2048;
   private static final long SECOND = 1_000_000_000L;
   private static final double BEATS_PER_NANO = TEMPO / 60e9;
   /** Clip-local beat of the loop start: the clip starts two beats before the loop. */
   private static final double CLIP_OFFSET = 2.0;

   /** Transport position at ``t`` for a recording that starts at the loop start at time 0. */
   private static double position(final long t)
   {
      return LOOP_START + PassSorterlessMath.wrap(t * BEATS_PER_NANO, LOOP);
   }

   /** Position updates at 60 Hz, each reported 0-15 ms late. */
   private static TransportTimeline timeline(final long until, final Random random)
   {
      final TransportTimeline timeline = new TransportTimeline();
      timeline.start(Long.MIN_VALUE);
      for (long t = 0; t < until; t += SECOND / 60)
      {
         final long reported = t + (long) (random.nextDouble() * 15e6);
         timeline.add(reported, position(t) );
      }
      return timeline;
   }

   private static final class PassSorterlessMath
   {
      static double wrap(final double v, final double l)
      {
         return LoopTakes.wrap(v, l);
      }
   }

   @Test
   void timelinePlacesEventsInTheRightPassDespiteLateUpdates()
   {
      final Random random = new Random(1);
      // 2.5 passes of 8 beats at 120 BPM: 10 seconds.
      final TransportTimeline timeline = timeline(10 * SECOND, random);
      final double halfMs = 0.5e-3 * TEMPO / 60; // half a millisecond in beats

      final TransportTimeline.Placement early = timeline.place(SECOND, LOOP_START, LOOP, TEMPO);
      assertEquals(0, early.pass());
      // The constant part of the update delay (on average 7.5 ms) shows as an offset.
      assertEquals(2.0, early.loopBeat() + 0.0075 * TEMPO / 60, 4 * halfMs);

      final TransportTimeline.Placement second = timeline.place(5 * SECOND, LOOP_START, LOOP, TEMPO);
      assertEquals(1, second.pass());
      assertEquals(2.0, second.loopBeat() + 0.0075 * TEMPO / 60, 4 * halfMs);

      // Well before and after the wrap. Within the update delay of the wrap the timeline still
      // shows the old pass; LoopTakes corrects that once the delay is known.
      assertEquals(0, timeline.place(4 * SECOND - 30_000_000, LOOP_START, LOOP, TEMPO).pass());
      final TransportTimeline.Placement afterWrap =
         timeline.place(4 * SECOND + 30_000_000, LOOP_START, LOOP, TEMPO);
      assertEquals(1, afterWrap.pass());
      assertTrue(afterWrap.loopBeat() < 0.1);
   }

   @Test
   void timelineWithoutSamplesPlacesNothing()
   {
      final TransportTimeline timeline = new TransportTimeline();
      timeline.start(100);
      timeline.add(50, 1.0); // before the guard
      assertNull(timeline.place(60, LOOP_START, LOOP, TEMPO));
   }

   @Test
   void keyboardEventsBecomeNotesWithLengthAndVelocities()
   {
      final TransportTimeline timeline = new TransportTimeline();
      timeline.start(Long.MIN_VALUE);
      for (long t = -SECOND; t < 10 * SECOND; t += SECOND / 100)
         timeline.add(t, LOOP_START + t * BEATS_PER_NANO);

      final KeyboardEvents keyboard = new KeyboardEvents();
      keyboard.add(-SECOND / 2, 0x90, 50, 90);       // pre-roll: before the loop start
      keyboard.add(-SECOND / 4, 0x80, 50, 10);
      keyboard.add(SECOND, 0x91, 60, 100);           // channel 2
      keyboard.add(SECOND + SECOND / 2, 0x81, 60, 40);
      keyboard.add(2 * SECOND, 0x90, 64, 127);
      keyboard.add(2 * SECOND + SECOND / 4, 0x90, 64, 0); // note-off as velocity 0
      keyboard.add(3 * SECOND, 0x90, 67, 64);        // still held at the end

      final List<KeyboardEvents.PlayedNote> notes =
         keyboard.notes(-SECOND, 3 * SECOND + SECOND / 2, timeline, LOOP_START, LOOP, TEMPO);
      assertEquals(3, notes.size());
      final KeyboardEvents.PlayedNote first = notes.get(0);
      assertEquals(1, first.channel());
      assertEquals(60, first.key());
      assertEquals(2.0, first.loopBeat(), 1e-6);
      assertEquals(1.0, first.durationBeats(), 1e-6);
      assertEquals(100 / 127.0, first.velocity(), 1e-9);
      assertEquals(40 / 127.0, first.releaseVelocity(), 1e-9);
      assertEquals(0.5, notes.get(1).durationBeats(), 1e-6);
      assertEquals(1.0, notes.get(2).durationBeats(), 1e-6);
   }

   @Test
   void keyboardTimesAreShiftedByTheClockOffset()
   {
      final TransportTimeline timeline = new TransportTimeline();
      timeline.start(Long.MIN_VALUE);
      for (long t = 0; t < 10 * SECOND; t += SECOND / 100)
         timeline.add(t, LOOP_START + t * BEATS_PER_NANO);

      // The companion's clock is 5 s behind; batches take 0.3 to 2 ms to arrive.
      final long behind = 5 * SECOND;
      final KeyboardEvents keyboard = new KeyboardEvents();
      keyboard.syncClock(3 * SECOND, 3 * SECOND - behind - 2_000_000);
      keyboard.syncClock(4 * SECOND, 4 * SECOND - behind - 300_000);
      keyboard.syncClock(5 * SECOND, 5 * SECOND - behind - 900_000);
      assertEquals(behind + 300_000, keyboard.clockOffset());

      keyboard.add(SECOND - behind, 0x90, 60, 100);
      keyboard.add(2 * SECOND - behind, 0x80, 60, 0);
      final List<KeyboardEvents.PlayedNote> notes =
         keyboard.notes(0, 6 * SECOND, timeline, LOOP_START, LOOP, TEMPO);
      assertEquals(1, notes.size());
      assertEquals(2.0 + 0.0003 * TEMPO / 60, notes.get(0).loopBeat(), 1e-6);
   }

   /** A note as played: pass, key, loop position (true), velocity. */
   private record Truth(int pass, int key, double loopBeat, double velocity)
   {
   }

   /** Three passes of an eight-note phrase with human timing, starting a sixteenth in. */
   private static List<Truth> performance(final Random random)
   {
      final int[] keys = { 60, 62, 64, 65, 67, 65, 64, 62 };
      final List<Truth> truth = new ArrayList<>();
      for (int pass = 0; pass < 3; pass++)
         for (int i = 0; i < keys.length; i++)
            truth.add(new Truth(pass, keys[i], i + 0.25 + (random.nextDouble() - 0.5) * 0.16,
               0.5 + random.nextDouble() * 0.4));
      return truth;
   }

   /** How the keyboard saw a note: 7 ms late (constant) plus up to 1 ms of jitter. */
   private static KeyboardEvents.PlayedNote seen(final Truth t, final Random random)
   {
      final double late = (0.007 + random.nextDouble() * 0.001) * TEMPO / 60;
      return new KeyboardEvents.PlayedNote(0, t.key(), t.pass(), t.loopBeat() + late, 0.4,
         t.velocity(), 0.5);
   }

   /** How Bitwig recorded a note: exact, clip-local, with attributes the keyboard lacks. */
   private static NoteData recorded(final Truth t)
   {
      return new NoteData(0, (int) Math.round((CLIP_OFFSET + t.loopBeat()) / STEP), t.key(),
         t.velocity(), 0.5, 0, 0.4, 0.25, -0.1, 0, 0.6, 0, false, 1, false, null, false, 1, 1,
         false, 1, 0, 1, 0, false, false);
   }

   private static void assertNear(final double expectedBeat, final NoteData note, final double ms)
   {
      final double tolerance = ms * 1e-3 * TEMPO / 60;
      assertEquals(expectedBeat, note.x() * STEP, tolerance, "note at step " + note.x());
   }

   @Test
   void overwrittenPassesAreRebuiltAndKeptNotesStayExact()
   {
      final Random random = new Random(3);
      final List<Truth> truth = performance(random);
      final List<KeyboardEvents.PlayedNote> played = new ArrayList<>();
      for (final Truth t : truth)
         played.add(seen(t, random));
      // Without overdub, Bitwig kept only the last pass.
      final List<NoteData> kept = new ArrayList<>();
      for (final Truth t : truth)
         if (t.pass() == 2)
            kept.add(recorded(t));

      final LoopTakes.Result result =
         LoopTakes.assemble(kept, played, STEP, LOOP, 0.08 * TEMPO / 60);
      assertEquals(3, result.passes().size());
      assertEquals(8, result.matched());
      assertEquals(16, result.rebuilt());
      assertEquals(0, result.unmatched());
      // The offset absorbs the clip position and the constant 7 ms delay.
      assertEquals(CLIP_OFFSET - 0.0075 * TEMPO / 60, result.offsetBeats(), 0.002);
      assertTrue(result.maxDeviationBeats() < 0.001 * TEMPO / 60, "deviation below 1 ms");

      // The last pass consists of Bitwig's notes themselves.
      for (final NoteData n : result.passes().get(2))
         assertTrue(kept.stream().anyMatch(k -> k == n));
      // Earlier passes are rebuilt close to where they were played, with recorded attributes.
      for (int pass = 0; pass < 2; pass++)
      {
         final List<NoteData> take = result.passes().get(pass);
         assertEquals(8, take.size());
         for (final Truth t : truth)
         {
            if (t.pass() != pass)
               continue;
            final NoteData n = take.stream().filter(x -> x.key() == t.key()
               && Math.abs(x.x() * STEP - (CLIP_OFFSET + t.loopBeat())) < 0.05)
               .findFirst().orElse(null);
            assertNotNull(n, "pass " + pass + " key " + t.key());
            assertNear(CLIP_OFFSET + t.loopBeat(), n, 1.5);
            assertEquals(t.velocity(), n.velocity(), 1e-9);
            assertEquals(0.25, n.pan(), 1e-9);
            assertEquals(0.6, n.gain(), 1e-9);
         }
      }
   }

   @Test
   void aRecordingStoppedMidPassKeepsTheTailOfThePreviousPass()
   {
      final Random random = new Random(5);
      final List<Truth> truth = performance(random);
      final List<KeyboardEvents.PlayedNote> played = new ArrayList<>();
      final List<NoteData> kept = new ArrayList<>();
      for (final Truth t : truth)
      {
         // Stopped after half of pass 3: it replaced only the first half.
         if (t.pass() == 2 && t.loopBeat() >= 3.5)
            continue;
         played.add(seen(t, random));
         if ((t.pass() == 2) || (t.pass() == 1 && t.loopBeat() >= 3.5))
            kept.add(recorded(t));
      }

      final LoopTakes.Result result =
         LoopTakes.assemble(kept, played, STEP, LOOP, 0.08 * TEMPO / 60);
      assertEquals(3, result.passes().size());
      assertEquals(8, result.passes().get(0).size());
      assertEquals(8, result.passes().get(1).size());
      assertEquals(4, result.passes().get(2).size());
      assertEquals(kept.size(), result.matched());
      // Pass 2's second half is Bitwig's own notes.
      for (final NoteData n : result.passes().get(1))
         if (n.x() * STEP >= CLIP_OFFSET + 4)
            assertSame(kept.stream().filter(k -> k == n).findFirst().orElse(null), n);
   }

   @Test
   void withoutKeyboardNotesTheRecordingIsOneTake()
   {
      final List<NoteData> kept = List.of(CompModelTest.note(10, 60, 0.5));
      final LoopTakes.Result result = LoopTakes.assemble(kept, List.of(), STEP, LOOP, 0.16);
      assertEquals(1, result.passes().size());
      assertEquals(1, result.unmatched());
   }

   @Test
   void aNotePlayedJustBeforeTheWrapCountsForTheOldPass()
   {
      final Random random = new Random(9);
      final List<Truth> truth = performance(random);
      final List<KeyboardEvents.PlayedNote> played = new ArrayList<>();
      final List<NoteData> kept = new ArrayList<>();
      for (final Truth t : truth)
      {
         played.add(seen(t, random));
         if (t.pass() == 2)
            kept.add(recorded(t));
      }
      // Played 3 ms before the end of pass 1 (index 0); seen 7.5 ms late, it lands at the start
      // of pass 2 (index 1) until the delay is corrected.
      final double beforeWrap = 0.003 * TEMPO / 60;
      final double late = 0.0075 * TEMPO / 60;
      played.add(new KeyboardEvents.PlayedNote(0, 72, 1, late - beforeWrap, 0.25, 0.8, 0.5));

      final LoopTakes.Result result =
         LoopTakes.assemble(kept, played, STEP, LOOP, 0.08 * TEMPO / 60);
      final NoteData moved = result.passes().get(0).stream().filter(n -> n.key() == 72)
         .findFirst().orElse(null);
      assertNotNull(moved, "in pass 1");
      assertNear(CLIP_OFFSET + LOOP - beforeWrap, moved, 1.0);
      assertTrue(result.passes().get(1).stream().noneMatch(n -> n.key() == 72));
   }

   @Test
   void notesAfterTheLastPassAreLeftOut()
   {
      // Single pass: recording stopped shortly after the wrap, and Bitwig recorded the note
      // played just after it at the loop start.
      final Random random = new Random(11);
      final List<KeyboardEvents.PlayedNote> played = new ArrayList<>();
      final List<NoteData> kept = new ArrayList<>();
      for (final Truth t : performance(random))
      {
         if (t.pass() > 0)
            continue;
         played.add(seen(t, random));
         kept.add(recorded(t));
      }
      final Truth late = new Truth(1, 72, 0.1, 0.7);
      played.add(seen(late, random));
      kept.add(recorded(late));

      final LoopTakes.Result result =
         LoopTakes.assemble(kept, played, STEP, LOOP, 0.08 * TEMPO / 60, 0);
      assertEquals(1, result.passes().size());
      assertEquals(8, result.passes().get(0).size());
      assertTrue(result.passes().get(0).stream().noneMatch(n -> n.key() == 72));
      assertEquals(1, result.dropped());
      assertEquals(0, result.rebuilt());
   }
}

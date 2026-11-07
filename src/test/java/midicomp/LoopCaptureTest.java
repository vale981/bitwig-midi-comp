package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopCaptureTest
{
   private static final double STEP = 1.0 / 128;
   private static final long COLLECT = (LoopCapture.COLLECT_DELAY_MILLIS + 10) * 1_000_000L;

   private FakeClip fake;
   private EditableClip clip;
   private LoopCapture capture;
   private final List<String> log = new ArrayList<>();

   @BeforeEach
   void setUp()
   {
      fake = new FakeClip();
      clip = new EditableClip(fake.clip, STEP);
      capture = new LoopCapture(log::add);
      capture.start();
   }

   private List<List<NoteData>> tick(final boolean recording, final double position,
      final int step, final long now)
   {
      return capture.tick(clip, true, recording, List.of(), position, step, now);
   }

   private void record(final int x, final int key)
   {
      fake.userAdd(x, key, 0.8, 0.25);
      fake.deliver();
   }

   private static boolean hasNoteAt(final List<NoteData> notes, final int x)
   {
      return notes.stream().anyMatch(n -> n.x() == x);
   }

   @Test
   void eachLoopRepetitionBecomesATake()
   {
      assertTrue(tick(true, 0.0, 0, System.nanoTime()).isEmpty());

      // Pass 1.
      record(10, 60);
      record(2000, 62);
      tick(true, 15.9, 2040, System.nanoTime());

      // The transport jumps back: the loop wrapped.
      final long wrap = System.nanoTime();
      assertTrue(tick(true, 0.05, 6, wrap).isEmpty());
      assertTrue(capture.isPending());

      // Reported after the wrap: a note played at the end of pass 1, and the first note of
      // pass 2 at the start of the loop.
      record(2030, 66);
      record(5, 64);

      final List<List<NoteData>> first = tick(true, 0.3, 40, wrap + COLLECT);
      assertEquals(1, first.size());
      final List<NoteData> pass1 = first.get(0);
      assertEquals(3, pass1.size());
      assertTrue(hasNoteAt(pass1, 10) && hasNoteAt(pass1, 2000) && hasNoteAt(pass1, 2030));
      assertFalse(hasNoteAt(pass1, 5));
      assertEquals(1, capture.passes());

      // Stopping the recording closes pass 2.
      final long stop = System.nanoTime();
      assertTrue(tick(false, 1.0, 120, stop).isEmpty());
      final List<List<NoteData>> second = tick(false, 1.0, 120, stop + COLLECT);
      assertEquals(1, second.size());
      assertEquals(1, second.get(0).size());
      assertTrue(hasNoteAt(second.get(0), 5));
      assertEquals(2, capture.passes());
   }

   @Test
   void singlePassStopsAtTheLoopEndAndDropsTheRest()
   {
      capture.armSinglePass();
      tick(true, 0.0, 0, System.nanoTime());
      record(10, 60);
      tick(true, 15.9, 2040, System.nanoTime());
      assertFalse(capture.takeStopRequest());

      final long wrap = System.nanoTime();
      tick(true, 0.05, 6, wrap);
      assertTrue(capture.takeStopRequest());
      assertFalse(capture.takeStopRequest());

      // Recorded between the wrap and the stop: not part of the take.
      record(3, 61);
      final long stop = System.nanoTime();
      tick(false, 0.1, 12, stop);

      final List<List<NoteData>> passes = tick(false, 0.1, 12, stop + COLLECT);
      assertEquals(1, passes.size());
      assertEquals(1, passes.get(0).size());
      assertTrue(hasNoteAt(passes.get(0), 10));
      assertFalse(capture.isPending());
      assertFalse(capture.isSinglePass());
   }

   @Test
   void passesWaitWhileTheClipIsNotSelected()
   {
      tick(true, 0.0, 0, System.nanoTime());
      record(10, 60);
      tick(true, 15.9, 2040, System.nanoTime());
      final long wrap = System.nanoTime();
      tick(true, 0.05, 6, wrap);
      record(100, 61);
      final long stop = System.nanoTime();
      tick(false, 0.5, 64, stop);

      // Both passes are due, but the clip is not under the cursor.
      assertTrue(capture.tick(clip, false, false, List.of(), 0.5, -1, stop + COLLECT).isEmpty());
      assertTrue(capture.isPending());

      // Once it is, both are collected in order, one take each.
      final List<List<NoteData>> passes =
         capture.tick(clip, true, false, List.of(), 0.5, -1, stop + 2 * COLLECT);
      assertEquals(2, passes.size());
      assertTrue(hasNoteAt(passes.get(0), 10));
      assertTrue(hasNoteAt(passes.get(1), 100));
      assertFalse(capture.isPending());
   }

   @Test
   void launcherLoopIsDetectedByThePlayingStep()
   {
      tick(true, 0.0, 0, System.nanoTime());
      record(100, 60);
      // The transport keeps running while the clip loops internally.
      tick(true, 3.9, 1990, System.nanoTime());
      final long wrap = System.nanoTime();
      tick(true, 4.1, 12, wrap);
      assertTrue(capture.isPending());
      final List<List<NoteData>> passes = tick(true, 4.4, 50, wrap + COLLECT);
      assertEquals(1, passes.size());
      assertEquals(1, passes.get(0).size());
   }

   @Test
   void notesPresentBeforeRecordingAreNotAPass()
   {
      record(10, 60);
      final long start = System.nanoTime();
      capture.tick(clip, true, true, clip.notes().all(), 0.0, 0, start);
      tick(false, 2.0, 200, start + 1_000_000);
      assertTrue(tick(false, 2.0, 200, start + 1_000_000 + COLLECT).isEmpty());
      assertEquals(0, capture.passes());
   }

   @Test
   void playbackWithoutRecordingCapturesNothing()
   {
      tick(false, 0.0, 0, System.nanoTime());
      record(10, 60);
      tick(false, 15.9, 2040, System.nanoTime());
      tick(false, 0.1, 10, System.nanoTime());
      assertFalse(capture.isPending());
      assertTrue(log.isEmpty());
   }
}

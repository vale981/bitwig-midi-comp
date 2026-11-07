package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopCaptureTest
{
   private static final double STEP = 1.0 / 128;
   private static final int MARGIN = 16;
   private static final long COLLECT = (LoopCapture.COLLECT_DELAY_MILLIS + 10) * 1_000_000L;

   private FakeClip fake;
   private EditableClip clip;
   private LoopCapture capture;

   @BeforeEach
   void setUp()
   {
      fake = new FakeClip();
      clip = new EditableClip(fake.clip, STEP);
      capture = new LoopCapture();
      capture.start();
   }

   private List<NoteData> tick(final boolean recording, final double position, final int step,
      final long now)
   {
      return capture.tick(clip, true, recording, List.of(), position, step, MARGIN, now);
   }

   private static boolean hasNoteAt(final List<NoteData> notes, final int x)
   {
      return notes.stream().anyMatch(n -> n.x() == x);
   }

   @Test
   void eachArrangerLoopPassBecomesAPass()
   {
      long now = System.nanoTime();
      assertNull(tick(true, 0.0, 0, now));

      // Pass 1: two notes, the second one just before the loop end.
      fake.userAdd(10, 60, 0.8, 0.25);
      fake.userAdd(2000, 62, 0.8, 0.25);
      fake.deliver();
      assertNull(tick(true, 15.9, 2040, now += 1_000_000));

      // The transport jumps back: the loop wrapped.
      final long wrap = System.nanoTime();
      assertNull(tick(true, 0.05, 6, wrap));
      assertTrue(capture.isPending());

      // The first note of pass 2 arrives before the pass is collected.
      fake.userAdd(5, 64, 0.8, 0.25);
      fake.deliver();

      final List<NoteData> pass1 = tick(true, 0.3, 40, wrap + COLLECT);
      assertNotNull(pass1);
      assertEquals(2, pass1.size());
      assertTrue(hasNoteAt(pass1, 10) && hasNoteAt(pass1, 2000));
      assertFalse(hasNoteAt(pass1, 5));
      assertEquals(1, capture.passes());

      // Stopping the recording closes pass 2, which contains the held-back note.
      final long stop = wrap + 2 * COLLECT;
      assertNull(tick(false, 1.0, 120, stop));
      final List<NoteData> pass2 = tick(false, 1.0, 120, stop + COLLECT);
      assertNotNull(pass2);
      assertEquals(1, pass2.size());
      assertTrue(hasNoteAt(pass2, 5));
      assertEquals(2, capture.passes());
   }

   @Test
   void launcherLoopIsDetectedByThePlayingStep()
   {
      final long start = System.nanoTime();
      tick(true, 0.0, 0, start);
      fake.userAdd(100, 60, 0.8, 0.25);
      fake.deliver();
      // The transport keeps running while the clip loops internally.
      tick(true, 3.9, 1990, start + 1_000_000);
      tick(true, 4.1, 12, start + 2_000_000);
      assertTrue(capture.isPending());
      final List<NoteData> pass = tick(true, 4.4, 50, start + 2_000_000 + COLLECT);
      assertNotNull(pass);
      assertEquals(1, pass.size());
   }

   @Test
   void notesPresentBeforeRecordingAreNotAPass()
   {
      fake.userAdd(10, 60, 0.8, 0.25);
      fake.deliver();
      final long start = System.nanoTime();
      capture.tick(clip, true, true, clip.notes().all(), 0.0, 0, MARGIN, start);
      assertNull(tick(false, 2.0, 200, start + 1_000_000));
      assertNull(tick(false, 2.0, 200, start + 1_000_000 + COLLECT));
      assertEquals(0, capture.passes());
   }

   @Test
   void playbackWithoutRecordingCapturesNothing()
   {
      final long start = System.nanoTime();
      tick(false, 0.0, 0, start);
      fake.userAdd(10, 60, 0.8, 0.25);
      fake.deliver();
      tick(false, 15.9, 2040, start + 1_000_000);
      tick(false, 0.1, 10, start + 2_000_000);
      assertFalse(capture.isPending());
   }
}

package midicomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns each pass of a loop recording into the comp clip into a take.
 *
 * When recording starts, the clip's expected content is taken as the baseline. Whenever the
 * playhead jumps back (the loop wrapped) or recording stops, the notes that are new since the
 * baseline form a pass.
 *
 * A pass is collected shortly after the wrap so that its last notes have arrived. By then the
 * first notes of the next pass may have arrived too. Those are recognized because they appeared
 * after the wrap *and* start no later than the clip's current playing step, i.e. at the beginning
 * of the loop; they stay out of the baseline and count towards the next pass.
 *
 * All methods run on the controller thread.
 */
final class LoopCapture
{
   /** Delay between a wrap and collecting the pass, so the pass's last notes have arrived. */
   static final long COLLECT_DELAY_MILLIS = 300;
   /** A backwards jump of the transport by more than this many beats counts as a wrap. */
   static final double WRAP_BEATS = 0.25;

   private boolean active;
   private Map<Long, NoteData> baseline = new HashMap<>();
   private int passes;
   private boolean wasRecording;
   private double lastPosition = Double.NaN;
   private int lastStep = -1;
   private long collectAt = -1;
   /** ``System.nanoTime()`` of the wrap that ended the pending pass, or ``-1`` after a stop. */
   private long wrapTime = -1;

   int passes()
   {
      return passes;
   }

   /** Returns whether a pass is waiting to be collected. */
   boolean isPending()
   {
      return collectAt >= 0;
   }

   /** Starts watching for recordings; previous passes are forgotten. */
   void start()
   {
      active = true;
      baseline = new HashMap<>();
      passes = 0;
      wasRecording = false;
      lastPosition = Double.NaN;
      lastStep = -1;
      collectAt = -1;
      wrapTime = -1;
   }

   void stop()
   {
      active = false;
      baseline = new HashMap<>();
      collectAt = -1;
   }

   /**
    * Follows the recording state and returns the notes of a pass when one is complete.
    *
    * Args:
    *    clip: cursor showing the recorded clip.
    *    available: whether ``clip`` currently shows the recorded clip.
    *    recording: whether Bitwig is recording into the clip.
    *    content: the clip's content before recording started; read when recording starts.
    *    position: transport position in beats.
    *    playingStep: the clip's current playing step, or a negative value if unknown.
    *    marginSteps: tolerance when comparing note positions with ``playingStep``.
    *    now: ``System.nanoTime()``.
    *
    * Returns:
    *    The notes of a completed pass, or ``null``.
    */
   List<NoteData> tick(
      final EditableClip clip,
      final boolean available,
      final boolean recording,
      final List<NoteData> content,
      final double position,
      final int playingStep,
      final int marginSteps,
      final long now)
   {
      if (!active)
         return null;

      if (recording)
      {
         if (!wasRecording)
         {
            baseline = new HashMap<>();
            for (final NoteData n : content)
               baseline.put(n.cellKey(), n);
            wasRecording = true;
         }
         else if (wrapped(position, playingStep, marginSteps))
         {
            schedule(now, now);
         }
         lastPosition = position;
         lastStep = playingStep;
      }
      else if (wasRecording)
      {
         wasRecording = false;
         lastPosition = Double.NaN;
         lastStep = -1;
         schedule(now, -1);
      }

      if (collectAt < 0 || now < collectAt || !available)
         return null;
      collectAt = -1;
      return collect(clip, playingStep, marginSteps);
   }

   /**
    * Arranger loops move the transport back; launcher clips loop internally, which only shows in
    * the clip's playing step.
    */
   private boolean wrapped(final double position, final int playingStep, final int marginSteps)
   {
      final boolean transportJump =
         !Double.isNaN(lastPosition) && position < lastPosition - WRAP_BEATS;
      final boolean stepJump =
         lastStep >= 0 && playingStep >= 0 && playingStep < lastStep - 2 * marginSteps;
      return transportJump || stepJump;
   }

   private void schedule(final long now, final long wrap)
   {
      collectAt = now + COLLECT_DELAY_MILLIS * 1_000_000L;
      wrapTime = wrap;
   }

   private List<NoteData> collect(final EditableClip clip, final int playingStep, final int marginSteps)
   {
      final List<NoteData> pass = new ArrayList<>();
      final Map<Long, NoteData> next = new HashMap<>();
      final NoteMirror mirror = clip.notes();

      for (final NoteData n : mirror.all())
      {
         final long cell = n.cellKey();
         if (baseline.containsKey(cell))
         {
            // Known note, possibly still growing (held across the wrap): keep it where it was.
            next.put(cell, n);
            continue;
         }

         final boolean nextPass = wrapTime >= 0
            && mirror.arrivedAt(cell) > wrapTime
            && (playingStep < 0 || n.x() <= playingStep + marginSteps);
         if (nextPass)
            continue;

         pass.add(n);
         next.put(cell, n);
      }

      baseline = next;
      if (pass.isEmpty())
         return null;
      passes++;
      return pass;
   }
}

package midicomp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns each pass of a loop recording into the session clip into a take.
 *
 * When recording starts, the clip's expected content becomes the baseline. Every wrap of the loop
 * (the transport jumping back, or the clip's playing step jumping back for launcher clips) ends a
 * pass, and so does the end of recording. Each ended pass is collected a moment later, when its
 * last notes have arrived.
 *
 * New notes are assigned to passes by the time they first appeared in the clip: a note belongs to
 * the pass during which it arrived. A note that arrives shortly after a wrap but lies in the second
 * half of the loop was played before the wrap and still belongs to the earlier pass. Collections
 * that cannot run (the clip is not under its cursor) are kept and run later, in order.
 *
 * In single-pass mode (:meth:`armSinglePass`) the first wrap ends the recording: the capture asks
 * for the transport to stop and, once stopped, takes everything new in the clip as one pass.
 *
 * Bitwig may add recorded notes to the clip only when recording ends, or shortly after. The
 * collection after recording therefore waits until the clip's notes have settled and, if nothing
 * new has arrived, keeps trying for :member:`FINAL_WAIT_MILLIS` before giving up.
 *
 * All methods run on the controller thread.
 */
final class LoopCapture
{
   /** Delay between the end of a pass and collecting it, so its last notes have arrived. */
   static final long COLLECT_DELAY_MILLIS = 300;
   /** Notes arriving this long after a wrap may still belong to the previous pass. */
   static final long LATE_NOTE_MILLIS = 500;
   /** How long the collection after recording waits for recorded notes to show up. */
   static final long FINAL_WAIT_MILLIS = 3000;
   /** A backwards jump of the transport by more than this many beats counts as a wrap. */
   static final double WRAP_BEATS = 0.25;

   /** A loop wrap: when it happened and the clip's playing step just before and after it. */
   private record Wrap(long time, int stepBefore, int stepAfter)
   {
   }

   /**
    * A pass waiting to be collected.
    *
    * Args:
    *    at: earliest collection time.
    *    pass: index of the pass.
    *    last: the pass that ends the recording; also takes notes assigned to later passes.
    *    deadline: until when an empty result is retried (only for the last pass).
    */
   private record Due(long at, int pass, boolean last, long deadline)
   {
   }

   private final Consumer<String> log;
   private boolean active;
   private Map<Long, NoteData> baseline = new HashMap<>();
   private int passes;
   private boolean wasRecording;
   private double lastPosition = Double.NaN;
   private int lastStep = -1;
   private long recordingStart;
   private final List<Wrap> wraps = new ArrayList<>();
   private final Deque<Due> due = new ArrayDeque<>();
   private boolean waitingLogged;
   private boolean singlePass;
   private boolean stopRequested;
   private boolean ignoreStop;

   /**
    * Args:
    *    log: receives a line for each capture event, for diagnostics.
    */
   LoopCapture(final Consumer<String> log)
   {
      this.log = log;
   }

   int passes()
   {
      return passes;
   }

   /** Returns whether a pass is waiting to be collected. */
   boolean isPending()
   {
      return !due.isEmpty();
   }

   /** Records only the next pass: the first wrap requests a stop (see :meth:`takeStopRequest`). */
   void armSinglePass()
   {
      singlePass = true;
   }

   /** Returns whether the single pass is complete and the transport should stop; clears it. */
   boolean takeStopRequest()
   {
      final boolean requested = stopRequested;
      stopRequested = false;
      return requested;
   }

   /** Returns whether a single-pass recording is armed or running. */
   boolean isSinglePass()
   {
      return singlePass;
   }

   /** Starts watching for recordings; previous passes are forgotten. */
   void start()
   {
      active = true;
      singlePass = false;
      stopRequested = false;
      ignoreStop = false;
      baseline = new HashMap<>();
      passes = 0;
      wasRecording = false;
      lastPosition = Double.NaN;
      lastStep = -1;
      wraps.clear();
      due.clear();
   }

   void stop()
   {
      active = false;
      baseline = new HashMap<>();
      wraps.clear();
      due.clear();
   }

   /**
    * Follows the recording state and returns the passes that could be collected.
    *
    * Args:
    *    clip: cursor showing the recorded clip.
    *    available: whether ``clip`` currently shows the recorded clip.
    *    recording: whether Bitwig is recording into the clip.
    *    content: the clip's content before recording started; read when recording starts.
    *    position: transport position in beats.
    *    playingStep: the clip's current playing step, or a negative value if unknown.
    *    now: ``System.nanoTime()``.
    *
    * Returns:
    *    The notes of each collected pass, in order; empty if none was collected.
    */
   List<List<NoteData>> tick(
      final EditableClip clip,
      final boolean available,
      final boolean recording,
      final List<NoteData> content,
      final double position,
      final int playingStep,
      final long now)
   {
      if (!active)
         return List.of();

      if (recording && !wasRecording)
      {
         baseline = new HashMap<>();
         for (final NoteData n : content)
            baseline.put(n.cellKey(), n);
         wraps.clear();
         recordingStart = now;
         wasRecording = true;
         log.accept(String.format("Recording started (clip %s, %d notes before)",
            available ? "selected" : "NOT selected", baseline.size()));
      }
      else if (recording)
      {
         if (wrapped(position, playingStep))
         {
            log.accept(String.format("Loop wrapped (position %.2f -> %.2f, step %d -> %d)",
               lastPosition, position, lastStep, playingStep));
            if (singlePass)
            {
               // The pass is complete: stop, then collect everything once recording has ended.
               singlePass = false;
               stopRequested = true;
               ignoreStop = true;
            }
            else
            {
               wraps.add(new Wrap(now, lastStep, playingStep));
               due.add(new Due(now + millis(COLLECT_DELAY_MILLIS), wraps.size() - 1, false, 0));
            }
         }
      }
      else if (wasRecording)
      {
         wasRecording = false;
         singlePass = false;
         log.accept(ignoreStop
            ? "Recording stopped at the end of the loop"
            : String.format("Recording stopped after %d wraps", wraps.size()));
         ignoreStop = false;
         due.add(new Due(now + millis(COLLECT_DELAY_MILLIS), wraps.size(), true,
            now + millis(FINAL_WAIT_MILLIS)));
      }
      lastPosition = recording ? position : Double.NaN;
      lastStep = recording ? playingStep : -1;

      final List<List<NoteData>> collected = new ArrayList<>();
      while (!due.isEmpty() && due.peekFirst().at() <= now)
      {
         final Due next = due.peekFirst();
         if (!available)
         {
            if (!waitingLogged)
               log.accept("Pass ready but the clip is not selected; waiting");
            waitingLogged = true;
            break;
         }
         // Recorded notes may still be arriving after the recording ended.
         if (next.last() && !clip.isSettled(LiveSync.SETTLE_MILLIS))
            break;
         final List<NoteData> pass = collect(clip, next.pass(), next.last());
         if (pass.isEmpty() && next.last() && now < next.deadline())
            break;
         due.pollFirst();
         waitingLogged = false;
         if (pass.isEmpty())
         {
            log.accept(String.format("Pass %d: no new notes%s", next.pass() + 1,
               next.last() ? " arrived after recording" : ""));
            continue;
         }
         passes++;
         log.accept(String.format("Pass %d: %d notes -> take", next.pass() + 1, pass.size()));
         collected.add(pass);
      }
      return collected;
   }

   private static long millis(final long ms)
   {
      return ms * 1_000_000L;
   }

   private boolean wrapped(final double position, final int playingStep)
   {
      final boolean transportJump =
         !Double.isNaN(lastPosition) && position < lastPosition - WRAP_BEATS;
      // Launcher clips loop internally: only the clip's playing step jumps back.
      final boolean stepJump = lastStep >= 0 && playingStep >= 0 && playingStep < lastStep - 8;
      return transportJump || stepJump;
   }

   /**
    * Returns the pass a note belongs to, from the time it first appeared in the clip.
    *
    * Args:
    *    arrival: ``System.nanoTime()`` when the note appeared.
    *    x: the note's start step.
    */
   private int passOf(final long arrival, final int x)
   {
      int pass = 0;
      while (pass < wraps.size() && wraps.get(pass).time() <= arrival)
         pass++;
      if (pass == 0)
         return 0;

      // Played just before the wrap but reported after it: it lies late in the loop.
      final Wrap wrap = wraps.get(pass - 1);
      final boolean late = arrival - wrap.time() < millis(LATE_NOTE_MILLIS);
      final boolean known = wrap.stepBefore() >= 0 && wrap.stepAfter() >= 0;
      if (late && known && x > (wrap.stepBefore() + wrap.stepAfter()) / 2)
         return pass - 1;
      return pass;
   }

   /**
    * Collects the new notes of ``pass``. Notes of earlier passes that arrived too late for their
    * own collection are included; with ``last`` set, so are notes assigned to later passes.
    */
   private List<NoteData> collect(final EditableClip clip, final int pass, final boolean last)
   {
      final List<NoteData> notes = new ArrayList<>();
      final NoteMirror mirror = clip.notes();
      for (final NoteData n : mirror.all())
      {
         final long cell = n.cellKey();
         if (baseline.containsKey(cell))
         {
            // Known note, possibly still growing (held across the wrap): keep it where it was.
            baseline.put(cell, n);
            continue;
         }
         final long arrival = mirror.arrivedAt(cell);
         final int notePass = arrival < recordingStart ? 0 : passOf(arrival, n.x());
         if (notePass > pass && !last)
            continue;
         notes.add(n);
         baseline.put(cell, n);
      }
      return notes;
   }
}

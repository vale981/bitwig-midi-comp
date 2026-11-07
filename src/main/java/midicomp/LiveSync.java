package midicomp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Writes the comp into the session clip whenever it changes.
 *
 * The clip is always overwritten with the comp: edits made to it in Bitwig are replaced on the
 * next comp change, and so are recorded notes once recording has ended (by then they have become
 * takes). Writes are diffs against the clip's current notes, so unchanged notes are not touched.
 *
 * The content written last is kept as :meth:`expected`; it is the baseline for detecting newly
 * recorded notes.
 *
 * Each write is verified once the clip has settled. If the cells holding notes differ from what
 * was written (a note the cursor had not reported when the diff was made, or a write the host did
 * not apply), the range is written again; if that does not help, the whole clip is cleared and
 * rewritten. Notes that survive even that cannot be removed through the API: they are reported
 * once and left out of later checks.
 *
 * All methods run on the controller thread.
 */
final class LiveSync
{
   /** Time without note updates before a clip's mirror is trusted. */
   static final long SETTLE_MILLIS = 150;
   /** Plain rewrite, then clear-and-rewrite, then give up. */
   private static final int MAX_REPAIRS = 2;

   /**
    * Result of :meth:`tick`.
    *
    * Args:
    *    wrote: whether the clip was written.
    *    firstWrite: the first write after linking, for restoring the original; otherwise ``null``.
    *    leftover: notes found in the clip that the last write should have removed.
    *    missing: notes the last write should have created but the clip lacks.
    *    gaveUp: the leftover notes could not be removed and are ignored from now on.
    */
   record Tick(
      boolean wrote,
      CompOperation firstWrite,
      List<NoteData> leftover,
      int missing,
      boolean gaveUp)
   {
      static final Tick NONE = new Tick(false, null, List.of(), 0, false);
   }

   private ClipRef link;
   private boolean dirty;
   private boolean firstWritePending;
   private List<NoteData> expected = List.of();
   private int lastLength;
   private boolean verifyPending;
   private int repairs;
   private boolean rewriteAll;
   /** Cells of notes that could not be removed; left out of checks. */
   private final java.util.Set<Long> ignored = new java.util.HashSet<>();

   ClipRef link()
   {
      return link;
   }

   /** Returns the content the clip held after the last write (or when linked). */
   List<NoteData> expected()
   {
      return expected;
   }

   /** Returns whether comp changes are waiting to be written. */
   boolean isPending()
   {
      return link != null && dirty;
   }

   /**
    * Links a clip. The comp is written on the next :meth:`tick` in which the clip is available.
    *
    * Args:
    *    newLink: the clip to keep in sync.
    *    content: the clip's notes at the time of linking.
    */
   void link(final ClipRef newLink, final List<NoteData> content)
   {
      link = newLink;
      dirty = true;
      firstWritePending = true;
      expected = List.copyOf(content);
      lastLength = 0;
      verifyPending = false;
      repairs = 0;
      rewriteAll = false;
      ignored.clear();
   }

   void unlink()
   {
      link = null;
      dirty = false;
      expected = List.of();
   }

   /** Requests a write because the comp changed. */
   void markDirty()
   {
      if (link != null)
         dirty = true;
   }

   /**
    * Marks that Bitwig is recording into the clip. Call instead of :meth:`tick` while recording;
    * the comp is written over the recording afterwards.
    */
   void holdForRecording()
   {
      markDirty();
   }

   /**
    * Writes pending changes when the clip is reachable and its notes have settled.
    *
    * Args:
    *    clip: the linked clip's cursor.
    *    available: whether ``clip`` currently points at the linked clip.
    *    desired: supplies the rendered comp; notes must start before ``length``.
    *    length: number of steps to write, at most the clip's length.
    *
    * Returns:
    *    What happened.
    */
   Tick tick(
      final EditableClip clip,
      final boolean available,
      final Supplier<List<NoteData>> desired,
      final int length)
   {
      if (link == null || !available || !clip.isSettled(SETTLE_MILLIS))
         return Tick.NONE;

      if (!dirty)
      {
         if (!verifyPending)
            return Tick.NONE;
         verifyPending = false;
         final EditableClip.CellDifference diff =
            clip.cellDifference(0, lastLength, expected, ignored);
         if (diff.isEmpty())
         {
            repairs = 0;
            return Tick.NONE;
         }
         if (repairs < MAX_REPAIRS)
         {
            // First a plain rewrite; if notes survive it, clear the whole clip and rewrite.
            rewriteAll = repairs == 1;
            repairs++;
            dirty = true;
            return new Tick(false, null, diff.leftover(), diff.missing(), false);
         }
         for (final NoteData n : diff.leftover())
            ignored.add(n.cellKey());
         repairs = 0;
         return new Tick(false, null, diff.leftover(), diff.missing(), true);
      }

      final List<NoteData> notes = desired.get();
      // Cover the previous length too, so that notes beyond a shortened comp are removed.
      final int range = Math.max(length, lastLength);
      final List<NoteData> next = new ArrayList<>(notes);
      for (final NoteData n : clip.notes().all())
         if (n.x() >= range)
            next.add(n);
      final CompOperation op = rewriteAll ? clip.rewriteAll(next) : clip.sync(0, range, notes);
      rewriteAll = false;
      expected = List.copyOf(next);
      lastLength = range;
      dirty = false;
      verifyPending = true;

      final CompOperation first = firstWritePending ? op : null;
      firstWritePending = false;
      return new Tick(true, first, List.of(), 0, false);
   }
}

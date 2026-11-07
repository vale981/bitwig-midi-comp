package midicomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitwig.extension.controller.api.NoteStep;

/**
 * Local copy of the notes in a cursor clip, kept current by the clip's note-step observer.
 *
 * Only cells whose state is ``NoteOn`` (a note starts there) are stored. For each note the time
 * it first appeared is kept, which tells apart notes recorded before and after a loop wrap.
 */
final class NoteMirror
{
   private final Map<Long, NoteData> notes = new HashMap<>();
   private final Map<Long, Long> arrivals = new HashMap<>();

   /**
    * Applies one observer update.
    *
    * Args:
    *    step: the changed grid cell.
    */
   void update(final NoteStep step)
   {
      final long cell = NoteData.cellKey(step.channel(), step.x(), step.y());
      if (step.state() == NoteStep.State.NoteOn)
      {
         if (notes.put(cell, NoteData.of(step)) == null)
            arrivals.put(cell, System.nanoTime());
      }
      else
      {
         notes.remove(cell);
         arrivals.remove(cell);
      }
   }

   void clear()
   {
      notes.clear();
      arrivals.clear();
   }

   /**
    * Returns when the note in a cell first appeared (``System.nanoTime()``), or
    * ``Long.MIN_VALUE`` if the cell is empty.
    *
    * Args:
    *    cellKey: key from :meth:`NoteData.cellKey`.
    */
   long arrivedAt(final long cellKey)
   {
      return arrivals.getOrDefault(cellKey, Long.MIN_VALUE);
   }

   /**
    * Returns the note starting in a cell, or ``null``.
    *
    * Args:
    *    cellKey: key from :meth:`NoteData.cellKey`.
    */
   NoteData get(final long cellKey)
   {
      return notes.get(cellKey);
   }

   /** Returns all notes. */
   List<NoteData> all()
   {
      return new ArrayList<>(notes.values());
   }

   /**
    * Returns the notes starting in the step range ``[from, to)``.
    *
    * Args:
    *    from: first step, inclusive.
    *    to: last step, exclusive.
    */
   List<NoteData> startingIn(final int from, final int to)
   {
      final List<NoteData> result = new ArrayList<>();
      for (final NoteData n : notes.values())
         if (n.x() >= from && n.x() < to)
            result.add(n);
      return result;
   }

}

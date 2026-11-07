package midicomp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes and the assignment of time ranges to takes.
 *
 * The comp is a sorted list of non-overlapping :class:`Segment` objects. Ranges without a segment
 * are silent. Adjacent segments of the same take are merged.
 */
final class CompModel
{
   /**
    * A range ``[from, to)`` in grid steps that plays the notes of take ``takeId``.
    */
   record Segment(int from, int to, int takeId)
   {
   }

   /** A note of the rendered comp and the take it came from. */
   record CompNote(NoteData note, int takeId)
   {
   }

   private static final int UNDO_DEPTH = 200;

   private final List<Take> takes = new ArrayList<>();
   private List<Segment> segments = List.of();
   private final Deque<List<Segment>> undo = new ArrayDeque<>();
   private int nextId = 1;

   List<Take> takes()
   {
      return Collections.unmodifiableList(takes);
   }

   List<Segment> segments()
   {
      return segments;
   }

   boolean canUndo()
   {
      return !undo.isEmpty();
   }

   /** Returns the comp length in steps: the length of the longest take. */
   int length()
   {
      int length = 0;
      for (final Take t : takes)
         length = Math.max(length, t.length());
      return length;
   }

   /**
    * Adds a take. The first take is assigned to its whole length.
    *
    * Args:
    *    name: display name.
    *    source: where the notes came from.
    *    origin: the clip the take follows, or ``null``.
    *    length: length in steps.
    *    notes: the notes.
    *
    * Returns:
    *    The new take.
    */
   Take addTake(
      final String name,
      final Take.Source source,
      final ClipRef origin,
      final int length,
      final List<NoteData> notes)
   {
      final Take take = new Take(nextId++, name, source, origin, length, List.copyOf(notes));
      takes.add(take);
      if (segments.isEmpty())
         assign(0, length, take.id());
      return take;
   }

   /**
    * Replaces the notes and length of a take, keeping its identity and segments.
    *
    * Args:
    *    id: take identifier.
    *    length: new length in steps.
    *    notes: new notes.
    *
    * Returns:
    *    Whether the take existed and changed.
    */
   boolean updateTake(final int id, final int length, final List<NoteData> notes)
   {
      for (int i = 0; i < takes.size(); i++)
      {
         final Take t = takes.get(i);
         if (t.id() != id)
            continue;
         if (t.length() == length && sameNotes(t.notes(), notes))
            return false;
         takes.set(i, new Take(t.id(), t.name(), t.source(), t.origin(), length,
            List.copyOf(notes)));
         return true;
      }
      return false;
   }

   /**
    * Returns whether two note lists contain the same notes, ignoring order and selection.
    *
    * Args:
    *    a: first list.
    *    b: second list.
    */
   static boolean sameNotes(final List<NoteData> a, final List<NoteData> b)
   {
      if (a.size() != b.size())
         return false;
      final Map<Long, NoteData> byCell = new HashMap<>();
      for (final NoteData n : a)
         byCell.put(n.cellKey(), n);
      for (final NoteData n : b)
      {
         final NoteData other = byCell.get(n.cellKey());
         if (other == null || !other.sameAs(n))
            return false;
      }
      return true;
   }

   Take take(final int id)
   {
      for (final Take t : takes)
         if (t.id() == id)
            return t;
      return null;
   }

   /**
    * Removes a take and its segments.
    *
    * Args:
    *    id: take identifier.
    *
    * Returns:
    *    Whether the take existed.
    */
   boolean removeTake(final int id)
   {
      if (take(id) == null)
         return false;
      pushUndo();
      takes.removeIf(t -> t.id() == id);
      segments = withoutMissingTakes(segments);
      return true;
   }

   /**
    * Assigns ``[from, to)`` to a take, or clears it.
    *
    * Args:
    *    from: first step, inclusive.
    *    to: last step, exclusive.
    *    takeId: take identifier, or ``-1`` to make the range silent.
    */
   void assign(final int from, final int to, final int takeId)
   {
      if (to <= from)
         return;
      pushUndo();

      final List<Segment> result = new ArrayList<>();
      for (final Segment s : segments)
      {
         if (s.to() <= from || s.from() >= to)
         {
            result.add(s);
            continue;
         }
         if (s.from() < from)
            result.add(new Segment(s.from(), from, s.takeId()));
         if (s.to() > to)
            result.add(new Segment(to, s.to(), s.takeId()));
      }
      if (takeId >= 0)
         result.add(new Segment(from, to, takeId));

      segments = merged(result);
   }

   /** Reverts the last segment change. Returns whether anything was undone. */
   boolean undo()
   {
      final List<Segment> previous = undo.pollFirst();
      if (previous == null)
         return false;
      segments = withoutMissingTakes(previous);
      return true;
   }

   /** Removes all takes and segments. */
   void clear()
   {
      takes.clear();
      segments = List.of();
      undo.clear();
   }

   /**
    * Builds the comp's notes.
    *
    * Each segment contributes the notes of its take that start inside it. A note that sounds past
    * the start of a later segment is shortened to end there.
    *
    * Args:
    *    stepSize: length of one grid step in beats.
    *
    * Returns:
    *    The notes, with the take each came from.
    */
   List<CompNote> render(final double stepSize)
   {
      final List<CompNote> result = new ArrayList<>();
      for (int i = 0; i < segments.size(); i++)
      {
         final Segment s = segments.get(i);
         final Take take = take(s.takeId());
         if (take == null)
            continue;
         final int nextStart = i + 1 < segments.size() ? segments.get(i + 1).from() : Integer.MAX_VALUE;

         for (final NoteData n : take.notes())
         {
            if (n.x() < s.from() || n.x() >= s.to())
               continue;
            final double maxDuration = (nextStart - (double) n.x()) * stepSize;
            final NoteData note = n.duration() > maxDuration ? n.withDuration(maxDuration) : n;
            result.add(new CompNote(note, take.id()));
         }
      }
      return result;
   }

   private void pushUndo()
   {
      undo.addFirst(segments);
      while (undo.size() > UNDO_DEPTH)
         undo.removeLast();
   }

   private List<Segment> withoutMissingTakes(final List<Segment> list)
   {
      final List<Segment> result = new ArrayList<>();
      for (final Segment s : list)
         if (take(s.takeId()) != null)
            result.add(s);
      return merged(result);
   }

   private static List<Segment> merged(final List<Segment> list)
   {
      final List<Segment> sorted = new ArrayList<>(list);
      sorted.sort(Comparator.comparingInt(Segment::from));

      final List<Segment> result = new ArrayList<>();
      for (final Segment s : sorted)
      {
         final int last = result.size() - 1;
         if (last >= 0 && result.get(last).takeId() == s.takeId() && result.get(last).to() == s.from())
            result.set(last, new Segment(result.get(last).from(), s.to(), s.takeId()));
         else
            result.add(s);
      }
      return List.copyOf(result);
   }
}

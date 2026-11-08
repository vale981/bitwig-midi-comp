package midicomp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

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
   /** Counts changes of takes or segments, so callers can notice them. */
   private long revision;

   /** Returns a number that changes whenever takes or segments change. */
   long revision()
   {
      return revision;
   }
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
    *    length: length in steps.
    *    notes: the notes.
    *
    * Returns:
    *    The new take.
    */
   Take addTake(
      final String name,
      final Take.Source source,
      final int length,
      final List<NoteData> notes)
   {
      return addTake(name, source, length, notes, List.of());
   }

   /**
    * Adds a take with control messages.
    *
    * Args:
    *    name: display name.
    *    source: where the notes came from.
    *    length: length in grid steps.
    *    notes: the notes in grid coordinates.
    *    controls: control messages in grid coordinates.
    *
    * Returns:
    *    The new take.
    */
   Take addTake(
      final String name,
      final Take.Source source,
      final int length,
      final List<NoteData> notes,
      final List<ControlEvent> controls)
   {
      final List<ControlEvent> sorted = new ArrayList<>(controls);
      sorted.sort(java.util.Comparator.comparingInt(ControlEvent::x));
      final Take take =
         new Take(nextId++, name, source, length, List.copyOf(notes), List.copyOf(sorted));
      takes.add(take);
      revision++;
      if (segments.isEmpty())
         assign(0, length, take.id());
      return take;
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
      revision++;
      return true;
   }

   /**
    * Renames a take. Not an undoable edit: undo concerns the cuts.
    *
    * Args:
    *    id: take identifier.
    *    name: the new name.
    *
    * Returns:
    *    Whether the take existed.
    */
   boolean renameTake(final int id, final String name)
   {
      for (int i = 0; i < takes.size(); i++)
      {
         final Take t = takes.get(i);
         if (t.id() == id)
         {
            takes.set(i, new Take(t.id(), name, t.source(), t.length(), t.notes(), t.controls()));
            revision++;
            return true;
         }
      }
      return false;
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
      revision++;
   }

   /** Reverts the last segment change. Returns whether anything was undone. */
   boolean undo()
   {
      final List<Segment> previous = undo.pollFirst();
      if (previous == null)
         return false;
      segments = withoutMissingTakes(previous);
      revision++;
      return true;
   }

   /**
    * Replaces everything with a restored comp. The undo history is cleared.
    *
    * Args:
    *    restored: takes in order; their identifiers are ignored and reassigned.
    *    segments: ranges as ``{from, to, takeIndex}`` with indices into ``restored``.
    */
   void restore(final List<Take> restored, final List<int[]> segments)
   {
      clear();
      final List<Integer> ids = new ArrayList<>();
      for (final Take t : restored)
      {
         final Take take = new Take(nextId++, t.name(), t.source(), t.length(),
            List.copyOf(t.notes()), List.copyOf(t.controls()));
         takes.add(take);
         ids.add(take.id());
      }
      final List<Segment> list = new ArrayList<>();
      for (final int[] s : segments)
         if (s[1] > s[0])
            list.add(new Segment(s[0], s[1], ids.get(s[2])));
      this.segments = merged(list);
      revision++;
   }

   /**
    * Builds the comp's control messages: each segment contributes its take's messages inside it,
    * and at its start the take's last value of every controller before it, so that a cut does
    * not keep the previous take's state (for example the hi-hat opening).
    *
    * Returns:
    *    The messages, by position.
    */
   List<ControlEvent> renderControls()
   {
      final List<ControlEvent> result = new ArrayList<>();
      for (final Segment s : segments)
      {
         final Take take = take(s.takeId());
         if (take == null)
            continue;
         final java.util.Map<Integer, ControlEvent> before = new java.util.LinkedHashMap<>();
         final List<ControlEvent> inside = new ArrayList<>();
         for (final ControlEvent c : take.controls())
         {
            if (c.x() < s.from())
               before.put(c.controller(), c);
            else if (c.x() < s.to())
               inside.add(c);
         }
         for (final ControlEvent c : before.values())
            result.add(c.at(s.from()));
         result.addAll(inside);
      }
      return result;
   }

   /**
    * Returns the part of the comp inside ``[from, from + length)`` as a new comp starting at 0:
    * takes and segments cropped and moved, notes shortened at the end, and each take's
    * controller values at ``from`` repeated at 0.
    *
    * Args:
    *    from: first step.
    *    length: number of steps.
    *    stepSize: grid step length in beats.
    */
   CompModel cropped(final int from, final int length, final double stepSize)
   {
      final int to = from + length;
      final List<Take> copies = new ArrayList<>();
      final List<Integer> ids = new ArrayList<>();
      for (final Take t : takes)
      {
         final List<NoteData> notes = new ArrayList<>();
         for (final NoteData n : t.notes())
         {
            if (n.x() < from || n.x() >= to)
               continue;
            final double room = (to - n.x()) * stepSize;
            final NoteData moved = n.withX(n.x() - from);
            notes.add(n.duration() > room ? moved.withDuration(room) : moved);
         }
         final java.util.Map<Integer, ControlEvent> before = new java.util.LinkedHashMap<>();
         final List<ControlEvent> controls = new ArrayList<>();
         for (final ControlEvent c : t.controls())
         {
            if (c.x() < from)
               before.put(c.controller(), c);
            else if (c.x() < to)
               controls.add(c.at(c.x() - from));
         }
         final List<ControlEvent> all = new ArrayList<>();
         for (final ControlEvent c : before.values())
            all.add(c.at(0));
         all.addAll(controls);
         copies.add(new Take(t.id(), t.name(), t.source(), length, notes, all));
         ids.add(t.id());
      }
      final List<int[]> cut = new ArrayList<>();
      for (final Segment s : segments)
      {
         final int a = Math.max(s.from(), from);
         final int b = Math.min(s.to(), to);
         if (b > a)
            cut.add(new int[] { a - from, b - from, ids.indexOf(s.takeId()) });
      }
      final CompModel result = new CompModel();
      result.restore(copies, cut);
      return result;
   }

   /**
    * Merges a comp of a region back into the full comp it was cropped from (see :meth:`cropped`):
    * segments outside the region stay, those inside come from ``region`` (moved by ``from``).
    * Takes correspond by position; their names come from ``region``, and takes ``region`` has
    * beyond the full comp's are added, moved by ``from``.
    *
    * Args:
    *    full: takes and segments of the full comp, as from :meth:`CompExport.read`.
    *    region: the edited region comp.
    *    from: first step of the region in the full comp.
    *    length: length of the region in steps.
    *
    * Returns:
    *    The merged comp.
    */
   static CompModel mergeRegion(
      final CompExport.Imported full,
      final CompModel region,
      final int from,
      final int length)
   {
      final List<Take> merged = new ArrayList<>();
      final List<Take> regionTakes = region.takes();
      final int fullLength = full.takes().stream().mapToInt(Take::length).max().orElse(0);
      for (int i = 0; i < Math.max(full.takes().size(), regionTakes.size()); i++)
      {
         if (i < full.takes().size())
         {
            final Take t = full.takes().get(i);
            final String name = i < regionTakes.size() ? regionTakes.get(i).name() : t.name();
            merged.add(new Take(t.id(), name, t.source(), t.length(), t.notes(), t.controls()));
            continue;
         }
         final Take t = regionTakes.get(i);
         final List<NoteData> notes = new ArrayList<>();
         for (final NoteData n : t.notes())
            notes.add(n.withX(n.x() + from));
         final List<ControlEvent> controls = new ArrayList<>();
         for (final ControlEvent c : t.controls())
            controls.add(c.at(c.x() + from));
         merged.add(new Take(t.id(), t.name(), t.source(), Math.max(fullLength, from + t.length()),
            notes, controls));
      }

      final int to = from + length;
      final List<int[]> segments = new ArrayList<>();
      for (final int[] s : full.segments())
      {
         if (s[0] < from)
            segments.add(new int[] { s[0], Math.min(s[1], from), s[2] });
         if (s[1] > to)
            segments.add(new int[] { Math.max(s[0], to), s[1], s[2] });
      }
      for (final Segment s : region.segments())
      {
         final int index = region.takes().indexOf(region.take(s.takeId()));
         if (index >= 0)
            segments.add(new int[] { s.from() + from, Math.min(s.to(), length) + from, index });
      }
      segments.removeIf(s -> s[1] <= s[0]);
      final CompModel result = new CompModel();
      result.restore(merged, segments);
      return result;
   }

   /** Removes all takes and segments. */
   void clear()
   {
      takes.clear();
      segments = List.of();
      revision++;
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

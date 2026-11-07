package midicomp;

import java.util.List;

/**
 * Snapshot of a clip's notes, used as one take in the comp.
 *
 * Args:
 *    id: identifier, unique within a :class:`CompModel`.
 *    name: display name.
 *    source: where the notes came from.
 *    origin: the clip the take follows, or ``null`` for takes that are not tied to a clip
 *       (recorded loop passes, adopted edits).
 *    length: length in grid steps.
 *    notes: the notes in grid coordinates.
 */
record Take(int id, String name, Take.Source source, ClipRef origin, int length, List<NoteData> notes)
{
   enum Source
   {
      LAUNCHER,
      ARRANGER
   }
}

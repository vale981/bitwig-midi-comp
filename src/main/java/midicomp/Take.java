package midicomp;

import java.util.List;

/**
 * A copy of notes used as one take in the comp. Takes are not tied to the clip they came from.
 *
 * Args:
 *    id: identifier, unique within a :class:`CompModel`.
 *    name: display name.
 *    source: where the notes came from.
 *    length: length in grid steps.
 *    notes: the notes in grid coordinates.
 */
record Take(int id, String name, Take.Source source, int length, List<NoteData> notes)
{
   enum Source
   {
      LAUNCHER,
      ARRANGER
   }
}

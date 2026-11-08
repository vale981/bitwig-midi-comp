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
 *    controls: control messages (CC, pressure, pitch bend) in grid coordinates, by position.
 */
record Take(int id, String name, Take.Source source, int length, List<NoteData> notes,
   List<ControlEvent> controls)
{
   /** A take without control messages. */
   Take(final int id, final String name, final Source source, final int length,
      final List<NoteData> notes)
   {
      this(id, name, source, length, notes, List.of());
   }

   enum Source
   {
      LAUNCHER,
      ARRANGER
   }
}

package midicomp;

import java.util.List;

/**
 * Record of one write to a clip, sufficient to revert it.
 *
 * Args:
 *    removed: notes that were deleted, or replaced in place (with their old attributes).
 *    added: notes that were written, or the new attributes of replaced notes.
 */
record CompOperation(List<NoteData> removed, List<NoteData> added)
{
}

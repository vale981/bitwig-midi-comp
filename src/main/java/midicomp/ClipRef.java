package midicomp;

import java.util.Objects;

/**
 * Identifies a clip that the API only exposes through a cursor.
 *
 * Args:
 *    source: launcher or arranger.
 *    trackPosition: position of the clip's track.
 *    trackName: name of the clip's track.
 *    sceneIndex: launcher scene, or ``-1`` for arranger clips.
 *    label: display name.
 */
record ClipRef(Take.Source source, int trackPosition, String trackName, int sceneIndex, String label)
{
   /**
    * Returns whether ``other`` points at the same clip. Labels are ignored.
    *
    * Arranger clips are identified by track only; the API exposes no position or name for them.
    *
    * Args:
    *    other: reference to compare with, may be ``null``.
    */
   boolean sameClip(final ClipRef other)
   {
      return other != null
         && source == other.source
         && trackPosition == other.trackPosition
         && Objects.equals(trackName, other.trackName)
         && (source == Take.Source.ARRANGER || sceneIndex == other.sceneIndex);
   }
}

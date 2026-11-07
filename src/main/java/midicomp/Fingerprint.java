package midicomp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Identifies a clip's content, so that a comp can be recognized in a copy of its clip.
 *
 * The fingerprint covers each note's channel, key and position (in beats, rounded to 1/960 beat
 * so that grid resolutions agree); other attributes do not distinguish versions of a comp.
 */
final class Fingerprint
{
   private Fingerprint()
   {
   }

   /**
    * Returns the fingerprint of some notes.
    *
    * Args:
    *    notes: the notes, in grid coordinates.
    *    stepSize: grid step length in beats.
    *
    * Returns:
    *    16 hex digits, or an empty string without notes.
    */
   static String of(final List<NoteData> notes, final double stepSize)
   {
      if (notes.isEmpty())
         return "";
      final List<String> keys = new ArrayList<>(notes.size());
      for (final NoteData n : notes)
         keys.add(n.channel() + ":" + n.key() + ":" + Math.round(n.x() * stepSize * 960.0));
      keys.sort(null);
      try
      {
         final byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(String.join(",", keys).getBytes(StandardCharsets.UTF_8));
         final StringBuilder hex = new StringBuilder(16);
         for (int i = 0; i < 8; i++)
            hex.append(String.format("%02x", digest[i]));
         return hex.toString();
      }
      catch (final NoSuchAlgorithmException e)
      {
         throw new IllegalStateException("SHA-256 is unavailable", e);
      }
   }
}

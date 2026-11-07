package midicomp;

import com.bitwig.extension.controller.api.NoteOccurrence;
import com.bitwig.extension.controller.api.NoteStep;

/**
 * Immutable snapshot of a note as reported by the note-step grid.
 *
 * Positions are grid coordinates: ``x`` is the step index (time) and ``key`` the MIDI key. All
 * other fields mirror the editable attributes of :class:`NoteStep`.
 */
record NoteData(
   int channel,
   int x,
   int key,
   double velocity,
   double releaseVelocity,
   double velocitySpread,
   double duration,
   double pan,
   double timbre,
   double pressure,
   double gain,
   double transpose,
   boolean chanceEnabled,
   double chance,
   boolean occurrenceEnabled,
   NoteOccurrence occurrence,
   boolean recurrenceEnabled,
   int recurrenceLength,
   int recurrenceMask,
   boolean repeatEnabled,
   int repeatCount,
   double repeatCurve,
   double repeatVelocityEnd,
   double repeatVelocityCurve,
   boolean muted,
   boolean selected)
{
   /**
    * Copies all attributes of a note step.
    *
    * Args:
    *    step: a step whose state is ``NoteOn``.
    *
    * Returns:
    *    The snapshot.
    */
   static NoteData of(final NoteStep step)
   {
      return new NoteData(
         step.channel(),
         step.x(),
         step.y(),
         step.velocity(),
         step.releaseVelocity(),
         step.velocitySpread(),
         step.duration(),
         step.pan(),
         step.timbre(),
         step.pressure(),
         step.gain(),
         step.transpose(),
         step.isChanceEnabled(),
         step.chance(),
         step.isOccurrenceEnabled(),
         step.occurrence(),
         step.isRecurrenceEnabled(),
         step.recurrenceLength(),
         step.recurrenceMask(),
         step.isRepeatEnabled(),
         step.repeatCount(),
         step.repeatCurve(),
         step.repeatVelocityEnd(),
         step.repeatVelocityCurve(),
         step.isMuted(),
         step.isIsSelected());
   }

   /**
    * Packs ``(channel, x, key)`` into a map key.
    *
    * Args:
    *    channel: MIDI channel, 0 to 15.
    *    x: step index.
    *    key: MIDI key, 0 to 127.
    *
    * Returns:
    *    A value unique for every grid cell.
    */
   static long cellKey(final int channel, final int x, final int key)
   {
      return ((long) x << 11) | ((long) key << 4) | channel;
   }

   long cellKey()
   {
      return cellKey(channel, x, key);
   }

   /**
    * Returns a copy of this note with a different length.
    *
    * Args:
    *    newDuration: length in beats.
    */
   NoteData withDuration(final double newDuration)
   {
      return new NoteData(channel, x, key, velocity, releaseVelocity, velocitySpread, newDuration,
         pan, timbre, pressure, gain, transpose, chanceEnabled, chance, occurrenceEnabled,
         occurrence, recurrenceEnabled, recurrenceLength, recurrenceMask, repeatEnabled,
         repeatCount, repeatCurve, repeatVelocityEnd, repeatVelocityCurve, muted, selected);
   }

   /**
    * Returns whether ``other`` describes the same note, ignoring selection and float noise.
    *
    * Args:
    *    other: note to compare with.
    */
   boolean sameAs(final NoteData other)
   {
      return channel == other.channel && x == other.x && key == other.key
         && close(velocity, other.velocity) && close(releaseVelocity, other.releaseVelocity)
         && close(velocitySpread, other.velocitySpread) && close(duration, other.duration)
         && close(pan, other.pan) && close(timbre, other.timbre)
         && close(pressure, other.pressure) && close(gain, other.gain)
         && close(transpose, other.transpose) && chanceEnabled == other.chanceEnabled
         && close(chance, other.chance) && occurrenceEnabled == other.occurrenceEnabled
         && occurrence == other.occurrence && recurrenceEnabled == other.recurrenceEnabled
         && recurrenceLength == other.recurrenceLength && recurrenceMask == other.recurrenceMask
         && repeatEnabled == other.repeatEnabled && repeatCount == other.repeatCount
         && close(repeatCurve, other.repeatCurve)
         && close(repeatVelocityEnd, other.repeatVelocityEnd)
         && close(repeatVelocityCurve, other.repeatVelocityCurve) && muted == other.muted;
   }

   private static boolean close(final double a, final double b)
   {
      return Math.abs(a - b) < 1e-4;
   }

   /** Returns the velocity as an integer in ``1..127`` for :meth:`Clip.setStep`. */
   int midiVelocity()
   {
      return Math.max(1, Math.min(127, (int) Math.round(velocity * 127.0)));
   }

   /**
    * Writes every attribute except position and selection to ``target``.
    *
    * Args:
    *    target: an existing note in the destination clip.
    */
   void applyTo(final NoteStep target)
   {
      target.setVelocity(velocity);
      target.setReleaseVelocity(releaseVelocity);
      target.setVelocitySpread(velocitySpread);
      target.setDuration(duration);
      target.setPan(pan);
      target.setTimbre(timbre);
      target.setPressure(pressure);
      target.setGain(gain);
      target.setTranspose(transpose);
      target.setIsChanceEnabled(chanceEnabled);
      target.setChance(chance);
      target.setIsOccurrenceEnabled(occurrenceEnabled);
      if (occurrence != null)
         target.setOccurrence(occurrence);
      target.setIsRecurrenceEnabled(recurrenceEnabled);
      target.setRecurrence(recurrenceLength, recurrenceMask);
      target.setIsRepeatEnabled(repeatEnabled);
      target.setRepeatCount(repeatCount);
      target.setRepeatCurve(repeatCurve);
      target.setRepeatVelocityEnd(repeatVelocityEnd);
      target.setRepeatVelocityCurve(repeatVelocityCurve);
      target.setIsMuted(muted);
   }
}

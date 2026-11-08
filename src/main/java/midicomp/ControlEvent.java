package midicomp;

/**
 * A MIDI control message in a take: control change, channel pressure or pitch bend.
 *
 * The extension API cannot write these into clips; they travel with takes and comps and reach
 * Bitwig through MIDI files (see :class:`MidiFile`).
 *
 * Args:
 *    x: position in grid steps.
 *    status: status byte, with the channel.
 *    data1: first data byte (controller number, pressure, or pitch bend LSB).
 *    data2: second data byte (value, or pitch bend MSB; 0 for channel pressure).
 */
record ControlEvent(int x, int status, int data1, int data2)
{
   /** Returns whether a status byte is a control message kept in takes. */
   static boolean isControl(final int status)
   {
      final int type = status & 0xF0;
      return type == 0xB0 || type == 0xD0 || type == 0xE0;
   }

   /** Identifies the controller: channel and type, plus the number for control changes. */
   int controller()
   {
      return (status & 0xF0) == 0xB0 ? status << 8 | data1 : status << 8 | 0xFF;
   }

   /** Returns this event at another position. */
   ControlEvent at(final int newX)
   {
      return new ControlEvent(newX, status, data1, data2);
   }

   /**
    * Returns this controller at rest, at the same position: control changes and channel pressure
    * at 0, pitch bend at the center.
    */
   ControlEvent resting()
   {
      return (status & 0xF0) == 0xE0 ? new ControlEvent(x, status, 0, 64)
         : new ControlEvent(x, status, (status & 0xF0) == 0xB0 ? data1 : 0, 0);
   }

   /** Returns whether this and another message set the same controller to the same value. */
   boolean sameValue(final ControlEvent other)
   {
      return status == other.status && data1 == other.data1 && data2 == other.data2;
   }
}

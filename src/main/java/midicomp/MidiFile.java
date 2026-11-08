package midicomp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes a comp as a standard MIDI file (format 0), the way to get control messages into a
 * Bitwig clip: the extension API cannot write them, but Bitwig imports them from MIDI files.
 *
 * At the same position, note-offs come first, then control messages, then note-ons, so a hi-hat
 * pedal change applies to the hit at the same position. The file's resolution is the comp's grid
 * (one tick per step), so imported notes land exactly on the steps the editor writes.
 */
final class MidiFile
{
   /** Largest resolution a MIDI file header can express. */
   private static final int MAX_PPQ = 0x7FFF;

   private record Event(long tick, int order, byte[] data)
   {
   }

   private MidiFile()
   {
   }

   /**
    * Returns the file contents.
    *
    * Args:
    *    notes: the comp's notes, in grid coordinates; muted notes are left out.
    *    controls: the comp's control messages.
    *    stepSize: grid step length in beats.
    *    lengthSteps: length of the comp; the file ends there or after its last event.
    */
   static byte[] write(
      final List<NoteData> notes,
      final List<ControlEvent> controls,
      final double stepSize,
      final int lengthSteps)
   {
      final int ppq = (int) Math.max(96, Math.min(MAX_PPQ, Math.round(1.0 / stepSize)));
      final List<Event> events = new ArrayList<>();
      for (final NoteData n : notes)
      {
         if (n.muted())
            continue;
         final long on = ticks(n.x() * stepSize, ppq);
         final long off = Math.max(on + 1, ticks(n.x() * stepSize + n.duration(), ppq));
         final int channel = n.channel() & 0x0F;
         events.add(new Event(on, 2, new byte[] { (byte) (0x90 | channel), (byte) n.key(),
            (byte) Math.max(1, Math.min(127, Math.round(n.velocity() * 127))) }));
         events.add(new Event(off, 0, new byte[] { (byte) (0x80 | channel), (byte) n.key(),
            (byte) Math.max(0, Math.min(127, Math.round(n.releaseVelocity() * 127))) }));
      }
      for (final ControlEvent c : controls)
      {
         final boolean twoBytes = (c.status() & 0xF0) == 0xD0;
         events.add(new Event(ticks(c.x() * stepSize, ppq), 1, twoBytes
            ? new byte[] { (byte) c.status(), (byte) c.data1() }
            : new byte[] { (byte) c.status(), (byte) c.data1(), (byte) c.data2() }));
      }
      events.sort(Comparator.comparingLong(Event::tick).thenComparingInt(Event::order));

      // No tempo event: positions are in beats, and Bitwig asks whether to take over a file's
      // tempo on every import.
      final ByteArrayOutputStream track = new ByteArrayOutputStream();
      long last = 0;
      for (final Event e : events)
      {
         writeVarLen(track, e.tick() - last);
         track.writeBytes(e.data());
         last = e.tick();
      }
      writeVarLen(track, Math.max(0, ticks(lengthSteps * stepSize, ppq) - last));
      track.writeBytes(new byte[] { (byte) 0xFF, 0x2F, 0x00 });

      final byte[] body = track.toByteArray();
      final ByteArrayOutputStream file = new ByteArrayOutputStream();
      file.writeBytes(new byte[] { 'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1,
         (byte) (ppq >> 8), (byte) ppq, 'M', 'T', 'r', 'k' });
      file.writeBytes(new byte[] { (byte) (body.length >> 24), (byte) (body.length >> 16),
         (byte) (body.length >> 8), (byte) body.length });
      file.writeBytes(body);
      return file.toByteArray();
   }

   private static long ticks(final double beats, final int ppq)
   {
      return Math.max(0, Math.round(beats * ppq));
   }

   private static void writeVarLen(final ByteArrayOutputStream out, final long value)
   {
      long buffer = value & 0x7F;
      long rest = value >> 7;
      while (rest > 0)
      {
         buffer <<= 8;
         buffer |= (rest & 0x7F) | 0x80;
         rest >>= 7;
      }
      while (true)
      {
         out.write((int) (buffer & 0xFF));
         if ((buffer & 0x80) == 0)
            break;
         buffer >>= 8;
      }
   }
}

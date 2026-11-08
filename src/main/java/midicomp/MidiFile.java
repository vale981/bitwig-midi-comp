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
 * pedal change applies to the hit at the same position.
 */
final class MidiFile
{
   /** Ticks per quarter note. */
   static final int PPQ = 960;

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
    *    tempo: tempo in beats per minute.
    *    lengthSteps: length of the comp; the file ends there or after its last event.
    */
   static byte[] write(
      final List<NoteData> notes,
      final List<ControlEvent> controls,
      final double stepSize,
      final double tempo,
      final int lengthSteps)
   {
      final List<Event> events = new ArrayList<>();
      for (final NoteData n : notes)
      {
         if (n.muted())
            continue;
         final long on = ticks(n.x() * stepSize);
         final long off = Math.max(on + 1, ticks(n.x() * stepSize + n.duration()));
         final int channel = n.channel() & 0x0F;
         events.add(new Event(on, 2, new byte[] { (byte) (0x90 | channel), (byte) n.key(),
            (byte) Math.max(1, Math.min(127, Math.round(n.velocity() * 127))) }));
         events.add(new Event(off, 0, new byte[] { (byte) (0x80 | channel), (byte) n.key(),
            (byte) Math.max(0, Math.min(127, Math.round(n.releaseVelocity() * 127))) }));
      }
      for (final ControlEvent c : controls)
      {
         final boolean twoBytes = (c.status() & 0xF0) == 0xD0;
         events.add(new Event(ticks(c.x() * stepSize), 1, twoBytes
            ? new byte[] { (byte) c.status(), (byte) c.data1() }
            : new byte[] { (byte) c.status(), (byte) c.data1(), (byte) c.data2() }));
      }
      events.sort(Comparator.comparingLong(Event::tick).thenComparingInt(Event::order));

      final ByteArrayOutputStream track = new ByteArrayOutputStream();
      final int micros = (int) Math.round(60_000_000.0 / Math.max(1.0, tempo));
      writeVarLen(track, 0);
      track.writeBytes(new byte[] { (byte) 0xFF, 0x51, 0x03, (byte) (micros >> 16),
         (byte) (micros >> 8), (byte) micros });
      long last = 0;
      for (final Event e : events)
      {
         writeVarLen(track, e.tick() - last);
         track.writeBytes(e.data());
         last = e.tick();
      }
      writeVarLen(track, Math.max(0, ticks(lengthSteps * stepSize) - last));
      track.writeBytes(new byte[] { (byte) 0xFF, 0x2F, 0x00 });

      final byte[] body = track.toByteArray();
      final ByteArrayOutputStream file = new ByteArrayOutputStream();
      file.writeBytes(new byte[] { 'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1,
         (byte) (PPQ >> 8), (byte) PPQ, 'M', 'T', 'r', 'k' });
      file.writeBytes(new byte[] { (byte) (body.length >> 24), (byte) (body.length >> 16),
         (byte) (body.length >> 8), (byte) body.length });
      file.writeBytes(body);
      return file.toByteArray();
   }

   private static long ticks(final double beats)
   {
      return Math.max(0, Math.round(beats * PPQ));
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

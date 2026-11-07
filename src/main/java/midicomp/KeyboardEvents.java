package midicomp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MIDI note messages sent by the companion, which reads the keyboard with timestamps.
 *
 * Timestamps are on the companion's monotonic clock, normally the clock behind
 * ``System.nanoTime()``. Each batch also carries the companion's time of sending; the smallest
 * difference to the time of arrival (the batch with the least transport delay) gives the offset
 * between the clocks, which is zero up to the delay when they are the same. Notes are rebuilt
 * from on/off pairs and placed in the loop with a :class:`TransportTimeline`.
 */
final class KeyboardEvents
{
   /** Messages older than this are dropped; recordings are much shorter. */
   private static final long KEEP_NANOS = 15L * 60 * 1_000_000_000L;

   /**
    * A note played on the keyboard during a recording.
    *
    * Args:
    *    channel: MIDI channel, 0 to 15.
    *    key: MIDI key.
    *    pass: loop pass it started in.
    *    loopBeat: start within the loop in beats.
    *    durationBeats: length in beats.
    *    velocity: note-on velocity, 0-1.
    *    releaseVelocity: note-off velocity, 0-1.
    */
   record PlayedNote(int channel, int key, int pass, double loopBeat, double durationBeats,
      double velocity, double releaseVelocity)
   {
   }

   private record Message(long time, int status, int data1, int data2)
   {
   }

   /** Clock offsets are estimated from the batches of this period. */
   private static final long OFFSET_WINDOW_NANOS = 60L * 1_000_000_000L;

   private record Arrival(long time, long offset)
   {
   }

   private final List<Message> messages = new ArrayList<>();
   private final ArrayDeque<Arrival> arrivals = new ArrayDeque<>();

   /**
    * Records a batch's arrival, for the clock offset.
    *
    * Args:
    *    arrived: ``System.nanoTime()`` at arrival.
    *    sent: companion time of sending, in nanoseconds.
    */
   void syncClock(final long arrived, final long sent)
   {
      arrivals.addLast(new Arrival(arrived, arrived - sent));
      while (arrivals.size() > 1 && arrivals.peekFirst().time() < arrived - OFFSET_WINDOW_NANOS)
         arrivals.removeFirst();
   }

   /** Returns the value added to companion times to get ``System.nanoTime()`` values. */
   long clockOffset()
   {
      long offset = Long.MAX_VALUE;
      for (final Arrival a : arrivals)
         offset = Math.min(offset, a.offset());
      return arrivals.isEmpty() ? 0 : offset;
   }

   /**
    * Adds a message.
    *
    * Args:
    *    time: timestamp in nanoseconds.
    *    status: MIDI status byte.
    *    data1: first data byte.
    *    data2: second data byte.
    */
   void add(final long time, final int status, final int data1, final int data2)
   {
      messages.add(new Message(time, status, data1, data2));
      final long cutoff = time - KEEP_NANOS;
      while (!messages.isEmpty() && messages.get(0).time() < cutoff)
         messages.remove(0);
   }

   int size()
   {
      return messages.size();
   }

   /**
    * Rebuilds the notes that started during a recording and places them in the loop.
    *
    * Args:
    *    from: recording start (nanoseconds).
    *    to: recording end (nanoseconds); notes still held then end there.
    *    timeline: the transport position during the recording.
    *    loopStart: loop start in beats.
    *    loopLength: loop length in beats.
    *    tempo: tempo in beats per minute.
    *
    * Returns:
    *    The notes in the order they were played; notes before the loop start are left out.
    */
   List<PlayedNote> notes(final long from, final long to, final TransportTimeline timeline,
      final double loopStart, final double loopLength, final double tempo)
   {
      final double beatsPerNano = tempo / 60e9;
      final long offset = clockOffset();
      final List<Message> sorted = new ArrayList<>(messages.size());
      for (final Message m : messages)
         sorted.add(new Message(m.time() + offset, m.status(), m.data1(), m.data2()));
      sorted.sort((a, b) -> Long.compare(a.time(), b.time()));

      record Open(long time, int velocity)
      {
      }
      final Map<Integer, Open> open = new HashMap<>();
      final List<long[]> finished = new ArrayList<>(); // {start, end, channel, key, vel, rel}
      for (final Message m : sorted)
      {
         if (m.time() > to)
            break;
         final int type = m.status() & 0xF0;
         final int channel = m.status() & 0x0F;
         final int cell = channel << 7 | (m.data1() & 0x7F);
         final boolean on = type == 0x90 && m.data2() > 0;
         final boolean off = type == 0x80 || (type == 0x90 && m.data2() == 0);
         if (on && m.time() >= from)
         {
            open.put(cell, new Open(m.time(), m.data2()));
         }
         else if (off)
         {
            final Open started = open.remove(cell);
            if (started != null)
               finished.add(new long[] { started.time(), m.time(), channel, m.data1(),
                  started.velocity(), type == 0x80 ? m.data2() : 64 });
         }
      }
      for (final Map.Entry<Integer, Open> e : open.entrySet())
         finished.add(new long[] { e.getValue().time(), to, e.getKey() >> 7, e.getKey() & 0x7F,
            e.getValue().velocity(), 64 });
      finished.sort((a, b) -> Long.compare(a[0], b[0]));

      final List<PlayedNote> notes = new ArrayList<>();
      for (final long[] f : finished)
      {
         final TransportTimeline.Placement at = timeline.place(f[0], loopStart, loopLength, tempo);
         if (at == null || at.loopBeat() < 0)
            continue;
         notes.add(new PlayedNote((int) f[2], (int) f[3], at.pass(), at.loopBeat(),
            Math.max(0, f[1] - f[0]) * beatsPerNano, f[4] / 127.0, f[5] / 127.0));
      }
      return notes;
   }
}

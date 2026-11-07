package midicomp;

import java.util.ArrayList;
import java.util.List;

/**
 * The transport position over time during a recording, for placing timestamped events.
 *
 * Position updates arrive at screen rate with some delay, so a single update says little about
 * the position at a given moment. Within a loop pass the position grows linearly at a rate set by
 * the tempo; each pass is therefore fitted as a line with that slope, whose offset is the average
 * over all of the pass's samples. A constant delay of the updates remains in the offset; it is
 * calibrated later against the notes Bitwig recorded.
 *
 * Times are ``System.nanoTime()`` values; positions are in beats.
 */
final class TransportTimeline
{
   /**
    * Where an instant falls in a loop recording.
    *
    * Args:
    *    pass: index of the loop pass, from 0.
    *    loopBeat: position within the loop in beats, from the loop start; negative before it.
    */
   record Placement(int pass, double loopBeat)
   {
   }

   private record Sample(long time, double position)
   {
   }

   /** A pass: from its first sample on, position = offset + slope * time. */
   private record Segment(long start, double offset)
   {
   }

   private final List<Sample> samples = new ArrayList<>();
   private long ignoreBefore;

   /**
    * Starts a new recording; samples before ``guardUntil`` are ignored, as they may still show
    * the playhead before it moved to the loop start.
    *
    * Args:
    *    guardUntil: ``System.nanoTime()`` before which samples are dropped.
    */
   void start(final long guardUntil)
   {
      samples.clear();
      ignoreBefore = guardUntil;
   }

   /**
    * Adds a position update.
    *
    * Args:
    *    time: ``System.nanoTime()`` of the update.
    *    position: transport position in beats.
    */
   void add(final long time, final double position)
   {
      if (time >= ignoreBefore)
         samples.add(new Sample(time, position));
   }

   int sampleCount()
   {
      return samples.size();
   }

   /**
    * Places an instant in the loop.
    *
    * Args:
    *    time: ``System.nanoTime()`` of the event.
    *    loopStart: loop start in beats.
    *    loopLength: loop length in beats.
    *    tempo: tempo in beats per minute.
    *
    * Returns:
    *    The placement, or ``null`` without samples.
    */
   Placement place(final long time, final double loopStart, final double loopLength,
      final double tempo)
   {
      final List<Segment> segments = segments(tempo);
      if (segments.isEmpty() || loopLength <= 0)
         return null;
      final double slope = tempo / 60e9;

      int index = 0;
      while (index + 1 < segments.size() && segments.get(index + 1).start() <= time)
         index++;
      double loopBeat = segments.get(index).offset() + slope * time - loopStart;
      int pass = index;
      // Between the pass's last update and the reported jump back: already the next pass.
      while (loopBeat >= loopLength)
      {
         loopBeat -= loopLength;
         pass++;
      }
      return new Placement(pass, loopBeat);
   }

   /** Splits the samples at backwards jumps and fits each run with the tempo's slope. */
   private List<Segment> segments(final double tempo)
   {
      final double slope = tempo / 60e9;
      final List<Segment> segments = new ArrayList<>();
      int first = 0;
      for (int i = 1; i <= samples.size(); i++)
      {
         final boolean jump = i < samples.size()
            && samples.get(i).position() < samples.get(i - 1).position() - LoopCapture.WRAP_BEATS;
         if (i < samples.size() && !jump)
            continue;
         double sum = 0;
         for (int j = first; j < i; j++)
            sum += samples.get(j).position() - slope * samples.get(j).time();
         segments.add(new Segment(samples.get(first).time(), sum / (i - first)));
         first = i;
      }
      return segments;
   }
}

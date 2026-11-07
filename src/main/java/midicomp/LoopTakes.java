package midicomp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles one take per loop pass from a continuous loop recording.
 *
 * Two sources describe the recording:
 *
 * - Bitwig's recorded notes: exact timing and every attribute, but without overdub Bitwig keeps
 *   only the most recent pass at each loop position, and it reveals the notes only after
 *   recording.
 * - The notes played on the keyboard (from the companion): every pass, with the pass each note
 *   belongs to, but timed by timestamps rather than by the audio engine.
 *
 * Recorded notes are matched to played notes per key, in order of loop position (in one dimension
 * the cheapest matching never crosses, so two passes playing a key at almost the same time are not
 * swapped). Matched recorded notes go to the pass of their played note, as they are. Played notes
 * without a recorded counterpart (overwritten by later passes) are rebuilt from the keyboard data,
 * with attributes Bitwig records but the keyboard does not send copied from a recorded note.
 *
 * Played positions are loop-relative while recorded positions are clip-local. The offset between
 * them is estimated from the recording itself and consists of the loop start in the clip plus a
 * constant delay of the played timing. The API does not expose the clip's position, so the loop
 * start is taken as the offset rounded to the nearest sixteenth note (loops start on the grid) and
 * the rest as the delay. Played notes are corrected by the delay before passes are assigned, so a
 * note played just after a loop wrap counts towards the new pass.
 */
final class LoopTakes
{
   /**
    * The assembled takes.
    *
    * Args:
    *    passes: the notes of each pass that has notes, in pass order.
    *    matched: recorded notes matched to a played note.
    *    rebuilt: played notes Bitwig had overwritten, rebuilt from the keyboard data.
    *    unmatched: recorded notes without a played note; added to the last pass.
    *    offsetBeats: clip-local beat of the loop start in played time.
    *    meanDeviationBeats: mean distance between matched recorded and played positions after
    *       removing the offset.
    *    maxDeviationBeats: the largest such distance.
    */
   record Result(List<List<NoteData>> passes, int matched, int rebuilt, int unmatched,
      double offsetBeats, double meanDeviationBeats, double maxDeviationBeats)
   {
   }

   private LoopTakes()
   {
   }

   /**
    * Assembles the takes.
    *
    * Args:
    *    recorded: the notes Bitwig recorded, in grid coordinates.
    *    played: the notes played during recording, in the order they were played.
    *    stepSize: grid step length in beats.
    *    loopBeats: loop length in beats.
    *    toleranceBeats: largest position difference for a match.
    *
    * Returns:
    *    The takes.
    */
   static Result assemble(
      final List<NoteData> recorded,
      final List<KeyboardEvents.PlayedNote> played,
      final double stepSize,
      final double loopBeats,
      final double toleranceBeats)
   {
      if (played.isEmpty() || loopBeats <= 0)
         return new Result(recorded.isEmpty() ? List.of() : List.of(List.copyOf(recorded)), 0, 0,
            recorded.size(), 0, 0, 0);

      int lastPass = 0;
      for (final KeyboardEvents.PlayedNote p : played)
         lastPass = Math.max(lastPass, p.pass());

      final Map<Integer, List<Integer>> playedByKey = new HashMap<>();
      for (int i = 0; i < played.size(); i++)
         playedByKey.computeIfAbsent(played.get(i).key(), k -> new ArrayList<>()).add(i);
      final Map<Integer, List<Integer>> recordedByKey = new HashMap<>();
      for (int r = 0; r < recorded.size(); r++)
         recordedByKey.computeIfAbsent(recorded.get(r).key(), k -> new ArrayList<>()).add(r);

      // 1. Rough offset (modulo the loop) and a first matching to refine it.
      final double rough = estimateOffset(recorded, played, playedByKey, stepSize, loopBeats);
      final int[] firstMatch = match(recorded, played, recordedByKey, playedByKey,
         p -> p.loopBeat(), rough, stepSize, loopBeats, toleranceBeats);
      final List<Double> differences = new ArrayList<>();
      for (int r = 0; r < recorded.size(); r++)
         if (firstMatch[r] >= 0)
            differences.add(signedDistance(
               recorded.get(r).x() * stepSize - played.get(firstMatch[r]).loopBeat(), rough,
               loopBeats));
      final double offset = unwrapOffset(rough + mean(differences), recorded, played, firstMatch,
         stepSize, loopBeats);

      // 2. Split the offset into the loop start in the clip (on the sixteenth-note grid) and the
      //    delay of the played timing; correct the played notes by the delay.
      final double loopStartInClip = Math.round(offset * 4) / 4.0;
      final double delay = offset - loopStartInClip;
      final List<KeyboardEvents.PlayedNote> corrected = new ArrayList<>(played.size());
      for (final KeyboardEvents.PlayedNote p : played)
      {
         final double beat = p.loopBeat() + delay;
         final int shift = (int) Math.floor(beat / loopBeats);
         corrected.add(new KeyboardEvents.PlayedNote(p.channel(), p.key(), p.pass() + shift,
            beat - shift * loopBeats, p.durationBeats(), p.velocity(), p.releaseVelocity()));
      }
      for (final KeyboardEvents.PlayedNote p : corrected)
         lastPass = Math.max(lastPass, p.pass());

      // 3. Final matching on corrected positions; the remaining differences are the deviation.
      final int[] partner = match(recorded, corrected, recordedByKey, playedByKey,
         p -> p.loopBeat(), loopStartInClip, stepSize, loopBeats, toleranceBeats);
      final boolean[] used = new boolean[played.size()];
      double deviationSum = 0;
      double deviationMax = 0;
      int matched = 0;
      for (int r = 0; r < recorded.size(); r++)
      {
         if (partner[r] < 0)
            continue;
         used[partner[r]] = true;
         matched++;
         final double deviation = Math.abs(signedDistance(recorded.get(r).x() * stepSize
            - loopStartInClip, corrected.get(partner[r]).loopBeat(), loopBeats));
         deviationSum += deviation;
         deviationMax = Math.max(deviationMax, deviation);
      }

      final NoteData template = recorded.isEmpty() ? null : recorded.get(0);
      final TreeMap<Integer, List<NoteData>> byPass = new TreeMap<>();
      int unmatched = 0;
      for (int r = 0; r < recorded.size(); r++)
      {
         final int pass = partner[r] >= 0 ? corrected.get(partner[r]).pass() : lastPass;
         if (partner[r] < 0)
            unmatched++;
         byPass.computeIfAbsent(pass, k -> new ArrayList<>()).add(recorded.get(r));
      }
      int rebuilt = 0;
      for (int p = 0; p < played.size(); p++)
      {
         if (used[p])
            continue;
         final NoteData note = rebuild(corrected.get(p), loopStartInClip, stepSize, template);
         if (note == null)
            continue;
         byPass.computeIfAbsent(corrected.get(p).pass(), k -> new ArrayList<>()).add(note);
         rebuilt++;
      }

      return new Result(new ArrayList<>(byPass.values()), matched, rebuilt, unmatched, offset,
         matched == 0 ? 0 : deviationSum / matched, deviationMax);
   }

   /**
    * Rebuilds a played note as a clip note. Attributes the keyboard does not send are taken from
    * ``template`` (a recorded note), or Bitwig's defaults without one.
    */
   static NoteData rebuild(
      final KeyboardEvents.PlayedNote p,
      final double offset,
      final double stepSize,
      final NoteData template)
   {
      final int x = (int) Math.round((p.loopBeat() + offset) / stepSize);
      if (x < 0)
         return null;
      final double duration = Math.max(stepSize, p.durationBeats());
      if (template == null)
         return new NoteData(p.channel(), x, p.key(), p.velocity(), p.releaseVelocity(), 0,
            duration, 0, 0, 0, 0.5, 0, false, 1, false, null, false, 1, 1, false, 1, 0, 1, 0,
            false, false);
      return new NoteData(p.channel(), x, p.key(), p.velocity(), p.releaseVelocity(),
         template.velocitySpread(), duration, template.pan(), template.timbre(),
         template.pressure(), template.gain(), template.transpose(), template.chanceEnabled(),
         template.chance(), template.occurrenceEnabled(), template.occurrence(),
         template.recurrenceEnabled(), template.recurrenceLength(), template.recurrenceMask(),
         template.repeatEnabled(), template.repeatCount(), template.repeatCurve(),
         template.repeatVelocityEnd(), template.repeatVelocityCurve(), template.muted(), false);
   }

   /**
    * Estimates the offset (modulo the loop length) as the most common difference between a
    * recorded and a played note of the same key: histogram with 1/32 beat bins, refined by
    * averaging the differences near the peak.
    */
   private static double estimateOffset(
      final List<NoteData> recorded,
      final List<KeyboardEvents.PlayedNote> played,
      final Map<Integer, List<Integer>> playedByKey,
      final double stepSize,
      final double loopBeats)
   {
      final double bin = 1.0 / 32;
      final long bins = Math.max(1, Math.round(loopBeats / bin));
      final Map<Long, Integer> histogram = new HashMap<>();
      final List<Double> diffs = new ArrayList<>();
      for (final NoteData n : recorded)
      {
         for (final int p : playedByKey.getOrDefault(n.key(), List.of()))
         {
            final double diff = wrap(n.x() * stepSize - played.get(p).loopBeat(), loopBeats);
            diffs.add(diff);
            histogram.merge(Math.round(diff / bin) % bins, 1, Integer::sum);
         }
      }
      if (histogram.isEmpty())
         return 0;
      final long peak = histogram.entrySet().stream()
         .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
      final double center = peak * bin;
      double sum = 0;
      int count = 0;
      for (final double d : diffs)
      {
         final double delta = signedDistance(d, center, loopBeats);
         if (Math.abs(delta) <= 2 * bin)
         {
            sum += delta;
            count++;
         }
      }
      return wrap(center + (count > 0 ? sum / count : 0), loopBeats);
   }

   /**
    * Picks the offset congruent to ``wrapped`` (modulo the loop) that is closest to the unwrapped
    * difference of the matched notes; the clip can be longer than the loop.
    */
   private static double unwrapOffset(
      final double wrapped,
      final List<NoteData> recorded,
      final List<KeyboardEvents.PlayedNote> played,
      final int[] partner,
      final double stepSize,
      final double loopBeats)
   {
      final List<Double> raw = new ArrayList<>();
      for (int r = 0; r < recorded.size(); r++)
         if (partner[r] >= 0)
            raw.add(recorded.get(r).x() * stepSize - played.get(partner[r]).loopBeat());
      if (raw.isEmpty())
         return wrap(wrapped, loopBeats);
      raw.sort(Double::compare);
      final double median = raw.get(raw.size() / 2);
      return wrapped + Math.round((median - wrapped) / loopBeats) * loopBeats;
   }

   /** Matches recorded to played notes per key in order of loop position (see :meth:`align`). */
   private static int[] match(
      final List<NoteData> recorded,
      final List<KeyboardEvents.PlayedNote> played,
      final Map<Integer, List<Integer>> recordedByKey,
      final Map<Integer, List<Integer>> playedByKey,
      final java.util.function.ToDoubleFunction<KeyboardEvents.PlayedNote> position,
      final double offset,
      final double stepSize,
      final double loopBeats,
      final double toleranceBeats)
   {
      final int[] partner = new int[recorded.size()];
      Arrays.fill(partner, -1);
      for (final Map.Entry<Integer, List<Integer>> entry : recordedByKey.entrySet())
      {
         final List<Integer> rs = new ArrayList<>(entry.getValue());
         final List<Integer> ps = new ArrayList<>(playedByKey.getOrDefault(entry.getKey(), List.of()));
         rs.sort(Comparator.comparingDouble(
            r -> wrap(recorded.get(r).x() * stepSize - offset, loopBeats)));
         ps.sort(Comparator.comparingDouble(q -> position.applyAsDouble(played.get(q))));
         final double[] rBeat = rs.stream()
            .mapToDouble(r -> wrap(recorded.get(r).x() * stepSize - offset, loopBeats)).toArray();
         final double[] pBeat =
            ps.stream().mapToDouble(q -> position.applyAsDouble(played.get(q))).toArray();
         final int[] m = align(rBeat, pBeat, toleranceBeats, loopBeats);
         for (int i = 0; i < rs.size(); i++)
            if (m[i] >= 0)
               partner[rs.get(i)] = ps.get(m[i]);
      }
      return partner;
   }

   private static double mean(final List<Double> values)
   {
      double sum = 0;
      for (final double v : values)
         sum += v;
      return values.isEmpty() ? 0 : sum / values.size();
   }

   /**
    * Matches two sorted position lists without crossings, minimising the total distance; a
    * recorded note without a partner within ``tolerance`` costs ``tolerance``.
    *
    * Args:
    *    recorded: recorded positions, ascending.
    *    played: played positions, ascending.
    *    tolerance: largest distance of a match.
    *    loopBeats: loop length, for distances across the loop end.
    *
    * Returns:
    *    For each recorded position the index of its played partner, or ``-1``.
    */
   static int[] align(
      final double[] recorded,
      final double[] played,
      final double tolerance,
      final double loopBeats)
   {
      final int n = recorded.length;
      final int m = played.length;
      final double[][] cost = new double[n + 1][m + 1];
      final byte[][] move = new byte[n + 1][m + 1]; // 0 skip recorded, 1 skip played, 2 match
      for (int i = 1; i <= n; i++)
         cost[i][0] = cost[i - 1][0] + tolerance;
      for (int j = 1; j <= m; j++)
         move[0][j] = 1;
      for (int i = 1; i <= n; i++)
      {
         for (int j = 1; j <= m; j++)
         {
            double best = cost[i - 1][j] + tolerance;
            byte how = 0;
            if (cost[i][j - 1] < best)
            {
               best = cost[i][j - 1];
               how = 1;
            }
            final double d = Math.abs(signedDistance(recorded[i - 1], played[j - 1], loopBeats));
            if (d <= tolerance && cost[i - 1][j - 1] + d < best)
            {
               best = cost[i - 1][j - 1] + d;
               how = 2;
            }
            cost[i][j] = best;
            move[i][j] = how;
         }
      }
      final int[] match = new int[n];
      Arrays.fill(match, -1);
      int i = n;
      int j = m;
      while (i > 0)
      {
         if (j == 0 || move[i][j] == 0)
            i--;
         else if (move[i][j] == 1)
            j--;
         else
         {
            match[i - 1] = j - 1;
            i--;
            j--;
         }
      }
      return match;
   }

   /** Returns ``value`` wrapped into ``[0, length)``. */
   static double wrap(final double value, final double length)
   {
      final double r = value % length;
      return r < 0 ? r + length : r;
   }

   private static double signedDistance(final double a, final double b, final double length)
   {
      double d = wrap(a - b, length);
      if (d > length / 2)
         d -= length;
      return d;
   }
}

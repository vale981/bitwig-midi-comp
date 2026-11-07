package midicomp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sorts the notes of a continuous loop recording into passes.
 *
 * Bitwig records all passes into one clip and only reveals the notes when recording stops, so the
 * notes themselves do not say which pass they belong to. While recording, the extension notes
 * every key played on its MIDI input together with the pass and the position in the loop. Each
 * recorded note is then matched to a played note of the same key at the nearest loop position,
 * and takes that note's pass.
 *
 * Recorded positions are clip-local while played positions are loop-relative; the offset between
 * the two is not exposed by the API and is estimated as the most common difference between
 * recorded and played notes of the same key.
 */
final class PassSorter
{
   /**
    * A note played on the MIDI input during recording.
    *
    * Args:
    *    key: MIDI key.
    *    pass: index of the pass it was played in.
    *    loopBeat: position in the loop in beats, from the loop start.
    */
   record Played(int key, int pass, double loopBeat)
   {
   }

   /**
    * The sorted recording.
    *
    * Args:
    *    passes: the notes of each pass that has notes, in pass order.
    *    matched: recorded notes matched to a played note.
    *    unmatched: recorded notes without a match; they were added to the last pass.
    *    offsetBeats: estimated loop start in clip-local beats.
    */
   record Result(List<List<NoteData>> passes, int matched, int unmatched, double offsetBeats)
   {
   }

   private PassSorter()
   {
   }

   /**
    * Sorts recorded notes into passes.
    *
    * Args:
    *    recorded: the notes Bitwig recorded, in grid coordinates.
    *    played: the notes played during recording.
    *    stepSize: grid step length in beats.
    *    loopBeats: loop length in beats.
    *    toleranceBeats: largest position difference for a match.
    *
    * Returns:
    *    The notes grouped by pass.
    */
   static Result sort(
      final List<NoteData> recorded,
      final List<Played> played,
      final double stepSize,
      final double loopBeats,
      final double toleranceBeats)
   {
      if (recorded.isEmpty())
         return new Result(List.of(), 0, 0, 0);
      int lastPass = 0;
      for (final Played p : played)
         lastPass = Math.max(lastPass, p.pass());
      if (played.isEmpty() || loopBeats <= 0)
         return new Result(List.of(List.copyOf(recorded)), 0, recorded.size(), 0);

      final Map<Integer, List<Integer>> playedByKey = new HashMap<>();
      for (int i = 0; i < played.size(); i++)
         playedByKey.computeIfAbsent(played.get(i).key(), k -> new ArrayList<>()).add(i);

      final double offset = estimateOffset(recorded, played, playedByKey, stepSize, loopBeats);

      // Match per key in order of loop position: in one dimension the cheapest matching never
      // crosses, so two passes playing a key at almost the same time are not swapped.
      final Map<Integer, List<Integer>> recordedByKey = new HashMap<>();
      for (int r = 0; r < recorded.size(); r++)
         recordedByKey.computeIfAbsent(recorded.get(r).key(), k -> new ArrayList<>()).add(r);

      final int[] passOf = new int[recorded.size()];
      java.util.Arrays.fill(passOf, -1);
      int matched = 0;
      for (final Map.Entry<Integer, List<Integer>> entry : recordedByKey.entrySet())
      {
         final List<Integer> rs = new ArrayList<>(entry.getValue());
         final List<Integer> ps = new ArrayList<>(playedByKey.getOrDefault(entry.getKey(), List.of()));
         final double[] rBeat = new double[rs.size()];
         rs.sort(Comparator.comparingDouble(
            r -> wrap(recorded.get(r).x() * stepSize - offset, loopBeats)));
         for (int i = 0; i < rs.size(); i++)
            rBeat[i] = wrap(recorded.get(rs.get(i)).x() * stepSize - offset, loopBeats);
         ps.sort(Comparator.comparingDouble(q -> played.get(q).loopBeat()));
         final int[] match = align(rBeat, ps.stream().mapToDouble(q -> played.get(q).loopBeat())
            .toArray(), toleranceBeats, loopBeats);
         for (int i = 0; i < rs.size(); i++)
         {
            if (match[i] < 0)
               continue;
            passOf[rs.get(i)] = played.get(ps.get(match[i])).pass();
            matched++;
         }
      }

      final TreeMap<Integer, List<NoteData>> byPass = new TreeMap<>();
      for (int r = 0; r < recorded.size(); r++)
      {
         final int pass = passOf[r] >= 0 ? passOf[r] : lastPass;
         byPass.computeIfAbsent(pass, k -> new ArrayList<>()).add(recorded.get(r));
      }
      return new Result(new ArrayList<>(byPass.values()), matched, recorded.size() - matched,
         offset);
   }

   /**
    * Estimates the clip-local position of the loop start as the most common difference between a
    * recorded note and a played note of the same key (histogram with 1/32 beat bins, refined by
    * averaging the differences near the peak).
    */
   private static double estimateOffset(
      final List<NoteData> recorded,
      final List<Played> played,
      final Map<Integer, List<Integer>> playedByKey,
      final double stepSize,
      final double loopBeats)
   {
      final double bin = 1.0 / 32;
      final Map<Long, Integer> histogram = new HashMap<>();
      final List<Double> diffs = new ArrayList<>();
      for (final NoteData n : recorded)
      {
         for (final int p : playedByKey.getOrDefault(n.key(), List.of()))
         {
            final double diff = wrap(n.x() * stepSize - played.get(p).loopBeat(), loopBeats);
            diffs.add(diff);
            histogram.merge(Math.round(diff / bin) % Math.max(1, Math.round(loopBeats / bin)), 1,
               Integer::sum);
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
      {
         cost[i][0] = cost[i - 1][0] + tolerance;
         move[i][0] = 0;
      }
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
            final double d = circularDistance(recorded[i - 1], played[j - 1], loopBeats);
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
      java.util.Arrays.fill(match, -1);
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

   private static double circularDistance(final double a, final double b, final double length)
   {
      return Math.abs(signedDistance(a, b, length));
   }
}

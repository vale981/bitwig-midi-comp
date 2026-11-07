package midicomp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bitwig.extension.controller.api.NoteOccurrence;

/**
 * Full-fidelity serialization of a comp (takes with every note attribute, and segments), used by
 * companions to save comps and restore them later.
 *
 * Positions are stored in grid steps together with the step size, so a comp saved at one timing
 * resolution can be restored at another.
 */
final class CompExport
{
   static final int FORMAT = 1;

   /**
    * A comp read back from JSON, in the current grid.
    *
    * Args:
    *    takes: the takes, in order; their ``id`` fields are positions in this list.
    *    segments: ranges as ``{from, to, takeIndex}``.
    */
   record Imported(List<Take> takes, List<int[]> segments)
   {
   }

   private CompExport()
   {
   }

   /**
    * Serializes the model.
    *
    * Args:
    *    model: takes and segments.
    *    clip: the session clip.
    *    project: project name.
    *    stepSize: grid step length in beats.
    *
    * Returns:
    *    The JSON text.
    */
   static String write(
      final CompModel model,
      final ClipRef clip,
      final String project,
      final double stepSize)
   {
      final StringBuilder sb = new StringBuilder(8192);
      sb.append("{\"format\":").append(FORMAT)
         .append(",\"project\":").append(Json.quote(project))
         .append(",\"clip\":").append(Json.quote(clip.label()))
         .append(",\"source\":").append(Json.quote(clip.source().name().toLowerCase()))
         .append(",\"stepSize\":").append(Json.number(stepSize))
         .append(",\"fingerprint\":").append(Json.quote(Fingerprint.of(
            model.render(stepSize).stream().map(CompModel.CompNote::note).toList(), stepSize)))
         .append(",\"takes\":[");

      final List<Take> takes = model.takes();
      for (int i = 0; i < takes.size(); i++)
      {
         final Take t = takes.get(i);
         sb.append(i > 0 ? "," : "")
            .append("{\"name\":").append(Json.quote(t.name()))
            .append(",\"source\":").append(Json.quote(t.source().name().toLowerCase()))
            .append(",\"length\":").append(t.length())
            .append(",\"notes\":[");
         for (int j = 0; j < t.notes().size(); j++)
            appendNote(sb.append(j > 0 ? "," : ""), t.notes().get(j));
         sb.append("]}");
      }

      sb.append("],\"segments\":[");
      final List<CompModel.Segment> segments = model.segments();
      for (int i = 0; i < segments.size(); i++)
      {
         final CompModel.Segment s = segments.get(i);
         int index = 0;
         while (index < takes.size() && takes.get(index).id() != s.takeId())
            index++;
         sb.append(i > 0 ? "," : "").append('[').append(s.from()).append(',').append(s.to())
            .append(',').append(index).append(']');
      }
      return sb.append("]}").toString();
   }

   /**
    * Parses a comp written by :meth:`write` and converts it to the current grid.
    *
    * Args:
    *    json: the document.
    *    stepSize: current grid step length in beats.
    *
    * Returns:
    *    The comp.
    *
    * Raises:
    *    IllegalArgumentException: if the document is not a comp of a known format.
    */
   @SuppressWarnings("unchecked")
   static Imported read(final String json, final double stepSize)
   {
      final Object parsed = MiniJson.parse(json);
      if (!(parsed instanceof Map<?, ?>))
         throw new IllegalArgumentException("Expected a JSON object");
      final Map<String, Object> root = (Map<String, Object>) parsed;
      if ((int) MiniJson.number(root, "format", -1) != FORMAT)
         throw new IllegalArgumentException("Unknown comp format");

      // Saved positions are in the saved grid; scale them to the current one.
      final double scale = MiniJson.number(root, "stepSize", stepSize) / stepSize;
      final List<Take> takes = new ArrayList<>();
      for (final Object o : MiniJson.array(root, "takes"))
      {
         final Map<String, Object> t = (Map<String, Object>) o;
         final List<NoteData> notes = new ArrayList<>();
         for (final Object n : MiniJson.array(t, "notes"))
            notes.add(readNote((Map<String, Object>) n, scale));
         takes.add(new Take(takes.size(), MiniJson.string(t, "name", "Take"),
            source(MiniJson.string(t, "source", "arranger")),
            scaled(MiniJson.number(t, "length", 0), scale), notes));
      }

      final List<int[]> segments = new ArrayList<>();
      for (final Object o : MiniJson.array(root, "segments"))
      {
         final List<Object> s = (List<Object>) o;
         final int index = (int) (double) (Double) s.get(2);
         if (index < 0 || index >= takes.size())
            throw new IllegalArgumentException("Segment refers to a missing take");
         segments.add(new int[] { scaled((Double) s.get(0), scale),
            scaled((Double) s.get(1), scale), index });
      }
      return new Imported(takes, segments);
   }

   private static int scaled(final double steps, final double scale)
   {
      return (int) Math.round(steps * scale);
   }

   private static Take.Source source(final String name)
   {
      return "launcher".equals(name) ? Take.Source.LAUNCHER : Take.Source.ARRANGER;
   }

   private static void appendNote(final StringBuilder sb, final NoteData n)
   {
      sb.append("{\"ch\":").append(n.channel())
         .append(",\"x\":").append(n.x())
         .append(",\"key\":").append(n.key())
         .append(",\"vel\":").append(Json.number(n.velocity()))
         .append(",\"rel\":").append(Json.number(n.releaseVelocity()))
         .append(",\"spread\":").append(Json.number(n.velocitySpread()))
         .append(",\"dur\":").append(Json.number(n.duration()))
         .append(",\"pan\":").append(Json.number(n.pan()))
         .append(",\"timbre\":").append(Json.number(n.timbre()))
         .append(",\"pressure\":").append(Json.number(n.pressure()))
         .append(",\"gain\":").append(Json.number(n.gain()))
         .append(",\"transpose\":").append(Json.number(n.transpose()))
         .append(",\"chanceOn\":").append(n.chanceEnabled())
         .append(",\"chance\":").append(Json.number(n.chance()))
         .append(",\"occurrenceOn\":").append(n.occurrenceEnabled())
         .append(",\"occurrence\":").append(
            Json.quote(n.occurrence() == null ? null : n.occurrence().name()))
         .append(",\"recurrenceOn\":").append(n.recurrenceEnabled())
         .append(",\"recurrenceLength\":").append(n.recurrenceLength())
         .append(",\"recurrenceMask\":").append(n.recurrenceMask())
         .append(",\"repeatOn\":").append(n.repeatEnabled())
         .append(",\"repeatCount\":").append(n.repeatCount())
         .append(",\"repeatCurve\":").append(Json.number(n.repeatCurve()))
         .append(",\"repeatVelEnd\":").append(Json.number(n.repeatVelocityEnd()))
         .append(",\"repeatVelCurve\":").append(Json.number(n.repeatVelocityCurve()))
         .append(",\"muted\":").append(n.muted())
         .append('}');
   }

   private static NoteData readNote(final Map<String, Object> m, final double scale)
   {
      NoteOccurrence occurrence = null;
      final String name = MiniJson.string(m, "occurrence", null);
      if (name != null)
      {
         try
         {
            occurrence = NoteOccurrence.valueOf(name);
         }
         catch (final IllegalArgumentException e)
         {
            // Written by a newer Bitwig; leave the occurrence condition unset.
         }
      }
      return new NoteData(
         (int) MiniJson.number(m, "ch", 0),
         scaled(MiniJson.number(m, "x", 0), scale),
         (int) MiniJson.number(m, "key", 60),
         MiniJson.number(m, "vel", 0.8),
         MiniJson.number(m, "rel", 0.5),
         MiniJson.number(m, "spread", 0),
         MiniJson.number(m, "dur", 0.25),
         MiniJson.number(m, "pan", 0),
         MiniJson.number(m, "timbre", 0),
         MiniJson.number(m, "pressure", 0),
         MiniJson.number(m, "gain", 0),
         MiniJson.number(m, "transpose", 0),
         MiniJson.bool(m, "chanceOn", false),
         MiniJson.number(m, "chance", 1),
         MiniJson.bool(m, "occurrenceOn", false),
         occurrence,
         MiniJson.bool(m, "recurrenceOn", false),
         (int) MiniJson.number(m, "recurrenceLength", 1),
         (int) MiniJson.number(m, "recurrenceMask", 1),
         MiniJson.bool(m, "repeatOn", false),
         (int) MiniJson.number(m, "repeatCount", 1),
         MiniJson.number(m, "repeatCurve", 0),
         MiniJson.number(m, "repeatVelEnd", 0),
         MiniJson.number(m, "repeatVelCurve", 0),
         MiniJson.bool(m, "muted", false),
         false);
   }
}

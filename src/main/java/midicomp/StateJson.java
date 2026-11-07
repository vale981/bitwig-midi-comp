package midicomp;

import java.util.List;

/** Serializes the state for ``GET /api/v1/state``. */
final class StateJson
{
   /**
    * Names of the selected clips; ``null`` when nothing is selected.
    *
    * Args:
    *    launcher: selected launcher clip.
    *    arranger: selected arranger clip.
    */
   record Selection(String launcher, String arranger)
   {
   }

   /**
    * Status of the comp session.
    *
    * Args:
    *    clip: the clip being comped.
    *    available: whether the clip is under its cursor (arranger clips must be selected).
    *    recording: whether Bitwig is recording into the clip.
    *    overdub: whether overdub is on; without it Bitwig records into a new clip.
    *    pending: whether comp changes wait to be written.
    *    truncated: whether the comp is longer than the clip.
    *    passes: loop passes recorded in this session.
    *    singlePass: whether recording stops at the end of the current pass.
    */
   record SessionStatus(
      ClipRef clip,
      boolean available,
      boolean recording,
      boolean overdub,
      boolean pending,
      boolean truncated,
      int passes,
      boolean singlePass)
   {
   }

   private StateJson()
   {
   }

   /**
    * Builds the state document.
    *
    * Args:
    *    version: state version, echoed by clients in ``/state?since=``.
    *    model: takes and segments.
    *    stepSize: grid step length in beats.
    *    barSteps: steps per bar.
    *    selection: selected clips.
    *    session: comp session status, or ``null`` without a session.
    *    canRevert: whether the session clip's original content can be restored.
    *    message: last status message.
    *    log: recent diagnostic events, oldest first.
    *
    * Returns:
    *    The JSON text.
    */
   static String build(
      final long version,
      final CompModel model,
      final double stepSize,
      final int barSteps,
      final Selection selection,
      final SessionStatus session,
      final boolean canRevert,
      final String message,
      final List<String> log)
   {
      final StringBuilder sb = new StringBuilder(4096);
      sb.append("{\"v\":").append(version)
         .append(",\"stepsPerBeat\":").append(Math.round(1.0 / stepSize))
         .append(",\"barSteps\":").append(barSteps)
         .append(",\"length\":").append(model.length())
         .append(",\"launcherSel\":").append(Json.quote(selection.launcher()))
         .append(",\"arrangerSel\":").append(Json.quote(selection.arranger()))
         .append(",\"canUndoEdit\":").append(model.canUndo())
         .append(",\"canRevert\":").append(canRevert)
         .append(",\"message\":").append(Json.quote(message));

      sb.append(",\"log\":[");
      for (int i = 0; i < log.size(); i++)
         sb.append(i > 0 ? "," : "").append(Json.quote(log.get(i)));
      sb.append(']');

      sb.append(",\"session\":");
      if (session == null)
         sb.append("null");
      else
         sb.append("{\"source\":").append(Json.quote(session.clip().source().name().toLowerCase()))
            .append(",\"label\":").append(Json.quote(session.clip().label()))
            .append(",\"available\":").append(session.available())
            .append(",\"recording\":").append(session.recording())
            .append(",\"overdub\":").append(session.overdub())
            .append(",\"pending\":").append(session.pending())
            .append(",\"truncated\":").append(session.truncated())
            .append(",\"passes\":").append(session.passes())
            .append(",\"singlePass\":").append(session.singlePass())
            .append('}');

      sb.append(",\"takes\":[");
      final List<Take> takes = model.takes();
      for (int i = 0; i < takes.size(); i++)
      {
         final Take t = takes.get(i);
         if (i > 0)
            sb.append(',');
         sb.append("{\"id\":").append(t.id())
            .append(",\"name\":").append(Json.quote(t.name()))
            .append(",\"source\":").append(Json.quote(t.source().name().toLowerCase()))
            .append(",\"length\":").append(t.length())
            .append(",\"notes\":[");
         appendNotes(sb, t.notes(), stepSize);
         sb.append("]}");
      }
      sb.append(']');

      sb.append(",\"segments\":[");
      final List<CompModel.Segment> segments = model.segments();
      for (int i = 0; i < segments.size(); i++)
      {
         final CompModel.Segment s = segments.get(i);
         if (i > 0)
            sb.append(',');
         sb.append('[').append(s.from()).append(',').append(s.to()).append(',')
            .append(s.takeId()).append(']');
      }
      sb.append(']');

      sb.append(",\"comp\":[");
      final List<CompModel.CompNote> comp = model.render(stepSize);
      for (int i = 0; i < comp.size(); i++)
      {
         if (i > 0)
            sb.append(',');
         appendNote(sb, comp.get(i).note(), stepSize, comp.get(i).takeId());
      }
      sb.append("]}");
      return sb.toString();
   }

   private static void appendNotes(
      final StringBuilder sb,
      final List<NoteData> notes,
      final double stepSize)
   {
      for (int i = 0; i < notes.size(); i++)
      {
         if (i > 0)
            sb.append(',');
         appendNote(sb, notes.get(i), stepSize, -1);
      }
   }

   /** Appends ``[x, key, durationSteps, velocity, muted, takeId]``. */
   private static void appendNote(
      final StringBuilder sb,
      final NoteData n,
      final double stepSize,
      final int takeId)
   {
      sb.append('[').append(n.x()).append(',').append(n.key()).append(',')
         .append(Json.number(Math.round(n.duration() / stepSize * 100.0) / 100.0)).append(',')
         .append(Json.number(Math.round(n.velocity() * 1000.0) / 1000.0)).append(',')
         .append(n.muted() ? 1 : 0).append(',').append(takeId).append(']');
   }
}

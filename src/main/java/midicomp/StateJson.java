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
    * Provisional notes of a loop pass being recorded.
    *
    * Args:
    *    pass: pass number, from 1.
    *    notes: the notes, in grid coordinates.
    */
   record PreviewPass(int pass, List<NoteData> notes)
   {
   }

   /**
    * Status of the comp session.
    *
    * Args:
    *    clip: the clip being comped.
    *    available: whether the clip is under its cursor (arranger clips must be selected).
    *    recording: whether Bitwig is recording into the clip.
    *    pending: whether comp changes wait to be written.
    *    truncated: whether the comp is longer than the clip.
    *    clipLength: the clip's length in steps, or ``-1`` while it is not under its cursor.
    *    passes: loop passes recorded in this session.
    *    singlePass: whether recording stops at the end of the current pass.
    *    recordingPass: the loop pass being recorded (1-based), or 0 when not recording.
    *    collecting: whether a recording waits for the clip to be selected to become takes.
    *    preview: provisional takes of the current recording, from the keyboard notes.
    *    fingerprint: fingerprint of the clip's content when the session started.
    *    fromArranger: whether the session runs in a launcher copy made by "Play in launcher".
    */
   record SessionStatus(
      ClipRef clip,
      boolean available,
      boolean recording,
      boolean pending,
      boolean truncated,
      int clipLength,
      int passes,
      boolean singlePass,
      int recordingPass,
      boolean collecting,
      List<PreviewPass> preview,
      String fingerprint,
      boolean fromArranger)
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
    *    project: name of the open project.
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
      final String project,
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
         .append(",\"project\":").append(Json.quote(project))
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
            .append(",\"pending\":").append(session.pending())
            .append(",\"truncated\":").append(session.truncated())
            .append(",\"clipLength\":").append(session.clipLength())
            .append(",\"passes\":").append(session.passes())
            .append(",\"singlePass\":").append(session.singlePass())
            .append(",\"recordingPass\":").append(session.recordingPass())
            .append(",\"collecting\":").append(session.collecting())
            .append(",\"track\":").append(Json.quote(session.clip().trackName()))
            .append(",\"fingerprint\":").append(Json.quote(session.fingerprint()))
            .append(",\"fromArranger\":").append(session.fromArranger())
            .append(",\"preview\":[");
      if (session != null)
      {
         for (int i = 0; i < session.preview().size(); i++)
         {
            final PreviewPass p = session.preview().get(i);
            sb.append(i > 0 ? "," : "").append("{\"pass\":").append(p.pass()).append(",\"notes\":[");
            appendNotes(sb, p.notes(), stepSize);
            sb.append("]}");
         }
         sb.append("]}");
      }

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
            .append(",\"controls\":").append(t.controls().size())
            .append(",\"cc\":");
         appendControls(sb, t.controls());
         sb
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

      sb.append(",\"compControls\":");
      appendControls(sb, model.renderControls());
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

   /** Appends control messages as ``[[x, status, data1, data2], ...]``. */
   private static void appendControls(final StringBuilder sb, final List<ControlEvent> controls)
   {
      sb.append('[');
      for (int i = 0; i < controls.size(); i++)
      {
         final ControlEvent c = controls.get(i);
         sb.append(i > 0 ? "," : "").append('[').append(c.x()).append(',').append(c.status())
            .append(',').append(c.data1()).append(',').append(c.data2()).append(']');
      }
      sb.append(']');
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

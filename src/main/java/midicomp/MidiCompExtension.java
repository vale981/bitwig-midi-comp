package midicomp;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.PinnableCursorClip;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.SettableStringValue;
import com.bitwig.extension.controller.api.Setting;
import com.bitwig.extension.controller.api.StringValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.Transport;

/**
 * MIDI comping for launcher and arranger clips, driven by a companion app over HTTP.
 *
 * The user comps one clip at a time (a *session*). Loop passes recorded into that clip become
 * takes, further takes can be captured from other clips, and the comp is written back into the
 * clip as it is edited. :class:`CompServer` serves the state and accepts commands; see ``API.md``.
 *
 * All Bitwig API calls happen on the controller thread. Commands arrive on HTTP threads and are
 * queued; :meth:`pump` runs them and publishes the resulting state.
 */
public class MidiCompExtension extends ControllerExtension
{
   private static final String SETTINGS_CATEGORY = "Companion app";

   /** Grid width in steps. Combined with the resolution this bounds the usable clip length. */
   private static final int GRID_WIDTH = 16384;
   private static final int GRID_HEIGHT = 128;
   private static final int HISTORY_DEPTH = 64;
   private static final int PUMP_INTERVAL_MS = 25;
   private static final int SERVER_PORT = 8765;
   private static final int SERVER_PORT_ATTEMPTS = 10;

   /** Coarsest and finest grid step in beats; sessions use the finest one their clip fits. */
   private static final double COARSEST_STEP = 1.0 / 64;
   private static final double FINEST_STEP = 1.0 / 4096;
   /** Played notes and recorded notes further apart than this are not matched. */
   private static final double MATCH_SECONDS = 0.08;

   /** Outcome of a command. */
   private record Result(boolean ok, String message)
   {
      static Result ok(final String message)
      {
         return new Result(true, message);
      }

      static Result fail(final String message)
      {
         return new Result(false, message);
      }
   }

   /**
    * An undoable clip write.
    *
    * Args:
    *    target: the cursor the write went through.
    *    op: the write.
    *    ref: the clip written to; undo requires the cursor to point at it again.
    */
   private record HistoryEntry(EditableClip target, CompOperation op, ClipRef ref)
   {
   }

   /** An API command waiting for the controller thread. */
   private record Command(
      String op,
      Map<String, String> params,
      String body,
      CompletableFuture<String> reply)
   {
   }

   private ControllerHost host;
   private Transport transport;
   private StringValue project;

   /** Follows the selected launcher clip. */
   private CursorTrack launcherTrack;
   private PinnableCursorClip launcherCursor;
   private EditableClip launcherSel;

   /** Pinned to a linked launcher clip, so that syncing does not depend on the selection. */
   private CursorTrack targetTrack;
   private PinnableCursorClip targetCursor;
   private EditableClip target;

   /** Follows the selected arranger clip; the API cannot pin it. */
   private EditableClip arranger;
   private Track arrangerTrack;

   private final CompModel model = new CompModel();
   private final LiveSync live = new LiveSync();
   private final LoopCapture capture = new LoopCapture(this::log);
   /** Recent capture and session events, for diagnostics in the companion. */
   private final Deque<String> eventLog = new ArrayDeque<>();
   private static final int EVENT_LOG_SIZE = 40;
   private final Deque<HistoryEntry> history = new ArrayDeque<>();
   private double stepSize = 1.0 / 128;
   private boolean wasRecording;
   /**
    * A recording was started from the companion: the comp clip was emptied and the sync holds
    * until the recording has ended and its passes are collected.
    */
   private boolean recordingRequested;
   private long recordingRequestedAt;
   private boolean recordingSeen;
   /** A session waiting for its clip to be re-read at a new grid resolution. */
   private String pendingStart;
   private long pendingStartAt;
   /** Notes played on the MIDI input during the current recording. */
   private final List<PassSorter.Played> played = new ArrayList<>();
   /** Notes the session clip's track reported during the current recording. */
   private final List<PassSorter.Played> trackPlayed = new ArrayList<>();
   /** Pinned to the session clip's track, whose notes time the passes without a MIDI input. */
   private CursorTrack sessionTrack;
   private int playedWraps;
   private double lastPosition = Double.NaN;
   private long lastPositionAt;
   private boolean emptyCheckLogged;

   private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
   private CompServer server;
   private long stateVersion;
   private boolean stateDirty = true;
   private String lastMessage = "";
   private volatile boolean running;

   private SettableStringValue apiStatus;

   protected MidiCompExtension(
      final MidiCompExtensionDefinition definition,
      final ControllerHost host)
   {
      super(definition, host);
   }

   @Override
   public void init()
   {
      host = getHost();

      project = host.createApplication().projectName();
      project.addValueObserver(v -> markDirty());
      transport = host.createTransport();
      transport.timeSignature().numerator().addValueObserver(v -> markDirty());
      transport.timeSignature().denominator().addValueObserver(v -> markDirty());
      transport.playPosition().markInterested();
      transport.isPlaying().addValueObserver(v -> markDirty());
      transport.isArrangerRecordEnabled().addValueObserver(v -> markDirty());
      transport.isArrangerLoopEnabled().markInterested();
      transport.arrangerLoopStart().markInterested();
      transport.playStartPosition().markInterested();

      initLauncherCursors();
      initArrangerCursor();

      apiStatus = host.getDocumentState().getStringSetting("API", SETTINGS_CATEGORY, 80,
         "not running");
      ((Setting) apiStatus).disable();

      // The keyboard on the MIDI input is forwarded to tracks, and its notes are watched while
      // recording to sort loop passes (see PassSorter).
      final MidiIn midiIn = host.getMidiInPort(0);
      final NoteInput keyboard = midiIn.createNoteInput("Keyboard", "??????");
      keyboard.setShouldConsumeEvents(false);
      midiIn.setMidiCallback(this::onMidi);
      transport.tempo().value().markInterested();
      transport.arrangerLoopDuration().markInterested();
      transport.playPosition().addValueObserver(this::onPosition);

      sessionTrack = host.createCursorTrack("midicomp-session", "Session track", 0, 0, true);
      sessionTrack.isPinned().markInterested();
      sessionTrack.name().markInterested();
      observeTrackNotes(sessionTrack);

      startServer();
      running = true;
      host.scheduleTask(this::pump, PUMP_INTERVAL_MS);
      host.println("MIDI Comp initialized");
   }

   @Override
   public void exit()
   {
      running = false;
      if (server != null)
      {
         EndpointFile.delete(server.token());
         server.stop();
      }
      Command c;
      while ((c = commands.poll()) != null)
         c.reply().complete(Json.reply(false, "Extension stopped"));
   }

   @Override
   public void flush()
   {
   }

   // ---------------------------------------------------------------------------------------------
   // Setup

   private void initLauncherCursors()
   {
      launcherTrack = host.createCursorTrack("midicomp-take", "Take", 0, 0, true);
      launcherCursor = launcherTrack.createLauncherCursorClip(
         "midicomp-take-clip", "Take clip", GRID_WIDTH, GRID_HEIGHT);
      targetTrack = host.createCursorTrack("midicomp-target", "Comp target", 0, 0, true);
      targetCursor = targetTrack.createLauncherCursorClip(
         "midicomp-target-clip", "Comp target clip", GRID_WIDTH, GRID_HEIGHT);

      launcherSel = new EditableClip(launcherCursor, stepSize);
      target = new EditableClip(targetCursor, stepSize);

      // Identity changes make the mirrors untrustworthy until the new notes have arrived.
      launcherTrack.name().addValueObserver(v -> identityChanged(launcherSel));
      launcherTrack.position().addValueObserver(v -> identityChanged(launcherSel));
      launcherCursor.exists().addValueObserver(v -> identityChanged(launcherSel));
      launcherCursor.clipLauncherSlot().name().addValueObserver(v -> markDirty());
      launcherCursor.clipLauncherSlot().sceneIndex()
         .addValueObserver(v -> identityChanged(launcherSel));
      launcherCursor.playingStep().markInterested();

      targetTrack.name().addValueObserver(v -> identityChanged(target));
      targetTrack.position().addValueObserver(v -> identityChanged(target));
      targetTrack.isPinned().markInterested();
      targetCursor.isPinned().addValueObserver(v -> identityChanged(target));
      targetCursor.exists().addValueObserver(v -> identityChanged(target));
      targetCursor.clipLauncherSlot().name().addValueObserver(v -> markDirty());
      targetCursor.clipLauncherSlot().sceneIndex().addValueObserver(v -> identityChanged(target));
      targetCursor.clipLauncherSlot().isRecording().addValueObserver(v -> markDirty());
   }

   private void initArrangerCursor()
   {
      arranger = new EditableClip(host.createArrangerCursorClip(GRID_WIDTH, GRID_HEIGHT), stepSize);
      arrangerTrack = arranger.clip().getTrack();
      arrangerTrack.name().addValueObserver(v -> identityChanged(arranger));
      arrangerTrack.position().addValueObserver(v -> identityChanged(arranger));
      arranger.clip().exists().addValueObserver(v -> identityChanged(arranger));
      arranger.clip().playingStep().markInterested();
   }

   private void identityChanged(final EditableClip clip)
   {
      clip.touch();
      markDirty();
   }

   private void startServer()
   {
      try
      {
         final String info = "{\"name\":\"MIDI Comp\",\"extensionVersion\":"
            + Json.quote(getExtensionDefinition().getVersion())
            + ",\"apiVersion\":" + CompServer.API_VERSION + "}";
         server = CompServer.start(SERVER_PORT, SERVER_PORT_ATTEMPTS, this::enqueue, info);
      }
      catch (final IOException e)
      {
         apiStatus.set("not running");
         host.errorln("Comp API server failed to start: " + e.getMessage());
         return;
      }

      apiStatus.set(server.baseUrl());
      host.println("Comp API: " + server.baseUrl() + " (endpoint file " + EndpointFile.path() + ")");
      try
      {
         EndpointFile.write(server.baseUrl(), server.token());
      }
      catch (final IOException e)
      {
         host.errorln("Could not write " + EndpointFile.path() + ": " + e.getMessage());
      }
   }

   // ---------------------------------------------------------------------------------------------
   // Command plumbing

   /** Called on an HTTP thread. */
   private CompletableFuture<String> enqueue(
      final String op,
      final Map<String, String> params,
      final String body)
   {
      final CompletableFuture<String> reply = new CompletableFuture<>();
      if (!running)
         reply.complete(Json.reply(false, "Extension stopped"));
      else
         commands.add(new Command(op, params, body, reply));
      return reply;
   }

   private void pump()
   {
      if (!running)
         return;

      Command c;
      while ((c = commands.poll()) != null)
      {
         if (c.op().equals("export"))
         {
            // Returns the document itself rather than a result message.
            c.reply().complete(live.link() == null
               ? Json.reply(false, "No comp session")
               : CompExport.write(model, live.link(), project.get(), stepSize));
            continue;
         }
         Result result;
         try
         {
            result = c.op().equals("import") ? importComp(c.body()) : runCommand(c.op(), c.params());
         }
         catch (final RuntimeException e)
         {
            host.errorln("Comp API command " + c.op() + " failed: " + e);
            result = Result.fail("Command failed: " + e.getMessage());
         }
         lastMessage = result.message();
         markDirty();
         live.markDirty();
         c.reply().complete(Json.reply(result.ok(), result.message()));
      }

      tickPendingStart();
      tickSession();

      if (stateDirty && server != null)
      {
         stateDirty = false;
         stateVersion++;
         server.publish(buildState(), stateVersion);
      }
      host.scheduleTask(this::pump, PUMP_INTERVAL_MS);
   }

   private String buildState()
   {
      final ClipRef launcher = launcherRef();
      final ClipRef arrangerClip = arrangerRef();
      final StateJson.Selection selection = new StateJson.Selection(
         launcher == null ? null : launcher.label(),
         arrangerClip == null ? null : arrangerClip.label());

      StateJson.SessionStatus session = null;
      if (live.link() != null)
         session = new StateJson.SessionStatus(live.link(), sessionAvailable(), isRecording(),
            live.isPending(),
            compLongerThanClip(), sessionAvailable() ? clipSteps(sessionClip()) : -1,
            capture.passes(),
            capture.isSinglePass(),
            isRecording() ? playedWraps + 1 : 0);

      return StateJson.build(stateVersion, model, stepSize, barSteps(), project.get(), selection,
         session, !history.isEmpty(), lastMessage, List.copyOf(eventLog));
   }

   private void markDirty()
   {
      stateDirty = true;
   }

   private Result runCommand(final String op, final Map<String, String> p)
   {
      return switch (op)
      {
         case "addTake" -> addTake(p.get("source"));
         case "removeTake" -> model.removeTake(intParam(p, "take"))
            ? Result.ok("Take removed") : Result.fail("No such take");
         case "assign" -> assign(intParam(p, "from"), intParam(p, "to"), intParam(p, "take"));
         case "clear" -> assign(intParam(p, "from"), intParam(p, "to"), -1);
         case "useTake" -> assign(0, model.length(), intParam(p, "take"));
         case "undoEdit" -> model.undo() ? Result.ok("Edit undone") : Result.fail("Nothing to undo");
         case "start" -> startSession(p.getOrDefault("source", ""));
         case "end" -> endSession();
         case "record" -> startRecording(!"false".equals(p.get("stopAtEnd")));
         case "stopRecording" -> stopRecording();
         case "revert" -> undoLastWrite();
         default -> Result.fail("Unknown command: " + op);
      };
   }

   private static int intParam(final Map<String, String> p, final String name)
   {
      final String value = p.get(name);
      if (value == null)
         throw new IllegalArgumentException("Missing parameter " + name);
      return Integer.parseInt(value);
   }

   /** Returns the selection cursor for ``"launcher"`` or ``"arranger"``, or ``null``. */
   private EditableClip selectionCursor(final String name)
   {
      return switch (name)
      {
         case "launcher" -> launcherSel;
         case "arranger" -> arranger;
         default -> null;
      };
   }

   private String selectHint(final EditableClip clip, final String what)
   {
      return "Select " + what + " in the " + (clip == arranger ? "arranger" : "clip launcher");
   }

   // ---------------------------------------------------------------------------------------------
   // Takes and segments

   private Result addTake(final String source)
   {
      final EditableClip clip = selectionCursor(source == null ? "" : source);
      if (clip == null)
         return Result.fail("Unknown source: " + source);
      final ClipRef ref = refOf(clip);
      if (ref == null)
         return Result.fail(selectHint(clip, "a MIDI clip"));
      if (ref.sameClip(live.link()))
         return Result.fail("This is the clip being comped; record into it to add takes");
      if (!clip.isSettled(LiveSync.SETTLE_MILLIS))
         return Result.fail("Bitwig is still sending the clip's notes; try again");

      final List<NoteData> notes = clip.notes().all();
      final Take take = model.addTake(uniqueTakeName(ref.label()), ref.source(),
         lengthInSteps(clip, notes), notes);
      return Result.ok(
         String.format("Copied %s as take %d (%d notes)", take.name(), take.id(), notes.size()));
   }

   /** Returns ``base``, numbered if a take of that name exists (the same clip added again). */
   private String uniqueTakeName(final String base)
   {
      int count = 0;
      for (final Take t : model.takes())
         if (t.name().equals(base) || t.name().startsWith(base + " "))
            count++;
      return count == 0 ? base : base + " " + (count + 1);
   }

   private Result assign(final int from, final int to, final int takeId)
   {
      if (takeId >= 0 && model.take(takeId) == null)
         return Result.fail("No such take");
      final int clampedFrom = Math.max(0, from);
      final int clampedTo = Math.min(model.length(), to);
      if (clampedTo <= clampedFrom)
         return Result.fail("Empty range");
      model.assign(clampedFrom, clampedTo, takeId);
      return Result.ok(String.format("%s %s to %s", takeId < 0 ? "Cleared" : "Take " + takeId + ":",
         formatPosition(clampedFrom * stepSize), formatPosition(clampedTo * stepSize)));
   }

   // ---------------------------------------------------------------------------------------------
   // Writing

   /**
    * Returns the length of a take from ``clip``: the clip's length, so that takes recorded into the
    * comp clip are exactly as long as it. Only if the clip reports no length do the notes decide.
    */
   private int lengthInSteps(final EditableClip clip, final List<NoteData> notes)
   {
      final int clipLength = clipSteps(clip);
      if (clipLength > 0)
         return clipLength;
      double beats = 0;
      for (final NoteData n : notes)
         beats = Math.max(beats, n.x() * stepSize + n.duration());
      final int steps = (int) Math.ceil(beats / stepSize - 1e-6);
      return Math.min(GRID_WIDTH, Math.max(steps, barSteps()));
   }

   /**
    * Returns whether the comp is noticeably longer than the session clip. Only meaningful while
    * the clip is under its cursor; small differences from rounding are ignored.
    */
   private boolean compLongerThanClip()
   {
      if (!sessionAvailable())
         return false;
      final int tolerance = Math.max(1, (int) Math.round(0.25 / stepSize));
      return model.length() > clipSteps(sessionClip()) + tolerance;
   }

   /** Returns the clip's length in steps, limited to the grid. */
   private int clipSteps(final EditableClip clip)
   {
      final double beats = clip.clip().getPlayStop().get();
      return Math.min(GRID_WIDTH, Math.max(0, (int) Math.ceil(beats / stepSize - 1e-9)));
   }

   /**
    * Returns how many steps of the comp may be written into ``clip``: never past the clip's end,
    * since notes written there can create clips on other tracks in the arranger.
    */
   private int writableLength(final EditableClip clip)
   {
      return Math.min(model.length(), clipSteps(clip));
   }

   private String truncationNote(final EditableClip clip)
   {
      if (clipSteps(clip) + Math.max(1, (int) Math.round(0.25 / stepSize)) >= model.length())
         return "";
      return String.format(". The clip ends at %s, before the comp (%s); notes after its end are "
         + "not written", formatPosition(clipSteps(clip) * stepSize),
         formatPosition(model.length() * stepSize));
   }

   private List<NoteData> renderedComp(final int length)
   {
      final List<NoteData> notes = new ArrayList<>();
      for (final CompModel.CompNote n : model.render(stepSize))
         if (n.note().x() < length)
            notes.add(n.note());
      return notes;
   }

   private Result undoLastWrite()
   {
      final HistoryEntry entry = history.peekFirst();
      if (entry == null)
         return Result.fail("Nothing to revert");

      // Cursors follow the selection; the written clip must be under the cursor again.
      if (!entry.ref().sameClip(refOf(entry.target())))
         return Result.fail("Select " + entry.ref().label() + " to revert the last write");

      history.removeFirst();
      entry.target().revert(entry.op());
      // The clip holds its original content again, so the session is over.
      if (entry.ref().sameClip(live.link()))
         endSession();
      markDirty();
      return Result.ok("Restored " + entry.ref().label() + " and finished comping");
   }

   private void pushHistory(final HistoryEntry entry)
   {
      history.addFirst(entry);
      while (history.size() > HISTORY_DEPTH)
         history.removeLast();
      markDirty();
   }

   // ---------------------------------------------------------------------------------------------
   // Clip identity

   private ClipRef launcherRef()
   {
      if (!launcherSel.exists())
         return null;
      final String track = launcherTrack.name().get();
      return new ClipRef(Take.Source.LAUNCHER, launcherTrack.position().get(), track,
         launcherCursor.clipLauncherSlot().sceneIndex().get(),
         track + " / " + launcherCursor.clipLauncherSlot().name().get());
   }

   private ClipRef targetRef()
   {
      if (!targetCursor.isPinned().get() || !target.exists())
         return null;
      final String track = targetTrack.name().get();
      return new ClipRef(Take.Source.LAUNCHER, targetTrack.position().get(), track,
         targetCursor.clipLauncherSlot().sceneIndex().get(),
         track + " / " + targetCursor.clipLauncherSlot().name().get());
   }

   private ClipRef arrangerRef()
   {
      if (!arranger.exists())
         return null;
      final String track = arrangerTrack.name().get();
      return new ClipRef(Take.Source.ARRANGER, arrangerTrack.position().get(), track, -1,
         track + " (arranger)");
   }

   /** Returns what ``clip``'s cursor currently points at, or ``null`` if nothing. */
   private ClipRef refOf(final EditableClip clip)
   {
      if (clip == launcherSel)
         return launcherRef();
      if (clip == target)
         return targetRef();
      return arrangerRef();
   }

   // ---------------------------------------------------------------------------------------------
   // Comp session

   private Result startSession(final String source)
   {
      final EditableClip selected = selectionCursor(source);
      if (selected == null)
         return Result.fail("Unknown source: " + source);
      final ClipRef ref = refOf(selected);
      if (ref == null)
         return Result.fail(selectHint(selected, "the clip to comp"));
      if (ref.sameClip(live.link()))
         return Result.fail("Already comping " + ref.label());

      if (live.link() != null)
         endSession();
      model.clear();
      history.clear();

      // Use the finest grid the clip fits; the clip's notes are re-read before the session starts.
      final double wanted = stepSizeFor(selected.clip().getPlayStop().get());
      if (wanted != stepSize)
      {
         stepSize = wanted;
         for (final EditableClip clip : List.of(launcherSel, target, arranger))
            clip.setStepSize(stepSize);
         pendingStart = source;
         pendingStartAt = System.nanoTime();
         log(String.format("Reading %s at 1/%d beat resolution", ref.label(),
            Math.round(1.0 / stepSize)));
         return Result.ok("Preparing " + ref.label());
      }
      if (!selected.isSettled(LiveSync.SETTLE_MILLIS))
         return Result.fail("Bitwig is still sending the clip's notes; try again");
      return finishStart(selected, ref);
   }

   /** Completes a session start that waited for the clip at a new resolution. */
   private void tickPendingStart()
   {
      if (pendingStart == null)
         return;
      final EditableClip selected = selectionCursor(pendingStart);
      final ClipRef ref = refOf(selected);
      final long waited = System.nanoTime() - pendingStartAt;
      // Give the host time to start re-sending notes, then wait until it is done.
      if (ref != null && waited > 400_000_000L && selected.isSettled(LiveSync.SETTLE_MILLIS))
      {
         pendingStart = null;
         lastMessage = finishStart(selected, ref).message();
         markDirty();
      }
      else if (waited > 5_000_000_000L)
      {
         pendingStart = null;
         lastMessage = "Could not read the clip; select it and try again";
         markDirty();
      }
   }

   /**
    * Returns the finest grid step (a power-of-two fraction of a beat) at which a clip of
    * ``beats`` fits into the grid.
    */
   private static double stepSizeFor(final double beats)
   {
      double step = FINEST_STEP;
      while (step < COARSEST_STEP && beats / step > GRID_WIDTH)
         step *= 2;
      return step;
   }

   private Result finishStart(final EditableClip selected, final ClipRef ref)
   {

      // What the clip holds now becomes the first take, and the content the sync expects.
      final List<NoteData> content = selected.notes().all();
      if (!content.isEmpty())
         model.addTake("Original", ref.source(), lengthInSteps(selected, content), content);
      if (ref.source() == Take.Source.LAUNCHER)
         pinTargetToSelection();
      sessionTrack.selectChannel(
         ref.source() == Take.Source.LAUNCHER ? launcherTrack : arrangerTrack);
      sessionTrack.isPinned().set(true);
      live.link(ref, content);
      capture.start();
      log("Comping " + ref.label() + ": " + selected.describe());
      return Result.ok("Comping " + ref.label() + ". Record into it with the loop on; each pass "
         + "becomes a take.");
   }

   private Result endSession()
   {
      final ClipRef old = live.link();
      if (old == null)
         return Result.fail("No comp session");
      live.unlink();
      capture.stop();
      sessionTrack.isPinned().set(false);
      if (old.source() == Take.Source.LAUNCHER)
         unpinTarget();
      model.clear();
      history.clear();
      return Result.ok("Finished comping " + old.label());
   }

   /** Returns the cursor that shows the session clip. */
   private EditableClip sessionClip()
   {
      return live.link().source() == Take.Source.LAUNCHER ? target : arranger;
   }

   /** Returns whether the session clip is under its cursor right now. */
   private boolean sessionAvailable()
   {
      return live.link().sameClip(refOf(sessionClip()));
   }

   /** Returns whether Bitwig is recording into the session clip. */
   private boolean isRecording()
   {
      if (live.link().source() == Take.Source.LAUNCHER)
         return sessionAvailable() && targetCursor.clipLauncherSlot().isRecording().get();
      return transport.isPlaying().get() && transport.isArrangerRecordEnabled().get();
   }

   /**
    * Collects recorded loop passes, then keeps the session clip in sync with the comp.
    *
    * While Bitwig records into the clip (and until the last pass is collected) nothing is written;
    * afterwards the comp is written over the recording, whose passes have become takes.
    */
   private void tickSession()
   {
      if (live.link() == null)
         return;
      final EditableClip clip = sessionClip();
      final boolean available = sessionAvailable();
      final boolean recording = isRecording();
      if (recording != wasRecording)
      {
         wasRecording = recording;
         markDirty();
         if (recording && live.link().source() == Take.Source.ARRANGER)
         {
            if (!recordingRequested)
            {
               played.clear();
               trackPlayed.clear();
               playedWraps = 0;
            }
            capture.setSplitter(this::splitByPlayedNotes);
         }
      }

      // A recording started from the companion begins with the clip emptied.
      final List<NoteData> before = recordingRequested ? List.of() : live.expected();
      for (final List<NoteData> pass : capture.tick(clip, available, recording, before,
         transport.playPosition().get(), clip.clip().playingStep().get(), System.nanoTime()))
         addPass(clip, pass);
      if (capture.takeStopRequest())
         stopTransportRecording();

      if (recordingRequested)
      {
         // Diagnostics: did emptying the clip take effect?
         if (!emptyCheckLogged && System.nanoTime() - recordingRequestedAt > 500_000_000L)
         {
            emptyCheckLogged = true;
            log((available ? "After emptying, the clip shows " : "After emptying (clip not selected) ")
               + clip.describe());
         }
         if (recording)
            recordingSeen = true;
         else if (recordingSeen && !capture.isPending())
            recordingRequested = false;
         else if (!recordingSeen && System.nanoTime() - recordingRequestedAt > 10_000_000_000L)
         {
            recordingRequested = false;
            log("Recording did not start; restoring the comp");
         }
      }
      if (recording || capture.isPending() || recordingRequested)
      {
         live.holdForRecording();
         return;
      }

      final int length = writableLength(clip);
      final LiveSync.Tick tick = live.tick(clip, available, () -> renderedComp(length), length);
      if (tick.firstWrite() != null)
         pushHistory(new HistoryEntry(clip, tick.firstWrite(), live.link()));
      if (tick.wrote())
         markDirty();
      if (tick.leftover() > 0 || tick.missing() > 0)
         log(String.format("Clip check after writing: %d leftover notes, %d missing; %s",
            tick.leftover(), tick.missing(),
            live.isPending() ? "writing again" : "giving up after repeated attempts"));

   }

   /**
    * Adds a recorded pass as a take. While the comp is still a single take, the new pass replaces
    * it, as with Bitwig's audio takes; once takes have been cut together, passes only add lanes.
    */
   private void addPass(final EditableClip clip, final List<NoteData> pass)
   {
      final boolean untouched = model.segments().size() <= 1;
      final Take take = model.addTake("Pass " + capture.passes(), live.link().source(),
         lengthInSteps(clip, clip.notes().all()), pass);
      if (untouched)
         model.assign(0, model.length(), take.id());
      live.markDirty();
      lastMessage = String.format("Recorded pass %d as take %d (%d notes)", capture.passes(),
         take.id(), pass.size());
      markDirty();
   }

   /**
    * Replaces the session's takes and segments with a comp exported earlier.
    *
    * Args:
    *    json: a document from ``GET /api/v1/export``.
    */
   private Result importComp(final String json)
   {
      if (live.link() == null)
         return Result.fail("Start comping a clip first");
      final CompExport.Imported comp;
      try
      {
         comp = CompExport.read(json, stepSize);
      }
      catch (final RuntimeException e)
      {
         return Result.fail("Could not read the saved comp: " + e.getMessage());
      }
      model.restore(comp.takes(), comp.segments());
      live.markDirty();
      log("Restored a saved comp with " + comp.takes().size() + " takes");
      return Result.ok("Restored " + comp.takes().size() + " takes");
   }

   /**
    * Records over the arranger loop region into the session clip.
    *
    * Args:
    *    stopAtEnd: stop when the loop wraps (one take); otherwise every repetition becomes a take
    *       until recording is stopped.
    */
   private Result startRecording(final boolean stopAtEnd)
   {
      if (live.link() == null)
         return Result.fail("Start comping a clip first");
      if (live.link().source() != Take.Source.ARRANGER)
         return Result.fail("Recording from the companion works for arranger clips; record "
            + "launcher clips in Bitwig");
      if (isRecording())
         return Result.fail("Already recording");

      if (!sessionAvailable())
         return Result.fail("Select a clip on the comp clip's track first");

      // Record into an empty clip: only the pass being recorded is heard. The comp is written back
      // from the takes when recording ends.
      final EditableClip clip = sessionClip();
      log("Emptying the comp clip before recording; it shows " + clip.describe());
      clip.clearAll();
      emptyCheckLogged = false;
      recordingRequested = true;
      recordingSeen = false;
      recordingRequestedAt = System.nanoTime();
      played.clear();
      trackPlayed.clear();
      playedWraps = 0;

      if (stopAtEnd)
         capture.armSinglePass();
      log(stopAtEnd ? "Recording one pass of the loop" : "Recording until stopped");
      beginRecording();
      return Result.ok(stopAtEnd
         ? "Recording one pass of the loop region"
         : "Recording; every pass of the loop becomes a take");
   }

   /** Plays from the arranger loop start with arranger recording enabled. */
   private void beginRecording()
   {
      final double loopStart = transport.arrangerLoopStart().get();
      transport.isArrangerLoopEnabled().set(true);
      transport.playStartPosition().set(loopStart);
      transport.setPosition(loopStart);
      transport.isArrangerRecordEnabled().set(true);
      transport.play();
   }

   private Result stopRecording()
   {
      if (!transport.isPlaying().get())
         return Result.fail("Not recording");
      stopTransportRecording();
      return Result.ok("Recording stopped");
   }

   private void stopTransportRecording()
   {
      transport.stop();
      transport.isArrangerRecordEnabled().set(false);
      markDirty();
   }

   private boolean transportRecording()
   {
      return transport.isPlaying().get() && transport.isArrangerRecordEnabled().get();
   }

   /** Tracks the playback position for timing played notes, counting loop wraps. */
   private void onPosition(final double position)
   {
      if (transportRecording() && !Double.isNaN(lastPosition)
         && position < lastPosition - LoopCapture.WRAP_BEATS)
         playedWraps++;
      lastPosition = position;
      lastPositionAt = System.nanoTime();
   }

   /** Notes each key played on the MIDI input while recording. */
   private void onMidi(final int status, final int data1, final int data2)
   {
      if ((status & 0xF0) == 0x90 && data2 > 0)
         notePlayed(played, data1);
   }

   /**
    * Watches the notes a track plays (live input and clip playback alike).
    *
    * The event callback is deprecated in favour of an array of playing notes, but it reports each
    * note-on as it happens, which is what timing needs. Only the key and the moment are used;
    * the notes of the takes always come from Bitwig's recording.
    */
   @SuppressWarnings("deprecation")
   private void observeTrackNotes(final CursorTrack track)
   {
      track.addNoteObserver((isNoteOn, key, velocity) -> {
         if (isNoteOn && live.link() != null)
            notePlayed(trackPlayed, key);
      });
   }

   /**
    * Records a note played during recording with its pass and position in the loop.
    *
    * Args:
    *    into: list to add to.
    *    key: MIDI key.
    */
   private void notePlayed(final List<PassSorter.Played> into, final int key)
   {
      if (!transportRecording() || Double.isNaN(lastPosition))
         return;
      final double tempo = transport.tempo().value().getRaw();
      final double loopLength = transport.arrangerLoopDuration().get();
      final double position =
         lastPosition + (System.nanoTime() - lastPositionAt) / 1e9 * tempo / 60.0;
      double inLoop = position - transport.arrangerLoopStart().get();
      int pass = playedWraps;
      if (inLoop < 0 || loopLength <= 0)
         return; // Pre-roll; nothing is recorded yet.
      // Past the loop end before the jump back was reported: already the next pass.
      while (inLoop >= loopLength)
      {
         inLoop -= loopLength;
         pass++;
      }
      into.add(new PassSorter.Played(key, pass, inLoop));
   }

   /** Sorts a recording's notes into passes by the notes played on the MIDI input. */
   private List<List<NoteData>> splitByPlayedNotes(final List<NoteData> notes)
   {
      // Prefer the MIDI input (only what was played); else the track's notes (no setup needed).
      final boolean fromInput = !played.isEmpty();
      final List<PassSorter.Played> timing = List.copyOf(fromInput ? played : trackPlayed);
      final double loopBeats = transport.arrangerLoopDuration().get();
      if (timing.isEmpty())
      {
         log("No played notes were seen (MIDI input or the track), so the recording is one take");
         return List.of(notes);
      }
      final double tempo = transport.tempo().value().getRaw();
      final PassSorter.Result result = PassSorter.sort(notes, timing, stepSize, loopBeats,
         MATCH_SECONDS * tempo / 60.0);
      log(String.format("Sorted %d notes into %d takes using %d notes from %s (%d unmatched, "
         + "added to the last take)", notes.size(), result.passes().size(), timing.size(),
         fromInput ? "the MIDI input" : "track " + sessionTrack.name().get(), result.unmatched()));
      if (!fromInput)
         log(String.format("Track notes repeating the previous pass within 10 ms (possibly "
            + "playback): %d", repeatsOfPreviousPass(timing, 0.01 * tempo / 60.0, loopBeats)));
      return result.passes();
   }

   /**
    * Counts notes that repeat a note of the previous pass at nearly the same loop position, which
    * suggests clip playback rather than playing. Diagnostics for the track note source.
    */
   private static int repeatsOfPreviousPass(
      final List<PassSorter.Played> notes,
      final double windowBeats,
      final double loopBeats)
   {
      int count = 0;
      for (final PassSorter.Played n : notes)
      {
         for (final PassSorter.Played m : notes)
         {
            if (m.pass() != n.pass() - 1 || m.key() != n.key())
               continue;
            double d = Math.abs(n.loopBeat() - m.loopBeat());
            d = Math.min(d, loopBeats - d);
            if (d <= windowBeats)
            {
               count++;
               break;
            }
         }
      }
      return count;
   }

   /**
    * Records a diagnostic event: printed to the controller console and kept for the companion.
    *
    * Args:
    *    message: the event.
    */
   private void log(final String message)
   {
      host.println(message);
      eventLog.addLast(String.format("%tT  %s", System.currentTimeMillis(), message));
      while (eventLog.size() > EVENT_LOG_SIZE)
         eventLog.removeFirst();
      markDirty();
   }

   private void pinTargetToSelection()
   {
      targetTrack.selectChannel(launcherTrack);
      targetCursor.selectClip(launcherCursor);
      targetTrack.isPinned().set(true);
      targetCursor.isPinned().set(true);
      target.touch();
   }

   private void unpinTarget()
   {
      targetCursor.isPinned().set(false);
      targetTrack.isPinned().set(false);
      markDirty();
   }

   // ---------------------------------------------------------------------------------------------
   // Preferences and formatting

   private double barBeats()
   {
      return 4.0 * transport.timeSignature().numerator().get()
         / transport.timeSignature().denominator().get();
   }

   private int barSteps()
   {
      return Math.max(1, (int) Math.round(barBeats() / stepSize));
   }

   /** Formats a beat time as ``bar.beat`` (1-based, quarter-note beats). */
   private String formatPosition(final double beats)
   {
      final double bar = barBeats();
      final int barIndex = (int) Math.floor(beats / bar);
      final double beatInBar = beats - barIndex * bar;
      final String beat = String.format("%.3f", beatInBar + 1).replaceAll("\\.?0+$", "");
      return (barIndex + 1) + "." + beat;
   }
}

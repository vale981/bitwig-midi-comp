package midicomp;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.PinnableCursorClip;
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
   /** Keyboard messages are kept this long beyond the recording, for notes ending late. */
   private static final long RECORDING_MARGIN_NANOS = 200_000_000L;

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
   /** Keyboard notes sent by the companion, with timestamps. */
   private final KeyboardEvents keyboard = new KeyboardEvents();
   /** The transport position during the current recording. */
   private final TransportTimeline timeline = new TransportTimeline();
   private int playedWraps;
   /** When the current recording started and ended (``System.nanoTime()``), 0 if not yet. */
   private long recordingSince;
   private long recordingUntil;
   /** Provisional takes from the keyboard notes, shown while recording; pass to notes. */
   private List<StateJson.PreviewPass> preview = List.of();
   /** Whether :member:`preview` follows the current recording (until its takes are assembled). */
   private boolean previewActive;
   private long previewAt;
   /** Clip-local beat of the loop start in played time, from the last assembly. */
   private double previewOffsetBeats;
   private static final long PREVIEW_INTERVAL_NANOS = 250_000_000L;
   /** The current recording was started to record a single pass. */
   private boolean recordingOnePass;
   /** A final pass shorter than this part of the loop is not a take (stopped just after a wrap). */
   private static final double SHORTEST_FINAL_PASS = 0.25;
   private double lastPosition = Double.NaN;
   private boolean emptyCheckLogged;

   private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
   private CompServer server;
   private long stateVersion;
   private boolean stateDirty = true;
   private String lastMessage = "";
   private volatile boolean running;

   private SettableStringValue apiStatus;

   protected MidiCompExtension(
      final ControllerExtensionDefinition definition,
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

      transport.tempo().value().markInterested();
      transport.arrangerLoopDuration().markInterested();
      transport.playPosition().addValueObserver(this::onPosition);

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
         if (c.op().equals("played"))
         {
            // Frequent and silent: no status message, no state change, no write.
            String reply = Json.reply(true, "ok");
            try
            {
               receiveKeyboard(c.body());
            }
            catch (final RuntimeException e)
            {
               reply = Json.reply(false, "Bad keyboard data: " + e.getMessage());
            }
            c.reply().complete(reply);
            continue;
         }
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
      updatePreview();

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
            isRecording() ? playedWraps + 1 : 0,
            capture.isPending() && !sessionAvailable(),
            preview);

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
            capture.setSplitter(this::assembleTakes);
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
      if (!tick.leftover().isEmpty() || tick.missing() > 0)
      {
         final StringBuilder notes = new StringBuilder();
         for (final NoteData n : tick.leftover())
            notes.append(String.format("%n    key %d ch %d at step %d (beat %.3f), %.3f beats long",
               n.key(), n.channel() + 1, n.x(), n.x() * stepSize, n.duration()));
         log(String.format("Clip check after writing: %d leftover notes, %d missing (clip %.2f "
            + "beats); %s%s", tick.leftover().size(), tick.missing(),
            clip.clip().getPlayStop().get(),
            tick.gaveUp() ? "these cannot be removed through the API and are ignored now"
               : live.isPending() ? "writing again" : "", notes));
      }

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
      recordingOnePass = stopAtEnd;
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
      final long now = System.nanoTime();
      if (!transportRecording())
      {
         if (recordingSince != 0 && recordingUntil == 0)
            recordingUntil = now;
      }
      else if (recordingSince == 0 || recordingUntil != 0)
      {
         // A new recording: the first moments may still show the playhead before the loop start.
         recordingSince = now;
         recordingUntil = 0;
         playedWraps = 0;
         previewActive = live.link() != null && live.link().source() == Take.Source.ARRANGER;
         preview = List.of();
         previewAt = 0;
         timeline.start(now + LoopCapture.START_GUARD_MILLIS * 1_000_000L);
      }
      if (transportRecording())
      {
         final boolean settled = now - recordingSince > LoopCapture.START_GUARD_MILLIS * 1_000_000L;
         if (settled && !Double.isNaN(lastPosition)
            && position < lastPosition - LoopCapture.WRAP_BEATS)
            playedWraps++;
         timeline.add(now, position);
      }
      lastPosition = position;
   }

   /**
    * Assembles one take per loop pass from Bitwig's recording and the keyboard notes the
    * companion sent (see :class:`LoopTakes`).
    */
   private List<List<NoteData>> assembleTakes(final List<NoteData> recorded)
   {
      final double tempo = transport.tempo().value().getRaw();
      final double loopStart = transport.arrangerLoopStart().get();
      final double loopBeats = transport.arrangerLoopDuration().get();
      final long until = recordingUntil != 0 ? recordingUntil : System.nanoTime();
      final List<KeyboardEvents.PlayedNote> played = keyboard.notes(recordingSince,
         until + RECORDING_MARGIN_NANOS, timeline, loopStart, loopBeats, tempo);
      if (played.isEmpty())
      {
         log("No keyboard notes arrived from the companion (is a MIDI input selected there?), "
            + "so the recording is one take");
         endPreview();
         return recorded.isEmpty() ? List.of() : List.of(recorded);
      }

      // Recording ends a moment after the stop is requested: notes played after the last
      // intended pass are not a take (single pass: the pass after the first; otherwise a final
      // pass that was stopped shortly after it began).
      int lastPass = Integer.MAX_VALUE;
      final TransportTimeline.Placement end = timeline.place(until, loopStart, loopBeats, tempo);
      if (recordingOnePass)
         lastPass = 0;
      else if (end != null)
         lastPass = end.pass() > 0 && end.loopBeat() < SHORTEST_FINAL_PASS * loopBeats
            ? end.pass() - 1 : end.pass();
      recordingOnePass = false;

      final LoopTakes.Result result = LoopTakes.assemble(recorded, played, stepSize, loopBeats,
         MATCH_SECONDS * tempo / 60.0, lastPass);
      if (result.dropped() > 0)
         log(String.format("Left out %d notes played after the last pass (pass %d)",
            result.dropped(), lastPass + 1));
      final double msPerBeat = 60_000.0 / tempo;
      log(String.format("Assembled %d takes from %d played notes: %d recorded by Bitwig, %d "
         + "rebuilt from the keyboard, %d recorded without a played note; timing deviation of "
         + "played vs. recorded notes: mean %.1f ms, max %.1f ms after removing a constant "
         + "%.1f ms (%d position samples, clock offset to the companion %.1f ms)",
         result.passes().size(), played.size(), result.matched(), result.rebuilt(),
         result.unmatched(), result.meanDeviationBeats() * msPerBeat,
         result.maxDeviationBeats() * msPerBeat,
         (result.offsetBeats() - Math.round(result.offsetBeats() * 4) / 4.0) * msPerBeat,
         timeline.sampleCount(), keyboard.clockOffset() / 1e6));
      if (result.matched() > 0)
         previewOffsetBeats = result.offsetBeats();
      endPreview();
      return result.passes();
   }

   /**
    * Recomputes the provisional takes from the keyboard notes, a few times per second while a
    * recording runs and until its takes are assembled. Positions use the offset of the last
    * assembly (the loop start in the clip and the timing delay), or none for the first one.
    */
   private void updatePreview()
   {
      final long now = System.nanoTime();
      if (!previewActive || now - previewAt < PREVIEW_INTERVAL_NANOS)
         return;
      previewAt = now;
      final double tempo = transport.tempo().value().getRaw();
      final double loopBeats = transport.arrangerLoopDuration().get();
      final long until = recordingUntil != 0 ? recordingUntil + RECORDING_MARGIN_NANOS : now;
      final TreeMap<Integer, List<NoteData>> byPass = new TreeMap<>();
      for (final KeyboardEvents.PlayedNote p : keyboard.notes(recordingSince, until, timeline,
         transport.arrangerLoopStart().get(), loopBeats, tempo))
      {
         if (recordingOnePass && p.pass() > 0)
            continue;
         final NoteData note = LoopTakes.rebuild(p, previewOffsetBeats, stepSize, null);
         if (note != null)
            byPass.computeIfAbsent(p.pass(), k -> new ArrayList<>()).add(note);
      }
      final List<StateJson.PreviewPass> next = new ArrayList<>();
      byPass.forEach((pass, notes) -> next.add(new StateJson.PreviewPass(pass + 1, notes)));
      if (!next.equals(preview))
      {
         preview = List.copyOf(next);
         markDirty();
      }
   }

   private void endPreview()
   {
      previewActive = false;
      if (!preview.isEmpty())
      {
         preview = List.of();
         markDirty();
      }
   }

   /**
    * Stores keyboard messages sent by the companion.
    *
    * Args:
    *    json: ``{"events": [[timeMicros, status, data1, data2], ...]}`` with times on the
    *       monotonic clock in microseconds.
    */
   @SuppressWarnings("unchecked")
   private void receiveKeyboard(final String json)
   {
      final long arrived = System.nanoTime();
      final Object parsed = MiniJson.parse(json);
      if (!(parsed instanceof Map<?, ?>))
         throw new IllegalArgumentException("Expected an object");
      final Map<String, Object> body = (Map<String, Object>) parsed;
      if (body.get("now") instanceof Double now)
         keyboard.syncClock(arrived, Math.round(now * 1000.0));
      for (final Object o : MiniJson.array(body, "events"))
      {
         final List<Object> e = (List<Object>) o;
         keyboard.add(Math.round((Double) e.get(0) * 1000.0), (int) (double) (Double) e.get(1),
            (int) (double) (Double) e.get(2), (int) (double) (Double) e.get(3));
      }
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

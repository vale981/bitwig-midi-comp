package midicomp;

import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.TrackBank;
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
   /** Fingerprint of the session clip's content when the session started. */
   private String sessionFingerprint = "";
   /** A duplicate of the session clip waiting to be selected, see :meth:`newVersion`. */
   private PendingVersion pendingVersion;
   /**
    * Arrangement beat at which the arranger session clip starts, learned while it plays (the API
    * does not expose clip positions); ``NaN`` until known. Recent estimates, for their median.
    */
   private final ArrayDeque<Double> clipStartEstimates = new ArrayDeque<>();
   /** Track position the estimates belong to. */
   private int clipStartTrack = -1;
   private com.bitwig.extension.controller.api.Application application;
   /** Bank for finding a free launcher slot on the comp track, see :meth:`loadIntoBitwig`. */
   private TrackBank loadBank;
   private static final int LOAD_SCENES = 64;
   /** A launcher copy of the comp being set up to play in sync with the arranger loop. */
   private static final class LauncherPlay
   {
      final String comp;
      final double loopStartInClip;
      final double loopBeats;
      ClipLauncherSlot slot;
      int slotIndex;
      int step;
      long at;
      long since;
      /** With the transport stopped, start playback at the loop start instead of waiting. */
      boolean startPlayback = true;

      LauncherPlay(final String comp, final double loopStartInClip, final double loopBeats)
      {
         this.comp = comp;
         this.loopStartInClip = loopStartInClip;
         this.loopBeats = loopBeats;
      }
   }

   /** Set up by :meth:`playInLauncher`; the clip is waiting to be loaded while ``slot`` is null. */
   private LauncherPlay launcherPlay;

   /**
    * The launcher clip made by :meth:`playInLauncher`, kept in sync with the comp: after changes,
    * the comp is loaded into it again as a file, so its automation follows the comp.
    */
   private record SyncedClip(int trackPosition, int sceneIndex, double loopBeats,
      String quantization, double quantizationBeats)
   {
   }

   /**
    * Returns the coarsest launch quantization on whose grid both the loop start and end lie, as
    * ``(id, beats)``, so a clip launched just before the loop end starts exactly at the wrap.
    * Loops that are not whole bars long need a finer grid than one bar.
    */
   private Map.Entry<String, Double> syncQuantization(final double loopStart, final double loopEnd)
   {
      final String[] ids = { "1", "1/2", "1/4", "1/8", "1/16" };
      double grid = barBeats();
      for (final String id : ids)
      {
         final boolean fits = Math.abs(loopStart / grid - Math.rint(loopStart / grid)) < 1e-6
            && Math.abs(loopEnd / grid - Math.rint(loopEnd / grid)) < 1e-6;
         if (fits && launchQuantizationId(id) != null)
            return Map.entry(id, grid);
         grid /= 2;
      }
      return null;
   }

   private SyncedClip syncedClip;

   /**
    * Where the synced launcher clip came from, for :meth:`backToArranger`: the arranger comp
    * clip, its full comp (export document) and the loop region's place in it, in beats.
    */
   private record LauncherOrigin(ClipRef arranger, String fullComp, double fromBeats,
      double lengthBeats)
   {
   }

   private LauncherOrigin launcherOrigin;
   private long syncedRevision;
   /** The launcher session :meth:`tickAutoReload` follows; a new one resets its baseline. */
   private ClipRef reloadLink;
   private long seenRevision;
   private long revisionChangedAt;
   private static final long RELOAD_DELAY_NANOS = 1_200_000_000L;
   /** A MIDI file being loaded into a launcher slot, see :meth:`tickSlotLoad`. */
   private static final class SlotLoad
   {
      final int track;
      int scene;
      final String file;
      final String clipName;
      final boolean session;
      final long since = System.nanoTime();
      long at = System.nanoTime() + 300_000_000L;
      boolean deleted;

      SlotLoad(final int track, final int scene, final String file, final String clipName,
         final boolean session)
      {
         this.track = track;
         this.scene = scene;
         this.file = file;
         this.clipName = clipName;
         this.session = session;
      }
   }

   private SlotLoad slotLoad;
   /** Slots this extension loaded comps into (track * 1000 + scene) and the clip names. */
   private final Map<Integer, String> ownSlots = new java.util.HashMap<>();
   /** Control messages for the passes being added by :meth:`addPass`, in order. */
   private final ArrayDeque<List<ControlEvent>> passControls = new ArrayDeque<>();
   /** After a MIDI file replaced the session clip: rewrite the notes from this time on. */
   private long rewriteAfterLoadAt;
   /** Re-pinning the session cursor to a replaced launcher clip: when, which scene, which step. */
   private long repinAt;
   private int repinScene;
   private int repinStep;
   private long lastRepin;
   private long repinSince;
   /** A comp to import once the next session has started. */
   private String carryAfterStart;

   /** A duplicate being prepared; ``renamedTo`` is set once the copy was given its title. */
   private static final class PendingVersion
   {
      final ClipRef original;
      final String comp;
      final long since;
      String renamedTo;
      long renamedAt;

      PendingVersion(final ClipRef original, final String comp, final long since)
      {
         this.original = original;
         this.comp = comp;
         this.since = since;
      }
   }

   private static final long VERSION_TIMEOUT_NANOS = 4_000_000_000L;
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

      application = host.createApplication();
      project = application.projectName();
      project.addValueObserver(v -> markDirty());
      transport = host.createTransport();
      transport.timeSignature().numerator().addValueObserver(v -> markDirty());
      transport.timeSignature().denominator().addValueObserver(v -> markDirty());
      transport.playPosition().markInterested();
      transport.playStartPosition().markInterested();
      transport.isPlaying().addValueObserver(v -> markDirty());
      transport.isArrangerRecordEnabled().addValueObserver(v -> markDirty());
      transport.isArrangerLoopEnabled().markInterested();
      transport.arrangerLoopStart().markInterested();
      transport.playStartPosition().markInterested();

      initLauncherCursors();
      initArrangerCursor();
      initLoadBank();

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
      targetCursor.clipLauncherSlot().isPlaying().markInterested();
      targetCursor.playingStep().markInterested();
   }

   /** One track with its launcher slots, scrolled to the comp track for loading comps. */
   private void initLoadBank()
   {
      loadBank = host.createTrackBank(1, 0, LOAD_SCENES);
      final Track track = loadBank.getItemAt(0);
      track.position().markInterested();
      track.name().markInterested();
      for (int i = 0; i < LOAD_SCENES; i++)
      {
         track.clipLauncherSlotBank().getItemAt(i).hasContent().markInterested();
         track.clipLauncherSlotBank().getItemAt(i).name().markInterested();
      }
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
      tickVersion();
      tickSession();
      updatePreview();
      learnClipStart();
      tickLoad();
      tickLauncherPlay();
      tickAutoReload();
      if (server != null)
         server.publishPlayhead(playheadJson());

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
            preview,
            sessionFingerprint,
            launcherOrigin != null && syncedClip != null);

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
         case "renameTake" -> renameTake(intParam(p, "take"), p.getOrDefault("name", ""));
         case "assign" -> assign(intParam(p, "from"), intParam(p, "to"), intParam(p, "take"));
         case "clear" -> assign(intParam(p, "from"), intParam(p, "to"), -1);
         case "useTake" -> assign(0, model.length(), intParam(p, "take"));
         case "undoEdit" -> model.undo() ? Result.ok("Edit undone") : Result.fail("Nothing to undo");
         case "start" -> {
            // A clip chosen by hand replaces a pending new version.
            pendingVersion = null;
            carryAfterStart = null;
            yield startSession(p.getOrDefault("source", ""));
         }
         case "end" -> endSession();
         case "newVersion" -> newVersion();
         case "record" -> startRecording(!"false".equals(p.get("stopAtEnd")));
         case "stopRecording" -> stopRecording();
         case "seek" -> seek(intParam(p, "step"));
         case "transport" -> transportAction(p.getOrDefault("action", ""));
         case "loadIntoBitwig" -> loadIntoBitwig();
         case "playInLauncher" -> playInLauncher();
         case "backToArranger" -> backToArranger();
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
         carryAfterStart = null;
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
      sessionFingerprint = Fingerprint.of(content, stepSize);
      // The learned clip position stays valid for another session on the same track.
      if (ref.trackPosition() != clipStartTrack)
         clipStartEstimates.clear();
      capture.start();
      log("Comping " + ref.label() + ": " + selected.describe());
      if (carryAfterStart != null)
      {
         final String carried = carryAfterStart;
         carryAfterStart = null;
         final Result imported = importComp(carried);
         return Result.ok("Comping the new version " + ref.label() + ": " + imported.message());
      }
      return Result.ok("Comping " + ref.label() + ". Record into it with the loop on; each pass "
         + "becomes a take.");
   }

   private Result endSession()
   {
      final ClipRef old = live.link();
      if (old == null)
         return Result.fail("No comp session");
      live.unlink();
      sessionFingerprint = "";
      capture.stop();
      if (old.source() == Take.Source.LAUNCHER)
         unpinTarget();
      model.clear();
      history.clear();
      return Result.ok("Finished comping " + old.label());
   }

   /** Renames a take; names are trimmed to at most 60 characters. */
   private Result renameTake(final int id, final String name)
   {
      String clean = name.strip();
      if (clean.isEmpty())
         return Result.fail("A take needs a name");
      if (clean.length() > 60)
         clean = clean.substring(0, 60);
      return model.renameTake(id, clean) ? Result.ok("Renamed the take to " + clean)
         : Result.fail("No such take");
   }

   /**
    * Duplicates the session clip as a new version that carries the comp: the copy gets the same
    * takes and cuts, and the original keeps its comp. The session ends here and starts on the
    * copy once Bitwig has selected it (:meth:`tickVersion`).
    *
    * Launcher clips only: ``Clip.duplicate()`` extends an arranger clip instead of copying it,
    * and the API identifies arranger clips by track, so a copy on the same track would receive
    * every edit too.
    */
   private Result newVersion()
   {
      final ClipRef original = live.link();
      if (original == null)
         return Result.fail("Start comping a clip first");
      if (original.source() != Take.Source.LAUNCHER)
         return Result.fail("New versions work for launcher clips. For arranger clips, duplicate "
            + "the clip to another track in Bitwig and comp the copy; the comp is offered there.");
      if (isRecording())
         return Result.fail("Stop recording first");
      final String comp = CompExport.write(model, original, project.get(), stepSize);
      targetCursor.clipLauncherSlot().duplicateClip();
      endSession();
      pendingVersion = new PendingVersion(original, comp, System.nanoTime());
      log("Duplicating " + original.label() + " as a new version");
      return Result.ok("Duplicating " + original.label());
   }

   /**
    * Starts the session on the duplicate made by :meth:`newVersion` once the launcher selection
    * shows another slot on the same track.
    */
   private void tickVersion()
   {
      if (pendingVersion == null)
         return;
      final ClipRef original = pendingVersion.original;
      final long now = System.nanoTime();
      final long waited = now - pendingVersion.since;
      final ClipRef selected = launcherRef();
      boolean ready = selected != null
         && selected.trackPosition() == original.trackPosition()
         && selected.sceneIndex() != original.sceneIndex()
         && launcherSel.isSettled(LiveSync.SETTLE_MILLIS);
      if (ready)
      {
         // Give the copy its own title, so its comps are saved under it; start once Bitwig
         // reports the new title (or after a second).
         final String title = launcherCursor.clipLauncherSlot().name().get();
         if (pendingVersion.renamedTo == null)
         {
            pendingVersion.renamedTo = nextVersionTitle(title);
            pendingVersion.renamedAt = now;
            launcherCursor.setName(pendingVersion.renamedTo);
            log("Naming the copy \"" + pendingVersion.renamedTo + "\"");
         }
         ready = title.equals(pendingVersion.renamedTo)
            || now - pendingVersion.renamedAt > 1_000_000_000L;
      }
      if (ready)
      {
         carryAfterStart = pendingVersion.comp;
         pendingVersion = null;
         final Result started = startSession("launcher");
         if (!started.ok())
            carryAfterStart = null;
         lastMessage = started.message();
         markDirty();
      }
      else if (waited > VERSION_TIMEOUT_NANOS)
      {
         pendingVersion = null;
         log("Bitwig did not select the copy of " + original.label() + "; select it and comp it, "
            + "the companion offers to continue with the comp");
         lastMessage = "Select the copy in Bitwig and comp it; the comp is offered there";
         markDirty();
      }
   }

   /**
    * Returns the title for a new version of a clip: "Lead" becomes "Lead v2", "Lead v2" becomes
    * "Lead v3".
    *
    * Args:
    *    title: the clip's title, possibly empty.
    */
   static String nextVersionTitle(final String title)
   {
      final java.util.regex.Matcher m =
         java.util.regex.Pattern.compile("^(.*) v(\\d+)$").matcher(title.strip());
      if (m.matches())
         return m.group(1) + " v" + (Integer.parseInt(m.group(2)) + 1);
      return title.isBlank() ? "Comp v2" : title.strip() + " v2";
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
      final List<ControlEvent> controls = passControls.isEmpty() ? List.of() : passControls.poll();
      final Take take = model.addTake("Pass " + capture.passes(), live.link().source(),
         lengthInSteps(clip, clip.notes().all()), pass, controls);
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
         MATCH_SECONDS * tempo / 60.0, lastPass, previewOffsetBeats);
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
      final List<List<ControlEvent>> controls = LoopTakes.placeControls(
         keyboard.controls(recordingSince, until + RECORDING_MARGIN_NANOS, timeline, loopStart,
            loopBeats, tempo), result, stepSize, loopBeats);
      passControls.clear();
      passControls.addAll(controls);
      final int controlCount = controls.stream().mapToInt(List::size).sum();
      if (controlCount > 0)
         log(String.format("%d control messages (CC, pressure, pitch bend) placed in the takes",
            controlCount));
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

   /**
    * Returns the playhead in the session clip: whether the transport plays, the step being
    * played (or -1), and steps per second for interpolating between updates. The step comes
    * from the clip itself; while Bitwig records into a hidden arranger clip, from the transport
    * position and the loop start in the clip measured at the last assembly.
    */
   private String playheadJson()
   {
      final boolean playing = transport.isPlaying().get();
      int step = -1;
      if (live.link() != null)
      {
         if (sessionAvailable() && playing)
            step = sessionClip().clip().playingStep().get();
         final double clipStart = arrangerClipStart();
         if (step < 0 && !Double.isNaN(clipStart) && !isRecording())
         {
            // From the transport: while playing with the clip deselected, or the play start
            // position while stopped.
            final double beat =
               (playing ? transport.playPosition().get() : transport.playStartPosition().get())
                  - clipStart;
            if (beat >= 0 && beat * (1.0 / stepSize) <= model.length())
               step = (int) Math.round(beat / stepSize);
         }
         if (step < 0 && live.link().source() == Take.Source.ARRANGER && isRecording()
            && !Double.isNaN(lastPosition))
         {
            final double loopBeats = transport.arrangerLoopDuration().get();
            final double inLoop =
               LoopTakes.wrap(lastPosition - transport.arrangerLoopStart().get(), loopBeats);
            step = (int) Math.round((Math.round(previewOffsetBeats * 4) / 4.0 + inLoop) / stepSize);
         }
      }
      final double stepsPerSecond = transport.tempo().value().getRaw() / 60.0 / stepSize;
      return String.format(java.util.Locale.ROOT,
         "{\"playing\":%b,\"step\":%d,\"stepsPerSecond\":%.3f,\"canSeek\":%b,"
            + "\"transportPlaying\":%b}",
         playing && step >= 0, step, stepsPerSecond, !Double.isNaN(arrangerClipStart()), playing);
   }

   /**
    * Starts or stops Bitwig's transport, as its play and stop buttons do.
    *
    * Args:
    *    action: ``play``, ``stop`` or ``toggle``.
    */
   private Result transportAction(final String action)
   {
      final SyncedClip synced = syncedClip;
      final boolean starting = action.equals("play")
         || (action.equals("toggle") && !transport.isPlaying().get());
      if (starting && synced != null && live.link() != null
         && live.link().trackPosition() == synced.trackPosition()
         && live.link().sceneIndex() == synced.sceneIndex() && synced.sceneIndex() < LOAD_SCENES
         && loadBank.getItemAt(0).position().get() == synced.trackPosition())
      {
         // Comping a launcher copy: start with the comp clip at the loop start.
         transport.playStartPosition().set(transport.arrangerLoopStart().get());
         loadBank.getItemAt(0).clipLauncherSlotBank().getItemAt(synced.sceneIndex()).launch();
         return Result.ok("Playing from the loop start with the comp in the launcher");
      }
      switch (action)
      {
         case "play" -> transport.play();
         case "stop" -> transport.stop();
         case "toggle" -> transport.togglePlay();
         default ->
         {
            return Result.fail("Unknown transport action: " + action);
         }
      }
      return Result.ok(action.equals("stop") ? "Stopped" : "Playing");
   }

   /**
    * Writes the comp, notes and control messages, as a MIDI file and loads it into Bitwig: into
    * the session clip's slot for launcher clips, else into the first free launcher slot of the
    * comp track, to be dragged into the arranger. The extension API cannot write control
    * messages into clips, but Bitwig imports them from MIDI files.
    */
   private Result loadIntoBitwig()
   {
      if (live.link() == null)
         return Result.fail("Start comping a clip first");
      if (isRecording())
         return Result.fail("Stop recording first");
      final boolean launcher = live.link().source() == Take.Source.LAUNCHER;
      final String title = launcher ? targetCursor.clipLauncherSlot().name().get()
         : live.link().trackName() + " comp";
      final java.nio.file.Path file;
      try
      {
         file = writeMidiFile(title.isBlank() ? "Comp" : title);
      }
      catch (final java.io.IOException e)
      {
         return Result.fail("Could not write the MIDI file: " + e.getMessage());
      }
      loadBank.scrollPosition().set(live.link().trackPosition());
      final String clipName = file.getFileName().toString().replaceFirst("\\.mid$", "");
      if (launcher)
      {
         // Into the session's own slot; the extension follows the new clip afterwards.
         slotLoad = new SlotLoad(live.link().trackPosition(), live.link().sceneIndex(),
            file.toString(), clipName, true);
         return Result.ok("Loading the comp into " + live.link().label());
      }
      // Into the slot used last time on this track, else the first free one.
      slotLoad = new SlotLoad(live.link().trackPosition(), -1, file.toString(), clipName, false);
      return Result.ok("Loading the comp into a launcher slot of " + live.link().trackName());
   }

   /**
    * Plays the comp from the clip launcher while the other tracks play the arrangement: loads
    * it into a free launcher slot of the comp track, loops that clip over the arranger loop
    * region, moves the session to it (takes and cuts come along) and launches it in the last bar
    * of the arranger loop, so it comes in at the next loop start and wraps with the arranger.
    */
   private Result playInLauncher()
   {
      if (live.link() == null || live.link().source() != Take.Source.ARRANGER)
         return Result.fail("Playing in the launcher starts from an arranger comp");
      if (!transport.isArrangerLoopEnabled().get())
         return Result.fail("Turn on the arranger loop over the comp first");
      final double loopBeats = transport.arrangerLoopDuration().get();
      final double clipStart = arrangerClipStart();
      final double loopStartInClip = !Double.isNaN(clipStart)
         ? transport.arrangerLoopStart().get() - clipStart : Math.round(previewOffsetBeats * 4) / 4.0;
      // The launcher clip holds just the loop region, from 0: notes, automation and the clip's
      // own loop then line up without further settings (the API cannot set automation loops).
      final int from = (int) Math.round(Math.max(0, loopStartInClip) / stepSize);
      final CompModel loop = model.cropped(from, (int) Math.round(loopBeats / stepSize), stepSize);
      final String comp = CompExport.write(loop, live.link(), project.get(), stepSize);
      final java.nio.file.Path file;
      try
      {
         file = writeMidiFile(live.link().trackName() + " loop comp", loop);
      }
      catch (final java.io.IOException e)
      {
         return Result.fail("Could not write the MIDI file: " + e.getMessage());
      }
      loadBank.scrollPosition().set(live.link().trackPosition());
      slotLoad = new SlotLoad(live.link().trackPosition(), -1, file.toString(),
         file.getFileName().toString().replaceFirst("\\.mid$", ""), false);
      launcherPlay = new LauncherPlay(comp, 0, loopBeats);
      launcherOrigin = new LauncherOrigin(live.link(),
         CompExport.write(model, live.link(), project.get(), stepSize), from * stepSize, loopBeats);
      log(String.format("Playing in the launcher: the loop region (from clip beat %.3f, %.3f "
         + "beats) as its own clip", Math.max(0, loopStartInClip), loopBeats));
      return Result.ok("Setting up the comp in the launcher");
   }

   /** Steps of :meth:`playInLauncher` once the clip is loaded. */
   private void tickLauncherPlay()
   {
      final LauncherPlay play = launcherPlay;
      final long now = System.nanoTime();
      if (play == null || play.slot == null || now < play.at)
         return;
      if (play.comp == null && play.step < 2)
         play.step = 2; // a relaunch after a reload: only the timing is left
      switch (play.step)
      {
         case 0 ->
         {
            play.slot.select();
            play.step = 1;
            play.at = now + 400_000_000L;
         }
         case 1 ->
         {
            final ClipRef selected = launcherRef();
            final boolean onCopy = selected != null && live.link() != null
               && selected.trackPosition() == live.link().trackPosition()
               && selected.sceneIndex() == play.slotIndex;
            if (!onCopy)
            {
               if (now - play.since > 5_000_000_000L)
               {
                  log("Playing in the launcher: the loaded clip did not get selected; select it "
                     + "and launch it yourself");
                  launcherPlay = null;
               }
               else
                  play.at = now + 200_000_000L;
               return;
            }
            launcherCursor.isLoopEnabled().set(true);
            launcherCursor.getLoopStart().set(play.loopStartInClip);
            launcherCursor.getLoopLength().set(play.loopBeats);
            final double loopStart = transport.arrangerLoopStart().get();
            final Map.Entry<String, Double> quantization =
               syncQuantization(loopStart, loopStart + play.loopBeats);
            if (quantization != null)
               launcherCursor.launchQuantization().set(quantization.getKey());
            log("Playing in the launcher: clip loop set, launch quantization "
               + (quantization == null ? "unchanged (the loop is on no launch grid)"
                  : quantization.getKey()));
            // Comp in the launcher clip from now on, with the same takes and cuts.
            syncedClip = new SyncedClip(selected.trackPosition(), selected.sceneIndex(),
               play.loopBeats, quantization == null ? null : quantization.getKey(),
               quantization == null ? 0 : quantization.getValue());
            seenRevision = 0;
            carryAfterStart = play.comp;
            final Result started = startSession("launcher");
            if (!started.ok())
               carryAfterStart = null;
            lastMessage = started.message();
            markDirty();
            play.step = 2;
            play.at = now + 200_000_000L;
         }
         case 2 ->
         {
            // Launch in the last bar of the arranger loop: it starts at the next bar line, which
            // is the loop start.
            final double loopStart = transport.arrangerLoopStart().get();
            final double loopEnd = loopStart + transport.arrangerLoopDuration().get();
            final double position = transport.playPosition().get();
            final SyncedClip synced = syncedClip;
            if (!transport.isPlaying().get() && play.startPlayback)
            {
               // Stopped: launching the clip starts playback, here from the loop start, so
               // the arranger and the clip begin together.
               transport.playStartPosition().set(loopStart);
               play.slot.launch();
               log("Playing in the launcher: started playback at the loop start with the comp");
               lastMessage = "The comp plays from the launcher; the other tracks from the arranger";
               markDirty();
               launcherPlay = null;
               return;
            }
            final double grid = synced != null && synced.quantizationBeats() > 0
               ? synced.quantizationBeats() : barBeats();
            if (transport.isPlaying().get() && position >= loopEnd - grid && position < loopEnd)
            {
               play.slot.launch();
               log("Playing in the launcher: launched, comes in at the next loop start");
               lastMessage = "The comp plays from the launcher; the other tracks from the arranger";
               markDirty();
               launcherPlay = null;
               return;
            }
            if (now - play.since > 120_000_000_000L)
            {
               log("Playing in the launcher: playback did not reach the end of the loop; launch "
                  + "the clip yourself in the loop's last bar");
               launcherPlay = null;
               return;
            }
            play.at = now + 20_000_000L;
         }
         default ->
         {
            // A relaunch outside Play in launcher: right away, with the clip's quantization.
            play.slot.launch();
            log("Relaunched the reloaded clip");
            launcherPlay = null;
         }
      }
   }

   /**
    * Returns from :meth:`playInLauncher` to the arranger: merges the launcher session's cuts back
    * into the full comp, returns the comp track to the arrangement and continues the session in
    * the arranger clip, which gets the merged comp.
    */
   private Result backToArranger()
   {
      final LauncherOrigin origin = launcherOrigin;
      if (origin == null || syncedClip == null || live.link() == null
         || live.link().source() != Take.Source.LAUNCHER)
         return Result.fail("Back to arranger works for a comp played in the launcher");
      final ClipRef arrangerClip = arrangerRef();
      if (arrangerClip == null || arrangerClip.trackPosition() != origin.arranger().trackPosition())
         return Result.fail("Select the comp clip on the " + origin.arranger().trackName()
            + " track in the arranger first");

      final CompExport.Imported full;
      try
      {
         full = CompExport.read(origin.fullComp(), stepSize);
      }
      catch (final RuntimeException e)
      {
         return Result.fail("Could not read the arranger comp: " + e.getMessage());
      }
      final int from = (int) Math.round(origin.fromBeats() / stepSize);
      final int length = (int) Math.round(origin.lengthBeats() / stepSize);
      if (full.takes().size() != model.takes().size())
         log(String.format("Back to arranger: the arranger comp has %d takes, the launcher comp "
            + "%d; takes are matched in order", full.takes().size(), model.takes().size()));
      final CompModel merged = CompModel.mergeRegion(full, model, from, length);

      final Track track = loadBank.getItemAt(0);
      if (track.position().get() == origin.arranger().trackPosition())
      {
         track.returnToArrangement();
         final int cleared = clearOwnSlots(origin.arranger().trackPosition());
         if (cleared > 0)
            log("Back to arranger: removed " + cleared + " launcher clip"
               + (cleared == 1 ? "" : "s") + " made by MIDI Comp");
      }
      else
         log("Back to arranger: use Back to arrangement in Bitwig for the comp track");
      syncedClip = null;
      launcherOrigin = null;
      launcherPlay = null;
      carryAfterStart = CompExport.write(merged, origin.arranger(), project.get(), stepSize);
      final Result started = startSession("arranger");
      if (!started.ok())
         carryAfterStart = null;
      log("Back to arranger: " + started.message());
      return started;
   }

   /**
    * Reloads a launcher session's clip from a file once the comp has not changed for a moment,
    * so its automation (control messages) follows the comp. A playing clip is relaunched, since
    * replacing it can interrupt it: a clip from :meth:`playInLauncher` in the last bar of the
    * arranger loop, others right away (with their own launch quantization).
    */
   private void tickAutoReload()
   {
      final ClipRef link = live.link();
      if (link == null || link.source() != Take.Source.LAUNCHER || pendingStart != null
         || carryAfterStart != null)
      {
         // Not a launcher session (yet): the next one starts from its own state.
         if (syncedClip != null && launcherPlay == null && pendingStart == null
            && carryAfterStart == null)
            syncedClip = null;
         reloadLink = null;
         seenRevision = 0;
         return;
      }
      final SyncedClip synced = syncedClip != null
         && link.trackPosition() == syncedClip.trackPosition()
         && link.sceneIndex() == syncedClip.sceneIndex() ? syncedClip : null;
      if (syncedClip != null && synced == null && launcherPlay == null)
         syncedClip = null; // the session moved to another clip
      if (!link.sameClip(reloadLink))
      {
         reloadLink = link;
         seenRevision = 0;
      }
      final long now = System.nanoTime();
      final long revision = model.revision();
      if (revision != seenRevision)
      {
         if (seenRevision == 0)
            syncedRevision = revision; // the session's starting point is already in the clip
         seenRevision = revision;
         revisionChangedAt = now;
      }
      final boolean settingUp = launcherPlay != null && launcherPlay.step < 2;
      if (revision == syncedRevision || now - revisionChangedAt < RELOAD_DELAY_NANOS || settingUp
         || slotLoad != null || repinAt != 0 || rewriteAfterLoadAt != 0 || isRecording())
         return;
      syncedRevision = revision;
      final boolean playing = targetCursor.clipLauncherSlot().isPlaying().get();
      final Result loaded = loadIntoBitwig();
      log("Comp changed: " + loaded.message() + (!playing ? ""
         : synced != null ? "; relaunching at the next loop start" : "; relaunching"));
      if (loaded.ok() && playing && link.sceneIndex() < LOAD_SCENES
         && (launcherPlay == null || launcherPlay.step < 2))
      {
         final LauncherPlay relaunch =
            new LauncherPlay(null, 0, synced != null ? synced.loopBeats() : 0);
         relaunch.slot = loadBank.getItemAt(0).clipLauncherSlotBank().getItemAt(link.sceneIndex());
         relaunch.slotIndex = link.sceneIndex();
         relaunch.step = synced != null ? 2 : 3;
         relaunch.startPlayback = false;
         relaunch.since = now;
         relaunch.at = now + 600_000_000L;
         launcherPlay = relaunch;
      }
   }

   /** Returns the id of a launch quantization value, or null; logs the ids once if missing. */
   private String launchQuantizationId(final String wanted)
   {
      final com.bitwig.extension.controller.api.EnumDefinition values =
         launcherCursor.launchQuantization().enumDefinition();
      final List<String> ids = new ArrayList<>();
      for (int i = 0; i < values.getValueCount(); i++)
      {
         final String id = values.valueDefinitionAt(i).getId();
         if (id.equals(wanted))
            return id;
         ids.add(id);
      }
      log("Launch quantization values: " + String.join(", ", ids));
      return null;
   }

   /** Writes the comp to ``~/.midicomp/midi/<title>.mid`` and returns the path. */
   private java.nio.file.Path writeMidiFile(final String title) throws java.io.IOException
   {
      return writeMidiFile(title, model);
   }

   /** Writes a comp as ``~/.midicomp/midi/<title>.mid`` and returns the path. */
   private java.nio.file.Path writeMidiFile(final String title, final CompModel comp)
      throws java.io.IOException
   {
      final java.nio.file.Path dir =
         java.nio.file.Path.of(System.getProperty("user.home"), ".midicomp", "midi");
      java.nio.file.Files.createDirectories(dir);
      final String safe = title.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
      final java.nio.file.Path file = dir.resolve((safe.isEmpty() ? "Comp" : safe) + ".mid");
      final List<NoteData> notes = new ArrayList<>();
      for (final CompModel.CompNote n : comp.render(stepSize))
         notes.add(n.note());
      java.nio.file.Files.write(file,
         MidiFile.write(notes, comp.renderControls(), stepSize, comp.length()));
      return file;
   }

   /** Finishes a load started by :meth:`loadIntoBitwig`: free slot, or the note rewrite. */
   private void tickLoad()
   {
      final long now = System.nanoTime();
      // A launcher session whose clip was replaced: follow it again once its slot is selected.
      if (repinAt == 0 && live.link() != null && live.link().source() == Take.Source.LAUNCHER
         && !sessionAvailable() && now - lastRepin > 1_000_000_000L)
      {
         final ClipRef selected = launcherRef();
         if (selected != null && selected.trackPosition() == live.link().trackPosition()
            && selected.sceneIndex() == live.link().sceneIndex())
         {
            lastRepin = now;
            pinTargetToSelection();
            log("Following " + live.link().label() + " again");
         }
      }
      if (repinAt != 0 && now > repinAt)
      {
         final Track track = loadBank.getItemAt(0);
         if (live.link() == null || repinScene >= LOAD_SCENES || now - repinSince > 5_000_000_000L)
            repinAt = 0;
         else if (track.position().get() != live.link().trackPosition())
            repinAt = now + 200_000_000L; // the bank is still scrolling
         else if (repinStep == 0)
         {
            track.clipLauncherSlotBank().getItemAt(repinScene).select();
            repinStep = 1;
            repinAt = now + 400_000_000L;
         }
         else
         {
            pinTargetToSelection();
            repinAt = 0;
            final SyncedClip synced = syncedClip;
            if (synced != null && synced.trackPosition() == live.link().trackPosition()
               && synced.sceneIndex() == repinScene)
            {
               // A new clip has default settings: loop and quantize it for the synced launch.
               targetCursor.isLoopEnabled().set(true);
               targetCursor.getLoopStart().set(0);
               targetCursor.getLoopLength().set(synced.loopBeats());
               if (synced.quantization() != null)
                  targetCursor.launchQuantization().set(synced.quantization());
            }
            log("Following the reloaded clip again");
         }
      }
      if (rewriteAfterLoadAt != 0 && now > rewriteAfterLoadAt + 8_000_000_000L)
      {
         rewriteAfterLoadAt = 0;
         log("The loaded clip did not become available; select it to continue comping");
      }
      if (rewriteAfterLoadAt != 0 && now > rewriteAfterLoadAt && live.link() != null
         && sessionAvailable() && sessionClip().isSettled(LiveSync.SETTLE_MILLIS))
      {
         rewriteAfterLoadAt = 0;
         // Expect an empty clip, so every comp note is written again with all its values.
         live.link(live.link(), List.of());
         log("Rewriting the comp's notes over the loaded file");
      }
      tickSlotLoad(now);
   }

   /**
    * Loads a MIDI file into a launcher slot (:member:`slotLoad`): picks the slot, deletes the clip
    * in it (loading into an occupied slot does not replace the clip's automation), waits until
    * the slot is empty and loads the file.
    */
   private void tickSlotLoad(final long now)
   {
      final SlotLoad load = slotLoad;
      if (load == null || now < load.at)
         return;
      final Track track = loadBank.getItemAt(0);
      if (track.position().get() != load.track)
      {
         if (now - load.since > 3_000_000_000L)
         {
            slotLoad = null;
            lastMessage = "Could not find the comp track; the MIDI file is " + load.file;
            markDirty();
         }
         else
            load.at = now + 100_000_000L; // the bank is still scrolling
         return;
      }
      if (load.scene < 0)
      {
         load.scene = ownSlot(track, load.track);
         for (int i = 0; load.scene < 0 && i < LOAD_SCENES; i++)
            if (!track.clipLauncherSlotBank().getItemAt(i).hasContent().get())
               load.scene = i;
         if (load.scene < 0)
         {
            slotLoad = null;
            lastMessage = "No free launcher slot in the first " + LOAD_SCENES + " scenes; the "
               + "MIDI file is " + load.file;
            markDirty();
            return;
         }
      }
      if (load.scene >= LOAD_SCENES)
      {
         slotLoad = null;
         log("The slot is beyond the first " + LOAD_SCENES + " scenes; load " + load.file
            + " yourself");
         return;
      }
      final ClipLauncherSlot slot = track.clipLauncherSlotBank().getItemAt(load.scene);
      if (slot.hasContent().get())
      {
         if (!load.deleted)
         {
            slot.deleteObject();
            load.deleted = true;
         }
         if (now - load.since > 4_000_000_000L)
         {
            slotLoad = null;
            log("Could not empty launcher slot " + (load.scene + 1) + "; the MIDI file is "
               + load.file);
            return;
         }
         load.at = now + 100_000_000L;
         return;
      }
      slotLoad = null;
      slot.replaceInsertionPoint().insertFile(load.file);
      ownSlots.put(load.track * 1000 + load.scene, load.clipName);
      log("Loaded " + load.file + " into launcher slot " + (load.scene + 1) + " of "
         + track.name().get());
      if (load.session)
      {
         // Follow the slot's new clip, then rewrite the notes: MIDI files cannot hold timbre,
         // chance and other note values.
         repinScene = load.scene;
         repinStep = 0;
         repinAt = now + 900_000_000L;
         repinSince = now;
         rewriteAfterLoadAt = now + 1_500_000_000L;
         return;
      }
      // Copying it into the arranger is left to the user: copy and paste through the API do
      // not carry the clip's automation, Bitwig's own copy and paste do.
      lastMessage = String.format("Loaded the comp into launcher slot %d of %s; copy it over the "
         + "arranger clip", load.scene + 1, track.name().get());
      if (launcherPlay != null && launcherPlay.slot == null)
      {
         launcherPlay.slot = slot;
         launcherPlay.slotIndex = load.scene;
         launcherPlay.at = now + 1_000_000_000L;
         launcherPlay.since = now;
      }
      markDirty();
   }

   /** Returns the scene of a slot this extension filled on the track, if it still holds that clip. */
   private int ownSlot(final Track track, final int trackPosition)
   {
      for (final Map.Entry<Integer, String> e : ownSlots.entrySet())
      {
         if (e.getKey() / 1000 != trackPosition || e.getKey() % 1000 >= LOAD_SCENES)
            continue;
         final ClipLauncherSlot slot = track.clipLauncherSlotBank().getItemAt(e.getKey() % 1000);
         if (slot.hasContent().get() && slot.name().get().equals(e.getValue()))
            return e.getKey() % 1000;
      }
      return -1;
   }

   /** Deletes the clips this extension loaded into slots of the track, if still unchanged. */
   private int clearOwnSlots(final int trackPosition)
   {
      final Track track = loadBank.getItemAt(0);
      if (track.position().get() != trackPosition)
         return 0;
      int cleared = 0;
      for (final java.util.Iterator<Map.Entry<Integer, String>> it = ownSlots.entrySet().iterator();
         it.hasNext();)
      {
         final Map.Entry<Integer, String> e = it.next();
         if (e.getKey() / 1000 != trackPosition || e.getKey() % 1000 >= LOAD_SCENES)
            continue;
         final ClipLauncherSlot slot = track.clipLauncherSlotBank().getItemAt(e.getKey() % 1000);
         if (slot.hasContent().get() && slot.name().get().equals(e.getValue()))
         {
            slot.deleteObject();
            cleared++;
         }
         it.remove();
      }
      return cleared;
   }

   /**
    * Learns where the arranger session clip starts: while it plays, the transport position minus
    * the clip's playing position. The median of recent estimates absorbs update delays.
    */
   private void learnClipStart()
   {
      if (live.link() == null || live.link().source() != Take.Source.ARRANGER)
         return;
      if (!transport.isPlaying().get() || !sessionAvailable())
         return;
      if (live.link().trackPosition() != clipStartTrack)
      {
         clipStartEstimates.clear();
         clipStartTrack = live.link().trackPosition();
      }
      final int step = arranger.clip().playingStep().get();
      if (step < 0)
         return;
      clipStartEstimates.addLast(transport.playPosition().get() - step * stepSize);
      while (clipStartEstimates.size() > 32)
         clipStartEstimates.removeFirst();
   }

   /**
    * Returns the arrangement beat at which the arranger session clip starts: learned while it
    * played, else from the last recording (the loop start in the clip), else ``NaN``.
    */
   private double arrangerClipStart()
   {
      if (live.link() == null || live.link().source() != Take.Source.ARRANGER)
         return Double.NaN;
      if (clipStartEstimates.size() >= 4)
      {
         final List<Double> sorted = new ArrayList<>(clipStartEstimates);
         sorted.sort(null);
         return sorted.get(sorted.size() / 2);
      }
      if (previewOffsetBeats != 0)
         return transport.arrangerLoopStart().get() - Math.round(previewOffsetBeats * 4) / 4.0;
      return Double.NaN;
   }

   /**
    * Moves the playhead to a step of the arranger session clip, as clicking Bitwig's ruler does:
    * sets the play start and jumps there while playing.
    *
    * Args:
    *    step: grid step in the clip.
    */
   private Result seek(final int step)
   {
      if (live.link() == null)
         return Result.fail("Start comping a clip first");
      if (live.link().source() != Take.Source.ARRANGER)
         return Result.fail("Bitwig does not let extensions move the playhead in launcher clips");
      final double clipStart = arrangerClipStart();
      if (Double.isNaN(clipStart))
         return Result.fail("Play the clip once, so MIDI Comp learns where it starts");
      transport.playStartPosition().set(clipStart + Math.max(0, step) * stepSize);
      if (transport.isPlaying().get())
         transport.jumpToPlayStartPosition();
      return Result.ok("Playhead moved");
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

package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveSyncTest
{
   private static final double STEP = 1.0 / 128;

   private FakeClip fake;
   private EditableClip clip;
   private CompModel model;
   private LiveSync live;
   private Take a;
   private Take b;

   @BeforeEach
   void setUp()
   {
      fake = new FakeClip();
      clip = new EditableClip(fake.clip, STEP);
      model = new CompModel();
      live = new LiveSync();

      a = model.addTake("A", Take.Source.LAUNCHER, 512, List.of(
         CompModelTest.note(0, 60, 0.5), CompModelTest.note(128, 62, 0.5),
         CompModelTest.note(256, 64, 0.5), CompModelTest.note(384, 65, 0.5)));
      b = model.addTake("B", Take.Source.LAUNCHER, 512, List.of(
         CompModelTest.note(0, 48, 0.5), CompModelTest.note(128, 50, 0.5),
         CompModelTest.note(256, 52, 0.5), CompModelTest.note(384, 53, 0.5)));

      // Existing content of the comp clip before linking.
      fake.userAdd(64, 70, 0.3, 0.25);
      settle();
   }

   /** Delivers host notifications and waits until the mirror counts as settled. */
   private void settle()
   {
      fake.deliver();
      try
      {
         Thread.sleep(LiveSync.SETTLE_MILLIS + 30);
      }
      catch (final InterruptedException e)
      {
         Thread.currentThread().interrupt();
      }
   }

   private LiveSync.Tick tick()
   {
      return live.tick(clip, true, this::rendered, model.length());
   }

   private List<NoteData> rendered()
   {
      final List<NoteData> notes = new ArrayList<>();
      for (final CompModel.CompNote n : model.render(STEP))
         notes.add(n.note());
      return notes;
   }

   private void link()
   {
      live.link(new ClipRef(Take.Source.LAUNCHER, 0, "Keys", 5, "Keys / Comp"), clip.notes().all());
   }

   @Test
   void firstSyncReplacesClipAndIsRevertible()
   {
      link();
      final LiveSync.Tick t = tick();
      assertNotNull(t.firstWrite());
      settle();

      assertTrue(clip.contains(0, 512, rendered()));
      assertEquals(4, fake.cells.size());
      assertEquals(4, live.expected().size());
      assertFalse(tick().wrote());

      clip.revert(t.firstWrite());
      settle();
      assertEquals(1, fake.cells.size());
      assertEquals(0.3, fake.velocityAt(64, 70), 1e-9);
   }

   @Test
   void editsWriteOnlyTheDifference()
   {
      link();
      tick();
      settle();

      fake.writeCalls = 0;
      model.assign(128, 256, b.id());
      live.markDirty();
      final LiveSync.Tick t = tick();
      assertTrue(t.wrote());
      assertNull(t.firstWrite());
      settle();

      assertTrue(clip.contains(0, 512, rendered()));
      // One note cleared and one created; created notes also receive their attributes.
      assertTrue(fake.writeCalls < 40, "write calls: " + fake.writeCalls);
      assertFalse(Double.isNaN(fake.velocityAt(128, 50)));
      assertTrue(Double.isNaN(fake.velocityAt(128, 62)));
   }

   @Test
   void outsideEditsAreOverwrittenOnTheNextChange()
   {
      link();
      tick();
      settle();

      fake.userAdd(200, 72, 0.9, 0.5);
      settle();
      // Nothing changed in the comp, so nothing is written.
      assertFalse(tick().wrote());
      assertFalse(Double.isNaN(fake.velocityAt(200, 72)));

      model.assign(0, 128, b.id());
      live.markDirty();
      assertTrue(tick().wrote());
      settle();
      assertTrue(Double.isNaN(fake.velocityAt(200, 72)));
      assertTrue(clip.contains(0, 512, rendered()));
   }

   @Test
   void recordingIsReplacedByTheCompAfterwards()
   {
      link();
      tick();
      settle();

      live.holdForRecording();
      fake.userAdd(100, 72, 0.9, 0.25);
      settle();

      assertTrue(tick().wrote());
      settle();
      assertTrue(Double.isNaN(fake.velocityAt(100, 72)));
      assertTrue(clip.contains(0, Integer.MAX_VALUE, rendered()));
   }

   @Test
   void unavailableClipIsNotTouched()
   {
      link();
      final LiveSync.Tick t = live.tick(clip, false, this::rendered, model.length());
      assertFalse(t.wrote());
      assertEquals(1, fake.cells.size());
      assertTrue(live.isPending());
   }

   @Test
   void unsettledClipIsNotTouched()
   {
      link();
      fake.userAdd(300, 40, 0.5, 0.1);
      fake.deliver();
      assertFalse(tick().wrote());
      assertTrue(live.isPending());
   }

   @Test
   void writesStopAtTheGivenLength()
   {
      link();
      live.tick(clip, true, () -> {
         final List<NoteData> notes = new ArrayList<>();
         for (final NoteData n : rendered())
            if (n.x() < 200)
               notes.add(n);
         return notes;
      }, 200);
      settle();
      assertTrue(fake.cells.keySet().stream().allMatch(cell -> (cell >> 11) < 200));
   }

   @Test
   void shorteningTheCompClearsTheTail()
   {
      link();
      tick();
      settle();
      assertTrue(model.removeTake(a.id()));
      assertTrue(model.removeTake(b.id()));
      live.markDirty();
      tick();
      settle();
      assertEquals(0, fake.cells.size());
   }

   @Test
   void aNoteReportedAfterTheWriteIsRemovedByTheCheck()
   {
      link();
      assertTrue(tick().wrote());
      // The host reports a note the diff did not know about, right after the write.
      fake.userAdd(200, 72, 0.9, 0.5);
      settle();

      final LiveSync.Tick check = tick();
      assertFalse(check.wrote());
      assertEquals(1, check.leftover());
      assertTrue(live.isPending());

      assertTrue(tick().wrote());
      settle();
      assertTrue(Double.isNaN(fake.velocityAt(200, 72)));
      final LiveSync.Tick again = tick();
      assertEquals(0, again.leftover());
      assertFalse(live.isPending());
   }

   @Test
   void editsAfterTheCheckStayUntilTheNextChange()
   {
      link();
      tick();
      settle();
      assertEquals(0, tick().leftover()); // verified

      fake.userAdd(200, 72, 0.9, 0.5);
      settle();
      assertFalse(tick().wrote());
      assertFalse(Double.isNaN(fake.velocityAt(200, 72)));
   }
}

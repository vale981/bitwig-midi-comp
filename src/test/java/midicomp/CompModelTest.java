package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompModelTest
{
   private static final double STEP = 1.0 / 128;

   static NoteData note(final int x, final int key, final double durationBeats)
   {
      return new NoteData(0, x, key, 0.8, 0.5, 0, durationBeats, 0, 0, 0, 0, 0, false, 1, false,
         null, false, 1, 1, false, 1, 0, 0.8, 0, false, false);
   }

   private static Take add(final CompModel m, final String name, final NoteData... notes)
   {
      return m.addTake(name, Take.Source.LAUNCHER, 512, List.of(notes));
   }

   @Test
   void firstTakeCoversWholeLength()
   {
      final CompModel m = new CompModel();
      final Take a = add(m, "A");
      assertEquals(List.of(new CompModel.Segment(0, 512, a.id())), m.segments());
      add(m, "B");
      assertEquals(1, m.segments().size());
   }

   @Test
   void assignSplitsAndMerges()
   {
      final CompModel m = new CompModel();
      final Take a = add(m, "A");
      final Take b = add(m, "B");

      m.assign(128, 256, b.id());
      assertEquals(List.of(
         new CompModel.Segment(0, 128, a.id()),
         new CompModel.Segment(128, 256, b.id()),
         new CompModel.Segment(256, 512, a.id())), m.segments());

      // Reassigning the middle to A merges everything back into one segment.
      m.assign(128, 256, a.id());
      assertEquals(List.of(new CompModel.Segment(0, 512, a.id())), m.segments());

      m.assign(100, 300, -1);
      assertEquals(List.of(
         new CompModel.Segment(0, 100, a.id()),
         new CompModel.Segment(300, 512, a.id())), m.segments());
   }

   @Test
   void undoRestoresPreviousSegments()
   {
      final CompModel m = new CompModel();
      final Take a = add(m, "A");
      final Take b = add(m, "B");
      m.assign(0, 128, b.id());
      assertTrue(m.undo());
      assertEquals(List.of(new CompModel.Segment(0, 512, a.id())), m.segments());
   }

   @Test
   void removingTakeDropsItsSegmentsAndUndoSkipsThem()
   {
      final CompModel m = new CompModel();
      final Take a = add(m, "A");
      final Take b = add(m, "B");
      m.assign(0, 128, b.id());
      assertTrue(m.removeTake(b.id()));
      assertEquals(List.of(new CompModel.Segment(128, 512, a.id())), m.segments());

      // The previous state referenced B only in 0..128; restoring it must not resurrect B.
      assertTrue(m.undo());
      for (final CompModel.Segment s : m.segments())
         assertEquals(a.id(), s.takeId());
      assertFalse(m.removeTake(b.id()));
   }

   @Test
   void renderPicksNotesBySegmentAndCutsAtNextSegment()
   {
      final CompModel m = new CompModel();
      // A: note at step 0 lasting 2 beats (256 steps), note at 300.
      final Take a = add(m, "A", note(0, 60, 2.0), note(300, 62, 0.5));
      // B: note at 128 and at 400.
      final Take b = add(m, "B", note(128, 64, 0.25), note(400, 65, 0.25));

      m.assign(128, 256, b.id());
      final List<CompModel.CompNote> comp = m.render(STEP);

      assertEquals(3, comp.size());
      final CompModel.CompNote first = comp.get(0);
      assertEquals(a.id(), first.takeId());
      assertEquals(0, first.note().x());
      // Cut where B's segment starts: 128 steps = 1 beat.
      assertEquals(1.0, first.note().duration(), 1e-9);

      assertEquals(b.id(), comp.get(1).takeId());
      assertEquals(128, comp.get(1).note().x());

      assertEquals(a.id(), comp.get(2).takeId());
      assertEquals(300, comp.get(2).note().x());
      assertEquals(0.5, comp.get(2).note().duration(), 1e-9);
   }

   @Test
   void silentRangeProducesNoNotes()
   {
      final CompModel m = new CompModel();
      add(m, "A", note(10, 60, 0.1), note(200, 60, 0.1));
      m.assign(0, 100, -1);
      final List<CompModel.CompNote> comp = m.render(STEP);
      assertEquals(1, comp.size());
      assertEquals(200, comp.get(0).note().x());
   }

   @Test
   void stateJsonContainsTakesSegmentsAndComp()
   {
      final CompModel m = new CompModel();
      add(m, "Take \"1\"", note(0, 60, 0.5));
      final String json = StateJson.build(7, m, STEP, 512, "Song",
         new StateJson.Selection("Bass / Clip", null), null, false, "ok", List.of("event"));
      assertTrue(json.startsWith("{\"v\":7,"), json);
      assertTrue(json.contains("\"name\":\"Take \\\"1\\\"\""), json);
      assertTrue(json.contains("\"segments\":[[0,512,1]]"), json);
      assertTrue(json.contains("\"comp\":[[0,60,64,0.8,0,1,0]]"), json); // channel last
      assertTrue(json.contains("\"arrangerSel\":null"), json);
      assertTrue(json.contains("\"session\":null"), json);
   }

   @Test
   void takesCanBeRenamed()
   {
      final CompModel model = new CompModel();
      final Take take = model.addTake("Pass 1", Take.Source.ARRANGER, 512, List.of(note(0, 60, 0.5)));
      assertTrue(model.renameTake(take.id(), "Chorus"));
      assertEquals("Chorus", model.takes().get(0).name());
      assertEquals(take.notes(), model.takes().get(0).notes());
      assertFalse(model.renameTake(take.id() + 100, "Nope"));
   }

   @Test
   void theRevisionCountsChanges()
   {
      final CompModel model = new CompModel();
      final long start = model.revision();
      final Take take = model.addTake("Pass 1", Take.Source.ARRANGER, 512, List.of(note(0, 60, 0.5)));
      final long added = model.revision();
      assertTrue(added > start);
      model.assign(0, 256, -1);
      assertTrue(model.revision() > added);
      final long assigned = model.revision();
      model.renderControls();
      model.render(1.0 / 128);
      assertEquals(assigned, model.revision()); // reading does not count
      model.renameTake(take.id(), "Verse");
      assertTrue(model.revision() > assigned);
   }
}

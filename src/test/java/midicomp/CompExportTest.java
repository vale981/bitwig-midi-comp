package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bitwig.extension.controller.api.NoteOccurrence;

class CompExportTest
{
   private static final double STEP = 1.0 / 128;
   private static final ClipRef CLIP = new ClipRef(Take.Source.ARRANGER, 2, "Keys", -1, "Keys (arranger)");

   /** A note with every attribute set to a distinct value. */
   private static NoteData detailed(final int x)
   {
      final NoteOccurrence occurrence = NoteOccurrence.values()[1];
      return new NoteData(3, x, 61, 0.7, 0.4, 0.1, 0.75, -0.25, 0.3, 0.6, -0.5, 2.0, true, 0.5,
         true, occurrence, true, 4, 5, true, 3, 0.2, 0.9, -0.3, true, false);
   }

   @Test
   void roundTripKeepsEveryAttributeAndTheSegments()
   {
      final CompModel model = new CompModel();
      model.addTake("Pass \"1\"", Take.Source.ARRANGER, 1024, List.of(detailed(100)));
      final Take second = model.addTake("Pass 2", Take.Source.LAUNCHER, 1024,
         List.of(CompModelTest.note(10, 60, 0.5)));
      model.assign(512, 1024, second.id());

      final String json = CompExport.write(model, CLIP, "Song", STEP);
      final CompExport.Imported read = CompExport.read(json, STEP);

      assertEquals(2, read.takes().size());
      assertEquals("Pass \"1\"", read.takes().get(0).name());
      assertEquals(Take.Source.LAUNCHER, read.takes().get(1).source());
      final NoteData original = detailed(100);
      final NoteData restored = read.takes().get(0).notes().get(0);
      assertTrue(original.sameAs(restored), restored.toString());
      assertEquals(original.channel(), restored.channel());

      assertEquals(2, read.segments().size());
      assertEquals(List.of(0, 512, 0), List.of(read.segments().get(0)[0],
         read.segments().get(0)[1], read.segments().get(0)[2]));
      assertEquals(1, read.segments().get(1)[2]);

      final CompModel restoredModel = new CompModel();
      restoredModel.restore(read.takes(), read.segments());
      assertEquals(model.render(STEP).size(), restoredModel.render(STEP).size());
      assertTrue(!restoredModel.canUndo());
   }

   @Test
   void positionsAreRescaledToTheCurrentResolution()
   {
      final CompModel model = new CompModel();
      model.addTake("A", Take.Source.ARRANGER, 512, List.of(CompModelTest.note(256, 60, 0.5)));
      final String json = CompExport.write(model, CLIP, "Song", 1.0 / 128);

      final CompExport.Imported read = CompExport.read(json, 1.0 / 64);
      assertEquals(256, read.takes().get(0).length());
      assertEquals(128, read.takes().get(0).notes().get(0).x());
      assertEquals(0.5, read.takes().get(0).notes().get(0).duration(), 1e-9);
      assertEquals(256, read.segments().get(0)[1]);
   }

   @Test
   void rejectsOtherDocuments()
   {
      assertThrows(IllegalArgumentException.class, () -> CompExport.read("{\"format\":99}", STEP));
      assertThrows(IllegalArgumentException.class, () -> CompExport.read("[1,2]", STEP));
      assertThrows(MiniJson.ParseException.class, () -> CompExport.read("{broken", STEP));
   }

   @Test
   @SuppressWarnings("unchecked")
   void miniJsonParsesNestedValuesAndEscapes()
   {
      final Object parsed = MiniJson.parse(
         " {\"a\": [1, -2.5e1, true, null], \"s\": \"x\\\"y\\u00e4\\n\", \"o\": {}} ");
      final Map<String, Object> map = (Map<String, Object>) parsed;
      assertEquals(List.of(1.0, -25.0, true), ((List<Object>) map.get("a")).subList(0, 3));
      assertNull(((List<Object>) map.get("a")).get(3));
      assertEquals("x\"yä\n", map.get("s"));
      assertTrue(((Map<String, Object>) map.get("o")).isEmpty());
      assertThrows(MiniJson.ParseException.class, () -> MiniJson.parse("{\"a\":1} x"));
   }
}

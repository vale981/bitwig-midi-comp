package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class FingerprintTest
{
   @Test
   void sameNotesInAnyOrderAndResolutionMatch()
   {
      final List<NoteData> a = List.of(CompModelTest.note(0, 60, 0.5), CompModelTest.note(128, 64, 0.5));
      final List<NoteData> b = List.of(CompModelTest.note(256, 64, 0.9), CompModelTest.note(0, 60, 0.2));
      // b at twice the resolution, other velocities, other order.
      assertEquals(Fingerprint.of(a, 1.0 / 128), Fingerprint.of(b, 1.0 / 256));
      assertEquals(16, Fingerprint.of(a, 1.0 / 128).length());
   }

   @Test
   void otherNotesDiffer()
   {
      final List<NoteData> a = List.of(CompModelTest.note(0, 60, 0.5));
      final List<NoteData> b = List.of(CompModelTest.note(1, 60, 0.5));
      assertNotEquals(Fingerprint.of(a, 1.0 / 128), Fingerprint.of(b, 1.0 / 128));
      assertEquals("", Fingerprint.of(List.of(), 1.0 / 128));
   }

   @Test
   void versionTitlesCountUp()
   {
      assertEquals("Lead v2", MidiCompExtension.nextVersionTitle("Lead"));
      assertEquals("Lead v3", MidiCompExtension.nextVersionTitle("Lead v2"));
      assertEquals("Lead v10", MidiCompExtension.nextVersionTitle("Lead v9 "));
      assertEquals("Comp v2", MidiCompExtension.nextVersionTitle(""));
   }
}

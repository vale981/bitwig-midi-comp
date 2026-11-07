package midicomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;

/**
 * A cursor clip with a local note mirror and support for writing complete notes.
 *
 * :meth:`Clip.setStep` only sets velocity and duration. The remaining attributes are queued and
 * applied when the host reports the new note through the note-step observer.
 */
final class EditableClip
{
   private final Clip clip;
   private final NoteMirror notes = new NoteMirror();
   private final Map<Long, NoteData> pendingAttributes = new HashMap<>();
   private long lastActivity = System.nanoTime();
   private long version;

   /**
    * Configures ``clip`` for full-range note access and registers the observer.
    *
    * Args:
    *    clip: a cursor clip created with a grid height of 128.
    *    stepSize: length of one grid step in beats.
    */
   EditableClip(final Clip clip, final double stepSize)
   {
      this.clip = clip;
      clip.setStepSize(stepSize);
      clip.scrollToKey(0);
      clip.scrollToStep(0);
      clip.addNoteStepObserver(this::onStep);
      clip.exists().markInterested();
      clip.getPlayStop().markInterested();
   }

   Clip clip()
   {
      return clip;
   }

   NoteMirror notes()
   {
      return notes;
   }

   boolean exists()
   {
      return clip.exists().get();
   }

   /**
    * Changes the grid step size and drops all cached state; the host re-reports every note.
    *
    * Args:
    *    stepSize: length of one grid step in beats.
    */
   void setStepSize(final double stepSize)
   {
      notes.clear();
      pendingAttributes.clear();
      clip.setStepSize(stepSize);
   }

   /**
    * Makes the notes starting in ``[from, to)`` equal to ``desired`` with minimal edits.
    *
    * Notes that are no longer wanted are cleared, new notes are created, and notes in the same
    * cell with different attributes are updated in place.
    *
    * Args:
    *    from: first step of the range, inclusive.
    *    to: last step of the range, exclusive.
    *    desired: the notes the range should contain.
    *
    * Returns:
    *    The operation, for :meth:`revert`. Updated notes appear in both ``removed`` (old
    *    attributes) and ``added`` (new attributes).
    */
   CompOperation sync(final int from, final int to, final List<NoteData> desired)
   {
      final Map<Long, NoteData> wanted = new HashMap<>();
      for (final NoteData n : desired)
         wanted.put(n.cellKey(), n);

      final List<NoteData> removed = new ArrayList<>();
      final List<NoteData> added = new ArrayList<>();
      pendingAttributes.clear();

      for (final NoteData current : notes.startingIn(from, to))
      {
         if (!wanted.containsKey(current.cellKey()))
         {
            clip.clearStep(current.channel(), current.x(), current.key());
            removed.add(current);
         }
      }
      for (final NoteData want : wanted.values())
      {
         final NoteData current = notes.get(want.cellKey());
         if (current == null)
         {
            write(want);
            added.add(want);
         }
         else if (!current.sameAs(want))
         {
            want.applyTo(clip.getStep(want.channel(), want.x(), want.key()));
            removed.add(current);
            added.add(want);
         }
      }
      return new CompOperation(removed, added);
   }

   /** Returns whether no note updates arrived for ``quietMillis``. */
   boolean isSettled(final long quietMillis)
   {
      return System.nanoTime() - lastActivity > quietMillis * 1_000_000L;
   }

   /** Records that the clip's identity or content may be changing. */
   void touch()
   {
      lastActivity = System.nanoTime();
      version++;
   }

   /** Returns a counter that changes whenever the clip's identity or notes may have changed. */
   long version()
   {
      return version;
   }

   /**
    * Returns whether the notes starting in ``[from, to)`` are exactly ``expected``.
    *
    * Args:
    *    from: first step, inclusive.
    *    to: last step, exclusive.
    *    expected: the notes the range should contain.
    */
   boolean contains(final int from, final int to, final List<NoteData> expected)
   {
      final List<NoteData> actual = notes.startingIn(from, to);
      if (actual.size() != expected.size())
         return false;
      for (final NoteData want : expected)
      {
         final NoteData have = notes.get(want.cellKey());
         if (have == null || !have.sameAs(want))
            return false;
      }
      return true;
   }

   /**
    * Undoes an operation returned by :meth:`sync` on this clip.
    *
    * Args:
    *    op: the operation to undo.
    */
   void revert(final CompOperation op)
   {
      pendingAttributes.clear();
      for (final NoteData n : op.added())
         clip.clearStep(n.channel(), n.x(), n.key());
      for (final NoteData n : op.removed())
         write(n);
   }

   private void write(final NoteData note)
   {
      pendingAttributes.put(note.cellKey(), note);
      clip.setStep(note.channel(), note.x(), note.key(), note.midiVelocity(), note.duration());
   }

   private void onStep(final NoteStep step)
   {
      touch();
      notes.update(step);
      if (step.state() != NoteStep.State.NoteOn)
         return;

      final NoteData pending = pendingAttributes.remove(
         NoteData.cellKey(step.channel(), step.x(), step.y()));
      if (pending != null)
         pending.applyTo(step);
   }
}

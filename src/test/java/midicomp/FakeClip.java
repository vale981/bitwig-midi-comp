package midicomp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import com.bitwig.extension.callback.NoteStepChangedCallback;
import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;

/**
 * In-memory stand-in for a Bitwig cursor clip.
 *
 * Notes live in a map keyed by cell. Like the host, change notifications are queued and only
 * delivered by :meth:`deliver`. Every other API method returns an inert proxy or a default value.
 */
final class FakeClip
{
   /** Attributes of one stored note, keyed by getter name (``velocity``, ``isMuted``, ...). */
   private static final class Cell
   {
      final int channel;
      final int x;
      final int y;
      final Map<String, Object> attrs = new HashMap<>();

      Cell(final int channel, final int x, final int y)
      {
         this.channel = channel;
         this.x = x;
         this.y = y;
      }
   }

   final Map<Long, Cell> cells = new HashMap<>();
   private final Queue<NoteStep> pending = new ArrayDeque<>();
   private NoteStepChangedCallback observer;
   final Clip clip = proxy(Clip.class, this::clipCall);
   int writeCalls;

   /** Delivers all queued note notifications. */
   void deliver()
   {
      NoteStep step;
      while ((step = pending.poll()) != null)
         observer.noteStepChanged(step);
   }

   /** Creates a note as if the user had drawn it in Bitwig. */
   void userAdd(final int x, final int key, final double velocity, final double duration)
   {
      final Cell cell = new Cell(0, x, key);
      cell.attrs.put("velocity", velocity);
      cell.attrs.put("duration", duration);
      cells.put(NoteData.cellKey(0, x, key), cell);
      pending.add(step(cell, NoteStep.State.NoteOn));
   }

   /** Deletes a note as if the user had removed it in Bitwig. */
   void userRemove(final int x, final int key)
   {
      final Cell cell = cells.remove(NoteData.cellKey(0, x, key));
      if (cell != null)
         pending.add(step(cell, NoteStep.State.Empty));
   }

   double velocityAt(final int x, final int key)
   {
      final Cell cell = cells.get(NoteData.cellKey(0, x, key));
      return cell == null ? Double.NaN : (Double) cell.attrs.getOrDefault("velocity", 0.0);
   }

   private Object clipCall(final Method m, final Object[] args)
   {
      switch (m.getName())
      {
         case "addNoteStepObserver" ->
         {
            observer = (NoteStepChangedCallback) args[0];
            return null;
         }
         case "setStep" ->
         {
            if (args.length != 5)
               throw new UnsupportedOperationException("setStep without channel");
            writeCalls++;
            final int ch = (Integer) args[0];
            final int x = (Integer) args[1];
            final int y = (Integer) args[2];
            final Cell cell = new Cell(ch, x, y);
            cell.attrs.put("velocity", (Integer) args[3] / 127.0);
            cell.attrs.put("duration", args[4]);
            cells.put(NoteData.cellKey(ch, x, y), cell);
            pending.add(step(cell, NoteStep.State.NoteOn));
            return null;
         }
         case "clearStep" ->
         {
            if (args.length != 3)
               throw new UnsupportedOperationException("clearStep without channel");
            writeCalls++;
            final Cell cell = cells.remove(NoteData.cellKey(
               (Integer) args[0], (Integer) args[1], (Integer) args[2]));
            if (cell != null)
               pending.add(step(cell, NoteStep.State.Empty));
            return null;
         }
         case "getStep" ->
         {
            final Cell cell = cells.get(NoteData.cellKey(
               (Integer) args[0], (Integer) args[1], (Integer) args[2]));
            return cell == null ? null : step(cell, NoteStep.State.NoteOn);
         }
         default ->
         {
            return inert(m.getReturnType());
         }
      }
   }

   private NoteStep step(final Cell cell, final NoteStep.State state)
   {
      return proxy(NoteStep.class, (m, args) -> {
         final String name = m.getName();
         switch (name)
         {
            case "x" -> { return cell.x; }
            case "y" -> { return cell.y; }
            case "channel" -> { return cell.channel; }
            case "state" -> { return state; }
            case "setRecurrence" ->
            {
               writeCalls++;
               cell.attrs.put("recurrenceLength", args[0]);
               cell.attrs.put("recurrenceMask", args[1]);
               pending.add(step(cell, NoteStep.State.NoteOn));
               return null;
            }
            default ->
            {
               if (name.startsWith("set"))
               {
                  writeCalls++;
                  final String attr = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                  cell.attrs.put(attr, args[0]);
                  pending.add(step(cell, NoteStep.State.NoteOn));
                  return null;
               }
               final Object value = cell.attrs.get(name);
               return value != null ? value : inert(m.getReturnType());
            }
         }
      });
   }

   private static Object inert(final Class<?> type)
   {
      if (type == void.class)
         return null;
      if (type == boolean.class)
         return false;
      if (type == int.class)
         return 0;
      if (type == double.class)
         return 0.0;
      if (type == long.class)
         return 0L;
      if (type.isInterface())
         return proxy(type, (m, args) -> inert(m.getReturnType()));
      return null;
   }

   @SuppressWarnings("unchecked")
   private static <T> T proxy(final Class<T> type, final Handler handler)
   {
      final InvocationHandler ih = (p, m, args) -> {
         if (m.getDeclaringClass() == Object.class)
            return switch (m.getName())
            {
               case "hashCode" -> System.identityHashCode(p);
               case "equals" -> p == args[0];
               default -> type.getSimpleName() + "@fake";
            };
         return handler.call(m, args == null ? new Object[0] : args);
      };
      return (T) Proxy.newProxyInstance(FakeClip.class.getClassLoader(), new Class<?>[] { type }, ih);
   }

   @FunctionalInterface
   private interface Handler
   {
      Object call(Method m, Object[] args) throws Throwable;
   }
}

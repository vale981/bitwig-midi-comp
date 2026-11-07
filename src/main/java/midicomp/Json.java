package midicomp;

/** Minimal JSON encoding helpers. */
final class Json
{
   private Json()
   {
   }

   /**
    * Returns ``value`` as a JSON string literal, or ``null`` if ``value`` is ``null``.
    *
    * Args:
    *    value: text to encode.
    */
   static String quote(final String value)
   {
      if (value == null)
         return "null";
      final StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
      for (int i = 0; i < value.length(); i++)
      {
         final char c = value.charAt(i);
         switch (c)
         {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default ->
            {
               if (c < 0x20)
                  sb.append(String.format("\\u%04x", (int) c));
               else
                  sb.append(c);
            }
         }
      }
      return sb.append('"').toString();
   }

   /**
    * Returns a finite number formatted for JSON; non-finite values become ``0``.
    *
    * Args:
    *    value: number to encode.
    */
   static String number(final double value)
   {
      if (!Double.isFinite(value))
         return "0";
      if (value == Math.rint(value) && Math.abs(value) < 1e15)
         return Long.toString((long) value);
      return Double.toString(value);
   }

   /**
    * Returns ``{"ok": ..., "message": ...}``.
    *
    * Args:
    *    ok: whether the command succeeded.
    *    message: text shown to the user.
    */
   static String reply(final boolean ok, final String message)
   {
      return "{\"ok\":" + ok + ",\"message\":" + quote(message) + "}";
   }
}

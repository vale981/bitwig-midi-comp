package midicomp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for request bodies.
 *
 * Produces :class:`Map` (objects, insertion ordered), :class:`List` (arrays), :class:`String`,
 * :class:`Double` (all numbers), :class:`Boolean` and ``null``.
 */
final class MiniJson
{
   /** Raised for malformed input. */
   static final class ParseException extends RuntimeException
   {
      private static final long serialVersionUID = 1L;

      ParseException(final String message, final int position)
      {
         super(message + " at offset " + position);
      }
   }

   private final String text;
   private int pos;

   private MiniJson(final String text)
   {
      this.text = text;
   }

   /**
    * Parses a JSON document.
    *
    * Args:
    *    text: the document.
    *
    * Returns:
    *    The parsed value.
    *
    * Raises:
    *    ParseException: if ``text`` is not valid JSON.
    */
   static Object parse(final String text)
   {
      final MiniJson parser = new MiniJson(text);
      final Object value = parser.value();
      parser.skipWhitespace();
      if (parser.pos != text.length())
         throw new ParseException("Trailing characters", parser.pos);
      return value;
   }

   /** Returns ``map[key]`` as an object, raising if it is missing or of another type. */
   @SuppressWarnings("unchecked")
   static Map<String, Object> object(final Map<String, Object> map, final String key)
   {
      if (map.get(key) instanceof Map<?, ?> m)
         return (Map<String, Object>) m;
      throw new IllegalArgumentException("Expected object '" + key + "'");
   }

   /** Returns ``map[key]`` as a list, raising if it is missing or of another type. */
   @SuppressWarnings("unchecked")
   static List<Object> array(final Map<String, Object> map, final String key)
   {
      if (map.get(key) instanceof List<?> l)
         return (List<Object>) l;
      throw new IllegalArgumentException("Expected array '" + key + "'");
   }

   /** Returns ``map[key]`` as a number, or ``fallback`` if it is missing. */
   static double number(final Map<String, Object> map, final String key, final double fallback)
   {
      return map.get(key) instanceof Double d ? d : fallback;
   }

   /** Returns ``map[key]`` as a boolean, or ``fallback`` if it is missing. */
   static boolean bool(final Map<String, Object> map, final String key, final boolean fallback)
   {
      return map.get(key) instanceof Boolean b ? b : fallback;
   }

   /** Returns ``map[key]`` as a string, or ``fallback`` if it is missing. */
   static String string(final Map<String, Object> map, final String key, final String fallback)
   {
      return map.get(key) instanceof String s ? s : fallback;
   }

   private Object value()
   {
      skipWhitespace();
      if (pos >= text.length())
         throw new ParseException("Unexpected end", pos);
      final char c = text.charAt(pos);
      return switch (c)
      {
         case '{' -> objectValue();
         case '[' -> arrayValue();
         case '"' -> stringValue();
         case 't' -> literal("true", Boolean.TRUE);
         case 'f' -> literal("false", Boolean.FALSE);
         case 'n' -> literal("null", null);
         default -> numberValue();
      };
   }

   private Map<String, Object> objectValue()
   {
      final Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (peek() == '}')
      {
         pos++;
         return map;
      }
      while (true)
      {
         skipWhitespace();
         if (peek() != '"')
            throw new ParseException("Expected key", pos);
         final String key = stringValue();
         skipWhitespace();
         expect(':');
         map.put(key, value());
         skipWhitespace();
         if (peek() == ',')
         {
            pos++;
            continue;
         }
         expect('}');
         return map;
      }
   }

   private List<Object> arrayValue()
   {
      final List<Object> list = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (peek() == ']')
      {
         pos++;
         return list;
      }
      while (true)
      {
         list.add(value());
         skipWhitespace();
         if (peek() == ',')
         {
            pos++;
            continue;
         }
         expect(']');
         return list;
      }
   }

   private String stringValue()
   {
      expect('"');
      final StringBuilder sb = new StringBuilder();
      while (true)
      {
         if (pos >= text.length())
            throw new ParseException("Unterminated string", pos);
         final char c = text.charAt(pos++);
         if (c == '"')
            return sb.toString();
         if (c != '\\')
         {
            sb.append(c);
            continue;
         }
         if (pos >= text.length())
            throw new ParseException("Unterminated escape", pos);
         final char e = text.charAt(pos++);
         switch (e)
         {
            case '"', '\\', '/' -> sb.append(e);
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' ->
            {
               if (pos + 4 > text.length())
                  throw new ParseException("Short unicode escape", pos);
               sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
               pos += 4;
            }
            default -> throw new ParseException("Bad escape", pos);
         }
      }
   }

   private Double numberValue()
   {
      final int start = pos;
      while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0)
         pos++;
      if (start == pos)
         throw new ParseException("Unexpected character", pos);
      try
      {
         return Double.parseDouble(text.substring(start, pos));
      }
      catch (final NumberFormatException e)
      {
         throw new ParseException("Bad number", start);
      }
   }

   private Object literal(final String word, final Object value)
   {
      if (!text.startsWith(word, pos))
         throw new ParseException("Expected " + word, pos);
      pos += word.length();
      return value;
   }

   private void expect(final char c)
   {
      if (peek() != c)
         throw new ParseException("Expected '" + c + "'", pos);
      pos++;
   }

   private char peek()
   {
      return pos < text.length() ? text.charAt(pos) : '\0';
   }

   private void skipWhitespace()
   {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos)))
         pos++;
   }
}

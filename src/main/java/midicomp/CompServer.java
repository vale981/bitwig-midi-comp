package midicomp;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Loopback HTTP API for companion applications. See ``API.md`` for the protocol.
 *
 * Every request must carry the ``X-Token`` header, and the ``Host`` header must name the loopback
 * address (this blocks DNS-rebinding requests from web pages).
 *
 * This class never touches the Bitwig API. State is published by the controller thread through
 * :meth:`publish`; commands are handed to a :class:`CommandSink`.
 */
final class CompServer
{
   /** Receives commands on a server thread; must complete the future on the controller thread. */
   interface CommandSink
   {
      /**
       * Args:
       *    op: command name, the path segment after ``/api/v1/``.
       *    params: query parameters.
       *    body: request body (empty for GET requests).
       */
      CompletableFuture<String> submit(String op, Map<String, String> params, String body);
   }

   static final int API_VERSION = 1;
   private static final String PREFIX = "/api/v" + API_VERSION + "/";
   private static final int COMMAND_TIMEOUT_SECONDS = 5;
   private static final int MAX_WAIT_SECONDS = 30;
   private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
   /** Read-only commands answered on the controller thread but requested with GET. */
   private static final Set<String> GET_COMMANDS = Set.of("export");
   private volatile String playhead = "{\"playing\":false,\"step\":-1,\"stepsPerSecond\":0}";

   private final HttpServer server;
   private final ExecutorService executor;
   private final CommandSink sink;
   private final String info;
   private final String token;
   private final Set<String> allowedHosts;

   private final Object stateLock = new Object();
   private String state = "{}";
   private long version = -1;

   private CompServer(final HttpServer server, final CommandSink sink, final String info)
   {
      this.server = server;
      this.sink = sink;
      this.info = info;

      final byte[] random = new byte[16];
      new SecureRandom().nextBytes(random);
      token = HexFormat.of().formatHex(random);

      final int port = server.getAddress().getPort();
      allowedHosts = Set.of("127.0.0.1:" + port, "localhost:" + port);

      // Long-polling clients each hold a thread while waiting.
      executor = Executors.newFixedThreadPool(8, runnable -> {
         final Thread thread = new Thread(runnable, "midicomp-http");
         thread.setDaemon(true);
         return thread;
      });
      server.setExecutor(executor);
      server.createContext("/", this::handle);
   }

   /**
    * Starts a server on the first free port in ``[firstPort, firstPort + attempts)``.
    *
    * Args:
    *    firstPort: preferred port.
    *    attempts: number of ports to try.
    *    sink: receiver for commands.
    *    info: JSON document served at ``GET /api/v1/info``.
    *
    * Returns:
    *    The running server.
    *
    * Raises:
    *    IOException: if no port could be bound.
    */
   static CompServer start(
      final int firstPort,
      final int attempts,
      final CommandSink sink,
      final String info) throws IOException
   {
      BindException last = null;
      for (int port = firstPort; port < firstPort + attempts; port++)
      {
         try
         {
            final HttpServer http = HttpServer.create(
               new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            final CompServer server = new CompServer(http, sink, info);
            http.start();
            return server;
         }
         catch (final BindException e)
         {
            last = e;
         }
      }
      throw new IOException("No free port in " + firstPort + ".." + (firstPort + attempts - 1), last);
   }

   /** Returns the base URL, e.g. ``http://127.0.0.1:8765``. */
   String baseUrl()
   {
      return "http://127.0.0.1:" + server.getAddress().getPort();
   }

   String token()
   {
      return token;
   }

   /**
    * Replaces the state served at ``/api/v1/state`` and wakes long-polling clients.
    *
    * Args:
    *    json: the state document.
    *    newVersion: version number contained in ``json``.
    */
   /**
    * Replaces the document served at ``/api/v1/playhead``. It changes many times a second, so it
    * is kept out of the state (whose version clients wait on).
    *
    * Args:
    *    json: the playhead document.
    */
   void publishPlayhead(final String json)
   {
      playhead = json;
   }

   void publish(final String json, final long newVersion)
   {
      synchronized (stateLock)
      {
         state = json;
         version = newVersion;
         stateLock.notifyAll();
      }
   }

   void stop()
   {
      synchronized (stateLock)
      {
         stateLock.notifyAll();
      }
      server.stop(0);
      executor.shutdownNow();
   }

   private void handle(final HttpExchange exchange) throws IOException
   {
      try
      {
         route(exchange);
      }
      finally
      {
         exchange.close();
      }
   }

   private void route(final HttpExchange exchange) throws IOException
   {
      final String host = exchange.getRequestHeaders().getFirst("Host");
      if (host == null || !allowedHosts.contains(host))
      {
         send(exchange, 403, Json.reply(false, "Forbidden host"));
         return;
      }
      if (!token.equals(exchange.getRequestHeaders().getFirst("X-Token")))
      {
         send(exchange, 401, Json.reply(false, "Missing or invalid X-Token"));
         return;
      }

      final String path = exchange.getRequestURI().getPath();
      if (!path.startsWith(PREFIX))
      {
         send(exchange, 404, Json.reply(false, "Not found"));
         return;
      }
      final String op = path.substring(PREFIX.length());
      final Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
      final String method = exchange.getRequestMethod();

      if (method.equals("GET") && op.equals("info"))
         send(exchange, 200, info);
      else if (method.equals("GET") && op.equals("playhead"))
         send(exchange, 200, playhead);
      else if (method.equals("GET") && op.equals("state"))
         serveState(exchange, params);
      else if (method.equals("GET") && GET_COMMANDS.contains(op))
         runCommand(exchange, op, params, "");
      else if (method.equals("POST") && !op.isEmpty() && !op.contains("/"))
         runCommand(exchange, op, params, readBody(exchange));
      else
         send(exchange, 404, Json.reply(false, "Not found"));
   }

   private void serveState(final HttpExchange exchange, final Map<String, String> params)
      throws IOException
   {
      final long since = parseLong(params.get("since"), Long.MIN_VALUE);
      final int waitSeconds =
         (int) Math.min(MAX_WAIT_SECONDS, Math.max(0, parseLong(params.get("wait"), 0)));

      String json;
      long current;
      synchronized (stateLock)
      {
         final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
         try
         {
            while (version == since && !executor.isShutdown())
            {
               final long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
               if (remainingMs <= 0)
                  break;
               stateLock.wait(remainingMs);
            }
         }
         catch (final InterruptedException e)
         {
            Thread.currentThread().interrupt();
         }
         json = state;
         current = version;
      }

      if (current == since)
      {
         exchange.sendResponseHeaders(204, -1);
         return;
      }
      send(exchange, 200, json);
   }

   private static String readBody(final HttpExchange exchange) throws IOException
   {
      final byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES)
         throw new IOException("Request body too large");
      return new String(body, StandardCharsets.UTF_8);
   }

   private void runCommand(
      final HttpExchange exchange,
      final String op,
      final Map<String, String> params,
      final String body) throws IOException
   {
      String reply;
      int status = 200;
      try
      {
         reply = sink.submit(op, params, body).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      catch (final TimeoutException e)
      {
         status = 504;
         reply = Json.reply(false, "Bitwig did not respond");
      }
      catch (final InterruptedException e)
      {
         Thread.currentThread().interrupt();
         status = 503;
         reply = Json.reply(false, "Server shutting down");
      }
      catch (final Exception e)
      {
         status = 500;
         reply = Json.reply(false, "Command failed: " + e);
      }
      send(exchange, status, reply);
   }

   private static long parseLong(final String value, final long fallback)
   {
      if (value == null)
         return fallback;
      try
      {
         return Long.parseLong(value);
      }
      catch (final NumberFormatException e)
      {
         return fallback;
      }
   }

   private static Map<String, String> query(final String raw)
   {
      final Map<String, String> result = new HashMap<>();
      if (raw == null || raw.isEmpty())
         return result;
      for (final String pair : raw.split("&"))
      {
         final int eq = pair.indexOf('=');
         final String key = eq < 0 ? pair : pair.substring(0, eq);
         final String value = eq < 0 ? "" : pair.substring(eq + 1);
         result.put(
            URLDecoder.decode(key, StandardCharsets.UTF_8),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      }
      return result;
   }

   private static void send(final HttpExchange exchange, final int status, final String body)
      throws IOException
   {
      final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
   }
}

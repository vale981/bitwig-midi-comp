package midicomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompServerTest
{
   private CompServer server;
   private final AtomicReference<String> lastOp = new AtomicReference<>();
   private final HttpClient client = HttpClient.newHttpClient();

   @BeforeEach
   void start() throws IOException
   {
      server = CompServer.start(18765, 20, (op, params, body) -> {
         lastOp.set(op + " " + params + " " + body);
         // Complete asynchronously, as the controller thread would.
         return CompletableFuture.supplyAsync(() -> Json.reply(true, "did " + op));
      }, "{\"name\":\"MIDI Comp\"}");
      server.publish("{\"v\":1}", 1);
   }

   @AfterEach
   void stop()
   {
      server.stop();
   }

   private HttpResponse<String> get(final String path, final boolean withToken)
      throws IOException, InterruptedException
   {
      final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUrl() + path));
      if (withToken)
         b.header("X-Token", server.token());
      return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
   }

   @Test
   void rejectsMissingToken() throws Exception
   {
      assertEquals(401, get("/api/v1/info", false).statusCode());
   }

   @Test
   void rejectsForeignHostHeader() throws Exception
   {
      // HttpClient does not allow overriding Host, so send the request by hand.
      final int port = URI.create(server.baseUrl()).getPort();
      try (Socket socket = new Socket("127.0.0.1", port))
      {
         final String request = "GET /api/v1/info HTTP/1.1\r\nHost: evil.example:" + port
            + "\r\nX-Token: " + server.token() + "\r\nConnection: close\r\n\r\n";
         socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
         final String response = new String(socket.getInputStream().readAllBytes(),
            StandardCharsets.US_ASCII);
         assertTrue(response.startsWith("HTTP/1.1 403"), response);
      }
   }

   @Test
   void servesInfoAndState() throws Exception
   {
      assertEquals("{\"name\":\"MIDI Comp\"}", get("/api/v1/info", true).body());
      assertEquals("{\"v\":1}", get("/api/v1/state", true).body());
      assertEquals(204, get("/api/v1/state?since=1", true).statusCode());
   }

   @Test
   void longPollReturnsWhenStateChanges() throws Exception
   {
      final long start = System.nanoTime();
      final CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
         HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/v1/state?since=1&wait=10"))
            .header("X-Token", server.token())
            .timeout(Duration.ofSeconds(15))
            .build(),
         HttpResponse.BodyHandlers.ofString());

      Thread.sleep(300);
      server.publish("{\"v\":2}", 2);
      final HttpResponse<String> response = pending.get();
      final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertEquals(200, response.statusCode());
      assertEquals("{\"v\":2}", response.body());
      assertTrue(elapsedMs < 5000, "returned after " + elapsedMs + " ms");
   }

   @Test
   void longPollTimesOutWith204() throws Exception
   {
      assertEquals(204, get("/api/v1/state?since=1&wait=1", true).statusCode());
   }

   @Test
   void postsCommandsWithParameters() throws Exception
   {
      final HttpResponse<String> response = client.send(
         HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/v1/assign?take=2&from=0&to=128"))
            .header("X-Token", server.token())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
         HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals("{\"ok\":true,\"message\":\"did assign\"}", response.body());
      final String op = lastOp.get();
      assertTrue(op.startsWith("assign "), op);
      assertTrue(op.contains("take=2") && op.contains("from=0") && op.contains("to=128"), op);
   }

   @Test
   void passesRequestBodiesAndRoutesExportAsGet() throws Exception
   {
      final HttpResponse<String> post = client.send(
         HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/v1/import"))
            .header("X-Token", server.token())
            .POST(HttpRequest.BodyPublishers.ofString("{\"format\":1}"))
            .build(),
         HttpResponse.BodyHandlers.ofString());
      assertEquals(200, post.statusCode());
      assertTrue(lastOp.get().startsWith("import {} {\"format\":1}"), lastOp.get());

      final HttpResponse<String> export = get("/api/v1/export", true);
      assertEquals(200, export.statusCode());
      assertTrue(lastOp.get().startsWith("export "), lastOp.get());

      // Other commands still need POST.
      assertEquals(404, get("/api/v1/assign", true).statusCode());
   }
}

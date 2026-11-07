package midicomp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Discovery file that tells companion apps where the API listens and which token to send.
 *
 * Written to ``~/.midicomp/endpoint.json`` with owner-only permissions where the file system
 * supports POSIX permissions.
 */
final class EndpointFile
{
   private EndpointFile()
   {
   }

   /** Returns the path of the discovery file. */
   static Path path()
   {
      return Path.of(System.getProperty("user.home"), ".midicomp", "endpoint.json");
   }

   /**
    * Writes the discovery file atomically.
    *
    * Args:
    *    baseUrl: API base URL without path.
    *    token: value for the ``X-Token`` header.
    *
    * Raises:
    *    IOException: if the file cannot be written.
    */
   static void write(final String baseUrl, final String token) throws IOException
   {
      final Path file = path();
      final Path dir = file.getParent();
      final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

      Files.createDirectories(dir);
      if (posix)
         Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));

      final String json = "{\"url\":" + Json.quote(baseUrl)
         + ",\"token\":" + Json.quote(token)
         + ",\"apiVersion\":" + CompServer.API_VERSION
         + ",\"pid\":" + ProcessHandle.current().pid() + "}\n";

      final Path tmp = Files.createTempFile(dir, "endpoint", ".tmp");
      if (posix)
         Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      Files.writeString(tmp, json, StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
   }

   /**
    * Deletes the discovery file if it still belongs to ``token``.
    *
    * Args:
    *    token: token of the server that is shutting down.
    */
   static void delete(final String token)
   {
      try
      {
         final Path file = path();
         if (Files.exists(file) && Files.readString(file).contains(token))
            Files.delete(file);
      }
      catch (final IOException e)
      {
         // Stale files are detected by clients through a failing request.
      }
   }
}

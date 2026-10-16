package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Чистый java.nio отвечает на /health так же, как сервер на Netty. */
class NioServerIT {
    @Test
    void healthUsesTheSameContract() throws Exception {
        Path db = Files.createTempDirectory("nio-it").resolve("feed.db");
        Process sqlite = new ProcessBuilder("sqlite3", db.toString()).start();
        sqlite.getOutputStream().write(Files.readString(Path.of("schema.sql")).getBytes(StandardCharsets.UTF_8));
        sqlite.getOutputStream().close();
        if (sqlite.waitFor() != 0) {
            throw new AssertionError("sqlite3 exited " + sqlite.exitValue());
        }
        NioServer server = NioServer.listen("127.0.0.1", 0, db, Tokens.SECRET);
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().startsWith("{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":"));
        } finally {
            server.close();
        }
    }
}

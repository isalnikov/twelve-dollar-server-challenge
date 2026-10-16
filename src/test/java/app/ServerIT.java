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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
/** Контракт пяти ручек на живом сервере и временной базе со схемой задания. */
class ServerIT {
    private static Server server;
    private static HttpClient http;
    private static String base;
    private static final long EXP = (System.currentTimeMillis() / 1000L) + 3600;

    @BeforeAll
    static void up() throws Exception {
        Path dir = Files.createTempDirectory("feed-it");
        Path db = dir.resolve("feed.db");
        sql(db, Files.readString(Path.of("schema.sql")));
        StringBuilder users = new StringBuilder("INSERT INTO users (id, username, created_at) VALUES\n");
        users.append("(1, 'ada', '2025-01-01T00:00:00.000Z'),\n");
        users.append("(2, 'bea', '2025-01-01T00:00:00.000Z')");
        for (int id = 3; id <= 20; id++) {
            users.append(",\n(").append(id).append(", 'u").append(id).append("', '2025-01-01T00:00:00.000Z')");
        }
        users.append(';');
        sql(db, users + """
                INSERT INTO posts (id, user_id, body, created_at) VALUES
                  (1, 1, 'first', '2025-06-01T00:00:00.000Z'),
                  (2, 2, 'second', '2025-12-31T23:59:19.409Z');
                INSERT INTO likes (user_id, post_id) VALUES (2, 1);
                """);
        server = Server.listen("127.0.0.1", 0, db, Tokens.SECRET);
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void down() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Order(1)
    /** /health и золотая лента из двух постов совпадают с контрактом байт в байт. */
    void healthAndTheSeededFeedMatchTheContract() throws Exception {
        HttpResponse<String> health = send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build());
        assertEquals(200, health.statusCode());
        assertTrue(health.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        assertTrue(health.body().matches("\\{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":\\d+}"));

        HttpResponse<String> feed = send(HttpRequest.newBuilder(URI.create(base + "/feed")).GET().build());
        assertEquals(200, feed.statusCode());
        assertEquals(
                "{\"posts\":["
                        + "{\"id\":2,\"body\":\"second\",\"created_at\":\"2025-12-31T23:59:19.409Z\",\"author\":\"bea\",\"like_count\":0},"
                        + "{\"id\":1,\"body\":\"first\",\"created_at\":\"2025-06-01T00:00:00.000Z\",\"author\":\"ada\",\"like_count\":1}"
                        + "]}",
                feed.body().stripTrailing());
        HttpResponse<String> post = send(HttpRequest.newBuilder(URI.create(base + "/posts/1")).GET().build());
        assertEquals(
                "{\"post\":{\"id\":1,\"body\":\"first\",\"created_at\":\"2025-06-01T00:00:00.000Z\",\"author\":\"ada\",\"like_count\":1}}",
                post.body().stripTrailing());
    }

    @Test
    @Order(2)
    /** Неизвестный путь — 404, плохой id — 400, чужой пост — 404. */
    void unknownPathsAndBadIdsFailBeforeAnyWrite() throws Exception {
        assertError("GET", "/nope", null, null, 404, "not found");
        for (String id : List.of("0", "-1", "abc", "1.5")) {
            assertError("GET", "/posts/" + id, null, null, 400, "invalid post id");
        }
        assertError("GET", "/posts/999999999", null, null, 404, "post not found");
    }

    @Test
    @Order(3)
    /** На лайке без токена 401, даже если id не число. */
    void authIsCheckedBeforeThePostId() throws Exception {
        assertError("POST", "/posts", null, "{\"body\":\"x\"}", 401, "missing bearer token");
        assertError("POST", "/posts", "Basic abc", "{\"body\":\"x\"}", 401, "missing bearer token");
        assertError("POST", "/posts", "Bearer abc", "{\"body\":\"x\"}", 401, "invalid or expired token");
        assertError("POST", "/posts/abc/like", null, null, 401, "missing bearer token");
        assertError("POST", "/posts/abc/like", Tokens.bearer(1, "ada", EXP), null, 400, "invalid post id");
    }

    @Test
    @Order(4)
    /** Пустое, пробельное, нестроковое, битое и слишком длинное тело. */
    void createValidatesTheJsonBody() throws Exception {
        String auth = Tokens.bearer(1, "ada", EXP);
        assertError("POST", "/posts", auth, "{\"body\":\"\"}", 400, "body is required");
        assertError("POST", "/posts", auth, "{\"body\":\"   \"}", 400, "body is required");
        assertError("POST", "/posts", auth, "{\"body\":123}", 400, "body is required");
        assertError("POST", "/posts", auth, "{}", 400, "body is required");
        assertError("POST", "/posts", auth, "{bad json", 400, "malformed JSON body");
        assertError("POST", "/posts", auth, "{\"body\":\"" + "a".repeat(501) + "\"}", 400, "body must be at most 500 characters");
    }

    @Test
    @Order(5)
    /** Создание, повторный лайк и чтение обратно видят зафиксированные данные. */
    void createLikeAndReadBackAreDurable() throws Exception {
        String auth = Tokens.bearer(1, "ada", EXP);
        String special = "  test: <b>&amp;</b> \\\"quoted\\\" / café ✓  ";
        HttpResponse<String> created = send(post("/posts", auth, "{\"body\":\"" + special + "\"}"));
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"body\":\"test: <b>&amp;</b> \\\"quoted\\\" / café ✓\""));
        assertTrue(created.body().contains("\"author\":\"ada\""));
        assertTrue(created.body().contains("\"like_count\":0"));

        String fiveHundred = "b".repeat(500);
        HttpResponse<String> post = send(post("/posts", auth, "{\"body\":\"  " + fiveHundred + " \"}"));
        assertEquals(201, post.statusCode());
        String body = post.body().stripTrailing();
        String id = between(body, "\"id\":", ",");
        String createdAt = between(body, "\"created_at\":\"", "\"");
        assertTrue(createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertEquals(
                "{\"post\":{\"id\":" + id + ",\"body\":\"" + fiveHundred + "\",\"created_at\":\"" + createdAt
                        + "\",\"author\":\"ada\",\"like_count\":0}}",
                body);

        HttpResponse<String> like = send(post("/posts/" + id + "/like", auth, ""));
        assertEquals(201, like.statusCode());
        assertEquals("{\"liked\":true,\"already_liked\":false,\"post_id\":" + id + "}", like.body().stripTrailing());
        HttpResponse<String> again = send(post("/posts/" + id + "/like", auth, ""));
        assertEquals(200, again.statusCode());
        assertEquals("{\"liked\":true,\"already_liked\":true,\"post_id\":" + id + "}", again.body().stripTrailing());
        assertError("POST", "/posts/999999999/like", auth, null, 404, "post not found");

        HttpResponse<String> read = send(HttpRequest.newBuilder(URI.create(base + "/posts/" + id)).GET().build());
        assertEquals(
                "{\"post\":{\"id\":" + id + ",\"body\":\"" + fiveHundred + "\",\"created_at\":\"" + createdAt
                        + "\",\"author\":\"ada\",\"like_count\":1}}",
                read.body().stripTrailing());
        HttpResponse<String> feed = send(HttpRequest.newBuilder(URI.create(base + "/feed")).GET().build());
        assertTrue(feed.body().startsWith("{\"posts\":[{\"id\":" + id + ","));
    }

    @Test
    @Order(6)
    /** Двадцать одновременных лайков дают like_count 20, ответ приходит после COMMIT. */
    void concurrentWritesCommitBeforeTheyRespond() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> likes = new ArrayList<>();
            for (int user = 1; user <= 20; user++) {
                String auth = Tokens.bearer(user, "u" + user, EXP);
                likes.add(pool.submit(() -> send(post("/posts/2/like", auth, "")).statusCode()));
            }
            for (Future<Integer> like : likes) {
                int status = like.get();
                assertTrue(status == 200 || status == 201, "status " + status);
            }
        }
        HttpResponse<String> post = send(HttpRequest.newBuilder(URI.create(base + "/posts/2")).GET().build());
        assertTrue(post.body().contains("\"like_count\":20"), post.body());
    }

    private static void assertError(String method, String path, String auth, String body, int status, String error) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = send(builder.build());
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("{\"error\":\"" + error + "\"}", response.body().stripTrailing());
    }

    private static HttpRequest post(String path, String auth, String body) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int value = from + start.length();
        return text.substring(value, text.indexOf(end, value));
    }

    private static void sql(Path db, String script) throws Exception {
        Process process = new ProcessBuilder("sqlite3", db.toString()).start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        if (process.waitFor() != 0) {
            throw new AssertionError("sqlite3 exited " + process.exitValue());
        }
    }
}

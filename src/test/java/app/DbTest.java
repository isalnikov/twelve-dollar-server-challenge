package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Лента, невидимость записи до COMMIT и первый лайк против повторного. */
class DbTest {

    @Test
    /** Лента идёт от новых к старым, отсутствующий пост — null. */
    void feedIsNewestFirstAndAMissingPostIsAbsent() throws Exception {
        try (Db db = Db.open(seed())) {
            String feed = new String(db.feed(), StandardCharsets.UTF_8);
            assertEquals(
                    "{\"posts\":["
                            + "{\"id\":2,\"body\":\"second\",\"created_at\":\"2025-12-31T23:59:19.409Z\",\"author\":\"bea\",\"like_count\":0},"
                            + "{\"id\":1,\"body\":\"first\",\"created_at\":\"2025-06-01T00:00:00.000Z\",\"author\":\"ada\",\"like_count\":1}"
                            + "]}",
                    feed);
            assertNull(db.post(999));
            String post = new String(db.post(1), StandardCharsets.UTF_8);
            assertEquals(
                    "{\"id\":1,\"body\":\"first\",\"created_at\":\"2025-06-01T00:00:00.000Z\",\"author\":\"ada\",\"like_count\":1}",
                    post);
        }
    }

    @Test
    /** Второе соединение не видит INSERT до COMMIT. */
    void aWriteStaysInvisibleUntilTheBatchCommits() throws Exception {
        Path path = seed();
        try (Db writer = Db.open(path); Db reader = Db.open(path)) {
            writer.begin();
            byte[] stored = writer.insert(1, "hidden", "ada");
            String json = new String(stored, StandardCharsets.UTF_8);
            assertTrue(json.contains("\"body\":\"hidden\""));
            assertTrue(json.contains("\"author\":\"ada\""));
            assertTrue(json.contains("\"like_count\":0"));
            assertFalse(new String(reader.feed(), StandardCharsets.UTF_8).contains("hidden"));
            writer.commit();
            assertTrue(new String(reader.feed(), StandardCharsets.UTF_8).contains("hidden"));
        }
    }

    @Test
    /** Первый лайк меняет одну строку, повторный — ноль. */
    void theFirstLikeInsertsAndTheSecondDoesNot() throws Exception {
        try (Db db = Db.open(seed())) {
            db.begin();
            assertEquals(1, db.like(1, 2));
            assertEquals(0, db.like(1, 2));
            assertEquals(0, db.like(1, 999));
            assertFalse(db.postExists(999));
            assertTrue(db.postExists(2));
            db.commit();
            String post = new String(db.post(2), StandardCharsets.UTF_8);
            assertTrue(post.contains("\"like_count\":1"));
        }
    }

    private static Path seed() throws Exception {
        Path dir = Files.createTempDirectory("feed-db");
        Path db = dir.resolve("feed.db");
        sql(db, Files.readString(Path.of("schema.sql")));
        sql(db, """
                INSERT INTO users (id, username, created_at) VALUES
                  (1, 'ada', '2025-01-01T00:00:00.000Z'),
                  (2, 'bea', '2025-01-01T00:00:00.000Z');
                INSERT INTO posts (id, user_id, body, created_at) VALUES
                  (1, 1, 'first', '2025-06-01T00:00:00.000Z'),
                  (2, 2, 'second', '2025-12-31T23:59:19.409Z');
                INSERT INTO likes (user_id, post_id) VALUES (2, 1);
                """);
        return db;
    }

    private static void sql(Path db, String script) throws Exception {
        Process process = new ProcessBuilder("sqlite3", db.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        if (process.waitFor() != 0) {
            throw new AssertionError("sqlite3 exited " + process.exitValue());
        }
    }
}

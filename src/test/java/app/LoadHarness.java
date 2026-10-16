package app;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Нагрузка закрытым циклом на отдельный процесс сервера.
 * Смесь как в задании: около 46% лента, 46% пост, 7% лайк, 1% создание.
 * Проход: p95 меньше 500 мс, p99 меньше 1 с, ошибок меньше 1%. Пауз между запросами нет.
 */
public final class LoadHarness {
    private static final int USERS = 2_000;
    private static final int POSTS = 20_000;
    private static final int LIKES = 80_000;
    private static final long EXP = (System.currentTimeMillis() / 1000L) + 3_600;
    private static final int[] LADDER = {1, 2, 4, 8, 16, 32, 64, 128};

    /** Прогрев, лестница до 128, затем лента, пост и пять минут смеси. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String classpath = System.getProperty("server.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("start via load.sh so -Dserver.classpath is set");
        }
        Path db = seed(root);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Process server = startServer(root, classpath, db, port);
        try {
            String base = "http://127.0.0.1:" + port;
            waitUntilHealthy(base, server);
            System.out.println("server pid=" + server.pid() + " port=" + port
                    + " processors=" + Runtime.getRuntime().availableProcessors());
            run(base, "warmup", 2, Duration.ofSeconds(5));
            int holdAt = 1;
            for (int concurrency : LADDER) {
                Report report = run(base, "probe", concurrency, Duration.ofSeconds(10));
                if (!report.passes()) {
                    System.out.println("ceiling before c=" + concurrency);
                    break;
                }
                holdAt = concurrency;
            }
            run(base, "feed", holdAt, Duration.ofSeconds(20));
            run(base, "post", holdAt, Duration.ofSeconds(20));
            Report hold = run(base, "mixed", holdAt, Duration.ofMinutes(5));
            System.out.println(hold.passes()
                    ? "FIVE_MINUTE PASS"
                    : "FIVE_MINUTE FAIL");
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    /** concurrency виртуальных потоков бьют в сервер до конца окна и печатают процентили. */
    private static Report run(String base, String scenario, int concurrency, Duration duration) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        String[] tokens = new String[USERS];
        for (int i = 0; i < USERS; i++) {
            tokens[i] = Tokens.bearer(i + 1, "u" + (i + 1), EXP);
        }
        LongAdder ok = new LongAdder();
        LongAdder err = new LongAdder();
        List<Worker> workers = new ArrayList<>();
        long deadline = System.nanoTime() + duration.toNanos();
        long started = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                Worker worker = new Worker(http, base, tokens, scenario, deadline, ok, err);
                workers.add(worker);
                tasks.add(() -> {
                    worker.loop();
                    return null;
                });
            }
            var heart = Thread.ofVirtual().start(() -> heartbeat(scenario, concurrency, started, deadline, ok, err));
            pool.invokeAll(tasks);
            heart.interrupt();
        }
        long elapsed = System.nanoTime() - started;
        Report report = Report.of(scenario, concurrency, elapsed, workers);
        System.out.println(report.line());
        return report;
    }

    private static void heartbeat(String scenario, int concurrency, long started, long deadline, LongAdder ok, LongAdder err) {
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(30_000);
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                long good = ok.sum();
                long bad = err.sum();
                System.out.printf("  ... %s c=%d t=%ds ok=%d err=%d%n", scenario, concurrency, seconds, good, bad);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 2000 пользователей, 20 000 постов и 80 000 лайков. synchronous=OFF только на время засева. */
    private static Path seed(Path root) throws Exception {
        Path dir = Files.createTempDirectory("feed-load");
        Path db = dir.resolve("feed.db");
        String script = """
                import sqlite3, pathlib, sys
                db, schema = sys.argv[1:]
                con = sqlite3.connect(db)
                con.executescript(pathlib.Path(schema).read_text())
                con.execute("PRAGMA journal_mode=WAL")
                con.execute("PRAGMA synchronous=OFF")
                cur = con.cursor()
                cur.execute("BEGIN")
                cur.executemany(
                    "INSERT INTO users (id, username, created_at) VALUES (?,?,?)",
                    [(i, f"u{i}", "2024-01-01T00:00:00.000Z") for i in range(1, %d + 1)])
                cur.executemany(
                    "INSERT INTO posts (id, user_id, body, created_at) VALUES (?,?,?,?)",
                    [(i, (i %% %d) + 1, f"post {i}", f"2024-06-01T12:00:{i // 1000:02d}.{i %% 1000:03d}Z")
                     for i in range(1, %d + 1)])
                cur.executemany(
                    "INSERT OR IGNORE INTO likes (user_id, post_id) VALUES (?,?)",
                    [((k %% %d) + 1, (k * 17 %% %d) + 1) for k in range(%d)])
                con.commit()
                con.close()
                """.formatted(USERS, USERS, POSTS, USERS, POSTS, LIKES);
        Process process = new ProcessBuilder("python3", "-c", script, db.toString(), root.resolve("schema.sql").toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("seed failed");
        }
        System.out.println("seed users=" + USERS + " posts=" + POSTS + " likes=" + LIKES);
        return db;
    }

    private static Process startServer(Path root, String classpath, Path db, int port) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-XX:+UseSerialGC",
                "-Xms512m", "-Xmx512m", "-XX:+AlwaysPreTouch",
                "-XX:+UseCompactObjectHeaders",
                "-XX:+DisableExplicitGC",
                "-XX:-UsePerfData",
                "-Dio.netty.allocator.type=adaptive",
                "-Dio.netty.noPreferDirect=false",
                "-Dio.netty.leakDetection.level=disabled",
                "-cp", classpath,
                "app.Main");
        builder.directory(root.toFile());
        builder.environment().put("HOST", "127.0.0.1");
        builder.environment().put("PORT", Integer.toString(port));
        builder.environment().put("SQLITE_PATH", db.toString());
        builder.environment().put("JWT_SECRET", Tokens.SECRET);
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            builder.environment().put("MALLOC_ARENA_MAX", "2");
        }
        String transport = System.getenv("TRANSPORT");
        if (transport != null && !transport.isBlank()) {
            builder.environment().put("TRANSPORT", transport);
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(root.resolve("build/load-server.log").toFile());
        Files.createDirectories(root.resolve("build"));
        return builder.start();
    }

    private static void waitUntilHealthy(String base, Process server) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < 50; i++) {
            if (!server.isAlive()) {
                throw new IllegalStateException("server exited " + server.exitValue());
            }
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(1)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("server did not become healthy");
    }

    private static final class Worker {
        private final HttpClient http;
        private final String base;
        private final String[] tokens;
        private final String scenario;
        private final long deadline;
        private final LongAdder ok;
        private final LongAdder err;
        private long[] samples = new long[8_192];
        private int count;
        private int good;
        private int bad;

        private Worker(HttpClient http, String base, String[] tokens, String scenario, long deadline, LongAdder ok, LongAdder err) {
            this.http = http;
            this.base = base;
            this.tokens = tokens;
            this.scenario = scenario;
            this.deadline = deadline;
            this.ok = ok;
            this.err = err;
        }

        private void loop() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (System.nanoTime() < deadline) {
                int roll = switch (scenario) {
                    case "feed" -> 0;
                    case "post" -> 50;
                    default -> random.nextInt(100);
                };
                long started = System.nanoTime();
                boolean success;
                try {
                    success = roll < 46 ? feed() : roll < 92 ? post(random) : roll < 99 ? like(random) : create(random);
                } catch (Exception e) {
                    success = false;
                }
                add(System.nanoTime() - started, success);
            }
        }

        private boolean feed() throws Exception {
            return status("/feed", "GET", null, null) == 200;
        }

        private boolean post(ThreadLocalRandom random) throws Exception {
            return status("/posts/" + (random.nextInt(POSTS) + 1), "GET", null, null) == 200;
        }

        private boolean like(ThreadLocalRandom random) throws Exception {
            int status = status("/posts/" + (random.nextInt(POSTS) + 1) + "/like", "POST", tokens[random.nextInt(tokens.length)], "");
            return status == 200 || status == 201;
        }

        private boolean create(ThreadLocalRandom random) throws Exception {
            String body = "{\"body\":\"load " + Long.toHexString(random.nextLong()) + "\"}";
            return status("/posts", "POST", tokens[random.nextInt(tokens.length)], body) == 201;
        }

        private int status(String path, String method, String authorization, String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            if ("POST".equals(method)) {
                request.header("Content-Type", "application/json");
                request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                request.GET();
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        private void add(long nanos, boolean success) {
            if (count == samples.length) {
                samples = Arrays.copyOf(samples, count * 2);
            }
            samples[count++] = nanos;
            if (success) {
                good++;
                ok.increment();
            } else {
                bad++;
                err.increment();
            }
        }
    }

    private record Report(String scenario, int concurrency, long elapsedNanos, int ok, int err, long p50, long p95, long p99, long max) {
        static Report of(String scenario, int concurrency, long elapsedNanos, List<Worker> workers) {
            int ok = 0;
            int err = 0;
            int total = 0;
            for (Worker worker : workers) {
                ok += worker.good;
                err += worker.bad;
                total += worker.count;
            }
            long[] all = new long[total];
            int cursor = 0;
            for (Worker worker : workers) {
                System.arraycopy(worker.samples, 0, all, cursor, worker.count);
                cursor += worker.count;
            }
            Arrays.sort(all);
            return new Report(scenario, concurrency, elapsedNanos, ok, err,
                    percentile(all, 50), percentile(all, 95), percentile(all, 99), total == 0 ? 0 : all[total - 1]);
        }

        boolean passes() {
            int total = ok + err;
            return total > 0 && err * 100.0 / total < 1.0 && p95 < 500_000_000L && p99 < 1_000_000_000L;
        }

        String line() {
            int total = ok + err;
            double seconds = elapsedNanos / 1_000_000_000.0;
            double rps = seconds == 0 ? 0 : total / seconds;
            double errors = total == 0 ? 0 : err * 100.0 / total;
            return "%s c=%d %.0fs req=%d rps=%.0f err=%.3f%% p50=%s p95=%s p99=%s max=%s %s".formatted(
                    scenario, concurrency, seconds, total, rps, errors, ms(p50), ms(p95), ms(p99), ms(max),
                    passes() ? "pass" : "fail");
        }

        private static long percentile(long[] sorted, int p) {
            if (sorted.length == 0) {
                return 0;
            }
            int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }

        private static String ms(long nanos) {
            return "%.2fms".formatted(nanos / 1_000_000.0);
        }
    }
}

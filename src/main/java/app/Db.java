package app;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Одно соединение SQLite и один поток.
 * Запись видна другим только после {@link #commit}: ответы до этого удерживает сервер.
 */
final class Db implements AutoCloseable {
    private static final int OK = 0;
    private static final int ROW = 100;
    private static final int DONE = 101;
    /** SQLITE_TRANSIENT: SQLite сам копирует привязанный текст до возврата из bind. */
    private static final MemorySegment TRANSIENT = MemorySegment.ofAddress(0xFFFFFFFFFFFFFFFFL);
    private static final MethodHandle OPEN;
    private static final MethodHandle CLOSE;
    private static final MethodHandle EXEC;
    private static final MethodHandle PREPARE;
    private static final MethodHandle BIND_TEXT;
    private static final MethodHandle BIND_INT;
    private static final MethodHandle STEP;
    private static final MethodHandle RESET;
    private static final MethodHandle CHANGES;
    private static final MethodHandle COLUMN_TEXT;
    private static final MethodHandle COLUMN_BYTES;
    private static final MethodHandle COLUMN_INT;
    private static final MethodHandle ERRMSG;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(library(), Arena.global());
        OPEN = down(linker, lib, "sqlite3_open_v2", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        CLOSE = down(linker, lib, "sqlite3_close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        EXEC = down(linker, lib, "sqlite3_exec", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        PREPARE = down(linker, lib, "sqlite3_prepare_v2", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        BIND_TEXT = down(linker, lib, "sqlite3_bind_text", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        BIND_INT = down(linker, lib, "sqlite3_bind_int64", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        STEP = down(linker, lib, "sqlite3_step", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        RESET = down(linker, lib, "sqlite3_reset", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        CHANGES = down(linker, lib, "sqlite3_changes", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        COLUMN_TEXT = down(linker, lib, "sqlite3_column_text", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        COLUMN_BYTES = down(linker, lib, "sqlite3_column_bytes", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        COLUMN_INT = down(linker, lib, "sqlite3_column_int64", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        ERRMSG = down(linker, lib, "sqlite3_errmsg", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    /** Порядок ключей поста фиксирован заданием: id, body, created_at, author, like_count. */
    private static final String POST_JSON = """
            json_object('id', p.id, 'body', p.body, 'created_at', p.created_at, 'author', u.username, 'like_count', (SELECT count(*) FROM likes l WHERE l.post_id = p.id))""";

    /**
     * Лента: топ-20 постов, лайки одним GROUP BY по id из подзапроса.
     * Один json_group_array на всю ленту на этой базе оказался в ~10 раз медленнее цикла step.
     */
    private static final String FEED_SQL = """
            SELECT json_object('id', p.id, 'body', p.body, 'created_at', p.created_at, 'author', u.username, 'like_count', COALESCE(lc.cnt, 0))
            FROM posts p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN (
              SELECT post_id, COUNT(*) AS cnt
              FROM likes
              WHERE post_id IN (SELECT id FROM posts ORDER BY created_at DESC, id DESC LIMIT 20)
              GROUP BY post_id
            ) lc ON lc.post_id = p.id
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT 20""";

    private final Arena arena = Arena.ofShared();
    private final MemorySegment scratch = arena.allocate(4096);
    private final MemorySegment db;
    private final MemorySegment feedStmt;
    private final MemorySegment postStmt;
    private final MemorySegment insertStmt;
    private final MemorySegment likeStmt;
    private final MemorySegment existsStmt;
    private boolean transaction;
    private MemorySegment sqlBegin;
    private MemorySegment sqlCommit;
    private MemorySegment sqlRollback;
    private MemorySegment sqlPing;
    private MemorySegment sqlCheckpoint;

    /** Открывает файл базы и готовит операторы. */
    static Db open(Path path) {
        return new Db(path);
    }

    private Db(Path path) {
        try {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            check(call(OPEN, arena.allocateFrom(path.toAbsolutePath().toString()), out, 2 | 4, MemorySegment.NULL));
            db = out.get(ValueLayout.ADDRESS, 0);
            // WAL и synchronous=NORMAL переживают сбой процесса. Чекпоинт делает второй поток.
            exec("PRAGMA journal_mode=WAL");
            exec("PRAGMA synchronous=NORMAL");
            exec("PRAGMA foreign_keys=ON");
            exec("PRAGMA busy_timeout=5000");
            exec("PRAGMA mmap_size=268435456");
            exec("PRAGMA cache_size=-32768");
            exec("PRAGMA temp_store=MEMORY");
            exec("PRAGMA wal_autocheckpoint=0");
            feedStmt = prepare(FEED_SQL);
            postStmt = prepare("SELECT " + POST_JSON + " FROM posts p JOIN users u ON u.id = p.user_id WHERE p.id=?");
            insertStmt = prepare("INSERT INTO posts (user_id, body) VALUES (?1, ?2) RETURNING id, created_at");
            likeStmt = prepare("""
                    INSERT INTO likes (user_id, post_id)
                    SELECT ?1, ?2 WHERE EXISTS (SELECT 1 FROM posts WHERE id=?2)
                    ON CONFLICT (user_id, post_id) DO NOTHING""");
            existsStmt = prepare("SELECT 1 FROM posts WHERE id=?");
        } catch (Throwable e) {
            arena.close();
            throw new IllegalStateException(e);
        }
    }

    /** Двадцать свежих постов одним JSON {"posts":[...]}; по строке — {@link #FEED_SQL}. */
    byte[] feed() {
        byte[] open = "{\"posts\":[".getBytes(StandardCharsets.US_ASCII);
        byte[] close = "]}".getBytes(StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(open);
        boolean first = true;
        try {
            int rc;
            while ((rc = step(feedStmt)) == ROW) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                out.writeBytes(columnBytes(feedStmt, 0));
            }
            if (rc != DONE) {
                throw failure();
            }
        } finally {
            reset(feedStmt);
        }
        out.writeBytes(close);
        return out.toByteArray();
    }

    /** Один пост или null, если строки нет. */
    byte[] post(long id) {
        try {
            bindInt(postStmt, 1, id);
            int rc = step(postStmt);
            if (rc == DONE) {
                return null;
            }
            if (rc != ROW) {
                throw failure();
            }
            return columnBytes(postStmt, 0);
        } finally {
            reset(postStmt);
        }
    }

    /** Вставляет пост и сразу собирает JSON. created_at берётся из RETURNING. */
    byte[] insert(long userId, String body, String author) {
        try {
            bindInt(insertStmt, 1, userId);
            bindText(insertStmt, 2, body);
            int rc = step(insertStmt);
            if (rc != ROW) {
                throw failure();
            }
            long id = columnLong(insertStmt, 0);
            String created = new String(columnBytes(insertStmt, 1), StandardCharsets.UTF_8);
            return jsonPost(id, body, created, author, 0);
        } finally {
            reset(insertStmt);
        }
    }

    /** 1, если лайк вставлен, 0 если пост есть и лайк уже был либо поста нет. */
    int like(long userId, long postId) {
        try {
            bindInt(likeStmt, 1, userId);
            bindInt(likeStmt, 2, postId);
            int rc = step(likeStmt);
            if (rc != DONE) {
                throw failure();
            }
            return changes();
        } finally {
            reset(likeStmt);
        }
    }

    /** Нужен, чтобы отличить повторный лайк от отсутствующего поста. */
    boolean postExists(long id) {
        try {
            bindInt(existsStmt, 1, id);
            int rc = step(existsStmt);
            if (rc == ROW) {
                return true;
            }
            if (rc != DONE) {
                throw failure();
            }
            return false;
        } finally {
            reset(existsStmt);
        }
    }

    /** BEGIN IMMEDIATE один раз на пачку. Повторный вызов внутри той же пачки ничего не делает. */
    void begin() {
        if (!transaction) {
            exec(sqlBegin == null ? (sqlBegin = arena.allocateFrom("BEGIN IMMEDIATE")) : sqlBegin);
            transaction = true;
        }
    }

    /** Фиксирует пачку. После этого ответы можно отдать клиентам. */
    void commit() {
        if (transaction) {
            exec(sqlCommit == null ? (sqlCommit = arena.allocateFrom("COMMIT")) : sqlCommit);
            transaction = false;
        }
    }

    /** Снимает пачку, если COMMIT не удался. */
    void rollback() {
        if (transaction) {
            try {
                exec(sqlRollback == null ? (sqlRollback = arena.allocateFrom("ROLLBACK")) : sqlRollback);
            } finally {
                transaction = false;
            }
        }
    }

    /** true, пока ответы записи нельзя отправлять. */
    boolean inTransaction() {
        return transaction;
    }

    /** PASSIVE-чекпоинт WAL со второго соединения, чтобы fsync не стоял на пути запроса. */
    void checkpoint() {
        exec(sqlCheckpoint == null ? (sqlCheckpoint = arena.allocateFrom("PRAGMA wal_checkpoint(PASSIVE)")) : sqlCheckpoint);
    }

    @Override
    /** Закрывает соединение. Ошибка здесь уже некому сообщить: процесс останавливается. */
    public void close() {
        try {
            call(CLOSE, db);
        } catch (Throwable ignored) {
            // Процесс останавливается, сообщение об ошибке закрытия никуда не уходит.
        }
        arena.close();
    }

    /** Готовит оператор один раз на жизнь соединения. */
    private MemorySegment prepare(String sql) {
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        check(call(PREPARE, db, arena.allocateFrom(sql), -1, out, MemorySegment.NULL));
        return out.get(ValueLayout.ADDRESS, 0);
    }

    /** Разовый SQL. Строка живёт в арене соединения, поэтому для цикла есть кэш сегментов. */
    private void exec(String sql) {
        exec(arena.allocateFrom(sql));
    }

    /** Выполняет уже размещённую в памяти строку SQL. */
    private void exec(MemorySegment sql) {
        check(call(EXEC, db, sql, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL));
    }

    /** Привязывает целое к параметру с единицы. */
    private void bindInt(MemorySegment stmt, int index, long value) {
        check(call(BIND_INT, stmt, index, value));
    }

    /** Копирует UTF-8 в общий буфер и отдаёт SQLite с SQLITE_TRANSIENT. */
    private void bindText(MemorySegment stmt, int index, String text) {
        byte[] utf = text.getBytes(StandardCharsets.UTF_8);
        if (utf.length >= scratch.byteSize()) {
            throw new IllegalStateException("text too long");
        }
        MemorySegment.copy(MemorySegment.ofArray(utf), 0, scratch, 0, utf.length);
        check(call(BIND_TEXT, stmt, index, scratch, utf.length, TRANSIENT));
    }

    /** Один шаг подготовленного оператора. */
    private int step(MemorySegment stmt) {
        return (int) call(STEP, stmt);
    }

    /** Возвращает оператор в начало, чтобы его можно было выполнить снова. */
    private void reset(MemorySegment stmt) {
        call(RESET, stmt);
    }

    /** SELECT 1 для /health. */
    void ping() {
        exec(sqlPing == null ? (sqlPing = arena.allocateFrom("SELECT 1")) : sqlPing);
    }

    /** Текст последней ошибки SQLite. Если указатель недоступен — unreachable. */
    String message() {
        try {
            MemorySegment pointer = ((MemorySegment) ERRMSG.invokeExact(db)).reinterpret(512);
            return pointer.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "unreachable";
        }
    }

    /** Байты текстовой колонки. Пустая колонка даёт пустой массив. */
    private byte[] columnBytes(MemorySegment stmt, int column) {
        try {
            int n = (int) COLUMN_BYTES.invokeExact(stmt, column);
            if (n <= 0) {
                return new byte[0];
            }
            MemorySegment pointer = ((MemorySegment) COLUMN_TEXT.invokeExact(stmt, column)).reinterpret(n);
            return pointer.toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Целая колонка, в том числе id из RETURNING. */
    private long columnLong(MemorySegment stmt, int column) {
        try {
            return (long) COLUMN_INT.invokeExact(stmt, column);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Сколько строк изменил последний оператор. Для лайка это 1 или 0. */
    private int changes() {
        try {
            return (int) CHANGES.invokeExact(db);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Собирает объект поста в порядке ключей задания. */
    private static byte[] jsonPost(long id, String body, String created, String author, int likes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length() + 80);
        raw(out, "{\"id\":");
        raw(out, Long.toString(id));
        raw(out, ",\"body\":");
        quote(out, body);
        raw(out, ",\"created_at\":");
        quote(out, created);
        raw(out, ",\"author\":");
        quote(out, author);
        raw(out, ",\"like_count\":");
        raw(out, Integer.toString(likes));
        out.write('}');
        return out.toByteArray();
    }

    /** Пишет ASCII без кавычек. */
    private static void raw(java.io.ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** JSON-строка с экранированием кавычек, обратной косой и управляющих символов. */
    private static void quote(java.io.ByteArrayOutputStream out, String text) {
        out.write('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> { out.write('\\'); out.write('"'); }
                case '\\' -> { out.write('\\'); out.write('\\'); }
                case '\b' -> { out.write('\\'); out.write('b'); }
                case '\f' -> { out.write('\\'); out.write('f'); }
                case '\n' -> { out.write('\\'); out.write('n'); }
                case '\r' -> { out.write('\\'); out.write('r'); }
                case '\t' -> { out.write('\\'); out.write('t'); }
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                        out.writeBytes(text.substring(i, i + 2).getBytes(StandardCharsets.UTF_8));
                        i++;
                    } else if (c < 0x20) {
                        raw(out, "\\u%04x".formatted((int) c));
                    } else if (c < 0x80) {
                        out.write(c);
                    } else {
                        out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        out.write('"');
    }

    /** OK, ROW и DONE — норма. Остальные коды становятся исключением с текстом SQLite. */
    private void check(int rc) {
        if (rc != OK && rc != ROW && rc != DONE) {
            throw failure();
        }
    }

    /** Исключение с errmsg текущего соединения. */
    private IllegalStateException failure() {
        return new IllegalStateException(message());
    }

    /** Вызов downcall, который возвращает код int. Не используется для указателей колонок. */
    private static int call(MethodHandle handle, Object... args) {
        try {
            Object result = handle.invokeWithArguments(args);
            if (result instanceof Integer n) {
                return n;
            }
            if (result instanceof Long n) {
                return n.intValue();
            }
            return 0;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Находит символ SQLite и связывает его с дескриптором вызова. */
    private static MethodHandle down(Linker linker, SymbolLookup lib, String name, FunctionDescriptor type) {
        return linker.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException(name)), type);
    }

    /** SQLITE_LIB, иначе native/libsqlite3.dylib или .so рядом с запуском. */
    private static Path library() {
        String env = System.getenv("SQLITE_LIB");
        if (env != null && !env.isEmpty()) {
            return Path.of(env);
        }
        Path dylib = Path.of("native/libsqlite3.dylib").toAbsolutePath();
        if (Files.exists(dylib)) {
            return dylib;
        }
        Path so = Path.of("native/libsqlite3.so").toAbsolutePath();
        if (Files.exists(so)) {
            return so;
        }
        throw new IllegalStateException("libsqlite3 not found; run build.sh");
    }
}

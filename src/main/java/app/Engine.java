package app;

/** Пять ручек задания. Запись открывает транзакцию, но не фиксирует её: это делает пачка. */
final class Engine {
    private static final byte[] ERR500 = "{\"error\":\"internal server error\"}".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final Db db;
    private final Jwt jwt;
    private final long startedNanos = System.nanoTime();

    Engine(Db db, Jwt jwt) {
        this.db = db;
        this.jwt = jwt;
    }

    /** Маршрут запроса. Любой сбой внутри превращается в 500 с телом контракта. */
    Reply handle(String method, String target, String authorization, byte[] body) {
        try {
            return route(method, target, authorization, body == null ? new byte[0] : body);
        } catch (RuntimeException e) {
            return new Reply(500, ERR500);
        }
    }

    /** Точное совпадение путей. Лишний сегмент у /posts/ уходит в 404, а не в разбор id. */
    private Reply route(String method, String target, String authorization, byte[] body) {
        if ("GET".equals(method) && "/health".equals(target)) {
            return health();
        }
        if ("GET".equals(method) && "/feed".equals(target)) {
            return new Reply(200, db.feed());
        }
        if ("GET".equals(method) && target.startsWith("/posts/")) {
            return get(target.substring("/posts/".length()));
        }
        if ("POST".equals(method) && "/posts".equals(target)) {
            return create(authorization, body);
        }
        if ("POST".equals(method) && target.startsWith("/posts/") && target.endsWith("/like") && target.length() > "/posts/".length() + "/like".length()) {
            return like(authorization, target.substring("/posts/".length(), target.length() - "/like".length()));
        }
        return error(404, "not found");
    }

    /** SELECT 1. Живая база даёт ok и uptime, мёртвая — 503 degraded. */
    private Reply health() {
        try {
            db.ping();
            int uptime = (int) ((System.nanoTime() - startedNanos) / 1_000_000_000L);
            return new Reply(200, ("{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":" + uptime + "}").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            String message = db.message().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            return new Reply(503, ("{\"status\":\"degraded\",\"db\":\"unreachable\",\"error\":\"" + message + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Один пост. Нечисло и ноль — 400, отсутствие строки — 404. */
    private Reply get(String raw) {
        if (raw.indexOf('/') >= 0) {
            return error(404, "not found");
        }
        long id = Ids.parse(raw);
        if (id < 0) {
            return error(400, "invalid post id");
        }
        byte[] row = db.post(id);
        if (row == null) {
            return error(404, "post not found");
        }
        return new Reply(200, wrap("{\"post\":", row));
    }

    /** Сначала токен, потом тело. INSERT остаётся внутри открытой транзакции. */
    private Reply create(String authorization, byte[] body) {
        Access access = jwt.authenticate(authorization);
        if (access instanceof Access.No denied) {
            return error(denied.status(), denied.error());
        }
        Parsed parsed = Bodies.parse(body);
        if (parsed.status() != 0) {
            return error(parsed.status(), parsed.error());
        }
        Access.Ok user = (Access.Ok) access;
        db.begin();
        return new Reply(201, wrap("{\"post\":", db.insert(user.id(), parsed.text(), user.username())));
    }

    /** Токен проверяется раньше id. Первый лайк — 201, повторный — 200, чужого поста нет — 404. */
    private Reply like(String authorization, String raw) {
        Access access = jwt.authenticate(authorization);
        if (access instanceof Access.No denied) {
            return error(denied.status(), denied.error());
        }
        if (raw.indexOf('/') >= 0) {
            return error(404, "not found");
        }
        long id = Ids.parse(raw);
        if (id < 0) {
            return error(400, "invalid post id");
        }
        Access.Ok user = (Access.Ok) access;
        db.begin();
        if (db.like(user.id(), id) == 1) {
            return liked(201, false, id);
        }
        if (db.postExists(id)) {
            return liked(200, true, id);
        }
        return error(404, "post not found");
    }

    /** Тело ответа лайка: liked всегда true, already_liked отличает повтор. */
    private static Reply liked(int status, boolean already, long id) {
        return new Reply(status, ("{\"liked\":true,\"already_liked\":" + already + ",\"post_id\":" + id + "}").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** Ошибка контракта: один ключ error, без лишних полей. */
    private static Reply error(int status, String message) {
        return new Reply(status, ("{\"error\":\"" + message + "\"}").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** Оборачивает объект поста в {"post": ... }. */
    private static byte[] wrap(String prefix, byte[] json) {
        byte[] head = prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + json.length + 1];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(json, 0, out, head.length, json.length);
        out[out.length - 1] = '}';
        return out;
    }
}

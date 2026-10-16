package app;

import java.nio.file.Path;

/** Точка входа: читает окружение и держит сервер, пока процесс жив. */
public final class Main {
    private Main() {}

    /** Слушает HOST:PORT (по умолчанию 0.0.0.0:80). SQLITE_PATH и JWT_SECRET обязательны. */
    public static void main(String[] args) throws Exception {
        String host = env("HOST", "0.0.0.0");
        int port = Integer.parseInt(env("PORT", "80"));
        Path database = Path.of(System.getenv("SQLITE_PATH"));
        String secret = System.getenv("JWT_SECRET");
        if ("nio".equals(env("TRANSPORT", "netty"))) {
            NioServer server = NioServer.listen(host, port, database, secret);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.block();
            return;
        }
        Server server = Server.listen(host, port, database, secret);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.block();
    }

    /** Берёт переменную окружения или запасное значение, если она пустая. */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}

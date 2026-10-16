package app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Тот же движок и групповой коммит, но цикл на java.nio.Selector, без Netty.
 * Нужен, чтобы измерить, даёт ли чистый JDK прирост на этом же SQLite.
 */
final class NioServer implements AutoCloseable {
    private static final byte[] S200 = "HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S201 = "HTTP/1.1 201 Created\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S400 = "HTTP/1.1 400 Bad Request\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S401 = "HTTP/1.1 401 Unauthorized\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S404 = "HTTP/1.1 404 Not Found\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S500 = "HTTP/1.1 500 Internal Server Error\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S503 = "HTTP/1.1 503 Service Unavailable\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD = "Content-Type: application/json\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEEP = "\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLOSE_HDR = "\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ERR500 = "{\"error\":\"internal server error\"}".getBytes(StandardCharsets.US_ASCII);

    private final Selector selector;
    private final ServerSocketChannel server;
    private final Db db;
    private final Db checkpointDb;
    private final Engine engine;
    private final List<Conn> held = new ArrayList<>();
    private final ByteBuffer readBuf = ByteBuffer.allocateDirect(32 * 1024);
    private final Thread loop;
    private final Thread checkpoint;
    private volatile boolean closed;
    private final int port;

    /** Слушает host:port. Цикл и SQLite живут в одном потоке. */
    static NioServer listen(String host, int port, Path database, String secret) throws Exception {
        return new NioServer(host, port, database, secret);
    }

    private NioServer(String host, int port, Path database, String secret) throws Exception {
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        server.bind(new InetSocketAddress(host, port), 8192);
        server.register(selector, SelectionKey.OP_ACCEPT);
        this.port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        db = Db.open(database);
        checkpointDb = Db.open(database);
        engine = new Engine(db, new Jwt(secret.getBytes(StandardCharsets.UTF_8), () -> System.currentTimeMillis() / 1000L));
        checkpoint = new Thread(this::checkpointLoop, "wal-checkpoint");
        checkpoint.setDaemon(true);
        checkpoint.start();
        loop = new Thread(this::run, "nio-loop");
        loop.start();
    }

    /** Фактический порт. */
    int port() {
        return port;
    }

    /** Ждёт остановки цикла. */
    void block() throws InterruptedException {
        loop.join();
    }

    /** Будит select и дожидается, пока поток закроет оба соединения SQLite. */
    @Override
    public void close() {
        closed = true;
        selector.wakeup();
        try {
            loop.join(2000);
            checkpoint.interrupt();
            checkpoint.join(2000);
            checkpointDb.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Раз в секунду сдвигает WAL со второго соединения. */
    private void checkpointLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                checkpointDb.checkpoint();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // Пассивный чекпоинт может проиграть гонку коммиту.
            }
        }
    }

    /** Один проход select, затем один COMMIT на всё, что записали в этой пачке. */
    private void run() {
        try {
            while (!closed) {
                selector.select();
                if (closed) {
                    break;
                }
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            accept();
                        } else if (key.isReadable()) {
                            read((Conn) key.attachment());
                        } else if (key.isWritable()) {
                            flush((Conn) key.attachment());
                        }
                    } catch (IOException e) {
                        closeKey(key);
                    }
                }
                if (db.inTransaction()) {
                    try {
                        db.commit();
                        releaseHeld(false);
                    } catch (RuntimeException e) {
                        db.rollback();
                        releaseHeld(true);
                    }
                }
            }
        } catch (IOException e) {
            // Цикл заканчивается, соединения закроет finally.
        } finally {
            try {
                selector.close();
                server.close();
            } catch (IOException ignored) {
                // Процесс останавливается.
            }
            db.close();
        }
    }

    /** Новое соединение без блокировки, с TCP_NODELAY. */
    private void accept() throws IOException {
        SocketChannel ch = server.accept();
        if (ch == null) {
            return;
        }
        ch.configureBlocking(false);
        ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
        Conn conn = new Conn(ch, key);
        key.attach(conn);
    }

    /** Дописывает байты и разбирает все целые запросы, которые уже лежат в буфере. */
    private void read(Conn conn) throws IOException {
        readBuf.clear();
        int n = conn.ch.read(readBuf);
        if (n < 0) {
            conn.ch.close();
            return;
        }
        if (n == 0) {
            return;
        }
        readBuf.flip();
        conn.append(readBuf);
        while (conn.filled > 0 && conn.ch.isOpen()) {
            int used = Http.parse(conn.pending, conn.filled, conn.incoming);
            if (used == 0) {
                return;
            }
            if (used < 0) {
                conn.ch.close();
                return;
            }
            Incoming in = conn.incoming;
            Reply reply;
            try {
                reply = engine.handle(in.method, in.target, in.authorization, in.body);
            } catch (RuntimeException e) {
                reply = new Reply(500, ERR500);
            }
            byte[] framed = frame(reply.status(), reply.json(), in.keepAlive);
            if (db.inTransaction()) {
                conn.held = framed;
                conn.closeAfter = !in.keepAlive;
                held.add(conn);
            } else {
                offer(conn, framed, !in.keepAlive);
            }
            conn.consume(used);
            if (!in.keepAlive) {
                break;
            }
        }
    }

    /** После COMMIT отдаёт удержанные ответы. После отката вместо них уходит 500. */
    private void releaseHeld(boolean failed) throws IOException {
        for (Conn conn : held) {
            if (!conn.ch.isOpen()) {
                conn.held = null;
                continue;
            }
            if (failed) {
                offer(conn, frame(500, ERR500, false), true);
            } else if (conn.held != null) {
                offer(conn, conn.held, conn.closeAfter);
            } else if (conn.closeAfter) {
                conn.ch.close();
            }
            conn.held = null;
        }
        held.clear();
    }

    /** Ставит байты в очередь записи и сразу пробует отдать их в сокет. */
    private void offer(Conn conn, byte[] bytes, boolean closeAfter) throws IOException {
        conn.closeAfter = closeAfter;
        if (conn.outgoing == null) {
            conn.outgoing = ByteBuffer.wrap(bytes);
        } else {
            ByteBuffer cur = conn.outgoing;
            byte[] merged = new byte[cur.remaining() + bytes.length];
            int remain = cur.remaining();
            cur.get(merged, 0, remain);
            System.arraycopy(bytes, 0, merged, remain, bytes.length);
            conn.outgoing = ByteBuffer.wrap(merged);
        }
        flush(conn);
    }

    /** Пишет, пока сокет принимает. Остаток ждёт OP_WRITE. */
    private void flush(Conn conn) throws IOException {
        ByteBuffer out = conn.outgoing;
        while (out != null && out.hasRemaining()) {
            int n = conn.ch.write(out);
            if (n == 0) {
                conn.key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                return;
            }
        }
        conn.outgoing = null;
        if (conn.closeAfter) {
            conn.ch.close();
            return;
        }
        SelectionKey key = conn.key;
        if (key != null && key.isValid()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    /** Закрывает канал ключа. Ошибку ввода наружу не выпускает. */
    private void closeKey(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // Соединение уже оборвано.
        }
    }

    /** Собирает ответ теми же строками, что и сервер на Netty. */
    private static byte[] frame(int status, byte[] json, boolean keep) {
        byte[] line = switch (status) {
            case 200 -> S200;
            case 201 -> S201;
            case 400 -> S400;
            case 401 -> S401;
            case 404 -> S404;
            case 503 -> S503;
            default -> S500;
        };
        byte[] tail = keep ? KEEP : CLOSE_HDR;
        byte[] len = Integer.toString(json.length).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[line.length + HEAD.length + len.length + tail.length + json.length];
        int p = 0;
        p = put(out, p, line);
        p = put(out, p, HEAD);
        p = put(out, p, len);
        p = put(out, p, tail);
        put(out, p, json);
        return out;
    }

    /** Копирует кусок и возвращает новую позицию. */
    private static int put(byte[] dest, int pos, byte[] src) {
        System.arraycopy(src, 0, dest, pos, src.length);
        return pos + src.length;
    }

    /** Буфер одного соединения и ответ, который ждёт коммита. */
    private static final class Conn {
        private final SocketChannel ch;
        private final SelectionKey key;
        private byte[] pending = new byte[1024];
        private int filled;
        private ByteBuffer outgoing;
        private byte[] held;
        private boolean closeAfter;
        private final Incoming incoming = new Incoming();

        private Conn(SocketChannel ch, SelectionKey key) {
            this.ch = ch;
            this.key = key;
        }

        /** Дописывает прочитанное в конец накопителя. */
        private void append(ByteBuffer buf) {
            int n = buf.remaining();
            if (filled + n > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(filled + n, pending.length * 2));
            }
            buf.get(pending, filled, n);
            filled += n;
        }

        /** Сдвигает разобранный запрос из головы накопителя. */
        private void consume(int used) {
            int rest = filled - used;
            if (rest > 0) {
                System.arraycopy(pending, used, pending, 0, rest);
            }
            filled = Math.max(rest, 0);
        }

    }
}

package app;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Готовый ответ: код HTTP и тело JSON. */
record Reply(int status, byte[] json) {}

/**
 * Один event loop. Чтение только ставит слот в кольцо, обработка идёт пачкой после select.
 * Запись отвечает клиенту только после COMMIT.
 */
final class Server implements AutoCloseable {
    private static final AttributeKey<Conn> KEY = AttributeKey.valueOf("c");
    private static final byte[] S200 = "HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S201 = "HTTP/1.1 201 Created\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S400 = "HTTP/1.1 400 Bad Request\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S401 = "HTTP/1.1 401 Unauthorized\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S404 = "HTTP/1.1 404 Not Found\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S500 = "HTTP/1.1 500 Internal Server Error\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] S503 = "HTTP/1.1 503 Service Unavailable\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD = "Content-Type: application/json\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEEP = "\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLOSE = "\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ERR500 = "{\"error\":\"internal server error\"}".getBytes(StandardCharsets.US_ASCII);

    private final EventLoopGroup group;
    private final Db db;
    private final Db checkpointDb;
    private final Engine engine;
    private final Loop loop = new Loop();
    private final Ring ring = new Ring(65_536);
    private final Slots slots = new Slots(65_536);
    private final Conn[] conns = new Conn[65_536];
    private final int[] held = new int[65_536];
    private final int[] shedIds = new int[4096];
    private final int highWater;
    private final Thread checkpoint;
    private int heldCount;
    private boolean drainQueued;
    private Channel channel;
    private int port;

    /** Слушает host:port напрямую, без Nginx. Epoll на Linux, NIO на macOS. */
    static Server listen(String host, int port, Path database, String secret) throws Exception {
        boolean epoll = epoll();
        EventLoopGroup group = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        Server server = new Server(group, database, secret);
        try {
            server.channel = new ServerBootstrap()
                    .group(group)
                    .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 8192)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 2048, 65_536))
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(server.loop);
                        }
                    })
                    .bind(host, port)
                    .sync()
                    .channel();
            server.port = ((InetSocketAddress) server.channel.localAddress()).getPort();
            return server;
        } catch (Exception e) {
            server.close();
            throw e;
        }
    }

    private Server(EventLoopGroup group, Path database, String secret) throws Exception {
        this.group = group;
        // Тысяча дескрипторов остаётся базе и служебным fd. Выше 60 000 слоты не растут.
        this.highWater = Math.min(60_000, Math.max(128, fileLimit() - 1024));
        this.db = group.submit(() -> Db.open(database)).get();
        this.checkpointDb = Db.open(database);
        this.engine = new Engine(db, new Jwt(secret.getBytes(StandardCharsets.UTF_8), () -> System.currentTimeMillis() / 1000L));
        this.checkpoint = new Thread(this::checkpointLoop, "wal-checkpoint");
        this.checkpoint.setDaemon(true);
        this.checkpoint.start();
    }

    /** Фактический порт. Нужен, когда слушали 0 и порт выбрал ядро. */
    int port() {
        return port;
    }

    /** Ждёт закрытия слушающего канала. */
    void block() throws InterruptedException {
        channel.closeFuture().sync();
    }

    @Override
    /** Останавливает приём, чекпоинт и оба соединения SQLite. */
    public void close() {
        try {
            if (channel != null) {
                channel.close().sync();
            }
            checkpoint.interrupt();
            checkpoint.join(2000);
            checkpointDb.close();
            group.submit(db::close).get();
            group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).sync();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Раз в секунду сдвигает WAL. Проигрыш гонки с COMMIT не страшен: следующая секунда повторит. */
    private void checkpointLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                checkpointDb.checkpoint();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // Пассивный чекпоинт может проиграть гонку коммиту. Следующий тик повторит.
            }
        }
    }

    /** Обрабатывает всё, что select положил в кольцо, затем один COMMIT на пачку. */
    private void drain() {
        drainQueued = false;
        int slot;
        while ((slot = ring.poll()) >= 0) {
            Conn conn = conns[slot];
            if (conn == null) {
                continue;
            }
            conn.queued = false;
            service(conn);
        }
        if (db.inTransaction()) {
            try {
                db.commit();
                flushHeld(false);
            } catch (RuntimeException e) {
                db.rollback();
                flushHeld(true);
            }
        }
    }

    /** Разбирает готовые запросы соединения. Неполный запрос остаётся в буфере. */
    private void service(Conn conn) {
        while (conn.pending != null && conn.pending.isReadable() && conn.channel.isActive()) {
            byte[] raw = new byte[conn.pending.readableBytes()];
            conn.pending.getBytes(conn.pending.readerIndex(), raw);
            int used = Http.parse(raw, raw.length, conn.incoming);
            if (used == 0) {
                return;
            }
            if (used < 0) {
                conn.channel.close();
                return;
            }
            conn.pending.skipBytes(used);
            Incoming in = conn.incoming;
            Reply reply;
            try {
                reply = engine.handle(in.method, in.target, in.authorization, in.body);
            } catch (RuntimeException e) {
                reply = new Reply(500, ERR500);
            }
            if (db.inTransaction()) {
                hold(conn, reply, in.keepAlive);
            } else {
                send(conn, reply, in.keepAlive);
            }
            if (!in.keepAlive) {
                conn.closeAfter = true;
                break;
            }
            if (!conn.pending.isReadable()) {
                conn.pending.release();
                conn.pending = null;
            }
        }
    }

    /** Складывает ответ записи до COMMIT. По сети он ещё не уходит. */
    private void hold(Conn conn, Reply reply, boolean keep) {
        if (conn.held == null) {
            conn.held = conn.channel.alloc().buffer();
        }
        frame(conn.held, reply.status(), reply.json(), keep);
        if (!keep) {
            conn.closeAfter = true;
        }
        if (!conn.listed) {
            conn.listed = true;
            held[heldCount++] = conn.slot;
        }
    }

    /** Пишет ответ сразу. Чтение не держит транзакцию. */
    private void send(Conn conn, Reply reply, boolean keep) {
        ByteBuf buf = conn.channel.alloc().buffer(reply.json().length + 96);
        frame(buf, reply.status(), reply.json(), keep);
        if (keep) {
            conn.channel.writeAndFlush(buf);
        } else {
            conn.channel.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** После COMMIT отдаёт удержанные ответы. После отката вместо них уходит 500 и соединение закрывается. */
    private void flushHeld(boolean failed) {
        for (int i = 0; i < heldCount; i++) {
            Conn conn = conns[held[i]];
            if (conn == null) {
                continue;
            }
            conn.listed = false;
            if (!conn.channel.isActive()) {
                if (conn.held != null) {
                    conn.held.release();
                    conn.held = null;
                }
                continue;
            }
            if (failed) {
                if (conn.held != null) {
                    conn.held.release();
                    conn.held = null;
                }
                send(conn, new Reply(500, ERR500), false);
                continue;
            }
            if (conn.held != null) {
                ByteBuf buf = conn.held;
                conn.held = null;
                if (conn.closeAfter) {
                    conn.channel.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
                } else {
                    conn.channel.writeAndFlush(buf);
                }
            } else if (conn.closeAfter) {
                conn.channel.close();
            }
        }
        heldCount = 0;
    }

    /** Закрывает keep-alive, которые молчат дольше секунды, когда слоты упираются в лимит дескрипторов. */
    private void shed() {
        long now = System.nanoTime();
        int room = Math.max(1, slots.count() - (highWater - highWater / 50));
        int n = slots.collectIdle(now, 1_000_000_000L, Math.min(room, shedIds.length), shedIds);
        for (int i = 0; i < n; i++) {
            Conn conn = conns[shedIds[i]];
            if (conn != null) {
                conn.channel.close();
            }
        }
    }

    /** Строка статуса, JSON, длина и Connection. Тело пишется последним. */
    private static void frame(ByteBuf buf, int status, byte[] json, boolean keep) {
        buf.writeBytes(switch (status) {
            case 200 -> S200;
            case 201 -> S201;
            case 400 -> S400;
            case 401 -> S401;
            case 404 -> S404;
            case 503 -> S503;
            default -> S500;
        });
        buf.writeBytes(HEAD);
        writeInt(buf, json.length);
        buf.writeBytes(keep ? KEEP : CLOSE);
        buf.writeBytes(json);
    }

    /** Десятичная длина без промежуточной строки. */
    private static void writeInt(ByteBuf buf, int n) {
        if (n == 0) {
            buf.writeByte('0');
            return;
        }
        byte[] tmp = new byte[10];
        int i = tmp.length;
        int value = n;
        while (value > 0) {
            tmp[--i] = (byte) ('0' + value % 10);
            value /= 10;
        }
        buf.writeBytes(tmp, i, tmp.length - i);
    }

    /** На macOS класса epoll нет в classpath или он недоступен. Тогда остаётся NIO. */
    private static boolean epoll() {
        try {
            return Epoll.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Мягкий RLIMIT_NOFILE. Ресурс 7 на Linux и 8 на macOS. Если вызов не удался — 65535. */
    private static int fileLimit() {
        try {
            Linker linker = Linker.nativeLinker();
            MemorySegment fn = linker.defaultLookup().find("getrlimit").orElse(null);
            if (fn == null) {
                return 65_535;
            }
            var getrlimit = linker.downcallHandle(fn, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            int resource = System.getProperty("os.name", "").toLowerCase().contains("mac") ? 8 : 7;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment limit = arena.allocate(16);
                if ((int) getrlimit.invokeExact(resource, limit) != 0) {
                    return 65_535;
                }
                long cur = limit.get(ValueLayout.JAVA_LONG, 0);
                if (cur <= 0 || cur > Integer.MAX_VALUE) {
                    return 65_535;
                }
                return (int) cur;
            }
        } catch (Throwable e) {
            return 65_535;
        }
    }

    /** Один обработчик на все соединения. Sharable, потому что состояния в нём нет: оно в слотах. */
    @Sharable
    private final class Loop extends ChannelInboundHandlerAdapter {
        /** Новый сокет занимает слот. На потолке сначала сбрасываются молчащие. */
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (slots.count() >= highWater) {
                shed();
            }
            int slot = slots.acquire();
            if (slot < 0) {
                ctx.close();
                return;
            }
            slots.touch(slot, System.nanoTime());
            Conn conn = new Conn();
            conn.slot = slot;
            conn.channel = ctx.channel();
            conn.incoming = new Incoming();
            conns[slot] = conn;
            ctx.channel().attr(KEY).set(conn);
        }

        @Override
        /** Освобождает буферы и возвращает слот в свободный список. */
        public void channelInactive(ChannelHandlerContext ctx) {
            Conn conn = ctx.channel().attr(KEY).get();
            if (conn == null) {
                return;
            }
            if (conn.pending != null) {
                conn.pending.release();
                conn.pending = null;
            }
            if (conn.held != null) {
                conn.held.release();
                conn.held = null;
            }
            conns[conn.slot] = null;
            slots.release(conn.slot);
        }

        @Override
        /** Дописывает байты и ставит слот в кольцо. Разбор начнётся после текущего select. */
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            Conn conn = ctx.channel().attr(KEY).get();
            if (conn == null) {
                in.release();
                return;
            }
            if (conn.pending == null) {
                conn.pending = ctx.alloc().buffer(Math.max(in.readableBytes(), 128));
            }
            conn.pending.writeBytes(in);
            in.release();
            slots.touch(conn.slot, System.nanoTime());
            if (!conn.queued && ring.offer(conn.slot)) {
                conn.queued = true;
                if (!drainQueued) {
                    drainQueued = true;
                    ctx.executor().execute(Server.this::drain);
                }
            }
        }

        @Override
        /** Пока запись не разгребла буфер, новые байты не читаются. */
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            ctx.channel().config().setAutoRead(ctx.channel().isWritable());
        }

        @Override
        /** Битый сокет закрывается. Ошибка не печатается: на нагрузке это шум. */
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** Состояние одного соединения. Индекс в таблице слотов — его адрес. */
    private static final class Conn {
        int slot;
        Channel channel;
        ByteBuf pending;
        ByteBuf held;
        Incoming incoming;
        boolean queued;
        boolean listed;
        boolean closeAfter;
    }
}

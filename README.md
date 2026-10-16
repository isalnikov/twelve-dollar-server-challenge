# Twelve-dollar server

A Java 25 and Netty implementation of the [twelve-dollar server challenge](https://github.com/arjaythedev/twelve-dollar-server-challenge): five HTTP endpoints, SQLite, JWT HS256, and durable writes. The process listens directly. Nginx from the challenge config caps `worker_connections` at 16384 and spends a noticeable share of one core, so 40 000 keep-alive connections do not fit behind it.

Russian description: [README.ru.md](README.ru.md). Measured run: [RESULTS.md](RESULTS.md), [RESULTS.ru.md](RESULTS.ru.md).

## Endpoints

| Method | Path | Auth | Result |
|---|---|---|---|
| GET | `/health` | no | `{"status":"ok","db":"ok","uptime_s":N}` or 503 when SQLite does not answer |
| GET | `/feed` | no | 20 newest posts, `created_at DESC, id DESC` |
| GET | `/posts/:id` | no | one post, or 400 / 404 |
| POST | `/posts` | bearer | 201 and the stored post |
| POST | `/posts/:id/like` | bearer, checked before the id | 201 the first time, 200 on repeat, 404 if the post is missing |

JSON keys stay in the order `id`, `body`, `created_at`, `author`, `like_count`. Errors are `{"error":"..."}`. The body of a post is trimmed with the JavaScript whitespace set and must be 1–500 characters. JWT is HS256, secret `twelve-dollar-challenge`, `sub` a positive integer string, `username` a string, `exp` in seconds. There is no response cache and no token cache.

## How a request moves

One event-loop thread accepts, parses, talks to SQLite, and writes the response. A second thread only runs `PRAGMA wal_checkpoint(PASSIVE)` once a second on its own connection. A thread pool would only add scheduling on the one core the challenge machine has.

Two fixed tables, both 65 536 entries, both a power of two:

- **Slots.** A connection occupies a slot and nothing else while it is idle. The live list is ordered by last activity, so the head is the longest idle. When the count reaches the file-descriptor ceiling (`min(60000, RLIMIT_NOFILE - 1024)`), keep-alive connections silent for more than a second are closed. The client opens a new one on the next request. The same idle slot is not chosen twice.
- **Ring.** `channelRead` only appends bytes and pushes the slot index. After the current `select` batch, `drain` parses every queued connection. Every `INSERT` in that batch shares one `BEGIN IMMEDIATE` … `COMMIT`. Write responses stay in memory until the commit succeeds. A failed commit rolls back and answers 500, then closes.

HTTP/1.1 is parsed in `Http` (header cap 8192, body cap 16384). Reads ask SQLite for `json_object(...)` so the feed is already JSON. Inserts use `RETURNING` and a small Java builder so quotes in the body are escaped. SQLite is the amalgamation 3.54.0, called through the foreign-function API, not JDBC. Pragmas: WAL, `synchronous=NORMAL`, foreign keys, `busy_timeout=5000`, 256 MB mmap, 32 MB cache, memory temp store, `wal_autocheckpoint=0`.

On Linux the loop is epoll (`EpollEventLoopGroup`, native `linux-x86_64` and `linux-aarch_64`). Accepted sockets get `TCP_NODELAY`, `TCP_QUICKACK` and `TCP_NOTSENT_LOWAT` of 32 KB. Netty 4.2 ignores `EPOLL_MODE`. `SO_BUSY_POLL` and `TCP_CORK` are left off: on one vCPU they spend the core and hold small responses. `install.sh` sets `somaxconn` to 8192, turns off slow-start after idle, and raises the file-descriptor limit to 65535. This Mac has no epoll, so those options are not part of the measured 10 043 req/s. The JVM is Serial GC, a 512 MB heap touched up front, compact object headers, and Netty leak detection off.

## Layout

| Piece | Role |
|---|---|
| `Server` | bind, slots, ring, group commit, checkpoint thread |
| `Engine` | the five routes |
| `Db` | one SQLite connection and the prepared statements |
| `Http`, `Json`, `Jwt`, `Bodies`, `Ids` | request bytes, JSON, token, post body, ids |
| `Ring`, `Slots` | the two fixed tables |
| `schema.sql` | the challenge schema, unchanged |
| `src/test` | unit tests, `ServerIT`, and `LoadHarness` |

## Build and run

`build.sh` downloads the amalgamation, checks SHA3-256 `7b670a62fdfbd672b75fef004cb703c8a3e87d3a5cc7d675b4a08337004a2d93`, compiles `native/libsqlite3`, and writes `target/cp.txt`. The jar does not contain Netty, so `start.sh` puts that classpath on the command line. On a clean Ubuntu 24.04, once, as root: `bash install.sh`.

```bash
bash build.sh
SQLITE_PATH=seed/feed.db JWT_SECRET=twelve-dollar-challenge HOST=127.0.0.1 PORT=3000 bash start.sh
```

Tests, including the integration tests against a temporary database:

```bash
mvn test
```

Five-minute numbers, before and after the in-place parser, are in [RESULTS.md](RESULTS.md). A pure `java.nio` loop is started with `TRANSPORT=nio`; on this Mac the tuned Netty server was faster over five minutes.

Five-minute load (separate server process, closed loop, no think time):

```bash
bash load.sh
```

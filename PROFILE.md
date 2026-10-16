# JVM profile

Java Flight Recorder (`settings=profile`) on the server for a full run: warmup, ladder, feed, single post, and a five-minute mix. File `build/server.jfr`, 18 567 samples (`jdk.ExecutionSample` and `jdk.NativeMethodSample`). Same machine: MacBook Pro, Intel Core i5-8279U, 8 GB, macOS 15.8.1, Temurin 25.0.2. Transport is NIO. This Mac has no epoll.

Profile mode slows the process. This run: **7 565** req/s, p95 **32.41** ms, p99 **58.03** ms, max **735** ms, 0 errors. The clean run without a recording was **10 043** req/s, p95 22.35 ms, p99 32.70 ms. The table is share of time, not absolute speed.

## Where the time goes

| Share | Place | What it is |
|---:|---|---|
| 29.0% | `Db.feed` → `sqlite3_step` | Feed: 20 posts, `json_object`, like counts |
| 18.1% | `Server.flushHeld` / `Server.send` → `SocketDispatcher.write0` | Writing the response |
| 12.8% | socket read → `SocketDispatcher.read0` | Reading the request |
| 11.0% | `Db.commit` | Group `COMMIT`, WAL at `synchronous=NORMAL` |
| 5.8% | `KQueue.poll` | The thread is waiting on the network |
| 5.7% | `Db.post` → `sqlite3_step` | One post |
| 2.2% | `Db.like` | A like |
| 1.3% | `Db.checkpoint` | Passive WAL checkpoint once a second |
| 0.4% | `Db.insert` | Creating a post |
| ~1.4% | `Http.crlf`, `Http.headerEnd`, `Http.parse` | HTTP parse |
| 0.3% | `Jwt.authenticate` → `DigestBase.engineUpdate` | Token HMAC |

The feed is about 46% of requests and 29% of samples. Reading one post is also about 46% of requests but only 5.7% of samples. One feed request costs about five times one post. In this same run, feed only was 5 379 req/s and one post was 8 988 req/s.

## What is not the bottleneck

Serial GC pauses over the whole recording: **231 ms** total, 200 pauses, median 0.71 ms, p99 4.03 ms, max 49.2 ms. A 735 ms request tail is not a collector pause.

No monitor contention on the request path. `Java Thread Sleep` is the checkpoint thread sleeping one second at a time. HTTP parse and JWT together are under 2% of samples.

## The SQLite call tax

9 821 samples sit in `Db.call`. That is `MethodHandle.invokeWithArguments`: the stack shows `MethodHandle.asSpreader`, `Objects.equals`, `MethodType`, and `Class.reflectionData`. Every `step` and `exec` rebuilds the call shape. `Db.columnBytes` also copies the `json_object` column through `MemorySegment.toArray`.

Writing the response is the second slice, and it is already in the kernel (`write0`), not in JSON assembly. `Server.frame` is about 0.2% of samples.

## What follows

The feed query comes first: twenty like `count(*)` subqueries. The group commit is second. HTTP parse and JWT are not worth chasing. The method timings below show that switching to `invokeExact` saves about 0.2 µs per `sqlite3_step`.

## Method time, JEP 520

`jdk.MethodTiming` counts every invocation. The filter is the request methods, not `Db.step` or `Db.call`: a timer on every `sqlite3_step` inflates the feed. Recording `build/method-timing.jfr`, `settings=default`, started after 21 s of warmup. The window is one minute of the mix, 128 clients, closed loop.

This run: **7 559** req/s, p95 **28.53** ms, p99 **39.41** ms, max **298** ms, 0 errors, 453 367 requests. Instrumentation slows the server. The clean run was 10 043 req/s. A method's time includes everything it calls. `drain` includes `handle`, `commit`, and `flushHeld`, so those totals must not be added together.

| Method | Invocations | Average | Maximum | Total |
|---|---:|---:|---:|---:|
| `Db.feed` | 209 273 | 0.111 ms | 139 ms | 23.2 s |
| `Server.flushHeld` | 6 841 | 1.27 ms | 69.6 ms | 8.7 s |
| `Db.post` | 208 196 | 0.0197 ms | 6.77 ms | 4.1 s |
| `Server.send` | 83 133 | 0.0363 ms | 43.5 ms | 3.0 s |
| `Db.commit` | 6 841 | 0.432 ms | 32.5 ms | 3.0 s |
| `Db.like` | 31 581 | 0.0526 ms | 1.57 ms | 1.7 s |
| `Db.checkpoint` | 59 | 11.8 ms | 16.9 ms | 0.70 s |
| `Jwt.authenticate` | 35 898 | 0.0173 ms | 1.03 ms | 0.62 s |
| `Db.insert` | 4 317 | 0.115 ms | 2.35 ms | 0.50 s |
| `Http.parse` | 499 073 | 0.00165 ms | 5.14 ms | 0.82 s |
| `Server.frame` | 453 367 | 0.00114 ms | 1.50 ms | 0.52 s |
| `Engine.handle` | 453 367 | 0.0682 ms | 139 ms | 30.9 s |
| `Server.drain` | 7 137 | 6.71 ms | 184 ms | 47.9 s |

`drain` is a batch, about 63 requests, so 6.71 ms is not the cost of one request. `flushHeld` and `commit` are per batch too. `checkpoint` runs on the other thread.

Per request, `Db.feed` is the long one: 0.111 ms against 0.020 ms for `Db.post`. The feed is 46% of requests and half of the time inside `drain` (23.2 s of 47.9 s). It is the same `json_object` with a correlated `count(*)` on `likes`, repeated for 20 rows and 21 `sqlite3_step` calls.

A short recording that also timed `Db.step` and `Db.call`: `step` 7.39 µs, `call` 7.61 µs. The 0.2 µs gap is `invokeWithArguments`. Across 21 feed steps that is about 4 µs of the 111.

## What to change

1. Build the feed in one SQLite step: `json_group_array` over the 20 newest posts and one `count(*)` for those ids, instead of twenty correlated subqueries and a Java `step` loop. The JSON contract stays the same.
2. The checkpoint averages 11.8 ms once a second, on a second connection, while the event loop is also inside SQLite. The WAL lock shows up as a wait inside `commit` (tail 32.5 ms) and inside `feed` (tail 139 ms against an average of 0.111 ms). Call `wal_checkpoint(PASSIVE)` from the event loop between batches, when no transaction is open.
3. `flushHeld` is the second total, 8.7 s. That is the batch write to sockets. It is the next lever after the feed is cheaper.
4. Leave `Http.parse` (1.7 µs), `Server.frame` (1.1 µs), and the FFM wrapper alone.

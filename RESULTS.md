# Measured run

Local capacity of this checkout. It is not the official score on the $12 droplet (1 vCPU, 2 GB). The harness is a closed loop with no think time, so each client sends the next request as soon as the previous one returns. The official k6 script waits 3–15 seconds between steps, and most of those users are idle connections.

## Machine

| | |
|---|---|
| Computer | MacBook Pro (MacBookPro15,2) |
| CPU | Intel Core i5-8279U @ 2.40 GHz, 4 cores, 8 threads |
| Memory | 8 GB |
| OS | macOS 15.8.1 (build 24H32), x86_64 |
| JDK | Eclipse Temurin 25.0.2+10 |
| Transport | Netty NIO. Epoll is not available on macOS |
| Database | SQLite 3.54.0, built by `build.sh` into `native/libsqlite3.dylib` |
| Server heap | 512 MB, Serial GC, compact object headers |

The server and the load generator are separate JVMs, so client garbage collection is not inside the 512 MB heap. They still share the same four cores.

## What was loaded

Seed, written once with `synchronous=OFF` and then opened by the server under WAL and `synchronous=NORMAL`:

- 2 000 users
- 20 000 posts
- 80 000 likes

This is the same SQL shape as the challenge, not the official seed of 50 000 users, 500 000 posts and 2 000 000 likes. A feed still counts likes for the 20 newest posts through the index.

Mix, closed loop: about 46% `GET /feed`, 46% `GET /posts/:id`, 7% `POST /posts/:id/like`, 1% `POST /posts`. Pass line, same as the challenge: p95 under 500 ms, p99 under 1 s, errors under 1%. Percentiles are nearest-rank: after sorting, index `ceil(p/100*n) - 1`.

## Five minutes at 128 in-flight clients

`mixed c=128 300s req=1942622 rps=6475 err=0,000% p50=14,48ms p95=45,33ms p99=123,34ms max=897,72ms pass`

| | |
|---|---|
| Requests | 1 942 622 |
| Rate | 6 475 req/s |
| Errors | 0% |
| p50 | 14.48 ms |
| p95 | 45.33 ms |
| p99 | 123.34 ms |
| max | 897.72 ms |
| Verdict | PASS |

Heartbeats during the hold stayed at zero errors. The count of completed requests moved from 280 800 at 30 s to 1 768 044 at 270 s and 1 942 622 at 300 s.

## Ladder before the hold

Each step is 10 seconds of the same mix. Every step passed. The ladder stops at 128, so this run did not find a latency ceiling. Throughput levels off around 6–8 thousand requests per second; more clients mostly add queueing.

| Clients | Requests | req/s | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 33 598 | 3 360 | 0.24 ms | 0.58 ms | 0.85 ms | 34.23 ms |
| 2 | 43 823 | 4 382 | 0.35 ms | 0.90 ms | 2.15 ms | 120.33 ms |
| 4 | 57 161 | 5 716 | 0.53 ms | 1.60 ms | 3.06 ms | 68.26 ms |
| 8 | 70 470 | 7 046 | 0.88 ms | 2.61 ms | 4.76 ms | 67.98 ms |
| 16 | 71 256 | 7 124 | 1.80 ms | 5.15 ms | 8.80 ms | 78.35 ms |
| 32 | 49 505 | 4 948 | 5.11 ms | 14.53 ms | 26.70 ms | 141.94 ms |
| 64 | 78 152 | 7 810 | 7.29 ms | 15.63 ms | 22.77 ms | 60.49 ms |
| 128 | 63 679 | 6 360 | 16.89 ms | 39.29 ms | 71.72 ms | 262.85 ms |

Warmup, 2 clients, 5 seconds: 11 585 requests, 2 317 req/s, p95 1.93 ms, p99 3.50 ms, 0 errors.

## Reads alone, 128 clients, 20 seconds

| Scenario | Requests | req/s | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|
| feed only | 113 144 | 5 650 | 41.72 ms | 57.13 ms | 158.51 ms |
| one post | 182 081 | 9 099 | 25.58 ms | 44.46 ms | 193.69 ms |

Both had 0 errors. A single post is cheaper than the feed, which joins the author and counts likes for twenty rows.

## What this does not say

128 concurrent clients is the top of the ladder, not a measured maximum. At that point p95 is still about ten times under the 500 ms bar. The number of idle keep-alive users this process can hold is a different question: the slot table is sized for 65 536 and sheds above the file-descriptor ceiling, and this Mac's soft limit is far above 60 000, so the five-minute run never had to shed. A 1 vCPU droplet will not repeat the 6 475 req/s figure.

## After the in-place parser

The follow-up run is the same harness, the same seed and the same Mac. Two things changed. The parser no longer allocates a `byte[]` for every request: a heap buffer is scanned in place, and a direct buffer is copied once into a scratch array owned by the event-loop thread. Netty 4.2’s adaptive allocator is set explicitly (it is the 4.2 default and uses less direct memory than a pool of arenas sized for every core). The JVM flags add `-XX:+DisableExplicitGC` on top of the fixed 512 MB heap, `AlwaysPreTouch`, compact object headers (JEP 519) and Serial GC. Serial is the collector the Java 25 GC guide points at for a single processor. On Linux the accepted channel also sets `TCP_QUICKACK`; this Mac has no epoll, so that option was idle here.

`mixed c=128 300s req=3012849 rps=10043 err=0,000% p50=11,73ms p95=22,35ms p99=32,70ms max=266,42ms pass`

| | Before | After |
|---|---:|---:|
| Requests | 1 942 622 | 3 012 849 |
| Rate | 6 475 req/s | 10 043 req/s |
| Errors | 0% | 0% |
| p50 | 14.48 ms | 11.73 ms |
| p95 | 45.33 ms | 22.35 ms |
| p99 | 123.34 ms | 32.70 ms |
| max | 897.72 ms | 266.42 ms |

Throughput is about 55% higher. p95 is about half, p99 is about a quarter. A first attempt that called `ByteBuf.getByte` once per byte was slower than the original copy, and it was thrown away. The tight loop has to run on a raw array.

The same ladder, after the change:

| Clients | req/s | p95 | p99 |
|---|---:|---:|---:|
| 1 | 2 223 | 0.81 ms | 1.28 ms |
| 4 | 8 204 | 0.89 ms | 1.34 ms |
| 16 | 8 992 | 3.42 ms | 5.10 ms |
| 64 | 9 439 | 12.50 ms | 18.18 ms |
| 128 | 9 648 | 23.93 ms | 35.80 ms |

Feed only, 128 clients, 20 seconds: 6 301 req/s, p95 39.95 ms, p99 92.16 ms. One post: 13 252 req/s, p95 16.48 ms, p99 24.30 ms. Both had 0 errors.

## Pure `java.nio`, same engine

`TRANSPORT=nio` starts `NioServer`: one `Selector` thread, the same SQLite engine, the same group commit, no Netty. Same five-minute mix at 128 clients:

`mixed c=128 300s req=2497496 rps=8325 err=0,000% p50=14,58ms p95=25,29ms p99=35,67ms max=526,27ms pass`

| | Tuned Netty | java.nio |
|---|---:|---:|
| Rate | 10 043 req/s | 8 325 req/s |
| p95 | 22.35 ms | 25.29 ms |
| p99 | 32.70 ms | 35.67 ms |
| max | 266.42 ms | 526.27 ms |

One client is faster without Netty (3 308 req/s against 2 223). From four clients upward the tuned Netty loop holds a higher rate, and the five-minute tail is shorter. Replacing Netty does not buy another step on this machine. The default remains Netty.

## Feed SQL: what changed (before / after)

Everything above is still the in-place HTTP parser and tuned Netty/JVM flags. The next change is **only** how `/feed` loads like counts from SQLite.

**Before**

- One `SELECT` returned twenty rows. Each row’s `json_object` held a **correlated** subquery: `(SELECT count(*) FROM likes l WHERE l.post_id = p.id)`.
- Java called `sqlite3_step` up to twenty-one times and glued `{"posts":[` … `]}` in a `ByteArrayOutputStream`.

**After (shipped)**

- The same twenty rows and the same JSON key order (`id`, `body`, `created_at`, `author`, `like_count`).
- Likes for the feed are counted **once**: `GROUP BY post_id` over `likes` where `post_id IN (SELECT id FROM posts ORDER BY created_at DESC, id DESC LIMIT 20)`, then `LEFT JOIN` back to the top twenty posts.
- Java still loops over rows and builds the outer `{"posts":[...]}`; only the SQL plan changed. `GET /posts/:id` still uses one correlated `count(*)` per post.

**Variants tried on the same seed (2k users, 20k posts, 80k likes)**

| Variant | Micro-benchmark (3k feeds) | Outcome |
|---|---:|---|
| Correlated `count(*)` per row | ~211 µs | Before |
| `json_group_array` + CTE, one `step` | ~2 077 µs | Rejected (~10× slower) |
| CTE + row loop, `GROUP BY` likes | ~2 029 µs | Rejected |
| `IN (top 20 ids)` + `GROUP BY` likes, row loop | ~204 µs | **Shipped** |

JEP 520 (`TIMING=1`, one minute mixed at 128 clients, recording overhead): `Db.feed()` average **0.111 ms → 0.070 ms**; see `PROFILE.md`.

**Noisy reruns on a loaded Mac** (same code, `bash load.sh`): **6 394** and **2 517** req/s on the five-minute mix — background CPU load, not a slower query. After clearing load, the run below landed above the pre-change **10 043** req/s figure.

## Five-minute hold after feed SQL (clean machine)

`bash load.sh`, 2026-10-16, MacBook Pro 15,2, Temurin 25, log `build/clean-load.log`, no JFR:

`mixed c=128 300s req=3203227 rps=10677 err=0,000% p50=10,64ms p95=22,56ms p99=33,58ms max=242,75ms pass`

| | After in-place parser only | After feed SQL (this run) |
|---|---:|---:|
| Requests (300 s) | 3 012 849 | 3 203 227 |
| Rate | 10 043 req/s | **10 677 req/s** |
| p50 | 11.73 ms | **10.64 ms** |
| p95 | 22.35 ms | 22.56 ms |
| p99 | 32.70 ms | 33.58 ms |
| max | 266.42 ms | **242.75 ms** |
| Errors | 0% | 0% |

Short ladder (10 s probes), same run:

| Clients | req/s | p95 | p99 |
|---:|---:|---:|---:|
| 1 | 2 997 | 0.64 ms | 0.89 ms |
| 2 | 6 945 | 0.48 ms | 0.65 ms |
| 16 | 6 953 | 4.50 ms | 6.34 ms |
| 32 | 8 126 | 7.07 ms | 9.91 ms |
| 128 | 7 346 | 27.99 ms | 38.31 ms |

Feed only, 128 clients, 20 s: **6 475** req/s, p95 **32.98** ms, p99 45.69 ms (was 6 301 / 39.95 / 92.16 after the parser). One post: **11 718** req/s, p95 18.28 ms, p99 29.34 ms (was 13 252 / 16.48 / 24.30). Both had 0 errors.

**Best verified mixed hold on this laptop: 10 677 req/s** with the feed SQL above. Reproduce profiling with `TIMING=1 bash load.sh` and `jfr view method-timing build/method-timing.jfr`.

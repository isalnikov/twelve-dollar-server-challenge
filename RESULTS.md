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

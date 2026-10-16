# Python + FastAPI

| | |
|---|---|
| Language | Python 3.12.3 (Ubuntu 24.04's `python3` package) |
| Framework | FastAPI 0.142.2 (Starlette 1.7.0) |
| Server | uvicorn 0.54.0 with uvloop 0.23.0 (event loop) and httptools 0.8.0 (HTTP parser) |
| SQLite driver | the standard library `sqlite3` module, linked to Ubuntu's SQLite 3.45.1 |
| JSON | orjson 3.12.0 |
| **Nginx or direct** | **Direct**: serves `0.0.0.0:80` itself |

All dependency versions, transitive ones included, are pinned in `requirements.txt`.

## Running it

```bash
sudo bash install.sh   # apt: python3, python3-venv
bash build.sh          # creates .venv and installs requirements.txt
SQLITE_PATH=... JWT_SECRET=... HOST=0.0.0.0 PORT=80 bash start.sh
```

## Optimizations, and why

- **One process, async handlers, SQLite called inline on the event loop.** The box has one vCPU, so
  extra worker processes or a threadpool would only add context switches. Every query is an index or
  primary-key lookup that takes well under a millisecond. One connection also means writes never wait
  on SQLite locks.
- **Direct instead of Nginx.** On one core, Nginx's CPU comes straight out of Python's budget. In my
  1-core load tests, direct mode had the better p99 near saturation, and p99 < 1 s is the limit that
  fails first. Nginx's `worker_connections 16384` also counts upstream connections, so it caps out
  around 16k users. With 15,000 extra idle keep-alive connections held open during a load run, uvicorn
  dropped none of them and served 0 errors, using about 250 MB extra.
- **uvloop + httptools**: the fastest event loop and HTTP parser uvicorn supports. Access log and
  proxy-headers middleware are off.
- **Lean FastAPI handlers.** There are no pydantic models or response models. Handlers take a raw
  `str` path parameter, validate it themselves, and return a prebuilt `Response` with orjson bytes,
  which skips FastAPI's validation and `jsonable_encoder` work. `/docs` and `/openapi.json` are
  disabled, so unknown paths really return 404.
- **JWT verified by hand** with `hmac` + `hashlib` on every request: HS256 only, constant-time
  signature compare, `exp`/`nbf` checked. No PyJWT overhead, and no caching of verifications (rule 5).
- **SQL**: the reference queries. Each like is a single statement,
  `INSERT ... SELECT ... WHERE EXISTS (post) ON CONFLICT DO NOTHING`; a post-existence check runs only
  when it inserted nothing, to tell "already liked" from "no such post".
- **Pragmas**: `journal_mode=WAL`, `synchronous=NORMAL` (rule 6), 1 GiB `mmap_size`, 64 MiB page cache.
  Autocommit mode commits each write before its response is sent.
- **GC**: objects created at startup are frozen and the gen-0 threshold is raised, because request
  handling creates almost no reference cycles.
- **Keep-alive timeout of 75 s** (uvicorn's default is 5 s), so idle clients keep their connections.

## License

MIT, under the repo's [license](../../LICENSE).

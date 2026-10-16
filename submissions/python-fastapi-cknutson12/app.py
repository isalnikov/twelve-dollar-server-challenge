"""The $12 server challenge API: FastAPI on SQLite, tuned for one vCPU.

Every handler is `async def` and calls SQLite inline on the event loop. The queries are all
index lookups that finish in well under a millisecond, so handing them to a threadpool would
only add a thread hop per request on a machine that has a single core anyway.
"""
import base64
import gc
import hashlib
import hmac
import os
import re
import sqlite3
import time

import orjson
from fastapi import FastAPI, Request
from fastapi.responses import Response
from starlette.exceptions import HTTPException as StarletteHTTPException

SQLITE_PATH = os.environ["SQLITE_PATH"]
JWT_SECRET = os.environ["JWT_SECRET"].encode()
START = time.monotonic()
MAX_ID = 2**63 - 1  # SQLite's INTEGER range; larger ids cannot exist

# --- database ---------------------------------------------------------------------------------

# Autocommit (isolation_level=None): every INSERT is its own transaction, committed before
# execute()/fetchall() returns, so a 201 is only sent for a committed row.
db = sqlite3.connect(SQLITE_PATH, isolation_level=None, check_same_thread=False, cached_statements=32)
db.execute("PRAGMA journal_mode = WAL")
db.execute("PRAGMA synchronous = NORMAL")  # rule 6: WAL + NORMAL
db.execute("PRAGMA busy_timeout = 5000")
db.execute("PRAGMA mmap_size = 1073741824")  # read the file through the OS page cache, no copies
db.execute("PRAGMA cache_size = -65536")  # 64 MiB
db.execute("PRAGMA temp_store = MEMORY")

POST_SELECT = """
SELECT p.id, p.body, p.created_at, u.username,
       (SELECT count(*) FROM likes l WHERE l.post_id = p.id)
  FROM posts p JOIN users u ON u.id = p.user_id
"""
FEED_SQL = POST_SELECT + " ORDER BY p.created_at DESC, p.id DESC LIMIT 20"
POST_SQL = POST_SELECT + " WHERE p.id = ?"
INSERT_POST_SQL = "INSERT INTO posts (user_id, body) VALUES (?, ?) RETURNING id, created_at"
# Inserts only if the post exists; 0 changes means "already liked" or "no such post".
INSERT_LIKE_SQL = """
INSERT INTO likes (user_id, post_id)
SELECT ?1, ?2 WHERE EXISTS (SELECT 1 FROM posts WHERE id = ?2)
ON CONFLICT (user_id, post_id) DO NOTHING
"""
POST_EXISTS_SQL = "SELECT 1 FROM posts WHERE id = ?"

# --- responses --------------------------------------------------------------------------------


def json_response(status: int, data) -> Response:
    return Response(orjson.dumps(data), status_code=status, media_type="application/json")


def error(status: int, message: str) -> Response:
    return json_response(status, {"error": message})


def post_object(row) -> dict:
    return {"id": row[0], "body": row[1], "created_at": row[2], "author": row[3], "like_count": row[4]}


def parse_id(raw: str):
    """A positive integer in plain decimal digits, else None."""
    if not (raw.isascii() and raw.isdigit()):
        return None
    n = int(raw)
    return n if n > 0 else None


# --- auth -------------------------------------------------------------------------------------

B64URL = re.compile(r"[A-Za-z0-9_-]*")


def b64url_decode(s: str) -> bytes:
    if not B64URL.fullmatch(s):
        raise ValueError("not base64url")
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def authenticate(request: Request):
    """Returns (user_id, username), or an error Response."""
    header = request.headers.get("authorization")
    if header is None or not header.startswith("Bearer "):
        return error(401, "missing bearer token")
    try:
        head_b64, payload_b64, sig_b64 = header[7:].split(".")
        head = orjson.loads(b64url_decode(head_b64))
        if not isinstance(head, dict) or head.get("alg") != "HS256":
            raise ValueError("alg")
        digest = hmac.new(JWT_SECRET, f"{head_b64}.{payload_b64}".encode(), hashlib.sha256).digest()
        expected = base64.urlsafe_b64encode(digest).rstrip(b"=")
        if not hmac.compare_digest(expected, sig_b64.encode()):
            raise ValueError("signature")
        payload = orjson.loads(b64url_decode(payload_b64))
        if not isinstance(payload, dict):
            raise ValueError("payload")
        now = int(time.time())
        exp = payload.get("exp")
        if exp is not None and (not isinstance(exp, (int, float)) or isinstance(exp, bool) or now >= exp):
            raise ValueError("exp")
        nbf = payload.get("nbf")
        if nbf is not None and (not isinstance(nbf, (int, float)) or isinstance(nbf, bool) or now < nbf):
            raise ValueError("nbf")
    except (ValueError, UnicodeError):  # includes orjson.JSONDecodeError and binascii.Error
        return error(401, "invalid or expired token")
    sub = payload.get("sub")
    username = payload.get("username")
    user_id = parse_id(sub) if isinstance(sub, str) else None
    if user_id is None or user_id > MAX_ID or not isinstance(username, str):
        return error(401, "invalid token payload")
    return user_id, username


# JavaScript's String.prototype.trim() set (WhiteSpace + LineTerminator), so bodies are trimmed
# exactly like the reference implementations.
WHITESPACE = "\t\n\v\f\r                  　﻿"

# --- app --------------------------------------------------------------------------------------

app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None, redirect_slashes=False)


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    if exc.status_code == 404:
        return error(404, "not found")
    return error(exc.status_code, str(exc.detail).lower())


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    return error(500, "internal server error")


@app.get("/health")
async def health():
    try:
        db.execute("SELECT 1").fetchone()
    except Exception as exc:
        return json_response(503, {"status": "degraded", "db": "unreachable", "error": str(exc)})
    return json_response(200, {"status": "ok", "db": "ok", "uptime_s": int(time.monotonic() - START)})


@app.get("/feed")
async def feed():
    rows = db.execute(FEED_SQL).fetchall()
    return json_response(200, {"posts": [post_object(r) for r in rows]})


@app.get("/posts/{post_id}")
async def get_post(post_id: str):
    pid = parse_id(post_id)
    if pid is None:
        return error(400, "invalid post id")
    row = db.execute(POST_SQL, (pid,)).fetchone() if pid <= MAX_ID else None
    if row is None:
        return error(404, "post not found")
    return json_response(200, {"post": post_object(row)})


@app.post("/posts")
async def create_post(request: Request):
    user = authenticate(request)
    if isinstance(user, Response):
        return user
    user_id, username = user
    try:
        data = orjson.loads(await request.body())
    except orjson.JSONDecodeError:
        return error(400, "malformed JSON body")
    body = data.get("body") if isinstance(data, dict) else None
    if not isinstance(body, str):
        return error(400, "body is required")
    body = body.strip(WHITESPACE)
    if not body:
        return error(400, "body is required")
    if len(body) > 500:
        return error(400, "body must be at most 500 characters")
    # fetchall() steps the statement to completion, which commits the autocommit transaction.
    post_id, created_at = db.execute(INSERT_POST_SQL, (user_id, body)).fetchall()[0]
    return json_response(
        201,
        {"post": {"id": post_id, "body": body, "created_at": created_at, "author": username, "like_count": 0}},
    )


@app.post("/posts/{post_id}/like")
async def like_post(post_id: str, request: Request):
    user = authenticate(request)  # auth before the id, per the spec
    if isinstance(user, Response):
        return user
    pid = parse_id(post_id)
    if pid is None:
        return error(400, "invalid post id")
    if pid <= MAX_ID:
        if db.execute(INSERT_LIKE_SQL, (user[0], pid)).rowcount == 1:
            return json_response(201, {"liked": True, "already_liked": False, "post_id": pid})
        if db.execute(POST_EXISTS_SQL, (pid,)).fetchone() is not None:
            return json_response(200, {"liked": True, "already_liked": True, "post_id": pid})
    return error(404, "post not found")


# Request handling allocates almost nothing that forms reference cycles, so the cyclic GC mostly
# wastes time walking long-lived objects. Freeze everything created at import and run it less often.
gc.collect()
gc.freeze()
gc.set_threshold(50_000, 20, 20)

# GraalVM Native Image

Build a standalone executable (no JVM at runtime). Needs **GraalVM CE** with `native-image`, for example:

```bash
sdk install java 25.0.1-graalce
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.1-graalce"
bash native-build.sh
./target/twelve-dollar-server   # same env as start.sh: SQLITE_PATH, JWT_SECRET, HOST, PORT
```

`native/libsqlite3.dylib` (or `.so`) is still loaded at runtime via FFM; metadata lives in `src/main/resources/META-INF/native-image/challenge/twelve-dollar-server/reachability-metadata.json` (SQLite downcalls, `-H:+SharedArenaSupport` for `Arena.ofShared`).

## Five-minute mixed hold (ONLY_MIXED=1, c=128, same seed, MacBook Pro 15,2, 2026-10-17)

| | JVM (Temurin 25.0.2, 512 MB heap) | Native (`target/twelve-dollar-server`, ~25 MB file) |
|---|---:|---:|
| Rate | **12 238 req/s** | **2 156 req/s** |
| p50 | 9.74 ms | 60.70 ms |
| p95 | 18.02 ms | 100.18 ms |
| p99 | 25.07 ms | 134.23 ms |
| max | 241 ms | 520 ms |
| Peak RSS (server process) | **607.6 MB** | **66.5 MB** |
| Errors | 0% | 0% |

Logs: `build/native-compare-jvm.log`, `build/native-compare-native.log`.

Reproduce:

```bash
export JAVA_HOME=/path/to/temurin-25
ONLY_MIXED=1 MEM_SAMPLE=1 bash load.sh
ONLY_MIXED=1 NATIVE=1 MEM_SAMPLE=1 bash load.sh
```

## Conclusion

Native Image cuts resident memory by about **9×** but this server is **much slower** on throughput (~5.7× lower req/s) under the closed-loop harness. The hot path uses Netty, many `MethodHandle` FFM calls into SQLite, and group commit; the substrate VM does not match a warmed-up JVM for that mix. **Production choice on this project: JVM** (`start.sh`). Native remains useful for small footprint or cold-start experiments, not for maximum req/s on this Mac.

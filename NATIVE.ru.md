# GraalVM Native Image

Сборка отдельного исполняемого файла (без JVM на рантайме). Нужны **GraalVM CE** и `native-image`, например:

```bash
sdk install java 25.0.1-graalce
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.1-graalce"
bash native-build.sh
./target/twelve-dollar-server   # те же переменные, что у start.sh: SQLITE_PATH, JWT_SECRET, HOST, PORT
```

`native/libsqlite3.dylib` (или `.so`) по-прежнему подгружается через FFM; метаданные — в `src/main/resources/META-INF/native-image/challenge/twelve-dollar-server/reachability-metadata.json` (downcall SQLite, `-H:+SharedArenaSupport` для `Arena.ofShared`).

## Пятиминутка смеси (ONLY_MIXED=1, c=128, тот же сид, MacBook Pro 15,2, 2026-10-17)

| | JVM (Temurin 25.0.2, куча 512 МБ) | Native (`target/twelve-dollar-server`, файл ~25 МБ) |
|---|---:|---:|
| Скорость | **12 238 зап/с** | **2 156 зап/с** |
| p50 | 9,74 мс | 60,70 мс |
| p95 | 18,02 мс | 100,18 мс |
| p99 | 25,07 мс | 134,23 мс |
| max | 241 мс | 520 мс |
| Пик RSS (процесс сервера) | **607,6 МБ** | **66,5 МБ** |
| Ошибки | 0% | 0% |

Логи: `build/native-compare-jvm.log`, `build/native-compare-native.log`.

Повтор:

```bash
export JAVA_HOME=/path/to/temurin-25
ONLY_MIXED=1 MEM_SAMPLE=1 bash load.sh
ONLY_MIXED=1 NATIVE=1 MEM_SAMPLE=1 bash load.sh
```

## Вывод

Native Image снижает память примерно в **9 раз**, но по пропускной способности на этом сервере **сильно проигрывает** (~5,7× меньше зап/с) в закрытом цикле нагрузки. Горячий путь — Netty, частые FFM через `MethodHandle`, групповой коммит; substrate VM после прогрева не догоняет JVM. **Для максимальной скорости — JVM** (`start.sh`). Native имеет смысл для малого footprint или экспериментов со стартом, не для рекорда req/s на этом Mac.

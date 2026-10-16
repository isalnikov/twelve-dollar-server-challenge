#!/usr/bin/env bash
# Слушает $HOST:$PORT. Нужны SQLITE_PATH и JWT_SECRET. Сначала bash build.sh.
set -euo pipefail
cd "$(dirname "$0")"
# Serial — сборщик для одного процессора (руководство Oracle по GC, Java 25).
# Куча фиксирована и трогается заранее, чтобы пауза не ждала страницы у ядра.
# Compact object headers — JEP 519. Adaptive — аллокатор Netty 4.2 по умолчанию.
exec java \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseSerialGC \
  -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -XX:+UseCompactObjectHeaders \
  -XX:+DisableExplicitGC \
  -XX:-UsePerfData \
  -Dio.netty.allocator.type=adaptive \
  -Dio.netty.noPreferDirect=false \
  -Dio.netty.leakDetection.level=disabled \
  -Djava.library.path=native \
  -cp "target/twelve-dollar-server-1.0.0.jar:$(cat target/cp.txt)" \
  app.Main

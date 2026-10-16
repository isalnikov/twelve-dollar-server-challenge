#!/usr/bin/env bash
# Слушает $HOST:$PORT. Нужны SQLITE_PATH и JWT_SECRET. Сначала bash build.sh.
set -euo pipefail
cd "$(dirname "$0")"
# На Linux поднимаем потолок дескрипторов до таблицы слотов и ужимаем арены glibc:
# потоков мало, лишние арены malloc только держат замки. Нет прав — продолжаем как есть.
if [[ "$(uname -s)" == Linux ]]; then
  ulimit -n 65535 2>/dev/null || true
  export MALLOC_ARENA_MAX=2
  sysctl -w net.core.somaxconn=8192 >/dev/null 2>&1 || true
  sysctl -w net.ipv4.tcp_max_syn_backlog=8192 >/dev/null 2>&1 || true
  sysctl -w net.ipv4.tcp_slow_start_after_idle=0 >/dev/null 2>&1 || true
fi
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

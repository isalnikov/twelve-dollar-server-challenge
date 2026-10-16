#!/usr/bin/env bash
# Слушает $HOST:$PORT. Нужны SQLITE_PATH и JWT_SECRET. Сначала bash build.sh.
set -euo pipefail
cd "$(dirname "$0")"
exec java \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseSerialGC \
  -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -XX:+UseCompactObjectHeaders \
  -XX:-UsePerfData \
  -Dio.netty.leakDetection.level=disabled \
  -Djava.library.path=native \
  -cp "target/twelve-dollar-server-1.0.0.jar:$(cat target/cp.txt)" \
  app.Main

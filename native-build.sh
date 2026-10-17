#!/usr/bin/env bash
# Собирает нативный бинарник GraalVM Native Image. Нужны GraalVM CE и native-image (sdk install java 25.0.1-graalce).
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" ]] || ! command -v native-image >/dev/null; then
  if [[ -f "$HOME/.sdkman/candidates/java/25.0.1-graalce/bin/native-image" ]]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.1-graalce"
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    echo "GraalVM with native-image required (e.g. sdk install java 25.0.1-graalce)" >&2
    exit 1
  fi
fi
bash build.sh
CP="target/twelve-dollar-server-1.0.0.jar:$(cat target/cp.txt)"
native-image \
  --enable-native-access=ALL-UNNAMED \
  -H:+SharedArenaSupport \
  --exact-reachability-metadata=app \
  -H:+ReportExceptionStackTraces \
  --no-fallback \
  -o target/twelve-dollar-server \
  -cp "$CP" \
  app.Main
echo "built target/twelve-dollar-server"

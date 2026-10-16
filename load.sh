#!/usr/bin/env bash
# Собирает сервер и гоняет нагрузку. Смешанный сценарий держится пять минут.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package test-compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/cp.txt
CP="target/classes:target/test-classes:$(cat target/cp.txt)"
exec java --enable-native-access=ALL-UNNAMED -Dserver.classpath="target/classes:$(cat target/cp.txt)" -cp "$CP" app.LoadHarness

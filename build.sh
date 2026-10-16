#!/usr/bin/env bash
# Собирает свою libsqlite3 и jar. Класс Netty в jar не входит: путь к нему пишется в target/cp.txt.
set -euo pipefail
cd "$(dirname "$0")"
root="build/sqlite-amalgamation-3540000"
zip="build/sqlite-amalgamation-3540000.zip"
if [[ ! -f "$root/sqlite3.c" ]]; then
  mkdir -p build
  curl -fsSL -o "$zip" "https://sqlite.org/2026/sqlite-amalgamation-3540000.zip"
  actual="$(openssl dgst -sha3-256 "$zip" | awk '{print $NF}')"
  [[ "$actual" == "7b670a62fdfbd672b75fef004cb703c8a3e87d3a5cc7d675b4a08337004a2d93" ]]
  unzip -qo "$zip" -d build
fi
mkdir -p native
flags=(-O2 -fPIC -DSQLITE_THREADSAFE=2 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_OMIT_DEPRECATED
  -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_DQS=0 -DSQLITE_USE_ALLOCA -DSQLITE_OMIT_LOAD_EXTENSION
  -DSQLITE_OMIT_PROGRESS_CALLBACK -DSQLITE_OMIT_DECLTYPE -DSQLITE_MAX_EXPR_DEPTH=0
  -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1)
if [[ "$(uname -s)" == "Darwin" ]]; then
  cc "${flags[@]}" -dynamiclib -o native/libsqlite3.dylib "$root/sqlite3.c" -lm
else
  cc "${flags[@]}" -shared -o native/libsqlite3.so "$root/sqlite3.c" -ldl -lpthread -lm
fi
mvn -q -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/cp.txt

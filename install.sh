#!/usr/bin/env bash
# Один раз от root на чистой Ubuntu 24.04. На macOS JDK 25 и компилятор C уже есть.
set -euo pipefail
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "install.sh targets Ubuntu 24.04; this machine already has a JDK and a compiler"
  exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y build-essential curl unzip ca-certificates
if ! java -version 2>&1 | grep -q 'version "25'; then
  tmp="$(mktemp)"
  curl -fsSL -o "$tmp" "https://api.adoptium.net/v3/binary/version/jdk-25.0.2%2B10/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p /opt/jdk-25
  tar -C /opt/jdk-25 --strip-components=1 -xzf "$tmp"
  rm -f "$tmp"
  ln -sfn /opt/jdk-25/bin/java /usr/local/bin/java
  ln -sfn /opt/jdk-25/bin/javac /usr/local/bin/javac
fi
if ! command -v mvn >/dev/null; then
  tmp="$(mktemp)"
  curl -fsSL -o "$tmp" "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/apache-maven-3.9.12-bin.tar.gz"
  tar -C /opt -xzf "$tmp"
  rm -f "$tmp"
  ln -sfn /opt/apache-maven-3.9.12/bin/mvn /usr/local/bin/mvn
fi
# Backlog сокета 8192. Без somaxconn ядро обрежет очередь.
# tcp_slow_start_after_idle=0 не сбрасывает окно после паузы клиента в задании (3–15 с).
cat >/etc/sysctl.d/99-twelve-dollar.conf <<'EOF'
net.core.somaxconn = 8192
net.ipv4.tcp_max_syn_backlog = 8192
net.core.netdev_max_backlog = 8192
net.ipv4.tcp_slow_start_after_idle = 0
EOF
sysctl --system >/dev/null
printf '* soft nofile 65535\n* hard nofile 65535\n' >/etc/security/limits.d/99-twelve-dollar.conf

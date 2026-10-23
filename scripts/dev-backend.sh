#!/bin/bash
# 本地启动 platform-bootstrap（dev profile）
# 用法: scripts/dev-backend.sh
#
# ⚠️ 为什么不用 IDE 启动：IDE 里的运行实例是**旧构建**，
#    登录响应缺 roles → 前端拿不到角色 → 管理菜单整组不可见。
#    本脚本跑最新打包产物（platform-bootstrap-*-exec.jar）。
set -a
# shellcheck disable=SC1091
source "$(dirname "$0")/../config.env"
set +a

JAVA_HOME=${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}
JAR="$(dirname "$0")/../platform/platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT-exec.jar"

if [ ! -f "$JAR" ]; then
  echo "缺少产物 $JAR —— 请先执行：mvn -o -pl platform-bootstrap -am package -DskipTests" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -jar "$JAR"

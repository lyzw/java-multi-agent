#!/bin/bash
# 本地启动 platform-bootstrap（dev profile）
# 用法: scripts/dev-backend.sh
#
# ⚠️ 为什么不用 IDE 启动：IDE 里的运行实例是**旧构建**，
#    登录响应缺 roles → 前端拿不到角色 → 管理菜单整组不可见。
#    本脚本跑最新打包产物（platform-bootstrap-*-exec.jar）。
set -a
# shellcheck disable=SC1091
source "$(dirname "$0")/../platform.env"
# JWT 密钥对单独一个文件（scripts/gen-jwt-keys.sh 生成，含私钥，被 .gitignore 忽略）。
# 不并进 platform.env 是因为两个文件都要能被 IDEA 的 EnvFile 插件解析，
# 而插件不认 source/if —— 这里的合并只走 shell 通道。
# 缺它 = dev profile 每次启动生成新密钥 → 平台一重启，RAG 配的验签公钥就作废。
[ -f "$(dirname "$0")/../jwt.env" ] && source "$(dirname "$0")/../jwt.env"
set +a

JAVA_HOME=${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}
JAR="$(dirname "$0")/../platform/platform-bootstrap/target/platform-bootstrap-1.0.1-SNAPSHOT-exec.jar"

if [ ! -f "$JAR" ]; then
  echo "缺少产物 $JAR —— 请先执行：mvn -o -pl platform-bootstrap -am package -DskipTests" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -jar "$JAR"

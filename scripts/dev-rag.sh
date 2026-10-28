#!/bin/bash
# 本地启动 rag-bootstrap（RAG 检索/管理 HTTP 服务）
# 用法: scripts/dev-rag.sh [端口]        默认取 rag.env 里的 RAG_SERVER_PORT
#
# 与 scripts/dev-backend.sh 的分工：那个起平台（8080），这个起 RAG（8081）。
# 两边的 env 文件按属主分开，不要互相 source（库不同、角色权限不同）：
#   platform.env ←→ 平台     |     rag.env ←→ RAG     |     jwt.env ←→ 两侧共用
#
# ⚠️ IDEA 里跑同一个服务时，EnvFile 挂 rag.env + jwt.env 两个文件即可；
#    本脚本只是把同一份配置走 shell 通道，两者读的是完全相同的键。
# ⚠️ rag-worker（索引流水线）是另一个进程：它只需要 rag.env，
#    不需要 JWT 公钥（它不发请求，没有调用方身份）。
set -euo pipefail
cd "$(dirname "$0")/.."

set -a
# shellcheck disable=SC1091
source ./rag.env
# 验签公钥与平台签发私钥同源：两侧读同一个 jwt.env，杜绝「配了另一把公钥」的漂移。
[ -f ./jwt.env ] && source ./jwt.env
set +a

PORT="${1:-${RAG_SERVER_PORT:-8081}}"
JAR="rag/rag-bootstrap/target/rag-bootstrap-1.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "缺少产物 $JAR —— 请先执行：cd rag && mvn -o -pl rag-bootstrap -am package -DskipTests" >&2
  exit 1
fi

JAVA_HOME=${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}
# ⚠️ 端口显式给命令行：宿主机若存在 SERVER_PORT / SERVER__PORT，
#    Spring 的 relaxed binding 会让它压过 rag.env 与 application.yml。
exec "$JAVA_HOME/bin/java" -jar "$JAR" --server.port="$PORT"

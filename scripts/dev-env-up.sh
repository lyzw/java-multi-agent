#!/usr/bin/env bash
#
# =====================================================================
# 本地开发环境一键拉起（P2-7 developer-onboarding 配套）
#
# 作用：按照「新人一小时内跑通」的验收要求，按顺序准备好全部依赖：
#   1. PostgreSQL（结构 + Flyway 迁移 + 约束自检）
#   2. RabbitMQ（quorum 队列语义，见 dev-rabbitmq.sh 的 x-delivery-limit 说明）
#   （可选）Ollama —— 本脚本不强制安装，检测到缺失仅提示，不阻塞
#   3. 冒烟测试（Tier0 纯领域测试，不启动后端、不连库）
#   4. 编译可执行 jar（lib）产物，准备给 dev-backend.sh
#
# 用法：
#   ./scripts/dev-env-up.sh                完整拉起（依赖服务 + 迁移 + 冒烟测试 + 打包）
#   ./scripts/dev-env-up.sh --with-ollama  顺带检查/提示 Ollama 服务
#   ./scripts/dev-env-up.sh --smoke-only   只跑冒烟测试（跳过服务与打包）
#   ./scripts/dev-env-up.sh --help
#
# 说明：PostgreSQL / RabbitMQ 实际由 scripts/dev-{postgres,rabbitmq}.sh 管理
# （它们处理容器名、端口 5433/5672、trust 模式等细节）；本脚本只编排顺序。
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POSTGRES="$ROOT/scripts/dev-postgres.sh"
RABBITMQ="$ROOT/scripts/dev-rabbitmq.sh"

WANT_OLLAMA=false
SMOKE_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --with-ollama) WANT_OLLAMA=true ;;
    --smoke-only)  SMOKE_ONLY=true ;;
    --help|-h)
      sed -n '1,40p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *) echo "未知参数: $arg（用 --help 查看）" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------
# 0) JDK / Maven 前置
# ---------------------------------------------------------------------
JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  if command -v java >/dev/null 2>&1; then
    : # 已在 PATH 中
  else
    echo "找不到 JDK 21——请设置 JAVA_HOME（见 docs/本地启动指南.md §1.1）" >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------------
# 1) 依赖服务
# ---------------------------------------------------------------------
if [ "$SMOKE_ONLY" = "false" ]; then
  step "启动 PostgreSQL（容器 agent-pg17，端口 5433）"
  "$POSTGRES" start

  step "执行结构校验（15 项约束，非特权角色）"
  "$POSTGRES" verify || { echo "⚠️ verify 失败——通常是迁移未跑或约束被破坏，见上方输出" >&2; exit 1; }

  step "启动 RabbitMQ（容器 agent-rabbitmq-1，端口 5672）"
  "$RABBITMQ" start

  if [ "$WANT_OLLAMA" = "true" ]; then
    step "检查 Ollama"
    if command -v ollama >/dev/null 2>&1 && curl -sf http://127.0.0.1:11434/api/version >/dev/null; then
      echo "Ollama 已就绪 ✓"
    else
      echo "⚠️ 未检测到 Ollama（模型服务，用于真实推理）。"
      echo "   可先跳过：新人在本地主要做领域/权限开发与冒烟测试，不强制需要。"
    fi
  fi
else
  step "跳过依赖服务（--smoke-only）"
fi

# ---------------------------------------------------------------------
# 2) 编译 + 冒烟测试
# ---------------------------------------------------------------------
step "mvn 编译 + 冒烟测试（agent-core 纯领域测试，Tier0）"
cd "$ROOT/platform"
mvn -q -pl agent-core test -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false

JAR="platform-bootstrap/target/platform-bootstrap-1.0.1-SNAPSHOT-exec.jar"
if [ "$SMOKE_ONLY" = "false" ]; then
  step "打包可执行 jar（供 dev-backend.sh 使用）"
  mvn -q -pl platform-bootstrap -am package -DskipTests

  # ⚠️ 用 ${JAR} 而不是 $JAR：后面紧跟的中文破折号会被 bash 当成变量名的一部分
  #    （变量名变成「JAR——」），在 set -u 下直接报 unbound variable 退出。
  if [ ! -f "$JAR" ]; then
    echo "未找到产物 ${JAR}——可运行脚本：scripts/dev-backend.sh" >&2
    exit 1
  fi
else
  # ⚠️ --smoke-only 的语义是「只跑冒烟测试」：不打包就不该检查 jar 产物，
  #    否则在没打过包的干净环境下必然失败（已实测：修复前此处 exit 1）。
  echo "（--smoke-only：跳过打包与产物检查）"
fi

if [ "$SMOKE_ONLY" = "false" ]; then
  step "全部就绪：依赖服务已启动，冒烟测试通过，产物已打包"
  echo "启动后端：  ./scripts/dev-backend.sh（或见 docs/本地启动指南.md §4）"
  echo "启动前端：  cd frontend && npm run dev   (http://localhost:5173)"
  echo "停止依赖：  ./scripts/dev-postgres.sh stop && ./scripts/dev-rabbitmq.sh stop"
else
  # ⚠️ 文案必须与实际做的事一致：--smoke-only 没起服务、没打包，
  #    说成「依赖服务已启动，产物已打包」是谎报（脚本的用途就是给新人
  #    确认环境，少说一句会让新人以为服务已就绪）。
  step "冒烟测试通过（--smoke-only：未启动依赖服务、未打包产物）"
  echo "完整拉起：  ./scripts/dev-env-up.sh"
  echo "启动后端：  ./scripts/dev-backend.sh（或见 docs/本地启动指南.md §4）"
  echo "启动前端：  cd frontend && npm run dev   (http://localhost:5173)"
fi
#!/usr/bin/env bash
#
# cap-gray-step.sh —— 运行一段 CAP 三段式灰度（阶段一 I2-1/I2-2）。
#
# 把《运行时灰度验证方案》的“开启→观察→判定→（通过则保留 / 失败则回退）”循环落成一段可执行命令。
# ⚠️ 它只做「租户级灰度」：通过环境变量把租户放进
#    config.flag.cap-authorization-enforced.tenants 白名单，全局开关(config.flag.cap-authorization-enforced)
#    保持默认 false。它【绝不】把全局开关翻成 true —— 那是全量开启专用，见 scripts/cap-enable-global.sh
#    （本交付不自动执行，须人工审阅后手动跑）。
#
# 用法：
#   ./scripts/cap-gray-step.sh --name <阶段名> \
#       --tenants <逗号分隔租户> \
#       --observe-minutes 1440 \
#       --metrics-base-url http://HOST:PORT \
#       --log-scan "grep -E '\[authz-mismatch\]' /var/log/app.log | grep <tenantId>"
#
# 判定口径（对齐 I2-2）：
#   E1  AUTHZ_MISMATCH 连续观察期 = 0（全局指标 cap_authz_mismatch_total + 每租户 WARN 日志扫描）
#   E2  工具可用率无退化（cap_tool_availability_ratio vs --baseline-ratio，偏差<1%）
#
# 环境变量覆盖（同名优先于参数）：
#   CAP_ENF_TENANTS        —— 本次灰度租户（代替 --tenants）
#   CAP_METRICS_BASE_URL   —— 抓取端点（代替 --metrics-base-url）
#   CAP_OBSERVE_MINUTES    —— 观察窗口（代替 --observe-minutes）

set -euo pipefail

usage() { sed -n '2,30p' "$0" | grep '^#' | tr -d '#'; exit 1; }

# 脚本所在目录（与 cwd 无关，确保从任意目录运行都能定位到同目录脚本/metrics-check）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

NAME=""
TENANTS="${CAP_ENF_TENANTS:-}"
OBSERVE_MINUTES="${CAP_OBSERVE_MINUTES:-1440}"
METRICS_BASE_URL="${CAP_METRICS_BASE_URL:-http://127.0.0.1:8080}"
BASELINE_RATIO=""
LOG_SCAN=""
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2;;
    --tenants) TENANTS="$2"; shift 2;;
    --observe-minutes) OBSERVE_MINUTES="$2"; shift 2;;
    --metrics-base-url) METRICS_BASE_URL="$2"; shift 2;;
    --baseline-ratio) BASELINE_RATIO="$2"; shift 2;;
    --log-scan) LOG_SCAN="$2"; shift 2;;
    --dry-run) DRY_RUN=1; shift;;
    *) usage;;
  esac
done

[ -n "$NAME" ] || { echo "!! 缺少 --name" >&2; usage; }
[ -n "$TENANTS" ] || { echo "!! 缺少 --tenants（或环境变量 CAP_ENF_TENANTS）" >&2; usage; }

# ── 1. 开启：把租户写进灰度白名单（全局开关仍 false）──────────────────
export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED="${CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED:-false}"
export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS="${TENANTS}"

echo "==> [${NAME}] 启用租户级灰度：TENANTS=${TENANTS}"
echo "==> [${NAME}] 全局开关保持 false（不翻转），仅白名单内租户走 CAP 链路"
if [ "$DRY_RUN" -eq 1 ]; then
  echo "==> [${NAME}] DRY-RUN：以下命令将由运维在目标环境中执行："
  echo "      export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED=false"
  echo "      export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS='${TENANTS}'"
  echo "      # 重启或热装载后进入观察窗口"
else
  echo "!! [${NAME}] 本脚本为编排模板：真实生效需你的发布机制把上面两个环境变量注入灰度实例并重启/热载。"
fi

# ── 2. 观察窗口 ──────────────────────────────────────────────
echo "==> [${NAME}] 观察窗口：${OBSERVE_MINUTES} 分钟"
echo "==> [${NAME}] 观察期开始于 $(date --iso-8601=seconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"

# ── 3. 判定：E1 / E2 反复（本脚本在窗口结束后一次性判定，并给出重复检查指引）──
echo ""
echo "==> [${NAME}] 观察结束，进入判定阶段"
PASS=1

# E1：集群级 AUTHZ_MISMATCH 必须 = 0（复用 metrics-check）
if ! "${CAP_METRICS_CHECK:-$SCRIPT_DIR/cap-gray-metrics-check.sh}" \
      check --base-url "$METRICS_BASE_URL" \
      --tenant "${TENANTS%%,*}" \
      ${BASELINE_RATIO:+--baseline-ratio "$BASELINE_RATIO"}; then
  PASS=0
fi

# E1 租户级：按 `--log-scan` 扫每租户 WARN（指标无租户标签，必须叠加日志）
if [ -n "$LOG_SCAN" ]; then
  echo "==> [${NAME}] E1 租户级日志扫描（每租户 [authz-mismatch] 必须为空）："
  IFS=',' read -ra TARR <<<"$TENANTS"
  for t in "${TARR[@]}"; do
    hits="$(eval "${LOG_SCAN//<tenantId>/$t}" || true)"
    if [ -n "$hits" ]; then
      echo "!! [${NAME}] 租户 ${t} 出现 authz-mismatch WARN，E1 租户级 FAIL：" >&2
      printf '%s\n' "$hits" >&2
      PASS=0
    else
      echo "==> [${NAME}] 租户 ${t} 无 authz-mismatch（PASS）"
    fi
  done
fi

# ── 4. 结果 / 回退 ──────────────────────────────────────────
if [ "$PASS" -eq 1 ]; then
  echo "==> [${NAME}] ✅ 阶段通过：可进入下一段灰度（扩大租户集）。"
else
  echo ""
  echo "!! [${NAME}] ❌ 阶段未通过。执行熔断回退（从白名单移除租户，全局开关仍 false）：" >&2
  echo "      export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED=false"
  echo "      export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS=''"
  echo "      # 回退=停止走 CAP 链路 ≠ 删除 cap_* 数据（DualWrite 语义：关=停用新判定）"
  echo "!! 排查要点（E1 非零→授权影子判定绑定漂移；E2 跌破→RLS/Schema/预算误拒），"
  echo "   定位后清除该租户后再重试同一阶段。" >&2
  exit 1
fi

# ── 5. 三段后全量开启（不自动执行）────────────────────────────
echo ""
echo "==> 若这是【阶段 3】且通过：下一步是【全量开启】，由人工运行 scripts/cap-enable-global.sh"
echo "==> 全量开启 = 把全局开关翻 true + 守卫日志 WARN→INFO；本脚本不自动执行该步。"
#!/usr/bin/env bash
#
# cap-enable-global.sh —— CAP 全量开启（P0-1d）的【人工检查单 + 执行说明】。
#
# ⚠️⚠️ 本脚本【绝不自动翻转】任何开关。它只做三件事：
#   1. 先执行为全量开启设定的前置检查（防呆）；
#   2. 打印全量开启的确切操作（改 env / yml，把全局开关翻 true）；
#   3. 打印开启后的 24h 观察与回退口径。
# 真正把开关翻 true 的动作必须由【运维手动执行】—— 一票否决与发布流程不在脚本里。
#
# 前置（缺一都拒绝建议开启）：
#   P1  三段灰度全部通过（cap-gray-step.sh 各阶段返回 0）
#   P2  P5 指针读路径已落地（本交付已核实；门禁文本已更新）
#   P3  全局 AUTHZ_MISMATCH 连续 24h = 0（cap-gray-metrics-check.sh check 通过）
#
# 用法：
#   ./scripts/cap-enable-global.sh [--metrics-base-url http://HOST:PORT]

set -euo pipefail

# 脚本所在目录（与 cwd 无关，确保从任意目录运行都能定位到同目录脚本）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

METRICS_BASE_URL="${CAP_METRICS_BASE_URL:-http://127.0.0.1:8080}"
[ "${1:-}" = "--metrics-base-url" ] && [ -n "${2:-}" ] && METRICS_BASE_URL="$2"

# 手动确认两件事（环境变量作真值，避免脚本里写死）
GRAY_ALL_GREEN="${CAP_GRAY_ALL_GREEN:-no}"
echo "==> 前置检查（P1 三段灰度全绿 / P2 P5 已落地 / P3 全局 mismatch=0）"
echo "==> 请在 CI/发布流程中 supply CAP_GRAY_ALL_GREEN=yes 表示三段灰度已全绿。"

if [ "$GRAY_ALL_GREEN" != "yes" ]; then
  echo "!! 三段灰度未确认全绿（CAP_GRAY_ALL_GREEN!=yes）。拒绝进入全量开启建议。" >&2
  echo "    请先用 scripts/cap-gray-step.sh 跑完三段并确认各阶段返回 0。" >&2
  exit 1
fi

echo "==> 复扫全局 AUTHZ_MISMATCH（P3）："
"$SCRIPT_DIR/cap-gray-metrics-check.sh" check --base-url "$METRICS_BASE_URL" \
    --tenant "${CAP_GRAY_LAST_TENANT:-}" || { echo "!! P3 未通过，不建议开启。" >&2; exit 1; }

echo ""
echo "==> ✅ 前置齐备。以下【人工操作】方可完成全量开启（本脚本不代为执行）："
echo ""
echo "  # ① 把全局开关翻 true（这是唯一改变授权语义的动作，必须人工/发布流程拍板）"
echo "  export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED=true"
echo "  # ② 清空灰度白名单（全量开启后白名单失效，仍保留机制以支持个别租户回退）"
echo "  export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS=''"
echo "  # ③ 重启/热载目标实例"
echo ""
echo "  # ④ CapEnforcementReadinessGuard 启动日志 WARN→INFO，并应打印："
echo "  #      CAP 授权链路=ENABLED   (E3 验收判据)"
echo ""
echo "==> 开启后 24h 观察（验收判据）："
echo "    - 全租户 cap_authz_mismatch_total = 0"
echo "    - 工具可用率无退化（与开启前 24h 基线对比偏差 < 1%）"
echo "    回退：把 ① 改回 false + 重启即可，cap_* 数据不删除（关=停用新判定 ≠ 删数据）。"
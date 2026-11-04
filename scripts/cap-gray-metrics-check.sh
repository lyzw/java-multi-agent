#!/usr/bin/env bash
#
# cap-gray-metrics-check.sh —— CAP 灰度阶段 E1/E2 指标判定（阶段一 I2-2）。
#
# 判定口径（对齐《运行时灰度验证方案》+《阶段一》I2-2 验收判据）：
#   E1   AUTHZ_MISMATCH 连续观察期 = 0        → 查 cap.authz_mismatch_total 全系列为 0
#   E2   工具可用率无退化（偏差 < 1%）         → 查 cap.tool_availability_ratio 灰度租户 vs 基线
#
# ⚠️ 重要口径说明：
#   - cap.authz_mismatch_total 是【全局、按 resource_type 分组】的计数（非租户标签）。
#     所以 E1 的"集群级=0"用本脚本查指标即可；
#     E1 的"某灰度租户=0"必须叠加【按租户扫 WARN 日志】，因为指标不带租户标签
#     （见 cap-gray-step.sh 的 --log-scan 段）。请勿只凭全局指标声称某个租户为零。
#   - cap.tool_availability_ratio 是【按租户】的 Gauge，可用 tenant 标签过滤。
#
# 用法：
#   ./scripts/cap-gray-metrics-check.sh check --base-url http://HOST:PORT \
#       --tenant <tenantId> --baseline-ratio <0.xx>
#
# 依赖：curl, awk 需可用。

set -euo pipefail

usage() {
  sed -n '2,30p' "$0" | grep '^#' | tr -d '#'
  exit 1
}

# 默认抓取地址（k8s 直连 pod 或经网关；可由环境覆盖）
BASE_URL="${CAP_METRICS_BASE_URL:-http://127.0.0.1:8080}"

E1_LABEL="cap_authz_mismatch_total"
E2_LABEL="cap_tool_availability_ratio"

# ── Prometheus 文本格式解析：获得某指标带某标签的当前值 ──────────
# 用法: metric_value <label> [<extraTag>=<value> ...]
# 输出: <求和值> <样例行数>；抓不到时样例行数为 0。
# ⚠️ 求和用 awk 浮点计算（指标值可能是 Gauge 小数，不能用 bash 整数运算）。
metric_value() {
  local label="$1"; shift
  local scrape
  scrape="$(curl -fsS "${BASE_URL}/actuator/prometheus")" || { echo 0 0; return 1; }
  local lines
  # 匹配以 `label` 开头的行（Prometheus 里形如 `label{tags...} value`）
  lines="$(printf '%s\n' "$scrape" | awk "\$0 ~ /^${label}/")" || true
  # 过滤指定标签（若传入）
  for kv in "$@"; do
    local k="${kv%%=*}" v="${kv#*=}"
    lines="$(printf '%s\n' "$lines" | grep "${k}=\"${v}\"" || true)"
  done
  # 求和数值列（最后一个空格字段），用 awk 做浮点求和
  local res
  res="$(printf '%s\n' "$lines" | awk '{s+=$NF} END{printf "%g %d", s+0, NR}')" 2>/dev/null
  [ -n "$res" ] || res="0 0"
  printf '%s' "$res"
}

do_check() {
  local tenant="" baseline=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --base-url) BASE_URL="$2"; shift 2;;
      --tenant) tenant="$2"; shift 2;;
      --baseline-ratio) baseline="$2"; shift 2;;
      *) shift;;
    esac
  done

  echo "==> 抓取 ${BASE_URL}/actuator/prometheus"
  if ! curl -fsS "${BASE_URL}/actuator/prometheus" >/dev/null 2>&1; then
    echo "!! 抓取端点不可达。请确认已启用 prometheus 暴露（本交付已配好）且网络可达。" >&2
    exit 2
  fi

  # E1：全局 AUTHZ_MISMATCH 必须 = 0（按 resource_type 求和）
  local e1 e1n
  e1="$(metric_value "$E1_LABEL")"; e1n="${e1#* }"; e1="${e1%% *}"
  echo "==> E1  AUTHZ_MISMATCH (cap_authz_mismatch_total) = ${e1}（${e1n} 个 resource_type 系列）"

  if [ "${e1:-}" != "0" ]; then
    echo "!! E1 FAIL：AUTHZ_MISMATCH 非零（全局），立即熔断，禁止放行。" >&2
    return 1
  fi
  # ⚠️ 再加一道【租户级】日志扫描（指标无租户标签，见文件头说明）
  echo "=> 注意：需再按租户扫 [authz-mismatch] WARN 日志以确认该灰度租户为零（见 cap-gray-step.sh）"

  # E2：工具可用率 vs 基线
  if [ -n "$tenant" ]; then
    local e2 e2n
    e2="$(metric_value "$E2_LABEL" "tenant=$tenant")"; e2n="${e2#* }"; e2="${e2%% *}"
    echo "==> E2  工具可用率 tenant=${tenant} = ${e2}"
    if [ -n "$baseline" ] && [ -n "$e2n" ]; then
      # 可用率须 ≥ 基线 − 1%（I2-2：偏差 < 1%）。
      # e2 为 [0,1] 比例；baseline 亦为比例。低于阈值 → FAIL。
      if awk -v r="${e2}" -v b="${baseline}" \
          'BEGIN { exit !(r >= b - 0.01) }'; then
        echo "==> E2 PASS：可用率 ${e2} ≥ 基线 ${baseline} − 1%"
      else
        echo "!! E2 FAIL：可用率 ${e2} < 基线 ${baseline} − 1%，当前租户应回退。" >&2
        return 1
      fi
    fi
  fi

  echo "==> E1 通过（集群级=0，需配合租户级日志扫描）；E2 判定完成。"
}

case "${1:-}" in
  check) shift; do_check "$@" ;;
  *) usage ;;
esac
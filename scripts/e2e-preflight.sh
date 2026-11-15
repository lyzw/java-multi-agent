#!/usr/bin/env bash
# =====================================================================
# E2E 前置硬门禁（T2 · E2E 二次评估 2026-11-14）
# =====================================================================
# 【为什么必须有这道门】
#   F7 实测：一次 Run 要真正跑起来有四层前置，缺任意一层都是
#   「创建 Run 返回 201 → 随后静默 FAILED / 永远 QUEUED」，
#   页面上看不出根因，只能在 DB 里倒查 outbox / budget / authz。
#   此前这些前置靠人工 SQL + 手工启动参数，换环境 / 换租户同一套 E2E 必然红。
#
# 【用法】任何 E2E runner 在发第一个业务请求**之前**先执行：
#   scripts/e2e-preflight.sh                       # 默认 t-e2e-fe，含平台健康
#   E2E_TENANT_SLUG=t-a scripts/e2e-preflight.sh
#   scripts/e2e-preflight.sh --no-health          # 平台未启动时显式跳过健康项
#
# 【退出码】0 前置齐备 / 1 有阻断项（禁止开跑）/ 2 环境或依赖问题。
#   ⚠️ 严禁用 `|| true`、`; exit 0` 之类绕过 —— 那等于把门禁关掉。
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLUG="${E2E_TENANT_SLUG:-t-e2e-fe}"

command -v python3 >/dev/null 2>&1 || {
  echo "❌ 缺少 python3" >&2
  exit 2
}

# 让当前 shell 的部署配置（含 AGENT_TENANT_SLUGS）覆盖文件默认值；
# Python 侧自身也会回落到仓库根 platform.env，故这里不强制 source 含密文件。
echo "=== E2E 前置硬门禁 · 租户 ${SLUG} ==="
exec python3 "$REPO/scripts/provision_tenant_runtime.py" \
  --slug "$SLUG" --check-only "$@"

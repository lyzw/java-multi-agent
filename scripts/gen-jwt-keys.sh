#!/usr/bin/env bash
#
# =====================================================================
# gen-jwt-keys.sh —— 生成平台两个令牌域的 RS256 密钥对，并产出**配套**的
#                   RAG 验签公钥。
#
# 【为什么需要它】
#   平台（8080）签发令牌用私钥，RAG（8081）只拿公钥验签。两边必须是同一对，
#   否则表现为「登录成功、调 RAG 全部 401」——而 401 看着像 RAG 的缺陷，
#   实际是配置漂移。RAG 侧 fail-closed：启用鉴权却一个公钥都没配 → 拒绝启动。
#
# 【两个令牌域】（互相拒绝，靠 audience 区分）
#   tenant   aud=agent-platform      平台 /api/auth/token 签发，带 tenant_id
#   platform aud=admin-console-api   管理台登录签发，跨租户治理
#
# 【为什么私钥要成对写「私钥.公钥」】
#   平台的 resolveKeyPair() 要求 JWT_PRIVATE_KEY=<privB64URL>.<pubB64URL>：
#   只配私钥时运维得另行提供公钥，两处不一致的后果是「签出的令牌全部验不过」，
#   这种故障在单机开发环境不可见、只在生产暴露，所以平台强制成对。
#
# 【本地开发为什么不能靠启动生成】
#   dev profile 下私钥留空 = 每次启动生成新密钥对：平台一重启，
#   RAG 配的公钥就作废。所以本地也要持久化一份。
#
# 【产物】
#   $JWT_KEYS_FILE（默认 <repo>/jwt-keys.env，被 .gitignore 的 *.env 忽略）
#     JWT_PRIVATE_KEY / PLATFORM_JWT_PRIVATE_KEY      ← 平台侧，机密
#     RAG_TENANT_JWT_PUBLIC_KEY / RAG_PLATFORM_JWT_PUBLIC_KEY ← RAG 侧，公开
#
# 【用法】
#   scripts/gen-jwt-keys.sh              # 生成（文件已存在则拒绝覆盖）
#   scripts/gen-jwt-keys.sh --force      # 重新生成（旧密钥签发的令牌全部失效）
#   scripts/gen-jwt-keys.sh --check      # 与运行中的平台 JWKS 比对，确认配套
#
# ⚠️ 本脚本**绝不打印私钥**：只输出公钥指纹与文件路径。
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${JWT_KEYS_FILE:-$REPO/jwt-keys.env}"
PLATFORM_BASE="${PLATFORM_BASE_URL:-http://127.0.0.1:8080}"

# DER → base64url（无填充）：平台 JWKS 与 JWT 段用的都是这个字母表。
b64url() { base64 | tr -d '\n=' | tr '+/' '-_'; }

# 公钥/私钥的 base64url 串 → 十六进制指纹（仅用于人眼比对，不解出内容）。
fp() {
  printf '%s' "$1" | python3 -c '
import sys, base64, hashlib
d = sys.stdin.read().strip().replace("-", "+").replace("_", "/")
print(hashlib.sha256(base64.b64decode(d + "=" * (-len(d) % 4))).hexdigest())'
}

# 生成一对密钥，把「私钥.b64url / 公钥.b64url」写进全局变量 PRIV/PUB。
gen_pair() {
  local pem; pem="$(mktemp)"
  ( umask 077; openssl genrsa -out "$pem" 2048 2>/dev/null )
  PRIV="$(openssl pkcs8 -topk8 -nocrypt -in "$pem" -outform DER 2>/dev/null | b64url)"
  PUB="$(openssl rsa -in "$pem" -pubout -outform DER 2>/dev/null | b64url)"
  rm -f "$pem"
}

# 从 env 文件里取一个变量的值（去掉外层单引号）。
read_var() {
  grep -m1 "^$1=" "$OUT" | sed -e "s/^$1=//" -e "s/^'//" -e "s/'$//"
}

mode="${1:-gen}"

if [ "$mode" = "--check" ]; then
  [ -f "$OUT" ] || { echo "未找到 $OUT —— 先执行 scripts/gen-jwt-keys.sh" >&2; exit 1; }
  t_key="$(read_var RAG_TENANT_JWT_PUBLIC_KEY)"
  echo "平台 JWKS（$PLATFORM_BASE/api/auth/jwks）："
  curl -s -m 5 "$PLATFORM_BASE/api/auth/jwks" \
    | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("publicKeyBase64",""))' \
    > /tmp/.jwks_pub.$$ || { echo "取不到 JWKS（平台没起？）" >&2; exit 1; }
  live="$(cat /tmp/.jwks_pub.$$)"; rm -f /tmp/.jwks_pub.$$
  if [ -z "$live" ]; then echo "JWKS 响应里没有 publicKeyBase64" >&2; exit 1; fi
  echo "  运行中公钥指纹 : $(fp "$live")"
  echo "  jwt-keys.env   : $(fp "$t_key")"
  if [ "$(fp "$live")" = "$(fp "$t_key")" ]; then
    echo "✅ 配套：平台当前正是用 jwt-keys.env 里的私钥在签租户令牌。"
  else
    echo "⛔ 不配套：平台用的是另一把私钥（多半仍在「启动生成」）。"
    echo "   处置：把 $OUT 的 JWT_PRIVATE_KEY 注入平台环境后重启平台。"
    exit 1
  fi
  exit 0
fi

if [ -f "$OUT" ] && [ "${1:-}" != "--force" ]; then
  echo "已存在 $OUT —— 保留现有密钥（重签会作废全部已发令牌）。"
  echo "确需重新生成：scripts/gen-jwt-keys.sh --force"
  exit 1
fi

gen_pair; TENANT_PRIV="$PRIV"; TENANT_PUB="$PUB"
gen_pair; PLATFORM_PRIV="$PRIV"; PLATFORM_PUB="$PUB"

( umask 077
  {
    echo "# 由 scripts/gen-jwt-keys.sh 生成于 $(date '+%F %T')。本文件含私钥，已被 .gitignore 忽略，切勿提交。"
    echo "# 平台侧（source 进 platform-bootstrap 的环境）："
    echo "JWT_PRIVATE_KEY='${TENANT_PRIV}.${TENANT_PUB}'"
    echo "PLATFORM_JWT_PRIVATE_KEY='${PLATFORM_PRIV}.${PLATFORM_PUB}'"
    echo "# RAG 侧（source 进 rag-bootstrap / rag-worker 的环境；公钥，非机密）："
    echo "RAG_TENANT_JWT_PUBLIC_KEY='${TENANT_PUB}'"
    echo "RAG_PLATFORM_JWT_PUBLIC_KEY='${PLATFORM_PUB}'"
  } > "$OUT" )

echo "已写入 $OUT（0600）"
echo "  租户域   公钥指纹 $(fp "$TENANT_PUB")"
echo "  平台域   公钥指纹 $(fp "$PLATFORM_PUB")"
echo
echo "下一步："
echo "  1) 平台侧注入 $OUT 的 JWT_PRIVATE_KEY / PLATFORM_JWT_PRIVATE_KEY 后重启平台"
echo "  2) RAG 侧注入 RAG_TENANT_JWT_PUBLIC_KEY / RAG_PLATFORM_JWT_PUBLIC_KEY 后重启 8081"
echo "  3) scripts/gen-jwt-keys.sh --check   # 与运行中的平台 JWKS 核对是否配套"

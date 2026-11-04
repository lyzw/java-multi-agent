#!/usr/bin/env bash
#
# e2e-index-generation.sh —— 索引代际蓝绿切换 · 端到端演练
#
# ⚠️ **为什么需要这个脚本**（RAG-D25 的实际教训）：
# 2026-11-04 首次在真实库演练时，**593 项单测全绿的应用连续失败 5 次**：
#   · 未绑定会话租户 → RLS 整行过滤（列表恒空）/ 写被拒（500）
#   · set_config 在 autocommit 下当场失效（worker 无限重试）
#   · 构建无"完成"标记 → 停在 5/5 无法切换
#   · isActivatable 一刀切 → 构建完成也永远无法切换
# 这些缺陷**没有一个能被 mock 掉 JdbcTemplate 的单测发现**——
# 因为出问题的恰恰是 RLS 策略与事务边界。
#
# 故把演练固化为脚本：每次改动索引/RLS/事务边界后跑一遍，
# 比"再想一遍当时怎么测的"可靠得多。
#
# 用法：
#   ./scripts/e2e-index-generation.sh
#
# 前置：
#   · 本地 PostgreSQL 容器（默认 rag-pg-s2，端口 55435，库 ragdb）
#   · 已执行迁移 08/09/10/11 与 03_rag_roles.sql
#
# ⚠️ 本脚本会**启动/停止** 8091（bootstrap）与 8092（worker）两个进程，
#    用的是**本地库**，不触碰云端 RDS。
#
# ⚠️ 退出码：0 = 六步全通；非 0 = 有步骤失败（详见输出）
set -uo pipefail

CONTAINER="${RAG_E2E_PG_CONTAINER:-rag-pg-s2}"
DB="${RAG_E2E_DB:-ragdb}"
PGUSER="${RAG_E2E_PGUSER:-postgres}"
APP_PORT="${RAG_E2E_APP_PORT:-8091}"
WORKER_PORT="${RAG_E2E_WORKER_PORT:-8092}"
WORK="${RAG_E2E_WORKDIR:-/tmp/rag-e2e}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ⚠️⚠️ **应用角色密码与超级用户密码是两回事**（实测踩到）：
#    容器里的 POSTGRES_PASSWORD 只对 postgres 超级用户有效；
#    rag_app 是 03_rag_roles.sql 建的**独立角色**，密码需单独设定。
#    初始实现误用超管密码 → "password authentication failed for user rag_app"。
#
# ⚠️ 每次演练强制重置 rag_app 密码：保证脚本**自包含**、不依赖"上次手工设成什么"。
#    这是本地测试库的一次性密码，不涉及任何真实凭据。
APP_PWD="e2e-$(date +%s)"
docker exec "$CONTAINER" psql -U "$PGUSER" -d "$DB" -q   -c "ALTER ROLE rag_app PASSWORD '$APP_PWD';" >/dev/null 2>&1 \
  || { echo "  ✗ 无法设置 rag_app 密码"; exit 1; }
PGPASSWORD_APP="$APP_PWD"

PASS=0; FAIL=0
step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31m✗ %s\033[0m\n' "$*"; FAIL=$((FAIL+1)); }
info() { printf '    %s\n' "$*"; }

cleanup() {
  pkill -f "rag-bootstrap.*--server.port=$APP_PORT" 2>/dev/null
  pkill -f "rag-worker-1.0.1-SNAPSHOT.jar" 2>/dev/null
  sleep 1
}
trap cleanup EXIT

psql_su()  { docker exec "$CONTAINER" psql -U "$PGUSER" -d "$DB" -tAc "$1" 2>&1; }
psql_app() { docker exec "$CONTAINER" psql -U rag_app -d "$DB" -tAc "$1" 2>&1; }

# ── 0. 前置检查 ─────────────────────────────────────────────────────────────
step "0. 前置检查"
docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" \
  && ok "容器 $CONTAINER 运行中" \
  || { bad "容器 $CONTAINER 未运行"; exit 1; }

[ "$(psql_su "SELECT to_regclass('rag.rag_index_generation') IS NOT NULL;")" = "t" ] \
  && ok "迁移 09 已应用（rag_index_generation 存在）" \
  || { bad "缺少 rag_index_generation——请先执行 09/10/11 迁移"; exit 1; }

[ "$(psql_su "SELECT count(*) FROM pg_roles WHERE rolname='rag_app';")" = "1" ] \
  && ok "非特权角色 rag_app 存在" \
  || { bad "缺 rag_app 角色——请先执行 03_rag_roles.sql"; exit 1; }

TENANT="$(psql_su "SELECT tenant_id FROM rag.rag_source ORDER BY created_at LIMIT 1;")"
[ -n "$TENANT" ] && ok "测试租户 $TENANT" || { bad "库内无语料来源，无法演练"; exit 1; }

# ── 1. 准备测试密钥与令牌 ───────────────────────────────────────────────────
step "1. 准备测试密钥与令牌"
mkdir -p "$WORK"
# ⚠️ 每次演练用**新密钥对**：避免与既有配置的公钥混淆，
#    也保证"演练不需要接触任何真实密钥"。
[ -f "$WORK/e2e_key.pem" ] || openssl genrsa -out "$WORK/e2e_key.pem" 2048 2>/dev/null
PUB="$(openssl rsa -in "$WORK/e2e_key.pem" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')"
[ -n "$PUB" ] && ok "测试公钥已生成" || { bad "公钥生成失败"; exit 1; }

# ⚠️ iss 必须是 RAG 期望的值（默认 https://auth.studykid.cn），
#    写成 "agent-platform" 会 401——这是演练时踩过的坑。
# base64url：JWT 要求 URL-safe 且无填充
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

mint_jwt() {
  local now exp header payload sig
  now=$(date +%s); exp=$((now+3600))
  header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
  payload=$(printf '{"iss":"https://auth.studykid.cn","aud":"agent-platform","sub":"e2e","tenant_id":"%s","iat":%s,"exp":%s}' \
    "$1" "$now" "$exp" | b64url)
  sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/e2e_key.pem" | b64url)
  printf '%s.%s.%s\n' "$header" "$payload" "$sig"
}
TOKEN="$(mint_jwt "$TENANT")"
[ -n "$TOKEN" ] && ok "测试令牌已签发" || { bad "令牌签发失败"; exit 1; }

# ── 2. 启动 bootstrap（非特权角色） ────────────────────────────────────────
step "2. 启动 bootstrap"
# ⚠️ 必须用 rag_app：超级用户会绕过 RLS，启动自检会拒绝（这是正确的防护）。
#    一个用超管跑通的"成功"是假成功。
JVM="$(/usr/libexec/java_home 2>/dev/null)/bin/java"
[ -x "$JVM" ] || JVM="/opt/homebrew/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home/bin/java"
[ -x "$JVM" ] || { bad "找不到 java"; exit 1; }

set -a; source "$ROOT/rag.env" 2>/dev/null; source "$ROOT/jwt.env" 2>/dev/null; set +a
export RAG_TENANT_JWT_PUBLIC_KEY="$PUB" RAG_PLATFORM_JWT_PUBLIC_KEY="$PUB"
export RAG_PG_URL="jdbc:postgresql://127.0.0.1:55435/$DB"
export RAG_PG_USERNAME=rag_app RAG_PG_PASSWORD="$PGPASSWORD_APP"
export RAG_OPS_ENABLED=false

cleanup
(cd "$ROOT" && nohup "$JVM" -jar rag/rag-bootstrap/target/rag-bootstrap-1.0.1-SNAPSHOT.jar \
   --server.port="$APP_PORT" > "$WORK/bootstrap.log" 2>&1 &)
for i in $(seq 1 40); do
  [ "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT/actuator/health" --max-time 2)" = "200" ] && break
  sleep 1
done
[ "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT/actuator/health" --max-time 3)" = "200" ] \
  && ok "bootstrap 就绪（${APP_PORT}）" \
  || { bad "bootstrap 未就绪，见 $WORK/bootstrap.log"; tail -5 "$WORK/bootstrap.log"; exit 1; }

api() { curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" "$@"; }
gen_field() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

# ── 3. 记录起始状态 ─────────────────────────────────────────────────────────
step "3. 记录起始状态"
# ⚠️ 基线固定为**迁移自动创建的那个代际**（note 含"迁移自动创建"），
#    而不是"当前 ACTIVE"——否则上一次演练的残留会让基线不断漂移，
#    每跑一次就多出一个历史代际。
BASE_ACTIVE="$(psql_su "SELECT generation FROM rag.rag_index_generation
                         WHERE COALESCE(note,'') LIKE '%迁移自动创建%'
                         ORDER BY generation LIMIT 1;")"
[ -n "$BASE_ACTIVE" ] || BASE_ACTIVE="$(psql_su "SELECT MIN(generation) FROM rag.rag_index_generation;")"
# ⚠️ 演练前先把基线代际恢复为 ACTIVE，保证起点一致（重复可跑）
psql_su "UPDATE rag.rag_index_generation SET status='RETIRED', retired_at=now() WHERE status='ACTIVE';" >/dev/null
psql_su "UPDATE rag.rag_index_generation SET status='ACTIVE', activated_at=now(), retired_at=NULL WHERE generation=${BASE_ACTIVE};" >/dev/null
BASE_VEC_1="$(psql_su "SELECT count(*) FROM rag.rag_chunk_embedding WHERE index_generation=${BASE_ACTIVE};")"
info "当前 ACTIVE 代际=${BASE_ACTIVE}，该代际向量行数=$BASE_VEC_1"
ok "起始状态已记录"

# ── 4. ① 新建代际 ───────────────────────────────────────────────────────────
step "① 新建代际"
CREATE_JSON="$(api -X POST -H 'Content-Type: application/json' \
  -d '{"embedModel":"e2e-test-model","dimension":1024}' \
  "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations" --max-time 15)"
NEW_GEN="$(printf '%s' "$CREATE_JSON" | gen_field "['generation']")"
if [ -n "$NEW_GEN" ]; then
  ok "新建代际 gen=${NEW_GEN}（状态 BUILDING）"
else
  bad "新建失败：$CREATE_JSON"; exit 1
fi

# ── 5. ② 触发构建（异步排队） ───────────────────────────────────────────────
step "② 触发构建（异步排队）"
START_MS=$(python3 -c 'import time;print(int(time.time()*1000))')
BUILD_JSON="$(api -X POST "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations/$NEW_GEN/build" --max-time 20)"
END_MS=$(python3 -c 'import time;print(int(time.time()*1000))')
ELAPSED=$((END_MS-START_MS))
TOTAL="$(printf '%s' "$BUILD_JSON" | gen_field "['generation']['totalChunks']")"
if [ -n "$TOTAL" ]; then
  ok "已排队：total=${TOTAL}，耗时 ${ELAPSED}ms"
  # ⚠️ 异步的关键证据：接口必须**立即返回**（不是等构建完成）。
  #    若这里是数秒以上，说明退化回同步执行了。
  [ "$ELAPSED" -lt 3000 ] && ok "响应迅速（<3s），异步语义成立" || bad "响应 ${ELAPSED}ms，疑似退化为同步"
else
  bad "排队失败：$BUILD_JSON"
fi

# ── 6. ③ worker 分批推进 ───────────────────────────────────────────────────
step "③ worker 分批推进"
(cd "$ROOT" && RAG_INDEXER_TENANTS="$TENANT" nohup "$JVM" -jar rag/rag-worker/target/rag-worker-1.0.1-SNAPSHOT.jar \
   > "$WORK/worker.log" 2>&1 &)
BUILT=0
for i in $(seq 1 40); do
  NOTE="$(psql_su "SELECT COALESCE(note,'') FROM rag.rag_index_generation WHERE generation=$NEW_GEN;")"
  echo "$NOTE" | grep -q '构建已完成' && { BUILT=1; break; }
  sleep 2
done
if [ "$BUILT" = "1" ]; then
  ok "构建完成（note 已标记「构建已完成」）"
else
  bad "构建未完成（超时）；worker 日志："; tail -5 "$WORK/worker.log"; 
fi
# ⚠️ 双代际共存是本次改造的核心能力，必须显式验证：
#    新代际有向量 **且** 基线代际向量数一行未少（切换前旧代际必须完好）。
VEC2="$(psql_su "SELECT count(*) FROM rag.rag_chunk_embedding WHERE index_generation=$NEW_GEN;")"
VEC1_NOW="$(psql_su "SELECT count(*) FROM rag.rag_chunk_embedding WHERE index_generation=${BASE_ACTIVE};")"
info "代际${NEW_GEN} 向量=${VEC2} 行；代际${BASE_ACTIVE} 向量=${VEC1_NOW} 行（起始 ${BASE_VEC_1} 行）"
[ "$VEC2" -gt 0 ] && ok "新代际已有向量" || bad "代际${NEW_GEN} 无向量"
[ "$VEC1_NOW" = "$BASE_VEC_1" ] && ok "双代际共存成立（旧代际向量未被覆盖）" \
  || bad "旧代际向量数从 $BASE_VEC_1 变为 $VEC1_NOW，共存被破坏"

# ── 7. ④ 未完成时切换应被拒绝 ──────────────────────────────────────────────
step "④ 状态守卫（构建中不可激活）"
# ⚠️ 这里用一个**新建但未构建**的代际验证守卫，
#    因为已构建完成的代际是允许切换的（RAG-D25 缺陷 5 的修复）。
GUARD_JSON="$(api -X POST -H 'Content-Type: application/json' \
  -d '{"embedModel":"e2e-guard","dimension":1024}' \
  "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations" --max-time 15)"
GUARD_GEN="$(printf '%s' "$GUARD_JSON" | gen_field "['generation']")"
GUARD_CODE="$(api -o /dev/null -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations/$GUARD_GEN/activate" --max-time 10)"
[ "$GUARD_CODE" = "409" ] && ok "未构建的代际被正确拒绝（409）" || bad "期望 409，实际 $GUARD_CODE"

# ── 8. ⑤ 切换 ──────────────────────────────────────────────────────────────
step "⑤ 切换到代际 $NEW_GEN"
ACT_JSON="$(api -X POST "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations/$NEW_GEN/activate" --max-time 15)"
ACT_STATUS="$(printf '%s' "$ACT_JSON" | gen_field "['status']")"
[ "$ACT_STATUS" = "ACTIVE" ] && ok "切换成功（gen=${NEW_GEN} 现为 ACTIVE）" || bad "切换失败：$ACT_JSON"

# ⚠️ 原子性证据：切换时「新代际 activated_at」与「旧代际 retired_at」必须**同一时刻**。
#    拆成两次提交会出现「零个 ACTIVE」的中间态（并发检索全站空转）。
#    ⚠️ 不能简单比较两行的 (activated,retired) 组合——旧代际可能早就有 activated_at
#       （它此前是 ACTIVE），那样比出来必然不同。要比的是**本次切换写入的那一对时间戳**。
SWITCH_TS="$(psql_su "SELECT CASE WHEN
    (SELECT activated_at FROM rag.rag_index_generation WHERE generation=${NEW_GEN})
    = (SELECT retired_at FROM rag.rag_index_generation WHERE generation=${BASE_ACTIVE})
  THEN 'same' ELSE 'diff' END;")"
[ "${SWITCH_TS}" = "same" ] \
  && ok "切换原子（新代际 activated_at == 旧代际 retired_at，同一事务）" \
  || bad "切换非原子：新代际 activated_at 与旧代际 retired_at 不是同一时刻"

RETIRED_CNT="$(psql_su "SELECT count(*) FROM rag.rag_index_generation WHERE status='ACTIVE';")"
[ "$RETIRED_CNT" = "1" ] && ok "任何时刻至多一个 ACTIVE" || bad "ACTIVE 数量=$RETIRED_CNT"

# ── 9. ⑥ 回滚 ──────────────────────────────────────────────────────────────
step "⑥ 回滚到代际 $BASE_ACTIVE"
RB_JSON="$(api -X POST "http://127.0.0.1:$APP_PORT/api/v1/management/index-generations/$BASE_ACTIVE/activate" --max-time 15)"
RB_STATUS="$(printf '%s' "$RB_JSON" | gen_field "['status']")"
[ "$RB_STATUS" = "ACTIVE" ] && ok "回滚成功（gen=${BASE_ACTIVE} 恢复 ACTIVE）" || bad "回滚失败：$RB_JSON"

# ── 10. 清理演练产物 ───────────────────────────────────────────────────────
step "10. 清理演练产物"
# ⚠️ 只删「除基线外」的代际与其向量。
#    ⚠️ 不能按 status='ACTIVE' 判断基线：如果演练中途失败，基线可能停在 RETIRED，
#    那样它的向量会被一起删掉（实测踩到：基线的 20 条向量被误删，只剩 5 条）。
#    **基线是固定值 BASE_ACTIVE，不是"当前谁 ACTIVE"**。
psql_su "DELETE FROM rag.rag_chunk_embedding WHERE index_generation <> ${BASE_ACTIVE};" >/dev/null
psql_su "DELETE FROM rag.rag_index_generation WHERE generation <> ${BASE_ACTIVE};" >/dev/null
CNT="$(psql_su "SELECT count(*) FROM rag.rag_index_generation;")"
LEFT_VEC="$(psql_su "SELECT count(*) FROM rag.rag_chunk_embedding WHERE index_generation=${BASE_ACTIVE};")"
[ "$CNT" = "1" ] && ok "演练代际已清理（剩余 $CNT 条代际）" || bad "清理后仍有 $CNT 条代际"
# ⚠️ 清理必须**不伤基线**：这是本脚本最容易误伤的地方，必须显式断言。
[ "$LEFT_VEC" = "$BASE_VEC_1" ] && ok "基线代际向量完好（${LEFT_VEC} 行）" \
  || bad "基线代际向量被误删：$BASE_VEC_1 → $LEFT_VEC"

# ── 汇总 ───────────────────────────────────────────────────────────────────
printf '\n\033[1m═══ 演练汇总 ═══\033[0m\n'
printf '  通过 %d 项' "$PASS"
[ "$FAIL" -eq 0 ] && printf '，\033[32m全部通过 ✅\033[0m\n' || printf '，\033[31m失败 %d 项 ❌\033[0m\n' "$FAIL"
printf '  日志：%s/{bootstrap,worker}.log\n' "${WORK}"
[ "$FAIL" -eq 0 ]

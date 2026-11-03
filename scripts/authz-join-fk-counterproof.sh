#!/usr/bin/env bash
#
# =====================================================================
# authz-join-fk-counterproof.sh —— D13 的两类「JOIN / 跨租户引用」反证
#
# 【要证明什么】
#   RLS 在时，「JOIN 从表」和「跨租户引用」都由数据库策略兜住；
#   退役 RLS 后它们只剩应用层。这两类结论**不能靠读代码推断**：
#
#     A 类 · 复合外键是否真的按租户一致（12 文 §7.3 C5）
#       ① 目标表上的复合外键**是否包含 tenant_id**？
#          —— 单列 FK（只约束 document_id）根本不阻止「子行 A 指向父行 B」，
#             因为父行 id 存在就通过。这是最容易漏的一处。
#       ② 现网数据里**是否已经存在**跨租户引用？
#          —— 只读审计，改动前先摸清存量。
#
#     B 类 · 已补谓词的 JOIN 查询是否真的不带他租户行（D13 §2）
#       ③ 在「本租户」上下文下，重跑已补谓词的 JOIN，断言从表租户全部等于本租户；
#       ④ 不设租户上下文时，同一查询必须返回 0 行（冻结「默认不可见」）。
#
# 【诚实边界】
#   - 本脚本是**只读**的（除 ④ 的 set_config，均在事务内且 ROLLBACK），
#     可以对着任何有这个库的环境跑；不插入、不删除、不建对象。
#   - 它证明的是「数据库当前允许什么」，不证明「应用层调用了什么」。
#     应用层接线由 JoinTenantPredicateArchitectureTest（静态）与
#     Jdbc*RepositoryShadowTest（染色）覆盖。
#   - ③ 在 RLS 未退役时是**双保险下的绿**：把 RLS 关掉再跑一次才算真反证
#     （见 12 文 §3.4「删 RLS → 仍红」的改造要求）。
#
# 用法：
#   PROBE_PG_URI=postgresql://host:5432/study_rag \
#   PROBE_PG_USER=app_agent PROBE_PG_PASSWORD=*** \
#   ./scripts/authz-join-fk-counterproof.sh
#
#   # 可选：指定要审的库与表（默认 study_rag + rag 语料链）
#   PROBE_DB=study_rag PROBE_CHILD=rag.rag_chunk PROBE_PARENT=rag.rag_document \
#   PROBE_KEY=document_id PROBE_TENANT_COL=tenant_id ./scripts/...sh
# =====================================================================

set -euo pipefail

: "${PROBE_PG_URI:?需要 PROBE_PG_URI（postgresql://host:port/db，pg 不认 jdbc: 前缀）}"
: "${PROBE_PG_USER:?需要 PROBE_PG_USER（应用角色，如 app_agent；不要用 BYPASSRLS 角色跑 B 类）}"
: "${PROBE_PG_PASSWORD:?需要 PROBE_PG_PASSWORD}"

PSQL_BIN="${PSQL_BIN:-psql}"
CHILD="${PROBE_CHILD:-rag.rag_chunk}"
PARENT="${PROBE_PARENT:-rag.rag_document}"
KEY="${PROBE_KEY:-document_id}"
TCOL="${PROBE_TENANT_COL:-tenant_id}"

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
info() { printf '  ----  %s\n' "$1"; }

q() {
  PGPASSWORD="$PROBE_PG_PASSWORD" "$PSQL_BIN" "$PROBE_PG_URI" -U "$PROBE_PG_USER" \
    -v ON_ERROR_STOP=1 -Atq -c "$1"
}

echo "== A① 复合外键是否包含 ${TCOL} =="
# 找出「子表 -> 父表 且列集合不含 tenant_id」的外键：这些都是跨租户引用的缺口
MISSING=$(q "
SELECT c.conname
  FROM pg_constraint c
  JOIN pg_class ch ON ch.oid = c.conrelid
  JOIN pg_class pa ON pa.oid = c.confrelid
 WHERE c.contype = 'f'
   AND ch.oid = '${CHILD}'::regclass
   AND pa.oid = '${PARENT}'::regclass
   AND NOT EXISTS (
        SELECT 1 FROM unnest(c.conkey) k
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k
         WHERE a.attname = '${TCOL}'
   )")
if [ -z "$MISSING" ]; then
  ok "${CHILD} -> ${PARENT} 的外键均包含 ${TCOL}"
else
  bad "以下外键不含 ${TCOL}（跨租户引用可插入）：${MISSING}"
fi

echo "== A② 存量数据是否已有跨租户引用（只读） =="
# 只审计「父行租户 != 子行租户」的组合；RLS 在时它也应当为 0
CROSS=$(q "
SELECT count(*)
  FROM ${CHILD} c
  JOIN ${PARENT} p ON p.${KEY} = c.${KEY}
 WHERE p.${TCOL} <> c.${TCOL}")
if [ "$CROSS" = "0" ]; then
  ok "存量无跨租户引用（${CHILD}.${KEY} -> ${PARENT}.${KEY}）"
else
  bad "存量存在 ${CROSS} 条跨租户引用（须先清洗再退役 RLS）"
fi

echo "== B③ 已补谓词的 JOIN 查询：从表租户必须全等于本租户 =="
# 取一个真实存在的租户，重跑 D13 补过谓词的 JOIN 形态
T_A=$(q "SELECT ${TCOL}::text FROM ${CHILD} ORDER BY ${TCOL} LIMIT 1" || true)
if [ -z "${T_A:-}" ]; then
  info "库内无 ${CHILD} 数据，跳过 B 类（空库无法反证）"
else
  info "使用租户 ${T_A}"
  VIOL=$(q "
    BEGIN;
    SELECT set_config('app.tenant_id', '${T_A}', true);
    SELECT count(*)
      FROM ${CHILD} c
      JOIN ${PARENT} p
        ON p.${KEY} = c.${KEY}
       AND p.${TCOL} = c.${TCOL}          -- D13 补的从表谓词
     WHERE c.${TCOL} = '${T_A}'::uuid
       AND p.${TCOL} <> '${T_A}'::uuid;   -- 应当恒为 0
    ROLLBACK;")
  VIOL=$(printf '%s\n' "$VIOL" | tail -1)
  if [ "$VIOL" = "0" ]; then
    ok "JOIN 结果不含他租户从表行"
  else
    bad "JOIN 返回了 ${VIOL} 条他租户从表行（从表谓词失效）"
  fi

  echo "== B④ 无租户上下文时必须返回 0 行 =="
  NOCTX=$(q "
    BEGIN;
    SELECT set_config('app.tenant_id', '', true);
    SELECT count(*) FROM ${CHILD};
    ROLLBACK;")
  NOCTX=$(printf '%s\n' "$NOCTX" | tail -1)
  if [ "$NOCTX" = "0" ]; then
    ok "未设租户上下文时 ${CHILD} 可见 0 行"
  else
    bad "未设租户上下文仍可见 ${NOCTX} 行（默认不可见被破坏）"
  fi
fi

echo
echo "结果：PASS=${PASS}  FAIL=${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1

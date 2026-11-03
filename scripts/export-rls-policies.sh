#!/usr/bin/env bash
#
# =====================================================================
# export-rls-policies.sh —— 导出全部 RLS 策略并归类为「应用层 ScopeFilter」
#
# 【要产出什么】
#   《RLS → 应用层权限迁移方案》(docs/权限管控体系/12_RLS到应用层权限迁移方案.md)
#   的 D1 交付物：一张**翻译对照表**，作为迁移期唯一的比对基线。
#
#   对照表回答一个问题：**每一条现役 RLS 策略，对应哪个 ScopeFilter？**
#   答案必须可机械核验 —— 因为 P1 影子阶段就是拿它跟运行时实际谓词逐条比对的。
#
# 【为什么必须从「活库」导出，而不是从 DDL 脚本里 grep】
#   ① 策略的真实形态受运行库影响（owner、FORCE、继承、ALTER 叠加）；
#   ② 仓库里已发生过「脚本改了但已部署环境察觉不到」（scripts/doc-c27-1.py:105
#      记录的 V26 回填静默 0 行）—— 静态 grep 无法证明运行库与脚本一致；
#   ③ 分类必须按**实际谓词文本**判定（严格相等 / 含哨兵 / 无条件），
#      不是按脚本里的书写顺序。
#
# 【分类规则（与 ScopeFilter 一一对应）】
#   TENANT_ONLY       谓词含 `tenant_id = <ctx>` 形态，**不含**哨兵函数
#                     → ScopeFilter.TenantOnly(subject.tenantId)
#   TENANT_WITH_SHARED 谓词含 `tenant_id IN (<ctx>, <哨兵>)` 形态
#                     → ScopeFilter.TenantWithShared(subject.tenantId)
#   UNRESTRICTED      谓词恒真 / 平台域表 / 运维通道专有
#                     → ScopeFilter.Unrestricted（仅限平台运维主体）
#   NONE              策略缺失（表有 tenant_id 却无策略）—— **这是缺陷，不是分类**
#                     → 必须登记为「缺口」，迁移前补齐
#
# 【重要：读策略与写策略必须分开归类】
#   RAG 语料链 8 张表是「读写分策略」：读放行到哨兵桶、写不放行。
#   把一张表的读写策略合并成一个分类 = 丢失语义 = 迁移期把写权限放宽。⛔
#
# 【用法】
#   ./scripts/export-rls-policies.sh                      # 用默认容器/库
#   PG_CONTAINER=agent-pg17 PG_USER=agent PG_DB=agent_dev \
#     ./scripts/export-rls-policies.sh --out docs/权限管控体系/authz/policy-mapping.yaml
#
#   # 远程活库（RDS / 云库，无法 docker exec）：
#   PG_URI=postgresql://host:port/agent_dev PGPASSWORD=*** \
#     PSQL_BIN=/opt/homebrew/opt/libpq/bin/psql \
#     ./scripts/export-rls-policies.sh --out docs/权限管控体系/authz/policy-mapping.yaml
#
#   ⚠️ 必须以**能看到全部策略**的身份连接（owner / superuser）。
#      用 app_agent（NOBYPASSRLS）连接时 pg_policies 仍可见（策略是目录对象），
#      但若该库已按 P3 退役过策略，导出会显示「缺口」—— 那是真实状态，不要掩盖。
#
#   ⚠️ PG_URI 必须是 libpq 形式（postgresql://…），**不要**直接传 jdbc:postgresql://…：
#      psql 认不出 jdbc: 前缀会静默回落本地 socket，报
#      「connection to server on socket "/tmp/.s.PGSQL.5432" failed」。
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 记录调用方是否显式给了 PG_USER —— 远程模式下只有显式给了才加 -U，
# 否则 psql 会用 OS 用户名（本机工人名），报 password authentication failed。
PG_USER_EXPLICIT="${PG_USER:+1}"
PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"
PG_USER="${PG_USER:-agent}"
PG_DB="${PG_DB:-agent_dev}"
OUT="${OUT:-$ROOT_DIR/docs/权限管控体系/authz/policy-mapping.yaml}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --container) PG_CONTAINER="$2"; shift 2 ;;
    --db) PG_DB="$2"; shift 2 ;;
    --ddl) DDL_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,50p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

say() { printf '%s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*" >&2; }

# ── 连接探针 ──────────────────────────────────────────────
# 【两种模式】
#   ① 本地容器模式（默认）：docker exec 进 PG 容器。
#   ② 远程活库模式：设 PG_URI=postgresql://host:port/db（libpq URI）
#      —— RDS / 云库无法 docker exec，但 D1 要求「从实际运行库导出」，
#      否则基线库里躺的是另一台机器的策略形态（本仓已踩过：基线来自 rag_base，
#      而应用实连 agent_dev，两者策略数 35 vs 50，P1 会全量假 mismatch）。
#      ⚠️ 不要把 JDBC URL 直接传进来（psql 认不出 jdbc: 前缀，会静默回落本地 socket）。
PSQL_BIN="${PSQL_BIN:-psql}"

if [[ -n "${PG_URI:-}" ]]; then
  MODE="remote"
  SOURCE_LABEL="$PG_URI"
  # 口令走 PGPASSWORD（由调用方 export），不在命令行出现
  if [[ -n "$PG_USER_EXPLICIT" ]]; then
    probe() { "$PSQL_BIN" "$PG_URI" -U "$PG_USER" -q -tA -F'|' -c "$1"; }
  else
    probe() { "$PSQL_BIN" "$PG_URI" -q -tA -F'|' -c "$1"; }
  fi
else
  MODE="docker"
  SOURCE_LABEL="$PG_CONTAINER/$PG_DB"
  if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    warn "未找到 PG 容器 $PG_CONTAINER"
    warn "请先启动容器，或用 --container 指定正确的容器名；"
    warn "若要连远程库（RDS），改设 PG_URI=postgresql://host:port/db。"
    exit 3
  fi
  probe() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -q -tA -F'|' -c "$1"; }
fi

psql_q() { probe "$1"; }

say "── 连接探针 ──────────────────────────────────"
say "模式 : $MODE"
say "来源 : $SOURCE_LABEL"
say "身份 : $(psql_q 'SELECT current_user')"
say "库   : $(psql_q 'SELECT current_database()')"

# 策略目录是库级对象，owner 连接最稳；非特权连接也能读 pg_policies
TABLE_COUNT="$(psql_q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema')")"
say "业务表数 : $TABLE_COUNT"

# ── 导出明细 ──────────────────────────────────────────────
# 每行：schema.table | policy_name | cmd | roles | using | with_check | relrowsecurity | relforcerowsecurity
RAW="$(psql_q "
SELECT n.nspname || '.' || c.relname,
       p.polname,
       CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT'
                     WHEN 'w' THEN 'UPDATE' WHEN 'd' THEN 'DELETE'
                     ELSE p.polcmd::text END,
       p.polroles::text,
       coalesce(pg_get_expr(p.polqual, p.polrelid), ''),
       coalesce(pg_get_expr(p.polwithcheck, p.polrelid), ''),
       c.relrowsecurity,
       c.relforcerowsecurity
FROM pg_policy p
JOIN pg_class c ON c.oid = p.polrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog','information_schema')
ORDER BY n.nspname, c.relname, p.polcmd, p.polname
")"

if [[ -z "$RAW" ]]; then
  warn "pg_policies 为空 —— 该库可能尚未部署任何 RLS 策略，或连接身份看不到策略目录。"
  warn "请勿据此判定「无策略即无风险」，先核对部署状态。"
  exit 4
fi

# ── 分类 + 生成 YAML ──────────────────────────────────────
# 哨兵识别：现役哨兵函数名 rag_global_tenant_id() / 全局哨兵列值
SENTINEL_MARKERS="rag_global_tenant_id|global_tenant_id|__shared__|SHARED_TENANT"
CTX_MARKERS="current_tenant_id|rag_current_tenant|app.tenant_id"

classify() {           # $1=using  $2=with_check  $3=cmd  → 输出 分类
  local using="$1" with_check="$2" cmd="$3"
  local hay="$using $with_check"

  # 写策略（INSERT/UPDATE/DELETE）单独判：它们的 with_check 才是真正的闸门
  if [[ "$cmd" == "INSERT" || "$cmd" == "UPDATE" || "$cmd" == "DELETE" ]]; then
    if [[ "$hay" =~ ($SENTINEL_MARKERS) ]]; then
      echo "UNRESTRICTED"
    elif [[ "$hay" =~ ($CTX_MARKERS) ]]; then
      echo "TENANT_ONLY"
    elif [[ "$hay" =~ (true|\bANY\b) ]]; then
      echo "UNRESTRICTED"
    else
      echo "UNKNOWN"
    fi
    return
  fi

  # 读策略（cmd='*' 的单策略 = 读写共用同一谓词）
  if [[ "$hay" =~ ($CTX_MARKERS) && "$hay" =~ ($SENTINEL_MARKERS) ]]; then
    echo "TENANT_WITH_SHARED"
  elif [[ "$hay" =~ ($CTX_MARKERS) ]]; then
    echo "TENANT_ONLY"
  elif [[ "$hay" =~ (true|\bANY\b) ]]; then
    echo "UNRESTRICTED"
  else
    echo "UNKNOWN"
  fi
}

GAP_LIST=""
UNKNOWN_LIST=""
UNRESTRICTED_LIST=""
ASYMMETRY_LIST=""
# 逐表累积分类结果：table|scope1,scope2（读写不对称检测用）
# ⚠️ 用纯文本而非关联数组 —— macOS 自带 bash 3.2 在 set -u 下 declare -A 会报错，
#    且本脚本需在 bash 3.2 / 5.x 下行为一致。
TABLE_SCOPES=""

mkdir -p "$(dirname "$OUT")"

{
  cat <<EOF
# =====================================================================
# policy-mapping.yaml —— RLS 策略 → 应用层 ScopeFilter 翻译对照表
#
# 生成方式：./scripts/export-rls-policies.sh
# 生成时刻：$(date '+%Y-%m-%d %H:%M:%S')
# 来源    ：${SOURCE_LABEL}（活库 pg_policy 目录，非 DDL 脚本）
#
# 【怎么用这份表】
#   ① 这是 P1 影子阶段的**比对基线**：运行时实际谓词必须与本表一致，
#      否则报 AUTHZ_MISMATCH，且**修本表**（不是修代码去迁就）。
#   ② 本表同时是 P3 逐表退役的**清单**：scope: NONE 的行 = 策略缺口，
#      必须先补齐再退役（迁移前补齐，不迁移后补）。
#   ③ 本表是 D8 退役脚本的**生成源**：每条 policy 都对应一条 DROP。
#
# 【字段说明】
#   scope        翻译结论（ScopeFilter 形态）
#   cmd          策略作用：SELECT / INSERT / UPDATE / DELETE
#   roles        适用角色（[] = PUBLIC）
#   using        行可见性谓词（读/删）
#   with_check   写入谓词（写）—— ⛔ 写策略的闸门在这里，不是 using
#   forced       relforcerowsecurity（FORCE RLS）：owner 是否也被策略收口
#
# 【分类口径】
#   TENANT_ONLY        → ScopeFilter.TenantOnly(subject.tenantId)
#   TENANT_WITH_SHARED → ScopeFilter.TenantWithShared(subject.tenantId)
#   UNRESTRICTED       → ScopeFilter.Unrestricted（仅限 isPlatformOps() 主体）
#   NONE               → **缺口**：表带 tenant_id 却无对应策略，须补
#   UNKNOWN            → **待人工判定**：谓词形态不在已知四类内
# =====================================================================

meta:
  generated_at: "$(date '+%Y-%m-%dT%H:%M:%S')"
  source: "$SOURCE_LABEL"
  database: "$(psql_q 'SELECT current_database()')"
  connected_as: "$(psql_q 'SELECT current_user')"
  business_tables: $TABLE_COUNT
  policy_count: $(printf '%s\n' "$RAW" | grep -c .)

policies:
EOF

  while IFS='|' read -r tbl pname cmd roles using wcheck rls forced; do
    [[ -z "$tbl" ]] && continue
    scope="$(classify "$using" "$wcheck" "$cmd")"

    case "$scope" in
      UNRESTRICTED) UNRESTRICTED_LIST+="  - $tbl / $pname ($cmd) — 无条件放行，迁移后须绑 isPlatformOps()"$'\n' ;;
      UNKNOWN)      UNKNOWN_LIST+="  - $tbl / $pname ($cmd) — 谓词形态未知，须人工判定"$'\n' ;;
      NONE)         GAP_LIST+="  - $tbl / $pname ($cmd)"$'\n' ;;
    esac

    # 逐表累积 scope 集合（读写不对称检测用）：同表多个 scope 用逗号连接
    cur_scopes="$(grep "^${tbl}|" <<< "$TABLE_SCOPES" | head -1 | cut -d'|' -f2 || true)"
    if [[ -z "$cur_scopes" ]]; then
      TABLE_SCOPES+="${tbl}|${scope}"$'\n'
    elif [[ ",${cur_scopes}," != *",${scope},"* ]]; then
      TABLE_SCOPES="$(awk -v t="$tbl" -v s="$cur_scopes,$scope" '
          $0 ~ "^"t"\\|" { print t"|"s; next } { print }' <<< "$TABLE_SCOPES")"
    fi

    esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

    cat <<EOF
  - table: "$(esc "$tbl")"
    policy: "$(esc "$pname")"
    cmd: "$(esc "$cmd")"
    roles: "$(esc "$roles")"
    forced: "$forced"
    scope: $scope
    using: "$(esc "$using")"
    with_check: "$(esc "$wcheck")"
EOF
  done <<< "$RAW"
} > "$OUT"

# ── 读写不对称检测 ────────────────────────────────────────
# 「读放行到哨兵桶、写不放行」是 RAG 语料链的核心语义。
# 若把一张表的读写策略合并成一个分类，就丢掉了这个语义 ⇒ 迁移期把写权限放宽。⛔
while IFS='|' read -r tbl scopes; do
  [[ -z "$tbl" ]] && continue
  if [[ "$scopes" == *"TENANT_WITH_SHARED"* && "$scopes" == *"TENANT_ONLY"* ]]; then
    ASYMMETRY_LIST+="  - $tbl — 读=TENANT_WITH_SHARED / 写=TENANT_ONLY（读含哨兵桶、写不含）"$'\n'
  fi
done <<< "$TABLE_SCOPES"

say ""
say "── 分类统计 ──────────────────────────────────"
for s in TENANT_ONLY TENANT_WITH_SHARED UNRESTRICTED UNKNOWN; do
  n="$(grep -c "scope: $s$" "$OUT" || true)"
  say "$(printf '%-20s %s' "$s" "$n")"
done
say ""
say "输出: $OUT"

# ── 缺口扫描：带 tenant_id 却没有对应策略的表 ──────────────
say ""
say "── 缺口扫描（带 tenant_id 却无策略的表）────────────"
GAPS="$(psql_q "
SELECT n.nspname || '.' || c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r','p')
  AND n.nspname NOT IN ('pg_catalog','information_schema')
  AND EXISTS (SELECT 1 FROM pg_attribute a
              WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
  AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
ORDER BY 1
")"

if [[ -z "$GAPS" ]]; then
  say "（无缺口）"
else
  say "⛔ 以下表带 tenant_id 但**没有任何 RLS 策略** —— 迁移前必须确认："
  say "    ① 是平台域表（本就无 RLS，属正常）；"
  say "    ② 还是策略漏写（属缺陷，须补齐 —— 对齐 DBP-03 教训）。"
  printf '%s\n' "$GAPS" | sed 's/^/    - /'
  {
    echo ""
    echo ""
    echo "# ====================================================================="
    echo "# 缺口清单（带 tenant_id 但无 RLS 策略）—— 生成时刻 $(date '+%Y-%m-%d %H:%M:%S')"
    echo "# ⚠️ 逐条判定：平台域表（正常） vs 策略漏写（缺陷，须补）"
    echo "# ====================================================================="
    echo "gaps:"
    printf '%s\n' "$GAPS" | sed 's/^/  - /'
  } >> "$OUT"
fi

# ── UNRESTRICTED 清单：迁移后必须绑 isPlatformOps() ────────
if [[ -n "$UNRESTRICTED_LIST" ]]; then
  {
    echo ""
    echo ""
    echo "# ====================================================================="
    echo "# 无条件放行清单 —— 迁移后这些规则**只能**由 isPlatformOps() 产出"
    echo "# （PolicyEngine 加载期校验，违规拒绝启动 —— 见方案 §2.3.3 / §7.1 R3）"
    echo "# ====================================================================="
    echo "unrestricted:"
    printf '%s' "$UNRESTRICTED_LIST"
  } >> "$OUT"
  say ""
  say "⚠️ 无条件放行策略 $(grep -c 'scope: UNRESTRICTED$' "$OUT" || true) 条 —— 见输出文件末尾清单"
  say "   迁移后这些必须绑 isPlatformOps()，否则等于把平台写权限给了所有租户。⛔"
fi

if [[ -n "$UNKNOWN_LIST" ]]; then
  {
    echo ""
    echo ""
    echo "# ====================================================================="
    echo "# 待人工判定（谓词形态不在已知四类内）—— 迁移前必须逐条归类"
    echo "# ====================================================================="
    echo "unknown:"
    printf '%s' "$UNKNOWN_LIST"
  } >> "$OUT"
  say ""
  say "⚠️ 未知形态 $(grep -c 'scope: UNKNOWN$' "$OUT" || true) 条 —— 须人工归类后才可进入 P1"
fi

# ── 读写不对称清单：翻译时必须保留这个区分 ────────────────
if [[ -n "$ASYMMETRY_LIST" ]]; then
  {
    echo ""
    echo ""
    echo "# ====================================================================="
    echo "# 读写不对称清单（读含哨兵桶 / 写不含）—— **翻译时必须保留该区分**"
    echo "#"
    echo "# ⛔ 为什么单列：这些表的读策略放行到平台全局桶（哨兵），写策略不放行。"
    echo "#    若把一张表的读写策略合并成一个 ScopeFilter，就会**丢掉写侧闸门**"
    echo "#    ⇒ 迁移期租户可以写全局桶 ⇒ 越权。"
    echo "#    迁移实现上，这张表需要**两个** ScopeFilter（读一份、写一份），"
    echo "#    或一个带读写双通道的决策模型。⛔"
    echo "# ====================================================================="
    echo "read_write_asymmetry:"
    printf '%s' "$ASYMMETRY_LIST"
  } >> "$OUT"
  say ""
  say "⚠️ 读写不对称表 $(printf '%s' "$ASYMMETRY_LIST" | grep -c . || true) 张 —— 见输出文件末尾清单"
  say "   这些表迁移后需要**两个** ScopeFilter（读/写各一），合并成一个 = 越权。⛔"
fi

say ""
say "✅ 导出完成。"
say "   下一步：评审本表 → 补齐 scope: NONE / UNKNOWN → 作为 P1 影子比对基线。"

# ── 漂移检测：活库 vs DDL 脚本 ────────────────────────────
# 【为什么必须做这一步】
#   仓库里已发生过「脚本改了但已部署环境察觉不到」（scripts/doc-c27-1.py:105
#   记录的 V26 回填在 FORCE RLS 下静默影响 0 行）。策略漂移是同一类问题的
#   权限面版本：DDL 说 4 条读写分策略，活库却只有 1 条旧策略 —— 此时
#   **以哪一份为准**决定了迁移的正确性。
#   本检测只报事实，不下结论（哪份是「对」的要人工判）。
if [[ -n "${DDL_DIR:-}" ]]; then
  say ""
  say "── 漂移检测（活库 vs DDL 脚本）────────────────────"
  DDL_POLICY_COUNT="$(grep -rhoE 'CREATE POLICY +[a-zA-Z0-9_.]+' "$DDL_DIR" 2>/dev/null | wc -l | tr -d ' ')"
  LIVE_POLICY_COUNT="$(printf '%s\n' "$RAW" | grep -c . || true)"
  say "DDL 脚本 CREATE POLICY 总数 : $DDL_POLICY_COUNT"
  say "活库实际策略数             : $LIVE_POLICY_COUNT"
  if [[ "$DDL_POLICY_COUNT" != "$LIVE_POLICY_COUNT" ]]; then
    say ""
    say "⛔ **策略数量不一致** —— 活库与 DDL 脚本已漂移。迁移前必须判定："
    say "    ① 活库是旧的、脚本是新的（活库需重建）？"
    say "    ② 活库是新的、脚本是旧的（脚本需补）？"
    say "    ③ 两者各自有意为之（需在对照表里注明差异来源）？"
    say "    ⚠️ 不判定就开迁移 = 翻译基线本身是错的（P1 影子会全量 mismatch）。"
  else
    say "✅ 数量一致（仍需逐条比对谓词 —— 数量相同不代表内容相同）。"
  fi
fi

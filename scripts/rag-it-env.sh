#!/usr/bin/env bash
# ============================================================================
# rag-it-env.sh —— RAG 连库 IT（*IT）的隔离环境唯一入口
# ============================================================================
# 为什么需要它：
#   rag/ 的 4 个 IT 类用 Assumptions.assumeTrue(IT_RAG_PG_URL != null) 守门，
#   不带变量时整类跳过（基线 clean verify 只有 682 单测、0 条 IT 真跑）。
#   历史配方把 IT_RAG_PG_URL 指向共享 RDS study_rag —— 那是**应用库**，
#   IT 会往里造数，且残留会让断言假绿（见下方「共享库假绿」）。
#
#   本脚本把「隔离 + 三条通道 + 干净夹具」收敛成一次 source：
#     · 目标库固定为本地 Docker 容器里的 study_rag_it，与 ragdb / study_rag 完全分开；
#     · 结构从 ragdb 只读 pg_dump 导出，不改动 ragdb 一行数据、不改任何现有角色口令；
#     · 三条专用角色：rag_it_app（NOBYPASSRLS）/ rag_it_admin（BYPASSRLS，seed 清理）
#       / rag_it_ops（BYPASSRLS，运维通道）。业务断言一律走 rag_it_app，隔离结论才可信。
#
# 用法
#   source scripts/rag-it-env.sh            # 准备隔离库 + 清夹具 + 导出 9 个变量
#   source scripts/rag-it-env.sh --reset    # 先 DROP DATABASE 再整套重建（清空所有 IT 残留）
#   cd rag && mvn -o clean verify
#
#   只跑管理/检索单类时可用 -Dit.test=...，环境变量由本脚本统一提供。
#
# 可覆盖的默认值
#   RAG_PG_CONTAINER=rag-pg-s2   RAG_TEMPLATE_DB=ragdb   RAG_IT_DB=study_rag_it
#   RAG_PG_HOST=127.0.0.1       RAG_PG_PORT=<自动从 docker port 读取，通常 55435>
#
# ⚠️⚠️ 探针库与生产结构的两处**已知漂移**（不要误以为探针库 == 生产库）：
#   1. 向量列：活库是 halfvec(4096)，RetrievalRepositoryIT 硬编码 ?::vector +
#      1024 维占位嵌入 → 探针库刻意改回 vector(1024)。
#   2. 主键：活库 rag_chunk_embedding 已是 (tenant_id, chunk_id, index_generation)
#      代际复合主键，而该 IT 仍用历史 ON CONFLICT (chunk_id) → 探针库补一个
#      chunk_id 单列唯一约束。
#   所以这轮 IT 覆盖的是检索/隔离/状态机/审计逻辑，**不覆盖**生产向量列形态与
#   索引代际语义。让 IT 适配 halfvec(4096) + 复合主键是独立待办，届时可删掉
#   下面 prepare_probe_shape() 的两步。
#
# ⚠️ 另一处已知缺口：deploy/sql/rag/04_rag_ops_role.sql 的最小授权清单里没有
#   rag_edition，而 G-9 用例（平台档载体导入会自动建默认版本）需要写它，
#   真实生产 rag_ops 角色在该路径同样会 permission denied。本脚本只给**探针专用**
#   rag_it_ops 补权以便把用例跑起来；生产授权脚本是否补，需单独评审。
#
# ⚠️ 共享库假绿（2026-11-06 实测）：legalTransitionChainWritesAudit 断言
#   「登记 + 审核各留一条审计」，历史共享库里同 ISBN 的残留审计行会让
#   auditCount >= 2 恒真。干净隔离库暴露出 applyVerification 把 ADMIT 审计
#   一并跳过（注释与实现矛盾），已修复。⇒ 每次 source 都会 TRUNCATE 全部 rag 表，
#   只保留 4 行全局字典基础数据，杜绝跨轮累积。
#
# 口令：不落仓库。随机生成后缓存在 /tmp/rag-it-env.password（0600），
#   每次 source 都会把三个专用角色的口令重设为该值；仓库/脚本里没有任何凭据。
# ============================================================================

# 跨 shell 取本脚本路径：zsh 用 prompt 转义 %x（BASH_SOURCE 恒空），bash 用 BASH_SOURCE。
if [ -n "${ZSH_VERSION:-}" ]; then
  _RAG_IT_ENV_SELF="${(%):-%x}"
else
  _RAG_IT_ENV_SELF="${BASH_SOURCE[0]:-$0}"
fi

_rag_it_env_root() {
  (cd "$(dirname "$_RAG_IT_ENV_SELF")/.." && pwd)
}

_rag_it_env_die() {
  echo "❌ rag-it-env: $*" >&2
  # ⚠️ 被 source 时不能 exit（会杀掉调用它的交互 shell）
  return 1 2>/dev/null || exit 1
}

# 在容器内以 postgres（local trust）执行 psql；不依赖宿主机装 libpq。
_rag_it_psql() {
  # PGOPTIONS 压掉 03/04 角色脚本的 NOTICE（角色已存在/口令已更新），
  # 保留真实 ERROR：失败仍由调用点的退出码判定。
  docker exec -i -e PGOPTIONS='-c client_min_messages=warning' \
    "$RAG_PG_CONTAINER" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"
}

_rag_it_env_main() {
  local reset=0
  for arg in "$@"; do
    case "$arg" in
      --reset) reset=1 ;;
      -h|--help) sed -n '2,58p' "$_RAG_IT_ENV_SELF"; return 0 ;;
      *) { _rag_it_env_die "未知参数: $arg（只支持 --reset）"; return 1; } ;;
    esac
  done

  local repo
  repo="$(_rag_it_env_root)" || return 1
  RAG_PG_CONTAINER="${RAG_PG_CONTAINER:-rag-pg-s2}"
  RAG_TEMPLATE_DB="${RAG_TEMPLATE_DB:-ragdb}"
  RAG_IT_DB="${RAG_IT_DB:-study_rag_it}"
  RAG_PG_HOST="${RAG_PG_HOST:-127.0.0.1}"

  command -v docker >/dev/null 2>&1 || { _rag_it_env_die "找不到 docker"; return 1; }
  docker inspect -f '{{.State.Running}}' "$RAG_PG_CONTAINER" 2>/dev/null | grep -q true \
    || { _rag_it_env_die "容器 $RAG_PG_CONTAINER 未运行（先 docker start $RAG_PG_CONTAINER）"; return 1; }

  if [ -z "${RAG_PG_PORT:-}" ]; then
    RAG_PG_PORT="$(docker port "$RAG_PG_CONTAINER" 5432/tcp 2>/dev/null | head -1 | sed 's/.*://')"
  fi
  [ -n "$RAG_PG_PORT" ] || { _rag_it_env_die "读不到 $RAG_PG_CONTAINER 的 5432 宿主端口，请显式设 RAG_PG_PORT"; return 1; }

  # ── 守卫：绝不把应用库 / 模板库当成 IT 库来 DROP 或写夹具 ─────────────
  case "$RAG_IT_DB" in
    postgres|template0|template1|ragdb|study_rag|agent_dev|agent_it)
      { _rag_it_env_die "目标库 '$RAG_IT_DB' 是应用库/模板库，拒绝操作"; return 1; } ;;
  esac
  [ "$RAG_IT_DB" = "$RAG_TEMPLATE_DB" ] && { _rag_it_env_die "目标库不能等于模板库 $RAG_TEMPLATE_DB"; return 1; }
  if [ -f "$repo/rag.env" ]; then
    local app_db
    app_db="$(grep -E '^RAG_PG_URL=' "$repo/rag.env" | head -1 | sed -E 's#.*/([^?/]+).*#\1#')"
    if [ -n "$app_db" ] && [ "$RAG_IT_DB" = "$app_db" ]; then
      { _rag_it_env_die "目标库 '$RAG_IT_DB' 正是 rag.env 指向的应用库，拒绝操作"; return 1; }
    fi
  fi
  if [ "$reset" = "1" ] && ! echo "$RAG_IT_DB" | grep -qi 'it'; then
    { _rag_it_env_die "--reset 只允许用于名字含 it 的测试库（当前 $RAG_IT_DB）"; return 1; }
  fi

  # ── 口令：随机生成，缓存到 0600 临时文件，仓库零凭据 ─────────────────
  local cred_file pw
  cred_file="${RAG_IT_CRED_FILE:-/tmp/rag-it-env.password}"
  if [ ! -s "$cred_file" ]; then
    (umask 077; openssl rand -hex 16 > "$cred_file") \
      || { _rag_it_env_die "生成随机口令失败（需要 openssl）"; return 1; }
  fi
  pw="$(cat "$cred_file")"

  # ── 1) 建库 / 重建 ──────────────────────────────────────────────────
  local dump_file="/tmp/rag-it-schema-only.sql"
  local need_build=0
  if [ "$reset" = "1" ]; then
    echo "· DROP DATABASE $RAG_IT_DB WITH (FORCE)"
    _rag_it_psql -d postgres -c "DROP DATABASE IF EXISTS \"$RAG_IT_DB\" WITH (FORCE)" >/dev/null \
      || { _rag_it_env_die "DROP DATABASE 失败"; return 1; }
  fi
  local exists
  exists="$(_rag_it_psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$RAG_IT_DB'" | tr -d '[:space:]')"
  if [ "$exists" != "1" ]; then
    _rag_it_psql -d postgres -c "CREATE DATABASE \"$RAG_IT_DB\"" >/dev/null \
      || { _rag_it_env_die "CREATE DATABASE 失败"; return 1; }
    need_build=1
  elif [ "$(_rag_it_psql -d "$RAG_IT_DB" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='rag'" | tr -d '[:space:]')" = "0" ]; then
    need_build=1
  fi

  if [ "$need_build" = "1" ]; then
    echo "· 从 $RAG_TEMPLATE_DB 只读导出结构 → $RAG_IT_DB"
    docker exec "$RAG_PG_CONTAINER" pg_dump -U postgres -d "$RAG_TEMPLATE_DB" --schema-only > "$dump_file" \
      || { _rag_it_env_die "pg_dump 失败"; return 1; }
    _rag_it_psql -d "$RAG_IT_DB" -f - < "$dump_file" >/dev/null \
      || { _rag_it_env_die "结构导入失败"; return 1; }
    # 活库 ragdb 缺 07 号增量的 metadata_confirmed* 三列（本地库结构不齐），补上。
    _rag_it_psql -d "$RAG_IT_DB" -f - < "$repo/deploy/sql/rag/07_v1_0_2_metadata_confirm_alter.sql" >/dev/null \
      || { _rag_it_env_die "应用 07 号增量失败"; return 1; }
    _rag_it_prepare_probe_shape || return 1
    echo "· 结构就绪：$RAG_IT_DB"
  fi

  # ── 2) 三条专用角色（幂等；只新建 rag_it_*，绝不动 rag_app / rag_ops）──
  _rag_it_psql -d "$RAG_IT_DB" \
    -v rag_role=rag_it_app -v rag_password="$pw" \
    -f - < "$repo/deploy/sql/rag/03_rag_roles.sql" >/dev/null \
    || { _rag_it_env_die "创建 rag_it_app 失败"; return 1; }
  _rag_it_psql -d "$RAG_IT_DB" \
    -v rag_ops_role=rag_it_ops -v rag_ops_password="$pw" \
    -f - < "$repo/deploy/sql/rag/04_rag_ops_role.sql" >/dev/null \
    || { _rag_it_env_die "创建 rag_it_ops 失败"; return 1; }

  _rag_it_psql -d "$RAG_IT_DB" <<SQL >/dev/null || { _rag_it_env_die "创建 rag_it_admin 失败"; return 1; }
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rag_it_admin') THEN
    EXECUTE 'CREATE ROLE rag_it_admin LOGIN NOSUPERUSER BYPASSRLS PASSWORD ''$pw''';
  ELSE
    EXECUTE 'ALTER ROLE rag_it_admin LOGIN NOSUPERUSER BYPASSRLS PASSWORD ''$pw''';
  END IF;
END \$\$;
GRANT USAGE ON SCHEMA rag, public TO rag_it_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA rag TO rag_it_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rag TO rag_it_admin;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rag TO rag_it_admin;
ALTER ROLE rag_it_admin SET search_path = rag, public;
SQL

  # 探针专用扩权：04 号生产脚本未授权 rag_edition，G-9 用例需要（见文件头注释）。
  _rag_it_psql -d "$RAG_IT_DB" -c "GRANT INSERT, UPDATE ON rag.rag_edition TO rag_it_ops" >/dev/null

  # ── 3) 每次 source 都恢复干净夹具，杜绝跨轮残留 ─────────────────────
  _rag_it_psql -d "$RAG_IT_DB" <<'SQL' >/dev/null 2>&1 || { _rag_it_env_die "清理夹具失败"; return 1; }
DO $$ DECLARE t text; BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='rag' LOOP
    EXECUTE format('TRUNCATE TABLE rag.%I CASCADE', t);
  END LOOP;
END $$;
SQL
  grep '^INSERT INTO rag.rag_dictionary' "$repo/deploy/sql/rag/02_rag_seed.sql" \
    | _rag_it_psql -d "$RAG_IT_DB" -f - >/dev/null \
    || { _rag_it_env_die "灌全局字典失败"; return 1; }

  # ── 4) 导出 IT 契约（URL 不带凭据：pgjdbc 对含特殊字符的口令解析有坑）──
  local base="jdbc:postgresql://${RAG_PG_HOST}:${RAG_PG_PORT}/${RAG_IT_DB}"
  export IT_RAG_PG_URL="$base"
  export IT_RAG_PG_USERNAME="rag_it_app"
  export IT_RAG_PG_PASSWORD="$pw"
  export IT_RAG_PG_ADMIN_URL="$base"
  export IT_RAG_PG_ADMIN_USERNAME="rag_it_admin"
  export IT_RAG_PG_ADMIN_PASSWORD="$pw"
  export IT_RAG_OPS_URL="$base"
  export IT_RAG_OPS_USERNAME="rag_it_ops"
  export IT_RAG_OPS_PASSWORD="$pw"

  echo "✅ RAG IT 环境就绪"
  echo "   目标库 : ${RAG_PG_HOST}:${RAG_PG_PORT}/${RAG_IT_DB}（应用库 ragdb / study_rag 均不动）"
  echo "   应用   : rag_it_app（NOBYPASSRLS，隔离断言可信）"
  echo "   管理   : rag_it_admin（BYPASSRLS，仅 seed / 清理全局桶）"
  echo "   运维   : rag_it_ops（BYPASSRLS，全局档写通道）"
  echo "   夹具   : 全部 rag 表已 TRUNCATE，仅保留 4 行全局字典"
  echo "   下一步 : cd rag && mvn -o clean verify"
}

# 探针形态：见文件头「已知漂移」。全部幂等，重跑/--reset 都安全。
_rag_it_prepare_probe_shape() {
  local vec_type
  vec_type="$(_rag_it_psql -d "$RAG_IT_DB" -tAc "
      SELECT format_type(a.atttypid, a.atttypmod)
      FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname='rag' AND c.relname='rag_chunk_embedding' AND a.attname='embedding'
  " | tr -d '[:space:]')"
  if [ "$vec_type" != "vector(1024)" ]; then
    _rag_it_psql -d "$RAG_IT_DB" -c \
      "ALTER TABLE rag.rag_chunk_embedding ALTER COLUMN embedding TYPE vector(1024) USING NULL::vector(1024)" \
      >/dev/null || { _rag_it_env_die "调整向量列为 vector(1024) 失败（表需为空）"; return 1; }
  fi
  local has_uq
  has_uq="$(_rag_it_psql -d "$RAG_IT_DB" -tAc \
    "SELECT 1 FROM pg_constraint WHERE conname='rag_chunk_embedding_chunk_id_uq'" | tr -d '[:space:]')"
  if [ "$has_uq" != "1" ]; then
    _rag_it_psql -d "$RAG_IT_DB" -c \
      "ALTER TABLE rag.rag_chunk_embedding ADD CONSTRAINT rag_chunk_embedding_chunk_id_uq UNIQUE (chunk_id)" \
      >/dev/null || { _rag_it_env_die "补 chunk_id 唯一约束失败"; return 1; }
  fi
}

_rag_it_env_main "$@"

#!/usr/bin/env bash
# ============================================================================
# it-env.sh —— 集成测试（*IT）连接环境的**唯一入口**
# ============================================================================
# 为什么需要它（2026-11-03 决策：IT 迁到 RDS 独立库 agent_it）：
#
#   在此之前，IT 与运行实例**共用一个库**（agent_dev），于是：
#     · IT 往 t-a 灌 Run/快照 → 运行实例的调试数据被污染；
#     · 运行实例的常驻 worker 会抢 IT 造的 Run → 报「任务未被领取」；
#     · execution_snapshot 有 forbid_snapshot_mutation 触发器、**物理删不掉**，
#       所以污染不可逆（实测 t-a 累积 1018 Run / 139 快照）。
#
#   正解不是「记得清理」，而是**换库**：IT 跑 agent_it，agent_dev 只服务运行实例。
#   本脚本把「换库」收敛成一次 source，并把最容易回归的那一步做成 fail-closed：
#   ⚠️ **目标库与应用库同名时直接拒绝**——「IT 又浇回 agent_dev」不再靠人记。
#
# 用法
#   export AI_AGENT_PASSWORD='...'        # RDS 上唯一带 CREATEDB 的业务角色
#   source scripts/it-env.sh              # 目标库默认 agent_it
#   source scripts/it-env.sh --probe      # 额外做一次连通性 + 结构自检
#   IT_DB=agent_it_2 source scripts/it-env.sh
#
#   想临时跑回共享库（不推荐，仅用于复现历史问题）：
#     IT_ALLOW_SHARED_DB=1 source scripts/it-env.sh
#
# 口令从哪来
#   · app_agent 口令  → platform.env 的 SPRING_DATASOURCE_PASSWORD（同集群同角色，直接复用）
#   · ai_agent 口令   → 环境变量 AI_AGENT_PASSWORD（**刻意不落盘**：写进 .env 会被
#                        EnvFile 带进应用进程，等于给运行时多开一个特权通道）
# ============================================================================

# 跨 shell 取本脚本路径：
#   · zsh（Bash 工具运行时）——
#       BASH_SOURCE 恒空、funcfiletrace 因缺 zsh/parameter 模块不可用，
#       唯有 prompt 转义 %x 能稳定给出「正在被 source 的文件」路径；
#       且在函数内 %x 会指向函数定义处，故在顶层先取好，函数里复用。
#   · bash——用 BASH_SOURCE（sourced 时为脚本路径，$0 在 sourced 时为 shell 名）。
if [ -n "${ZSH_VERSION:-}" ]; then
  _IT_ENV_SELF="${(%):-%x}"
else
  _IT_ENV_SELF="${BASH_SOURCE[0]:-$0}"
fi

_it_env_repo_root() {
  local dir
  dir="$(cd "$(dirname "$_IT_ENV_SELF")" && pwd)"
  cd "$dir/.." && pwd
}

_it_env_die() {
  echo "❌ it-env: $*" >&2
  # ⚠️ 被 source 时不能 exit（会杀掉调用它的交互 shell）
  return 1 2>/dev/null || exit 1
}

_it_env_main() {
  local probe=0
  [ "${1:-}" = "--probe" ] && probe=1

  local repo env_file
  repo="$(_it_env_repo_root)"
  env_file="${PLATFORM_ENV_FILE:-$repo/platform.env}"

  [ -f "$env_file" ] || { _it_env_die "找不到 ${env_file}（先 cp platform.env.example platform.env）"; return 1; }

  # ── 解析 platform.env（只认 KEY=VALUE / #注释，与应用侧格式纪律一致）──────
  local _line _k _v
  local app_url='' app_user='' app_pw='' mq_user='' mq_pw='' mq_vhost=''
  local ret_user='' ret_pw=''
  while IFS= read -r _line || [ -n "$_line" ]; do
    case "$_line" in ''|'#'*) continue;; esac
    case "$_line" in *=*) ;; *) continue;; esac
    _k="${_line%%=*}"; _v="${_line#*=}"
    _k="${_k//[[:space:]]/}"
    # 兼容 shell 风格引号：值若整体被 ' 或 " 包裹，去掉外围引号——
    # 否则 PG 会把引号当成口令一部分（实测 platform.env 的
    # AGENT_RETENTION_DATASOURCE_PASSWORD='retention_dev_pw' 带单引号，
    # 不剥离则 IT_RETENTION_PASSWORD 导出为字面 'retention_dev_pw'，
    # RetentionErasureIT 的 agent_retention 登录直接 FATAL: password
    # authentication failed）。SPRING_DATASOURCE_PASSWORD 等不带引号的值不受影响。
    case "$_v" in
      \'*\') _v="${_v#\'}"; _v="${_v%\'}";;
      \"*\") _v="${_v#\"}"; _v="${_v%\"}";;
    esac
    # 去掉 CRLF / 尾部空白（从 Windows 编辑器或复制粘贴带来的隐形字符）
    _v="${_v%$'\r'}"
    case "$_k" in
      SPRING_DATASOURCE_URL)               app_url="$_v";;
      SPRING_DATASOURCE_USERNAME)          app_user="$_v";;
      SPRING_DATASOURCE_PASSWORD)          app_pw="$_v";;
      RABBITMQ_USER)                       mq_user="$_v";;
      RABBITMQ_PASSWORD)                   mq_pw="$_v";;
      RABBITMQ_VHOST)                      mq_vhost="$_v";;
      AGENT_RETENTION_DATASOURCE_USERNAME) ret_user="$_v";;
      AGENT_RETENTION_DATASOURCE_PASSWORD) ret_pw="$_v";;
    esac
  done < "$env_file"

  [ -n "$app_url" ] || { _it_env_die "$env_file 缺 SPRING_DATASOURCE_URL"; return 1; }

  # jdbc:postgresql://host:port/db[?params]
  local rest host_port db
  rest="${app_url#jdbc:postgresql://}"
  host_port="${rest%%/*}"
  db="${rest#*/}"; db="${db%%\?*}"
  local host="${IT_HOST:-${host_port%%:*}}" port="${IT_PORT:-${host_port##*:}}"
  [ "$port" = "$host" ] && port=5432

  local target_db="${IT_DB:-agent_it}"

  # ── 守卫①：IT 不得与运行实例共库 ───────────────────────────────────
  if [ "$target_db" = "$db" ] && [ "${IT_ALLOW_SHARED_DB:-0}" != "1" ]; then
    _it_env_die "目标库 '$target_db' 正是应用库（$env_file 的 SPRING_DATASOURCE_URL）。
   IT 与运行实例共库会互相污染，且 execution_snapshot 受 forbid_snapshot_mutation
   保护、污染不可逆。请改 IT_DB（默认 agent_it），或显式 IT_ALLOW_SHARED_DB=1 复现历史问题。"
    return 1
  fi

  # ── 守卫②：管理角色必须有建库/改结构权限（口令是唯一的入参）──────────
  # 本地容器（IT_HOST 指向 127.0.0.1 且是 trust 认证）不需要口令；
  # RDS 的 ai_agent 必须给，否则整类 IT 在连接阶段就红。
  local admin_user="${IT_ADMIN_USER:-ai_agent}"
  local admin_pw="${IT_ADMIN_PASSWORD:-${AI_AGENT_PASSWORD:-}}"
  if [ -z "$admin_pw" ] && [ "$admin_user" = "ai_agent" ]; then
    _it_env_die "缺 AI_AGENT_PASSWORD（RDS 上唯一带 CREATEDB/CREATEROLE 的业务角色）。
   建库与 IT 的管理通道（dropTrigger / 造跨租户夹具）都需要它。
   导出后重试：export AI_AGENT_PASSWORD='...'
   本地 docker 库可改用：IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent"
    return 1
  fi

  # ── 导出 IT 环境 ─────────────────────────────────────────────────
  export IT_PG_URL="jdbc:postgresql://$host:$port/$target_db"
  export IT_PG_USER="${app_user:-app_agent}"
  export IT_PG_PASSWORD="$app_pw"
  export IT_PG_ADMIN_USER="$admin_user"
  export IT_PG_ADMIN_PASSWORD="$admin_pw"
  # MQ 与 platform.env 同名，IT 侧直接复用（RealBrokerEndToEndIT 等需要）
  # ⚠️ 用 if 而非 `[ -n ... ] && export ...`：调用方若开了 set -e，
  #    「变量为空」会让整条链返回非零，把 source 它的脚本直接掀翻。
  if [ -n "$mq_user" ]; then export RABBITMQ_USER="$mq_user"; fi
  if [ -n "$mq_pw" ]; then export RABBITMQ_PASSWORD="$mq_pw"; fi
  if [ -n "$mq_vhost" ]; then export RABBITMQ_VHOST="$mq_vhost"; fi

  # ── 保留期专用凭据（G3 凭据隔离）────────────────────────────────
  # ⚠️ 缺这两个变量时 RetentionErasureIT **整类挂起**（容器级 abort，failsafe 只报
  #    「Skipped: 1」而实际是 20 个用例一条没跑）——正是本项目「跳过≠通过」的形态。
  #    所以这里必须导，不能等它自己降级。
  if [ -n "$ret_user" ]; then export IT_RETENTION_USER="$ret_user"; fi
  if [ -n "$ret_pw" ]; then export IT_RETENTION_PASSWORD="$ret_pw"; fi

  echo "✅ IT 环境就绪"
  echo "   目标库 : $host:$port/${target_db}（应用库是 ${db}）"
  echo "   应用角色: ${IT_PG_USER}（受 RLS 约束）｜管理角色: ${IT_PG_ADMIN_USER}（结构/夹具）"
  echo "   MQ     : ${RABBITMQ_USER:-<未配>}@${RABBITMQ_VHOST:-<未配>}"
  if [ -n "$ret_pw" ]; then
    echo "   保留期 : ${IT_RETENTION_USER:-agent_retention}（凭据隔离通道，RetentionErasureIT 需要）"
  else
    echo "   ⚠️ 保留期凭据缺失：RetentionErasureIT 会整类挂起（20 个用例一条不跑，failsafe 只显示 Skipped: 1）"
  fi

  # ── 可选：连通性 + 结构自检 ──────────────────────────────────────
  if [ "$probe" = "1" ]; then
    local psql_bin
    psql_bin="$(command -v psql || true)"
    [ -z "$psql_bin" ] && for c in /opt/homebrew/opt/libpq/bin /usr/local/opt/libpq/bin; do
      [ -x "$c/psql" ] && psql_bin="$c/psql" && break
    done
    if [ -z "$psql_bin" ]; then
      echo "   ⚠️ 无 psql，跳过 --probe（brew install libpq 后可自检）"
    else
      local tables rls policies
      tables="$(PGPASSWORD="$IT_PG_ADMIN_PASSWORD" "$psql_bin" -h "$host" -p "$port" -d "$target_db" \
                -U "$IT_PG_ADMIN_USER" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname IN ('public','platform')" 2>&1)" \
        || { echo "   ❌ 连不上 ${target_db}：$tables"; return 1; }
      rls="$(PGPASSWORD="$IT_PG_ADMIN_PASSWORD" "$psql_bin" -h "$host" -p "$port" -d "$target_db" \
                -U "$IT_PG_ADMIN_USER" -tAc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity" 2>/dev/null)"
      policies="$(PGPASSWORD="$IT_PG_ADMIN_PASSWORD" "$psql_bin" -h "$host" -p "$port" -d "$target_db" \
                -U "$IT_PG_ADMIN_USER" -tAc "SELECT count(*) FROM pg_policies WHERE schemaname='public'" 2>/dev/null)"
      # 2026-11-06 P1 记忆表 +1：快照 67 表 / 43 RLS 表 / 51 策略。
      # 2026-11-07 统一模型目录表 model_catalog（全局、无 RLS）：68 表 / 43 RLS 表 / 51 策略。
      # ⚠️ 这三个常量与 init-db.sh 自检、01_platform_ddl.sql 快照是同一份事实，
      #    改快照增删表时必须一起改，否则 --probe 会把「结构陈旧」报成「正常」。
      echo "   结构   : 表 ${tables}（应 68）｜RLS 表 ${rls}（应 43）｜策略 ${policies}（应 51）"
      if [ "$tables" -lt 68 ]; then
        echo "   ⚠️ 库是空的或结构不全 —— 先跑：scripts/init-db.sh platform --db $target_db ..."
      fi
    fi
  fi
}

_it_env_main "$@"

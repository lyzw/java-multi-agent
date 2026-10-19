#!/usr/bin/env bash
#
# dev-seed.sh —— 本地开发数据初始化（租户 / 账号 / 角色）
#
# ⚠️ **仅供本地开发使用**。生产走受控发号流程，无自助注册（D-001 §7）。
#
# 解决的实际卡点：
#   1. password_hash 有 CHECK 约束（须匹配 $pbkdf2-sha256$i=%$%），
#      而生成它的 PasswordHasher.hash() 无任何 CLI 暴露 → 手写 SQL 无法建号。
#   2. 本机并存两个 PostgreSQL（5433 PG17 正确 / 5432 PG16 旧且无 agent_dev 库），
#      连错库会得到"查询为空"的假象。
#
# 用法：
#   ./scripts/dev-seed.sh                 # 初始化默认租户与账号
#   ./scripts/dev-seed.sh --list          # 查看现有租户与账号
#   ./scripts/dev-seed.sh --login <name>  # 只查某账号的 tenant_id
#
# ⚠️ 幂等：重复执行不会重复插入，也不会覆盖已有口令。

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────
PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"
PG_PORT="${PG_PORT:-5433}"
PG_DB="${PG_DB:-agent_dev}"
PG_USER="${PG_USER:-agent}"

DEFAULT_SLUG="t-dev"
DEFAULT_NAME="本地开发租户"
DEFAULT_LOGIN="devstudent"
DEFAULT_PASSWORD="Dev-local-2026"
DEFAULT_ROLES=("STUDENT")

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES_DIR="$ROOT_DIR/platform/agent-infrastructure/target/classes"

# ⚠️ 变量后紧跟全角标点会被 shell 解析为变量名的一部分（见下方同类注释）。
JAVA_HOME="${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}"
JAVA_BIN="$JAVA_HOME/bin/java"
JSHELL_BIN="$JAVA_HOME/bin/jshell"

# ── 输出 ────────────────────────────────────────────────
info()  { printf "  %s\n" "$*"; }
ok()    { printf "✅ %s\n" "$*"; }
warn()  { printf "⚠️  %s\n" "$*"; }
fail()  { printf "❌ %s\n" "$*" >&2; exit 1; }

# ── 前置检查 ─────────────────────────────────────────────
check_prerequisites() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    fail "容器 $PG_CONTAINER 未运行。请先：./scripts/dev-postgres.sh start"
  fi

  if ! docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c '\dt' >/dev/null 2>&1; then
    # ⚠️ 变量后紧跟中文标点会被 shell 解析为变量名的一部分（unbound variable），
    #    故此处全部用 ${} 显式界定边界。
    fail "数据库 ${PG_DB} 不存在或不可访问。
   容器 ${PG_CONTAINER} 暴露端口为 ${PG_PORT}。
   ⚠️ 本机另有旧实例占 5432（无 agent_dev 库），勿连错。"
  fi
}

psql_cmd() {
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "$@"
}

# ── 口令哈希：调用 Java 真实实现，避免算法漂移 ────────────
# ⚠️ 刻意不用 Python/bash 复现 PBKDF2 —— 复现即漂移风险。
#    哈希算法一旦与后端不一致，症状是"密码明明对却登不进去"，极难排查。
generate_password_hash() {
  local raw_password="$1"

  if [[ ! -f "$CLASSES_DIR/cn/studykid/multiagent/infrastructure/security/PrincipalRepository\$PasswordHasher.class" ]]; then
    warn "未找到已编译的 PasswordHasher，正在编译 agent-infrastructure…"
    ( cd "$ROOT_DIR/platform" && mvn -q -pl agent-infrastructure -am compile -DskipTests ) \
      || fail "编译失败。请先执行：cd platform && mvn clean package -DskipTests"
  fi

  local jshell_out
  # ⚠️ jshell 的反馈选项互斥（-q/-s/-v 只能选一个），
  #    此处用 -q（静默）+ 显式 grep 提取结果。
  jshell_out=$("$JSHELL_BIN" \
    --class-path "$CLASSES_DIR" \
    -q <<EOF
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PasswordHasher;
System.out.println("HASH=" + PasswordHasher.hash("$raw_password"));
/exit
EOF
)

  local hash
  hash=$(printf '%s\n' "$jshell_out" | grep -o 'HASH=.*' | head -1 | sed 's/^HASH=//')
  [[ -n "$hash" ]] || fail "生成口令哈希失败：${jshell_out}"
  echo "$hash"
}

# ── 列表 ────────────────────────────────────────────────
cmd_list() {
  # ⚠️ 全角标点会被 shell 当作变量名的一部分（见上方同类注释），
  #    故此处变量一律用 ${} 界定边界。
  echo "=== 租户（${PG_CONTAINER}:${PG_PORT}/${PG_DB}）==="
  psql_cmd -c "SELECT slug, name, status, tenant_id FROM tenant ORDER BY slug;" || true
  echo
  echo "=== 账号 ==="
  psql_cmd -c "SELECT login_name, status, tenant_id FROM principal ORDER BY login_name;" || true
  echo
  echo "=== 角色分配 ==="
  psql_cmd -c "SELECT login_name, role FROM principal_role pr JOIN principal p USING (principal_id) ORDER BY login_name, role;" || true
}

# ── 登录查询 ────────────────────────────────────────────
cmd_login() {
  local login="$1"
  psql_cmd -tAc "SELECT tenant_id FROM principal WHERE login_name = '$login';"
}

# ── 登录诊断 ────────────────────────────────────────────
# ⚠️ 用途：登录返回 invalid_credentials 时，**无法区分**两种原因：
#    ① 账号不存在（被清理脚本删了 / 连错库）
#    ② 账号存在但口令错
#    而这两种的处理方式完全相反（重建 vs 核对口令），必须分开。
#
# 实测遇到过：账号 tadmin / agentauthor 在库中消失（外部清理），
#   若不去查库，会误以为是「口令记错了」而反复试错，方向完全错误。
cmd_diagnose() {
  local login="$1"
  local rows status failed

  rows=$(psql_cmd -tAc "SELECT tenant_id || '|' || status || '|' || failed_attempts
                         FROM principal WHERE login_name = '${login}';" | tr -d ' ')

  if [[ -z "$rows" ]]; then
    printf "❌ 账号不存在：%s\n" "$login"
    echo
    echo "可能原因（按概率排序）："
    echo "  1. 账号被清理脚本删除 —— 本地库会因 migrate/destroy 等操作丢失账号"
    echo "  2. 连错了数据库 —— 本机有 5433(PG17 正确) 与 5432(PG16 旧，无 agent_dev)"
    echo
    echo "请先确认当前库： ./scripts/dev-seed.sh --list"
    echo "确认后可重建：   ./scripts/dev-seed.sh --slug t-a --login ${login} \\"
    echo "                   --password '<口令>' --roles TENANT_ADMIN"
    return 1
  fi

  IFS='|' read -r _ status failed <<< "$rows"
  echo "✅ 账号存在：${login}"
  echo "   状态          : ${status}"
  echo "   连续失败次数  : ${failed}"
  if [[ "$status" != "ACTIVE" ]]; then
    echo "   ⚠️ 状态非 ACTIVE，无法登录。可能是 LOCKED（失败次数超限）。"
  fi
  echo
  echo "若口令确认无误却仍失败，可重建该账号（脚本不会覆盖已有账号，需先删除）："
  echo "  docker exec ${PG_CONTAINER} psql -U ${PG_USER} -d ${PG_DB} -c \\"
  echo "    \"DELETE FROM principal_role WHERE principal_id IN (SELECT principal_id FROM principal WHERE login_name='${login}'); DELETE FROM principal WHERE login_name='${login}';\""
  return 0
}

# ── 初始化 ──────────────────────────────────────────────
cmd_seed() {
  local slug="$DEFAULT_SLUG"
  local name="$DEFAULT_NAME"
  local login="$DEFAULT_LOGIN"
  local password="$DEFAULT_PASSWORD"
  local roles=("${DEFAULT_ROLES[@]}")

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --slug)     slug="$2"; shift 2 ;;
      --name)     name="$2"; shift 2 ;;
      --login)    login="$2"; shift 2 ;;
      --password) password="$2"; shift 2 ;;
      --roles)    IFS=',' read -r -a roles <<< "$2"; shift 2 ;;
      *) fail "未知参数：${1}" ;;
    esac
  done

  # ⚠️ 前置校验：让格式问题在脚本阶段就暴露，
  #    而不是等数据库 CHECK 约束报错（那时错误信息难读）。
  #    login_name: ^[a-z0-9][a-z0-9._-]{2,63}$（实测自 pg_constraint）
  if [[ ! "$login" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]]; then
    fail "登录名不合法：${login}
   约束：^[a-z0-9][a-z0-9._-]{2,63}\$
   即小写字母或数字开头，长度 3–64，后续可含 . _ -"
  fi

  # slug: ^[a-z0-9][a-z0-9-]{1,62}$（实测自 tenant 表 CHECK）
  if [[ ! "$slug" =~ ^[a-z0-9][a-z0-9-]{1,62}$ ]]; then
    fail "租户 slug 不合法：${slug}
   约束：^[a-z0-9][a-z0-9-]{1,62}\$"
  fi

  for role in "${roles[@]}"; do
    # role CHECK: ^[A-Z][A-Z_]{1,31}$
    if [[ ! "$role" =~ ^[A-Z][A-Z_]{1,31}$ ]]; then
      fail "角色名不合法：${role}（须为大写字母与下划线，如 STUDENT / TENANT_ADMIN）"
    fi
  done

  # ⚠️⚠️ 口令中的特殊字符会导致**静默失败**（实测踩坑）。
  #
  # 现象：口令含 '!' 等 shell 敏感字符时，脚本输出「初始化完成」
  #       但账号**根本没入库**—— psql 报错被 shell 吞掉，
  #       而脚本仍继续执行到成功分支（EXIT 0）。
  #
  # 风险：⚠️ 这属于「谎报成功」。比直接失败更危险——
  #       使用者会以为账号可用，直到登录失败才发现。
  #
  # 处置：显式拒绝含 shell/SQL 敏感字符的口令。
  if [[ "$password" == *[!A-Za-z0-9._-]* ]]; then
    fail "口令含不允许的字符。仅支持字母、数字与 . _ -
   ⚠️ 这不是功能限制而是刻意约束：含 \$ ' \` \" ! & | ; ( ) < > 等字符时
   脚本会出现「报成功但实际未入库」的静默失败，极难排查。
   请改用字母数字组合（如 Admin-2026a）。"
  fi

  info "租户 slug : ${slug}"
  info "租户名称 : ${name}"
  info "登录账号 : ${login}"
  info "口令     : ${password}"
  info "角色     : ${roles[*]}"

  local hash
  hash=$(generate_password_hash "$password")

  # ⚠️ 预构造角色 VALUES 字面量。
  #    不能写 VALUES ('A'), ('B') —— 变量与中文逗号相邻会被
  #    shell 解析为变量名的一部分（报 unbound variable）。
  local role_values=""
  for role in "${roles[@]}"; do
    if [[ -z "$role_values" ]]; then
      role_values="('${role}')"
    else
      role_values="${role_values}, ('${role}')"
    fi
  done

  # ⚠️⚠️ **不使用 heredoc 拼接 SQL**。
  #    实测踩坑 3 次：heredoc 未加引号时做变量展开，而 SQL 里的
  #    逗号（','）紧跟变量（'$slug', ...），shell 会把
  #    "'$slug'," 当成一个变量名 → 报 `unbound variable`。
  #    ⚠️ 该坑对**半角逗号**同样成立，不只是全角标点。
  #    故改用 printf 逐行构造：变量只在双引号串内独立出现。
  local sql=""
  sql+="BEGIN;"$'\n'
  sql+="INSERT INTO tenant (tenant_id, slug, name, status) "
  sql+="VALUES (gen_random_uuid(), '${slug}', '${name}', 'ACTIVE') "
  sql+="ON CONFLICT (slug) DO NOTHING;"$'\n'
  sql+="INSERT INTO principal (tenant_id, login_name, password_hash, status) "
  sql+="SELECT t.tenant_id, '${login}', '${hash}', 'ACTIVE' "
  sql+="FROM tenant t WHERE t.slug = '${slug}' "
  sql+="ON CONFLICT (login_name) DO NOTHING;"$'\n'
  sql+="INSERT INTO principal_role (tenant_id, principal_id, role) "
  sql+="SELECT p.tenant_id, p.principal_id, r.role "
  sql+="FROM principal p CROSS JOIN (VALUES ${role_values}) AS r(role) "
  sql+="WHERE p.login_name = '${login}' "
  sql+="ON CONFLICT DO NOTHING;"$'\n'
  sql+="COMMIT;"$'\n'

  # 幂等说明：全部 ON CONFLICT DO NOTHING，
  # 绝不覆盖已有账号（覆盖等于静默改口令，且会绕过失败锁定计数）。
  # ⚠️ login_name 唯一性由**索引**（uq_principal_login_name）而非约束实现，
  #    故用 ON CONFLICT (login_name) 列推断；
  #    ON CONFLICT ON CONSTRAINT 会报「constraint does not exist」（实测）。
  printf '%s' "$sql" | psql_cmd

  echo
  local tenant_id
  tenant_id=$(cmd_login "$login" | tr -d ' ')

  # ⚠️⚠️ 事后校验（不可省略）：
  #   仅靠 SQL 的输出判断成败是不够的 —— psql 的报错可能被吞掉，
  #   而流程仍会走到成功分支（实测出现过「报成功但未入库」）。
  #   故必须**回查数据库**确认真实存在，才能宣告成功。
  if [[ -z "$tenant_id" ]]; then
    fail "初始化失败：数据库中查不到账号 ${login}。
   可能原因：含特殊字符的口令被 shell 截断，或权限不足。
   请检查上方 psql 输出是否有 ERROR。"
  fi

  # 角色也要回查：角色插入失败同样是静默的
  local role_count
  role_count=$(psql_cmd -tAc \
    "SELECT count(*) FROM principal_role pr
       JOIN principal p USING (principal_id)
      WHERE p.login_name = '${login}';" | tr -d ' ')
  if [[ "$role_count" == "0" ]]; then
    fail "账号 ${login} 已创建，但角色未生效。roles 参数：${roles[*]}"
  fi

  ok "初始化完成（已回查确认账号与角色均已入库）"
  echo
  echo "─── 登录信息（可直接复制）───"
  echo "  租户 ID : $tenant_id"
  echo "  账  号  : $login"
  echo "  口  令  : $password"
  echo "  角  色  : ${roles[*]}"
  echo
  echo "─── 验证登录 ───"
  echo "  curl -s -X POST http://localhost:8080/api/auth/token \\"
  echo "    -H 'Content-Type: application/json' \\"
  echo "    -d '{\"tenantId\":\"$tenant_id\",\"loginName\":\"$login\",\"password\":\"$password\"}'"
}

# ── 主流程 ──────────────────────────────────────────────
main() {
  # ⚠️ 变量后紧跟全角标点会被 shell 解析为变量名的一部分
  #    （报 "commandX: unbound variable"），故一律用 ${} 界定。
  local command="${1:-seed}"

  # ⚠️ 首个参数是子命令时才 shift；
  #    若直接传 --slug/--login 等 seed 参数，则不 shift（交由 cmd_seed 解析）。
  case "$command" in
    --list|-l)
      check_prerequisites
      cmd_list
      ;;
    --lookup)
      # ⚠️ 查 tenant_id 的子命令**刻意不叫 --login**：
      #    --login 同时是 seed 的「设账号名」选项，两义会导致歧义
      #    （实测：--login <name> 被子命令分支抢走，seed 的校验形同虚设）。
      check_prerequisites
      shift || true
      cmd_login "${1:-}"
      ;;
    --diagnose)
      check_prerequisites
      shift || true
      cmd_diagnose "${1:-}"
      ;;
    --help|-h)
      usage
      ;;
    --seed)
      shift || true
      check_prerequisites
      cmd_seed "$@"
      ;;
    -*)
      # 其余以 - 开头的参数都是 seed 的选项，不 shift
      check_prerequisites
      cmd_seed "$@"
      ;;
    *)
      fail "未知命令：${command}（可用：--list / --lookup <name> / 默认 seed）"
      ;;
  esac
}

usage() {
  cat <<'USAGE'
用法：
  ./scripts/dev-seed.sh                        用默认参数初始化（t-dev / devstudent）
  ./scripts/dev-seed.sh --list                 列出现有租户、账号、角色
  ./scripts/dev-seed.sh --lookup <name>        查某账号的 tenant_id
  ./scripts/dev-seed.sh --diagnose <name>      诊断登录失败原因（账号不存在 / 被锁）

seed 可选参数：
  --slug <slug>        租户标识（默认 t-dev）
  --name <name>        租户名称（默认 本地开发租户）
  --login <name>       登录名（默认 devstudent）
  --password <pwd>     口令（默认 Dev-local-2026，仅限字母数字 . _ -）
  --roles <R1,R2>      角色，逗号分隔（默认 STUDENT）

示例：
  ./scripts/dev-seed.sh --login alice --roles TEACHER,APPROVER
  ./scripts/dev-seed.sh --lookup alice
  ./scripts/dev-seed.sh --diagnose alice
USAGE
}

main "$@"
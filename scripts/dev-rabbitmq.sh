#!/usr/bin/env bash
#
# =====================================================================
# RabbitMQ 本地开发环境（DEC-12 / ADR-001）
#
# 为什么不是随手一条 docker run：
#   本项目的 MQ 语义依赖 quorum queue 的两个特性，必须显式配置，
#   否则会静默退化为 classic queue 而失去复制能力：
#
#   1. x-queue-type: quorum
#      RabbitMQ 4.x 未配置 vhost 默认队列类型时，默认仍是 classic。
#      不显式声明就拿到 classic 队列——classic 在 4.x 没有复制能力
#      （classic mirrored queue 已在 4.0 彻底移除）。
#
#   2. x-delivery-limit: 3
#      quorum queue 默认 delivery-limit 是 20。对本项目唯一的
#      有副作用工具 record_weakness 而言，重投 20 次意味着重复写入
#      最多 20 次 —— Sev-0。必须显式降到 3。
#
# 单节点限制（务必知情）：
#   单节点集群下 quorum queue 的 Raft「多数派」就是自己本身，
#   功能正常但无高可用。仅适用于本地开发与 PoC。
#   生产环境须起 3 节点集群（见文件末尾「生产集群」）。
#
# 用法：
#   ./scripts/dev-rabbitmq.sh start     启动并准备 vhost
#   ./scripts/dev-rabbitmq.sh stop      停止，保留数据
#   ./scripts/dev-rabbitmq.sh destroy   停止并删除容器
#   ./scripts/dev-rabbitmq.sh status    查看状态
#   ./scripts/dev-rabbitmq.sh verify    检查队列参数（应用启动后执行）
#   ./scripts/dev-rabbitmq.sh logs      跟踪日志
# =====================================================================

set -euo pipefail

# ── 配置（可用环境变量覆盖）────────────────────────────────────────
CONTAINER_NAME="${RMQ_CONTAINER:-agent-rabbitmq-1}"
# ADR-001 指定 4.3.6。若本地无该镜像，用 RMQ_IMAGE 覆盖为已有版本
# （quorum queue 需 >= 3.8）。
IMAGE="${RMQ_IMAGE:-rabbitmq:4.3.6-management}"
NETWORK="${RMQ_NETWORK:-agent-local}"
VHOST="${RMQ_VHOST:-agent}"
RMQ_USER="${RMQ_USER:-agent}"
RMQ_PASS="${RMQ_PASSWORD:-agent_dev_pw}"
PERSIST_DIR="${RMQ_DATA:-$HOME/.agent-platform/rabbitmq}"

AMQP_PORT="${RMQ_AMQP_PORT:-5672}"
MGMT_PORT="${RMQ_MGMT_PORT:-15672}"

# 镜像加速器：Docker Hub 直连不可达时使用。
# 用法：RMQ_MIRROR=docker.xuanyuan.me ./scripts/dev-rabbitmq.sh start
#   脚本会拉取 <mirror>/library/rabbitmq:4.3.6-management 后 retag 为标准名，
#   使后续脚本与文档中的 IMAGE 值保持不变。
#   注意：免费节点对单 IP 限流（TOOMANYREQUESTS），繁忙时需重试或换加速器。
MIRROR="${RMQ_MIRROR:-}"
PULL_RETRIES="${RMQ_PULL_RETRIES:-3}"

# 队列拓扑（ADR-001 §4）
RUN_EXECUTE_QUEUE="agent.run.execute"
RUN_RECONCILE_QUEUE="agent.run.reconcile"
DEAD_QUEUE="agent.run.dead"
DELIVERY_LIMIT=3

say()  { printf '\033[0;36m[MQ]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[MQ] ⚠\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[MQ] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

usage() {
  cat <<EOF

用法: $0 {start|stop|destroy|status|verify|logs}

  start     启动 RabbitMQ 并准备 vhost（队列由应用启动时声明）
  stop      停止容器，保留数据
  destroy   删除容器（数据目录需手动清理）
  status    查看运行状态
  verify    检查队列类型与 delivery-limit（应用启动后必做）
  logs      跟踪日志

镜像获取（Docker Hub 直连不可达时）：
  RMQ_MIRROR=docker.xuanyuan.me $0 start
  RMQ_PULL_RETRIES=5          # 限流时重试次数，默认 3

典型流程：
  1) $0 start
  2) 启动应用（Spring 用 spring.rabbitmq.* 声明 quorum 队列）
  3) $0 verify      ← 确认三个队列均为 quorum 且 delivery-limit=3

环境变量：
  RMQ_CONTAINER  容器名     默认 agent-rabbitmq-1
  RMQ_IMAGE      镜像       默认 rabbitmq:4.3.6-management
  RMQ_NETWORK    网络       默认 agent-local
  RMQ_VHOST      vhost      默认 agent
  RMQ_USER       用户名     默认 agent
  RMQ_PASSWORD   密码       默认 agent_dev_pw
  RMQ_AMQP_PORT  AMQP 端口  默认 5672
  RMQ_MGMT_PORT  管理端口   默认 15672
  RMQ_DATA       数据目录     默认 ~/.agent-platform/rabbitmq
  RMQ_MIRROR     镜像加速器   默认空（直连）；如 docker.xuanyuan.me
  RMQ_PULL_RETRIES 拉取重试   默认 3
EOF
}

container_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; }
container_running() { [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || echo false)" = "true" ]; }

ensure_network() {
  if ! docker network ls --format '{{.Name}}' | grep -qx "$NETWORK"; then
    say "创建网络 $NETWORK"
    docker network create "$NETWORK" >/dev/null
  fi
}

# 镜像不可用时给出可执行建议，而不是静默继续或用错版本
resolve_image() {
  if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    say "使用本地镜像 $IMAGE"
    return 0
  fi

  # 走加速器：拉取后 retag 为标准名，保持 IMAGE 取值不变
  if [ -n "$MIRROR" ]; then
    local remote="${MIRROR}/library/${IMAGE#*/}"
    say "经加速器拉取：$remote"
    pull_with_retry "$remote" || die "加速器拉取失败（常见原因：免费节点限流 TOOMANYREQUESTS）"
    say "retag 为标准镜像名 $IMAGE"
    docker tag "$remote" "$IMAGE" >/dev/null
    return 0
  fi

  say "本地无 ${IMAGE} ，尝试直连拉取…"
  if docker pull "$IMAGE" >/dev/null 2>&1; then
    return 0
  fi
  die "无法获取镜像 $IMAGE"${$IMAGE}"下方可选处理）"
}

# 带重试的拉取：加速器限流时返回 403 TOOMANYREQUESTS，多半是临时的
pull_with_retry() {
  local remote="$1"
  local i
  for i in $(seq 1 "$PULL_RETRIES"); do
    say "  拉取尝试 $i/$PULL_RETRIES …"
    if docker pull "$remote" >/dev/null 2>&1; then
      return 0
    fi
    if [ "$i" -lt "$PULL_RETRIES" ]; then
      sleep $((i * 5))
    fi
  done
  return 1
}

# 只做控制面准备（vhost + 权限），不声明队列。
# 队列类型与 delivery-limit 来自应用配置，由 Spring 在启动时声明——
# 若由运维脚本预建，会造成「配置改了但队列没变」的错觉，
# 且应用声明参数不一致时会抛 PRECONDITION_FAILED。
prepare_vhost() {
  say "准备 vhost '$VHOST'"
  if docker exec "$CONTAINER_NAME" rabbitmqctl list_vhosts -q 2>/dev/null | grep -qx "$VHOST"; then
    say "vhost 已存在，跳过创建"
  else
    docker exec "$CONTAINER_NAME" rabbitmqctl add_vhost "$VHOST" >/dev/null
    say "vhost 已创建"
  fi
  docker exec "$CONTAINER_NAME" rabbitmqctl set_permissions \
    -p "$VHOST" "$RMQ_USER" ".*" ".*" ".*" >/dev/null
  say "权限已授予（队列声明交由应用启动时完成）"
}

cmd_start() {
  if container_running; then
    say "已在运行：$CONTAINER_NAME"
    return 0
  fi

  if container_exists; then
    say "容器存在但未运行，启动中"
    docker start "$CONTAINER_NAME" >/dev/null
  else
    ensure_network
    resolve_image
    mkdir -p "$PERSIST_DIR"
    say "启动容器 $CONTAINER_NAME"
    # 端口绑定 127.0.0.1：本地开发不应暴露到局域网
    docker run -d \
      --name "$CONTAINER_NAME" \
      --hostname "$CONTAINER_NAME" \
      --network "$NETWORK" \
      -p "127.0.0.1:${AMQP_PORT}:5672" \
      -p "127.0.0.1:${MGMT_PORT}:15672" \
      -e RABBITMQ_DEFAULT_USER="$RMQ_USER" \
      -e RABBITMQ_DEFAULT_PASS="$RMQ_PASS" \
      -e RABBITMQ_DEFAULT_VHOST="$VHOST" \
      -v "$PERSIST_DIR:/var/lib/rabbitmq" \
      "$IMAGE" >/dev/null
  fi

  say "等待 RabbitMQ 就绪…"
  # 用 await_startup 而非 diagnostics ping：
  # ping 通过不代表 rabbit app 可用，直接执行 set_permissions 会报
  # "requires the 'rabbit' app"。
  local i
  for i in $(seq 1 90); do
    if docker exec "$CONTAINER_NAME" rabbitmqctl await_startup --timeout 5 >/dev/null 2>&1; then
      say "就绪（耗时约 ${i}s）"
      prepare_vhost
      say "下一步：启动应用后执行 '$0 verify' 校验队列参数"
      return 0
    fi
    sleep 1
  done
  die "启动超时，请执行 '$0 logs' 查看日志"
}

cmd_verify() {
  container_running || die "容器未运行，请先执行 '$0 start'"

  # 先检查 vhost 默认队列类型 —— 这是最容易踩的坑：
  # 4.x 实测 default_queue_type = classic，即不显式声明 x-queue-type
  # 就会静默退化为 classic 队列，而 classic 在 4.x 没有复制能力。
  local default_type
  default_type=$(docker exec "$CONTAINER_NAME" rabbitmqctl list_vhosts \
    -q name default_queue_type 2>/dev/null \
    | awk -v v="$VHOST" '$1 == v { print $2 }' | tail -1)

  if [ -n "$default_type" ]; then
    say "vhost '${VHOST}' 默认队列类型：${default_type}"
    if [ "$default_type" != "quorum" ]; then
      warn "默认类型为 ${default_type} —— 应用必须显式声明 x-queue-type: quorum，"
      warn "否则队列会静默退化为 ${default_type}，在 4.x 下失去复制能力。"
      warn "（本项目 application.yml 已显式声明，见 agent.mq.*）"
    fi
  fi
  echo ""

  say "=== 当前队列 ==="
  local rows
  rows=$(docker exec "$CONTAINER_NAME" rabbitmqctl list_queues \
    --vhost "$VHOST" name type durable arguments 2>/dev/null || true)

  if [ -z "$rows" ] || ! echo "$rows" | grep -q "^$RUN_EXECUTE_QUEUE"; then
    warn "vhost '$VHOST' 下尚未声明本项目队列"
    warn "请先启动应用（Spring 用 spring.rabbitmq.* 声明队列），再执行本命令"
    return 1
  fi
  echo "$rows"

  local target_rows
  target_rows=$(echo "$rows" \
    | grep -E "^${RUN_EXECUTE_QUEUE}[[:space:]]|^${RUN_RECONCILE_QUEUE}[[:space:]]|^${DEAD_QUEUE}[[:space:]]" || true)

  echo ""
  say "=== 校验结果 ==="

  if [ -z "$target_rows" ]; then
    warn "未找到本项目的三个队列，请确认应用已启动并声明了队列"
    return 1
  fi

  local quorum_count classic_count
  quorum_count=$(echo "$target_rows" | grep -cw "quorum" || true)
  classic_count=$(echo "$target_rows" | grep -cw "classic" || true)

  if [ "$classic_count" -gt 0 ]; then
    printf '\033[0;31m  ✗ 检测到 classic 队列（%s 个）：classic 在 4.x 无复制能力\033[0m\n' "$classic_count"
    printf '\033[0;31m    须在应用配置中显式声明 x-queue-type: quorum\033[0m\n'
  fi
  if [ "$quorum_count" -eq 3 ]; then
    printf '\033[0;32m  ✓ 三个队列均为 quorum 类型（具备 Raft 复制）\033[0m\n'
  elif [ "$quorum_count" -gt 0 ]; then
    printf '\033[0;33m  ! 仅 %s/3 个队列为 quorum，其余需检查配置\033[0m\n' "$quorum_count"
  fi

  if echo "$target_rows" | grep -q "delivery-limit.*${DELIVERY_LIMIT}"; then
    printf '\033[0;32m  ✓ delivery-limit = %s（防重复写入 Sev-0）\033[0m\n' "$DELIVERY_LIMIT"
  elif echo "$target_rows" | grep -q "delivery-limit"; then
    printf '\033[0;31m  ✗ delivery-limit 非 %s：重复投递可能导致重复写入\033[0m\n' "$DELIVERY_LIMIT"
  else
    printf '\033[0;31m  ✗ 未见 delivery-limit：quorum 默认 20，对 record_weakness 风险过高\033[0m\n'
  fi

  echo ""
  warn "单节点说明：quorum queue 的 Raft 多数派 = 自身，功能正常但无高可用。"
  warn "生产环境须起 3 节点集群，见本文件末尾「生产集群」段。"
}

cmd_stop() {
  container_exists || { say "容器不存在"; return 0; }
  say "停止容器（数据保留在 ${PERSIST_DIR}）"
  docker stop "$CONTAINER_NAME" >/dev/null
}

cmd_destroy() {
  container_exists || { say "容器不存在"; return 0; }
  warn "删除容器"
  docker rm -f "$CONTAINER_NAME" >/dev/null
  say "如需彻底清除数据：rm -rf $PERSIST_DIR"
}

cmd_status() {
  if container_running; then
    say "运行中：$CONTAINER_NAME"
    docker exec "$CONTAINER_NAME" rabbitmqctl status 2>/dev/null | head -12 || true
    echo ""
    say "AMQP: 127.0.0.1:${AMQP_PORT}    管理界面: http://127.0.0.1:${MGMT_PORT}"
  else
    say "未运行：$CONTAINER_NAME"
  fi
}

case "${1:-start}" in
  start)          cmd_start ;;
  stop)           cmd_stop ;;
  destroy)        cmd_destroy ;;
  status)         cmd_status ;;
  verify)         cmd_verify ;;
  logs)           docker logs -f "$CONTAINER_NAME" ;;
  -h|--help|help) usage ;;
  *)              usage; exit 1 ;;
esac

# =====================================================================
# 生产集群参考（本脚本只起单节点，生产需 3 节点）
#
#   docker network create agent-prod
#   for i in 1 2 3; do
#     docker run -d --name rmq-$i --hostname rmq-$i \
#       --network agent-prod -p 567$i:5672 \
#       -e RABBITMQ_ERLANG_COOKIE=<共享 cookie> \
#       rabbitmq:4.3.6-management
#   done
#
#   # 逐个加入集群（erlang cookie 必须一致）
#   for i in 2 3; do
#     docker exec rmq-$i rabbitmqctl stop_app
#     docker exec rmq-$i rabbitmqctl reset
#     docker exec rmq-$i rabbitmqctl join_cluster rabbit@rmq-1
#     docker exec rmq-$i rabbitmqctl start_app
#   done
#   docker exec rmq-1 rabbitmqctl cluster_status
#
#   生产需修正三处（本脚本默认值仅适用于开发）：
#     1. 去掉 -p 127.0.0.1 绑定，按实际网络暴露
#     2. 密码改由密钥管理注入，不得写入 compose 文件或环境变量默认值
#     3. quorum queue 副本数建议为 3（奇数，便于计算多数派）
#
#   社区支持 2026-11-30 止（R-022 已接受）：P4 生产准入前须评估升级 4.4。
# =====================================================================
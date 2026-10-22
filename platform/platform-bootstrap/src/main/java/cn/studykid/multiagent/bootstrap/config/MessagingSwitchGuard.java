package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;

import cn.studykid.multiagent.infrastructure.messaging.PgQueueRepository;

/**
 * 消息通道切换残留检查（P4-D17 · 把「静默滞留」变成「启动即报错」）。
 *
 * <p><b>⚠️ 为什么切换前必须排空旧通道</b>：两条通道各有各的积压。
 * 从 RabbitMQ 切到 DB（或反之）时，旧通道里未消费的消息<b>不会被新通道自动接管</b>——
 * 它们会安静地躺在旧通道里，Run 永远不执行，
 * 而系统表面完全正常（API 正常、无报错、无告警）。
 * 这与本项目已记录的「断链」形态同族，且更难察觉。
 *
 * <p><b>⚠️ 本守卫把这件事变成显式失败</b>：
 * <ol>
 *   <li><b>DB 侧残留</b>（可精确检查）：某个租户已切到 RabbitMQ，
 *       但它的 DB 队列里还有待消费消息 → <b>拒绝启动</b>；</li>
 *   <li><b>Rabbit 侧残留</b>（只能整体检查）：Rabbit 队列是<b>跨租户共享</b>的，
 *       无法分辨里面哪条属于谁 —— 故只要存在 DB 模式租户，
 *       就要求 Rabbit 队列<b>整体为空</b>；不空则<b>拒绝启动</b>。</li>
 * </ol>
 *
 * <p><b>⚠️ 「无法检查」不等于「通过」</b>：
 * broker 不可达时无法检查 Rabbit 侧残留，此时：
 * <ul>
 *   <li>若 Rabbit 通道<b>在用</b> → <b>拒绝启动</b>（通道启用却连不上 broker = 消息永远不消费）；</li>
 *   <li>若 Rabbit 通道<b>不在用</b>（纯 DB 部署）→ 放行并<b>记一条 WARN</b>：
 *       此时 broker 里即便有残留消息，它也只可能来自更早的部署，
 *       需人工排空（消息仍在 broker 里，<b>没有丢失</b>，只是无人消费）。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么用 TCP 预检</b>：Rabbit 的连接超时是 10s，
 * 若每次启动都去「探测一下 broker」，无 broker 的环境（CI）会白白多等 10s。
 * TCP 预检最多 1s，且连接被拒是立即返回的。
 */
public final class MessagingSwitchGuard {

    private static final Logger log = LoggerFactory.getLogger(MessagingSwitchGuard.class);

    private static final int TCP_PROBE_TIMEOUT_MS = 1000;

    /**
     * 供 Spring 以 Bean 形式持有（检查本身是静态方法）。
     */
    public MessagingSwitchGuard() {
    }

    /**
     * 执行切换残留检查。
     *
     * @param channels       通道分配
     * @param pgRepository   DB 队列仓储（检查 DB 侧残留）
     * @param rabbitAdmin    Rabbit 管理器（检查 Rabbit 侧残留；可为 null 表示不检查）
     * @param rabbitHost     Rabbit 主机（TCP 预检）
     * @param rabbitPort     Rabbit 端口（TCP 预检）
     * @param rabbitQueueName Rabbit 执行队列名
     * @throws IllegalStateException 存在残留或通道不可用（拒绝启动）
     */
    public static void verify(@NonNull MessagingChannels channels,
                              @NonNull PgQueueRepository pgRepository,
                              @Nullable AmqpAdmin rabbitAdmin,
                              @Nullable String rabbitHost,
                              int rabbitPort,
                              @NonNull String rabbitQueueName) {
        // ── 检查 1：DB 侧残留（可精确到租户）──────────────────────────
        List<String> rabbitTenants = channels.rabbitTenantIds();
        for (String tenantId : rabbitTenants) {
            int pending = pgRepository.pendingDepthOf(tenantId);
            if (pending > 0) {
                throw new IllegalStateException(
                        "⛔ 消息通道切换残留：该租户当前为 rabbitmq 模式，"
                                + "但它的数据库队列里还有 " + pending + " 条待消费消息（tenantId="
                                + tenantId + "）。这些消息不会被 RabbitMQ 通道接管，"
                                + "将永远滞留（Run 永不执行且无任何报错）。"
                                + "处置：先用 database 模式运行至队列排空，再切换；"
                                + "或把它们迁回后再启动。");
            }
        }

        // ── 检查 2：Rabbit 侧残留（只能整体检查）───────────────────────
        if (!channels.databaseActive()) {
            // 没有 DB 模式租户 → Rabbit 侧残留与本次启动无关
            return;
        }
        if (rabbitHost == null || rabbitHost.isBlank()) {
            log.info("RabbitMQ 主机未配置，跳过 Rabbit 侧残留检查（无 broker 即无残留可查）");
            return;
        }
        boolean reachable = tcpReachable(rabbitHost, rabbitPort);
        if (!reachable) {
            if (channels.rabbitActive()) {
                throw new IllegalStateException(
                        "⛔ 消息通道启用 rabbitmq，但 broker 不可达: " + rabbitHost + ":" + rabbitPort
                                + "（fail-closed：通道启用却连不上 = 消息永远不消费）");
            }
            log.warn("⚠️ RabbitMQ 不可达（{}:{}），跳过 Rabbit 侧残留检查。"
                    + "若该 broker 中仍有旧消息，它们不会丢但也无人消费——请人工排空。",
                    rabbitHost, rabbitPort);
            return;
        }
        if (rabbitAdmin == null) {
            log.info("Rabbit 管理器不可用，跳过 Rabbit 侧残留检查");
            return;
        }
        Integer depth = residualDepth(rabbitAdmin, rabbitQueueName);
        if (depth != null && depth > 0) {
            throw new IllegalStateException(
                    "⛔ 消息通道切换残留：RabbitMQ 队列 " + rabbitQueueName + " 仍有 " + depth
                            + " 条未消费消息，而当前存在数据库模式租户。"
                            + "Rabbit 队列跨租户共享，无法把其中的消息分别交给新通道，"
                            + "故必须先排空再切换（否则这些消息永远滞留、Run 永不执行）。"
                            + "处置：先用 rabbitmq 模式启动一次让队列排空，或在 broker 侧清理。");
        }
        log.info("消息通道切换残留检查通过: Rabbit 队列 {} 无残留", rabbitQueueName);
    }

    /**
     * 取队列中未消费的消息数。
     *
     * @return 深度；队列不存在返回 0；broker 不可达返回 null（无法检查）
     */
    private static @Nullable Integer residualDepth(AmqpAdmin admin, String queueName) {
        try {
            // ⚠️ Spring AMQP 4：getQueueProperties 返回 Properties；
            //    队列不存在时返回 null（与「队列为空」在残留语义上等价）。
            Properties props = admin.getQueueProperties(queueName);
            if (props == null) {
                return 0;
            }
            String count = props.getProperty("QUEUE_MESSAGE_COUNT");
            if (count == null) {
                return 0;
            }
            try {
                return Integer.parseInt(count.trim());
            } catch (NumberFormatException e) {
                log.warn("Rabbit 队列 {} 的消息数为非数字（按 0 处理）: {}", queueName, count);
                return 0;
            }
        } catch (AmqpException e) {
            log.warn("读取 Rabbit 队列 {} 深度失败（无法检查残留）: {}", queueName, e.getMessage());
            return null;
        }
    }

    /** TCP 预检（最多 1s；连接被拒立即返回）。 */
    private static boolean tcpReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

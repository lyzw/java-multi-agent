package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Rabbit 通道健康指示器（**替换** Spring Boot 自动装配的那一个）。
 *
 * <p><b>⚠️ 为什么必须替换（不是「关掉检查」）</b>：
 * Spring Boot 只要 classpath 上有 {@code spring-boot-starter-amqp} 就
 * <b>无条件</b>装配 {@code RabbitHealthIndicator}（条件只有「有 RabbitTemplate bean」），
 * 与「本实例是否真的在用 Rabbit 通道」<b>毫无关系</b>。
 * 而本项目自 P4-D17 起默认走 {@code database} 通道
 * （{@code agent.messaging.mode} 默认 {@code database}），
 * 默认部署里<b>根本没有 RabbitMQ</b> →
 * 自动装配的指示器照样去连 {@code 127.0.0.1:5672} → {@code Connection refused}
 * → {@code /actuator/health} 报 <b>DOWN（实测 HTTP 503）</b>。
 *
 * <p><b>⚠️ 这个假 DOWN 有三重真实危害</b>：
 * <ol>
 *   <li><b>可用性</b>：任何以 {@code /actuator/health} 为存活/就绪探针的编排
 *       （k8s probe / Docker healthcheck / 云负载均衡）会把<b>完全健康的实例</b>
 *       判定为不健康 → 反复重启或摘除流量；</li>
 *   <li><b>排查方向被带偏</b>：健康明细被刻意设为最小暴露面（{@code show-details=never}），
 *       匿名访问只看得到 {@code status=DOWN}，看不出「是 Rabbit」——
 *       运维会误判为数据库或应用故障；</li>
 *   <li><b>日志噪音</b>：每次探针轮询刷一整页 Rabbit 连接堆栈，
 *       真故障发生时反而更难在日志里定位。</li>
 * </ol>
 *
 * <p><b>⚠️ 口径必须与启动期 {@link MessagingSwitchGuard} 完全一致</b>：
 * <ul>
 *   <li>Rabbit 通道<b>在用</b> → <b>真检查</b>：broker 不可达即 DOWN
 *       （通道启用却连不上 = 消息永远不消费，与启动期拒绝启动同源）；</li>
 *   <li>Rabbit 通道<b>不在用</b>（纯 DB 部署）→ <b>不参与健康判定</b>，
 *       且<b>连都不连</b>（不误报 DOWN，也不刷堆栈）。</li>
 * </ul>
 * 这不是「把检查关掉」，而是<b>只对实际在用的依赖下判决</b>——
 * 用一个根本不部署的中间件给整个实例的健康下判决，本身就是谎报。
 *
 * <p><b>⚠️ 委托给 Spring 自己的 {@link RabbitHealthIndicator}</b>：
 * Rabbit 在用时<b>不重新实现</b>探测逻辑，直接用 Spring 的那个，
 * 保证「修之前它怎么报，修之后还怎么报」——不会出现
 * 「我们自己写的检查比 Spring 宽松 → 真故障被放过」这种倒退。
 *
 * <p><b>⚠️ worker 未启用时没有 {@link MessagingChannels} Bean</b>：
 * 通道分配 Bean 位于 {@code WorkerLoopConfig}，而它带
 * {@code @ConditionalOnProperty("agent.worker.enabled")}；
 * worker 关闭时本实例<b>不消费任何消息</b> → 没有租户在用 Rabbit 通道
 * → 同样不参与健康判定。故此处用 {@link ObjectProvider} 取，缺失即视为「不在用」。
 */
public final class MessagingChannelHealthIndicator implements HealthIndicator {

    private final ObjectProvider<MessagingChannels> channelsProvider;

    private final ObjectProvider<RabbitTemplate> templateProvider;

    /**
     * @param channelsProvider 通道分配（worker 未启用时不存在 → 视为「Rabbit 不在用」）
     * @param templateProvider Rabbit 发送器（无 Rabbit 通道的部署里也可能没有）
     */
    public MessagingChannelHealthIndicator(
            @NonNull ObjectProvider<MessagingChannels> channelsProvider,
            @NonNull ObjectProvider<RabbitTemplate> templateProvider) {
        this.channelsProvider = channelsProvider;
        this.templateProvider = templateProvider;
    }

    @Override
    public @NonNull Health health() {
        MessagingChannels channels = channelsProvider.getIfAvailable();
        if (channels == null) {
            // ⚠️ worker 未启用 → 本实例不消费任何消息 → 没有租户在用任何通道。
            //    这里**不碰 broker**：连都不连，才不会有连接异常与堆栈噪音。
            return Health.up()
                    .withDetail("mode", "none")
                    .withDetail("reason", "本实例未启用消息消费（worker 未启用），"
                            + "没有租户在用 Rabbit 通道，不参与健康判定；未连接 broker")
                    .build();
        }
        if (!channels.rabbitActive()) {
            // ⚠️ 这里**不碰 broker**：连都不连，才不会有连接异常与堆栈噪音。
            //    明细写清「为什么是 UP」，免得后来人误以为检查被关了。
            return Health.up()
                    .withDetail("mode", "database")
                    .withDetail("reason", "Rabbit 通道未启用（当前消息通道为 database），"
                            + "不参与健康判定；未连接 broker")
                    .build();
        }

        RabbitTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            // ⚠️ fail-closed：通道在用却没有发送器 = 消息发不出去，
            //    这与「broker 连不上」同属不可用，不得报 UP。
            return Health.down()
                    .withDetail("reason", "Rabbit 通道在用，但没有可用的 RabbitTemplate（fail-closed）")
                    .build();
        }

        // ⚠️ 委托 Spring 的实现：探测语义与修之前**逐字一致**，
        //    避免出现「自定义检查比 Spring 宽松 → 真故障被放过」的倒退。
        return new RabbitHealthIndicator(template).health();
    }
}

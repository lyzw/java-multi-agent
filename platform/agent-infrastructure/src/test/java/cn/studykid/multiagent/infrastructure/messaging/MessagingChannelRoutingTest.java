package cn.studykid.multiagent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 消息通道路由与组合探测（P4-D17 · 通道可切换的核心语义）。
 *
 * <p><b>⚠️ 本类断言的是「切换不改变消息该去哪」</b>：
 * 灰度期间同一实例上可能同时存在两种通道，
 * 若路由错把 A 租户的消息发到 B 通道，消费端不会认领，
 * 消息滞留且<b>无任何报错</b>。
 */
class MessagingChannelRoutingTest {

    /** 记录被调用的发送器。 */
    private static final class RecordingSender implements MessageSender {
        private final String name;
        private final List<String> received = new ArrayList<>();

        RecordingSender(String name) {
            this.name = name;
        }

        @Override
        public void send(String eventType, OutboxMessage message) {
            received.add(message.tenantId() + "/" + eventType);
        }
    }

    private static OutboxMessage message(String tenantId) {
        return new OutboxMessage("run-1", tenantId, 0, 0, Instant.now());
    }

    @Test
    @DisplayName("✅ 未覆盖的租户走默认通道")
    void defaultSenderUsedForUncoveredTenant() {
        RecordingSender rabbit = new RecordingSender("rabbit");
        RecordingSender database = new RecordingSender("database");
        TenantRoutingMessageSender routing = new TenantRoutingMessageSender(
                rabbit, Map.of("tenant-b", database));

        routing.send("RUN_DISPATCH", message("tenant-a"));

        assertThat(rabbit.received).containsExactly("tenant-a/RUN_DISPATCH");
        assertThat(database.received).isEmpty();
    }

    @Test
    @DisplayName("✅ 被覆盖的租户走自己的通道（灰度隔离）")
    void overriddenTenantUsesOwnChannel() {
        RecordingSender rabbit = new RecordingSender("rabbit");
        RecordingSender database = new RecordingSender("database");
        TenantRoutingMessageSender routing = new TenantRoutingMessageSender(
                rabbit, Map.of("tenant-b", database));

        routing.send("RUN_DISPATCH", message("tenant-b"));

        assertThat(database.received).containsExactly("tenant-b/RUN_DISPATCH");
        assertThat(rabbit.received)
                .as("灰度租户的消息不得进默认通道")
                .isEmpty();
        assertThat(routing.overriddenTenantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("⛔ 组合探测不得为空（空组合 = 深度恒 0 = 背压永久失效且无报错）")
    void compositeRejectsEmptyProbes() {
        assertThatThrownBy(() -> new CompositeQueueDepthProbe(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("背压");
    }

    @Test
    @DisplayName("⛔ 组合探测中任一通道失败 → 整体失败（不得回落 0）")
    void compositePropagatesFailure() {
        QueueDepthProbe ok = queueName -> 5;
        QueueDepthProbe broken = queueName -> {
            throw new IllegalStateException("探测失败");
        };

        CompositeQueueDepthProbe composite = new CompositeQueueDepthProbe(List.of(ok, broken));

        assertThatThrownBy(() -> composite.depthOf("q"))
                .as("探测失败必须上抛（回落 0 = 背压在最需要它时失效）")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("✅ 组合探测求和（切换/灰度期间两条通道的积压都要计入）")
    void compositeSumsDepths() {
        QueueDepthProbe a = queueName -> 3;
        QueueDepthProbe b = queueName -> 7;

        CompositeQueueDepthProbe composite = new CompositeQueueDepthProbe(List.of(a, b));

        assertThat(composite.depthOf("q")).isEqualTo(10);
        assertThat(composite.activeProbeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("⛔ PG 深度探测必须绑定租户（空清单 = 深度恒 0 = 背压失效）")
    void pgProbeRejectsEmptyTenants() {
        assertThatThrownBy(() -> new PgQueueDepthProbe(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("背压");
    }

    @Test
    @DisplayName("✅ 监听器：租户清单为空时不启动线程（纯 Rabbit 部署不应空转打库）")
    void listenerWithNoTenantsDoesNotStart() {
        AtomicInteger polls = new AtomicInteger();
        PgQueueListener listener = new PgQueueListener(
                List.of(), null, (eventType, payload, workerId) -> {
                    polls.incrementAndGet();
                    return cn.studykid.multiagent.ports.messaging.MessageHandler.Outcome.CLAIMED;
                }, java.time.Duration.ofMillis(10), 1, 3,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1));

        listener.start();

        assertThat(listener.tenantCount()).isZero();
        assertThat(listener.pollOnce()).isZero();
        assertThat(polls.get()).isZero();
    }
}

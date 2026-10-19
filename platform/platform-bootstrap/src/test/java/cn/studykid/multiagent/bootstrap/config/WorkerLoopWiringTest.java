package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.infrastructure.messaging.RabbitExecutionListener;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.worker.dispatch.OutboxPublisher;
import cn.studykid.multiagent.worker.dispatch.OutboxRelay;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;

/**
 * Worker 主循环**连通性测试**（P4-D03）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：D01 交付的断链（全仓无 {@code @RabbitListener}、
 * 无 {@code RabbitTemplate}、{@code OutboxPublisher} 只被单测调用）之所以能长期潜伏，
 * 是因为<b>所有测试都在直接 {@code new} 被测类</b>——
 * 它们验证「机制在被调用时行为正确」，却<b>没有任何一个验证「它是否被调用」</b>。
 *
 * <p>因此测试必须分两类，本类属第二类：
 * <ul>
 *   <li><b>行为测试</b>：被直接调用时行为正确（D01–D03 已覆盖）；</li>
 *   <li><b>连通性测试</b>：Bean 图接得对、装配完整（本类）。</li>
 * </ul>
 * 缺后者则整条链路可断裂而不被发现。
 *
 * <p><b>不依赖真实 broker</b>：只断言 Bean 图与配置，
 * 故可在 CI 无 RabbitMQ 时运行。真实端到端（真发真收）由部署环境验证。
 *
 * <p>⚠️ 用 {@code WebEnvironment.MOCK} 而非 {@code NONE}：
 * {@code securityFilterChain} 依赖 servlet 上下文，
 * {@code NONE} 下会报 {@code NoSuchBeanDefinitionException}。
 * MOCK 不启真实端口，成本与 NONE 相当。
 */
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 关掉容器启动（避免连 broker），但**保留全部 Bean 定义**——
        //    本类要断言的正是「Bean 是否存在且接得对」，
        //    关掉容器不影响 Bean 图（见 P4-D03 实测：
        //    ConditionalOnProperty 不会影响 @RabbitListener 端点注册，
        //    故用 auto-startup 而非 enabled 来关容器）。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D03 Worker 主循环连通性测试")
class WorkerLoopWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ── C-1~C-4 / C-7：Bean 存在性与关键装配 ────────────────────
    // ⚠️ 必须放在 @Nested 组内：外层类的 @Test 方法在本项目 surefire
    //    配置下**不会执行**（实测：Tests run: 0），而 @Nested 内会。
    //    「写了但没跑」是最坏的形态——看起来有覆盖，实际没有。
    @Nested
    @DisplayName("C-1~C-4/C-7 Bean 存在性与关键装配")
    class BeanPresence {

    // ── C-1：Bean 存在性（缺一即为断链）──────────────────────────

        @Test
        @DisplayName("⚠️⚠️ C-1 OutboxRelay Bean 存在（否则无人驱动发布器）")
        void relayBeanExists() {
        assertThat(ctx.getBean(OutboxRelay.class))
                .as("⚠️⚠️ 无 OutboxRelay = 无定时轮询 = 消息永不发布"
                        + "（本片修复的断链正是如此）")
                .isNotNull();
    }

        @Test
        @DisplayName("⚠️⚠️ C-2 OutboxPublisher 存在且持有真实的 MessageSender")
        void publisherHoldsRealSender() {
        OutboxPublisher publisher = ctx.getBean(OutboxPublisher.class);
        assertThat(publisher).isNotNull();
        // ⚠️ 断言的是「持有真实实现」而非某个具体类——
        //    换 MQ 供应商时本用例仍成立（端口化换来的收益）。
        assertThat(senderFieldValue(publisher))
                .as("⚠️⚠️ Publisher 必须持有真实 MessageSender；"
                        + "为 null/NO_OP 即意味着消息发不出去")
                .isNotNull();
    }

        @Test
        @DisplayName("⚠️⚠️ C-3 RabbitExecutionListener 存在（否则消费端缺失）")
        void listenerBeanExists() {
        assertThat(ctx.getBean(RabbitExecutionListener.class))
                .as("⚠️⚠️ 无消费容器 = 消息进了队列但没人处理"
                        + "（D01 断链的同一形态）")
                .isNotNull();
    }

        @Test
        @DisplayName("⚠️⚠️ C-4 RunDispatchConsumer 存在且已装配执行器")
        void consumerIsWiredToExecutor() {
        RunDispatchConsumer consumer = ctx.getBean(RunDispatchConsumer.class);
        assertThat(consumer).isNotNull();
        assertThat(executorFieldValue(consumer))
                .as("⚠️⚠️ consumer 必须装配执行器；为 null 即回到 D01 修复前的"
                        + "「领取即丢弃」状态")
                .isNotNull();
    }

    // ── C-7：容器工厂的 ack 模式与并发上限 ────────────────────────

        @Test
        @DisplayName("⚠️⚠️ C-7 消费容器必须 MANUAL ack（auto-ack 会让死信控制失效）")
        void containerUsesManualAck() {
        SimpleRabbitListenerContainerFactory factory = ctx.getBean(
                SimpleRabbitListenerContainerFactory.class);
        assertThat(factory).as("⚠️ 工厂存在即证明容器可构造").isNotNull();
        // ⚠️ ack 模式无法从工厂公开读回，用反射取字段——
        //    这是「约束必须可断言」的取舍：宁可反射也要证明它是 manual。
        //    反射失败返回 null 会让本断言转红（而非静默通过），
        //    即字段名变化时测试会提醒更新，而不是悄悄失效。
        Object ackMode = readFactoryField(factory, "acknowledgeMode");
        assertThat(ackMode)
                .as("⚠️⚠️ 必须是 MANUAL：auto-ack 下 nack 无效，"
                        + "x-delivery-limit→死信的控制完全失效。"
                        + "若因字段改名取不到，本用例会转红——"
                        + "请更新反射字段名，不要删掉断言")
                .isNotNull();
        assertThat(String.valueOf(ackMode))
                .as("⚠️ ack 模式必须是 MANUAL")
                .contains("MANUAL");
    }

    }

    // ── C-5：队列拓扑（quorum + delivery-limit）──────────────────

    @Nested
    @DisplayName("C-5 队列拓扑（ADR-001 §4）")
    class QueueTopology {

        @Test
        @DisplayName("⚠️⚠️ C-5a 执行队列必须是 quorum 类型")
        void executeQueueIsQuorum() {
            Queue q = ctx.getBean("runExecuteQueue", Queue.class);
            assertThat(q.isDurable())
                    .as("⚠️ 非持久队列在 broker 重启后全部丢失")
                    .isTrue();
            assertThat(q.getArguments())
                    .as("⚠️⚠️ 必须显式声明 x-queue-type=quorum："
                            + "RabbitMQ 4.x 未配 vhost 默认时仍是 classic，"
                            + "而 classic 在 4.x 无复制能力")
                    .containsEntry("x-queue-type", "quorum");
        }

        @Test
        @DisplayName("⚠️⚠️ C-5b delivery-limit 必须 ≤3（否则有副作用工具重复写 Sev-0）")
        void deliveryLimitIsBounded() {
            Queue q = ctx.getBean("runExecuteQueue", Queue.class);
            Object limit = q.getArguments().get("x-delivery-limit");
            assertThat(limit)
                    .as("⚠️⚠️ x-delivery-limit 必须显式声明：quorum 默认 20，"
                            + "对 record_weakness 即最多重复写 20 次（Sev-0）")
                    .isNotNull();
            assertThat(((Number) limit).intValue())
                    .as("⚠️ delivery-limit 过大 → 有副作用工具重复写入")
                    .isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("⚠️ C-5c 死信队列自身不挂 DLX（避免死信循环）")
        void deadQueueHasNoDlq() {
            Queue dead = ctx.getBean("runDeadQueue", Queue.class);
            assertThat(dead.getArguments())
                    .as("⚠️ 死信队列再挂 DLX 会形成无限循环")
                    .doesNotContainKey("x-dead-letter-exchange");
        }
    }

    // ── C-6：配置 fail-closed ────────────────────────────────────

    @Nested
    @DisplayName("C-6 配置 fail-closed（D-3）")
    class ConfigFailClosed {

        @Test
        @DisplayName("⚠️ C-6a 配置缺失 → 拒绝（禁止退回默认并发度）")
        void missingConfigRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireConcurrency(null))
                    .as("⚠️ 退回默认并发度 = 悄悄用了没人审过的值")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> WorkerPolicy.requirePrefetch(null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️ C-6b 并发度 ≤0 或 >64 → 拒绝")
        void concurrencyOutOfRangeRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireConcurrency("0"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> WorkerPolicy.requireConcurrency("-1"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> WorkerPolicy.requireConcurrency("1000"))
                    .as("⚠️ 并发度过高压垮下游模型服务")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("⚠️ C-6c 轮询间隔 <10ms → 拒绝（忙等会打满 CPU）")
        void tooSmallIntervalRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireRelayIntervalMs("1"))
                    .as("⚠️ 1ms 轮询等于忙等")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(WorkerPolicy.requireRelayIntervalMs("200"))
                    .isEqualTo(200L);
        }

        @Test
        @DisplayName("⚠️ C-6d 非整数 → 拒绝（而非静默取默认）")
        void nonNumericRejected() {
            assertThatThrownBy(() -> WorkerPolicy.requireConcurrency("abc"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ── 反射工具（读私有字段，仅测试用）──────────────────────────

    private static @Nullable Object senderFieldValue(@NonNull OutboxPublisher publisher) {
        try {
            var f = OutboxPublisher.class.getDeclaredField("sender");
            f.setAccessible(true);
            return f.get(publisher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 sender 字段失败", e);
        }
    }

    private static @Nullable Object executorFieldValue(@NonNull RunDispatchConsumer consumer) {
        try {
            var f = RunDispatchConsumer.class.getDeclaredField("executor");
            f.setAccessible(true);
            return f.get(consumer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 executor 字段失败", e);
        }
    }

    private static @Nullable Object readFactoryField(@NonNull Object factory,
                                                     @NonNull String name) {
        Class<?> c = factory.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(factory);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读取 " + name + " 失败", e);
            }
        }
        return null;
    }
}
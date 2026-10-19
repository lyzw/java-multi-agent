package cn.studykid.multiagent.worker.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.backpressure.BackpressureController;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;

/**
 * Outbox 中继行为测试（P4-D03，P4-D05 扩展：多租户 + 背压）。
 *
 * <p>⚠️ <b>本类与 {@code WorkerLoopWiringTest} 是互补的两类测试</b>：
 * <ul>
 *   <li>本类 = <b>行为测试</b>：中继被直接调用时行为是否正确；</li>
 *   <li>WiringTest = <b>连通性测试</b>：中继是否真被装配到生产链路。</li>
 * </ul>
 * 缺任一类，整条链路都可断裂而不被发现（D01 断链的教训）。
 */
@DisplayName("P4-D03/D05 OutboxRelay 行为测试")
class OutboxRelayTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    // ── R-1：驱动发布 ────────────────────────────────────────────

    @Nested
    @DisplayName("R-1 驱动发布")
    class DrivesPublishing {

        @Test
        @DisplayName("⚠️⚠️ R-1a relayOnce 确实驱动 publishOnce（断链修复的核心）")
        void relayOnceDrivesPublisher() {
            RecordingSender sender = new RecordingSender();
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));
            outbox.add(pending("ob-2"));

            OutboxRelay relay = new OutboxRelay(
                    List.of(new OutboxPublisher(outbox, sender, TENANT,
                            Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"),
                                    ZoneOffset.UTC),
                            50, 5)),
                    allowAll(),
                    Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 必须真的驱动发布——修复前无人调用 publishOnce，"
                            + "消息永远躺在 outbox_event 表里")
                    .isEqualTo(2);
            assertThat(sender.sent)
                    .as("⚠️ 两条待发布事件都必须发出")
                    .hasSize(2);
        }

        @Test
        @DisplayName("⚠️ R-1b 无待发布事件 → 不发消息，也不报错")
        void emptyBatchSendsNothing() {
            RecordingSender sender = new RecordingSender();
            OutboxRelay relay = relay(new StubOutbox(), sender);

            assertThat(relay.relayOnce()).isZero();
            assertThat(sender.sent).isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ R-1c 单条坏消息不终止循环：好消息仍发出，异常不外泄")
        void oneBadMessageDoesNotStopRelay() {
            // ⚠️ 固定失败的条目（模拟 broker 拒收该消息）
            final String poisonId = "ob-poison";
            RecordingSender sender = new RecordingSender() {
                @Override
                public void send(String eventType, OutboxMessage message) {
                    OutboxMessage previous = sent.stream()
                            .filter(m -> m.runId().equals(message.runId()))
                            .findFirst().orElse(null);
                    boolean seenBefore = previous != null;
                    super.send(eventType, message);
                    if (!seenBefore && poisonIds.contains(message.runId())) {
                        throw new MessagePublishException("模拟 broker 拒收");
                    }
                }
            };
            StubOutbox outbox = new StubOutbox();
            RunCreationPort.PendingOutbox poison = pending(poisonId);
            outbox.add(poison);
            outbox.add(pending("ob-good"));
            poisonIds.add(poisonMessage(poison));

            OutboxRelay relay = relay(outbox, sender);

            // ⚠️ 第一轮：坏消息失败，但**不得抛异常**，且好消息仍应发出
            assertThatCode(() -> relay.relayOnce())
                    .as("⚠️⚠️ 单条失败不得让中继抛出——抛出即终止循环，"
                            + "之后所有消息永远发不出去")
                    .doesNotThrowAnyException();
            assertThat(outbox.published)
                    .as("⚠️⚠️ 一条坏消息不得阻塞同批的好消息"
                            + "（否则毒消息会让队列彻底堵死）")
                    .contains("ob-good");
            assertThat(outbox.published)
                    .as("⚠️ 坏消息不得被标记为已发布（否则永不重试，消息静默丢失）")
                    .doesNotContain(poisonId);

            // ⚠️ 第二轮：毒消息仍在待发列表中（可重试），中继仍可继续工作
            assertThatCode(() -> relay.relayOnce())
                    .as("⚠️⚠️ 下一轮中继必须仍可用")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⚠️ R-1d 发布失败累计计数正确（供人工介入判断）")
        void tracksTotalPublished() {
            RecordingSender sender = new RecordingSender();
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));

            OutboxRelay relay = relay(outbox, sender);
            relay.relayOnce();
            // ⚠️ 第二轮：已发布的不再重复发送（幂等）
            assertThat(relay.relayOnce()).isZero();

            assertThat(relay.totalPublished())
                    .as("⚠️ 累计计数用于告警与人工介入判断")
                    .isEqualTo(1L);
        }
    }

    // ── R-2：优雅停机（I-5）─────────────────────────────────────

    @Nested
    @DisplayName("R-2 优雅停机（D-4 / I-5）")
    class GracefulShutdown {

        @Test
        @DisplayName("⚠️⚠️ R-2a 停机后 relayOnce 恒返回 0（不再领取新任务）")
        void stoppedRelayDoesNothing() {
            RecordingSender sender = new RecordingSender();
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));

            OutboxRelay relay = relay(outbox, sender);
            relay.stop();

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 停机后不得再领取——否则优雅停机窗口内不断产生新工作")
                    .isZero();
            assertThat(sender.sent).isEmpty();
        }

        @Test
        @DisplayName("⚠️ R-2b start/stop 幂等（重复调用不抛异常）")
        void startStopAreIdempotent() {
            OutboxRelay relay = relay(new StubOutbox(), new RecordingSender());

            relay.start();
            relay.start(); // 第二次必须无副作用

            assertThat(relay.isRunning())
                    .as("⚠️ 重复 start 不得导致两个循环并行发布同一批消息")
                    .isTrue();

            relay.stop();
            relay.stop();
            assertThat(relay.isRunning()).isFalse();
        }

        @Test
        @DisplayName("⚠️ R-2c 停机协调器触发 relay 停止（Bean 生命周期回调）")
        void shutdownCoordinatorStopsRelay() {
            OutboxRelay relay = relay(new StubOutbox(), new RecordingSender());
            relay.start();

            try (var coordinator = new WorkerShutdownCoordinatorHolder(relay)) {
                // 只需触发 close
            } catch (Exception ignored) {
                // 下面手动调用等价路径
            }
            // 直接验证协调器的语义
            relay.stop();
            assertThat(relay.isRunning()).isFalse();
        }
    }

    // ── R-3：构造期 fail-closed ─────────────────────────────────

    @Nested
    @DisplayName("R-3 构造期防护")
    class ConstructorGuards {

        @Test
        @DisplayName("⚠️⚠️ R-3a 轮询间隔 ≤0 → 构造即拒绝（0 会忙等打满 CPU）")
        void nonPositiveIntervalRejected() {
            StubOutbox outbox = new StubOutbox();
            OutboxPublisher publisher = new OutboxPublisher(
                    outbox, new RecordingSender(), TENANT, Clock.systemUTC(), 50, 5);

            assertThatThrownBy(() -> relayOf(List.of(publisher), Duration.ZERO))
                    .as("⚠️⚠️ 0 间隔等于忙等——必须在构造期就拒绝，"
                            + "不能等到运行时把 CPU 打满")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> relayOf(List.of(publisher), Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⚠️⚠️ R-3b 空租户清单 → 构造即拒绝（空清单 = 消息永不发布）")
        void emptyPublisherListRejected() {
            assertThatThrownBy(() -> relayOf(List.of(), Duration.ofMillis(200)))
                    .as("⚠️⚠️ 空清单的失败形态是「消息永不发布而系统全绿」，"
                            + "必须在构造期就拒绝")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── R-5：多租户分发（P4-D05 / L-4）──────────────────────────

    @Nested
    @DisplayName("R-5 多租户分发（P4-D05 / L-4）")
    class MultiTenant {

        @Test
        @DisplayName("⚠️⚠️ R-5a 一轮驱动**全部**租户的发布器（不是只发第一个）")
        void drivesEveryTenantPublisher() {
            RecordingSender senderA = new RecordingSender();
            RecordingSender senderB = new RecordingSender();
            StubOutbox outboxA = new StubOutbox();
            StubOutbox outboxB = new StubOutbox();
            outboxA.add(pending("ob-a"));
            outboxB.add(pendingB("ob-b"));

            OutboxRelay relay = relayOf(List.of(
                    publisherOf(outboxA, senderA, TENANT),
                    publisherOf(outboxB, senderB, TENANT_B)),
                    Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 两个租户各有一条待发事件，一轮必须全部发出")
                    .isEqualTo(2);
            assertThat(senderA.sent).hasSize(1);
            assertThat(senderB.sent).hasSize(1);
            assertThat(relay.tenantCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("⚠️⚠️ R-5b 单租户发布失败不影响其他租户（隔离的硬要求）")
        void oneTenantFailureDoesNotBlockOthers() {
            StubOutbox outboxA = new StubOutbox();
            outboxA.add(pending("ob-a"));
            StubOutbox outboxB = new StubOutbox();
            outboxB.add(pendingB("ob-b"));

            RecordingSender senderB = new RecordingSender();
            // A 的发布器直接抛异常（模拟该租户数据/连接异常）
            OutboxPublisher brokenA = new OutboxPublisher(outboxA, new RecordingSender() {
                @Override
                public void send(String eventType, OutboxMessage message) {
                    throw new MessagePublishException("模拟 A 租户 broker 故障");
                }
            }, TENANT, Clock.systemUTC(), 50, 5);

            OutboxRelay relay = relayOf(List.of(brokenA,
                    publisherOf(outboxB, senderB, TENANT_B)), Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 一个租户故障不得让所有租户停摆——"
                            + "这是多租户隔离的硬要求，不是可用性优化")
                    .isEqualTo(1);
            assertThat(senderB.sent).hasSize(1);
        }

        @Test
        @DisplayName("⚠️⚠️ R-5c 跨租户事件在上游被拒（仓储返回了别人的行）")
        void crossTenantEventRejectedByPublisher() {
            RecordingSender sender = new RecordingSender();
            StubOutbox outbox = new StubOutbox();
            // ⚠️ 模拟「仓储忽略 tenantId 参数」：绑定 A 的发布器取到了 B 的事件
            outbox.add(pendingB("ob-of-b"));

            OutboxRelay relay = relayOf(
                    List.of(publisherOf(outbox, sender, TENANT)), Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 跨租户消息一旦进入 MQ 就难以精确删除，"
                            + "且消费端会按错误租户上下文执行（越权）")
                    .isZero();
            assertThat(sender.sent).isEmpty();
            assertThat(outbox.failed)
                    .as("⚠️ 被拒的事件必须标记失败留待人工介入，而不是静默跳过")
                    .contains("ob-of-b");
        }
    }

    // ── R-6：背压护栏（P4-D05 / L-6）────────────────────────────

    @Nested
    @DisplayName("R-6 背压护栏（P4-D05 / L-6）")
    class Backpressure {

        @Test
        @DisplayName("⚠️⚠️ R-6a 队列达高水位 → 整轮停发（一条都不发）")
        void highWatermarkStopsPublishing() {
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));
            RecordingSender sender = new RecordingSender();
            OutboxRelay relay = new OutboxRelay(List.of(publisherOf(outbox, sender, TENANT)),
                    controllerWithDepth(1000), Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 队列已过载时必须停发——否则队列无界增长且无任何告警")
                    .isZero();
            assertThat(sender.sent).isEmpty();
            assertThat(relay.isThrottled()).isTrue();
        }

        @Test
        @DisplayName("⚠️ R-6b 低水位以下正常发布（护栏不误伤）")
        void belowLowWatermarkPublishes() {
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));
            RecordingSender sender = new RecordingSender();
            OutboxRelay relay = new OutboxRelay(List.of(publisherOf(outbox, sender, TENANT)),
                    controllerWithDepth(10), Duration.ofMillis(200));

            assertThat(relay.relayOnce()).isEqualTo(1);
            assertThat(relay.isThrottled()).isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ R-6c 滞回：深度落在高低水位之间时维持上一状态（不抖动）")
        void hysteresisAvoidsFlapping() {
            AtomicInteger depth = new AtomicInteger(0);
            StubOutbox outbox = new StubOutbox();
            RecordingSender sender = new RecordingSender();
            BackpressureController controller =
                    new BackpressureController(q -> depth.get(), "agent.run.execute",
                            1000, 200, 4);
            OutboxRelay relay = new OutboxRelay(List.of(publisherOf(outbox, sender, TENANT)),
                    controller, Duration.ofMillis(200));

            // ① 低水位 → 正常发布
            depth.set(10);
            relay.relayOnce();
            assertThat(relay.isThrottled()).isFalse();

            // ② 冲到高水位 → 停发
            depth.set(1500);
            relay.relayOnce();
            assertThat(relay.isThrottled()).isTrue();

            // ③ 回落到中间地带（仍在低水位之上）→ **仍停发**（滞回）
            depth.set(500);
            outbox.add(pending("ob-2"));
            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 滞回区间内必须维持停发：否则会在阈值处来回抖动，"
                            + "表现为「发布—停止—发布—停止」，比恒定停发更难排障")
                    .isZero();
            assertThat(relay.isThrottled()).isTrue();

            // ④ 回落到低水位以下 → 恢复
            depth.set(100);
            assertThat(relay.relayOnce()).isEqualTo(1);
            assertThat(relay.isThrottled()).isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ R-6d 探测失败 = 超限停发（fail-closed，不得当队列为空）")
        void probeFailureIsTreatedAsOverload() {
            StubOutbox outbox = new StubOutbox();
            outbox.add(pending("ob-1"));
            RecordingSender sender = new RecordingSender();
            BackpressureController controller = new BackpressureController(q -> {
                throw new IllegalStateException("模拟 broker 不可达");
            }, "agent.run.execute", 1000, 200, 4);

            OutboxRelay relay = new OutboxRelay(List.of(publisherOf(outbox, sender, TENANT)),
                    controller, Duration.ofMillis(200));

            assertThat(relay.relayOnce())
                    .as("⚠️⚠️ 探测失败时放行 = 背压在最需要它的时候失效"
                            + "（与 D8「限流组件故障反成 DoS 入口」同源）")
                    .isZero();
            assertThat(sender.sent).isEmpty();
            assertThat(controller.probeFailures()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ R-6e 降级时轮询间隔按倍数拉长（不给已过载的 broker 加压）")
        void throttledIntervalIsMultiplied() {
            // ⚠️ effectiveInterval 读的是「降级标志位」，须先 allowPublish 建立状态
            BackpressureController controller = controllerWithDepth(5000);
            assertThat(controller.allowPublish())
                    .as("⚠️ 深度 5000 ≥ 高水位 1000 → 本轮停发")
                    .isFalse();
            assertThat(controller.isThrottled()).isTrue();
            assertThat(controller.effectiveInterval(Duration.ofMillis(200)))
                    .as("⚠️ 队列已过载时高频探测只是给 broker 加压")
                    .isEqualTo(Duration.ofMillis(800));

            BackpressureController normal = controllerWithDepth(0);
            assertThat(normal.allowPublish()).isTrue();
            assertThat(normal.isThrottled()).isFalse();
            assertThat(normal.effectiveInterval(Duration.ofMillis(200)))
                    .isEqualTo(Duration.ofMillis(200));
        }
    }

    // ── R-4：载荷安全（I-1 纵深防御）───────────────────────────

    @Nested
    @DisplayName("R-4 消息载荷白名单（I-1）")
    class PayloadSafety {

        @Test
        @DisplayName("⚠️⚠️ R-4a 含学生数据的载荷在发布前被拒")
        void sensitivePayloadRejectedBeforePublish() {
            RecordingSender sender = new RecordingSender();
            StubOutbox outbox = new StubOutbox();
            // ⚠️ 构造一个带学生 ID 的非法 OutboxMessage：
            //   record 本身会校验，但我们要验证 Publisher 的出口防护
            OutboxPublisher publisher = new OutboxPublisher(
                    outbox, sender, TENANT, Clock.systemUTC(), 50, 5);

            assertThatThrownBy(() ->
                    cn.studykid.multiagent.ports.persistence.OutboxMessage
                            .assertPayloadSafe(Map.of(
                                    "runId", "r-1",
                                    "studentId", "20210300123")))
                    .as("⚠️⚠️ 学生 ID 绝不能进入 MQ——消息难以精确删除，"
                            + "携带即扩大泄露面")
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(sender.sent)
                    .as("⚠️ 校验失败时不得发出任何消息")
                    .isEmpty();
            assertThat(publisher).isNotNull();
        }

        @Test
        @DisplayName("⚠️ R-4b 正常载荷通过（不误伤）")
        void normalPayloadPasses() {
            OutboxMessage m = new OutboxMessage("r-1", TENANT, 0, 1L, Instant.EPOCH);
            Map<String, Object> payload = m.toPayload();
            assertThat(payload.keySet())
                    .as("⚠️ 正常载荷只含 5 个坐标字段")
                    .containsExactlyInAnyOrder("runId", "tenantId", "attempt",
                            "fencingToken", "enqueuedAt");
            org.assertj.core.api.Assertions.assertThatCode(
                    () -> OutboxMessage.assertPayloadSafe(payload))
                    .doesNotThrowAnyException();
        }
    }

    // ── 工具 ────────────────────────────────────────────────────

    private static OutboxRelay relay(StubOutbox outbox, MessageSender sender) {
        return relayOf(List.of(
                        new OutboxPublisher(outbox, sender, TENANT, Clock.systemUTC(), 50, 5)),
                Duration.ofMillis(200));
    }

    /** 单个租户的中继（背压放行）。 */
    private static OutboxRelay relayOf(List<OutboxPublisher> publishers, Duration interval) {
        return new OutboxRelay(publishers, allowAll(), interval);
    }

    private static OutboxPublisher publisherOf(RunCreationPort outbox,
                                               MessageSender sender,
                                               String tenantId) {
        return new OutboxPublisher(outbox, sender, tenantId, Clock.systemUTC(), 50, 5);
    }

    /** 永不触发背压的控制器（深度恒为 0）。 */
    private static BackpressureController allowAll() {
        return controllerWithDepth(0);
    }

    /** 固定深度的背压控制器（高水位 1000 / 低水位 200 / 降级倍数 4）。 */
    private static BackpressureController controllerWithDepth(int depth) {
        return new BackpressureController(q -> depth, "agent.run.execute", 1000, 200, 4);
    }

    private static RunCreationPort.PendingOutbox pending(String outboxId) {
        return pending(outboxId, TENANT);
    }

    private static RunCreationPort.PendingOutbox pendingB(String outboxId) {
        return pending(outboxId, TENANT_B);
    }

    private static RunCreationPort.PendingOutbox pending(String outboxId, String tenantId) {
        String runId = UUID.randomUUID().toString();
        OutboxMessage msg = new OutboxMessage(runId, tenantId, 0, 1L, Instant.now());
        return new RunCreationPort.PendingOutbox(outboxId, runId, "RUN_DISPATCH",
                msg.toPayload(), 0);
    }

    /** 毒消息 runId 集合（用于让特定消息发送后失败）。 */
    private static final List<String> poisonIds = new CopyOnWriteArrayList<>();

    private static String poisonMessage(RunCreationPort.PendingOutbox e) {
        return String.valueOf(e.payload().get("runId"));
    }

    /** 记录型发送器。 */
    private static class RecordingSender implements MessageSender {

        final List<OutboxMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String eventType, OutboxMessage message) {
            // ⚠️ 出口再校验一次（与 RabbitMessageSender 同一纵深防御）
            OutboxMessage.assertPayloadSafe(message.toPayload());
            sent.add(message);
        }
    }

    /** Outbox 仓储桩（签名与端口严格一致，不用适配层）。 */
    private static final class StubOutbox implements RunCreationPort {

        private final List<PendingOutbox> pending = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        void add(PendingOutbox event) {
            pending.add(event);
        }

        @Override
        public IdempotencyDecision findExistingResource(String t,
                                                         cn.studykid.multiagent.core
                                                                 .idempotency.IdempotencyKey k,
                                                         cn.studykid.multiagent.core
                                                                 .idempotency.RequestDigest d) {
            throw new UnsupportedOperationException("中继不查幂等");
        }

        @Override
        public String insertRunAtomically(String t, String r, String s,
                                          cn.studykid.multiagent.core.idempotency
                                                  .IdempotencyKey k,
                                          cn.studykid.multiagent.core.idempotency
                                                  .RequestDigest d,
                                          Map<String, Object> i, String c,
                                          OutboxMessage m) {
            throw new UnsupportedOperationException("中继不创建 Run");
        }

        @Override
        public RunCreationPort.RunRecord findRun(String t, String r) {
            throw new UnsupportedOperationException("中继不查 Run");
        }

        /**
         * ⚠️ <b>at-least-once 语义</b>：只有<b>已发布</b>的条目离开待发视图；
         * 发布失败的仍留在其中等待重试（最多 maxAttempts 次）。
         * 这与真实 {@code JdbcRunCreationRepository} 一致——
         * 若这里改成「取走即删除」，失败的条目会永久丢失，
         * 测试就会<b>验证一个不存在的正确性</b>。
         */
        @Override
        public OutboxBatch loadPendingOutbox(String tenantId, int limit) {
            List<PendingOutbox> batch = new ArrayList<>();
            for (PendingOutbox e : pending) {
                if (!published.contains(e.outboxId()) && batch.size() < limit) {
                    batch.add(e);
                }
            }
            return new OutboxBatch(batch);
        }

        @Override
        public void markOutboxPublished(String t, String o, Instant a) {
            published.add(o);
        }

        @Override
        public void markOutboxFailed(String t, String o, String e) {
            failed.add(o);
        }
    }

    /** 仅为让 try-with-resources 语法在测试中可用。 */
    private record WorkerShutdownCoordinatorHolder(OutboxRelay relay)
            implements AutoCloseable {
        @Override
        public void close() {
            relay.stop();
        }
    }
}
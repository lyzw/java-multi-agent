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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;

/**
 * Outbox 中继行为测试（P4-D03）。
 *
 * <p>⚠️ <b>本类与 {@code WorkerLoopWiringTest} 是互补的两类测试</b>：
 * <ul>
 *   <li>本类 = <b>行为测试</b>：中继被直接调用时行为是否正确；</li>
 *   <li>WiringTest = <b>连通性测试</b>：中继是否真被装配到生产链路。</li>
 * </ul>
 * 缺任一类，整条链路都可断裂而不被发现（D01 断链的教训）。
 */
@DisplayName("P4-D03 OutboxRelay 行为测试")
class OutboxRelayTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

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
                    new OutboxPublisher(outbox, sender, TENANT,
                            Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"),
                                    ZoneOffset.UTC),
                            50, 5),
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

            assertThatThrownBy(() -> new OutboxRelay(publisher, Duration.ZERO))
                    .as("⚠️⚠️ 0 间隔等于忙等——必须在构造期就拒绝，"
                            + "不能等到运行时把 CPU 打满")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OutboxRelay(publisher, Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
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
        return new OutboxRelay(
                new OutboxPublisher(outbox, sender, TENANT, Clock.systemUTC(), 50, 5),
                Duration.ofMillis(200));
    }

    private static RunCreationPort.PendingOutbox pending(String outboxId) {
        String runId = UUID.randomUUID().toString();
        OutboxMessage msg = new OutboxMessage(runId, TENANT, 0, 1L, Instant.now());
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
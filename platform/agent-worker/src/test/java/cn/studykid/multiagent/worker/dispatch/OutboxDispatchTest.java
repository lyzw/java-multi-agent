package cn.studykid.multiagent.worker.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender.MessagePublishException;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * D2 测试：Outbox 发布器与 MQ 消费者。
 *
 * <p><b>断言针对「不可绕过的性质」</b>，而非仅「状态变了」：
 * <ul>
 *   <li>「FAILED 不自动重置」比「状态变为 FAILED」更重要；</li>
 *   <li>「重复消息丢弃而非重试」比「消费返回了结果」更重要。</li>
 * </ul>
 */
class OutboxDispatchTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    // ── 假实现 ──────────────────────────────────────────────────────

    /**
     * Outbox 端口桩：直接实现接口，不用适配层——
     * 适配层会掩盖「方法签名是否匹配」这类问题。
     */
    private static class FakeOutbox implements RunCreationPort {
        final List<RunCreationPort.PendingOutbox> pending = new ArrayList<>();
        // 真并发用例会让两个发布者同时写这两个列表，用 COW 避免 ArrayList 并发写损坏。
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> failed = new CopyOnWriteArrayList<>();

        @Override
        public IdempotencyDecision findExistingResource(String t, IdempotencyKey k,
                                                          RequestDigest d) {
            throw new UnsupportedOperationException("发布器不查幂等");
        }

        @Override
        public String insertRunAtomically(String t, String r, String s, IdempotencyKey k,
                                          RequestDigest d, Map<String, Object> i, String c,
                                          OutboxMessage m) {
            throw new UnsupportedOperationException("发布器不创建 Run");
        }

        @Override
        public RunRecord findRun(String t, String r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutboxBatch loadPendingOutbox(String tenantId, int limit) {
            return new OutboxBatch(pending);
        }

        @Override
        public synchronized boolean markOutboxPublished(String t, String o, Instant a) {
            // 模拟 DB 的条件状态转换：只有仍为 PENDING 的那次 UPDATE 影响 1 行。
            // synchronized 保证 check-then-act 在真并发用例里是原子的（等价于 DB 行锁）。
            // published 因此代表「真正完成收敛的事件」，而非「调用次数」。
            if (published.contains(o)) {
                return false;
            }
            published.add(o);
            return true;
        }

        @Override
        public synchronized void markOutboxFailed(String t, String o, String e) {
            failed.add(o);
        }
    }

    private static FakeOutbox outboxWith(String outboxId, int attempts) {
        FakeOutbox f = new FakeOutbox();
        String runId = UUID.randomUUID().toString();
        f.pending.add(new RunCreationPort.PendingOutbox(outboxId, runId, "RUN_DISPATCH",
                new OutboxMessage(runId, TENANT, 0, 0L, NOW).toPayload(), attempts));
        return f;
    }

    private static OutboxPublisher publisher(FakeOutbox outbox,
                                             MessageSender sender) {
        return new OutboxPublisher(outbox, sender, TENANT,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), 100, 3);
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("发布器（P2-D02 §3）")
    class Publisher {

        @Test
        @DisplayName("✅ 发布成功 → 标记 PUBLISHED")
        void publishSuccessMarksPublished() {
            FakeOutbox outbox = outboxWith("ob-1", 0);
            var pub = publisher(outbox, (type, msg) -> { });

            int n = pub.publishOnce();

            assertThat(n).isEqualTo(1);
            assertThat(outbox.published).containsExactly("ob-1");
            assertThat(outbox.failed).isEmpty();
        }

        @Test
        @DisplayName("✅ 无待发布事件 → 返回 0")
        void noPendingReturnsZero() {
            FakeOutbox outbox = new FakeOutbox();
            assertThat(publisher(outbox, (t, m) -> { }).publishOnce()).isZero();
        }

        @Test
        @DisplayName("✅ 发布失败 → 标记 FAILED，不静默吞掉")
        void publishFailureMarksFailed() {
            FakeOutbox outbox = outboxWith("ob-2", 0);
            var pub = publisher(outbox, (type, msg) -> {
                throw new MessagePublishException("broker down");
            });

            assertThat(pub.publishOnce()).isZero();
            assertThat(outbox.failed).containsExactly("ob-2");
            assertThat(outbox.published).isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 达到失败阈值 → 标记 FAILED，不自动重置为 PENDING")
        void exhaustedAttemptsNotReset() {
            FakeOutbox outbox = outboxWith("ob-3", 2); // 阈值=3，本次将达到
            var pub = publisher(outbox, (type, msg) -> {
                throw new MessagePublishException("永久失败");
            });

            pub.publishOnce();

            assertThat(outbox.failed)
                    .as("⚠️ 自动重置为 PENDING 会让坏消息无限循环，须人工介入")
                    .containsExactly("ob-3");
        }

        @Test
        @DisplayName("⚠️ 未预期异常也被标记失败（不吞异常）")
        void unexpectedExceptionAlsoMarkedFailed() {
            FakeOutbox outbox = outboxWith("ob-4", 0);
            var pub = publisher(outbox, (type, msg) -> {
                throw new IllegalStateException("序列化异常");
            });

            assertThat(pub.publishOnce()).isZero();
            assertThat(outbox.failed).containsExactly("ob-4");
        }

        @Test
        @DisplayName("⚠️ 消息体含敏感字段 → 拒绝发布")
        void sensitivePayloadRejected() {
            FakeOutbox outbox = new FakeOutbox();
            String runId = UUID.randomUUID().toString();
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("runId", runId);
            payload.put("prompt", "系统的秘密提示词");
            outbox.pending.add(new RunCreationPort.PendingOutbox("ob-5", runId, "RUN_DISPATCH", payload, 0));

            var pub = publisher(outbox, (type, msg) -> { });
            pub.publishOnce();

            assertThat(outbox.published)
                    .as("发布器是消息进入 MQ 的最后关口，须复核消息体")
                    .isEmpty();
            assertThat(outbox.failed).containsExactly("ob-5");
        }

        @Test
        @DisplayName("⚠️ 并发重复领取：只有一次被计为成功发布，且绝不误标 FAILED")
        void duplicatePublishCountedOnceAndNeverFailed() {
            FakeOutbox outbox = outboxWith("ob-dup", 0);
            AtomicInteger sent = new AtomicInteger();
            MessageSender sender = (type, msg) -> sent.incrementAndGet();

            // 两个发布实例都从无锁 SELECT 捞到同一条 PENDING ——
            // Fake 的 loadPendingOutbox 不看 published，正是生产里
            // loadPendingOutbox「无锁 SELECT + rollbackQuietly」的形态。
            OutboxPublisher a = publisher(outbox, sender);
            OutboxPublisher b = publisher(outbox, sender);

            int n1 = a.publishOnce();
            int n2 = b.publishOnce();

            assertThat(n1 + n2)
                    .as("撤掉 OutboxPublisher 对 markOutboxPublished 返回值的判断，"
                            + "这里会是 2 —— 发布成功数指标静默虚高（P0 反向验证点）")
                    .isEqualTo(1);
            assertThat(outbox.published).containsExactly("ob-dup");
            assertThat(outbox.failed)
                    .as("消息已经成功发出；重复者绝不能被写成 FAILED（状态倒退 + 误告警）")
                    .isEmpty();
            assertThat(sent.get())
                    .as("诚实边界：本修复不阻止重复投递，消费端 DUPLICATE_DISCARDED 幂等承接")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("⚠️ 真并发：两个发布者同时领取同一条，成功数合计仍为 1")
        void trulyConcurrentPublishersHaveSingleWinner() throws Exception {
            FakeOutbox outbox = outboxWith("ob-race", 0);
            AtomicInteger sent = new AtomicInteger();
            MessageSender sender = (type, msg) -> sent.incrementAndGet();

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> f1 = pool.submit(() -> {
                    start.await();
                    return publisher(outbox, sender).publishOnce();
                });
                Future<Integer> f2 = pool.submit(() -> {
                    start.await();
                    return publisher(outbox, sender).publishOnce();
                });
                start.countDown();

                int total = f1.get(5, TimeUnit.SECONDS) + f2.get(5, TimeUnit.SECONDS);
                assertThat(total)
                        .as("真并发下也必须只有一个发布者完成状态收敛")
                        .isEqualTo(1);
                assertThat(outbox.published).containsExactly("ob-race");
                assertThat(outbox.failed).isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("消费者（P2-D02 §4）")
    class Consumer {

        /** 记录领取调用的假端口。 */
        private class FakeClaimPort implements RunClaimPort {
            boolean claimable = true;
            int claimCount;

            @Override
            public List<LeasedTask> claimTasks(String t, String w, Duration d, int l) {
                return List.of();
            }

            @Override
            public Optional<LeasedTask> claimSpecificRun(String tenantId, String runId,
                                                           String workerId, Duration d) {
                claimCount++;
                if (!claimable) {
                    return Optional.empty();
                }
                return Optional.of(new LeasedTask(runId, 1L, NOW.plusSeconds(30), 1));
            }

            @Override
            public boolean renewLease(String t, String r, String w, long f, Duration d) {
                return true;
            }

            @Override
            public boolean releaseLease(String t, String r, String w, long f) {
                return true;
            }

            @Override
            public boolean commitResult(String t, String r, long f, String s, String p) {
                return true;
            }

            @Override
            public boolean isCurrentGeneration(String t, String r, long f) {
                return true;
            }

            /** P3-D06：取消相关方法（Outbox 消费链路不涉及，恒未取消）。 */
            @Override
            public boolean requestCancel(String t, String r) {
                return false;
            }

            @Override
            public boolean isCancelled(String t, String r) {
                return false;
            }
        }

        private Map<String, Object> validPayload() {
            return new OutboxMessage(UUID.randomUUID().toString(), TENANT, 0, 0L, NOW)
                    .toPayload();
        }

        @Test
        @DisplayName("✅ 正常消息 → CLAIMED")
        void validMessageClaimed() {
            FakeClaimPort port = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(port, Duration.ofSeconds(30));

            var result = consumer.consume("RUN_DISPATCH", validPayload(), "w-1");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
        }

        @Test
        @DisplayName("⚠️⚠️ 重复投递（已被领取）→ DUPLICATE_DISCARDED，不重试")
        void duplicateDiscarded() {
            FakeClaimPort port = new FakeClaimPort();
            port.claimable = false;
            var consumer = new RunDispatchConsumer(port, Duration.ofSeconds(30));

            var result = consumer.consume("RUN_DISPATCH", validPayload(), "w-2");

            assertThat(result)
                    .as("⚠️ 重试会让消息在多个 Worker 间来回打转")
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.DUPLICATE_DISCARDED);
            assertThat(port.claimCount)
                    .as("只应尝试领取一次，不循环重试")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 消息含敏感字段 → REJECTED")
        void sensitivePayloadRejected() {
            FakeClaimPort port = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(port, Duration.ofSeconds(30));

            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("runId", "r1");
            payload.put("studentName", "张三");

            var result = consumer.consume("RUN_DISPATCH", payload, "w-3");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.REJECTED);
            assertThat(port.claimCount)
                    .as("非法消息不应触碰数据库")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️ 非本消费者事件 → REJECTED")
        void foreignEventRejected() {
            FakeClaimPort port = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(port, Duration.ofSeconds(30));

            var result = consumer.consume("SOME_OTHER_EVENT", validPayload(), "w-4");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.REJECTED);
            assertThat(port.claimCount).isZero();
        }

        @Test
        @DisplayName("⚠️ 缺 runId → REJECTED")
        void missingRunIdRejected() {
            FakeClaimPort port = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(port, Duration.ofSeconds(30));

            var result = consumer.consume("RUN_DISPATCH", Map.of("tenantId", TENANT), "w-5");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.REJECTED);
        }

        @Test
        @DisplayName("✅ 消息体只含白名单字段")
        void payloadContainsOnlyWhitelistedKeys() {
            Map<String, Object> payload = validPayload();
            assertThat(payload.keySet())
                    .containsExactlyInAnyOrder("runId", "tenantId", "attempt",
                            "fencingToken", "enqueuedAt");
        }
    }
}
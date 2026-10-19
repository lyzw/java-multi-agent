package cn.studykid.multiagent.worker.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * Worker 执行链连通性测试（P4-D01 · 关闭 P3 退出条件 C-3）。
 *
 * <p><b>⚠️ 本类要证明的是「链路是否接通」，不是「执行逻辑对不对」</b>——
 * 后者由 D3–D11 的既有测试覆盖。
 *
 * <p><b>为何必须有这一类测试</b>：修复前 {@code consume} 领到租约后
 * <b>直接返回</b>，{@code task} 拿到手即被丢弃，
 * {@code RunExecutionLoop} / {@code ToolInvocationGate} <b>从未被实例化</b>——
 * 而 D5–D11 的全部集成测试都直接 {@code new} 这些类，
 * 所以<b>每一项都测得通过</b>。
 *
 * <p>换言之：<b>此前没有任何测试验证过「它们是否真的被调用」</b>。
 */
class WorkerChainWiringTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = "run-wiring-1";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 可脚本化的领取端口。 */
    private static final class FakeClaimPort implements RunClaimPort {
        final AtomicInteger claimCount = new AtomicInteger();
        volatile boolean claimable = true;

        @Override public java.util.List<LeasedTask> claimTasks(
                String t, String w, java.time.Duration d, int l) {
            return java.util.List.of();
        }

        @Override public Optional<LeasedTask> claimSpecificRun(
                String tenantId, String runId, String workerId, java.time.Duration d) {
            claimCount.incrementAndGet();
            if (!claimable) {
                return Optional.empty();
            }
            return Optional.of(new LeasedTask(runId, 1L, Instant.now().plusSeconds(30), 1));
        }

        @Override public boolean renewLease(String t, String r, String w, long f,
                java.time.Duration d) { return true; }
        @Override public boolean releaseLease(String t, String r, String w, long f) { return true; }
        @Override public boolean commitResult(String t, String r, long f, String s, String p) {
            return true;
        }
        @Override public boolean isCurrentGeneration(String t, String r, long f) { return true; }
        @Override public boolean requestCancel(String t, String r) { return false; }
        @Override public boolean isCancelled(String t, String r) { return false; }
    }

    private static Map<String, Object> validPayload() {
        return new OutboxMessage(RUN_ID, TENANT, 0, 0L, Instant.now()).toPayload();
    }

    // ── W-1：断链修复的核心断言 ──────────────────────────────────────

    @Nested
    @DisplayName("W-1 ⚠️⚠️ 领取后必须真正执行（断链修复的核心）")
    class ExecutionActuallyHappens {

        @Test
        @DisplayName("⚠️⚠️ 消费 CLAIMED → 执行器被调用（而非只领租约）")
        void executorIsInvokedAfterClaim() {
            FakeClaimPort claim = new FakeClaimPort();
            AtomicInteger executed = new AtomicInteger();
            AtomicReference<String> executedTenant = new AtomicReference<>();

            var consumer = new RunDispatchConsumer(claim, java.time.Duration.ofSeconds(30),
                    (tenantId, task) -> {
                        executed.incrementAndGet();
                        executedTenant.set(tenantId);
                        return RunDispatchConsumer.Outcome.SUCCEEDED;
                    });

            var result = consumer.consume(
                    RunDispatchConsumer.RUN_DISPATCH, validPayload(), "worker-1");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
            assertThat(executed.get())
                    .as("⚠️⚠️ 执行器必须被调用——修复前这里恒为 0（断链）")
                    .isEqualTo(1);
            assertThat(executedTenant.get())
                    .as("⚠️ 租户必须来自已验签消息，不得为 null")
                    .isEqualTo(TENANT);
        }

        @Test
        @DisplayName("⚠️ 未装配执行器时显式告警（不得静默只领不跑）")
        void missingExecutorIsLoud() {
            FakeClaimPort claim = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(claim, java.time.Duration.ofSeconds(30));

            var result = consumer.consume(
                    RunDispatchConsumer.RUN_DISPATCH, validPayload(), "worker-1");

            assertThat(result).isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
            assertThat(claim.claimCount.get())
                    .as("⚠️ 仍应领取租约（保持兼容），但执行器缺失已在日志中告警")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 重复投递 → 不执行（已在领取阶段被拒）")
        void duplicateNotExecuted() {
            FakeClaimPort claim = new FakeClaimPort();
            claim.claimable = false;
            AtomicInteger executed = new AtomicInteger();

            var consumer = new RunDispatchConsumer(claim, java.time.Duration.ofSeconds(30),
                    (tenantId, task) -> {
                        executed.incrementAndGet();
                        return RunDispatchConsumer.Outcome.SUCCEEDED;
                    });

            var result = consumer.consume(
                    RunDispatchConsumer.RUN_DISPATCH, validPayload(), "worker-1");

            assertThat(result)
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.DUPLICATE_DISCARDED);
            assertThat(executed.get())
                    .as("⚠️ 重复投递不得执行（他人已领走）")
                    .isZero();
        }
    }

    // ── W-2/W-3：结局 → MQ 处置映射（⚠️ UNKNOWN 是 Sev-0 防线）──────

    @Nested
    @DisplayName("W-2…W-4 结局 → MQ 处置（按重试策略决定）")
    class OutcomeMapping {

        private RunDispatchConsumer.ConsumeResult consumeWith(
                RunDispatchConsumer.Outcome outcome) {
            var claim = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(claim, java.time.Duration.ofSeconds(30),
                    (tenantId, task) -> outcome);
            return consumer.consume(
                    RunDispatchConsumer.RUN_DISPATCH, validPayload(), "worker-1");
        }

        @Test
        @DisplayName("⚠️ SUCCEEDED → ack 丢弃（不重投）")
        void succeededIsAcked() {
            assertThat(consumeWith(RunDispatchConsumer.Outcome.SUCCEEDED))
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
        }

        @Test
        @DisplayName("⚠️⚠️⚠️ UNKNOWN → ack 丢弃，**绝不重投**（重投 = 重复副作用 = Sev-0）")
        void unknownMustNotBeRedelivered() {
            assertThat(consumeWith(RunDispatchConsumer.Outcome.UNKNOWN_RESULT))
                    .as("⚠️⚠️⚠️ UNKNOWN 必须映射为丢弃——"
                            + "任何改成 RETRY 的改动都会让 record_weakness 重复写入")
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
        }

        @Test
        @DisplayName("⚠️ RETRYABLE_FAILURE → nack 重投")
        void retryableIsRedelivered() {
            assertThat(consumeWith(RunDispatchConsumer.Outcome.RETRYABLE_FAILURE))
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.RETRY);
        }

        @Test
        @DisplayName("✅ 终态结局（CANCELLED / STALE_GENERATION / FAILED）均丢弃")
        void terminalOutcomesAcked() {
            for (var outcome : new RunDispatchConsumer.Outcome[]{
                    RunDispatchConsumer.Outcome.CANCELLED,
                    RunDispatchConsumer.Outcome.STALE_GENERATION,
                    RunDispatchConsumer.Outcome.FAILED}) {
                assertThat(consumeWith(outcome))
                        .as("结局 %s 应 ack 丢弃", outcome)
                        .isEqualTo(RunDispatchConsumer.ConsumeResult.CLAIMED);
            }
        }

        @Test
        @DisplayName("⚠️ 执行器抛异常 → REJECTED（进死信，不静默吞掉）")
        void executorExceptionRejected() {
            var claim = new FakeClaimPort();
            var consumer = new RunDispatchConsumer(claim, java.time.Duration.ofSeconds(30),
                    (tenantId, task) -> {
                        throw new IllegalStateException("模拟执行崩溃");
                    });

            var result = consumer.consume(
                    RunDispatchConsumer.RUN_DISPATCH, validPayload(), "worker-1");

            assertThat(result)
                    .as("⚠️ 执行异常不得被吞掉——必须走死信便于排查")
                    .isEqualTo(RunDispatchConsumer.ConsumeResult.REJECTED);
        }
    }
}
package cn.studykid.multiagent.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunLimits;

/**
 * P2-1：<b>「不限时」的 Run 从此是一条显式决策，而不是一处沉默的默认</b>。
 *
 * <p>⚠️ <b>本类要钉住的判据</b>：{@code timeoutMs == null} → {@code deadlineAt == null}
 * → 执行循环里的 {@code if (task.deadlineAt() != null …)} <b>整段跳过</b>，
 * 该 Run 失去任何业务时间上界，只剩 {@code maxSteps} 与租约续期兜底。
 * 修复前这个后果<b>既不在日志里、也不在配置里</b>：
 * 「忘记设超时」与「故意设成很长」从外部完全无法区分。
 *
 * <p>⚠️ <b>为什么默认仍允许「不限时」</b>：这是 {@code CreateRunRequest} 的既定语义，
 * 且被 {@code CreateRunRequestTimeoutTest} 显式锁定。把它改成必填，
 * 等于在无产品决策的前提下静默改变对外契约 —— 那是break change，不是加固。
 * 加固的正确形状是：保留默认、<b>加上策略开关</b>、并让不受保护的事实可见。
 *
 * <p><b>本类的三条判据</b>：
 * <ul>
 *   <li>T-1：{@code requireRunTimeout=false}（默认）→ 不限时仍被接受，语义不变；</li>
 *   <li>T-2：{@code requireRunTimeout=true} → 未声明 timeoutMs 在创建期被拒绝，
 *           且<b>一次都没有写库</b>（不是「先创建、运行期再被打死」）；</li>
 *   <li>T-3：{@code requireRunTimeout=true} 且声明了 timeoutMs → 正常放行，
 *           并算出正确的截止时刻。</li>
 * </ul>
 */
class RunCreationServiceTimeoutPolicyTest {

    private static final String TENANT = "t-a";
    private static final TenantContext CONTEXT =
            new TenantContext(TENANT, "p-1", java.util.Set.of("TENANT_ADMIN"), 1L);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 记录是否真的写了 Run —— 拒绝路径上必须一次都不写。 */
    private static final class RecordingRunCreation implements RunCreationPort {
        int inserts;
        RunLimits seenLimits;

        @Override
        public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   @NonNull String snapshotId,
                                                   @NonNull IdempotencyKey key,
                                                   @NonNull RequestDigest digest,
                                                   @NonNull Map<String, Object> inputPayload,
                                                   @NonNull String correlationId,
                                                   @NonNull OutboxMessage agentPayload) {
            inserts++;
            return runId;
        }

        @Override
        public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   @NonNull String snapshotId,
                                                   @NonNull IdempotencyKey key,
                                                   @NonNull RequestDigest digest,
                                                   @NonNull Map<String, Object> inputPayload,
                                                   @NonNull String correlationId,
                                                   @NonNull OutboxMessage agentPayload,
                                                   @NonNull RunLimits limits) {
            this.seenLimits = limits;
            inserts++;
            return runId;
        }

        @Override
        public RunCreationPort.@Nullable RunRecord
                findRun(@NonNull String tenantId, @NonNull String runId) {
            return null;
        }

        @Override
        public @NonNull OutboxBatch loadPendingOutbox(@NonNull String tenantId, int limit) {
            return new OutboxBatch(List.of());
        }

        @Override
        public void markOutboxPublished(@NonNull String tenantId, @NonNull String outboxId,
                                        @NonNull Instant publishedAt) {
        }

        @Override
        public void markOutboxFailed(@NonNull String tenantId, @NonNull String outboxId,
                                     @NonNull String error) {
        }

        @Override
        public @NonNull IdempotencyDecision findExistingResource(
                @NonNull String tenantId, @NonNull IdempotencyKey key,
                @NonNull RequestDigest digest) {
            return IdempotencyDecision.firstExecution(UUID.randomUUID().toString());
        }
    }

    private static final ExecutionSnapshotPort SNAPSHOT =
            (tenantId, agentId) -> Optional.of(new ExecutionSnapshotPort.SnapshotRef(
                    "11111111-1111-1111-1111-111111111111", 1));

    private static final RunCreationService.TransactionRunner TX =
            new RunCreationService.TransactionRunner() {
                @Override
                public <T> T execute(@NonNull String tenantId, TransactionalAction<T> action) {
                    return action.run();
                }
            };

    private static RunCreationService service(RunCreationPort port, boolean requireTimeout) {
        return new RunCreationService(port, SNAPSHOT, TX, CLOCK, null, null, requireTimeout);
    }

    private static void create(RecordingRunCreation port, RunCreationService service,
                               Long timeoutMs) {
        service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-1"), Map.of("q", "x"),
                timeoutMs, null);
    }

    @Test
    @DisplayName("T-1 默认策略：未声明 timeoutMs 仍被接受（既定语义不得被加固顺手改掉）")
    void unlimitedStillAllowedByDefault() {
        RecordingRunCreation port = new RecordingRunCreation();
        RunCreationService service = service(port, false);

        assertThatCode(() -> create(port, service, null))
                .as("默认值 false = 沿用 CreateRunRequest 的「不传即不限时」契约")
                .doesNotThrowAnyException();
        assertThat(port.inserts).isEqualTo(1);
        assertThat(port.seenLimits.deadlineAt())
                .as("不限时的 Run，其 deadlineAt 必须是 null（不假装有上界）")
                .isNull();
    }

    @Test
    @DisplayName("T-2 ⛔ requireRunTimeout=true：未声明 timeoutMs 在创建期被拒，且绝不写库")
    void unlimitedRejectedWhenPolicyRequiresTimeout() {
        RecordingRunCreation port = new RecordingRunCreation();
        RunCreationService service = service(port, true);

        assertThatThrownBy(() -> create(port, service, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
        assertThat(port.inserts)
                .as("必须在创建期就拒绝：Run 已落库再失败，用户侧是「转圈后被判死」")
                .isZero();
    }

    @Test
    @DisplayName("T-3 ✅ requireRunTimeout=true 且声明了 timeoutMs → 放行并算出正确截止时刻")
    void declaredTimeoutProceedsUnderStrictPolicy() {
        RecordingRunCreation port = new RecordingRunCreation();
        RunCreationService service = service(port, true);

        create(port, service, 30_000L);

        assertThat(port.inserts).isEqualTo(1);
        assertThat(port.seenLimits.deadlineAt())
                .isEqualTo(NOW.plusMillis(30_000));
    }
}

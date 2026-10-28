package cn.studykid.multiagent.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.CapDualWritePort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort.SnapshotRef;
import cn.studykid.multiagent.ports.persistence.RunCreationPort.OutboxBatch;
import cn.studykid.multiagent.ports.persistence.RunCreationPort.RunRecord;
import cn.studykid.multiagent.ports.persistence.RunLimits;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;

/**
 * Run 创建链路对 {@code execution_capability_snapshot} 的<b>写入入口</b>（G-3 · S0）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复前这张表<b>全仓没有生产写入点</b>
 * （唯一的 {@code save()} 调用在双写服务里，而双写服务的
 * {@code onExecutionSnapshotCreated} 没有任何调用方）。
 * 后果不是「少一份审计数据」，而是<b>依赖该快照的六个读方一律 fail-closed</b>
 * —— 每个 Run 建得出来、跑不起来，且现场看着像「配置缺失」。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>C-1：首次创建 → 双写被调用，且带的是<b>写事务内解析出的</b> Agent 版本与
 *       <b>认证上下文里的</b>主体；</li>
 *   <li>C-2：幂等重放 → <b>不</b>调用双写（重放不得产生副作用）；</li>
 *   <li>C-3：快照不可解析 → 建 Run 失败，双写也<b>不</b>发生；</li>
 *   <li>C-4：未装配双写 → Run <b>照常</b>创建（不给创建请求新增失败面）；</li>
 *   <li>C-5：双写<b>先于</b>事务提交 —— 这是执行侧能否读到快照的分水岭。</li>
 * </ul>
 */
class RunCreationServiceCapabilitySnapshotTest {

    private static final String TENANT = "t-a";
    private static final String PRINCIPAL = "p-1";
    private static final TenantContext CONTEXT =
            new TenantContext(TENANT, PRINCIPAL, Set.of("TENANT_ADMIN"), 1L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String SNAPSHOT_ID = "11111111-1111-1111-1111-111111111111";

    /** 写事务解析出的版本号：断言它被原样带到双写，而不是「另查一次当前版本」。 */
    private static final int RESOLVED_VERSION = 7;

    // ── 替身 ────────────────────────────────────────────────────────

    /** 记录 Run 写入与快照绑定参数的仓储替身。 */
    private static class RecordingRunCreation implements RunCreationPort {
        final List<String> snapshotIds = new ArrayList<>();
        /** 幂等判定结果；默认「首次执行」。 */
        IdempotencyDecision decision = IdempotencyDecision.firstExecution("placeholder");

        @Override
        public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   @NonNull String snapshotId,
                                                   @NonNull IdempotencyKey key,
                                                   @NonNull RequestDigest digest,
                                                   @NonNull Map<String, Object> inputPayload,
                                                   @NonNull String correlationId,
                                                   @NonNull OutboxMessage agentPayload) {
            return insertRunAtomically(tenantId, runId, snapshotId, key, digest, inputPayload,
                    correlationId, agentPayload, RunLimits.UNLIMITED);
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
            snapshotIds.add(snapshotId);
            return runId;
        }

        @Override
        public @Nullable RunRecord findRun(@NonNull String tenantId, @NonNull String runId) {
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
            return decision;
        }
    }

    /** 记录双写调用的替身。 */
    private static final class RecordingDualWrite implements CapDualWritePort {
        final List<String> calls = new ArrayList<>();
        /** 非空时把「双写发生了」记进同一条时序，供提交顺序断言。 */
        @Nullable List<String> order;

        @Override
        public void onAgentVersionPublished(@NonNull String tenantId, @NonNull String agentId,
                                            int versionNumber, @Nullable String definition,
                                            @Nullable String agentName,
                                            @Nullable String agentDescription,
                                            @Nullable Instant publishedAt) {
            throw new AssertionError("Run 创建不该触发版本发布双写");
        }

        @Override
        public void onExecutionSnapshotCreated(@NonNull String tenantId, @NonNull String runId,
                                               @NonNull String agentId, int agentVersion,
                                               @NonNull String principalId) {
            calls.add(tenantId + "|" + runId + "|" + agentId + "|" + agentVersion
                    + "|" + principalId);
            if (order != null) {
                order.add("dual-write");
            }
        }
    }

    /**
     * 带时序记录的事务器：在<b>提交点</b>打标记。
     *
     * <p>存在的理由见 C-5 —— 双写相对 commit 的<b>位置</b>就是它能否被 Worker 读到的分水岭。
     * <p>注意本服务一个 {@code createRun} 会开<b>两个</b>事务（先幂等读、后写），
     * 所以断言看的是<b>最后一个</b>事务里的顺序。
     */
    private static final class OrderingTx implements RunCreationService.TransactionRunner {
        final List<String> order = new ArrayList<>();

        @Override
        public <T> T execute(
                @NonNull String tenantId, @NonNull TransactionalAction<T> action) {
            T result = action.run();
            order.add("commit");
            return result;
        }
    }

    /** 恒「首次执行」的极简事务器。 */
    private static final RunCreationService.TransactionRunner TX =
            new RunCreationService.TransactionRunner() {
                @Override
                public <T> T execute(@NonNull String tenantId, TransactionalAction<T> action) {
                    return action.run();
                }
            };

    private static ExecutionSnapshotPort resolver(
            @Nullable SnapshotRef ref) {
        return (tenantId, agentId) -> Optional.ofNullable(ref);
    }

    private static RunCreationService service(RunCreationPort runCreation,
                                              @Nullable CapDualWritePort dualWrite,
                                              @Nullable SnapshotRef ref) {
        return new RunCreationService(runCreation, resolver(ref), TX, CLOCK, null, dualWrite);
    }

    private static @NonNull SnapshotRef resolved() {
        return new SnapshotRef(SNAPSHOT_ID, RESOLVED_VERSION);
    }

    // ── C-1 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-1 ✅ 首次创建 → 双写被调用，参数是事务内解析出的版本 + 认证上下文主体")
    void firstExecutionTriggersCapabilitySnapshotDualWrite() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RecordingDualWrite dualWrite = new RecordingDualWrite();
        RunCreationService service = service(runCreation, dualWrite, resolved());

        var result = service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-1"), Map.of("q", "x"));

        assertThat(runCreation.snapshotIds)
                .as("Run 必须绑到解析出的快照（不是随机 UUID）")
                .containsExactly(SNAPSHOT_ID);
        assertThat(dualWrite.calls)
                .as("⛔ 双写没被调用 = execution_capability_snapshot 依旧没有写入点 = G-3 未修复")
                .hasSize(1);
        assertThat(dualWrite.calls.get(0).split("\\|"))
                .as("双写的五个参数：租户来自认证上下文、版本号来自写事务内的解析")
                .containsExactly(TENANT, result.runId(), "agent-1",
                        String.valueOf(RESOLVED_VERSION), PRINCIPAL);
    }

    // ── C-2 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-2 ⛔ 幂等重放 → 不双写（重放不得产生副作用）")
    void replayDoesNotDualWrite() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        String firstRunId = UUID.randomUUID().toString();
        runCreation.decision = IdempotencyDecision.replay(firstRunId);
        RecordingDualWrite dualWrite = new RecordingDualWrite();
        RunCreationService service = service(runCreation, dualWrite, resolved());

        var result = service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-2"), Map.of("q", "x"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.runId()).isEqualTo(firstRunId);
        assertThat(dualWrite.calls)
                .as("⛔ 重放再写一次快照 = 重复副作用；首那次已经写过了")
                .isEmpty();
        assertThat(runCreation.snapshotIds).isEmpty();
    }

    // ── C-3 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-3 ⛔ 快照不可解析（Agent 未发布）→ 建 Run 失败且绝不双写")
    void unavailableSnapshotRejectsBeforeDualWrite() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RecordingDualWrite dualWrite = new RecordingDualWrite();
        RunCreationService service = service(runCreation, dualWrite, null);

        assertThatThrownBy(() -> service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-3"), Map.of("q", "x")))
                .isInstanceOf(ExecutionSnapshotUnavailableException.class);

        assertThat(dualWrite.calls)
                .as("⛔ Run 都没建成就写能力快照 = 给一个不存在的 runId 写不可变历史（外键必炸）")
                .isEmpty();
    }

    // ── C-4 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("C-4 ✅ 未装配双写 → Run 照常创建（不得新增失败面）")
    void missingDualWritePortDoesNotBreakRunCreation() {
        RecordingRunCreation runCreation = new RecordingRunCreation();

        var result = service(runCreation, null, resolved())
                .createRun(CONTEXT, "agent-1",
                        new IdempotencyKey("run.create", "k-4"), Map.of("q", "x"));

        assertThat(result.replayed()).isFalse();
        assertThat(runCreation.snapshotIds).containsExactly(SNAPSHOT_ID);
    }

    // ── C-5 ─────────────────────────────────────────────────────────

    /**
     * C-5 是本类里唯一会「跑着跑着突然坏」的判据：它锁的是<b>时序</b>而不是参数。
     *
     * <p>2026-10-28 真实端到端实测：双写放在事务外时<b>晚约 1.15s</b> 落库（闭包解析要串
     * 「指针 → 发布头 → 对象版本」三段查询），而 Outbox 消息与 Run 同事务提交，
     * Worker 几十毫秒就能领到任务 —— 于是执行侧读不到根 Agent 身份，
     * 按 fail-closed 把每一个 Run 判成 CONFIG_UNAVAILABLE。
     */
    @Test
    @DisplayName("C-5 ⛔ 双写必须先于 commit（否则 Worker 读不到快照 = 每个 Run 必挂）")
    void dualWriteHappensBeforeCommit() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        OrderingTx tx = new OrderingTx();
        RecordingDualWrite dualWrite = new RecordingDualWrite();
        dualWrite.order = tx.order;
        RunCreationService service = new RunCreationService(runCreation, resolver(resolved()),
                tx, CLOCK, null, dualWrite);

        service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-5"), Map.of("q", "x"));

        assertThat(tx.order)
                .as("⛔ commit 之后才双写 = 消息先可见、快照后可见 = 执行侧 fail-closed")
                .endsWith("dual-write", "commit");
    }

    // ── 版本号与快照同源 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ SnapshotRef 拒绝非正版本号 —— 缺失的版本号不许伪装成 v0 写进快照")
    void snapshotRefRejectsNonPositiveVersion() {
        assertThatThrownBy(() -> new SnapshotRef(SNAPSHOT_ID, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentVersion");
        assertThatThrownBy(() -> new SnapshotRef(" ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotId");
    }
}

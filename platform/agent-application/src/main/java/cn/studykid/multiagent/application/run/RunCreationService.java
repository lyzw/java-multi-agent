package cn.studykid.multiagent.application.run;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import org.springframework.stereotype.Service;

/**
 * Run 创建用例（P1-05 / 红线 3）。
 *
 * <p><b>原子提交</b>：Run、初始事件、幂等记录、Outbox 四者同事务写入。
 * 事务边界由 {@link TransactionRunner} 显式控制——实现方<b>不</b>自行提交，
 * 避免「实现内部提交」造成半成品状态。
 *
 * <p><b>幂等语义</b>（ADR-003）：
 * <ul>
 *   <li>首次执行 → 创建 Run；</li>
 *   <li>重复且请求相同 → 返回<b>首次的 Run</b>，不新建；</li>
 *   <li>键相同但请求不同 → 冲突，拒绝。</li>
 * </ul>
 */
@Service
public class RunCreationService {

    private final RunCreationPort runCreation;
    private final ExecutionSnapshotPort snapshots;
    private final TransactionRunner tx;
    private final Clock clock;

    public RunCreationService(@NonNull RunCreationPort runCreation,
                              @NonNull ExecutionSnapshotPort snapshots,
                              @NonNull TransactionRunner tx,
                              @NonNull Clock clock) {
        this.runCreation = runCreation;
        this.snapshots = snapshots;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 创建 Run（原子提交）。
     *
     * @param context      认证上下文；<b>tenantId 取自此处，不得取自请求参数</b>
     * @param agentId      Agent ID（来自请求体）；执行快照由本用例<b>在写事务内</b>解析
     * @param key          幂等键
     * @param inputPayload 输入载荷（不含可识别学生信息）
     * @return 创建结果
     * @throws ExecutionSnapshotUnavailableException 该 Agent 未发布或无执行快照（→ 404）
     */
    public @NonNull CreateRunResult createRun(@NonNull TenantContext context,
                                               @NonNull String agentId,
                                               @NonNull IdempotencyKey key,
                                               @NonNull Map<String, Object> inputPayload) {
        RequestDigest digest = RequestDigest.of(inputPayload);
        String correlationId = UUID.randomUUID().toString();

        // 先查幂等记录：命中则不进入写路径，避免无谓的事务与写入
        IdempotencyDecision existing = tx.execute(tenantId(context), () ->
                runCreation.findExistingResource(context.tenantId(), key, digest));

        if (existing.isConflict()) {
            existing.throwIfConflict();
            throw new IllegalStateException("不可达：throwIfConflict 应已抛出");
        }
        if (existing.kind() == IdempotencyDecision.Kind.REPLAY) {
            // 重复请求：直接返回首次结果，<b>不重新执行任何副作用</b>
            return new CreateRunResult(existing.originalResourceId(), true);
        }

        // 首次执行：四者同事务写入
        String runId = UUID.randomUUID().toString();
        OutboxMessage message = new OutboxMessage(
                runId, context.tenantId(), 0, 0L, clock.instant());
        OutboxMessage.assertPayloadSafe(message.toPayload());

        return tx.execute(tenantId(context), () -> {
            // ⚠️⚠️ 快照解析**必须在写事务内**（P4-D18 修复）：
            //   此前由调用方传入的 snapshotId() 恒为随机 UUID，
            //   必然违反 run_snapshot_fk → 端点恒 500。
            //   放事务内解析还顺带消除了「解析完 → Agent 恰好被改版」的窗口：
            //   解析与写入落在同一个快照下。
            //   ⚠️ 不可解析属**客户端错误**（4xx），必须显式抛出而非让
            //   外键以 PSQLException 的形式冒泡成 500。
            String snapshotId = snapshots
                    .resolveSnapshotId(context.tenantId(), agentId)
                    .orElseThrow(() -> new ExecutionSnapshotUnavailableException(
                            context.tenantId(), agentId));

            String actualRunId = runCreation.insertRunAtomically(
                    context.tenantId(),
                    runId,
                    snapshotId,
                    key,
                    digest,
                    inputPayload,
                    correlationId,
                    message);
            return new CreateRunResult(actualRunId, false);
        });
    }

    /**
     * 事务边界抽象。
     *
     * <p><b>为什么要显式抽象</b>：若在实现里直接写 {@code commit()}，
     * 一旦忘记回滚路径就会留下半成品数据。抽象出来使得「哪些操作在事务内」
     * 一目了然，且便于集成测试替换为真实事务管理器。
     */
    @FunctionalInterface
    public interface TransactionRunner {
        <T> T execute(@NonNull String tenantId, @NonNull TransactionalAction<T> action);

        /** 事务内的动作。 */
        @FunctionalInterface
        interface TransactionalAction<T> {
            T run();
        }
    }

    /**
     * 创建结果。
     *
     * @param runId      Run 标识（新建或复用首次的）
     * @param replayed   是否为重复请求命中既有 Run
     */
    public record CreateRunResult(@NonNull String runId, boolean replayed) {
        public CreateRunResult {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId 不能为空");
            }
        }
    }

    /**
     * 取租户 ID 用于事务绑定。
     *
     * <p>注意：这里取的是<b>认证上下文</b>里的 tenantId，
     * 而不是方法参数中的任何客户端输入（P0-D02 NFR-001）。
     */
    private static String tenantId(TenantContext context) {
        return context.tenantId();
    }
}
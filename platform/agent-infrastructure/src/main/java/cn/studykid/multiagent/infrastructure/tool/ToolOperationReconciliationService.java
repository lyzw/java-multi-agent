package cn.studykid.multiagent.infrastructure.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * ToolOperation 对账服务（P3-D03 §2.3 / FR-009 / 红线 7）。
 *
 * <p><b>对账原则：查下游事实，不猜</b>。
 * UNKNOWN 的含义是「请求已发出但响应丢失」——副作用可能已发生。
 * 收敛依据是<b>下游幂等记录是否真实存在</b>，而不是「重试一下看看」：
 * <ul>
 *   <li>查到 → {@code RECONCILED_SUCCEEDED}（副作用已发生，
 *       ⚠️ 预算不释放——成本真实发生了，R-007）；</li>
 *   <li>没查到但登记时间未超宽限期 → <b>保持 UNKNOWN</b>（下游可能尚未落库，
 *       读己之写延迟下「没查到」≠「没发生」）；</li>
 *   <li>没查到且超宽限期 → {@code RECONCILED_ABSENT}；</li>
 *   <li>不可查的（幂等键无法解析）→ 保持 UNKNOWN，转人工（D5 接 MANUAL_DISPOSED）。</li>
 * </ul>
 *
 * <p>对账是<b>幂等</b>的：可反复扫描，已收敛的不会被再碰（状态机只认 UNKNOWN）。
 *
 * <p>⚠️ 刻意不加 {@code @Service}：装配归 bootstrap 的 ApplicationConfiguration
 * （与项目其他基础设施类一致），避免被测试切片的组件扫描意外拾取
 * 而在缺依赖时炸掉整个上下文（E2E 15 项曾因此全错）。
 */
public class ToolOperationReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(ToolOperationReconciliationService.class);

    private final RunToolLedgerPort ledger;
    private final StudentDataRepository studentData;
    private final Duration gracePeriod;

    public ToolOperationReconciliationService(@NonNull RunToolLedgerPort ledger,
                                              @NonNull StudentDataRepository studentData,
                                              @NonNull Duration gracePeriod) {
        this.ledger = ledger;
        this.studentData = studentData;
        this.gracePeriod = gracePeriod;
    }

    /** 对账结果统计。 */
    public record ReconcileStats(int scanned, int succeeded, int absent, int deferred) {
    }

    /**
     * 扫描并收敛该租户全部 UNKNOWN 操作。
     */
    public @NonNull ReconcileStats reconcileAll(@NonNull String tenantId) {
        List<RunToolLedgerPort.UnknownOperation> pending =
                ledger.findUnknownOperations(tenantId);
        int succeeded = 0;
        int absent = 0;
        int deferred = 0;
        for (RunToolLedgerPort.UnknownOperation op : pending) {
            Outcome o = reconcileOne(tenantId, op);
            switch (o) {
                case SUCCEEDED -> succeeded++;
                case ABSENT -> absent++;
                case DEFERRED -> deferred++;
            }
        }
        if (log.isInfoEnabled()) {
            log.info("对账扫描完成: tenant={}, 扫描={}, 收敛成功={}, 确认未发生={}, 顺延={}",
                    tenantId, pending.size(), succeeded, absent, deferred);
        }
        return new ReconcileStats(pending.size(), succeeded, absent, deferred);
    }

    /** 单个操作的对账结果。 */
    public enum Outcome { SUCCEEDED, ABSENT, DEFERRED }

    /**
     * 对单个 UNKNOWN 操作对账。
     */
    public @NonNull Outcome reconcileOne(@NonNull String tenantId,
                                         cn.studykid.multiagent.ports.persistence.
                                                 RunToolLedgerPort.UnknownOperation op) {
        ParsedKey key = parseRecordWeaknessKey(op.idempotencyKey());
        if (key == null) {
            // ⚠️ 不可解析 → 保持 UNKNOWN 转人工。绝不猜测性收敛。
            log.warn("幂等键不可解析，保持 UNKNOWN 转人工: operation={}, key={}",
                    op.operationId(), op.idempotencyKey());
            return Outcome.DEFERRED;
        }

        var found = studentData.reconcileWeakness(
                tenantId, key.studentId(), key.runId(), key.knowledgePoint());

        if (found.isPresent()) {
            ledger.reconcile(tenantId, op.operationId(),
                    ToolOperationState.RECONCILED_SUCCEEDED,
                    "profile-lookup:hit:" + found.get());
            return Outcome.SUCCEEDED;
        }

        // 没查到：区分「还没落库」与「确实没发生」——宽限期内不判定
        if (Instant.now().isBefore(op.createdAt().plus(gracePeriod))) {
            if (log.isDebugEnabled()) {
                log.debug("宽限期内未查到下游记录，保持 UNKNOWN: operation={}",
                        op.operationId());
            }
            return Outcome.DEFERRED;
        }

        ledger.reconcile(tenantId, op.operationId(),
                ToolOperationState.RECONCILED_ABSENT,
                "profile-lookup:absent-after-grace:" + gracePeriod);
        return Outcome.ABSENT;
    }

    /**
     * 解析 record_weakness 幂等键：
     * {@code tool.record_weakness/<studentId>/<runId>/<knowledgePoint>}。
     *
     * @return 不可解析返回 {@code null}（转人工）
     */
    private record ParsedKey(String studentId, String runId, String knowledgePoint) { }

    private ParsedKey parseRecordWeaknessKey(String composite) {
        // composite 格式：tool.record_weakness:<studentId>/<runId>/<knowledgePoint>
        if (composite == null || !composite.startsWith("tool.record_weakness:")) {
            return null;
        }
        String[] parts = composite.split("/");
        if (parts.length != 3) {
            return null;
        }
        return new ParsedKey(
                parts[0].substring("tool.record_weakness:".length()),
                parts[1], parts[2]);
    }
}

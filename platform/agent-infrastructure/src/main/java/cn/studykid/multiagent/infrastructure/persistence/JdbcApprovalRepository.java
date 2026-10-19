package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/**
 * {@link ApprovalPort} 的 JDBC 实现（P3-D05 / FR-013）。
 *
 * <p><b>⚠️ 三个不变量</b>：
 * <ol>
 *   <li><b>消费与取消检查同事务原子</b>（§2 D-1）——
 *       先 {@code SELECT..FOR UPDATE} 锁 Run 校验取消，再锁审批校验状态/有效期/绑定；
 *       两者任一步不满足即拒绝，绝不「先消费后检查」；</li>
 *   <li><b>绑定防篡改</b>（§2 D-2）—— 消费时比对 argument_digest 与 target_resource，
 *       与审批授予时绑定的值必须一致；不一致即拒绝，审批后的参数篡改无效；</li>
 *   <li><b>幂等消费</b>（§2 D-3）—— 已 CONSUMED 的审批再次消费返回 CONSUMED，
 *       支持「提交后崩溃、重启重放」的崩溃恢复，且不制造双重计费。</li>
 * </ol>
 *
 * <p><b>连接所有权</b>：全部经 {@link TransactionalConnectionHolder#inTransaction}
 * （P2-D05 铁律：禁止直接 {@code dataSource.getConnection()}）。
 *
 * <p><b>DB 第二道防线</b>：{@code enforce_approval_state_transition} 触发器
 * （V5 迁移）禁止从终态（CONSUMED/EXPIRED/REJECTED）非法迁出，
 * 与应用层校验构成双保险（D-4）。
 */
public class JdbcApprovalRepository implements ApprovalPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalRepository.class);

    private final DataSource dataSource;

    /**
     * 审计端口（P3-D07 / R-1）。
     *
     * <p>⚠️ <b>可空 + 默认空实现</b>：本类此前无审计依赖；新增构造参数会波及
     * 所有调用方。缺省为空实现（no-op）保持既有行为，
     * 生产装配必须传入真实实现——否则 R-1 的「审批决定可追溯」名存实亡。
     */
    private final AuditPort audit;

    /** 空审计（未装配时的占位）。生产<b>必须</b>替换为真实实现。 */
    private static final AuditPort NO_AUDIT = new AuditPort() {
        @Override
        public void recordRequired(String tenantId, String actorId, String action,
                                   String resourceType, String resourceId,
                                   Map<String, Object> detail) {
            log.error("⚠️⚠️ 关键审计未装配，审批动作将无痕（tenant={}, action={}）",
                    tenantId, action);
        }

        @Override
        public void recordAccess(String tenantId, String actorId, String action,
                                 String resourceType, String resourceId,
                                 Map<String, Object> detail) {
            // no-op
        }

        @Override
        public java.util.List<AuditView> findByResource(String tenantId,
                                                        String resourceType,
                                                        String resourceId) {
            return java.util.List.of();
        }
    };

    public JdbcApprovalRepository(@NonNull DataSource dataSource) {
        this(dataSource, NO_AUDIT);
    }

    public JdbcApprovalRepository(@NonNull DataSource dataSource, @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull String grant(@NonNull String tenantId,
                                 @NonNull String operationId,
                                 @NonNull String argumentDigest,
                                 @NonNull String targetResource,
                                 @NonNull ApprovalRequirement requirement,
                                 @NonNull String approverScope,
                                 @NonNull Instant expiresAt,
                                 @NonNull String approverId) {
        String approvalId = inTenantTx(tenantId, conn -> {
            String sql = """
                    INSERT INTO approval
                        (tenant_id, operation_id, state, requirement,
                         argument_digest, target_resource, approver_scope,
                         expires_at, approver_id, decided_at)
                    VALUES (?::uuid, ?::uuid, 'APPROVED', ?::approval_requirement,
                            ?, ?, ?, ?, ?, now())
                    RETURNING approval_id::text
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                ps.setString(3, requirement.name());
                ps.setString(4, argumentDigest);
                ps.setString(5, targetResource);
                ps.setString(6, approverScope);
                ps.setTimestamp(7, Timestamp.from(expiresAt));
                ps.setString(8, approverId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "审批授予未返回 ID（tenant=" + tenantId
                                        + ", operation=" + operationId + "）");
                    }
                    return rs.getString(1);
                }
            }
        });
        // ⚠️ 审计在审批事务提交<b>之后</b>写（P3-D07 §2.1 D-1）：
        //    审批写失败时不会留下「已批准」的错误审计；
        //    反之审计失败会向上抛 → 调用方中止（关键审计 fail-closed）。
        audit.recordRequired(tenantId, approverId, AuditPort.Actions.APPROVAL_GRANT,
                "approval", operationId,
                Map.of("requirement", requirement.name(),
                        "targetResource", targetResource,
                        "expiresAt", expiresAt.toString()));
        return approvalId;
    }

    @Override
    public @NonNull ApprovalOutcome consume(@NonNull String tenantId,
                                           @NonNull String operationId,
                                           @NonNull String argumentDigest,
@NonNull String targetResource,
                                     @NonNull String runId) {
        // ⚠️ firstConsume 区分「首次消费」与「幂等重放」：
        //    两者都返回 CONSUMED，但只有前者应留审计（否则崩溃恢复重放会产生 N 条重复痕迹）。
        boolean[] firstConsume = {false};
        ApprovalOutcome outcome = inTenantTx(tenantId, conn -> {
            // ① 锁 Run 校验取消（D-1：消费与取消检查原子）
            RunCancelView run = lockRun(conn, tenantId, runId);
            if (run == null || run.cancelled()) {
                if (log.isInfoEnabled()) {
                    log.info("审批消费被拒：Run 已取消或不存在（原子取消检查）: "
                            + "tenant={}, run={}, cancelled={}", tenantId, runId,
                            run == null ? "N/A" : run.cancelled());
                }
                return ApprovalOutcome.RUN_CANCELLED;
            }

            // ② 锁审批（FOR UPDATE，串行化同 operation 的并发消费）
            ApprovalRow a = lockApproval(conn, tenantId, operationId);
            if (a == null) {
                return ApprovalOutcome.NO_APPROVAL;
            }

            // ③ 状态校验
            if (a.state != ApprovalState.APPROVED) {
                return switch (a.state) {
                    case REJECTED -> ApprovalOutcome.REJECTED;
                    case EXPIRED -> ApprovalOutcome.EXPIRED;
                    case CONSUMED -> ApprovalOutcome.CONSUMED; // 幂等命中
                    default -> ApprovalOutcome.NOT_APPROVED;    // PENDING 等
                };
            }

            // ④ 有效期校验（DB 触发器也置 EXPIRED，但应用层先判，避免无谓更新）
            if (a.expiresAt.isBefore(Instant.now())) {
                markExpired(conn, tenantId, operationId);
                return ApprovalOutcome.EXPIRED;
            }

            // ⑤ 绑定防篡改（D-2）
            if (!a.argumentDigest.equals(argumentDigest)) {
                if (log.isWarnEnabled()) {
                    log.warn("审批消费被拒：参数摘要不符（防篡改）: operation={}",
                            operationId);
                }
                return ApprovalOutcome.ARGUMENT_MISMATCH;
            }
            if (!a.targetResource.equals(targetResource)) {
                if (log.isWarnEnabled()) {
                    log.warn("审批消费被拒：目标资源不符: operation={}", operationId);
                }
                return ApprovalOutcome.TARGET_MISMATCH;
            }

            // ⑥ 标记消费（幂等：已消费则跳过更新）
            if (a.state != ApprovalState.CONSUMED) {
                markConsumed(conn, tenantId, operationId);
                firstConsume[0] = true;
            }
            if (log.isInfoEnabled()) {
                log.info("审批消费成功: tenant={}, operation={}", tenantId, operationId);
            }
            return ApprovalOutcome.CONSUMED;
        });

        // ⚠️ 只在<b>首次消费</b>时留痕（幂等重放不重复记）：
        //    崩溃恢复场景会重放 consume，若每次都记审计会产生 N 条重复痕迹，
        //    反而让「消费了几次」这个问题无法回答。
        if (outcome == ApprovalOutcome.CONSUMED && firstConsume[0]) {
            audit.recordRequired(tenantId, "worker", AuditPort.Actions.APPROVAL_CONSUME,
                    "approval", operationId,
                    Map.of("runId", runId, "targetResource", targetResource));
        }
        return outcome;
    }

    @Override
    public void revoke(@NonNull String tenantId,
                       @NonNull String operationId,
                       @NonNull String approverId,
                       @NonNull String reason) {
        int updated = inTenantTx(tenantId, conn -> {
            // ⚠️ 只撤销尚未消费的审批（已消费不可撤销，须走对账）
            String sql = """
                    UPDATE approval
                    SET state = 'REJECTED', approver_id = ?, decided_at = now()
                    WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                      AND state = 'APPROVED'
                    """;
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, approverId);
                ps.setString(2, tenantId);
                ps.setString(3, operationId);
                affected = ps.executeUpdate();
            }
            return affected;
        });
        if (log.isInfoEnabled()) {
            log.info("审批撤销: operation={}, 影响行={}, 原因={}", operationId, updated, reason);
        }
        // ⚠️ 只在<b>确实撤销</b>时留痕：updated == 0 说明已是终态，
        //    记一条「撤销」审计会污染追溯（让人以为发生过一次撤销）。
        if (updated > 0) {
            audit.recordRequired(tenantId, approverId, AuditPort.Actions.APPROVAL_REVOKE,
                    "approval", operationId, Map.of("reason", reason));
        }
    }

    @Override
    public @NonNull Optional<ApprovalView> findActive(@NonNull String tenantId,
                                                     @NonNull String operationId) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT approval_id::text, state::text, requirement::text,
                           expires_at, approver_id
                    FROM approval
                    WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                      AND state IN ('APPROVED', 'PENDING')
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    ApprovalOutcome state = switch (rs.getString("state")) {
                        case "APPROVED" -> ApprovalOutcome.CONSUMED; // 有效待消费
                        case "PENDING" -> ApprovalOutcome.NOT_APPROVED;
                        default -> ApprovalOutcome.NOT_APPROVED;
                    };
                    Timestamp exp = rs.getTimestamp("expires_at");
                    String approver = rs.getString("approver_id");
                    return Optional.of(new ApprovalView(
                            rs.getString("approval_id"),
                            operationId,
                            state,
                            rs.getString("requirement"),
                            exp == null ? null : exp.toInstant(),
                            rs.wasNull() ? null : approver));
                }
            }
        });
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private @Nullable RunCancelView lockRun(Connection conn, String tenantId, String runId)
            throws SQLException {
        String sql = """
                SELECT state::text, cancel_requested_at
                FROM run
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String state = rs.getString("state");
                Timestamp cancel = rs.getTimestamp("cancel_requested_at");
                boolean cancelled = "CANCELLED".equals(state)
                        || (cancel != null && !rs.wasNull());
                return new RunCancelView(cancelled);
            }
        }
    }

    private @Nullable ApprovalRow lockApproval(Connection conn, String tenantId,
                                              String operationId) throws SQLException {
        String sql = """
                SELECT state::text, argument_digest, target_resource, expires_at
                FROM approval
                WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ApprovalRow(
                        ApprovalState.valueOf(rs.getString("state")),
                        rs.getString("argument_digest"),
                        rs.getString("target_resource"),
                        rs.getTimestamp("expires_at").toInstant());
            }
        }
    }

    private void markConsumed(Connection conn, String tenantId, String operationId)
            throws SQLException {
        String sql = """
                UPDATE approval
                SET state = 'CONSUMED', consumed_at = now()
                WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            ps.executeUpdate();
        }
    }

    private void markExpired(Connection conn, String tenantId, String operationId)
            throws SQLException {
        String sql = """
                UPDATE approval
                SET state = 'EXPIRED', decided_at = now()
                WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            ps.executeUpdate();
        }
    }

    /** Run 取消视图。 */
    private record RunCancelView(boolean cancelled) {
    }

    /** 审批行快照。 */
    private record ApprovalRow(ApprovalState state,
                               String argumentDigest,
                               String targetResource,
                               Instant expiresAt) {
    }

    /** 审批状态（与 DB approval_state 枚举对齐）。 */
    private enum ApprovalState {
        PENDING, APPROVED, REJECTED, EXPIRED, CONSUMED
    }

    /**
     * 在租户事务中执行：复用外层连接或自开（P2-D05 连接所有权铁律）。
     */
    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "worker", Set.of(), 0L),
                action::run);
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T run(Connection conn) throws SQLException;
    }
}

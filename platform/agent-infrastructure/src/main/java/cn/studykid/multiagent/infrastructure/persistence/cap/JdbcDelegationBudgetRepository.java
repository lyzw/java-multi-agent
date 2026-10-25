package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.DelegationBudgetPort;

/**
 * {@link DelegationBudgetPort} 的 PostgreSQL 实现（第二十二批 · C22-1 · A2）。
 *
 * <p>⚠️ <b>核心是一条 LEFT JOIN 反查</b>：
 * 从 {@code budget_reservation} 出发，
 * 用 {@code operation_id = agent_invocation.invocation_id} 反查委派调用状态，
 * 并用 {@code NOT EXISTS tool_operation} 把「工具侧」排除掉 ——
 * 这样本端口与工具侧的两条对账查询构成<b>互补</b>的划分，互不重叠。
 *
 * <p>⚠️ <b>为什么用 LEFT JOIN 而不是 INNER JOIN</b>：
 * 委派终态提交若报 {@code NOT_FOUND}（记录不存在），闸门会判 UNKNOWN 并
 * <b>持有</b>预留 —— 此时<b>连 {@code agent_invocation} 行都没有</b>。
 * 用 INNER JOIN 就会把这一类<b>漏掉</b>，
 * 而它恰恰是最需要人工看一眼的那一类（额度被占、查无实据）。
 * LEFT JOIN 才能把它带出来（{@code invocationStatus} 为空）。
 */
public class JdbcDelegationBudgetRepository implements DelegationBudgetPort {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcDelegationBudgetRepository.class);

    /**
     * 反查持有中的委派侧预留。
     *
     * <p>⚠️ {@code NOT EXISTS tool_operation} 是「非工具侧」的判据：
     * 工具侧的预留由 {@code findOrphaned} / {@code findUnknownHeld} 负责，
     * 两边靠这一条互补，不重叠。
     */
    private static final String SQL_FIND_HELD = """
            SELECT r.reservation_id::text, r.operation_id::text, r.reserved_amount,
                   r.created_at, i.status, i.run_id::text
              FROM budget_reservation r
              LEFT JOIN agent_invocation i
                ON i.tenant_id = r.tenant_id
               AND i.invocation_id = r.operation_id
             WHERE r.tenant_id = ?::uuid
               AND r.state = 'RESERVED'
               AND NOT EXISTS (
                   SELECT 1 FROM tool_operation o
                    WHERE o.tenant_id = r.tenant_id
                      AND o.operation_id = r.operation_id
               )
             ORDER BY r.created_at ASC
            """;

    private static final String SQL_FIND_HELD_BY_OPERATION = """
            SELECT r.reservation_id::text, r.operation_id::text, r.reserved_amount,
                   r.created_at, i.status, i.run_id::text
              FROM budget_reservation r
              LEFT JOIN agent_invocation i
                ON i.tenant_id = r.tenant_id
               AND i.invocation_id = r.operation_id
             WHERE r.tenant_id = ?::uuid
               AND r.operation_id = ?::uuid
               AND r.state = 'RESERVED'
               AND NOT EXISTS (
                   SELECT 1 FROM tool_operation o
                    WHERE o.tenant_id = r.tenant_id
                      AND o.operation_id = r.operation_id
               )
             ORDER BY r.created_at ASC
            """;

    private final DataSource dataSource;

    public JdbcDelegationBudgetRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<HeldReservation> findHeldReservations(@NonNull String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<HeldReservation> out = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_HELD)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(readHeld(rs));
                            }
                        }
                    }
                    return out;
                });
    }

    @Override
    public @NonNull Optional<HeldReservation> findHeldReservation(@NonNull String tenantId,
                                                                   @NonNull String operationId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_FIND_HELD_BY_OPERATION)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, operationId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                            return Optional.of(readHeld(rs));
                        }
                    }
                });
    }

    /** 读一行持有中的预留。 */
    private static @NonNull HeldReservation readHeld(@NonNull ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        return new HeldReservation(
                rs.getString("reservation_id"),
                rs.getString("operation_id"),
                rs.getBigDecimal("reserved_amount"),
                parseStatus(status),
                rs.getString("run_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 解析委派调用状态。
     *
     * <p>⚠️ <b>无法识别的状态按「无锚点」处理</b>（返回 {@code null}），
     * 而不是当成某个终态 —— 未知状态绝不能被当成「已结束」而自动结算。
     */
    private static @Nullable AgentInvocationStatus parseStatus(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AgentInvocationStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("未知的委派调用状态，按无锚点处理（不自动处置）: {}", raw);
            return null;
        }
    }

    private static @NonNull TenantContext tenantContext(@NonNull String tenantId) {
        return new TenantContext(tenantId, "worker", Set.of(), 0L);
    }
}

package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.run.IllegalStateTransitionException;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import org.springframework.stereotype.Repository;

/**
 * {@link RunToolLedgerPort} 的 JDBC 实现（P3-D03）。
 *
 * <p><b>⚠️ 三条不变量</b>：
 * <ol>
 *   <li><b>状态机即契约</b>——所有迁移先经
 *       {@link ToolOperationState#requireTransitionTo} 校验，
 *       DB 触发器（{@code forbid_attempt_after_terminal} 等）是第二道防线；</li>
 *   <li><b>对账只认 UNKNOWN</b>——对任意状态收敛等于绕过状态机，
 *       会让「成功被改判」成为可能；</li>
 *   <li><b>连接所有权</b>——全部经
 *       {@link TransactionalConnectionHolder#inTransaction}（复用外层或自开，
 *       P2-D5 铁律：禁止直接 {@code dataSource.getConnection()}）。</li>
 * </ol>
 */
@Repository
public class JdbcToolOperationLedger implements RunToolLedgerPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcToolOperationLedger.class);

    private final DataSource dataSource;

    public JdbcToolOperationLedger(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Registration register(@NonNull String tenantId,
                                          @NonNull String runId,
                                          @NonNull String toolName,
                                          @NonNull String riskLevel,
                                          @NonNull String idempotencyKey,
                                          @NonNull String requestDigest,
                                          @NonNull Map<String, Object> arguments,
                                          long fencingToken) {
        return inTenantTx(tenantId, conn -> {
            // ① 幂等键命中 → 复用既有逻辑操作（重复调用不新建，FR-003）
            Optional<Registration> existing = findExisting(conn, tenantId, idempotencyKey);
            if (existing.isPresent()) {
                if (log.isDebugEnabled()) {
                    log.debug("账本幂等命中: key={}, operation={}",
                            idempotencyKey, existing.get().operationId());
                }
                return existing.get();
            }

            // ② 新建：PENDING → IN_FLIGHT（状态机校验，合法迁移）
            ToolOperationState.PENDING.requireTransitionTo(ToolOperationState.IN_FLIGHT);

            String sql = """
                    INSERT INTO tool_operation
                        (tenant_id, run_id, tool_name, risk_level, state,
                         idempotency_key, request_digest, tool_arguments, fencing_token)
                    VALUES (?::uuid, ?::uuid, ?, ?::tool_risk_level, 'IN_FLIGHT',
                            ?, ?, ?::jsonb, ?)
                    RETURNING operation_id::text
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setString(3, toolName);
                ps.setString(4, riskLevel);
                ps.setString(5, idempotencyKey);
                ps.setString(6, requestDigest);
                ps.setString(7, toJson(arguments));
                ps.setLong(8, fencingToken);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Registration(rs.getString(1),
                            ToolOperationState.IN_FLIGHT, 0);
                }
            }
        });
    }

    @Override
    public void recordResult(@NonNull String tenantId,
                             @NonNull String operationId,
                             @NonNull ToolOperationState result,
                             long fencingToken,
                             String errorClass,
                             String errorMessage) {
        if (result != ToolOperationState.SUCCEEDED
                && result != ToolOperationState.FAILED
                && result != ToolOperationState.UNKNOWN) {
            throw new IllegalArgumentException(
                    "attempt 结局只能是 SUCCEEDED/FAILED/UNKNOWN，收到: " + result);
        }
        inTenantTx(tenantId, conn -> {
            // 锁行读当前状态：与 attempt 插入同事务，防并发竞态
            ToolOperationState current = lockState(conn, tenantId, operationId);
            if (current != ToolOperationState.IN_FLIGHT) {
                throw new IllegalStateTransitionException(
                        "ToolOperation", current.name(), result.name(),
                        current.legalTargets());
            }
            current.requireTransitionTo(result);

            // attempt_number = 已有数 + 1（同事务内一致）
            int nextAttempt = countAttempts(conn, tenantId, operationId) + 1;
            insertAttempt(conn, tenantId, operationId, nextAttempt,
                    result, fencingToken, errorClass, errorMessage);

            // 操作状态推进（UNKNOWN/SUCCEEDED/FAILED）
            updateOperationState(conn, tenantId, operationId, result);
            return null;
        });
    }

    @Override
    public void reconcile(@NonNull String tenantId,
                          @NonNull String operationId,
                          @NonNull ToolOperationState reconciled,
                          @NonNull String source) {
        if (reconciled != ToolOperationState.RECONCILED_SUCCEEDED
                && reconciled != ToolOperationState.RECONCILED_ABSENT) {
            throw new IllegalArgumentException(
                    "对账目标只能是 RECONCILED_SUCCEEDED/RECONCILED_ABSENT，收到: " + reconciled);
        }
        inTenantTx(tenantId, conn -> {
            ToolOperationState current = lockState(conn, tenantId, operationId);
            // ⚠️ 只认 UNKNOWN：对任意状态收敛 = 绕过状态机
            current.requireTransitionTo(reconciled);

            String sql = """
                    UPDATE tool_operation
                    SET state = ?::tool_operation_state,
                        reconciliation_source = ?,
                        reconciled_at = now(),
                        updated_at = now()
                    WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, reconciled.name());
                ps.setString(2, source);
                ps.setString(3, tenantId);
                ps.setString(4, operationId);
                ps.executeUpdate();
            }
            if (log.isInfoEnabled()) {
                log.info("UNKNOWN 已对账收敛: operation={}, → {}, source={}",
                        operationId, reconciled, source);
            }
            return null;
        });
    }

    @Override
    public void manualDispose(@NonNull String tenantId,
                              @NonNull String operationId,
                              @NonNull String disposedBy,
                              @NonNull String note) {
        inTenantTx(tenantId, conn -> {
            ToolOperationState current = lockState(conn, tenantId, operationId);
            current.requireTransitionTo(ToolOperationState.MANUAL_DISPOSED);

            String sql = """
                    UPDATE tool_operation
                    SET state = 'MANUAL_DISPOSED',
                        reconciliation_source = ?,
                        manual_disposed_by = ?,
                        manual_disposed_at = now(),
                        updated_at = now()
                    WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, note);
                ps.setString(2, disposedBy);
                ps.setString(3, tenantId);
                ps.setString(4, operationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public @NonNull List<UnknownOperation> findUnknownOperations(@NonNull String tenantId) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT operation_id::text, run_id::text, tool_name,
                           idempotency_key, created_at
                    FROM tool_operation
                    WHERE tenant_id = ?::uuid AND state = 'UNKNOWN'
                    ORDER BY created_at
                    """;
            List<UnknownOperation> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp created = rs.getTimestamp("created_at");
                        out.add(new UnknownOperation(
                                rs.getString("operation_id"),
                                rs.getString("run_id"),
                                rs.getString("tool_name"),
                                rs.getString("idempotency_key"),
                                created.toInstant()));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public @NonNull Optional<Registration> findByIdempotencyKey(@NonNull String tenantId,
                                                                @NonNull String idempotencyKey) {
        return inTenantTx(tenantId, conn -> findExisting(conn, tenantId, idempotencyKey));
    }

    @Override
    public @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                        @NonNull String idempotencyKey) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT o.request_digest
                    FROM tool_operation o
                    WHERE o.tenant_id = ?::uuid AND o.idempotency_key = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, idempotencyKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private Optional<Registration> findExisting(Connection conn, String tenantId,
                                                String idempotencyKey) throws SQLException {
        String sql = """
                SELECT o.operation_id::text, o.state::text,
                       (SELECT count(*) FROM tool_operation_attempt a
                        WHERE a.tenant_id = o.tenant_id AND a.operation_id = o.operation_id)
                       AS attempt_count
                FROM tool_operation o
                WHERE o.tenant_id = ?::uuid AND o.idempotency_key = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Registration(
                        rs.getString("operation_id"),
                        ToolOperationState.valueOf(rs.getString("state")),
                        rs.getInt("attempt_count")));
            }
        }
    }

    /** FOR UPDATE 锁行读状态（与写同事务，防并发竞态）。 */
    private ToolOperationState lockState(Connection conn, String tenantId,
                                         String operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state::text FROM tool_operation "
              + "WHERE tenant_id = ?::uuid AND operation_id = ?::uuid FOR UPDATE")) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "逻辑操作不存在: " + operationId);
                }
                return ToolOperationState.valueOf(rs.getString(1));
            }
        }
    }

    private int countAttempts(Connection conn, String tenantId,
                              String operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM tool_operation_attempt "
              + "WHERE tenant_id = ?::uuid AND operation_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertAttempt(Connection conn, String tenantId, String operationId,
                               int attemptNumber, ToolOperationState state,
                               long fencingToken, String errorClass,
                               String errorMessage) throws SQLException {
        String sql = """
                INSERT INTO tool_operation_attempt
                    (tenant_id, operation_id, attempt_number, state, fencing_token,
                     completed_at, error_class, error_message, result_unknown)
                VALUES (?::uuid, ?::uuid, ?, ?::tool_operation_state, ?, now(), ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            ps.setInt(3, attemptNumber);
            ps.setString(4, state.name());
            ps.setLong(5, fencingToken);
            ps.setString(6, errorClass);
            ps.setString(7, errorClass == null ? null : errorMessage);
            ps.setBoolean(8, state == ToolOperationState.UNKNOWN);
            ps.executeUpdate();
        }
    }

    private void updateOperationState(Connection conn, String tenantId,
                                      String operationId,
                                      ToolOperationState state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tool_operation SET state = ?::tool_operation_state, "
              + "updated_at = now() WHERE tenant_id = ?::uuid AND operation_id = ?::uuid")) {
            ps.setString(1, state.name());
            ps.setString(2, tenantId);
            ps.setString(3, operationId);
            ps.executeUpdate();
        }
    }

    /**
     * 在租户事务中执行：复用外层连接或自开（P2-D5 连接所有权铁律）。
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

    private static String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append(quote(s));
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}

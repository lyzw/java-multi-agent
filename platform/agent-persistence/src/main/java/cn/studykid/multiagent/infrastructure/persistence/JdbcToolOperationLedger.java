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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.run.IllegalStateTransitionException;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcToolOperationLedger(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcToolOperationLedger(@NonNull DataSource dataSource,
                            @Nullable ScopedDataAccess scoped,
                            @Nullable AuthzShadowReporter reporter,
                            boolean shadowEnabled,
                            @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
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
            //    ⚠️ createdByThisCall=false：**不得据此再次执行工具** ——
            //    该逻辑操作可能已经产生过副作用（重复执行 = Sev-0）。
            Optional<Registration> existing = findExisting(conn, tenantId, idempotencyKey);
            if (existing.isPresent()) {
                if (log.isDebugEnabled()) {
                    log.debug("账本幂等命中: key={}, operation={}, state={}",
                            idempotencyKey, existing.get().operationId(), existing.get().state());
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
                            ToolOperationState.IN_FLIGHT, 0, fencingToken, true);
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
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志，不改变结果）。
        shadowCompare("tool_operation", tenantId, true);
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT tenant_id, operation_id::text, run_id::text, tool_name,
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
                        // ⚠️ 主体=查询租户；行归属=结果行真实 tenant_id（防同义反复）。
                        String rowTenant = rs.getString("tenant_id");
                        Timestamp created = rs.getTimestamp("created_at");
                        out.add(new UnknownOperation(
                                rs.getString("operation_id"),
                                rs.getString("run_id"),
                                rs.getString("tool_name"),
                                rs.getString("idempotency_key"),
                                created.toInstant()));
                        shadowTaint("tool_operation", tenantId, rowTenant);
                    }
                }
            }
            return out;
        });
    }

    @Override
    public @NonNull Optional<Registration> findByIdempotencyKey(@NonNull String tenantId,
                                                                @NonNull String idempotencyKey) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        return inTenantTx(tenantId, conn -> findExisting(conn, tenantId, idempotencyKey));
    }

    @Override
    public @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                        @NonNull String idempotencyKey) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT o.tenant_id, o.request_digest
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
                    // ⚠️ 主体=查询租户；行归属=结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    Optional<String> result = Optional.ofNullable(rs.getString("request_digest"));
                    shadowTaint("tool_operation", tenantId, rowTenant);
                    return result;
                }
            }
        });
    }

    @Override
    public @NonNull Optional<Registration> findByOperationId(@NonNull String tenantId,
                                                              @NonNull String operationId) {
        // ⚠️ 恢复方拿到的是**执行意图**（意图上记的是 invocationId = 账本 operationId），
        //    故恢复流程必须按 operationId 反查账本状态，
        //    才能判定「外部是否已执行、结果是否已知」（§5.3.4）。
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT o.tenant_id, o.operation_id::text, o.state::text, o.fencing_token,
                           (SELECT count(*) FROM tool_operation_attempt a
                            WHERE a.tenant_id = o.tenant_id AND a.operation_id = o.operation_id)
                           AS attempt_count
                    FROM tool_operation o
                    WHERE o.tenant_id = ?::uuid AND o.operation_id = ?::uuid
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    // ⚠️ 主体=查询租户；行归属=结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    Optional<Registration> result = Optional.of(new Registration(
                            rs.getString("operation_id"),
                            ToolOperationState.valueOf(rs.getString("state")),
                            rs.getInt("attempt_count"),
                            rs.getLong("fencing_token"),
                            false));
                    shadowTaint("tool_operation", tenantId, rowTenant);
                    return result;
                }
            }
        });
    }

    @Override
    public @NonNull Optional<Invocation> findInvocation(@NonNull String tenantId,
                                                      @NonNull String operationId) {
        // ⚠️ M1-4 / G2 恢复：按 operationId 反查原始调用，
        //    供 RESUME 重建 ToolCallRequest 重入闸门（不重跑模型）。
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT o.tenant_id, o.tool_name, o.tool_arguments::text, o.idempotency_key
                    FROM tool_operation o
                    WHERE o.tenant_id = ?::uuid AND o.operation_id = ?::uuid
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    // ⚠️ 主体=查询租户；行归属=结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    String toolName = rs.getString("tool_name");
                    String json = rs.getString("tool_arguments");
                    Map<String, Object> arguments = parseArguments(json);
                    String callId = callIdFromKey(rs.getString("idempotency_key"));
                    Optional<Invocation> result =
                            Optional.of(new Invocation(toolName, arguments, callId));
                    shadowTaint("tool_operation", tenantId, rowTenant);
                    return result;
                }
            }
        });
    }

    /** 解析 tool_arguments jsonb 文本为参数 Map（失败回退空，绝不抛异常中断恢复）。 */
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(json,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("解析 tool_arguments 失败，回退空参数: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 从复合幂等键反解 callId（键值末段），用于重建与首次相同的幂等键（§5.3.4）。 */
    private String callIdFromKey(String composite) {
        if (composite == null || composite.isBlank()) {
            return null;
        }
        String keyValue = composite.contains(":")
                ? composite.substring(composite.indexOf(':') + 1)
                : composite;
        String[] parts = keyValue.split("/");
        return parts.length == 0 ? null
                : cn.studykid.multiagent.core.idempotency.IdempotencyKey.decodePart(
                        parts[parts.length - 1]);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private Optional<Registration> findExisting(Connection conn, String tenantId,
                                                String idempotencyKey) throws SQLException {
        String sql = """
                SELECT o.tenant_id, o.operation_id::text, o.state::text, o.fencing_token,
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
                // ⚠️ 主体=查询租户；行归属=结果行真实 tenant_id（防同义反复）。
                //    注：本方法亦被 register（写路径）复用，染色仅观测、不改变写行为。
                String rowTenant = rs.getString("tenant_id");
                Optional<Registration> result = Optional.of(new Registration(
                        rs.getString("operation_id"),
                        ToolOperationState.valueOf(rs.getString("state")),
                        rs.getInt("attempt_count"),
                        rs.getLong("fencing_token"),
                        false));
                shadowTaint("tool_operation", tenantId, rowTenant);
                return result;
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
        // ⚠️ 制表符同 \n/\r：模型生成的工具参数里极常见，PG 的 jsonb 一律拒绝裸控制字符。
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被 {@code @Repository} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     * 若把行租户当作主体，策略引擎必然算出 {@code TenantOnly(行租户)}，逐行比对恒通过 ——
     * 染色会退化成「永远 0 违例」的装饰。
     *
     * @param subjectTenantId 发起查询的租户（主体）
     * @param rowTenantId 结果行的真实租户（不可用查询绑定租户代替，否则是同义反复）
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        // 哨兵行可由「行租户 == 哨兵租户」直接推断，无需调用方另行声明。
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}

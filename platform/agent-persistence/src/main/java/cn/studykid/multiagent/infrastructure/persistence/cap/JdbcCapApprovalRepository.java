package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

/**
 * {@link CapApprovalPort} 的 PostgreSQL 实现（W-PERM-P3 · §5.3.1 / §5.3.3）。
 *
 * <p>⚠️ <b>预占是 CAS，不是「先查后改」</b>：
 * {@code UPDATE ... WHERE approval_state = 'PENDING' AND expires_at > now()
 * AND effective_args_hash = ? AND closure_fingerprint = ? AND authz_version = ?}。
 * <ul>
 *   <li>并发预占同一审批时只有一个能更新成功（独占）；</li>
 *   <li>绑定项是<b>更新前置条件</b>，不是「更新后再校验」——
 *       后者会在「校验通过 → 更新生效」之间留窗口。</li>
 * </ul>
 *
 * <p>⚠️ <b>不写 {@code approval_scope_hash} / {@code effective_args_hash}</b>：
 * 这两个哈希在授予时确定，消费时只做比对。
 * 若允许消费侧改写，就等于「先改绑定再通过校验」——
 * 防参数替换重放将形同虚设。
 *
 * <p>⚠️ <b>P1 影子态：只观测读路径</b>：CAP 审批。**3 处读查询**（findById / findByTool / findByInvocation）已接影子；
 * 写路径（grant / requestHumanApproval / reserve / consume / cancel）用 {@code INSERT…RETURNING}，
 * 而 {@code RETURNING} 同样走 {@code executeQuery} —— ⚠️ <b>它是写路径，不接影子钩子</b>
 * （否则会把写事务误记成读观测）。写隔离由 RLS {@code WITH CHECK} 保证，属 P2 范围。
 */
public class JdbcCapApprovalRepository implements CapApprovalPort {
    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_approval";


    private static final String COLUMNS = """
            approval_id::text, tenant_id::text, principal_id, run_id::text,
            invocation_id, operation_id::text, tool_id, target_resource,
            approval_scope_hash, scope_definition, effective_args_hash,
            closure_fingerprint, authz_version, approver_id, approver_scope,
            decided_at, expires_at, approval_state::text, terminal_status,
            cancel_reason, created_at, updated_at
            """;

    private static final String SQL_INSERT = """
            INSERT INTO cap_approval
                (tenant_id, principal_id, tool_id, target_resource,
                 approval_scope_hash, scope_definition, effective_args_hash,
                 closure_fingerprint, authz_version, approver_id, approver_scope,
                 decided_at, expires_at, approval_state, created_at, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            RETURNING approval_id::text
            """;

    /**
     * 登记「待人工决策」的审批（M3 / G1）。
     *
     * <p>⚠️ {@code approval_state = 'AWAITING_HUMAN'}，{@code approver_id} 与
     * {@code decided_at} 均为 {@code NULL}（人还没批）。
     */
    private static final String SQL_INSERT_AWAITING_HUMAN = """
            INSERT INTO cap_approval
                (tenant_id, principal_id, tool_id, target_resource,
                 approval_scope_hash, scope_definition, effective_args_hash,
                 closure_fingerprint, authz_version, approver_scope,
                 expires_at, approval_state, created_at, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, 'AWAITING_HUMAN', ?, ?)
            RETURNING approval_id::text
            """;

    // ⚠️⚠️ 下面 SQL 里的「SELECT + 反斜杠 s」**不是笔误**：
    //   Java 文本块会<b>剥掉每行的行尾空白</b>，
    //   故 `SELECT ` + COLUMNS 会拼成 `SELECTapproval_id::text` —— 语法错误，
    //   而报错信息（syntax error at or near "SELECTapproval_id"）完全看不出根因。
    //   反斜杠 s 是「保留一个空格」的标准转义。
    private static final String SQL_FIND_BY_ID = """
            SELECT \s""" + COLUMNS + """
            FROM cap_approval
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
            """;

    private static final String SQL_FIND_BY_TOOL = """
            SELECT \s""" + COLUMNS + """
            FROM cap_approval
            WHERE tenant_id = ?::uuid AND tool_id = ?
            ORDER BY created_at ASC, approval_id ASC
            """;

    private static final String SQL_FIND_BY_INVOCATION = """
            SELECT \s""" + COLUMNS + """
            FROM cap_approval
            WHERE tenant_id = ?::uuid AND invocation_id = ?
            ORDER BY created_at ASC
            """;

    /** CAS 预占：绑定项全部作为前置条件。 */
    private static final String SQL_RESERVE = """
            UPDATE cap_approval
            SET approval_state = 'RESERVED',
                invocation_id = ?,
                operation_id = ?::uuid,
                run_id = COALESCE(run_id, ?::uuid),
                reserved_at = now(),
                updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND approval_state = 'PENDING'
              AND expires_at > now()
              AND effective_args_hash = ?
              AND closure_fingerprint = ?
              AND authz_version = ?
            """;

    private static final String SQL_MARK_INTENT = """
            UPDATE cap_approval
            SET approval_state = 'INTENT_SUBMITTED', intent_submitted_at = now(), updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND invocation_id = ? AND approval_state = 'RESERVED'
            """;

    private static final String SQL_MARK_INTENT_IDEMPOTENT = """
            UPDATE cap_approval
            SET updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND invocation_id = ? AND approval_state IN ('INTENT_SUBMITTED', 'CONSUMED')
            """;

    private static final String SQL_CONSUME = """
            UPDATE cap_approval
            SET approval_state = 'CONSUMED', terminal_status = ?,
                consumed_at = now(), updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND invocation_id = ? AND approval_state = 'INTENT_SUBMITTED'
            """;

    private static final String SQL_CONSUME_IDEMPOTENT = """
            UPDATE cap_approval
            SET updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND invocation_id = ? AND approval_state = 'CONSUMED'
            """;

    /** 取消：任一非终态 → CANCELLED（幂等：已取消则不产生状态迁移）。 */
    private static final String SQL_CANCEL = """
            UPDATE cap_approval
            SET approval_state = 'CANCELLED', cancel_reason = ?,
                cancelled_at = now(), updated_at = now()
            WHERE tenant_id = ?::uuid AND approval_id = ?::uuid
              AND invocation_id = ?
              AND approval_state IN ('PENDING', 'RESERVED', 'INTENT_SUBMITTED')
            """;

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcCapApprovalRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    JdbcCapApprovalRepository(@NonNull DataSource dataSource,
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
    public @NonNull String grant(@NonNull CapApprovalGrant grant) {
        Instant now = Instant.now();
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(grant.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, grant.tenantId());
                        ps.setString(2, grant.principalId());
                        ps.setString(3, grant.toolId());
                        ps.setString(4, grant.targetResource());
                        ps.setString(5, grant.approvalScopeHash());
                        ps.setString(6, grant.scopeDefinition());
                        ps.setString(7, grant.effectiveArgsHash());
                        ps.setString(8, grant.closureFingerprint());
                        ps.setLong(9, grant.authzVersion());
                        ps.setString(10, grant.approverId());
                        ps.setString(11, grant.approverScope());
                        ps.setTimestamp(12, Timestamp.from(now));
                        ps.setTimestamp(13, Timestamp.from(grant.expiresAt()));
                        ps.setTimestamp(14, Timestamp.from(now));
                        ps.setTimestamp(15, Timestamp.from(now));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("审批授予失败：未返回 approval_id");
                            }
                            return rs.getString(1);
                        }
                    }
                });
    }

    /**
     * 登记待人工决策的审批（M3 / G1 · 运行时 HITL）。
     *
     * <p>⚠️ <b>与 {@link #grant} 的三处关键差别</b>：
     * <ol>
     *   <li>{@code approval_state = 'AWAITING_HUMAN'}（<b>不是</b> PENDING）——
     *       PENDING 意为「已批准、待消耗」，混淆即等于「未批准的副作用被执行」；</li>
     *   <li>{@code approver_id} 写 <b>NULL</b>（人还没批）；</li>
     *   <li>{@code decided_at} 写 <b>NULL</b>（还没决定）。</li>
     * </ol>
     */
    @Override
    public @NonNull String requestHumanApproval(@NonNull CapApprovalRequest request) {
        Instant now = Instant.now();
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(request.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_AWAITING_HUMAN)) {
                        ps.setString(1, request.tenantId());
                        ps.setString(2, request.principalId());
                        ps.setString(3, request.toolId());
                        ps.setString(4, request.targetResource());
                        ps.setString(5, request.approvalScopeHash());
                        ps.setString(6, request.scopeDefinition());
                        ps.setString(7, request.effectiveArgsHash());
                        ps.setString(8, request.closureFingerprint());
                        ps.setLong(9, request.authzVersion());
                        ps.setString(10, request.approverScope());
                        ps.setTimestamp(11, Timestamp.from(request.expiresAt()));
                        ps.setTimestamp(12, Timestamp.from(now));
                        ps.setTimestamp(13, Timestamp.from(now));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException(
                                        "登记待人工审批失败：未返回 approval_id");
                            }
                            return rs.getString(1);
                        }
                    }
                });
    }

    @Override
    public @NonNull ReservationOutcome reserve(@NonNull String tenantId,
                                                @NonNull String approvalId,
                                                @NonNull String invocationId,
                                                @NonNull String operationId,
                                                @Nullable String runId,
                                                @NonNull String effectiveArgsHash,
                                                @NonNull String closureFingerprint,
                                                long authzVersion) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_RESERVE)) {
                        ps.setString(1, invocationId);
                        ps.setString(2, operationId);
                        if (runId == null) {
                            ps.setNull(3, java.sql.Types.OTHER);
                        } else {
                            ps.setString(3, runId);
                        }
                        ps.setString(4, tenantId);
                        ps.setString(5, approvalId);
                        ps.setString(6, effectiveArgsHash);
                        ps.setString(7, closureFingerprint);
                        ps.setLong(8, authzVersion);
                        return ps.executeUpdate() > 0;
                    }
                });
        if (Boolean.TRUE.equals(updated)) {
            return ReservationOutcome.RESERVED;
        }
        // ⚠️ 未更新成功 → 读回真实状态分类。
        //    不可凭「没更新成功」就断言是「被他人预占」—— 也可能是过期/已消费/绑定不符。
        return classify(tenantId, approvalId, invocationId);
    }

    /**
     * 读回审批并分类预占失败的原因。
     */
    private @NonNull ReservationOutcome classify(@NonNull String tenantId,
                                                 @NonNull String approvalId,
                                                 @NonNull String invocationId) {
        Optional<CapApproval> found = findById(tenantId, approvalId);
        if (found.isEmpty()) {
            return ReservationOutcome.NOT_FOUND;
        }
        CapApproval approval = found.get();
        if (!approval.expiresAt().isAfter(Instant.now())) {
            return ReservationOutcome.EXPIRED;
        }
        return switch (approval.state()) {
            case PENDING -> ReservationOutcome.NOT_FOUND;
            case RESERVED -> approval.invocationId() != null
                    && approval.invocationId().equals(invocationId)
                    ? ReservationOutcome.ALREADY_RESERVED_BY_SAME
                    : ReservationOutcome.ALREADY_RESERVED_BY_OTHER;
            case INTENT_SUBMITTED -> ReservationOutcome.INTENT_SUBMITTED;
            case CONSUMED -> ReservationOutcome.ALREADY_CONSUMED;
            case EXPIRED -> ReservationOutcome.EXPIRED;
            case CANCELLED -> ReservationOutcome.CANCELLED;
            // ⚠️ 待人工决策：尚未有人批准，**不可被预占**。
            //    与 NOT_FOUND 区分开（前者是「等一等」，后者是「没有」）。
            case AWAITING_HUMAN -> ReservationOutcome.AWAITING_HUMAN_DECISION;
        };
    }

    @Override
    public boolean markIntentSubmitted(@NonNull String tenantId,
                                        @NonNull String approvalId,
                                        @NonNull String invocationId) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_INTENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, approvalId);
                        ps.setString(3, invocationId);
                        if (ps.executeUpdate() > 0) {
                            return Boolean.TRUE;
                        }
                    }
                    // ⚠️ 幂等：已迁移过（INTENT_SUBMITTED / CONSUMED）视为成功
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_INTENT_IDEMPOTENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, approvalId);
                        ps.setString(3, invocationId);
                        return ps.executeUpdate() > 0;
                    }
                });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean consume(@NonNull String tenantId,
                           @NonNull String approvalId,
                           @NonNull String invocationId,
                           @NonNull String terminalStatus) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_CONSUME)) {
                        ps.setString(1, terminalStatus);
                        ps.setString(2, tenantId);
                        ps.setString(3, approvalId);
                        ps.setString(4, invocationId);
                        if (ps.executeUpdate() > 0) {
                            return Boolean.TRUE;
                        }
                    }
                    try (PreparedStatement ps = conn.prepareStatement(SQL_CONSUME_IDEMPOTENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, approvalId);
                        ps.setString(3, invocationId);
                        return ps.executeUpdate() > 0;
                    }
                });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean cancel(@NonNull String tenantId,
                          @NonNull String approvalId,
                          @NonNull String invocationId,
                          @NonNull String reason) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_CANCEL)) {
                        ps.setString(1, reason);
                        ps.setString(2, tenantId);
                        ps.setString(3, approvalId);
                        ps.setString(4, invocationId);
                        return ps.executeUpdate() > 0;
                    }
                });
        // ⚠️ 已取消 / 已被消费 → 返回 false（幂等，不报错）
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public @NonNull Optional<CapApproval> findById(@NonNull String tenantId,
                                                    @NonNull String approvalId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_ID)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, approvalId);
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                        shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                        return Optional.of(map(rs));
                        }
                    }
                });
    }

    @Override
    public @NonNull List<CapApproval> findByTool(@NonNull String tenantId,
                                                  @NonNull String toolId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapApproval> out = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_TOOL)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, toolId);
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                                out.add(map(rs));
                            }
                        }
                    }
                    return out;
                });
    }

    @Override
    public @NonNull Optional<CapApproval> findByInvocation(@NonNull String tenantId,
                                                            @NonNull String invocationId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_INVOCATION)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                        shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                        return Optional.of(map(rs));
                        }
                    }
                });
    }

    private static CapApproval map(ResultSet rs) throws SQLException {
        return new CapApproval(
                rs.getString("approval_id"),
                rs.getString("tenant_id"),
                rs.getString("principal_id"),
                rs.getString("run_id"),
                rs.getString("invocation_id"),
                rs.getString("operation_id"),
                rs.getString("tool_id"),
                rs.getString("target_resource"),
                rs.getString("approval_scope_hash"),
                rs.getString("scope_definition"),
                rs.getString("effective_args_hash"),
                rs.getString("closure_fingerprint"),
                rs.getLong("authz_version"),
                rs.getString("approver_id"),
                rs.getString("approver_scope"),
                rs.getTimestamp("decided_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                CapApproval.ApprovalState.valueOf(rs.getString("approval_state")),
                rs.getString("terminal_status"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    /** 生成新的审批 id（供测试与补偿任务构造使用）。 */
    public static @NonNull String newApprovalId() {
        return UUID.randomUUID().toString();
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
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

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。
     *
     * @param hasTenantPredicate 该查询的 SQL 是否真的带了显式租户谓词（必须如实填写）。
     */
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
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}

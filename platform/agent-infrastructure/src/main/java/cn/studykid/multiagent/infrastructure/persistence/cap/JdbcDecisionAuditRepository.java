package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * {@link DecisionAuditPort} 的 PostgreSQL 实现（§5.1 / §7.4 / 08 §7 · W-PERM-P5）。
 *
 * <p>⚠️ <b>本表不存任何业务参数</b>：
 * 列集合里只有 id、决策字段与上下文标识，
 * 「日志不含明文凭据与不必要的敏感参数」（08 §7 第 2 条 / NFR-06）
 * 因此不需要任何过滤逻辑 —— 没有可泄漏的字段。
 *
 * <p>⚠️ <b>写入是 fail-closed</b>：{@link #append} 失败直接抛异常，
 * 由 {@code DecisionAuditService} 按「是否已发生副作用」分级处置。
 */
public class JdbcDecisionAuditRepository implements DecisionAuditPort {

    private static final String COLUMNS = """
            decision_id, tenant_id::text, run_id::text, invocation_id,
            authz_decision, exec_decision, exec_status, ledger_status, reason_code,
            authz_version, closure_fingerprint, config_publish_seq, release_id,
            principal_id, tool_id, decision_kind, phase, created_at
            """;

    private static final String SQL_INSERT = """
            INSERT INTO cap_decision_log
                (tenant_id, run_id, invocation_id, authz_decision, exec_decision,
                 exec_status, ledger_status, reason_code, authz_version,
                 closure_fingerprint, config_publish_seq, release_id, principal_id,
                 tool_id, decision_kind, phase, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_BY_INVOCATION = "SELECT " + COLUMNS
            + " FROM cap_decision_log WHERE tenant_id = ?::uuid AND invocation_id = ?"
            + " ORDER BY created_at";

    private static final String SQL_BY_RUN = "SELECT " + COLUMNS
            + " FROM cap_decision_log WHERE tenant_id = ?::uuid AND run_id = ?::uuid"
            + " ORDER BY created_at";

    /**
     * 覆盖率统计。
     *
     * <p>⚠️ 「不完整」的判定口径：
     * <ul>
     *   <li>四字段任一为空（DB 已 NOT NULL，这里是<b>双保险</b>，防止将来放宽约束后指标失效）；</li>
     *   <li>DENY 却报 SUCCESS —— 这是一条<b>无用的拒绝记录</b>，
     *       08 §7「非笼统 DENY 占比 100%」正是要排除它。</li>
     * </ul>
     */
    private static final String SQL_COVERAGE = """
            SELECT
                count(*) AS total,
                count(*) FILTER (WHERE decision_kind = 'INVOCATION') AS invocations,
                count(*) FILTER (WHERE authz_decision = 'DENY') AS denies,
                count(*) FILTER (WHERE authz_decision = 'DENY'
                                   AND (reason_code IS NULL OR reason_code = ''
                                        OR reason_code = 'SUCCESS')) AS non_specific,
                count(*) FILTER (WHERE authz_decision IS NULL OR exec_decision IS NULL
                                   OR exec_status IS NULL OR ledger_status IS NULL
                                   OR reason_code IS NULL OR reason_code = '') AS incomplete
            FROM cap_decision_log
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcDecisionAuditRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull DecisionAuditEntry entry) {
        TransactionalConnectionHolder.inTransaction(
                dataSource, context(entry.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, entry.tenantId());
                        if (entry.runId() == null) {
                            ps.setNull(2, Types.OTHER);
                        } else {
                            ps.setString(2, entry.runId().toString());
                        }
                        ps.setString(3, entry.invocationId());
                        ps.setString(4, entry.authzDecision().name());
                        ps.setString(5, entry.execDecision().name());
                        ps.setString(6, entry.execStatus().name());
                        ps.setString(7, entry.ledgerStatus().name());
                        ps.setString(8, entry.reasonCode().name());
                        setNullableLong(ps, 9, entry.authzVersion());
                        ps.setString(10, entry.closureFingerprint());
                        setNullableLong(ps, 11, entry.configPublishSeq());
                        setNullableLong(ps, 12, entry.releaseId());
                        ps.setString(13, entry.principalId());
                        ps.setString(14, entry.toolId());
                        ps.setString(15, entry.decisionKind().name());
                        ps.setString(16, entry.phase());
                        ps.setTimestamp(17, Timestamp.from(entry.createdAt()));
                        ps.executeUpdate();
                    }
                    return null;
                });
    }

    @Override
    @NonNull
    public List<DecisionAuditEntry> findByInvocation(@NonNull String tenantId,
                                                      @NonNull String invocationId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_BY_INVOCATION)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<DecisionAuditEntry> entries = new ArrayList<>();
                            while (rs.next()) {
                                entries.add(map(rs));
                            }
                            return entries;
                        }
                    }
                });
    }

    @Override
    @NonNull
    public List<DecisionAuditEntry> findByRun(@NonNull String tenantId, @NonNull String runId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_BY_RUN)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<DecisionAuditEntry> entries = new ArrayList<>();
                            while (rs.next()) {
                                entries.add(map(rs));
                            }
                            return entries;
                        }
                    }
                });
    }

    @Override
    @NonNull
    public DecisionAuditCoverage coverage(@NonNull String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_COVERAGE)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return new DecisionAuditCoverage(0, 0, 0, 0, 0);
                            }
                            return new DecisionAuditCoverage(
                                    rs.getLong("total"),
                                    rs.getLong("invocations"),
                                    rs.getLong("denies"),
                                    rs.getLong("non_specific"),
                                    rs.getLong("incomplete"));
                        }
                    }
                });
    }

    /**
     * 近期拒绝记录查询（管理面 P4 运行监控）。
     *
     * <p>⚠️ <b>只取 DENY</b>：ALLOW 行不是告警素材，混入只会放大结果集
     * 并让调用方各自再过滤一遍（口径分散的典型来源）。
     *
     * <p>⚠️ <b>排序是 {@code created_at DESC, decision_id DESC} 全序</b>：
     * 只按 {@code created_at} 排序时，同一毫秒的多条拒绝顺序不确定，
     * 两次相邻查询可能给出不同排列 —— 那会让「告警列表是否变化」无法判断。
     * 加 {@code decision_id} 作为第二排序键可保证全序稳定。
     */
    private static final String SQL_RECENT_DENIES = "SELECT " + COLUMNS
            + " FROM cap_decision_log"
            + " WHERE tenant_id = ?::uuid AND authz_decision = 'DENY'"
            + " AND created_at >= ?"
            + " ORDER BY created_at DESC, decision_id DESC"
            + " LIMIT ?";

    @Override
    @NonNull
    public List<DecisionAuditEntry> findRecentDenies(@NonNull String tenantId,
                                                     @NonNull Instant since,
                                                     int limit) {
        if (since == null) {
            throw new IllegalArgumentException("since 不能为空：监控查询必须有窗口");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数");
        }
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_RECENT_DENIES)) {
                        ps.setString(1, tenantId);
                        ps.setTimestamp(2, Timestamp.from(since));
                        ps.setInt(3, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<DecisionAuditEntry> entries = new ArrayList<>();
                            while (rs.next()) {
                                entries.add(map(rs));
                            }
                            return entries;
                        }
                    }
                });
    }

    private static void setNullableLong(PreparedStatement ps, int index, @Nullable Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static DecisionAuditEntry map(ResultSet rs) throws SQLException {
        return new DecisionAuditEntry(
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("invocation_id"),
                AuthorizationDecision.Authorization.valueOf(rs.getString("authz_decision")),
                AuthorizationDecision.Execution.valueOf(rs.getString("exec_decision")),
                AuthorizationDecision.Status.valueOf(rs.getString("exec_status")),
                AuthorizationDecision.Ledger.valueOf(rs.getString("ledger_status")),
                reasonCode(rs),
                nullableLong(rs, "authz_version"),
                rs.getString("closure_fingerprint"),
                nullableLong(rs, "config_publish_seq"),
                nullableLong(rs, "release_id"),
                rs.getString("principal_id"),
                rs.getString("tool_id"),
                DecisionAuditEntry.Kind.valueOf(rs.getString("decision_kind")),
                rs.getString("phase"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 解析原因码。
     *
     * <p>⚠️ <b>未知码降级为 SUCCESS</b>：DB 只存文本，不做枚举约束；
     * 读到未知码时若抛异常，会让「读审计」这件事本身失败 ——
     * 那等于让一条坏数据把整段审计历史变得不可读。
     * 降级后该行仍会被 {@link #coverage} 的「非笼统 DENY」口径计入（若它是 DENY）。
     */
    @NonNull
    private static ReasonCode reasonCode(ResultSet rs) throws SQLException {
        ReasonCode parsed = ReasonCode.parse(rs.getString("reason_code"));
        return parsed == null ? ReasonCode.SUCCESS : parsed;
    }

    @Nullable
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ PLATFORM 级发布事件没有租户 —— 仍绑定零 UUID 哨兵，
     * 否则 {@code set_config} 绑定非 UUID 会让 {@code current_tenant_id()} 的类型转换直接报错。
     */
    private static TenantContext context(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /** PLATFORM 作用域的租户哨兵：零 UUID。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";
}

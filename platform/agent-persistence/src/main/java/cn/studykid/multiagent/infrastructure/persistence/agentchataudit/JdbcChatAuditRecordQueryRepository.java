package cn.studykid.multiagent.infrastructure.persistence.agentchataudit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 对话审计记录查询仓储（平台审计/效果评估角色，跨租户只读）。
 *
 * <p><b>⚠️ 必须用 BYPASSRLS 的 platform_ops 数据源装配</b>：{@code chat_audit_record}
 * 是 FORCE RLS 租户表，主数据源 app_agent 受 RLS 限制<b>读不到别的租户</b>；平台审计角色
 * 没有可绑的租户，强行绑定只会得到恒 0 行（静默失真，与记忆里的铁律一致）。
 * 本类<b>不挂 {@code @Repository}</b>：组件扫描若把它接到主数据源，跨租户页会静默空表。
 * 由组合根 {@code ChatAuditQueryConfiguration} 显式注入运维数据源并登记架构守卫白名单。
 *
 * <p><b>⚠️ 只读、不写 outbox、不参与 Worker</b>：本类只有 SELECT，绝不触发 Run 重放。
 *
 * <p><b>⚠️ 列表不拉 envelope 整列</b>：摘要列「用户问题预览」用
 * {@code envelope #>> '{request,message}'} 精确取标量并截断；完整原文只在
 * {@link #detail} 按 (source, audit_id) 单条取，防止列表页把全库原文读进内存。
 *
 * <p><b>⚠️ 两域合并的游标全序</b>：排序键 {@code (started_at, audit_id, source)}。
 * 两张表的 audit_id 理论可能重复，游标 id 编码成 {@code source|auditId} 保证全序；
 * decode 后按第一个 {@code |} 拆分（PageCursor 只把第一个分隔符前当时间）。
 */
public class JdbcChatAuditRecordQueryRepository implements ChatAuditRecordQueryPort {

    /** 两域合并列表：各分支列对齐后用 UNION ALL，统一排序 + 游标 + 探测下一页。 */
    private static final String LIST_SQL = """
            SELECT audit_id::text AS audit_id, trace_id, source, tenant_id, domain, principal_id,
                   endpoint, started_at, completed_at, duration_ms, terminal_state,
                   http_method, http_status, message_preview, rejection_reason
            FROM (
                SELECT audit_id, trace_id, 'RECORD' AS source, tenant_id::text AS tenant_id,
                       domain, principal_id, endpoint, started_at, completed_at, duration_ms,
                       terminal_state, http_method, http_status,
                       left(envelope #>> '{request,message}', 200) AS message_preview,
                       NULL::text AS rejection_reason
                FROM public.chat_audit_record
                WHERE 1=1 %s
                UNION ALL
                SELECT audit_id, trace_id, 'UNATTRIBUTED' AS source, NULL::text AS tenant_id,
                       NULL::text AS domain, NULL::text AS principal_id, endpoint, started_at,
                       NULL::timestamptz AS completed_at, NULL::bigint AS duration_ms,
                       'REJECTED' AS terminal_state, http_method, http_status,
                       NULL::text AS message_preview, rejection_reason
                FROM public.chat_audit_unattributed
                WHERE 1=1 %s
            ) u
            WHERE 1=1 %s
            ORDER BY started_at DESC, audit_id DESC, source DESC
            LIMIT ?
            """;

    private static final String DETAIL_RECORD = """
            SELECT audit_id::text, trace_id, tenant_id::text, domain, principal_id, endpoint,
                   started_at, completed_at, duration_ms, terminal_state, http_method, http_status,
                   config_version, NULL::text AS rejection_reason, envelope::text AS payload
            FROM public.chat_audit_record WHERE audit_id = ?::uuid
            """;

    private static final String DETAIL_UNATTRIBUTED = """
            SELECT audit_id::text, trace_id, NULL::text AS tenant_id, NULL::text AS domain,
                   NULL::text AS principal_id, endpoint, started_at, NULL::timestamptz AS completed_at,
                   NULL::bigint AS duration_ms, 'REJECTED' AS terminal_state, http_method,
                   http_status, 0::bigint AS config_version, rejection_reason,
                   metadata::text AS payload
            FROM public.chat_audit_unattributed WHERE audit_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcChatAuditRecordQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<Summary> list(@NonNull Query query) {
        if (query.limit() <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + query.limit());
        }
        PageCursor after = query.cursor() == null || query.cursor().isBlank()
                ? null : PageCursor.decode(query.cursor());

        StringBuilder recordWhere = new StringBuilder();
        StringBuilder unattrWhere = new StringBuilder();
        // ⚠️ UNION ALL 的占位符顺序：先 RECORD 分支全部、再 UNATTRIBUTED 分支全部，
        //    所以两条分支各自维护参数列表，最终先拼接 record 再拼接 unattr，不能按过滤项交错。
        List<Object> recordParams = new ArrayList<>();
        List<Object> unattrParams = new ArrayList<>();
        List<Object> outerParams = new ArrayList<>();

        // 来源裁剪：指定 RECORD / UNATTRIBUTED 时，另一分支用 1=0 关掉（仍保留 UNION 形状）。
        Source source = query.source();
        boolean includeRecord = source == null || source == Source.RECORD;
        boolean includeUnattributed = source == null || source == Source.UNATTRIBUTED;
        if (!includeRecord) {
            recordWhere.append(" AND 1=0");
        }
        if (!includeUnattributed) {
            unattrWhere.append(" AND 1=0");
        }

        appendFilters(recordWhere, recordParams, unattrWhere, unattrParams, query);

        StringBuilder outerWhere = new StringBuilder();
        if (after != null) {
            // 游标 id = "SOURCE|auditId"；拆出审计主键与来源，参与三元组全序比较。
            CursorParts parts = CursorParts.decode(after.id());
            outerWhere.append(" AND (started_at < ? OR (started_at = ? AND "
                    + "(audit_id < ?::uuid OR (audit_id = ?::uuid AND source < ?))))");
            outerParams.add(Timestamp.from(after.createdAt()));
            outerParams.add(Timestamp.from(after.createdAt()));
            outerParams.add(parts.auditId());
            outerParams.add(parts.auditId());
            outerParams.add(parts.source().name());
        }

        String sql = String.format(LIST_SQL, recordWhere, unattrWhere, outerWhere);
        List<Object> params = new ArrayList<>(recordParams.size() + unattrParams.size()
                + outerParams.size() + 1);
        params.addAll(recordParams);
        params.addAll(unattrParams);
        params.addAll(outerParams);
        params.add(query.limit() + 1);

        List<Summary> rows = new ArrayList<>(query.limit());
        try (Connection conn = openRead();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readSummary(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("查询对话审计记录列表失败", e);
        }

        String next = null;
        if (rows.size() > query.limit()) {
            rows.remove(rows.size() - 1);
            Summary last = rows.get(rows.size() - 1);
            next = new PageCursor(parseInstant(last.startedAt()),
                    CursorParts.encode(Source.valueOf(last.source()), last.auditId())).encode();
        }
        return Page.of(rows, next);
    }

    /**
     * 两域过滤条件分别追加。⚠️ tenantId / terminalState 只对 RECORD 域有意义
     * （UNATTRIBUTED 建连前无租户、终态恒 REJECTED），故只加在 RECORD 分支上；
     * 这与「按租户筛选时建连前失败记录不该出现」的语义一致。
     */
    private static void appendFilters(StringBuilder recordWhere, List<Object> recordParams,
                                      StringBuilder unattrWhere, List<Object> unattrParams,
                                      Query query) {
        if (query.tenantId() != null && !query.tenantId().isBlank()) {
            recordWhere.append(" AND tenant_id = ?::uuid");
            recordParams.add(query.tenantId());
        }
        if (query.terminalState() != null && !query.terminalState().isBlank()) {
            recordWhere.append(" AND terminal_state = ?");
            recordParams.add(query.terminalState());
        }
        if (query.endpoint() != null && !query.endpoint().isBlank()) {
            recordWhere.append(" AND endpoint = ?");
            unattrWhere.append(" AND endpoint = ?");
            recordParams.add(query.endpoint());
            unattrParams.add(query.endpoint());
        }
        if (query.from() != null) {
            recordWhere.append(" AND started_at >= ?");
            unattrWhere.append(" AND started_at >= ?");
            recordParams.add(Timestamp.from(query.from()));
            unattrParams.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            recordWhere.append(" AND started_at <= ?");
            unattrWhere.append(" AND started_at <= ?");
            recordParams.add(Timestamp.from(query.to()));
            unattrParams.add(Timestamp.from(query.to()));
        }
    }

    @Override
    public @Nullable Detail detail(@NonNull Source source, @NonNull String auditId) {
        String normalized = ensureUuid(auditId);
        String sql = source == Source.RECORD ? DETAIL_RECORD : DETAIL_UNATTRIBUTED;
        try (Connection conn = openRead();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp completed = rs.getTimestamp("completed_at");
                Long duration = rs.getObject("duration_ms", Long.class);
                Long configVersion = rs.getObject("config_version", Long.class);
                return new Detail(
                        source.name(),
                        rs.getString("audit_id"),
                        rs.getString("trace_id"),
                        rs.getString("tenant_id"),
                        rs.getString("domain"),
                        rs.getString("principal_id"),
                        rs.getString("endpoint"),
                        rs.getTimestamp("started_at").toInstant().toString(),
                        completed == null ? null : completed.toInstant().toString(),
                        duration == null ? 0L : duration,
                        rs.getString("terminal_state"),
                        rs.getString("http_method"),
                        rs.getInt("http_status"),
                        configVersion == null ? 0L : configVersion,
                        rs.getString("rejection_reason"),
                        rs.getString("payload"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询对话审计记录详情失败", e);
        }
    }

    private Connection openRead() throws SQLException {
        // BYPASSRLS 运维连接：不绑租户（绑了反而可能让审计视图被误判）。
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static Summary readSummary(ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        Long duration = rs.getObject("duration_ms", Long.class);
        return new Summary(
                rs.getString("audit_id"),
                rs.getString("trace_id"),
                rs.getString("source"),
                rs.getString("tenant_id"),
                rs.getString("domain"),
                rs.getString("principal_id"),
                rs.getString("endpoint"),
                rs.getTimestamp("started_at").toInstant().toString(),
                completed == null ? null : completed.toInstant().toString(),
                duration == null ? 0L : duration,
                rs.getString("terminal_state"),
                rs.getString("http_method"),
                rs.getInt("http_status"),
                rs.getString("message_preview"),
                rs.getString("rejection_reason"));
    }

    /** 拒绝非 UUID，避免 {@code ?::uuid} 抛 22P02 把 400 变成 500。 */
    private static @NonNull String ensureUuid(@NonNull String raw) {
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("auditId 不是合法 UUID: " + raw, e);
        }
    }

    private static @NonNull Instant parseInstant(@NonNull String raw) {
        return Instant.parse(raw);
    }

    /** 游标 id 的 source|auditId 编解码（审计 id 本身是 UUID，不含 '|'）。 */
    private record CursorParts(@NonNull Source source, @NonNull String auditId) {
        static @NonNull String encode(@NonNull Source source, @NonNull String auditId) {
            return source.name() + "|" + auditId;
        }

        static @NonNull CursorParts decode(@NonNull String raw) {
            int sep = raw.indexOf('|');
            if (sep <= 0 || sep == raw.length() - 1) {
                throw new IllegalArgumentException("游标内容非法（缺少来源分隔符）");
            }
            return new CursorParts(Source.valueOf(raw.substring(0, sep)), raw.substring(sep + 1));
        }
    }
}

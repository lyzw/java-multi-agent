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

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link ConfigReleasePort} 的 PostgreSQL 实现（§6.1 / 06 §1.7 · W-PERM-P0-T4）。
 *
 * <p>⚠️ <b>本类只做存储，<b>不含 CAS</b></b>：原子发布（版本行插入 + release
 * 记录 + 当前指针 CAS 同事务提交，§6.3）是 W-PERM-P5 的范围。
 * P0 刻意不实现 CAS —— 在发布语义定稿前固化并发协议比不实现更危险。
 *
 * <p>⚠️ <b>发布序号由 DB 序列分配</b>（{@code nextval('cap_config_publish_seq')}），
 * 禁止应用先读后加一（§6.3 第 2 条）。
 *
 * <p>⚠️ <b>PLATFORM 作用域需要越过租户 RLS 写策略</b>：
 * 本表的写策略刻意只放行「本租户的 TENANT 行」（见 V15 迁移注释），
 * 因此以应用角色写 PLATFORM 行会被 RLS 拒绝 —— 那是**预期行为**，
 * 平台级发布须由平台运维角色执行。异常信息在此显式化，
 * 避免「permission denied for table」这种远离成因的报错。
 */
public class JdbcConfigReleaseRepository implements ConfigReleasePort {

    /**
     * PLATFORM 作用域的租户哨兵：零 UUID。
     *
     * <p>必须<b>是合法 UUID</b>——见 {@link #tenantContext(String)} 的说明：
     * 绑非 UUID 会让 {@code current_tenant_id()} 的 {@code ::UUID} 转换直接报错。
     */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private static final String SQL_INSERT = """
            INSERT INTO cap_config_release
                (config_publish_seq, scope, tenant_id, object_versions,
                 aggregate_fingerprint, status, requester_id, approver_id,
                 effective_at, created_at)
            VALUES (nextval('cap_config_publish_seq'), ?, ?::uuid, ?::jsonb,
                    ?, ?::cap_release_status, ?, ?, ?, ?)
            RETURNING release_id, config_publish_seq, created_at
            """;

    private static final String SQL_FIND_BY_SEQ = """
            SELECT release_id, config_publish_seq, scope, tenant_id::text, object_versions::text,
                   aggregate_fingerprint, status, requester_id, approver_id,
                   effective_at, created_at
            FROM cap_config_release
            WHERE config_publish_seq = ?
            """;

    /**
     * 最近一条<b>已发布</b>的发布头。
     *
     * <p>⚠️ 必须先过滤 {@code status='PUBLISHED'} 再取 seq 最大者。
     * 「当前版本不以 max(seq) 选择」指的是<b>不得在全部行上</b>取 max(seq)
     * ——那会选中 PREPARING（未提交）或 ROLLED_BACK（仅审计旁路）的行。
     */
    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT release_id, config_publish_seq, scope, tenant_id::text, object_versions::text,
                   aggregate_fingerprint, status, requester_id, approver_id,
                   effective_at, created_at
            FROM cap_config_release
            WHERE status = 'PUBLISHED'
              AND scope = ?
              AND (tenant_id IS NULL OR tenant_id = ?::uuid)
            ORDER BY config_publish_seq DESC
            LIMIT 1
            """;

    /**
     * 按发布记录 id 读取（回滚协议用 · §6.3 第 6 条）。
     *
     * <p>⚠️ 不带租户过滤：{@code cap_config_release} 的<b>读</b>策略已放行
     * PLATFORM 行与本租户行（V15 分读写策略），租户边界由 DB 保证。
     */
    private static final String SQL_FIND_BY_ID = """
            SELECT release_id, config_publish_seq, scope, tenant_id::text, object_versions::text,
                   aggregate_fingerprint, status, requester_id, approver_id,
                   effective_at, created_at
            FROM cap_config_release
            WHERE release_id = ?
            """;

    /**
     * 标记为 ROLLED_BACK（§6.3 第 7 条 / 06 §3.2）。
     *
     * <p>⚠️ <b>「仍被指针指向」是前置条件</b>：
     * 否则会把一条早已不是当前的发布记录标记成已回滚 ——
     * 审计旁路指向错误的对象，比不标记更糟。
     *
     * <p>⚠️ <b>历史内容一字不改</b>：只改 status 列。
     * 对象版本行与发布记录本身都保留（06 §3.2「回滚不覆盖、不删除任何历史记录」）。
     */
    private static final String SQL_MARK_ROLLED_BACK = """
            UPDATE cap_config_release
               SET status = 'ROLLED_BACK'
             WHERE release_id = ?
               AND status = 'PUBLISHED'
               AND EXISTS (SELECT 1 FROM cap_config_pointer
                            WHERE current_release_id = ?)
            """;

    /**
     * 本租户发布历史（TENANT 作用域，含 ROLLED_BACK）。
     *
     * <p>⚠️ keyset 游标：{@code config_publish_seq} 由 DB 序列分配、
     * 全局唯一单调，是全序键；游标二元组 {@code (created_at, release_id)}
     * 与既有列表实现同口径（比较两个分量，防同刻行漏读）。
     *
     * <p>⚠️ 必须绑定<b>本租户</b>上下文：发布头行受 RLS 保护，
     * 且本查询刻意只取 TENANT 作用域（平台级行不属于租户时间线）。
     */
    private static final String SQL_LIST = """
            SELECT release_id, config_publish_seq, scope, tenant_id::text, object_versions::text,
                   aggregate_fingerprint, status, requester_id, approver_id,
                   effective_at, created_at
            FROM cap_config_release
            WHERE scope = 'TENANT'
              AND tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcConfigReleaseRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull ConfigRelease save(@NonNull ConfigRelease release) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(release.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, release.scope().name());
                        if (release.tenantId() == null) {
                            ps.setNull(2, java.sql.Types.OTHER);
                        } else {
                            ps.setString(2, release.tenantId());
                        }
                        ps.setString(3, release.objectVersions());
                        ps.setString(4, release.aggregateFingerprint());
                        ps.setString(5, release.status().name());
                        ps.setString(6, release.requesterId());
                        ps.setString(7, release.approverId());
                        if (release.effectiveAt() == null) {
                            ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(8, Timestamp.from(release.effectiveAt()));
                        }
                        ps.setTimestamp(9, Timestamp.from(release.createdAt()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("发布记录插入未返回结果");
                            }
                            return new ConfigRelease(
                                    rs.getLong("release_id"),
                                    rs.getLong("config_publish_seq"),
                                    release.scope(),
                                    release.tenantId(),
                                    release.objectVersions(),
                                    release.aggregateFingerprint(),
                                    release.status(),
                                    release.requesterId(),
                                    release.approverId(),
                                    release.effectiveAt(),
                                    rs.getTimestamp("created_at").toInstant());
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<ConfigRelease> findByReleaseId(@Nullable String tenantId,
                                                              long releaseId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_ID)) {
                        ps.setLong(1, releaseId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public boolean markRolledBack(@Nullable String tenantId, long releaseId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_ROLLED_BACK)) {
                        ps.setLong(1, releaseId);
                        ps.setLong(2, releaseId);
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<ConfigRelease> findByPublishSeq(@NonNull String tenantId,
                                                              long configPublishSeq) {
        // ⚠️ 必须绑定**该租户**的上下文：
        //    平台级（零 UUID）上下文看不到租户行，读取会静默返回空。
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_SEQ)) {
                        ps.setLong(1, configPublishSeq);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<ConfigRelease>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<ConfigRelease> findLatestPublished(ConfigRelease.@NonNull Scope scope,
                                                                 String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST_PUBLISHED)) {
                        ps.setString(1, scope.name());
                        ps.setString(2, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<ConfigRelease>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Page<ConfigRelease> listReleases(@NonNull String tenantId,
                                                     @Nullable String cursor,
                                                     int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_LIST);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (after != null) {
            // ⚠️ 全序游标：created_at 同刻的多行必须按 release_id 再区分
            sql.append("""
                     AND (created_at < ?
                          OR (created_at = ? AND release_id < ?))
                    """);
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, release_id DESC LIMIT ?");
        params.add(limit + 1);

        List<ConfigRelease> rows = new ArrayList<>();
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId),
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                            bind(ps, params);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    rows.add(map(rs));
                                }
                            }
                        }
                        String next = null;
                        if (rows.size() > limit) {
                            ConfigRelease last = rows.get(limit - 1);
                            rows.subList(limit, rows.size()).clear();
                            next = new PageCursor(last.createdAt(),
                                    String.valueOf(last.releaseId())).encode();
                        }
                        return Page.of(rows, next);
                    });
        } catch (RuntimeException e) {
            throw new IllegalStateException("查询发布历史失败", e);
        }
    }

    /** 按位置绑定参数（与申请仓储同口径：TIMESTAMP / 数值 / 文本分流）。 */
    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            int index = i + 1;
            if (p instanceof Timestamp ts) {
                ps.setTimestamp(index, ts);
            } else if (p instanceof Integer value) {
                // ⚠️ LIMIT 参数必须按整数绑定：setString 会让 PG 报
                //    「argument of LIMIT must be type bigint」
                ps.setInt(index, value);
            } else if (p instanceof Long value) {
                ps.setLong(index, value);
            } else {
                ps.setString(index, String.valueOf(p));
            }
        }
    }

    private static ConfigRelease map(ResultSet rs) throws SQLException {
        Timestamp effectiveAt = rs.getTimestamp("effective_at");
        return new ConfigRelease(
                rs.getLong("release_id"),
                rs.getLong("config_publish_seq"),
                ConfigRelease.Scope.valueOf(rs.getString("scope")),
                rs.getString("tenant_id"),
                rs.getString("object_versions"),
                rs.getString("aggregate_fingerprint"),
                ConfigRelease.Status.valueOf(rs.getString("status")),
                rs.getString("requester_id"),
                rs.getString("approver_id"),
                effectiveAt == null ? null : effectiveAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ <b>PLATFORM 作用域必须绑定一个<b>合法 UUID</b>，不能绑 {@code null}</b>：
     * {@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}——
     * 绑非 UUID 文本会让 <b>类型转换直接报错</b>（而不是「读不到行」），
     * 报错点落在 {@code ::UUID} 转换上，离「这个表是平台级的」这一成因很远。
     *
     * <p>因此用<b>零 UUID</b> 作哨兵：它能通过 UUID 转换，
     * 在 RLS 下等价于「一个不存在的租户」——
     * <ul>
     *   <li>租户行（{@code tenant_id = 零UUID}）：不可见，也不可能存在；</li>
     *   <li>PLATFORM 行（{@code tenant_id IS NULL}）：<b>读</b>策略显式放行；</li>
     *   <li>写入 PLATFORM 行：<b>写</b>策略 {@code tenant_id = current_tenant_id()}
     *       不满足 → RLS 拒绝 —— 这正是期望行为（平台级发布须由运维角色执行）。</li>
     * </ul>
     */
    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", Set.of(), 0L);
    }
}

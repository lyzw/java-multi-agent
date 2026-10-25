package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * {@link CapRoutingPolicyPort} 的 PostgreSQL 实现（多级 Agent v1.1 · 第十四批）。
 *
 * <p>⚠️ <b>写入是「插入不可变版本行」，不是更新</b>：同
 * {@code (tenantId, policyId, version)} 已存在时返回 {@code false} 且不覆盖。
 * 改策略只能发新版本 —— 否则在途 Run 按快照引用的旧策略会被就地改写，
 * 路由输出将无从复核（MA-T15）。
 *
 * <p>⚠️ <b>RLS 表</b>：必须经 {@code tenantConnection(tenantId)} 绑定租户上下文后查询
 * （外层 {@code WHERE tenant_id} 与 RLS 策略双保险）。
 *
 * <p>⚠️ <b>{@code policy_id} 是可读字符串，不是 UUID</b>：
 * 对外 schema 契约（{@code common.schema.json#/$defs/id}）把
 * {@code fixedVersionRef.id} 定义为可读字符串（形如 {@code orders-router}）。
 * 参见 V23 的教训：把新概念的 id 声明成 UUID 会让整笔发布事务在非 UUID id 上回滚，
 * 而这种缺陷在 mock 连接的单元测试里<b>永远不会暴露</b>。
 *
 * <p>⚠️ <b>{@code targets} 存 JSONB</b>：顺序在 {@code ORDERED_FALLBACK}
 * 下降级语义下有意义，故用数组而非集合。读回时按 JSON 语义解析，
 * 不按字节比较（PG 的 {@code jsonb::text} 会规范化空白）。
 */
public class JdbcCapRoutingPolicyRepository implements CapRoutingPolicyPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SQL_INSERT = """
            INSERT INTO cap_routing_policy
                (tenant_id, policy_id, version, strategy, targets, config_fingerprint,
                 schema_version, created_at, published_at)
            VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, policy_id, version) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT tenant_id::text, policy_id::text, version, strategy, targets::text,
                   config_fingerprint, schema_version, created_at, published_at,
                   lifecycle_state::text
            FROM cap_routing_policy
            WHERE tenant_id = ?::uuid AND policy_id = ? AND version = ?
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT tenant_id::text, policy_id::text, version, strategy, targets::text,
                   config_fingerprint, schema_version, created_at, published_at,
                   lifecycle_state::text
            FROM cap_routing_policy
            WHERE tenant_id = ?::uuid AND policy_id = ?
              AND published_at IS NOT NULL
            ORDER BY version DESC
            LIMIT 1
            """;

    /**
     * 最新版本（<b>含草稿</b>）—— 仅用于「分配下一个版本号」。
     *
     * <p>⚠️ 必须含草稿：草稿也占用了版本号；只看已发布版本会分配出与草稿撞号的版本，
     * 插入被 {@code ON CONFLICT DO NOTHING} 静默丢弃 → 「发布成功但策略没生效」。
     */
    private static final String SQL_FIND_LATEST = """
            SELECT tenant_id::text, policy_id::text, version, strategy, targets::text,
                   config_fingerprint, schema_version, created_at, published_at,
                   lifecycle_state::text
            FROM cap_routing_policy
            WHERE tenant_id = ?::uuid AND policy_id = ?
            ORDER BY version DESC
            LIMIT 1
            """;

    private final DataSource dataSource;

    public JdbcCapRoutingPolicyRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapRoutingPolicyDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(def.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.tenantId());
                        ps.setString(2, def.policyId());
                        ps.setInt(3, def.version());
                        ps.setString(4, def.strategy().name());
                        ps.setString(5, MAPPER.writeValueAsString(def.targets()));
                        ps.setString(6, def.configFingerprint());
                        ps.setInt(7, def.schemaVersion());
                        ps.setTimestamp(8, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(9, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(9, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapRoutingPolicyDef> find(@NonNull String tenantId,
                                                        @NonNull String policyId,
                                                        int version) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, policyId);
                        ps.setInt(3, version);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapRoutingPolicyDef> findLatestPublished(@NonNull String tenantId,
                                                                        @NonNull String policyId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_FIND_LATEST_PUBLISHED)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, policyId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapRoutingPolicyDef> findLatest(@NonNull String tenantId,
                                                              @NonNull String policyId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, policyId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    private static CapRoutingPolicyDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        Instant created = rs.getTimestamp("created_at").toInstant();
        List<String> targets = MAPPER.readValue(rs.getString("targets"),
                new TypeReference<List<String>>() {
                });
        RoutingStrategy strategy = RoutingStrategy.valueOf(rs.getString("strategy"));
        return new CapRoutingPolicyDef(
                rs.getString("tenant_id"),
                rs.getString("policy_id"),
                rs.getInt("version"),
                strategy,
                targets,
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                created,
                publishedAt == null ? null : publishedAt.toInstant(),
                lifecycleState(rs));
    }


    /**
     * 读取生命周期状态（§6.6）。
     *
     * <p>⚠️ <b>「读不到状态」绝不能被降级成「没有停用」</b>：
     * 本方法<b>不吞异常</b> —— 查询语句显式选择了 {@code lifecycle_state::text}，
     * 列不存在（迁移 V26 未应用）时 SQL 直接失败，
     * 而不是返回 {@code null} 让调用方以为「这行没被停用」。
     * 后者会让「停用」这道防线<b>看起来在、其实不在</b>：
     * 发布期引用校验读不到 DEPRECATED 状态 → 停用过的契约照样能被新发布引用。
     * 数据层失败必须<b>显式炸出来</b>（fail-closed），由迁移纪律保证列一定存在。
     *
     * @param rs 结果集
     * @return 生命周期状态；值为空时为 {@code null}（= 未记录）
     */
    private static @Nullable ConfigLifecycleState lifecycleState(ResultSet rs) throws SQLException {
        String raw = rs.getString("lifecycle_state");
        return raw == null ? null : ConfigLifecycleState.valueOf(raw);
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}

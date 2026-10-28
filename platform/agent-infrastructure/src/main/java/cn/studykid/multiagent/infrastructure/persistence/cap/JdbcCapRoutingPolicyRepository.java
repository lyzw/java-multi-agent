package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;
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
 * <p>⚠️ <b>三段结构存 JSONB，读回的是 PG 规范化后的文本，不是提交时的原文</b>：
 * PG 的 {@code jsonb} 会重排对象键、压缩空白，但<b>保留数组顺序</b> ——
 * 而数组顺序恰好是路由语义（{@code evaluation} 的规则次序、{@code in} 的字面量集合）。
 * 于是产生一个隐蔽的要求：<b>内容指纹必须对规范化后的形态稳定</b>，
 * 否则「写进去一行、读出来指纹变了」会让 {@code config_fingerprint}
 * 失去「这行没变」的证明力（这正是它唯一的用途）。
 * {@link cn.studykid.multiagent.application.cap.CapObjectFingerprint} 因此
 * 对 JSON 列<b>递归排序对象键、保留数组顺序</b>后再哈希。
 * 注意这与 {@code ClosureFingerprint} 的做法<b>刻意不同</b>：后者对顶层数组也排序，
 * 因为参数策略的数组无序 —— 两处若写成同一个方法，路由的规则次序就会被悄悄抹平。
 */
public class JdbcCapRoutingPolicyRepository implements CapRoutingPolicyPort {

    /** 三条查询共用的投影列（与 {@code JdbcCapApprovalRepository} 同写法：拼接点前用 {@code \s} 保空格）。 */
    private static final String COLUMNS = """
            tenant_id::text, policy_id::text, version, mode,
                   input_schema::text, evaluation::text, default_target,
                   no_match_action, on_conflict, sample_set::text,
                   config_fingerprint, schema_version, created_at, published_at,
                   lifecycle_state::text
            """;

    private static final String SQL_INSERT = """
            INSERT INTO cap_routing_policy
                (tenant_id, policy_id, version, mode, input_schema, evaluation, default_target,
                 no_match_action, on_conflict, sample_set, config_fingerprint,
                 schema_version, created_at, published_at)
            VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, policy_id, version) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT \s""" + COLUMNS + """
            FROM cap_routing_policy
            WHERE tenant_id = ?::uuid AND policy_id = ? AND version = ?
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT \s""" + COLUMNS + """
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
            SELECT \s""" + COLUMNS + """
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
                        ps.setString(4, def.mode().name());
                        ps.setString(5, def.inputSchema());
                        ps.setString(6, def.evaluation());
                        ps.setString(7, def.defaultTarget());
                        ps.setString(8, def.noMatchAction().name());
                        ps.setString(9, def.onConflict().name());
                        ps.setString(10, def.sampleSet());
                        ps.setString(11, def.configFingerprint());
                        ps.setInt(12, def.schemaVersion());
                        ps.setTimestamp(13, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(14, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(14, Timestamp.from(def.publishedAt()));
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
        return new CapRoutingPolicyDef(
                rs.getString("tenant_id"),
                rs.getString("policy_id"),
                rs.getInt("version"),
                RoutingMode.valueOf(rs.getString("mode")),
                rs.getString("input_schema"),
                rs.getString("evaluation"),
                rs.getString("default_target"),
                NoMatchAction.valueOf(rs.getString("no_match_action")),
                ConflictPolicy.valueOf(rs.getString("on_conflict")),
                rs.getString("sample_set"),
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

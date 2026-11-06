package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;

/**
 * {@link AuthzVersionPort} 的 PostgreSQL 实现（§6.1 / 06 §1.5 · W-PERM-P0-T3）。
 *
 * <p><b>本类是「撤权即时全实例失效」的存储层地基</b>：
 * 多实例部署下，授权撤销必须对<b>所有</b>实例立即可见。
 * 内存版 {@code RoleBasedAuthorizationService} 只能保证单实例内生效，
 * 因此 P0 用本仓储替换它作为版本事实源。
 *
 * <p>⚠️ <b>为什么「递增」是一条 SQL 而不是一个方法体</b>（06 §1.5 第 2 条）：
 * 若实现为「SELECT 读版本 → Java 加一 → UPDATE 写回」，两个并发撤权会
 * 读到同一个旧值、写回同一个新值 —— <b>丢失一次递增</b>。
 * 后果是「撤了权但版本没变」，持有旧版本的上下文仍然通过校验，
 * 撤权<b>静默失效</b>。因此递增必须是 DB 原子操作。
 *
 * <p>⚠️ <b>为什么用 upsert 语义而不是先查后插</b>：
 * 「先查 → 不存在则插 → 存在则加一」在并发下仍会撞唯一约束。
 * 单条 {@code INSERT ... ON CONFLICT DO UPDATE} 由 PostgreSQL 的
 * 唯一索引仲裁：并发插入时后者等待前者提交后重算，得到的是
 * <b>连续递增</b>的结果（2、3、4…），不会丢号也不会重号。
 *
 * <p>⚠️ <b>强一致读</b>：读走权威主库，且语句在 READ COMMITTED 下
 * 看到「语句开始时已提交的所有数据」——这已满足撤权 SLA。
 * <b>不</b>依赖任何异步缓存刷新：异步缓存的最终一致会把撤权延迟
 * 到不可控窗口，等于把 SLA 降级。
 */
public class JdbcAuthzVersionRepository implements AuthzVersionPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuthzVersionRepository.class);

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_authz_version";

    private static final String SQL_FIND = """
            SELECT tenant_id::text, principal_id, authz_version, updated_at
            FROM cap_authz_version
            WHERE tenant_id = ?::uuid AND principal_id = ?
            """;

    /**
     * 原子递增：不存在则建为初始值 1，存在则 +1。
     *
     * <p>⚠️ {@code ON CONFLICT DO UPDATE} 的表达式里必须<b>引用表名限定</b>
     * （{@code cap_authz_version.authz_version}）—— 不限定时 PostgreSQL
     * 会把它解析为 {@code EXCLUDED}，导致「每次都写回被排除的新值」，
     * 递增永远停在 1。这类错误<b>不报错</b>，只表现为「撤权无效」，
     * 极难在测试外被发现。
     */
    private static final String SQL_BUMP = """
            INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version, version, updated_at)
            VALUES (?::uuid, ?, 1, 0, now())
            ON CONFLICT (tenant_id, principal_id) DO UPDATE
                SET authz_version = cap_authz_version.authz_version + 1,
                    version       = cap_authz_version.version + 1,
                    updated_at    = now()
            RETURNING authz_version, updated_at
            """;

    private static final String SQL_INITIALIZE = """
            INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version, version, updated_at)
            VALUES (?::uuid, ?, 1, 0, now())
            ON CONFLICT (tenant_id, principal_id) DO NOTHING
            """;

    /**
     * 租户级水位聚合（管理面 P4 运行监控）。
     *
     * <p>⚠️ <b>{@code revoked_subjects} 的口径是 {@code authz_version > 1}</b>：
     * 初始版本恒为 1（{@code AuthzVersion.INITIAL_VERSION}），
     * 故「> 1」精确等于「发生过至少一次撤权递增」，且与
     * {@code CHECK (authz_version >= 1)} 约束不冲突。
     */
    private static final String SQL_WATERMARK = """
            SELECT
                count(*)                                        AS subject_count,
                count(*) FILTER (WHERE authz_version > 1)      AS revoked_subjects,
                COALESCE(max(authz_version), 0)                 AS max_authz_version,
                max(updated_at)                                 AS last_bump_at
            FROM cap_authz_version
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2；阶段一 P0-1b 第 1 批）────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    public JdbcAuthzVersionRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled    是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcAuthzVersionRepository(@NonNull DataSource dataSource,
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
    public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                 @NonNull String principalId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, principalId);
                        try (ResultSet rs = ps.executeQuery()) {
                            Optional<AuthzVersion> row =
                                    rs.next() ? Optional.of(map(rs)) : Optional.<AuthzVersion>empty();
                            row.ifPresent(v -> shadowTaint(RESOURCE_TYPE, tenantId, v.tenantId()));
                            return row;
                        }
                    }
                });
    }

    @Override
    public @NonNull AuthzVersion require(@NonNull String tenantId, @NonNull String principalId) {
        Optional<AuthzVersion> found = find(tenantId, principalId);
        if (found.isPresent()) {
            return found.get();
        }
        // ⚠️ fail-closed：无记录 = 无法确认版本新鲜度 → 拒绝，绝不降级放行
        //    （06 §1.5 第 6 条 / 04 §A9）。此时调用方须以 AUTHZ_UNAVAILABLE 拒绝。
        throw new AuthzUnavailableException(tenantId, principalId,
                "授权版本记录不存在，无法确认版本新鲜度（fail-closed 拒绝）", null);
    }

    @Override
    public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                            @NonNull String principalId) {
        TransactionalConnectionHolder.inTransaction(dataSource, tenantContext(tenantId), conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SQL_INITIALIZE)) {
                ps.setString(1, tenantId);
                ps.setString(2, principalId);
                ps.executeUpdate();
                return null;
            }
        });
        return require(tenantId, principalId);
    }

    @Override
    public long bump(@NonNull String tenantId, @NonNull String principalId) {
        long version = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_BUMP)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, principalId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("授权版本递增未返回结果");
                            }
                            return rs.getLong("authz_version");
                        }
                    }
                });
        if (log.isDebugEnabled()) {
            log.debug("授权版本已递增: tenant={}, principal={}, authzVersion={}",
                    tenantId, principalId, version);
        }
        return version;
    }

    /**
     * 租户级授权版本水位聚合（管理面 P4 运行监控）。
     *
     * <p>⚠️ <b>为什么用一条聚合 SQL 而不是遍历主体</b>：
     * 水位是租户级指标，主体数随租户增长；逐主体 {@code find} 是 N+1 查询，
     * 在监控页这种「高频打开」的场景会直接拖垮 DB。
     *
     * <p>⚠️ <b>单条 SQL 保证口径一致</b>：
     * {@code count(*)} / {@code count(*) FILTER (authz_version > 1)} /
     * {@code max(authz_version)} / {@code max(updated_at)} 在同一条语句的同一快照上求值，
     * 不会出现「计数取自时刻 A、最大版本取自时刻 B」的自相矛盾。
     *
     * <p>⚠️ <b>无记录时返回零值快照而不是空</b>：
     * 「该租户还没有任何授权版本记录」是一个<b>合法的初始状态</b>（尚未授权任何主体），
     * 不是故障。此时返回 subjectCount=0 的水位，让监控页展示「暂无授权记录」；
     * 真正的故障（表不存在 / 连接失败）会抛 SQLException，由上层转 503。
     */
    @Override
    public @NonNull AuthzWatermark watermark(@NonNull String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_WATERMARK)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                // ⚠️ 聚合查询无匹配行时返回 0 值行；
                                //    但本 SQL 无 GROUP BY，恒有一行 —— 保留兜底以防 SQL 变更
                                return new AuthzWatermark(
                                        tenantId, 0L, 0L, 0L, null, Instant.now());
                            }
                            long subjects = rs.getLong("subject_count");
                            long revoked = rs.getLong("revoked_subjects");
                            long maxVersion = rs.getLong("max_authz_version");
                            Timestamp lastBump = rs.getTimestamp("last_bump_at");
                            return new AuthzWatermark(
                                    tenantId,
                                    subjects,
                                    revoked,
                                    maxVersion,
                                    lastBump == null ? null : lastBump.toInstant(),
                                    Instant.now());
                        }
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖，
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

    void shadowCompare(@NonNull String resourceType, @NonNull String tenantId, boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

    void shadowTaint(@NonNull String resourceType,
                     @NonNull String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                SubjectContext.forTenant(subjectTenantId), rowTenantId, isShared);
    }

    private static AuthzVersion map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("updated_at");
        return new AuthzVersion(
                rs.getString("tenant_id"),
                rs.getString("principal_id"),
                rs.getLong("authz_version"),
                ts == null ? Instant.EPOCH : ts.toInstant());
    }

    /**
     * 构造仅用于绑定租户上下文的上下文。
     *
     * <p>⚠️ {@code principalId} 用固定占位值：本仓储的操作<b>按调用方显式传入的
     * principalId</b> 定位记录，不依赖上下文的 principal。
     * 若这里用上下文的 principal，会把「查 A 的版本」错变成「查调用者的版本」。
     */
    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}

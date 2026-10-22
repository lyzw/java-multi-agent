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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
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

    private final DataSource dataSource;

    public JdbcAuthzVersionRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                 @NonNull String principalId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, principalId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.<AuthzVersion>empty();
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

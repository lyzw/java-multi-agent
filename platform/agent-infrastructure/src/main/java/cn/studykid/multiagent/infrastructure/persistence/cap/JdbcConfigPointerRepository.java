package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;

/**
 * {@link ConfigPointerPort} 的 PostgreSQL 实现（§6.3 第 3/5 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>CAS 的 SQL 形态是本类的全部要点</b>：
 * <pre>{@code
 * UPDATE cap_config_pointer
 *    SET current_release_id = ?, current_publish_seq = ?, updated_at = now()
 *  WHERE pointer_key = ?
 *    AND current_publish_seq IS NOT DISTINCT FROM ?
 * }</pre>
 *
 * <p>⚠️ <b>为什么用 {@code IS NOT DISTINCT FROM} 而不是 {@code =}</b>：
 * 首次发布时指针尚不存在、{@code current_publish_seq IS NULL}，
 * 而 expected 也是 {@code null}（「期望尚未发布过」是合法基线）。
 * SQL 里 {@code NULL = NULL} 恒为 UNKNOWN，{@code = ?} 传 null 时<b>永远匹配不上</b> ——
 * 首次发布会静默失败，且失败现场是「CAS 没生效」而非「首次发布语义」，
 * 排查方向完全错误。{@code IS NOT DISTINCT FROM} 把 null 当作一个可比较的值。
 *
 * <p>⚠️ <b>为什么不用 {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE}</b>：
 * 那条语句在「指针行不存在」时会走 INSERT 分支，若同时带 WHERE 条件，
 * 条件不满足时既不更新也不报错 —— 调用方<b>分不清</b>「CAS 失败」与「指针不存在」。
 * 本类拆成「先 UPDATE，0 行且指针确实不存在时再 INSERT ... ON CONFLICT DO NOTHING」，
 * 让「指针不存在」与「基线已变」两种结局<b>可区分</b>。
 */
public class JdbcConfigPointerRepository implements ConfigPointerPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcConfigPointerRepository.class);

    private static final String SQL_SELECT = """
            SELECT pointer_key, scope, tenant_id::text, current_release_id,
                   current_publish_seq, updated_at
            FROM cap_config_pointer
            WHERE pointer_key = ?
            """;

    private static final String SQL_UPDATE = """
            UPDATE cap_config_pointer
               SET current_release_id = ?, current_publish_seq = ?, updated_at = now()
             WHERE pointer_key = ?
               AND current_publish_seq IS NOT DISTINCT FROM ?
            """;

    private static final String SQL_INSERT = """
            INSERT INTO cap_config_pointer
                (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
            VALUES (?, ?, ?::uuid, ?, ?, now())
            ON CONFLICT (pointer_key) DO NOTHING
            """;

    private final DataSource dataSource;

    public JdbcConfigPointerRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @NonNull
    public Optional<ConfigPointer> current(ConfigRelease.@NonNull Scope scope,
                                            @Nullable String tenantId) {
        String key = ConfigPointer.pointerKey(scope, tenantId);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public boolean compareAndSet(ConfigRelease.@NonNull Scope scope,
                                  @Nullable String tenantId,
                                  @Nullable Long expectedPublishSeq,
                                  long newReleaseId,
                                  long newPublishSeq) {
        String key = ConfigPointer.pointerKey(scope, tenantId);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> {
                    // ── 第一步：基于 expected 基线的单行更新 ──
                    try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE)) {
                        ps.setLong(1, newReleaseId);
                        ps.setLong(2, newPublishSeq);
                        ps.setString(3, key);
                        if (expectedPublishSeq == null) {
                            ps.setNull(4, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(4, expectedPublishSeq);
                        }
                        if (ps.executeUpdate() == 1) {
                            return Boolean.TRUE;
                        }
                    }

                    // ── 第二步：0 行 —— 要么指针不存在，要么基线已变 ──
                    //    只有「指针确实不存在 且 expected 为 null（首次发布）」才允许补插。
                    //    若指针存在而 expected 为 null，同样是基线冲突（调用方以为还没发布过），
                    //    必须失败 —— 否则会覆盖掉别人已经发布的当前指针。
                    boolean exists;
                    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            exists = rs.next();
                        }
                    }
                    if (exists || expectedPublishSeq != null) {
                        log.debug("指针 CAS 失败（基线已变）: key={}, expected={}", key, expectedPublishSeq);
                        return Boolean.FALSE;
                    }

                    // ── 第三步：首次发布 —— 补插指针行 ──
                    //    ON CONFLICT DO NOTHING：并发首次发布时只有一个成功，
                    //    另一个拿到 0 行 → CAS 失败（§6.3 第 4 条：仅一次成功）。
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, key);
                        ps.setString(2, scope.name());
                        if (tenantId == null) {
                            ps.setNull(3, java.sql.Types.OTHER);
                        } else {
                            ps.setString(3, tenantId);
                        }
                        ps.setLong(4, newReleaseId);
                        ps.setLong(5, newPublishSeq);
                        return ps.executeUpdate() == 1;
                    }
                });
    }

    private static ConfigPointer map(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        long releaseId = rs.getLong("current_release_id");
        long publishSeq = rs.getLong("current_publish_seq");
        boolean hasRelease = !rs.wasNull();
        return new ConfigPointer(
                rs.getString("pointer_key"),
                ConfigRelease.Scope.valueOf(rs.getString("scope")),
                rs.getString("tenant_id"),
                hasRelease ? releaseId : null,
                hasRelease ? publishSeq : null,
                updatedAt.toInstant());
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ PLATFORM 作用域绑定<b>零 UUID 哨兵</b>（而非 {@code null}）：
     * {@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错。
     * 零 UUID 是合法 UUID 且不对应任何真实租户。
     */
    private static TenantContext context(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /** PLATFORM 作用域的租户哨兵：零 UUID。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";
}

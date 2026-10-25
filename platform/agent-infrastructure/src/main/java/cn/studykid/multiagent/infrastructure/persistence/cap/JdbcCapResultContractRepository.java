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

import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;

/**
 * {@link CapResultContractPort} 的 PostgreSQL 实现（多级 Agent v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>写入是「插入不可变版本行」，不是更新</b>：同
 * {@code (tenantId, contractId, version)} 已存在时返回 {@code false} 且不覆盖。
 * 改契约只能发新版本 —— 否则在途 Run 按快照引用的旧契约会被就地改写，
 * 历史可追溯性就没了。
 *
 * <p>⚠️ <b>RLS 表</b>：必须经 {@code tenantConnection(tenantId)} 绑定租户上下文后查询
 * （外层 {@code WHERE tenant_id} 与 RLS 策略双保险）。
 */
public class JdbcCapResultContractRepository implements CapResultContractPort {

    private static final String SQL_INSERT = """
            INSERT INTO cap_result_contract
                (tenant_id, contract_id, version, schema_json, config_fingerprint,
                 schema_version, created_at, published_at)
            VALUES (?::uuid, ?::uuid, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, contract_id, version) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT tenant_id::text, contract_id::text, version, schema_json::text,
                   config_fingerprint, schema_version, created_at, published_at
            FROM cap_result_contract
            WHERE tenant_id = ?::uuid AND contract_id = ?::uuid AND version = ?
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT tenant_id::text, contract_id::text, version, schema_json::text,
                   config_fingerprint, schema_version, created_at, published_at
            FROM cap_result_contract
            WHERE tenant_id = ?::uuid AND contract_id = ?::uuid
              AND published_at IS NOT NULL
            ORDER BY version DESC
            LIMIT 1
            """;

    /**
     * 最新版本（<b>含草稿</b>）—— 仅用于「分配下一个版本号」。
     *
     * <p>⚠️ 必须含草稿：草稿也占用了版本号；
     * 只看已发布版本会分配出与草稿撞号的版本，插入被
     * {@code ON CONFLICT DO NOTHING} 静默丢弃 → 「发布成功但契约没生效」。
     */
    private static final String SQL_FIND_LATEST = """
            SELECT tenant_id::text, contract_id::text, version, schema_json::text,
                   config_fingerprint, schema_version, created_at, published_at
            FROM cap_result_contract
            WHERE tenant_id = ?::uuid AND contract_id = ?::uuid
            ORDER BY version DESC
            LIMIT 1
            """;

    private final DataSource dataSource;

    public JdbcCapResultContractRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapResultContractDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(def.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.tenantId());
                        ps.setString(2, def.contractId());
                        ps.setInt(3, def.version());
                        ps.setString(4, def.schemaJson());
                        ps.setString(5, def.configFingerprint());
                        ps.setInt(6, def.schemaVersion());
                        ps.setTimestamp(7, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(8, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                         @NonNull String contractId,
                                                         int version) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, contractId);
                        ps.setInt(3, version);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapResultContractDef> findLatestPublished(
            @NonNull String tenantId, @NonNull String contractId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_FIND_LATEST_PUBLISHED)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, contractId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapResultContractDef> findLatest(@NonNull String tenantId,
                                                              @NonNull String contractId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, contractId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    private static CapResultContractDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        Instant created = rs.getTimestamp("created_at").toInstant();
        return new CapResultContractDef(
                rs.getString("tenant_id"),
                rs.getString("contract_id"),
                rs.getInt("version"),
                rs.getString("schema_json"),
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                created,
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}

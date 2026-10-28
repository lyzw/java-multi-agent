package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunInputPort;

/**
 * {@link RunInputPort} 的 JDBC 实现（L-4 闭合）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>：与 {@code JdbcRunQueryRepository}
 * 同一铁律 —— RLS 之外应用侧条件是第一道防线。
 */
@Repository
public class JdbcRunInputRepository implements RunInputPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private static final String SQL_FIND_INPUT = """
            SELECT input_payload::text FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcRunInputRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Map<String, Object> inputOf(@NonNull String tenantId,
                                                @NonNull String runId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_INPUT)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Map.of();
                    }
                    return parse(rs.getString(1));
                }
            } finally {
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 输入失败", e);
        }
    }

    /**
     * JSONB 文本 → Map。
     *
     * <p>⚠️ 解析失败返回空 Map 而不是抛异常：坏输入属于<b>该 Run 的问题</b>，
     * 由执行侧按「无输入」拒绝执行并落终态，比在读取层抛一串栈更可归因。
     */
    private static Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 外层有事务则复用，否则自开并绑定租户（同 {@code JdbcRunQueryRepository}）：
     * 未绑定上下文时 RLS 默认拒绝，读取会静默返回 0 行。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }
}

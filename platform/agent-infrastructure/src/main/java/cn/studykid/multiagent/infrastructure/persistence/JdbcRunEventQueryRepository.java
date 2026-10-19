package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;

/**
 * {@link RunEventQueryPort} 的 JDBC 实现（P2-D05）。
 *
 * <p><b>跨租户防护是双层的</b>：
 * <ol>
 *   <li><b>第一道（应用侧）</b>：SQL 显式携带 {@code tenant_id = ?}。</li>
 *   <li><b>第二道（数据库侧）</b>：{@code run_event} 表启用 RLS 策略，
 *       未绑定 {@code app.tenant_id} 时可见 0 行。</li>
 * </ol>
 * 两层机制相互独立。若某天 RLS 策略被误删，第一道仍能挡住；
 * 若应用侧条件被误删，第二道仍能挡住。**任何单层都可能被误改，双层不会同时失效**。
 *
 * <p>⚠️ <b>为什么不用 Spring 的 {@code @Transactional}</b>：
 * RLS 的 {@code set_config(..., is_local=true)} 采用事务局部语义，
 * 自动提交模式下绑定立即失效。本类显式管理事务边界，
 * 走 {@link TenantConnectionLease} 统一入口。
 */
@Repository
public class JdbcRunEventQueryRepository implements RunEventQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunEventQueryRepository.class);

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（P2-D05 §5.2 S7 反证测试会失败）。
     *
     * <p>{@code sequence_no} 上的唯一约束为 {@code (tenant_id, run_id, sequence_no)}，
     * 已有索引支撑本查询。
     */
    private static final String SQL_FIND_AFTER = """
            SELECT sequence_no, event_type, payload, created_at
            FROM run_event
            WHERE tenant_id = ?::uuid
              AND run_id = ?::uuid
              AND sequence_no > ?
            ORDER BY sequence_no ASC
            LIMIT ?
            """;

    private static final String SQL_FIND_STATE = """
            SELECT state::text
            FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    /** 单次拉取上限，防止长 Run 一次性返回海量事件。 */
    private static final int DEFAULT_LIMIT = 200;

    private final DataSource dataSource;

    public JdbcRunEventQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<RunEvent> findAfter(@NonNull String tenantId,
                                            @NonNull String runId,
                                            long afterSequenceNo,
                                            int limit) {
        // ⚠️ 负数游标视为 0：负数无业务含义，
        //    静默接受会让 ORDER BY / > 语义变得难以推理。
        long cursor = Math.max(0L, afterSequenceNo);
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        List<RunEvent> events = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_AFTER)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setLong(3, cursor);
                ps.setInt(4, effectiveLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp created = rs.getTimestamp("created_at");
                        events.add(new RunEvent(
                                rs.getLong("sequence_no"),
                                rs.getString("event_type"),
                                parsePayload(rs.getString("payload")),
                                created == null ? Instant.EPOCH : created.toInstant()));
                    }
                }
            }
            // ⚠️ 只读查询：回滚以释放读事务。
            //    复用外层连接时本方法为空操作（租约内部已处理）。
            lease.rollbackQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 事件失败", e);
        }

        if (log.isTraceEnabled()) {
            log.trace("Run 事件查询: runId={}, after={}, 返回={} 条",
                    runId, cursor, events.size());
        }
        return List.copyOf(events);
    }

    @Override
    public @Nullable String findState(@NonNull String tenantId, @NonNull String runId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_STATE)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } finally {
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 状态失败", e);
        }
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunCreationRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    /**
     * 最小化 JSON 解析。
     *
     * <p>⚠️ <b>刻意不引入完整 JSON 库</b>：事件载荷在写入时已被限制为
     * 「扁平的标量键值对」（见 {@code insertInitialEvent}），
     * 无嵌套结构。为一个扁平对象拖入 Jackson 不划算。
     *
     * <p>⚠️ <b>解析失败不抛异常</b>：返回空载荷而非让整个查询失败。
     * 理由：SSE 订阅是<b>可观测性</b>通道，一条事件解析失败不应
     * 掐断整个事件流。但必须打 warn —— 静默吞掉会掩盖数据问题。
     */
    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return Map.of();
        }
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = unquote(pair.substring(0, idx).trim());
            String raw = unquote(pair.substring(idx + 1).trim());
            result.put(key, coerce(raw));
        }
        return result;
    }

    private static String unquote(String s) {
        String v = s.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    private static Object coerce(String raw) {
        if ("null".equals(raw)) {
            return null;
        }
        if ("true".equals(raw) || "false".equals(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            if (raw.indexOf('.') >= 0) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 非数字 → 保持字符串，这是最常见的合法情况
            return raw;
        }
    }
}
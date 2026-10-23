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

import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;

/**
 * {@link ResultEventPort} 的 PostgreSQL 实现（§6.1 · W-PERM-P0-T5）。
 *
 * <p>⚠️ <b>写入是 {@code ON CONFLICT DO NOTHING}，不是 upsert</b>：
 * 结果事件是「已发生的事实」，先写入者为准。
 * 若改成 {@code DO UPDATE}，崩溃恢复时的重试会<b>覆盖</b>既有确定结果——
 * 而 §7.4 的双维度结果依赖「确定结果不变、只重试落账」，
 * 覆盖会让「结果未知」被改写成「确定成功」，掩盖真实状态。
 */
public class JdbcResultEventRepository implements ResultEventPort {

    private static final String SQL_INSERT = """
            INSERT INTO cap_result_event
                (event_id, tenant_id, run_id, invocation_id, terminal_event_id,
                 result_status, result_payload, reason_code, created_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::cap_result_status, ?::jsonb, ?, ?)
            ON CONFLICT (tenant_id, invocation_id) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT event_id::text, tenant_id::text, run_id::text, invocation_id,
                   terminal_event_id, result_status, result_payload::text, reason_code, created_at
            FROM cap_result_event
            WHERE tenant_id = ?::uuid AND invocation_id = ?
            """;

    private final DataSource dataSource;

    public JdbcResultEventRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean recordIfAbsent(@NonNull ResultEvent event) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(event.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, event.eventId());
                        ps.setString(2, event.tenantId());
                        if (event.runId() == null) {
                            ps.setNull(3, java.sql.Types.OTHER);
                        } else {
                            ps.setString(3, event.runId());
                        }
                        ps.setString(4, event.invocationId());
                        ps.setString(5, event.terminalEventId());
                        ps.setString(6, event.resultStatus().name());
                        if (event.resultPayload() == null) {
                            ps.setNull(7, java.sql.Types.OTHER);
                        } else {
                            ps.setString(7, toJsonb(event.resultPayload()));
                        }
                        ps.setString(8, event.reasonCode());
                        ps.setTimestamp(9, Timestamp.from(event.createdAt()));
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    /**
     * 把结果载荷转成可写入 JSONB 的文本。
     *
     * <p>⚠️ <b>工具输出不保证是合法 JSON</b>：
     * {@code ToolResult.output} 是 {@code String}，真实工具一般产出 JSON 文档，
     * 但并非强制。若直接 {@code ?::jsonb} 写入非 JSON 文本，
     * PostgreSQL 会报 {@code invalid input syntax for type json} ——
     * 而这个失败发生在<b>「确定结果落盘」</b>这一步，
     * 后果是「工具成功了但结果没落盘」→ 系统把成功判成「证据丢失」。
     *
     * <p>故：<b>合法 JSON 原样写入</b>（保住结构，下游可按 JSONB 查询）；
     * <b>非 JSON 转成 JSON 字符串</b>（内容不丢，只是类型退化为字符串）。
     */
    private static @NonNull String toJsonb(@NonNull String payload) {
        try {
            tools.jackson.databind.JsonNode node =
                    tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload);
            if (node != null) {
                return payload;
            }
        } catch (RuntimeException e) {
            // ⚠️ 非 JSON → 落成 JSON 字符串（不抛：抛了等于让「载荷格式」决定成败）
        }
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(payload);
    }

    @Override
    public @NonNull Optional<ResultEvent> findByInvocation(@NonNull String tenantId,
                                                            @NonNull String invocationId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<ResultEvent>empty();
                        }
                    }
                });
    }

    private static ResultEvent map(ResultSet rs) throws SQLException {
        return new ResultEvent(
                rs.getString("event_id"),
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("invocation_id"),
                rs.getString("terminal_event_id"),
                ResultEvent.Status.valueOf(rs.getString("result_status")),
                rs.getString("result_payload"),
                rs.getString("reason_code"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}

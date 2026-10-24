package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.reference.TutoringVocabulary;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;

/**
 * Run 列表查询实现（P4-D13 / G7）。
 *
 * <p>⚠️ <b>租户隔离是双层的</b>（与 {@code JdbcRunQueryRepository} 同源铁律）：
 * SQL 显式携带 {@code tenant_id = ?}（第一道）+ 表上强制 RLS（第二道）。
 * 任一层被误改，另一层仍能挡住。
 *
 * <p>⚠️ <b>排序必须是全序</b>：{@code ORDER BY created_at DESC, run_id DESC}。
 * 只按 {@code created_at} 排时同微秒多行顺序不确定，
 * 游标翻页会出现重复或漏行（反证 A-2）。
 */
@Repository
public class JdbcRunListQueryRepository implements RunListQueryPort {

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（反证 A-1）。
     *
     * <p>多取 {@code limit + 1} 行用于判断「是否还有下一页」——
     * 比多发一次 {@code COUNT} 查询便宜得多，且不会在并发下与 COUNT 结果不一致。
     *
     * <p>⚠️ <b>只取两个 JSONB 标量字段，不取整个 {@code input_payload}</b>：
     * {@code input_payload} 里除了学科/年级，还有学生提交的题干与解答原文。
     * 用 {@code ->>} 精确取两个键，而不是把整列拉进内存再解析——
     * 后者会让「不小心多读一个字段」变成一次静默的隐私事故。
     */
    private static final String SQL_BASE = """
            SELECT run_id, state, created_at, completed_at, attempt, error_code,
                   input_payload->>'subject' AS subject,
                   input_payload->>'grade'   AS grade
            FROM run
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcRunListQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<RunListItem> list(@NonNull String tenantId,
                                           @Nullable String state,
                                           @Nullable String subject,
                                           @Nullable Instant from,
                                           @Nullable Instant to,
                                           @Nullable String cursor,
                                           int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_BASE);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (state != null) {
            sql.append(" AND state = ?::run_state");
            params.add(state);
        }
        if (subject != null) {
            // ⚠️ 用 btrim 比较：存的值可能带首尾空白（客户端可控），
            //    而展示侧（TutoringVocabulary）也只做 trim——
            //    两边口径必须一致，否则会出现「列表里显示数学，却筛不出数学」。
            sql.append(" AND btrim(input_payload->>'subject') = ?");
            params.add(subject.trim());
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(to));
        }
        if (after != null) {
            // ⚠️ 全序比较：时间「小于」**或**时间相等而 id「小于」。
            //    写成 (created_at, run_id) < (?, ?) 需要 PG 的行值比较，
            //    这里展开成显式 OR 以免因类型推断踩坑（V4 曾栽在 createArrayOf 上）。
            sql.append(" AND (created_at < ? OR (created_at = ? AND run_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }

        sql.append(" ORDER BY created_at DESC, run_id DESC LIMIT ?");
        params.add(limit + 1);

        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<RunListItem> rows = new ArrayList<>(limit);
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(read(rs));
                    }
                }
            }
            // ⚠️ 多取的那一行**只用于判定**，不返回——
            //    返回它会让本页实际条数超过调用方请求的 limit。
            String next = null;
            if (rows.size() > limit) {
                rows.remove(rows.size() - 1);
                RunListItem last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.runId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 列表失败", e);
        }
    }

    private static RunListItem read(ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new RunListItem(
                rs.getString("run_id"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getInt("attempt"),
                rs.getString("error_code"),
                // ⚠️⛔ **fail-closed**：客户端可控的学科/年级，只有命中 DEC-6 词表
                //    才允许回显；不认识的一律当「没有」（null）。
                //    原样回显等于把请求体里的任意文本直接送到前端渲染。
                TutoringVocabulary.subject(rs.getString("subject")),
                TutoringVocabulary.grade(rs.getString("grade")));
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}
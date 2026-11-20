package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunListQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunListQueryMapper.RunListRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort.RunListItem;

/**
 * RunListQueryPort MyBatis-Plus 实现（W3 Run 域，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ <b>游标翻页语义零改动</b>：
 * <ul>
 *   <li>显式 OR 展开游标条件（避免 PG 行值比较类型推断坑）；</li>
 *   <li>limit+1 判断 hasMore（不引入 COUNT 子查询）；</li>
 *   <li>全序排序 (created_at DESC, run_id DESC)；</li>
 *   <li>subject/grade 只取 input_payload 两个 JSONB 标量，不取整列。</li>
 * </ul>
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunListQueryRepository implements RunListQueryPort {

    private final RunListQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisRunListQueryRepository(@NonNull RunListQueryMapper mapper,
                                         @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Page<RunListItem> list(@NonNull String tenantId,
                                            @Nullable String requesterId,
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

        TenantScope.bind(tenantId);
        try {
            List<RunListRow> rows = TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.list(tenantId, requesterId,
                            state,
                            subject != null && !subject.isBlank() ? subject.trim() : null,
                            from, to,
                            after != null ? after.createdAt() : null,
                            after != null ? after.id() : null,
                            limit + 1));

            boolean hasMore = rows.size() > limit;
            List<RunListItem> items = rows.stream().limit(limit).map(this::toItem).toList();
            String nextCursor = null;
            if (hasMore && !items.isEmpty()) {
                RunListItem last = items.get(items.size() - 1);
                nextCursor = new PageCursor(last.createdAt(), last.runId()).encode();
            }
            return new Page<>(items, nextCursor);
        } finally {
            TenantScope.clear();
        }
    }

    private RunListItem toItem(RunListRow r) {
        return new RunListItem(r.getRunId(), r.getState(), r.getCreatedAt(),
                r.getCompletedAt(), r.getAttempt(), r.getErrorCode(),
                r.getSubject(), r.getGrade());
    }
}

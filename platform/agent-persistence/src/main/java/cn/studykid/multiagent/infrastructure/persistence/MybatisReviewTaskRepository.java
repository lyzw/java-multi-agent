package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ReviewTaskMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ReviewTaskDO;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;

/**
 * ReviewTask Port MyBatis-Plus 实现（W2 平台域迁移，opsSqlSessionFactory）。
 *
 * <p>⚠️ 表在 platform schema（{@code platform.review_task}），但<b>不是 BYPASSRLS</b>：
 * 表有 tenant_id 列，走运维连接是因为 platform 域表统一由 platform_ops 角色访问。
 * ⚠️ 动态 list 用 `<where><choose>` 处理默认 PENDING+IN_REVIEW 过滤。
 */
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisReviewTaskRepository implements ReviewTaskPort {

    private final ReviewTaskMapper mapper;
    private final DataSource dataSource;

    public MybatisReviewTaskRepository(
            @NonNull ReviewTaskMapper mapper,
            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull String create(@NonNull ReviewTaskSpec spec) {
        String taskId = mapper.create(spec.traceId(), spec.tenantId(), spec.runId(),
                spec.source().name(), spec.priority().name(), spec.note());
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("platform.review_task INSERT RETURNING 空");
        }
        return taskId;
    }

    @Override
    public boolean hasOpenTask(@NonNull String traceId) {
        return mapper.existsPendingByTraceId(traceId);
    }

    @Override
    public void assign(@NonNull String taskId, @NonNull String assignee) {
        int n = mapper.assign(taskId, assignee);
        if (n == 0) {
            throw new IllegalStateException("review_task assign 未命中（PENDING/IN_REVIEW 范围外或 task_id 不存在）");
        }
    }

    @Override
    public void resolve(@NonNull String taskId, @Nullable String rootCause, @Nullable String note) {
        int n = mapper.resolve(taskId, rootCause, note);
        if (n == 0) {
            throw new IllegalStateException("review_task resolve 未命中");
        }
    }

    @Override
    public void dismiss(@NonNull String taskId, @Nullable String note) {
        int n = mapper.dismiss(taskId, note);
        if (n == 0) {
            throw new IllegalStateException("review_task dismiss 未命中");
        }
    }

    @Override
    public @NonNull List<ReviewTaskView> list(@Nullable String status, @Nullable String tenantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return mapper.list(status, tenantId, safeLimit).stream().map(this::toView).toList();
    }

    private ReviewTaskView toView(ReviewTaskDO d) {
        return new ReviewTaskView(
                d.getTaskId(), d.getTraceId(), d.getTenantId(), d.getRunId(),
                d.getSource(), d.getPriority(), d.getStatus(),
                d.getAssignedTo(), d.getRootCause(),
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
    }
}

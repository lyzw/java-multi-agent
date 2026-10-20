package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审计查询端口（P4-D13 / G10 · FE-01 §P10 审计页 · P3-D07）。
 *
 * <p>⚠️ <b>只读</b>：{@code audit_record} 上有触发器 {@code forbid_audit_mutation}
 * （追加只读，P3-D07 R-3）。本端口<b>刻意不提供任何写入或删除方法</b>，
 * 写审计仍走 {@link AuditPort}。
 *
 * <p>⚠️ <b>为什么 {@code detail} 可以返回</b>：
 * {@code detail} 在<b>写入时</b>已由 {@code JdbcAuditRepository} 做过
 * 「可识别信息扫描，命中即拒绝写」（F-9）。写入侧已保证不含学生信息，
 * 读出侧再一刀切掉只会让审计页失去意义。
 * <b>前提</b>：读出侧不得再把其他列拼进 {@code detail}。
 */
public interface AuditQueryPort {

    /**
     * 查询审计记录（游标分页）。
     *
     * @param action        动作过滤；{@code null} 表示不限
     * @param resourceType  资源类型过滤；{@code null} 表示不限
     * @param resourceId    资源标识过滤；{@code null} 表示不限
     * @param from          起始时刻（含）
     * @param to            结束时刻（含）
     */
    @NonNull Page<AuditEntry> query(@NonNull String tenantId,
                                    @Nullable String action,
                                    @Nullable String resourceType,
                                    @Nullable String resourceId,
                                    @Nullable Instant from,
                                    @Nullable Instant to,
                                    @Nullable String cursor,
                                    int limit);

    /**
     * 审计条目。
     *
     * @param auditId 审计标识（游标排序键第二分量）
     */
    record AuditEntry(
            @NonNull String auditId,
            @NonNull String actorId,
            @NonNull String action,
            @NonNull String resourceType,
            @Nullable String resourceId,
            @NonNull Instant createdAt,
            @Nullable String correlationId,
            @NonNull Map<String, Object> detail
    ) {
        public AuditEntry {
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }
}
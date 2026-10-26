package cn.studykid.multiagent.ports.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台审计端口（重规划 §5.3 / §12）。
 *
 * <p>平台管理操作审计与租户业务审计<b>分域保存</b>：本端口只写/查
 * {@code platform.platform_audit_event}（无 {@code tenant_id}），
 * 与租户侧 {@code audit_record} 物理隔离。两者通过 {@code correlation_id}
 * 在必要时关联调查，但绝不在同一张表里混写。
 *
 * <p>审计记录<b>仅追加</b>：实现须保证 {@code INSERT} 后不可 {@code UPDATE/DELETE}
 * （应用层不提供修改/删除端点），满足不可抵赖要求。
 */
public interface PlatformAuditPort {

    /** 写入一条平台审计事件（仅追加）。 */
    void record(@NonNull PlatformAuditEvent event);

    /** 按条件查询平台审计事件（游标分页）。 */
    @NonNull
    List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query);

    /** 平台审计事件（不可变表示）。 */
    record PlatformAuditEvent(
            @NonNull UUID id,
            @NonNull String actorId,
            @NonNull String actorType,
            @NonNull String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @NonNull String decision,
            @Nullable String reasonCode,
            @Nullable UUID correlationId,
            @Nullable String ip,
            @NonNull Instant occurredAt
    ) {
    }

    /** 平台审计查询条件。 */
    record PlatformAuditQuery(
            @Nullable String actorId,
            @Nullable String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable Instant from,
            @Nullable Instant to,
            int limit,
            @Nullable String cursor
    ) {
        public static @NonNull PlatformAuditQuery of(int limit) {
            return new PlatformAuditQuery(null, null, null, null, null, null,
                    Math.max(1, Math.min(limit, 200)), null);
        }
    }
}

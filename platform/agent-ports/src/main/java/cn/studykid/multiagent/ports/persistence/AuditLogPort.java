package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.AuditLog;

/**
 * 业务审计日志端口（对应 {@code cap_audit_log} 表）。
 *
 * <p>⚠️ <b>与 {@code AuditPort} 区分</b>：{@code AuditPort} 是系统级操作审计
 * （通用），本端口是资产配置级业务审计（含 before/after 快照 + diff）。
 * 二者并行写入，互不替代。
 *
 * <p>⚠️ <b>审计写失败不得吞掉</b>：调用方应在事务结束前写审计，
 * 写失败应抛出让事务回滚（无审计的高危变更比变更失败更危险）。
 */
public interface AuditLogPort {

    /** 写一条业务审计日志。 */
    @NonNull AuditLog record(@NonNull AuditLog audit);

    /** 按 auditId 查询（只返回本租户可见的）。 */
    @NonNull Optional<AuditLog> findById(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @NonNull UUID auditId);

    /** 按资源查询（带事件类型过滤、游标分页，时间倒序）。 */
    @NonNull Page<AuditLog> list(@NonNull String scope,
                                  @Nullable String tenantId,
                                  @Nullable String resourceKind,
                                  @Nullable String resourceId,
                                  @Nullable String eventType,
                                  @Nullable String cursor,
                                  int limit);

    /** 通用查询（按操作人 / 时间范围 / 事件类型，游标分页）。 */
    @NonNull Page<AuditLog> query(@NonNull String scope,
                                   @Nullable String tenantId,
                                   @Nullable String eventType,
                                   @Nullable String operatorId,
                                   @Nullable String fromTime,
                                   @Nullable String toTime,
                                   @Nullable String cursor,
                                   int limit);
}

package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuditLogDO;

/**
 * 业务审计日志 Mapper（对应 {@code JdbcAuditLogRepository}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 MybatisRlsConfiguration），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code AuditLogMapper.xml}，仅 {@code ?} → {@code #{param}}；
 * {@code ::uuid} / {@code ::jsonb} / {@code ::timestamptz} / {@code COALESCE(?, now())} 全部保留。
 */
public interface AuditLogMapper extends BaseMapper<AuditLogDO> {

    /** INSERT ... RETURNING created_at；时间戳由 DB 生成（COALESCE 兜底），空结果由适配层 fail-closed。 */
    Optional<AuditLogDO> insertRecord(@Param("auditId") UUID auditId,
                                      @Param("scope") String scope,
                                      @Param("tenantId") UUID tenantId,
                                      @Param("resourceKind") String resourceKind,
                                      @Param("resourceId") String resourceId,
                                      @Param("resourceName") String resourceName,
                                      @Param("eventType") String eventType,
                                      @Param("fromVersion") Integer fromVersion,
                                      @Param("toVersion") Integer toVersion,
                                      @Param("fromState") String fromState,
                                      @Param("toState") String toState,
                                      @Param("beforeConfig") String beforeConfig,
                                      @Param("afterConfig") String afterConfig,
                                      @Param("diffSummary") String diffSummary,
                                      @Param("requestId") UUID requestId,
                                      @Param("releaseId") Long releaseId,
                                      @Param("operatorId") String operatorId,
                                      @Param("reason") String reason,
                                      @Param("createdAt") Instant createdAt);

    /** 按 scope + tenant_id（IS NOT DISTINCT FROM 兼容 PLATFORM 的 NULL）+ audit_id 查。 */
    Optional<AuditLogDO> findById(@Param("scope") String scope,
                                  @Param("tenantId") UUID tenantId,
                                  @Param("auditId") UUID auditId);

    /**
     * list / query 共用的动态查询（limit+1 探底，全序游标分页）。
     * 各过滤条件为 null / 空白时不追加该谓词，与原 StringBuilder 拼接逐字对应。
     */
    List<AuditLogDO> query(@Param("scope") String scope,
                           @Param("tenantId") UUID tenantId,
                           @Param("resourceKind") String resourceKind,
                           @Param("resourceId") String resourceId,
                           @Param("eventType") String eventType,
                           @Param("operatorId") String operatorId,
                           @Param("fromTime") String fromTime,
                           @Param("toTime") String toTime,
                           @Param("cursorCreatedAt") String cursorCreatedAt,
                           @Param("cursorAuditId") UUID cursorAuditId,
                           @Param("limitPlusOne") int limitPlusOne);
}

package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.model.ConfigPublishRequestDO;

/**
 * 配置变更申请单 Mapper（对应 {@code JdbcConfigPublishRequestRepository} 的 SQL 常量）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内。
 *
 * <p>⚠️ PLATFORM 作用域的 {@code tenantId} 为 null：SQL 使用
 * {@code IS NOT DISTINCT FROM #{tenantId}::uuid} 兼容，绝不能用 {@code =}。
 */
public interface ConfigPublishRequestMapper extends BaseMapper<ConfigPublishRequestDO> {

    /** INSERT ... RETURNING request_id::text, requested_at（空结果由适配层 fail-closed）。 */
    Optional<ConfigPublishRequestDO> insertRequest(@Param("tenantId") String tenantId,
                                                   @Param("scope") String scope,
                                                   @Param("operation") String operation,
                                                   @Param("payload") String payload,
                                                   @Param("reason") String reason,
                                                   @Param("requesterId") String requesterId);

    /**
     * INSERT（带变更前快照）... RETURNING request_id::text, requested_at。
     *
     * <p>⚠️ {@code beforePayload} 为 {@code null} 时写入 SQL NULL（无基线）。
     */
    Optional<ConfigPublishRequestDO> insertRequestWithBefore(@Param("tenantId") String tenantId,
                                                             @Param("scope") String scope,
                                                             @Param("operation") String operation,
                                                             @Param("payload") String payload,
                                                             @Param("beforePayload") String beforePayload,
                                                             @Param("reason") String reason,
                                                             @Param("requesterId") String requesterId);

    /** SELECT by request_id（租户维度由 IS NOT DISTINCT FROM + RLS 共同保证）。 */
    Optional<ConfigPublishRequestDO> find(@Param("tenantId") String tenantId,
                                          @Param("requestId") String requestId);

    /** 游标分页列表（limit+1 探底，全序 requested_at DESC, request_id DESC）。 */
    List<ConfigPublishRequestDO> listByCursor(@Param("tenantId") String tenantId,
                                              @Param("state") String state,
                                              @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                              @Param("cursorRequestId") String cursorRequestId,
                                              @Param("limitPlusOne") int limitPlusOne);

    /** FOR UPDATE 锁读（decide / transitionTo 写路径）。 */
    Optional<ConfigPublishRequestDO> loadForUpdate(@Param("tenantId") String tenantId,
                                                   @Param("requestId") String requestId);

    /** decide 的 UPDATE ... RETURNING（不返回资源关联列，与原实现逐字一致）。 */
    Optional<ConfigPublishRequestDO> updateDecision(@Param("tenantId") String tenantId,
                                                    @Param("requestId") String requestId,
                                                    @Param("targetState") String targetState,
                                                    @Param("note") String note,
                                                    @Param("approverId") String approverId);

    /** markApplied / markFailed 的 UPDATE ... RETURNING（含资源关联列与 fail_details）。 */
    Optional<ConfigPublishRequestDO> transition(@Param("tenantId") String tenantId,
                                                @Param("requestId") String requestId,
                                                @Param("targetState") String targetState,
                                                @Param("failureReason") String failureReason,
                                                @Param("failStage") String failStage,
                                                @Param("failDetailsJson") String failDetailsJson,
                                                @Param("appliedAt") Instant appliedAt);
}

package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.ExecutionIntentDO;

/**
 * 执行意图 Mapper（W2 迁移，对应 {@code JdbcExecutionIntentRepository}）。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描，所有方法都会经过
 * {@code RlsTenantBindingInterceptor} —— 调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 */
public interface ExecutionIntentMapper extends BaseMapper<ExecutionIntentDO> {

    /** 写入意图：ON CONFLICT (tenant_id, invocation_id) DO NOTHING（幂等）。 */
    int recordIntent(@Param("intentId") String intentId,
                     @Param("tenantId") String tenantId,
                     @Param("runId") String runId,
                     @Param("invocationId") String invocationId,
                     @Param("toolId") String toolId,
                     @Param("toolVersion") Integer toolVersion,
                     @Param("argsFingerprint") String argsFingerprint,
                     @Param("approvalId") String approvalId,
                     @Param("intentState") String intentState,
                     @Param("createdAt") Instant createdAt,
                     @Param("updatedAt") Instant updatedAt);

    /** 按 (tenant_id, invocation_id) 读取；SQL 自带显式租户谓词。 */
    Optional<ExecutionIntentDO> findByInvocation(@Param("tenantId") String tenantId,
                                                  @Param("invocationId") String invocationId);

    /** CAS 迁移：仅当 intent_state = expectedFrom 时更新，返回受影响行数。 */
    int transitionState(@Param("toState") String toState,
                        @Param("tenantId") String tenantId,
                        @Param("invocationId") String invocationId,
                        @Param("expectedFrom") String expectedFrom);

    /** 崩溃恢复扫描：未终结两态，按 created_at 升序，LIMIT 由适配层传入。 */
    List<ExecutionIntentDO> findPending(@Param("tenantId") String tenantId,
                                        @Param("limit") int limit);
}

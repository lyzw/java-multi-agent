package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.AgentInvocationDO;

/**
 * 委派调用记录 Mapper（对应 {@code JdbcAgentInvocationRepository}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 MybatisRlsConfiguration），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code AgentInvocationMapper.xml}，仅 {@code ?} → {@code #{param}}；
 * {@code ::uuid} / {@code ::jsonb} / {@code ON CONFLICT DO NOTHING} / CAS 条件 / 终态白名单全部保留。
 */
public interface AgentInvocationMapper extends BaseMapper<AgentInvocationDO> {

    /** SQL_INSERT：幂等插入（ON CONFLICT (tenant_id, invocation_id) DO NOTHING）。 */
    int insertInvocation(@Param("invocationId") String invocationId,
                         @Param("tenantId") String tenantId,
                         @Param("runId") String runId,
                         @Param("parentInvocationId") String parentInvocationId,
                         @Param("parentAgentId") String parentAgentId,
                         @Param("parentAgentVersion") int parentAgentVersion,
                         @Param("targetAgentId") String targetAgentId,
                         @Param("targetAgentVersion") int targetAgentVersion,
                         @Param("requestedTargetAgentId") String requestedTargetAgentId,
                         @Param("status") String status,
                         @Param("depth") int depth,
                         @Param("traceId") String traceId,
                         @Param("inputDigest") String inputDigest,
                         @Param("outputDigest") String outputDigest,
                         @Param("reasonCode") String reasonCode,
                         @Param("fencingToken") long fencingToken,
                         @Param("startedAt") Instant startedAt,
                         @Param("finishedAt") Instant finishedAt,
                         @Param("routingDecision") String routingDecision,
                         @Param("policyRef") String policyRef,
                         @Param("policyFingerprint") String policyFingerprint);

    /** SQL_FIND：按 tenant_id + invocation_id 查。 */
    Optional<AgentInvocationDO> find(@Param("tenantId") String tenantId,
                                     @Param("invocationId") String invocationId);

    /** SQL_LIST_BY_RUN：按 tenant_id + run_id 查，按 started_at / invocation_id 升序。 */
    List<AgentInvocationDO> listByRun(@Param("tenantId") String tenantId,
                                      @Param("runId") String runId);

    /** SQL_ADOPT_IN_FLIGHT：只提升在途且更旧世代的 fencing_token。 */
    int adoptInFlight(@Param("tenantId") String tenantId,
                      @Param("runId") String runId,
                      @Param("newFencingToken") long newFencingToken);

    /** SQL_MARK_TERMINAL：CAS 终态写入，返回受影响行数（0 = 未命中）。 */
    int markTerminal(@Param("tenantId") String tenantId,
                     @Param("invocationId") String invocationId,
                     @Param("status") String status,
                     @Param("reasonCode") String reasonCode,
                     @Param("outputDigest") String outputDigest,
                     @Param("finishedAt") Instant finishedAt,
                     @Param("fencingToken") long fencingToken);

    /** SQL_DIAGNOSE：CAS 未命中后区分「已是终态」与「token 过期」。 */
    Optional<AgentInvocationDO> diagnose(@Param("tenantId") String tenantId,
                                         @Param("invocationId") String invocationId);
}

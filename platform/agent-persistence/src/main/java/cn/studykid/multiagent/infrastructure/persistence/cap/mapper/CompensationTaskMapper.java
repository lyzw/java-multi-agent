package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CompensationTaskDO;

/**
 * 补偿任务 Mapper（对应 {@code JdbcCompensationTaskRepository}）。
 *
 * <p>五条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND_BY_ID}、
 * {@code SQL_FIND_PENDING}、{@code SQL_MARK_COMPLETED}、{@code SQL_MARK_FAILED}。
 * SQL 逐字搬迁到 {@code CompensationTaskMapper.xml}（仅 {@code ?} -> {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(tenantId)}，
 * 且每条语句都自带显式 {@code tenant_id = #{tenantId}::uuid} 谓词（第一道隔离，RLS 是第二道）。
 */
public interface CompensationTaskMapper extends BaseMapper<CompensationTaskDO> {

    /**
     * SQL_INSERT：入队一条补偿任务。
     *
     * <p>⚠️ 幂等靠 {@code ON CONFLICT (tenant_id, kind, target_id) DO NOTHING}，
     * 返回受影响行数（0 = 已存在同键任务，适配层映射为 {@code false}）。
     */
    int insertIfAbsent(@Param("taskId") String taskId,
                       @Param("tenantId") String tenantId,
                       @Param("kind") String kind,
                       @Param("targetId") String targetId,
                       @Param("invocationId") @Nullable String invocationId,
                       @Param("payload") @Nullable String payload,
                       @Param("state") String state,
                       @Param("attempts") int attempts,
                       @Param("lastError") @Nullable String lastError,
                       @Param("createdAt") Instant createdAt,
                       @Param("updatedAt") Instant updatedAt,
                       @Param("nextRetryAt") @Nullable Instant nextRetryAt);

    /** SQL_FIND_BY_ID：按 tenant_id + task_id 精确读取。 */
    Optional<CompensationTaskDO> findById(@Param("tenantId") String tenantId,
                                          @Param("taskId") String taskId);

    /**
     * SQL_FIND_PENDING：扫描「到期可重试」的任务。
     *
     * <p>⚠️ 条件 {@code next_retry_at IS NULL OR next_retry_at <= now()} 不可简化：
     * 无退避的直接重试会在下游持续故障时形成热循环。
     */
    List<CompensationTaskDO> findPending(@Param("tenantId") String tenantId,
                                         @Param("limit") int limit);

    /** SQL_MARK_COMPLETED：条件 UPDATE（仅 PENDING/RUNNING/FAILED），返回受影响行数。 */
    int markCompleted(@Param("tenantId") String tenantId,
                      @Param("taskId") String taskId);

    /** SQL_MARK_FAILED：条件 UPDATE（仅 PENDING/RUNNING），attempts 自增 + 退避时刻。 */
    int markFailed(@Param("tenantId") String tenantId,
                   @Param("taskId") String taskId,
                   @Param("error") String error,
                   @Param("nextRetryAt") Instant nextRetryAt);
}

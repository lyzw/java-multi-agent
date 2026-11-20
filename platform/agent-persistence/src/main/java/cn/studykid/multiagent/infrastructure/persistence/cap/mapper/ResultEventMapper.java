package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.ResultEventDO;

/**
 * 确定结果事件 Mapper（对应 {@code JdbcResultEventRepository} 的两条 SQL）。
 *
 * <p>两条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}（ON CONFLICT DO NOTHING）、
 * {@code SQL_FIND}（租户 + invocation_id 双谓词）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内。
 */
public interface ResultEventMapper extends BaseMapper<ResultEventDO> {

    /** SQL_INSERT：先写入者为准，ON CONFLICT DO NOTHING（不是 upsert）；返回受影响行数。 */
    int recordIfAbsent(@Param("eventId") String eventId,
                       @Param("tenantId") String tenantId,
                       @Param("runId") @Nullable String runId,
                       @Param("invocationId") String invocationId,
                       @Param("terminalEventId") @Nullable String terminalEventId,
                       @Param("resultStatus") String resultStatus,
                       @Param("resultPayload") @Nullable String resultPayload,
                       @Param("reasonCode") @Nullable String reasonCode,
                       @Param("createdAt") Instant createdAt);

    /** SQL_FIND：按租户 + invocation_id 读取（显式 tenant_id 谓词 = 第一道防线）。 */
    Optional<ResultEventDO> findByInvocation(@Param("tenantId") String tenantId,
                                             @Param("invocationId") String invocationId);
}

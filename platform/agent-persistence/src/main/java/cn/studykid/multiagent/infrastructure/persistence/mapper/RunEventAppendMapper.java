package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.model.RunEventDO;

/**
 * Run 事件追加 Mapper（对应 {@code JdbcRunEventAppendRepository} 的三条 SQL）。
 *
 * <p>三条语句与旧 JDBC 常量一一对应：{@code SQL_LOCK_RUN}（锁 run 行）、
 * {@code SQL_NEXT_SEQUENCE}（取 MAX+1）、{@code SQL_APPEND}（插入事件）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内 ——
 * 锁行 / 算序号 / 插入必须共用同一条事务连接，否则锁失效。
 */
public interface RunEventAppendMapper extends BaseMapper<RunEventDO> {

    /** SQL_LOCK_RUN：锁 run 行以串行化同一 Run 的并发追加；无行返回 Optional.empty()。 */
    Optional<String> lockRun(@Param("tenantId") String tenantId,
                             @Param("runId") String runId);

    /** SQL_NEXT_SEQUENCE：COALESCE(MAX(sequence_no), 0) + 1；无行时由适配层回退 1L。 */
    Long nextSequence(@Param("tenantId") String tenantId,
                      @Param("runId") String runId);

    /** SQL_APPEND：插入事件；payload 由适配层 payloadJson() 序列化后按 jsonb 写入。 */
    int insertEvent(@Param("tenantId") String tenantId,
                    @Param("runId") String runId,
                    @Param("sequenceNo") long sequenceNo,
                    @Param("eventType") String eventType,
                    @Param("payload") String payload);
}

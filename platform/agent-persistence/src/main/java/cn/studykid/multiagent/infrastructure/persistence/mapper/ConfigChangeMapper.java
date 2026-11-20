package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.model.ConfigChangeDO;

/**
 * 高危配置变更申请 Mapper（对应 {@code JdbcConfigChangeRepository} 的 SQL 常量）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内。
 */
public interface ConfigChangeMapper extends BaseMapper<ConfigChangeDO> {

    /** INSERT ... RETURNING change_id::text, requested_at（空结果由适配层 fail-closed）。 */
    Optional<ConfigChangeDO> insertChange(@Param("tenantId") String tenantId,
                                          @Param("key") String key,
                                          @Param("value") String value,
                                          @Param("valueType") String valueType,
                                          @Param("reason") String reason,
                                          @Param("requesterId") String requesterId);

    /** 游标分页列表（limit+1 探底，全序 requested_at DESC, change_id DESC）。 */
    List<ConfigChangeDO> listByCursor(@Param("tenantId") String tenantId,
                                      @Param("state") String state,
                                      @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                      @Param("cursorChangeId") String cursorChangeId,
                                      @Param("limitPlusOne") int limitPlusOne);

    /** FOR UPDATE 锁读（decide / apply 写路径，P1 不观测，租户隔离由 RLS 兜底）。 */
    Optional<ConfigChangeDO> loadForUpdate(@Param("tenantId") String tenantId,
                                           @Param("changeId") String changeId);

    /** approve / reject 的条件 UPDATE ... RETURNING（state 用 ::config_change_state cast）。 */
    Optional<ConfigChangeDO> updateDecision(@Param("tenantId") String tenantId,
                                            @Param("changeId") String changeId,
                                            @Param("targetState") String targetState,
                                            @Param("note") String note,
                                            @Param("approverId") String approverId);

    /** 置 APPLIED 的 UPDATE ... RETURNING（applied_at = now()）。 */
    Optional<ConfigChangeDO> markApplied(@Param("tenantId") String tenantId,
                                         @Param("changeId") String changeId);
}

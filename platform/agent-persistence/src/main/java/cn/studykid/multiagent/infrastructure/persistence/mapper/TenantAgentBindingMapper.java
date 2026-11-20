package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.TenantAgentBindingDO;

public interface TenantAgentBindingMapper {

    List<TenantAgentBindingDO> listByTenant(@Param("tenantId") String tenantId);

    List<TenantAgentBindingDO> find(@Param("tenantId") String tenantId,
                                    @Param("agentId") String agentId);

    /** INSERT ... ON CONFLICT DO UPDATE ... RETURNING (完整 7 列)。 */
    TenantAgentBindingDO bind(@Param("tenantId") String tenantId,
                              @Param("agentId") String agentId,
                              @Param("pinnedVersion") Integer pinnedVersion,
                              @Param("config") String config);

    /** UPDATE ... SET pinned_version = ?, config = ?::jsonb WHERE status='ACTIVE'。 */
    int update(@Param("tenantId") String tenantId,
               @Param("agentId") String agentId,
               @Param("pinnedVersion") Integer pinnedVersion,
               @Param("config") String config);

    /** 改状态 SUSPENDED WHERE status='ACTIVE'（条件原子，两个 reviewer 并发只有一个生效）。 */
    int unbind(@Param("tenantId") String tenantId,
               @Param("agentId") String agentId);
}

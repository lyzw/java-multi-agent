package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** platform.authz_decision_log 授权决策日志（仅追加）。 */
public interface AuthzDecisionLogMapper {
    int insert(@Param("occurredAt") Instant occurredAt, @Param("tenantId") String tenantId,
                @Param("principalId") String principalId, @Param("scope") String scope,
                @Param("action") String action, @Param("resourceType") String resourceType,
                @Param("permitted") boolean permitted, @Param("reasonCode") String reasonCode);

    /** 启动期零行写入探测：与 {@code JdbcAuthzDecisionLog#probe()} 等价。 */
    int probe();
}

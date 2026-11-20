package cn.studykid.multiagent.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/** agent_risk_event 写入（platform schema，走主数据源 RLS）。 */
public interface RiskEventMapper {
    int insert(@Param("traceId") String traceId,
               @Param("tenantId") String tenantId,
               @Param("eventType") String eventType,
               @Param("detailDigest") String detailDigest);
}

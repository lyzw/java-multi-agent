package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** 平台 Agent 目录（LATERAL JOIN 复杂查询）。 */
public interface PlatformAgentDirectoryMapper {
    List<PlatformAgentRow> listAgents(@Param("tenantIdFilter") String tenantIdFilter);
    record PlatformAgentRow(String tenantId, String agentId, String name, String description,
                            Integer versionCount, Integer latestVersionNumber,
                            Instant latestPublishedAt, int tenantCount) {}
}

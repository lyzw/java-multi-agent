package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.mapper.OpsMapper;

/** 平台 Agent 目录（跨租户只读，必须走 opsSqlSessionFactory + BYPASSRLS）。 */
@OpsMapper
public interface PlatformAgentDirectoryMapper {
    List<PlatformAgentRow> listAgents(@Param("tenantIdFilter") String tenantIdFilter);

    List<PlatformAgentChangeRow> listAgentChanges(@Param("agentId") String agentId,
                                                  @Param("limit") int limit);

    record PlatformAgentRow(String tenantId, String agentId, String name, String description,
                            int versionCount, Integer latestVersionNumber,
                            Instant latestPublishedAt, Integer defVersion, String defLifecycleState,
                            String defRoles, String defEnabledSkills, String defCapabilityTags,
                            String defOverrides, String defModelRef, String defSkillBundles,
                            String defInstructions, String defOrchestration,
                            String orchestrationMode, int tenantCount,
                            String pendingState, Instant pendingAt) {}

    /**
     * 变更记录行。
     *
     * @param beforeConfig 变更前配置（JSON 文本；首个版本 / 非发布类动作为 null）
     * @param afterConfig  变更后配置（JSON 文本；非发布类动作为 null）
     */
    record PlatformAgentChangeRow(String changeId, String category, String tenantId,
                                  String action, String state, Integer targetVersion,
                                  String reason, String actorId,
                                  Instant requestedAt, Instant appliedAt,
                                  String beforeConfig, String afterConfig) {}
}

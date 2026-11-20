package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** agent execution_snapshot 版本解析。 */
public interface ExecutionSnapshotMapper {
    Optional<SnapshotRow> resolve(@Param("tenantId") String tenantId, @Param("agentId") String agentId);
    record SnapshotRow(String snapshotId, int versionNumber) {}
}

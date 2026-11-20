package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * 租户级授权版本水位聚合结果（非表实体，对应 core 的 {@code AuthzWatermark}）。
 *
 * <p>⚠️ 这是单条聚合 SQL 的投影（count / count FILTER / COALESCE(max) / max），
 * 不对应任何一张表，因此没有 {@code @TableName}；由适配层转成 core record。
 */
public class AuthzWatermarkDO {

    private long subjectCount;
    private long revokedSubjects;
    private long maxAuthzVersion;
    private @Nullable Instant lastBumpAt;

    public long getSubjectCount() { return subjectCount; }
    public void setSubjectCount(long subjectCount) { this.subjectCount = subjectCount; }
    public long getRevokedSubjects() { return revokedSubjects; }
    public void setRevokedSubjects(long revokedSubjects) { this.revokedSubjects = revokedSubjects; }
    public long getMaxAuthzVersion() { return maxAuthzVersion; }
    public void setMaxAuthzVersion(long maxAuthzVersion) { this.maxAuthzVersion = maxAuthzVersion; }
    public @Nullable Instant getLastBumpAt() { return lastBumpAt; }
    public void setLastBumpAt(@Nullable Instant lastBumpAt) { this.lastBumpAt = lastBumpAt; }
}

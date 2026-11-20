package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import org.jspecify.annotations.Nullable;

/**
 * {@code agent_version} 主键读取投影（{@code AgentReleaseArchivePort} 的归档回读）。
 *
 * <p>⚠️ 这不是表实体：归档只关心版本行主键
 * （{@code INSERT ... ON CONFLICT DO NOTHING RETURNING version_id::text} 的返回值），
 * 不读整行定义 —— 故不建 {@code @TableName} DO，也不做通用 CRUD。
 */
public class AgentVersionDO {

    /** {@code version_id::text}：版本行主键。 */
    private @Nullable String versionId;

    public @Nullable String getVersionId() { return versionId; }
    public void setVersionId(@Nullable String versionId) { this.versionId = versionId; }
}

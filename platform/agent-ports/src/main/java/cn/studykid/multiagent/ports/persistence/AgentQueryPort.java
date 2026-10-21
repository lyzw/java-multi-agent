package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 查询端口（P4-D14 / G2 · FE-01 §P18 Agent 配置）。
 *
 * <p>⚠️ <b>为什么走端口而非直查</b>：{@code agent} / {@code agent_version}
 * 是租户隔离实体，所有查询须走 {@code tenantConnection} 绑定 RLS。
 * 绕过端口等于绕过隔离。
 *
 * <p>⚠️ <b>definition 是自由 JSONB</b>：V1 设计现状即自由 JSON，本端口不定义 schema，
 * 仅以文本透传（前端用 textarea 收 JSON，不做 schema 校验）。
 */
public interface AgentQueryPort {

    /**
     * 查询 Agent 列表（游标分页）。
     *
     * <p>每个 Agent 附带其最新版本信息（版本数、最新版本号、definition、发布时间）。
     *
     * @param cursor keyset 游标；{@code null} 表示从头
     * @param limit  每页大小（正数）
     */
    @NonNull Page<AgentItem> listAgents(@NonNull String tenantId,
                                        @Nullable String cursor,
                                        int limit);

    /**
     * 查询单个 Agent。
     *
     * @return 存在且属于该租户时返回；否则 {@code null}
     */
    @Nullable AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId);

    /** Agent 视图（含最新版本摘要）。 */
    record AgentItem(
            @NonNull String agentId,
            @NonNull String name,
            @Nullable String description,
            int versionCount,
            int latestVersionNumber,
            @Nullable String latestDefinition,
            @Nullable Instant latestPublishedAt
    ) {
    }

    /** Agent 版本视图（不可变快照）。 */
    record AgentVersionItem(
            @NonNull String versionId,
            int versionNumber,
            @NonNull String definition,
            @Nullable Instant publishedAt,
            @NonNull Instant createdAt
    ) {
    }

    /**
     * Agent 不存在或不属于该租户。
     *
     * <p>⚠️ 定义在端口而非基础设施：端点层需区分「404 不存在」与「409 状态冲突」，
     * 但不得依赖 {@code agent-infrastructure}（否则依赖方向反转）。
     */
    class AgentNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AgentNotFoundException(String agentId) {
            super("Agent 不存在: " + agentId);
        }
    }
}

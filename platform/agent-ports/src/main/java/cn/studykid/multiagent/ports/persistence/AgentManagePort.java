package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;

/**
 * Agent 管理端口（P4-D14 / G2 · FE-01 §P18 Agent 配置与发布）。
 *
 * <p>⚠️ <b>与 {@link AgentQueryPort} 的分工</b>：后者是「查看列表/详情」，
 * 本端口是「创建 Agent / 发布新版本」的写操作。二者操作同一组表，但语义不同。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：发布后不得原地修改，
 * 故本端口<b>不提供</b>「编辑已发布版本」方法。要改内容只能发新版本。
 *
 * <p>⚠️ <b>审计 fail-closed</b>：{@code AGENT_CREATE} / {@code AGENT_PUBLISH}
 * 走 {@code AuditPort.recordRequired}，审计写入失败即中止主流程
 * （无审计的发布等于无声变更，正是 R-1 要防）。
 */
public interface AgentManagePort {

    /**
     * 创建 Agent。
     *
     * @param creatorId 创建人（来自认证上下文，<b>不得</b>取自请求体）
     * @throws IllegalArgumentException           名称非法（空 / 超长）→ 端点转 400
     * @throws DuplicateAgentNameException       租户内重名（唯一约束冲突）→ 端点转 409
     */
    @NonNull AgentItem createAgent(@NonNull String tenantId,
                                   @NonNull String name,
                                   @Nullable String description,
                                   @NonNull String creatorId)
            throws DuplicateAgentNameException;

    /**
     * 发布新版本。
     *
     * <p>⚠️ 版本号 = 该 Agent 当前最大版本号 + 1；并发发布由
     * {@code (tenant_id, agent_id, version_number)} 唯一约束兜底，
     * 冲突抛 {@link VersionConflictException}（→ 409，前端提示「请刷新后重试」）。
     *
     * @param definition  版本定义（JSON 文本，自由 schema）
     * @param publisherId 发布人（来自认证上下文）
     * @throws AgentQueryPort.AgentNotFoundException Agent 不存在 / 不属于本租户
     * @throws VersionConflictException           并发版本号冲突
     */
    @NonNull AgentVersionItem publishVersion(@NonNull String tenantId,
                                             @NonNull String agentId,
                                             @NonNull String definition,
                                             @NonNull String publisherId);

    /**
     * 版本号并发冲突（→ 409）。
     *
     * <p>⚠️ 与「请求体不合法」（→ 400）区分：冲突是状态竞争，不是参数错误。
     */
    class VersionConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public VersionConflictException(String message) {
            super(message);
        }
    }

    /**
     * 租户内 Agent 重名（→ 409）。
     *
     * <p>⚠️ 与「名称非法」（→ 400）区分：重名是<b>状态竞争</b>
     * （另一协作者刚建了同名 Agent），而非请求体格式错误。
     * 端点捕获后返回 409 + 「请换一个名称」。
     */
    class DuplicateAgentNameException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateAgentNameException(String name) {
            super("租户内已存在同名 Agent: " + name);
        }
    }
}

package cn.studykid.multiagent.core.orchestration;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 父子 Agent 关系的<b>发布投影</b>（06 变更补充「关系数据」· {@code cap_agent_child_ref}）。
 *
 * <p>⚠️ <b>本表不是权威来源</b>：权威定义是不可变的 Agent release 里的
 * {@code childAgentRefs}。本表只是把「边」投影出来，便于查询与影响分析。
 * 因此本表<b>允许重建</b>，而 release 内容<b>永不改写</b>
 * （06「数据库迁移需支持旧 Agent 无编排字段的兼容读；回填不应把历史 release 内容原地改写」）。
 *
 * @param tenantId            租户标识
 * @param parentAgentId       父 Agent 标识
 * @param parentReleaseVersion 父 Agent 的固定版本号
 * @param childAgentId        子 Agent 标识
 * @param childReleaseVersion 子 Agent 的固定版本号
 * @param ordinal             声明顺序（SEQUENTIAL 模式按此顺序执行）
 * @param required            是否为必选子 Agent
 * @param createdAt           创建时间
 */
public record CapAgentChildRef(
        @NonNull String tenantId,
        @NonNull String parentAgentId,
        int parentReleaseVersion,
        @NonNull String childAgentId,
        int childReleaseVersion,
        int ordinal,
        boolean required,
        @NonNull Instant createdAt
) {

    public CapAgentChildRef {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户标识不能为空");
        }
        if (parentAgentId == null || parentAgentId.isBlank()) {
            throw new IllegalArgumentException("父 Agent 标识不能为空");
        }
        if (childAgentId == null || childAgentId.isBlank()) {
            throw new IllegalArgumentException("子 Agent 标识不能为空");
        }
        if (parentReleaseVersion <= 0 || childReleaseVersion <= 0) {
            throw new IllegalArgumentException("父/子 release 版本号必须为正数");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("声明顺序不能为负数，收到: " + ordinal);
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("创建时间不能为空");
        }
    }
}

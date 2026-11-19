package cn.studykid.multiagent.core.orchestration;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 父子 Agent 关系的<b>发布投影</b>（{@code cap_agent_child_ref}）。
 *
 * <p>Agent 是全局实体，父子关系同样是全局定义的一部分；租户可见性由绑定表决定。
 *
 * <p>⚠️ 本表不是权威来源：权威定义是不可变的 Agent release 里的 {@code childAgentRefs}。
 * 本表只是把「边」投影出来，便于查询与影响分析，因此允许重建，而 release 永不改写。
 *
 * @param parentAgentId       父 Agent 全局标识
 * @param parentReleaseVersion 父 Agent 的固定版本号
 * @param childAgentId        子 Agent 全局标识
 * @param childReleaseVersion 子 Agent 的固定版本号
 * @param ordinal             声明顺序
 * @param required            是否为必选子 Agent
 * @param createdAt           创建时间
 */
public record CapAgentChildRef(
        @NonNull String parentAgentId,
        int parentReleaseVersion,
        @NonNull String childAgentId,
        int childReleaseVersion,
        int ordinal,
        boolean required,
        @NonNull Instant createdAt
) {

    /** 兼容构造器：旧 tenantId 被忽略，Agent 关系已是全局定义。 */
    @Deprecated(forRemoval = true)
    public CapAgentChildRef(@NonNull String ignoredTenantId,
                            @NonNull String parentAgentId,
                            int parentReleaseVersion,
                            @NonNull String childAgentId,
                            int childReleaseVersion,
                            int ordinal,
                            boolean required,
                            @NonNull Instant createdAt) {
        this(parentAgentId, parentReleaseVersion, childAgentId, childReleaseVersion,
                ordinal, required, createdAt);
    }

    public CapAgentChildRef {
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

package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;

/**
 * 固定版本的子 Agent 引用（06 变更补充「AgentDefinition 增补」· {@code childAgentRefs[]}）。
 *
 * <p>⚠️ <b>必须固定 release</b>：本对象只接受<b>确定的版本号</b>，
 * 不接受 {@code latest} 之类的漂移别名。理由：
 * <ul>
 *   <li>在途 Run 绑定的是<b>发布闭包快照</b>（06 §3.1）——
 *       若目标版本可漂移，子 Agent 换版本后父 Run 的行为会在无人授权的情况下改变；</li>
 *   <li>MA-T02 要求「引用 latest/未发布版本 → 拒绝发布」；
 *       MA-T08 要求「运行期间子 Agent 发布新版本 → 当前 Run 继续绑定原 release」。</li>
 * </ul>
 *
 * @param agentId        子 Agent 标识（<b>同租户</b>，跨租户引用一律拒绝）
 * @param releaseVersion 子 Agent 的<b>固定版本号</b>（>0）
 * @param required       是否为必选子 Agent（false = 可选，失败不必然导致父失败）
 */
public record ChildAgentRef(
        @NonNull String agentId,
        int releaseVersion,
        boolean required
) {

    public ChildAgentRef {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("子 Agent 标识不能为空");
        }
        if (releaseVersion <= 0) {
            // ⚠️ 不接受 latest/漂移别名：委派目标必须在发布时固定 release。
            throw new IllegalArgumentException(
                    "子 Agent 引用必须固定版本号（禁止 latest），收到: " + releaseVersion);
        }
    }

    /**
     * @param agentId        子 Agent 标识
     * @param releaseVersion 固定版本号
     * @return 必选子 Agent 引用
     */
    public static @NonNull ChildAgentRef required(@NonNull String agentId, int releaseVersion) {
        return new ChildAgentRef(agentId, releaseVersion, true);
    }

    /**
     * @param agentId        子 Agent 标识
     * @param releaseVersion 固定版本号
     * @return 可选子 Agent 引用
     */
    public static @NonNull ChildAgentRef optional(@NonNull String agentId, int releaseVersion) {
        return new ChildAgentRef(agentId, releaseVersion, false);
    }

    /**
     * @return 稳定的引用标识（{@code agentId@version}），用于环检测与去重
     */
    public @NonNull String key() {
        return agentId + "@" + releaseVersion;
    }
}

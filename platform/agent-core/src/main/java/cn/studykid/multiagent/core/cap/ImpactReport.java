package cn.studykid.multiagent.core.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.DependencyGraph.EdgeKind;

/**
 * 影响分析报告（管理面 M7 · §5.3 依赖影响分析）。
 *
 * <p>回答两个问题：
 * <ul>
 *   <li><b>出向依赖</b>（{@link #dependencies()}）：目标引用了谁 —— 用于详情页「依赖」页签；</li>
 *   <li><b>入向影响</b>（{@link #dependents()} / {@link #chains()} / {@link #affectedAgentIds()}）：
 *       改/删目标会命中谁 —— 用于删除守卫判据与影响分析页。</li>
 * </ul>
 *
 * <p>⚠️ <b>链路沿入向回溯到 Agent 终止</b>：V1 没有任何对象引用 Agent
 * （{@code inherits} 写入即拒，N1），故受影响面止于 Agent。
 * {@code TENANT_OF} 是所有权边而非引用边，不计入依赖/影响。
 *
 * <p>⚠️ <b>作用域</b>：{@code affectedAgentIds} 仅含<b>调用方租户</b>的 Agent
 * （{@code cap_agent_def} RLS 隔离）；平台级 Skill/Tool/MCP 的引用者是全平台的，
 * 但「最终命中哪些 Agent」只能按租户回答 —— 其他租户的命中面对本租户不可见。
 */
public record ImpactReport(
        @NonNull String entityType,
        @NonNull String entityId,
        @NonNull String entityLabel,
        boolean entityExists,
        @NonNull List<Ref> dependencies,
        @NonNull List<Ref> dependents,
        @NonNull List<Chain> chains,
        @NonNull List<String> affectedAgentIds,
        @NonNull List<DanglingRef> danglingRefs
) {

    /** 实体不存在时的空报告（控制器据此返回 404）。 */
    public static @NonNull ImpactReport notFound(@NonNull String entityType,
                                                 @NonNull String entityId) {
        return new ImpactReport(entityType, entityId, entityId, false,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 一条引用（方向由所在列表体现）。
     *
     * @param type  对象类型（{@code AGENT} / {@code TOOL} / {@code SKILL} / {@code MCP_SERVER}）
     * @param id    对象业务 id
     * @param label 展示名
     * @param kind  边类型（引用语义）
     */
    public record Ref(
            @NonNull String type,
            @NonNull String id,
            @NonNull String label,
            @NonNull EdgeKind kind
    ) {
    }

    /**
     * 一条传递影响链：从目标沿入向回溯到一个受影响 Agent。
     *
     * <p>例：删 Tool {@code t} → Skill {@code s} 声明它 → Agent {@code a} 启用 {@code s}，
     * 则 {@code path = [t, s, a]}，{@code agentId = a}。
     *
     * @param path        链路节点（目标 → … → 末端 Agent）
     * @param agentId     末端受影响 Agent id
     * @param agentLabel  末端受影响 Agent 展示名
     */
    public record Chain(
            @NonNull List<Step> path,
            @NonNull String agentId,
            @NonNull String agentLabel
    ) {

        public record Step(
                @NonNull String type,
                @NonNull String id,
                @NonNull String label
        ) {
        }
    }

    /**
     * 悬空引用告警：目标引用了目录/租户中不存在的对象。
     *
     * <p>⚠️ 这是<b>配置缺陷信号</b>：授权路径对悬空引用 fail-closed 拒绝
     * （{@code ClosureResolutionException}），管理面必须显式暴露，让运维在拒绝发生前修掉。
     */
    public record DanglingRef(
            @NonNull String type,
            @NonNull String id,
            @NonNull EdgeKind via
    ) {
    }
}

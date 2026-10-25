package cn.studykid.multiagent.core.orchestration;

/**
 * Agent 编排模式（多级 Agent v1.1 · 06 变更补充「AgentDefinition 增补」）。
 *
 * <p>⚠️ <b>缺省等价于 {@link #LEAF}</b>：旧 Agent 定义没有 {@code orchestration} 字段时，
 * 按 LEAF 解析（MA-T01）。这意味着「多级 Agent 是<b>显式开启</b>的能力」——
 * 没有声明编排的 Agent 绝不参与委派，也绝不获得任何子 Agent 的能力。
 *
 * <p>⚠️ <b>委派关系不是授权关系</b>：模式只描述「这个 Agent 允许把工作交给谁」，
 * 不代表被委派方获得调用方权限。实际授权在每一跳由
 * {@code AuthorizationService} + {@code authzVersion} 重新判定（FR-025）。
 */
public enum OrchestrationMode {

    /**
     * 叶子 Agent：不发起任何委派（缺省语义）。
     *
     * <p>唯一允许 {@code childAgentRefs} 为空的模式；声明了子引用即为配置错误。
     */
    LEAF,

    /**
     * 路由 Agent：按<b>固定版本</b>的路由策略选择子 Agent。
     *
     * <p>⚠️ <b>模型输出只是候选</b>：路由模型给出的目标必须落在本 Agent 声明的
     * {@code childAgentRefs} 内，且由服务端再次校验后才可委派（NFR-013 / MA-T05）。
     */
    ROUTER,

    /**
     * 串行编排 Agent：按 {@code childAgentRefs} 声明顺序依次执行子 Agent，
     * 并按输出契约聚合结果（FR-027）。
     *
     * <p>V1 为<b>有界串行</b>：不开放并行委派与动态发现（00 修订说明「本次修订原则」）。
     */
    SEQUENTIAL
}

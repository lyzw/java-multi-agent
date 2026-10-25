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
     * 串行编排 Agent：父 Agent 可在其声明的子 Agent 集合内<b>逐次委派</b>。
     *
     * <p>⚠️ <b>「串行」与「聚合」由父 Agent 自己完成，框架不代劳</b>
     * （评审前置决策 1 · 2026-10-25 拍板：`SEQUENTIAL` <b>不纳入 P2</b>）：
     * <ul>
     *   <li>框架提供的只有三件事：<b>子 Agent 集合</b>（委派拓扑）、
     *       <b>逐跳重新授权</b>（每一跳独立判定授权）、<b>逐跳资源封顶</b>
     *       （深度 / 次数 / 超时 / 预算）。</li>
     *   <li>父 Agent 本身是<b>多轮执行体</b>：它可以在不同轮次依次委派给
     *       不同子 Agent，并把各轮结果合成一份输出 —— 这是父 Agent 的
     *       <b>模型行为</b>，不是框架的功能。</li>
     *   <li>框架<b>不会</b>因为声明了多个子 Agent 就替父把子 Agent 全部跑一遍
     *       再合成：<b>一次委派 = 一个目标 = 一条调用记录 = 一次子执行</b>
     *       （见 {@code DelegationExecutorTest#multipleDeclaredChildrenStillRunOnePerDelegation}）。</li>
     * </ul>
     *
     * <p>⚠️ <b>不要再写「按输出契约聚合结果」这类承诺</b>：框架<b>没有</b>多子结果聚合
     * 这个动作（FR-027 的聚合部分<b>明确不做</b>）。此前 javadoc 承诺了它而实现没有，
     * 那与第十四批之前 ROUTER「名不副实」是同构缺陷 —— 名字许诺了做不到的事，
     * 配置方就会按那个许诺来设计。
     *
     * <p>⚠️ <b>聚合结果的校验点在哪</b>：父 Agent 合成后的输出，是它<b>被调用时</b>
     * 的返回值，由<b>调用方声明的链接契约</b>（{@code resultContractRef}，见
     * {@link AgentOrchestration}）校验 —— 每一跳的契约都由**调用方**声明。
     *
     * <p>V1 不开放并行委派与动态发现（00 修订说明「本次修订原则」）。
     */
    SEQUENTIAL
}

package cn.studykid.multiagent.ports.invocation;

/**
 * 能力调用入口（Channel）："同一功能的两种录入"在判定层的统一表达。
 *
 * <p><b>为什么需要它</b>：admin-console 里同一个能力（如 RAG 语料录入、Agent 编排对话）
 * 既能由平台运营在表单里直接操作，也能由 Agent 在对话里代为调用。二者若各写一套身份 /
 * 作用域判定，任何一处漏一道闸就是跨身份域泄露。本枚举把"谁触发的"收敛成三个规范值，
 * 让下游能按入口决定只读/可写、是否允许推理草稿。
 *
 * <p><b>与身份域的关系</b>：Channel 描述的是<b>触发方式</b>，不描述主体身份域
 * （PLATFORM / TENANT 在 {@link InvocationContext#domain()} 表达）。一个
 * {@code PLATFORM_DIRECT} 入口的主体必是平台用户；{@code AGENT_ORCH} 入口的主体
 * 是平台对话的父主体或租户用户；{@code TENANT_SESSION} 是租户前台自带身份。
 * 三者都归一到同一个 {@link InvocationContext}，由 {@link PolicyGate} 统一裁决。
 */
public enum Channel {
    /** 平台控制台表单 / 上传直接操作（含 G/T/A 三档作用域选择）。 */
    PLATFORM_DIRECT,
    /** Agent 编排对话里由 Agent 代为调用能力（须持代理票据）。 */
    AGENT_ORCH,
    /** 租户前台会话，身份来自已验签租户令牌。 */
    TENANT_SESSION;

    /**
     * 该入口是否允许下发模型原始推理草稿（{@code event: thinking}）。
     *
     * <p>只有平台控制台直接操作允许；Agent 编排与租户会话恒否 —— 对应
     * {@code AgentChatController} 既有纪律：平台链 thinkingAllowed=true，租户链=false。
     * 由入口派生而非随上下文流转，杜绝草稿跨渠道携带。
     */
    public boolean thinkingAllowed() {
        return this == PLATFORM_DIRECT;
    }
}

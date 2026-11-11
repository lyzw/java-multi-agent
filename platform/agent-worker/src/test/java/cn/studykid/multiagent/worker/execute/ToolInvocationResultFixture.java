package cn.studykid.multiagent.worker.execute;

import cn.studykid.multiagent.worker.execute.ToolInvocationGate.ToolInvocationResult;

/**
 * 测试夹具：在 {@code ToolInvocationGate} 同包内构造<b>真实</b>的 {@link ToolInvocationResult}。
 *
 * <p><b>为什么需要单独一个同包夹具</b>：{@code ToolInvocationResult} 的 {@code record*}
 * 写入方法是包级私有（只有闸门自己能写），{@code worker.agentchat} 测试包无法直接调用。
 * 与其 mock 一个 final 类（依赖 inline mock maker，且默认空集合会掩盖回灌语义），
 * 不如在闸门同包提供一个公开夹具，让编排器测试拿到<b>结构真实</b>的成功 / 被拒 / 失败结果。
 *
 * <p>这样编排器测试断言的「工具结果回灌成不可信消息对」才建立在真实结果对象上，
 * 而不是建立在 mock 的返回值上（mock 错了会变成自证式假绿）。
 */
public final class ToolInvocationResultFixture {

    private ToolInvocationResultFixture() {
    }

    /** 空结果（闸门被调用但没有任何工具产生结局）——用于验证组件级失败回灌。 */
    public static ToolInvocationResult empty() {
        return new ToolInvocationResult();
    }

    /** 成功且有输出。 */
    public static ToolInvocationResult succeeded(String tool, String output) {
        ToolInvocationResult result = new ToolInvocationResult();
        result.recordSucceeded(tool, output);
        return result;
    }

    /** 成功但无输出。 */
    public static ToolInvocationResult succeededSilently(String tool) {
        ToolInvocationResult result = new ToolInvocationResult();
        result.recordSucceeded(tool);
        return result;
    }

    /** 被授权策略拒绝（未执行）。 */
    public static ToolInvocationResult rejected(String tool, String reason) {
        ToolInvocationResult result = new ToolInvocationResult();
        result.recordRejected(tool, reason);
        return result;
    }

    /** 执行失败（结果确定失败）。 */
    public static ToolInvocationResult failed(String tool, String reason) {
        ToolInvocationResult result = new ToolInvocationResult();
        result.recordFailed(tool, reason);
        return result;
    }
}

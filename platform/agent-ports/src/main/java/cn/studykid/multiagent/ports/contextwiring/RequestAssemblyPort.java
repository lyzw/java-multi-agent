package cn.studykid.multiagent.ports.contextwiring;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * Worker 两条执行链路（L1 {@code RunExecutionLoop} / L4 {@code WorkerChildAgentRunner}）
 * 的<b>统一请求装配入口</b>（ADR-013 D38 / 开发计划 §6.4-②，M7-b）。
 *
 * <p>⚠️ <b>为什么不是 M6 冻结的 {@code ContextAssemblerPort}</b>：那个端口的签名是
 * {@code assemble(ContextSpec)}，而 {@code ContextSpec} 刻意只承载六块上下文，
 * <b>不含</b> {@code ModelRequest} 的非上下文字段（temperature / maxTokens / timeout /
 * toolSchemas(Map) / userContent / modelId）。若装配器仅凭 {@code ContextSpec} 重建请求，
 * 关闭态就不再是「与没有压缩功能逐位一致」，而是「悄悄换了一套请求参数」（[D11] 失真）。
 * 本端口因此携带一条<b>由调用方按 legacy 路径完整构造好的 {@code legacyRequest}</b>：
 * 关闭态原样返回该对象（同一引用），开启态只切换上下文字段。
 *
 * <p>⚠️ <b>关闭等价桥是构造级保证</b>：全局开关 {@code agent.context.enabled=false}
 * 时实现必须<b>短路</b>——零策略解析、零能力读取、零预算、零管线、零布局、零摘要、
 * 零指标、零事件，直接返回 {@code input.legacyRequest()} 同一引用。
 *
 * <p>⚠️ <b>中性端口</b>：签名只使用 {@code agent-ports} 已有类型，不泄漏
 * {@code agent-context} 的算法层类型；实现由组合根（platform-bootstrap）提供，
 * worker 通过与实现同包的中性 DTO 交互，维持 ADR-011 依赖方向。
 *
 * <p>⚠️ <b>fail-closed</b>：冻结策略非法 / 预算超硬顶 / 装配链路异常时抛
 * {@link RequestAssemblyException}（带对外稳定原因码），调用方必须按该码拒绝该 Run，
 * ⛔ 绝不静默回退 legacy（那会让「配错策略」被伪装成「策略正常」，灰度证据全失真）。
 *
 * <p>⚠️ <b>行为分流靠引用身份</b>：实现用
 * {@code result.request() != input.legacyRequest()} 推导
 * {@link RequestAssemblyResult#assembled()}，调用方据此选择「legacy trim 事件 +
 * legacy 成本估算」还是「onContextCompacted + 全窗口成本预留」；
 * ⛔ 不在 worker 里再抄一份 enabled 判定（两份判定迟早漂移）。
 */
public interface RequestAssemblyPort {

    /**
     * 按已冻结的策略快照装配一次模型请求。
     *
     * @return 唯一送模型的请求与行为分流标记；关闭态返回
     *         {@link RequestAssemblyResult#passthrough(ModelRequest)}（同一引用）
     * @throws RequestAssemblyException 策略 / 预算 / 装配失败（fail-closed）
     */
    @NonNull RequestAssemblyResult assemble(@NonNull RequestAssemblyInput input);
}

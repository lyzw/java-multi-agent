package cn.studykid.multiagent.ports.agentchat;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;

/**
 * 请求级流式 Agent 编排端口。
 *
 * <p>一次调用在一次 HTTP 请求内完成「路由 → RAG → Skill 指引 → Tool 循环 →
 * 流式回答 → 审计落库」，通过 {@link Sink} 实时推 13 类事件。
 * 实现方位于 {@code agent-worker}（与真实工具闸门 / 模型装配器同模块），
 * 由 {@code platform-bootstrap} 装配、{@code agent-api} 控制器只依赖本端口。
 *
 * <p><b>不派发 Worker</b>：本端口<b>不写 Outbox / MQ</b>。审计 Run 以
 * {@code RUNNING} 占位 + 终态收敛两段式写入，并使用专用
 * {@code lease_owner} 让 {@code claimTasks} 显式跳过 ——
 * {@link AgentChatAuditPort} 有完整说明。
 */
public interface AgentChatOrchestratorPort {

    /** 事件汇聚点（控制器把它桥接到 SseEmitter）。 */
    @FunctionalInterface
    interface Sink {
        void accept(@NonNull AgentChatEvent event);
    }

    /**
     * 客户端已断开：继续推帧只会抛 IOException，故以本信号提前收口。
     *
     * <p>编排器捕获后必须仍然收敛审计（记 FAILED / CLIENT_DISCONNECTED），
     * 但不再向 sink 推任何帧；它<b>不是</b>模型失败，也不是服务端 5xx。
     */
    class SinkClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SinkClosedException(@NonNull String message) {
            super(message);
        }

        public SinkClosedException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 执行一次编排。方法返回时流已收口（成功发 {@code done}，失败发 {@code error + done}）。
     *
     * @param context 已验签上下文（tenantId 不可来自请求体）
     * @param request 请求体
     * @param sink    事件汇聚点
     */
    void run(@NonNull Context context,
             @NonNull AgentChatRequest request,
             @NonNull Sink sink);
}

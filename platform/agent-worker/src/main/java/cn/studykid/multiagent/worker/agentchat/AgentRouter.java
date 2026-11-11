package cn.studykid.multiagent.worker.agentchat;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;

/**
 * 请求级 Agent 路由器。
 *
 * <p><b>与既有 {@code RoutingEngine} 的分工</b>：后者是多级 Agent 的<b>委派</b>决策引擎
 * （按固定版本策略在父声明的子 Agent 中选目标，含规则/模型候选/冲突裁决）。
 * 本接口解决的是<b>入口级</b>问题：一次用户请求该由哪个已发布 Agent 承接。
 * 两者不互相替代，避免把「委派准入」塞进入口路由形成第二套准入真相。
 *
 * <p><b>fail-closed</b>：无法从候选中确定唯一目标时抛 {@link RoutingException}，
 * 编排器转为 {@code ROUTE_FAILED} 终帧；绝不「随便挑一个」把请求交给错误 Agent。
 */
public interface AgentRouter {

    /**
     * @param tenantId          已验签租户
     * @param message           本轮用户输入
     * @param explicitAgentId   调用方显式指定（仍须命中候选集）
     * @param candidates        该租户下<b>已发布且有执行快照</b>的 Agent
     * @return 选中目标 + 人读理由 + 候选列表（供 route 事件展示）
     */
    @NonNull Decision route(@NonNull String tenantId,
                            @NonNull String message,
                            @Nullable String explicitAgentId,
                            @NonNull List<AgentQueryPort.AgentItem> candidates);

    /** 路由结论。 */
    record Decision(@NonNull String agentId, @NonNull String reason,
                    @NonNull List<AgentChatEvent.Candidate> candidates) {
    }

    /** 路由无法给出确定结论（配置缺失 / 模型返回非法目标）。 */
    class RoutingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RoutingException(@NonNull String message) {
            super(message);
        }

        public RoutingException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}

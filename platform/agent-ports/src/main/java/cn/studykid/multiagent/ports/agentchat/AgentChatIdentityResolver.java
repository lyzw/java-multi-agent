package cn.studykid.multiagent.ports.agentchat;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;

/**
 * Agent 对话端点的身份解析器（组合根实现，控制器只依赖本接口）。
 *
 * <p><b>为什么需要它</b>：两条安全链的请求属性常量定义在
 * {@code platform-bootstrap.SecurityConfiguration}，而控制器位于 {@code agent-api}，
 * 直接引用会造成「bootstrap → api → bootstrap」的编译期环。
 * 本端口把「从已验签请求属性取身份」这件事留在组合根，控制器只拿到可信结果。
 *
 * <p><b>唯一可信来源</b>：Spring Security 过滤器写入的请求属性 +
 * 已验签的 {@code Authorization} 头。<b>绝不</b>从请求体的 tenantId 取身份。
 */
public interface AgentChatIdentityResolver {

    /**
     * 解析身份。
     *
     * @param platformChain  true = 平台链（aud=admin-console-api，主体不持租户）；
     *                       false = 租户链（aud=agent-platform）
     * @param requestedTenantId 平台链目标租户（租户链必须为 null）
     * @return 可信上下文
     * @throws UnauthorizedAgentChatException 请求属性缺失 / 身份域不匹配 / 令牌为空
     * @throws InvalidAgentChatTargetException 平台链目标租户非法（非 UUID / 不存在）
     */
    @NonNull Context resolve(boolean platformChain,
                                              @Nullable String requestedTenantId);

    /** 请求未经有效验签，或身份域与端点不匹配。 */
    class UnauthorizedAgentChatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnauthorizedAgentChatException(@NonNull String message) {
            super(message);
        }
    }

    /** 平台链显式指定的目标租户不合法或不存在。 */
    class InvalidAgentChatTargetException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidAgentChatTargetException(@NonNull String message) {
            super(message);
        }
    }
}

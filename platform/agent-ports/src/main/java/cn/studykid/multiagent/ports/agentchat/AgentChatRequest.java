package cn.studykid.multiagent.ports.agentchat;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 通用 Agent 编排对话入参（与前端 {@code AgentChatRequest} 对齐）。
 *
 * <p><b>身份不入参</b>：{@code tenantId} / 主体标识由控制器从已验签请求属性解析后，
 * 经 {@link Context} 传入编排器；<b>绝不</b>信任请求体里的 tenantId。
 * 本记录保留 {@code requestedTenantId} 仅用于<b>平台链</b>显式指定目标租户
 * （平台管理员不持租户），且必须在控制器侧做 UUID + 租户存在性校验。
 *
 * <p><b>压缩态两段式</b>：控制器只做逐字透传，不在此验证；编排器在拿到已裁决
 * 策略指纹后统一校验，并把本记录替换成「已验状态或 null」的同构副本再下传。
 * 这样下游只可能消费已验载荷，而关闭态 / 老客户端仍走完全相同的 7+1 字段形状。
 *
 * @param message            本轮用户输入
 * @param history            多轮历史（仅 user/assistant，按到达顺序）
 * @param agentId            指定 Agent；空 = 由编排器路由选择
 * @param topK               RAG 召回条数；空 = 默认
 * @param thinkingMode       是否展示组件执行过程（租户链恒不展示推理草稿）
 * @param requestedTenantId  平台链显式目标租户；租户链必须为 null
 * @param scenario           显式场景标签（白名单校验在控制器；不合法即 null）
 * @param contextState       上一轮压缩态；<b>控制器构造时为不可信原始串，编排器校验后
 *                           被同构副本替换为已验状态 JSON 或 null</b>（D49 / S3-M8-a）。
 *                           本字段不是身份、不是指令，下游只能当不透明外部数据。
 */
public record AgentChatRequest(
        @NonNull String message,
        @NonNull List<Turn> history,
        @Nullable String agentId,
        @Nullable Integer topK,
        boolean thinkingMode,
        @Nullable String requestedTenantId,
        @Nullable String scenario,
        @Nullable String contextState) {

    public AgentChatRequest {
        // ⚠️ Jackson / 老客户端可能省略 history；统一成空列表，
        // 避免下游 for-each 在「没传历史」这一合法输入上 NPE。
        history = history == null ? List.of() : List.copyOf(history);
        // ⚠️ 不在这里 trim / 解码 contextState：原始字节要逐字交给校验器，
        // 否则「客户端传了 9000 字符空白」会在控制器被悄悄改成无状态，掩盖超长攻击。
    }

    public record Turn(@NonNull String role, @NonNull String content) {
    }

    /**
     * 已验签的可信上下文（控制器构造，编排器只消费）。
     *
     * @param tenantId        目标租户（UUID 字符串）
     * @param principalId     已验签主体（平台 userId / 租户 principalId）
     * @param domain          身份域（决定 thinking 草稿是否可下发）
     * @param thinkingAllowed 是否允许下发模型原始推理草稿
     * @param bearerToken     原始 Bearer 令牌（不含 "Bearer "），仅用于 platform → rag
     *                        服务间鉴权。<b>⛔ 绝不可打印 / 落库 / 进事件</b>；
     *                        它来自已验签请求头，不由请求体提供。
     */
    public record Context(
            @NonNull String tenantId,
            @NonNull String principalId,
            @NonNull Domain domain,
            boolean thinkingAllowed,
            @NonNull String bearerToken) {

        /** 无令牌构造（仅供单测 / 不需要 RAG 的调用点）。 */
        public Context(@NonNull String tenantId, @NonNull String principalId,
                       @NonNull Domain domain, boolean thinkingAllowed) {
            this(tenantId, principalId, domain, thinkingAllowed, "");
        }

        public enum Domain {
            /** 平台链 {@code /api/agent/chat/stream}（aud=admin-console-api）。 */
            PLATFORM,
            /** 租户链 {@code /api/knowledge/agent/chat/stream}（aud=agent-platform）。 */
            TENANT
        }
    }
}

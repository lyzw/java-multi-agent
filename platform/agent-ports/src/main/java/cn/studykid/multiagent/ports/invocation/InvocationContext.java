package cn.studykid.multiagent.ports.invocation;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 统一调用上下文（全案枢纽）：两条入口归一后的唯一可信判定输入。
 *
 * <p><b>构造纪律</b>：只能由 IdentityResolver + ScopeResolver 在请求线程用<b>已验签</b>的
 * 主体与作用域构造；能力执行器不得接收裸 tenantId / 裸令牌 / 裸请求头。本记录把既有
 * {@code AgentChatRequest.Context} 的可信字段扩展为"能力 + 入口 + 作用域 + 票据"视图，
 * 不改动旧类型（8 处调用点保持兼容），由适配层并行产出。
 *
 * <p><b>不可变 + 自校验</b>：紧凑构造器强制以下不变量，违反即 IllegalArgumentException
 * （视为编程错误 / 非法入参，fail-closed）：
 * <ul>
 *   <li>scope=TENANT 时 tenantId 必填且为 UUID 形态；其余档位 tenantId 必须为 null；</li>
 *   <li>principalId / capability / correlationId 非空；</li>
 *   <li>thinkingAllowed 只能由 {@link Channel#thinkingAllowed()} 派生，禁止调用方自报；</li>
 *   <li>ALL 档恒只读（由 {@link Scope#allowsWrite()} 与 PolicyGate 双重保证）。</li>
 * </ul>
 *
 * @param principalId    已验签主体（平台 userId / 租户 principalId / 被扮演成员）
 * @param domain         主体身份域
 * @param channel        触发入口
 * @param scope          显式作用域
 * @param tenantId       TENANT 档的目标租户 UUID；其余档为 null
 * @param capability     能力标识（如 rag.corpus.ingest）
 * @param purpose        本次调用目的（写审计）
 * @param correlationId  贯通双入口与分域审计的关联 ID
 * @param scopeExplicit  作用域是否由调用方显式声明。false = 来自平台默认租户兜底，
 *                       仅允许只读能力（关闭 R1 隐式作用域写泄露）
 * @param ticket         已验签票据声明；需要票据的能力由 PolicyGate 强制校验
 */
public record InvocationContext(
        @NonNull String principalId,
        @NonNull Domain domain,
        @NonNull Channel channel,
        @NonNull Scope scope,
        @Nullable String tenantId,
        @NonNull String capability,
        @NonNull String purpose,
        @NonNull String correlationId,
        boolean scopeExplicit,
        @Nullable TicketClaims ticket) {

    public enum Domain {
        PLATFORM,
        TENANT
    }

    public InvocationContext {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(correlationId, "correlationId");
        if (principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不得为空");
        }
        if (capability.isBlank()) {
            throw new IllegalArgumentException("capability 不得为空");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId 不得为空");
        }
        if (scope.requiresTenantId()) {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("TENANT 档必须显式提供 tenantId");
            }
            try {
                java.util.UUID.fromString(tenantId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("tenantId 不是合法 UUID: " + tenantId, e);
            }
        } else if (tenantId != null && !tenantId.isBlank()) {
            throw new IllegalArgumentException("非 TENANT 档不得携带 tenantId（" + scope + "）");
        }
    }

    /** 推理草稿可见性由入口派生，绝不随上下文 / 票据流转。 */
    public boolean thinkingAllowed() {
        return channel.thinkingAllowed();
    }

    /** 是否写意图（供 PolicyGate 与能力注册判断；本类型不单独决定可否写）。 */
    public boolean isWrite() {
        return WriteIntent.fromPurpose(purpose) == WriteIntent.WRITE;
    }

    /** 目的里的写意图标记（字符串协议，避免引入额外布尔参数破坏构造器数量）。 */
    public enum WriteIntent {
        READ,
        WRITE;

        /**
         * 从 purpose 解析写意图：以 "write:" 前缀标记写；其余一律视为读（默认拒绝写）。
         * 这样任何未标注的能力调用都不会被误判为可写。
         */
        public static @NonNull WriteIntent fromPurpose(@NonNull String purpose) {
            return purpose.startsWith("write:") ? WRITE : READ;
        }
    }

    /**
     * 桥接：从既有 AgentChatRequest.Context 的可信字段构造统一上下文。
     *
     * <p>⚠️ 由平台链 resolver 调用，{@code scopeExplicit=false} 表示租户来自默认兜底。
     * 本方法把 thinking 可见性重新绑定到 channel，忽略旧 ctx 的 thinkingAllowed，
     * 从而关闭"草稿随上下文跨渠道流转"的 R2 风险。
     */
    public static @NonNull InvocationContext fromChat(
            @NonNull String principalId,
            @NonNull Domain domain,
            @NonNull Channel channel,
            @NonNull String tenantId,
            @NonNull String capability,
            @NonNull String purpose,
            @NonNull String correlationId,
            boolean scopeExplicit,
            @Nullable TicketClaims ticket) {
        return new InvocationContext(principalId, domain, channel, Scope.TENANT, tenantId,
                capability, purpose, correlationId, scopeExplicit, ticket);
    }
}

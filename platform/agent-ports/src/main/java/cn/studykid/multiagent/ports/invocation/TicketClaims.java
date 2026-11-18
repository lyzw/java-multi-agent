package cn.studykid.multiagent.ports.invocation;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 已验签票据声明的统一形态（R3：收敛执行票 / 治理票 / 代理票的语义）。
 *
 * <p><b>为什么统一</b>：现状两张票分别由两个 Service 签发、claims 结构不同、下游各自解析，
 * 任何一处把 {@code rag_scope} 当成"任意平台令牌也有"、或把执行票当平台身份用，都是提权。
 * 本记录给三种票一个共同的<b>不可变声明视图</b>，由 {@link #matches(InvocationContext, String)}
 * 做"票与本次调用"的三元组匹配，收口交叉误用风险。
 *
 * <p><b>不负责签发 / 验签</b>：本类型是纯值对象，签名仍由 JwtTokenService /
 * PlatformJwtTokenService 完成；调用方在验签成功后把 claims 映射为本记录，再交给
 * {@link PolicyGate} 裁决。它保证的是"语义一致"，不替代密码学。
 *
 * @param jti        票据标识（审计 reason_code 同源）
 * @param domain     签发票的令牌域：PLATFORM / TENANT，见 {@link Domain}
 * @param principalId 被扮演 / 被授权的主体
 * @param scope      票据声明的作用域（治理票/代理票有；执行票为 TENANT）
 * @param capability 票据被授权的能力标识；空 = 不限制能力（如执行票）
 * @param channel    票据被授权的入口；空 = 不限制入口
 * @param expiresAt  到期时刻（服务端时钟判定）
 */
public record TicketClaims(
        @NonNull String jti,
        @NonNull Domain domain,
        @NonNull String principalId,
        @Nullable String scope,
        @Nullable String capability,
        @Nullable String channel,
        @NonNull Instant expiresAt) {

    public enum Domain {
        PLATFORM,
        TENANT
    }

    public TicketClaims {
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (jti.isBlank()) {
            throw new IllegalArgumentException("jti 不得为空");
        }
    }

    /**
     * 票据是否匹配本次调用（三元组 + 主体 + 未过期）。
     *
     * <p>规则：
     * <ol>
     *   <li>票据域必须等于调用上下文的身份域（治理票 PLATFORM 不能打租户调用）；</li>
     *   <li>票据主体必须等于上下文主体（不可冒用他人票）；</li>
     *   <li>票据声明的 capability / channel / scope 若非空，必须逐项等于本次调用；</li>
     *   <li>已过期的票一律不匹配（服务端时钟）。</li>
     * </ol>
     * 任一不满足返回稳定的 reasonCode，供 PolicyGate 归一为 403 并写审计。
     */
    public @Nullable String mismatch(@NonNull InvocationContext ctx, @NonNull Instant now) {
        if (!now.isBefore(expiresAt)) {
            return "TICKET_EXPIRED";
        }
        if (domain.name().equals(ctx.domain().name()) == false) {
            return "TICKET_DOMAIN_MISMATCH";
        }
        if (!principalId.equals(ctx.principalId())) {
            return "TICKET_SUBJECT_MISMATCH";
        }
        if (capability != null && !capability.equals(ctx.capability())) {
            return "TICKET_CAPABILITY_MISMATCH";
        }
        if (channel != null && !channel.equals(ctx.channel().name())) {
            return "TICKET_CHANNEL_MISMATCH";
        }
        if (scope != null && !scope.equals(ctx.scope().auditValue())) {
            return "TICKET_SCOPE_MISMATCH";
        }
        return null;
    }

    /** 是否声明了作用域（治理票 / 代理票有，执行票无）。 */
    public boolean declaresScope() {
        return scope != null && !scope.isBlank();
    }
}

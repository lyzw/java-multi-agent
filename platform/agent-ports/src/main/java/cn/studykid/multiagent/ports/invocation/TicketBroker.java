package cn.studykid.multiagent.ports.invocation;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.invocation.InvocationContext.Domain;

/**
 * 统一票据契约（第 2 刀）：把"票代表什么"从三处签发逻辑里抽成一个共同视图。
 *
 * <p><b>本类只表达契约，不做签发 / 验签</b>：签发仍由 {@code PlatformExecutionTicketService}
 * （执行票）、{@code PlatformRagGovernanceTicketService}（治理票）完成，二者已提供
 * {@code toClaims()} 归一入口；验签由 JwtTokenService / PlatformJwtTokenService 完成。
 * 本类提供的是<b>代理票的声明规格</b>（Q3：复用执行票结构 + capability/channel claim），
 * 以及统一的"票与调用匹配"判定入口，供各能力与 PolicyGate 共用。
 *
 * <p>把它放进 ports 而非某个 Service：避免执行票 / 治理票两个 Service 互相依赖，
 * 也避免下游再写第三份 claims 解析。
 */
public final class TicketBroker {

    private TicketBroker() {
    }

    /** 代理票声明：Agent 编排代为调用能力时申请，继承父主体与目标作用域。 */
    public record DelegationSpec(
            @NonNull String parentPrincipalId,
            @NonNull Domain domain,
            @NonNull String targetTenantId,
            @NonNull String capability,
            @NonNull Channel channel,
            @NonNull String purpose,
            @NonNull String correlationId) {
    }

    /**
     * 从代理票规格构造统一声明（真正签发由调用方交给 JwtTokenService 完成）。
     *
     * <p>⚠️ 域与入口的一致性在这里强制：TENANT_SESSION 入口只能是 TENANT 域主体；
     * 其它组合按调用方给的域走，PolicyGate 会在裁决时再核一遍 channel 白名单。
     */
    public static @NonNull TicketClaims delegationClaims(
            @NonNull DelegationSpec spec,
            @NonNull String jti,
            @NonNull Instant expiresAt) {
        Domain domain = spec.channel() == Channel.TENANT_SESSION
                ? Domain.TENANT
                : spec.domain();
        return new TicketClaims(
                jti,
                domain == Domain.PLATFORM
                        ? TicketClaims.Domain.PLATFORM
                        : TicketClaims.Domain.TENANT,
                spec.parentPrincipalId(),
                cn.studykid.multiagent.ports.invocation.Scope.TENANT.auditValue(),
                spec.capability(),
                spec.channel().name(),
                expiresAt);
    }

    /**
     * 统一"票与本次调用"匹配：返回 null = 匹配，否则为稳定 reasonCode。
     * 这是 {@link TicketClaims#mismatch(InvocationContext, java.time.Instant)} 的别名，
     * 给业务侧一个不依赖具体票据类型的调用入口。
     */
    public static @Nullable String match(
            @NonNull TicketClaims claims,
            @NonNull InvocationContext ctx,
            @NonNull Instant now) {
        return claims.mismatch(ctx, now);
    }
}

package cn.studykid.multiagent.api.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.ports.invocation.Capability;
import cn.studykid.multiagent.ports.invocation.Channel;
import cn.studykid.multiagent.ports.invocation.InvocationContext;
import cn.studykid.multiagent.ports.invocation.PolicyGate;
import cn.studykid.multiagent.ports.invocation.Scope;
import cn.studykid.multiagent.ports.invocation.TicketBroker;
import cn.studykid.multiagent.ports.invocation.TicketBroker.DelegationSpec;
import cn.studykid.multiagent.ports.invocation.TicketClaims;

/**
 * 代理票签发服务（第 2 刀增量 · 双入口方案 §4.2）。
 *
 * <p><b>一句话</b>：Agent 编排代为调用能力时，<b>永远不直接持有平台令牌写数据，
 * 也不借用他人的租户令牌</b> —— 只能在这里申请一张「代理票」：
 * 绑定父主体（谁让 Agent 做的）、绑定目标租户（继承父上下文，Agent 不得自报）、
 * 绑定能力与入口（capability / channel 三元组）、60 秒 TTL。
 *
 * <p><b>签发前闸门（fail-closed，逐条对应双入口方案 §5）</b>：
 * <ol>
 *   <li><b>租户必须继承父上下文</b>：{@code spec.targetTenantId} 与父上下文租户
 *       不一致即拒（{@code DELEGATION_TENANT_MISMATCH}）—— Agent 被诱导传
 *       {@code tenantId=其它租户} 时在签发前就失败，不发生任何写；</li>
 *   <li><b>能力必须在注册表</b>：未知能力拒（{@code CAPABILITY_UNKNOWN}），
 *       不靠 Agent 自觉；</li>
 *   <li><b>PolicyGate 统一裁决</b>：{@code allowedChannels} 是否含
 *       {@code AGENT_ORCH}、scope 是否合法、Q1 全局写禁令等七道闸门全部过一遍，
 *       拒绝时透传稳定 reasonCode；</li>
 *   <li><b>TTL 60 秒</b>：与执行票同级 —— 代理票与租户口令等价可用，必须短时。</li>
 * </ol>
 *
 * <p><b>claims 结构（Q3：复用执行票结构）</b>：标准租户 JWT
 * （aud=agent-platform、tenant_id、roles=父主体角色交集、authz_version、jti）
 * 外加 {@code capability} / {@code channel} / {@code scope} 三个绑定声明，
 * 下游按 {@link TicketClaims#mismatch(InvocationContext, Instant)} 做三元组匹配。
 *
 * <p><b>审计边界</b>：代理票签发发生在编排服务端（非平台特权操作），平台审计
 * 不在此落库；{@link DelegationTicket} 携带 {@code jti + correlationId}，
 * 由编排层的既有 Run 审计贯通（双入口方案 A7：correlationId 与父对话串起来）。
 */
@Component
public class DelegationTicketService {

    private static final Logger log = LoggerFactory.getLogger(DelegationTicketService.class);

    /** 代理票 TTL：60 秒（与执行票同级；§3.3 统一票据语义）。 */
    private static final Duration DELEGATION_TTL = Duration.ofSeconds(60);

    private final JwtTokenService tokens;
    private final PolicyGate policyGate;
    private final boolean enabled;

    public DelegationTicketService(@NonNull JwtTokenService tokens,
                                   @NonNull PolicyGate policyGate,
                                   @Value("${platform.security.delegation-ticket.enabled:true}")
                                   boolean enabled) {
        this.tokens = tokens;
        this.policyGate = policyGate;
        this.enabled = enabled;
    }

    /** 代理票签发被拒：与 {@link PlatformExecutionTicketService.TicketRejectedException} 同构。 */
    public static final class DelegationRejectedException extends RuntimeException {

        private final @NonNull String reasonCode;

        public DelegationRejectedException(@NonNull String reasonCode, @NonNull String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        /** 稳定原因码（与 PolicyGate reasonCode 同源）。 */
        public @NonNull String reasonCode() {
            return reasonCode;
        }
    }

    /** 一次代理票签发结果：令牌本体 + 审计定位字段 + 已验签声明视图。 */
    public record DelegationTicket(
            @NonNull String token,
            @NonNull String jti,
            @NonNull Instant expiresAt,
            @NonNull TicketClaims claims,
            @NonNull String correlationId,
            @NonNull String purpose) {
    }

    /**
     * 为 Agent 编排代为调用能力签发一张代理票。
     *
     * @param parent 父上下文（Agent 编排所在会话的已验签租户身份）
     * @param spec   代理票规格（父主体 / 目标租户 / 能力 / 入口 / 目的 / 关联 id）
     * @throws DelegationRejectedException 通道关闭 / 租户不一致 / 能力未知 / 策略拒绝
     */
    public DelegationTicket issue(@NonNull TenantContext parent,
                                  @NonNull DelegationSpec spec) {
        // 闸门 1：通道开关。fail-closed：关闭即拒，不静默降级为「父令牌直通」。
        if (!enabled) {
            throw new DelegationRejectedException("CHANNEL_DISABLED",
                    "代理票通道未启用（platform.security.delegation-ticket.enabled=false）");
        }

        // 闸门 2：目标租户必须继承父上下文 —— Agent 自报租户一律不接受（§5 反诱导）。
        if (!spec.targetTenantId().equals(parent.tenantId())) {
            throw new DelegationRejectedException("DELEGATION_TENANT_MISMATCH",
                    "代理票目标租户须继承父上下文：目标 " + spec.targetTenantId()
                            + " ≠ 父租户 " + parent.tenantId());
        }
        if (!spec.parentPrincipalId().equals(parent.principalId())) {
            throw new DelegationRejectedException("DELEGATION_SUBJECT_MISMATCH",
                    "代理票父主体须与父上下文一致：" + spec.parentPrincipalId()
                            + " ≠ " + parent.principalId());
        }

        // 闸门 3：能力必须在注册表（未知能力 fail-closed；PolicyGate 闸门 1 会再核一遍，
        // 这里先行拒绝是为了给出与「通道未启用」同样清晰、不掺杂内部结构的错误语义）。
        Capability.find(Capability.defaults(), spec.capability())
                .orElseThrow(() -> new DelegationRejectedException("CAPABILITY_UNKNOWN",
                        "未知能力，拒绝签发代理票：" + spec.capability()));

        // 闸门 4：PolicyGate 统一裁决（allowedChannels / scope / Q1 全局写禁令等）。
        // ⚠️ 签发时刻的「鸡生蛋」：注册表里 requiresTicket 的能力（如 rag.corpus.ingest）
        //    在裁决时要求 ctx 已带票据 —— 而这张代理票正是现在要签的东西。
        //    解法：先用预览 jti 构造声明视图放进 ctx，让闸门 7 顺带完成
        //    「预览声明与本次调用三元组自洽」的自检，再拿同一份声明去签发。
        Instant now = Instant.now();
        TicketClaims previewClaims = TicketBroker.delegationClaims(
                spec, UUID.randomUUID().toString(), now.plus(DELEGATION_TTL));
        InvocationContext ctx = new InvocationContext(
                parent.principalId(),
                InvocationContext.Domain.TENANT,
                spec.channel(),
                Scope.TENANT,
                parent.tenantId(),
                spec.capability(),
                spec.purpose(),
                spec.correlationId(),
                true,
                previewClaims);
        PolicyGate.Decision decision = policyGate.decide(ctx);
        if (decision.denied()) {
            throw new DelegationRejectedException(decision.reasonCode(),
                    "代理票申请被策略闸门拒绝：" + decision.reasonCode()
                            + "（能力 " + spec.capability() + " 入口 " + spec.channel() + "）");
        }

        // 签发：复用执行票结构 + capability/channel/scope 绑定声明（Q3）。
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("capability", previewClaims.capability());
        extra.put("channel", previewClaims.channel());
        extra.put("scope", previewClaims.scope());
        JwtTokenService.Issued issued =
                tokens.issueDetailed(parent, extra, now, DELEGATION_TTL);

        // previewClaims 里的 jti 是预览值；以签发器真正生成的 jti 为准重算声明视图，
        // 保证「令牌里的 jti = 审计与匹配用的 jti」。
        TicketClaims claims = new TicketClaims(issued.jti(), previewClaims.domain(),
                previewClaims.principalId(), previewClaims.scope(),
                previewClaims.capability(), previewClaims.channel(), issued.expiresAt());

        log.info("签发代理票: parent={}, tenant={}, capability={}, channel={}, jti={}",
                parent.principalId(), parent.tenantId(), spec.capability(),
                spec.channel(), issued.jti());

        return new DelegationTicket(issued.token(), issued.jti(), issued.expiresAt(),
                claims, spec.correlationId(), spec.purpose());
    }
}

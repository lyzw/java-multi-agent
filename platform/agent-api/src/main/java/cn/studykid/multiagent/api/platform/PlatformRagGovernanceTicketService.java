package cn.studykid.multiagent.api.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * RAG 全局语料<b>治理票据</b>签发服务（RAG-D11 第四刀 · 第二刀 §2.3）。
 *
 * <p><b>为什么需要它</b>：RAG 管理面第一刀之后能区分「调用方是平台域」，
 * 但平台域身份本身<b>不等于</b>「被授权改动全局语料」——
 * 平台登录令牌谁都能拿去调几十个平台端点，把它当「全局语料的写授权」用，
 * 等于把一次具体的治理动作折算成一整段登录态的权限。
 * 票据只多做一件事：在签名保护的 claim 里写明<b>这一档作用域被授权了</b>，
 * 并且是一枚<b>短时效、空角色</b>的独立凭据。
 *
 * <p><b>⚠️ 四道闸门，顺序即安全边界（全部在签发之前）</b>：
 * <ol>
 *   <li><b>授权</b>：{@code platform.resource.manage}（矩阵驱动、默认拒绝）打在
 *       资源 {@code rag_corpus} 上。排在最前，未授权者不该从
 *       「通道是否启用」「档位是否合法」上读出任何区别，也不该消耗限流令牌；</li>
 *   <li><b>配置开关</b>（{@code platform.security.rag-governance-ticket.enabled}，默认开）：
 *       关闭即 503，<b>仍然写审计</b>——「有权限的人在用一个被关的通道」是可查的事实；</li>
 *   <li><b>档位合法性</b>：本轮只签 {@code PLATFORM}。
 *       {@code ALL}（跨租户盘点）属第三刀且<b>只有读语义</b>，
 *       现在签出去就是一张没人能正确解释的票，故 409 而不是「先签了再说」；</li>
 *   <li><b>限流</b>（按<b>平台主体</b>计量，见 {@link RateLimitPort.Buckets#PLATFORM_RAG_GOVERNANCE_TICKET}）。</li>
 * </ol>
 *
 * <p><b>为什么授权复用 {@code platform.resource.manage} 而不新增权限码</b>：
 * 权限矩阵的运行期权威源是代码（{@code PlatformRolePermissionMatrix}），
 * 新增码要同时改矩阵、DB 治理副本并把绑定跑在共享库上（owner 执行），
 * 而这件事的语义本就是「管理平台级资源」——全局语料正是平台级资源。
 * 若日后要把「改目录」与「改全局语料」分给不同角色，再拆码不迟；
 * 拆之前<b>不得</b>让 RAG 侧误以为票据代表细粒度授权（票据只声明作用域，不声明权限码）。
 *
 * <p><b>审计字段落位</b>（{@code platform_audit_event} 无租户列）：
 * {@code target_type=rag_governance_ticket}、{@code target_id=NULL}
 * （全局语料桶不是某一行资源，哨兵租户 ID 又属于 RAG 库，塞进平台的 uuid 列是假的归因）、
 * {@code reason_code=} 票据 {@code jti}（ALLOW）或拒绝码（DENY）。
 * 事后凭 jti 可把一张泄露的票对回到「谁、哪档、何时签的」。
 */
@Component
public class PlatformRagGovernanceTicketService {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformRagGovernanceTicketService.class);

    /** 授权动作码（闸门 1）。 */
    static final String REQUIRED_PERMISSION = Namespaces.Platform.RESOURCE_MANAGE;

    /** 被治理资源的类型标识（授权与限流都不用它，只有审计用它做检索键）。 */
    static final String RESOURCE_TYPE = "rag_corpus";

    /** 审计动作名：<b>刻意不同于权限码</b>——按它检索到的是「签过治理票据」这件事。 */
    static final String AUDIT_ACTION = "platform.rag.governance_ticket";

    /** 审计的 target_type 取值。 */
    private static final String TARGET_TYPE = "rag_governance_ticket";

    /** 本轮唯一可签的档位：平台全局语料。 */
    static final String SCOPE_PLATFORM = "PLATFORM";

    /** 第三刀才有语义的档位：跨租户盘点（只读）。 */
    static final String SCOPE_ALL = "ALL";

    /** TTL 下界：与执行票证同源（低于此值等于每次写操作都要重新点一次「取票」）。 */
    static final long MIN_TTL_SECONDS = 60L;

    /**
     * TTL 上界（15 分钟）。
     *
     * <p><b>为什么必须有上界而不是只设下界</b>：票据是<b>绕过租户档默认视图</b>的凭据，
     * 有效期越长，「一次取票、整天写全局桶」就越接近一个常驻特权。
     * 配置写超即拒绝启动（与执行票证的下界同一手法）：
     * 静默截断会让运维以为调大了，实际没有。
     */
    static final long MAX_TTL_SECONDS = 900L;

    private final PlatformSecurity security;
    private final PlatformJwtTokenService tokens;
    private final RateLimitPort rateLimiter;
    private final PlatformAuditPort audit;
    private final boolean enabled;
    private final Duration ttl;

    public PlatformRagGovernanceTicketService(
            PlatformSecurity security,
            PlatformJwtTokenService tokens,
            RateLimitPort rateLimiter,
            PlatformAuditPort audit,
            @Value("${platform.security.rag-governance-ticket.enabled:true}") boolean enabled,
            @Value("${platform.security.rag-governance-ticket.ttl-seconds:300}") long ttlSeconds) {
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "platform.security.rag-governance-ticket.ttl-seconds 须在 "
                            + MIN_TTL_SECONDS + "~" + MAX_TTL_SECONDS + " 之间，当前 " + ttlSeconds);
        }
        this.security = security;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 一张已签发的治理票据（{@code accessToken} 是凭据，绝不落日志与审计正文）。 */
    public record GovernanceTicket(@NonNull String accessToken,
                                   @NonNull String scope,
                                   @NonNull String platformUserId,
                                   @NonNull Instant expiresAt,
                                   @NonNull String jti) {
    }

    /**
     * 票据闸门拒绝：状态码 + 原因码 + 可读文案三件套。
     *
     * <p>与 {@code PlatformExecutionTicketService.TicketRejectedException} 同一取舍：
     * {@code ResponseStatusException} 的 reason 在本项目被 {@code ApiExceptionHandler}
     * 刻意不回显，而这里每种拒绝的<b>下一步动作都不同</b>
     * （换档 / 开开关 / 等资源 / 找超管授权），塌成同一条消息等于让操作者猜。
     */
    public static final class GovernanceTicketRejectedException extends RuntimeException {

        private final transient @NonNull HttpStatus status;
        private final @NonNull String reasonCode;

        public GovernanceTicketRejectedException(@NonNull HttpStatus status,
                                                 @NonNull String reasonCode,
                                                 @NonNull String message) {
            super(message);
            this.status = status;
            this.reasonCode = reasonCode;
        }

        public @NonNull HttpStatus status() {
            return status;
        }

        /** 与平台审计 {@code reason_code} 同值的稳定码。 */
        public @NonNull String reasonCode() {
            return reasonCode;
        }
    }

    /**
     * 为指定作用域签出一枚短时治理票据。
     *
     * @param principal 平台主体（必须是已认证的平台登录态）
     * @param rawScope  请求声明的作用域（{@code PLATFORM}；大小写不敏感）
     * @return 票据
     * @throws GovernanceTicketRejectedException 400 档位缺失或非法 / 503 通道关闭 /
     *                                           409 档位本轮不可签 / 429 限流（含 fail-closed）；
     *                                           除 400 外每种拒绝都已写一条 DENY 审计。
     *                                           授权不足由 {@code PlatformSecurity}
     *                                           抛 {@code AccessDeniedException}（→ 403）
     */
    public GovernanceTicket issue(@NonNull PlatformPrincipal principal,
                                  @Nullable String rawScope) {
        String scope = rawScope == null ? "" : rawScope.trim().toUpperCase(Locale.ROOT);

        // 闸门 1：授权（默认拒绝）。
        security.require(principal, REQUIRED_PERMISSION, RESOURCE_TYPE, scope);

        // 闸门 2：配置开关。关闭也要留痕（见类注释）。
        if (!enabled) {
            throw denyAndReject(principal, HttpStatus.SERVICE_UNAVAILABLE,
                    "CHANNEL_DISABLED",
                    "治理票据通道未启用（platform.security.rag-governance-ticket.enabled=false）");
        }

        // 闸门 3：档位。先判空再判未知，是为了让「前端没带 scope」这种集成缺陷
        // 不被归类成「这个档位不允许」——两者要去修的地方完全不同。
        if (scope.isEmpty()) {
            throw new GovernanceTicketRejectedException(HttpStatus.BAD_REQUEST, "MISSING_SCOPE",
                    "scope 必填：票据必须指名一档（PLATFORM），归属不能由服务端猜");
        }
        if (!SCOPE_PLATFORM.equals(scope) && !SCOPE_ALL.equals(scope)) {
            throw new GovernanceTicketRejectedException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE",
                    "未知作用域 " + scope + "（合法值：PLATFORM / ALL）");
        }
        if (SCOPE_ALL.equals(scope)) {
            throw denyAndReject(principal, HttpStatus.CONFLICT,
                    "SCOPE_NOT_MINTABLE",
                    "ALL（跨租户盘点）是第三刀的只读档位，本轮不存在「拿票据往全部租户写」"
                            + "这条通道；要治理平台全局语料请签 PLATFORM");
        }

        // 闸门 4：限流（按平台主体，见常量注释）。FAIL_CLOSED 与 REJECTED 一律拒绝。
        RateLimitPort.Decision decision = rateLimiter.check(
                principal.userId(), RateLimitPort.Buckets.PLATFORM_RAG_GOVERNANCE_TICKET);
        if (decision != RateLimitPort.Decision.ALLOWED) {
            throw decision == RateLimitPort.Decision.REJECTED
                    ? denyAndReject(principal, HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMITED", "治理票据签发过于频繁，请稍后再试")
                    : denyAndReject(principal, HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMIT_UNAVAILABLE", "限流组件不可用，票据签发已按 fail-closed 拒绝");
        }

        PlatformJwtTokenService.Issued issued =
                tokens.issueRagGovernanceTicket(principal, Instant.now(), ttl, scope);
        allow(principal, issued.jti());
        log.info("签发 RAG 治理票据: platformUser={}, scope={}, jti={}, expiresAt={}",
                principal.userId(), scope, issued.jti(), issued.expiresAt());

        return new GovernanceTicket(issued.token(), scope, principal.userId(),
                issued.expiresAt(), issued.jti());
    }

    // ── 审计 ───────────────────────────────────────────────

    /**
     * 写一条 DENY 审计并返回（不抛出）对应的拒绝异常。
     *
     * <p>返回而非抛出是为了让调用点写成 {@code throw denyAndReject(...)}：
     * <b>审计与响应同处一行</b>，拆成「先 audit 再 throw」早晚会出现某处忘了留痕。
     */
    private GovernanceTicketRejectedException denyAndReject(PlatformPrincipal principal,
                                                            HttpStatus status,
                                                            String reasonCode, String message) {
        record(principal, "DENY", reasonCode);
        return new GovernanceTicketRejectedException(status, reasonCode, message);
    }

    private void allow(PlatformPrincipal principal, String jti) {
        record(principal, "ALLOW", jti);
    }

    /**
     * 落一条平台审计。
     *
     * <p>{@code reason_code} 同时承载「ALLOW 的 jti」与「DENY 的原因码」两种语义，
     * 与执行票证通道完全同构（靠 {@code decision} 区分读法）。
     *
     * <p>⚠️ <b>档位（scope）本轮不入列</b>：{@code platform_audit_event} 没有可用的
     * 自由文本列——{@code ip} 是 {@code inet}、{@code target_id}/{@code correlation_id}
     * 是 {@code uuid}、{@code reason_code} 已被 jti／拒绝码占用。
     * 本轮可签档位唯一（PLATFORM），所以「哪档」不丢失；
     * <b>第三刀放开 ALL 之前必须先给审计补一列</b>（或补 action 约定），
     * 否则「签了哪档」只能靠推断，而推断出来的审计不能作为追责证据。
     */
    private void record(PlatformPrincipal principal, String decision, String reasonCode) {
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), principal.userId(), "PLATFORM_USER",
                AUDIT_ACTION, TARGET_TYPE, null, decision, reasonCode,
                null, null, Instant.now()));
    }
}

package cn.studykid.multiagent.api.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.ports.security.PrincipalStore;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 跨域执行票证服务（admin-console AI 实验室的执行通道）。
 *
 * <p><b>为什么需要它</b>：{@code /api/runs} 是<b>租户域</b>端点，租户身份取自
 * 已验签令牌；平台令牌（{@code aud=admin-console-api}）打过去必然 401 ——
 * 那是受众隔离按设计生效（见 {@code JwtTokenService#verify}），不是缺陷。
 * 实验室要在<b>不保存租户口令</b>的前提下测真实执行，唯一诚实的做法是
 * 由平台侧<b>签发</b>一枚短时租户令牌：权限判定集中在签发这一次调用上，
 * 而不是给租户域开一个「看懂平台令牌」的旁路。
 *
 * <p><b>⚠️ 风险口径</b>：{@code platform.tenant.execution_ticket} 等于
 * 「可扮演任意租户的成员发起执行」，因此本服务的四道闸门<b>顺序即安全边界</b>，
 * 缺一不可，且都在签发之前：
 * <ol>
 *   <li><b>授权</b>（矩阵驱动、默认拒绝，仅超级管理员持有该权限码）——排在最前，
 *       未授权者不该从「通道是否启用」「参数是否合法」上读出任何区别，也不该消耗限流令牌；</li>
 *   <li><b>配置开关</b>（{@code platform.security.execution-ticket.enabled}）：
 *       关闭即 503，且仍然写审计 —— 「有权限的人在用一个被关的通道」是可查的事实；</li>
 *   <li><b>限流</b>（按目标租户计量，见 {@link RateLimitPort.Buckets#PLATFORM_EXECUTION_TICKET}）；</li>
 *   <li><b>真实性</b>：租户存在且生命周期为 ACTIVE，主体是该租户下真实存在、
 *       状态 ACTIVE 的成员 —— 计费与审计归属必须指向真人，不能是一个虚构身份。</li>
 * </ol>
 *
 * <p><b>⚠️ 票证角色取交集而非取常量</b>：设计固定为 {@code AGENT_AUTHOR}
 * （实测它恰好覆盖 RUN_CREATE / RUN_READ / RESULT_READ / EVENT_SUBSCRIBE，
 * 是能发起执行的最小角色）。但「固定」不等于「授权」：若主体库里没有这个角色，
 * 直接写进令牌就是<b>凭空提权</b>。故实际签出的是
 * {@code {AGENT_AUTHOR} ∩ 库中登记角色}，交集为空即拒绝签发。
 *
 * <p><b>审计字段落位</b>（{@code platform_audit_event} 无租户列、无数值明细，
 * {@code target_id}/{@code correlation_id} 均为 uuid）：
 * {@code target_type=execution_ticket}、{@code target_id=目标租户}、
 * {@code correlation_id=被扮演的租户主体}、{@code reason_code=票证的 jti}。
 * 事后凭 jti 可把一张泄露的票对回到具体签发记录。
 */
@Component
public class PlatformExecutionTicketService {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformExecutionTicketService.class);

    /** 签发审计的动作名（与权限码同源，便于审计页按动作检索）。 */
    private static final String AUDIT_ACTION = Namespaces.Platform.TENANT_EXECUTION_TICKET;

    /** 审计的 target_type 取值。 */
    private static final String TARGET_TYPE = "execution_ticket";

    /** 票证声明的角色（最小可执行角色）；与库中登记角色取交集后才生效。 */
    static final String TICKET_ROLE = "AGENT_AUTHOR";

    /** 票证默认有效期：60 秒。 */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /** TTL 下界与登录令牌同源（{@code AuthConfiguration.MIN_TTL_SECONDS}）：低于此值即视为配置错误。 */
    private static final long MIN_TTL_SECONDS = 60L;

    private final PlatformTenantPort tenants;
    private final PlatformSecurity security;
    private final PlatformAuditPort audit;
    private final PrincipalStore principals;
    private final JwtTokenService tokens;
    private final RateLimitPort rateLimiter;
    private final boolean enabled;
    private final Duration ttl;

    public PlatformExecutionTicketService(
            PlatformTenantPort tenants,
            PlatformSecurity security,
            PlatformAuditPort audit,
            PrincipalStore principals,
            JwtTokenService tokens,
            RateLimitPort rateLimiter,
            @Value("${platform.security.execution-ticket.enabled}") boolean enabled,
            @Value("${platform.security.execution-ticket.ttl-seconds:60}") long ttlSeconds) {
        if (ttlSeconds < MIN_TTL_SECONDS) {
            // ⚠️ 缺省值刻意取「合法下界」而不是 fail-closed 拒绝启动：
            //    TTL 配短只会让票证提前过期（可用性问题），配长才是安全退化，
            //    而后者由本条下界拦住。拒绝启动会让「没配这一项」的老环境直接起不来。
            throw new IllegalArgumentException(
                    "platform.security.execution-ticket.ttl-seconds 不得小于 "
                            + MIN_TTL_SECONDS + "，当前 " + ttlSeconds);
        }
        this.tenants = tenants;
        this.security = security;
        this.audit = audit;
        this.principals = principals;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 一张已签发的执行票证。 */
    public record ExecutionTicket(@NonNull String accessToken,
                                 @NonNull String tenantId,
                                 @NonNull String principalId,
                                 @NonNull String loginName,
                                 @NonNull Set<String> roles,
                                 @NonNull Instant expiresAt,
                                 @NonNull String jti) {
    }

    /**
     * 票证闸门拒绝：客户端可见的<b>状态码 + 原因码 + 可读文案</b>三件套。
     *
     * <p>⚠️ <b>为什么不走 {@code ResponseStatusException}</b>：本项目的
     * {@code ApiExceptionHandler.handleResponseStatus} 按既有约定<b>刻意不回显 reason</b>
     * （防止新端点把内部结构写进消息）。对「资源不存在」这类无差别 404 是对的，
     * 但票证有<b>八种语义不同、行动建议也不同</b>的拒绝：
     * 「租户没登记」要去建租户，「状态不是 ACTIVE」要走生命周期迁移，
     * 「主体没这个角色」要去授权，「限流」要等一分钟 —— 全塌成
     * {@code NOT_FOUND / 资源不存在} 之后，真原因只留在审计表里，
     * 操作者得会查库才知道下一步（实测：前端只见 404「资源不存在」，
     * 而库里同一时刻写着 {@code TENANT_NOT_FOUND}）。
     *
     * <p>与 {@code ConfigOverrideController.ApprovalRequiredException} 同一先例：
     * 语义不同的拒绝必须有独立的异常类型与稳定的码，不走通用归一。
     *
     * <p>{@code reasonCode} 与该次 DENY 审计的 {@code reason_code} <b>同源同值</b>
     * （由 {@code denyAndReject} 单点产出）：响应里读到的码就是库里查到的码。
     * 消息只含<b>操作者本就知道的</b>入参（租户 ID / 登录名 / 当前状态），
     * 不给 SQL、不给令牌；出现表名只为了说清「去哪儿补登记」。
     */
    public static final class TicketRejectedException extends RuntimeException {

        private final transient @NonNull HttpStatus status;
        private final @NonNull String reasonCode;

        public TicketRejectedException(@NonNull HttpStatus status,
                                       @NonNull String reasonCode,
                                       @NonNull String message) {
            super(message);
            this.status = status;
            this.reasonCode = reasonCode;
        }

        public @NonNull HttpStatus status() {
            return status;
        }

        /** 与平台审计 {@code reason_code} 同值的稳定码（前端据此给行动建议）。 */
        public @NonNull String reasonCode() {
            return reasonCode;
        }
    }

    /**
     * 为指定租户的指定成员签发一枚短时执行票证。
     *
     * @param tenantId  目标租户（UUID）
     * @param loginName 该租户下的登录名（<b>由操作者显式指名</b>，不自动挑选 ——
     *                  归属必须是真的，不能是服务端猜的）
     * @throws TicketRejectedException 400 参数非法 / 503 通道关闭 / 429 限流（含 fail-closed）/
     *                                 404 租户或主体不存在 / 409 状态或角色不允许签发；
     *                                 除 400 外每种拒绝都已写一条 DENY 审计
     */
    public ExecutionTicket issue(@NonNull PlatformPrincipal principal,
                                 @NonNull String tenantId,
                                 @NonNull String loginName) {
        // 闸门 1：授权（默认拒绝；AccessDeniedException 由 ApiExceptionHandler 归一为 403）。
        // ⚠️ 排在最前：未授权者不应从「通道是否启用」「参数是否合法」上读出任何区别，
        //    也不该消耗限流令牌。
        security.require(principal, AUDIT_ACTION, "tenant", tenantId);

        // 闸门 2：配置开关。关闭时不签发票证，但把「有权限的人尝试了」写进审计——
        // 一个应当存在的通道反复被使用，本身是运维需要知道的事。
        if (!enabled) {
            throw denyAndReject(principal, tenantId, null, HttpStatus.SERVICE_UNAVAILABLE,
                    "CHANNEL_DISABLED",
                    "执行票证通道未启用（platform.security.execution-ticket.enabled=false）");
        }
        // 目标必须是 UUID：platform_audit_event.target_id 是 uuid 列，
        // 非 UUID 会让审计写入抛 SQLException → 500，把「参数错」伪装成「服务坏」。
        if (!isUuid(tenantId)) {
            throw new TicketRejectedException(HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID",
                    "tenantId 须为 UUID");
        }
        if (loginName.isBlank()) {
            throw new TicketRejectedException(HttpStatus.BAD_REQUEST, "MISSING_LOGIN_NAME",
                    "loginName 必填：票证必须指名一个真实的租户成员（审计与计费归属要指向真人）");
        }

        // 闸门 3：限流（按目标租户）。⚠️ FAIL_CLOSED 与 REJECTED 一律拒绝：
        // 放行等于「限流组件一坏，扮演通道就无限量」。
        RateLimitPort.Decision decision =
                rateLimiter.check(tenantId, RateLimitPort.Buckets.PLATFORM_EXECUTION_TICKET);
        if (decision != RateLimitPort.Decision.ALLOWED) {
            throw decision == RateLimitPort.Decision.REJECTED
                    ? denyAndReject(principal, tenantId, null, HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMITED", "票证签发过于频繁，请一分钟后再试")
                    : denyAndReject(principal, tenantId, null, HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMIT_UNAVAILABLE", "限流组件不可用，票证签发已按 fail-closed 拒绝");
        }

        // 闸门 4a：租户存在且生命周期允许承接新业务
        PlatformTenantRow tenant = tenants.findTenant(tenantId);
        if (tenant == null) {
            throw denyAndReject(principal, tenantId, null, HttpStatus.NOT_FOUND,
                    "TENANT_NOT_FOUND",
                    "租户未在 platform.tenant 登记，无法签发执行票证：" + tenantId);
        }
        if (!TenantLifecycle.isActive(tenant.lifecycleState())) {
            throw denyAndReject(principal, tenantId, null, HttpStatus.CONFLICT,
                    "TENANT_NOT_ACTIVE",
                    "租户 " + tenantId + " 当前状态不允许发起执行：" + tenant.lifecycleState()
                            + "（需 ACTIVE；worker 侧同口径，签发一枚跑不起来的票没有意义）");
        }

        // 闸门 4b：主体真实存在且 ACTIVE —— 审计与计费归属指向真人
        Optional<PrincipalStore.PrincipalRecord> found =
                principals.findByLogin(tenantId, loginName);
        if (found.isEmpty()) {
            throw denyAndReject(principal, tenantId, null, HttpStatus.NOT_FOUND,
                    "PRINCIPAL_NOT_FOUND", "该租户下不存在登录名 " + loginName);
        }
        PrincipalStore.PrincipalRecord record = found.get();
        if (!"ACTIVE".equals(record.status())) {
            throw denyAndReject(principal, tenantId, record.principalId(), HttpStatus.CONFLICT,
                    "PRINCIPAL_NOT_ACTIVE",
                    "主体 " + loginName + " 当前状态为 " + record.status() + "，不允许签发票证");
        }

        // 闸门 4c：角色取交集（见类注释）。authz_version 用库中当前值：
        // 陈旧版本会被执行时重授权拒绝（红线 9），这里没有理由自己造一个。
        Set<String> granted = principals.findRoles(tenantId, record.principalId());
        if (!granted.contains(TICKET_ROLE)) {
            throw denyAndReject(principal, tenantId, record.principalId(), HttpStatus.CONFLICT,
                    "TICKET_ROLE_NOT_HELD",
                    "主体 " + loginName + " 未持有 " + TICKET_ROLE + " 角色，"
                            + "无最小执行权限可签发（当前角色："
                            + (granted.isEmpty() ? "无" : String.join("/", granted)) + "）");
        }
        Set<String> roles = Set.of(TICKET_ROLE);

        TenantContext context = new TenantContext(
                tenantId, record.principalId(), roles, record.authzVersion());
        JwtTokenService.Issued issued =
                tokens.issueDetailed(context, Instant.now(), ttl);

        allow(principal, tenantId, record.principalId(), issued.jti());
        log.info("签发执行票证: platformUser={}, tenant={}, principal={}, jti={}",
                principal.userId(), tenantId, record.principalId(), issued.jti());

        return new ExecutionTicket(issued.token(), tenantId, record.principalId(),
                record.loginName(), roles, issued.expiresAt(), issued.jti());
    }

    // ── 审计 ───────────────────────────────────────────────

    /**
     * 写一条 DENY 审计，并返回（不抛出）对应的拒绝异常。
     *
     * <p>返回而非抛出是为了让调用点写成 {@code throw denyAndReject(...)}：
     * 控制流一眼可见，且<b>审计与响应同处一行</b> —— 拆成「先 audit 再 throw」
     * 早晚会出现某一处忘了留痕。
     */
    private TicketRejectedException denyAndReject(PlatformPrincipal principal, String tenantId,
                                                  @Nullable String principalId, HttpStatus status,
                                                  String reasonCode, String message) {
        deny(principal, tenantId, principalId, reasonCode);
        return new TicketRejectedException(status, reasonCode, message);
    }

    private void allow(PlatformPrincipal principal, String tenantId,
                       String principalId, String jti) {
        record(principal, tenantId, principalId, "ALLOW", jti);
    }

    private void deny(PlatformPrincipal principal, String tenantId,
                      String principalId, String reasonCode) {
        record(principal, tenantId, principalId, "DENY", reasonCode);
    }

    private void record(PlatformPrincipal principal, String tenantId,
                        String principalId, String decision, String reasonCode) {
        // ⚠️ 解析 principalId 为 UUID：若为 null 或非法格式，则传 null 给审计事件。
        //    避免重复调用 UUID.fromString（isUuid 已校验过格式）。
        UUID principalUuid = null;
        if (principalId != null && isUuid(principalId)) {
            try {
                principalUuid = UUID.fromString(principalId);
            } catch (IllegalArgumentException e) {
                // 日志记录，但不抛出异常：审计记录不应因 principalId 格式问题而失败
                log.debug("principalId 不是有效 UUID: {}", principalId, e);
            }
        }
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), principal.userId(), "PLATFORM_USER",
                AUDIT_ACTION, TARGET_TYPE, isUuid(tenantId) ? tenantId : null,
                decision, reasonCode,
                principalUuid,
                null, Instant.now(), null));
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

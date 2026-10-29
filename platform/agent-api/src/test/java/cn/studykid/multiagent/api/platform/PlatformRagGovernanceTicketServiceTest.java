package cn.studykid.multiagent.api.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.platform.PlatformRagGovernanceTicketService.GovernanceTicket;
import cn.studykid.multiagent.api.platform.PlatformRagGovernanceTicketService.GovernanceTicketRejectedException;
import cn.studykid.multiagent.api.platform.PlatformTenantServiceTest.FakeAuditPort;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * RAG 治理票据签发服务（RAG-D11 第四刀 · 第二刀 §2.3）。
 *
 * <p>用<b>真实密钥的签发器</b>而不是替身：票据的本体是一枚 RS256 JWT，
 * 「签出来的东西里到底有没有那一格 claim、角色是不是真的空」
 * 只能靠解码真令牌断言，替身签发会把这件事留到线上。
 *
 * <p>断言的重点有两组：
 * ① 四道闸门的<b>顺序与留痕</b>（授权先于一切、被拒也要写审计、
 * 响应码与审计 reason_code 同源）；
 * ② 票据的<b>用途限定</b>——它带着 rag_scope，但<b>不带任何平台角色</b>，
 * 因此在平台域内过不了授权矩阵（否则一次取票等于一整段登录态的权限）。
 */
class PlatformRagGovernanceTicketServiceTest {

    private static final String ISSUER = "https://auth.studykid.cn";
    private static final String PLATFORM_AUDIENCE = "admin-console-api";
    private static final long TTL = 300L;

    private static PlatformPrincipal superAdmin() {
        return new PlatformPrincipal(UUID.randomUUID().toString(),
                Set.of(PlatformRoles.SUPER_ADMIN), 1L, true);
    }

    /** 生命周期管理员：能建/停租户，<b>不</b>持有 platform.resource.manage。 */
    private static PlatformPrincipal lifecycleAdmin() {
        return new PlatformPrincipal(UUID.randomUUID().toString(),
                Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN), 1L, true);
    }

    // ── 签发与 claim 形态 ──────────────────────────────────

    @Test
    @DisplayName("持有 platform.resource.manage → 签出带 rag_scope 的票据并留一条 ALLOW 审计")
    void issuesTicketWithScopeClaimAndAuditsIt() {
        Harness h = new Harness(true, TTL);

        GovernanceTicket ticket = h.service.issue(superAdmin(), "PLATFORM");

        assertEquals("PLATFORM", ticket.scope());
        assertEquals(36, ticket.jti().length());
        long seconds = Duration.between(Instant.now(), ticket.expiresAt()).getSeconds();
        assertTrue(seconds > TTL / 2 && seconds <= TTL,
                "票据应按配置的 300 秒过期，实际剩余 " + seconds);

        PlatformAuditEvent event = h.audit.events.get(0);
        assertEquals("platform.rag.governance_ticket", event.action());
        assertEquals("rag_governance_ticket", event.targetType());
        assertEquals("ALLOW", event.decision());
        assertEquals(ticket.jti(), event.reasonCode(),
                "事后要能凭 jti 把一张泄露的票对回到签发记录");
        assertTrue(h.audit.events.stream().noneMatch(e -> ticket.accessToken().equals(e.reasonCode())),
                "⚠️ 审计正文里绝不允许出现凭据本身");
    }

    @Test
    @DisplayName("⚠️⚠️ 票据的角色刻意签成空集合：它在平台域内过不了授权矩阵")
    void ticketCarriesNoPlatformRoles() {
        Harness h = new Harness(true, TTL);
        GovernanceTicket ticket = h.service.issue(superAdmin(), "PLATFORM");

        // 用平台自己的校验器复核这枚令牌（RAG 侧不看角色，平台侧看）。
        PlatformPrincipal verified = h.tokens.verify(ticket.accessToken(), Instant.now(),
                PlatformRoles.ALL);
        assertTrue(verified.roles().isEmpty(),
                "票据若带上持有者角色，它就同时是有效期内的平台登录态：" + verified.roles());

        var decision = new PlatformAuthorizationServiceImpl().authorize(verified,
                Namespaces.Platform.RESOURCE_MANAGE, "rag_corpus", "PLATFORM");
        assertTrue(!decision.allowed(),
                "空角色票据在平台侧必须被默认拒绝，否则「取票」等于「提权」");

        String payload = decodePayload(ticket.accessToken());
        assertTrue(payload.contains("\"" + PlatformJwtTokenService.RAG_SCOPE_CLAIM
                + "\":\"PLATFORM\""), payload);
        assertTrue(payload.contains("\"roles\":[]"), payload);
        assertEquals("PLATFORM_USER", decodeClaim(payload, "principal_type"), payload);
        assertEquals(PLATFORM_AUDIENCE, decodeClaim(payload, "aud"), payload);
    }

    @Test
    @DisplayName("作用域大小写不敏感：前端传 platform 也要签成 PLATFORM")
    void scopeIsNormalizedToUpper() {
        Harness h = new Harness(true, TTL);

        assertEquals("PLATFORM", h.service.issue(superAdmin(), " platform ").scope());
    }

    // ── 闸门顺序与留痕 ─────────────────────────────────────

    @Test
    @DisplayName("⚠️ 授权排在最前：无权者不消耗限流令牌、不写审计、也读不到「通道是否启用」")
    void unauthorizedCallerIsDeniedBeforeEverythingElse() {
        Harness h = new Harness(false, TTL);

        assertThrows(AccessDeniedException.class,
                () -> h.service.issue(lifecycleAdmin(), "ALL"));
        assertEquals(0, h.rateLimit.calls, "越权尝试不该消耗限流令牌");
        assertTrue(h.audit.events.isEmpty(), "授权拒绝由 PlatformSecurity 统一留痕，此处不得重复写");
    }

    @Test
    @DisplayName("通道关闭 → 503 CHANNEL_DISABLED，且仍然留痕")
    void disabledChannelRejectsButStillAudits() {
        Harness h = new Harness(false, TTL);

        GovernanceTicketRejectedException ex = assertThrows(
                GovernanceTicketRejectedException.class,
                () -> h.service.issue(superAdmin(), "PLATFORM"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
        assertEquals("CHANNEL_DISABLED", ex.reasonCode());
        assertEquals("DENY", h.audit.events.get(0).decision());
        assertEquals("CHANNEL_DISABLED", h.audit.events.get(0).reasonCode());
    }

    @Test
    @DisplayName("⚠️ ALL 本轮不可签 → 409：它是第三刀的只读盘点档，签出去没人能正确解释")
    void allScopeIsRefusedWithItsOwnCode() {
        Harness h = new Harness(true, TTL);

        GovernanceTicketRejectedException ex = assertThrows(
                GovernanceTicketRejectedException.class,
                () -> h.service.issue(superAdmin(), "ALL"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("SCOPE_NOT_MINTABLE", ex.reasonCode());
        assertEquals("SCOPE_NOT_MINTABLE", h.audit.events.get(0).reasonCode(),
                "响应里读到的码必须就是库里查到的码");
        assertTrue(ex.getMessage().contains("PLATFORM"),
                "文案要给出可行动的下一步（改签 PLATFORM）：" + ex.getMessage());
    }

    @Test
    @DisplayName("档位缺失 / 未知 → 400，且不消耗限流令牌")
    void missingOrUnknownScopeIsBadRequest() {
        Harness h = new Harness(true, TTL);

        GovernanceTicketRejectedException missing = assertThrows(
                GovernanceTicketRejectedException.class,
                () -> h.service.issue(superAdmin(), null));
        assertEquals(HttpStatus.BAD_REQUEST, missing.status());
        assertEquals("MISSING_SCOPE", missing.reasonCode());

        GovernanceTicketRejectedException unknown = assertThrows(
                GovernanceTicketRejectedException.class,
                () -> h.service.issue(superAdmin(), "TENANT"));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.status());
        assertEquals("INVALID_SCOPE", unknown.reasonCode());

        assertEquals(0, h.rateLimit.calls, "参数不合法不该消耗令牌");
        assertTrue(h.audit.events.isEmpty(), "400 属集成缺陷，不是治理决策");
    }

    @Test
    @DisplayName("被限流与限流故障都拒绝（fail-closed），两个码可区分")
    void rateLimitRejectionAndFailureBothRefuse() {
        Harness h = new Harness(true, TTL);
        PlatformPrincipal caller = superAdmin();

        h.rateLimit.decision = RateLimitPort.Decision.REJECTED;
        assertEquals("RATE_LIMITED", assertThrows(GovernanceTicketRejectedException.class,
                () -> h.service.issue(caller, "PLATFORM")).reasonCode());

        h.rateLimit.decision = RateLimitPort.Decision.FAIL_CLOSED;
        assertEquals("RATE_LIMIT_UNAVAILABLE", assertThrows(GovernanceTicketRejectedException.class,
                () -> h.service.issue(caller, "PLATFORM")).reasonCode());

        // 计量键是平台主体而不是租户（见 Buckets 注释）：桶键负责命名空间隔离。
        h.rateLimit.decision = RateLimitPort.Decision.ALLOWED;
        h.service.issue(caller, "PLATFORM");
        assertEquals(
                List.of("PLATFORM_RAG_GOVERNANCE_TICKET@" + caller.userId(),
                        "PLATFORM_RAG_GOVERNANCE_TICKET@" + caller.userId(),
                        "PLATFORM_RAG_GOVERNANCE_TICKET@" + caller.userId()),
                h.rateLimit.lastKeys);
    }

    @Test
    @DisplayName("TTL 越界（下界 60／上界 900）拒绝启动，不静默截断")
    void ttlOutsideBoundsRefusesToStart() {
        IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> new Harness(true, 5L));
        assertTrue(tooShort.getMessage().contains("rag-governance-ticket"));

        assertThrows(IllegalArgumentException.class, () -> new Harness(true, 3600L));
    }

    @Test
    @DisplayName("⚠️ claim 值形态非法时签发器直接拒绝：手写 JSON 里一个引号就是声明注入")
    void scopeClaimShapeIsEnforcedAtSigning() {
        Harness h = new Harness(true, TTL);

        assertThrows(IllegalArgumentException.class, () -> h.tokens.issueRagGovernanceTicket(
                superAdmin(), Instant.now(), Duration.ofSeconds(TTL),
                "PLATFORM\",\"admin\":true"));
        assertThrows(IllegalArgumentException.class, () -> h.tokens.issueRagGovernanceTicket(
                superAdmin(), Instant.now(), Duration.ofSeconds(TTL), "platform"));
        assertThrows(IllegalArgumentException.class, () -> h.tokens.issueRagGovernanceTicket(
                superAdmin(), Instant.now(), Duration.ofSeconds(-1), "PLATFORM"));
    }

    // ── 夹具 ──────────────────────────────────────────────

    /** 真实签发器 + 内存审计 + 内存限流。 */
    private static final class Harness {
        final PlatformJwtTokenService tokens;
        final FakeAuditPort audit = new FakeAuditPort();
        final FakeRateLimiter rateLimit = new FakeRateLimiter();
        final PlatformRagGovernanceTicketService service;

        Harness(boolean enabled, long ttlSeconds) {
            KeyPair keyPair = PlatformJwtTokenService.generateKeyPair();
            this.tokens = new PlatformJwtTokenService(keyPair.getPrivate(),
                    keyPair.getPublic(), ISSUER, PLATFORM_AUDIENCE,
                    Duration.ofSeconds(900), "k-test");
            this.service = new PlatformRagGovernanceTicketService(
                    new PlatformSecurity(new PlatformAuthorizationServiceImpl()),
                    tokens, rateLimit, audit, enabled, ttlSeconds);
        }
    }

    private static String decodePayload(String jwt) {
        String segment = jwt.split("\\.")[1];
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    /** 从 payload 原文里取一个字符串声明的值（测试专用，不引 JSON 库以免与签发器同源）。 */
    private static String decodeClaim(String payload, String key) {
        int start = payload.indexOf("\"" + key + "\":\"");
        if (start < 0) {
            return "<missing>";
        }
        int from = start + key.length() + 4;
        int end = payload.indexOf('"', from);
        return payload.substring(from, end);
    }

    /** 假限流：三态可切换，并记录「桶键@计量键」用于断言按平台主体计量。 */
    private static final class FakeRateLimiter implements RateLimitPort {

        Decision decision = Decision.ALLOWED;
        int calls;
        final List<String> lastKeys = new ArrayList<>();

        @Override
        public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
            calls++;
            lastKeys.add(bucketKey + "@" + tenantId);
            return decision;
        }

        @Override
        public long currentCount(@NonNull String tenantId, @NonNull String bucketKey) {
            return calls;
        }
    }
}

package cn.studykid.multiagent.api.platform;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.platform.PlatformExecutionTicketService.TicketRejectedException;
import cn.studykid.multiagent.api.platform.PlatformTenantServiceTest.FakeAuditPort;
import cn.studykid.multiagent.api.platform.PlatformTenantServiceTest.FakeTenantPort;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.ports.security.PrincipalStore;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨域执行票证服务单元测试。
 *
 * <p>全部用内存假实现 + <b>真实密钥的签发器</b>：票证的本体是一张 JWT，
 * 用替身签发就等于把「签出来的东西能不能被租户域验通」这件事留到线上验证。
 *
 * <p>验证的是四道闸门的<b>顺序与留痕</b>，不是 JWT 语法（后者由
 * {@code JwtAndAuthenticationTest} 负责）：
 * 授权先于签发、被拒也要写审计、TTL 独立于登录令牌、
 * 角色必须与库中登记取交集（不得凭空提权）。
 */
class PlatformExecutionTicketServiceTest {

    private static final String ISSUER = "https://auth.studykid.cn";
    private static final String TENANT_AUDIENCE = "agent-platform";

    private static final String TENANT_ID = "3f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final String PRINCIPAL_ID = "9a8b7c6d-5e4f-3210-fedc-ba9876543210";

    private static PlatformPrincipal superAdmin() {
        return new PlatformPrincipal(UUID.randomUUID().toString(),
                Set.of(PlatformRoles.SUPER_ADMIN), 1L, true);
    }

    private static PlatformPrincipal lifecycleAdmin() {
        return new PlatformPrincipal(UUID.randomUUID().toString(),
                Set.of(PlatformRoles.TENANT_LIFECYCLE_ADMIN), 1L, true);
    }

    // ── 用例 ──────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 租户 + ACTIVE 主体 + 持有 AGENT_AUTHOR → 签出可被租户域验通的票证")
    void issuesVerifiableTicketAndAuditsIt() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR", "TEACHER"));

        PlatformExecutionTicketService.ExecutionTicket ticket =
                h.service.issue(superAdmin(), TENANT_ID, "lab-user");

        assertEquals(TENANT_ID, ticket.tenantId());
        assertEquals(PRINCIPAL_ID, ticket.principalId());
        assertEquals(Set.of("AGENT_AUTHOR"), ticket.roles());
        // ⚠️ 角色只签最小集：主体同时持有 TEACHER，票证不得把它一起带上
        assertTrue(ticket.jti().length() == 36);

        TenantContext verified = h.tokens.verify(
                ticket.accessToken(), Instant.now(), Set.of("AGENT_AUTHOR", "TEACHER"));
        assertEquals(TENANT_ID, verified.tenantId());
        assertEquals(PRINCIPAL_ID, verified.principalId());
        assertEquals(Set.of("AGENT_AUTHOR"), verified.roles());

        PlatformAuditEvent event = h.audit.events.stream().findFirst().orElseThrow();
        assertEquals("platform.tenant.execution_ticket", event.action());
        assertEquals("ALLOW", event.decision());
        assertEquals("execution_ticket", event.targetType());
        assertEquals(TENANT_ID, event.targetId());
        // 审计三要素：谁（actorId）/ 哪个租户（targetId）/ 哪张票（reasonCode=jti）
        assertEquals(ticket.jti(), event.reasonCode());
        assertEquals(UUID.fromString(PRINCIPAL_ID), event.correlationId());
    }

    @Test
    @DisplayName("⚠️ 票证 TTL 独立于登录令牌：签发器默认 15 分钟，票证只有 60 秒")
    void ticketTtlIsShorterThanLoginToken() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

        PlatformExecutionTicketService.ExecutionTicket ticket =
                h.service.issue(superAdmin(), TENANT_ID, "lab-user");

        long seconds = Duration.between(Instant.now(), ticket.expiresAt()).getSeconds();
        assertTrue(seconds > 30 && seconds <= 60,
                "票证应约 60 秒后过期（登录令牌是 900 秒），实际剩余 " + seconds);
    }

    @Test
    @DisplayName("⚠️ 只有超级管理员能签发：生命周期管理员能启停租户，不等于能扮演租户执行")
    void nonSuperAdminIsDeniedBeforeAnythingElse() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

        assertThrows(AccessDeniedException.class,
                () -> h.service.issue(lifecycleAdmin(), TENANT_ID, "lab-user"));
        assertTrue(h.audit.events.isEmpty(), "授权拒绝由 PlatformSecurity 统一留痕，此处不得重复写");
        assertTrue(h.rateLimit.calls == 0, "越权尝试不应消耗限流令牌");
    }

    @Test
    @DisplayName("⚠️ 租户没在 platform.tenant 登记 → 404 + TENANT_NOT_FOUND（2026-10-29 现场：app 侧有租户、平台侧没有）")
    void unregisteredTenantIsNotFoundWithItsOwnCode() {
        Harness h = new Harness(true, 60L);
        // 刻意不 seedTenant：这就是「public.tenant 有、platform.tenant 没有」的漂移形态
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("TENANT_NOT_FOUND", ex.reasonCode());
        assertEquals(1, h.rateLimit.calls, "登记校验排在限流之后，令牌该消耗");
    }

    @Test
    @DisplayName("⚠️⚠️ 每条拒绝的响应码与审计 reason_code 同源同值（否则前端读不到真原因）")
    void responseCodeAndAuditReasonCodeAreTheSameValue() {
        // 缺陷现场：审计里写着 TENANT_NOT_FOUND，HTTP 响应却只有归一化的「资源不存在」，
        // 操作者必须会查库才知道下一步。这条断言把「响应码 == 审计码」钉住。
        assertSameCodeAsAudit(HttpStatus.SERVICE_UNAVAILABLE, "CHANNEL_DISABLED", h -> {
            h.seedTenant(TenantLifecycle.ACTIVE);
            h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));
        }, false);
        assertSameCodeAsAudit(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", h -> {
            h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));
        }, true);
        assertSameCodeAsAudit(HttpStatus.CONFLICT, "TENANT_NOT_ACTIVE", h -> {
            h.seedTenant(TenantLifecycle.SUSPENDED);
            h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));
        }, true);
        assertSameCodeAsAudit(HttpStatus.NOT_FOUND, "PRINCIPAL_NOT_FOUND", h -> {
            h.seedTenant(TenantLifecycle.ACTIVE);
        }, true);
        assertSameCodeAsAudit(HttpStatus.CONFLICT, "PRINCIPAL_NOT_ACTIVE", h -> {
            h.seedTenant(TenantLifecycle.ACTIVE);
            h.seedPrincipal("lab-user", "LOCKED", Set.of("AGENT_AUTHOR"));
        }, true);
        assertSameCodeAsAudit(HttpStatus.CONFLICT, "TICKET_ROLE_NOT_HELD", h -> {
            h.seedTenant(TenantLifecycle.ACTIVE);
            h.seedPrincipal("lab-user", "ACTIVE", Set.of("STUDENT"));
        }, true);
    }

    /** 断言某个闸门：抛出的状态/原因码与它自己写进审计的 reason_code 一致。 */
    private static void assertSameCodeAsAudit(HttpStatus expectedStatus, String expectedCode,
                                              java.util.function.Consumer<Harness> seed,
                                              boolean enabled) {
        Harness h = new Harness(enabled, 60L);
        seed.accept(h);
        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"),
                "闸门 " + expectedCode + " 应当拒绝");
        assertEquals(expectedStatus, ex.status(), expectedCode + " 的状态码不该漂");
        assertEquals(expectedCode, ex.reasonCode());
        assertEquals(expectedCode, h.audit.events.get(0).reasonCode(),
                expectedCode + "：响应里读到的码必须是审计里查到的码");
        // 文案必须带得动下一步：不含令牌，且把操作者需要的标识说出来
        assertTrue(ex.getMessage().contains("lab-user")
                        || ex.getMessage().contains(TENANT_ID)
                        || ex.getMessage().contains(expectedCode)
                        || ex.getMessage().contains("execution-ticket"),
                expectedCode + " 的文案没给出任何可行动信息：" + ex.getMessage());
    }

    @Test
    @DisplayName("通道关闭（enabled=false）→ 503，且仍然留痕")
    void disabledChannelRejectsButStillAudits() {
        Harness h = new Harness(false, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());

        PlatformAuditEvent event = h.audit.events.stream().findFirst().orElseThrow();
        assertEquals("DENY", event.decision());
        assertEquals("CHANNEL_DISABLED", event.reasonCode());
    }

    @Test
    @DisplayName("被限流与限流故障都拒绝（fail-closed），且两个码可区分")
    void rateLimitRejectionAndFailureBothRefuse() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

        h.rateLimit.decision = RateLimitPort.Decision.REJECTED;
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(TicketRejectedException.class,
                        () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"))
                        .status());

        h.rateLimit.decision = RateLimitPort.Decision.FAIL_CLOSED;
        assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"));
        assertEquals(2, h.audit.events.stream()
                .filter(e -> "DENY".equals(e.decision())).count());
        // 两个码不同：「你调太快」与「限流组件坏了」是两份不同的运维动作
        assertEquals("RATE_LIMITED", h.audit.events.get(0).reasonCode());
        assertEquals("RATE_LIMIT_UNAVAILABLE", h.audit.events.get(1).reasonCode());
    }

    @Test
    @DisplayName("租户非 ACTIVE（暂停/退役中/待初始化）一律不签")
    void nonActiveTenantIsRefused() {
        for (String state : Set.of(TenantLifecycle.SUSPENDED,
                TenantLifecycle.DECOMMISSIONING, TenantLifecycle.PENDING_SETUP)) {
            Harness h = new Harness(true, 60L);
            h.seedTenant(state);
            h.seedPrincipal("lab-user", "ACTIVE", Set.of("AGENT_AUTHOR"));

            TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                    () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"),
                    "状态 " + state + " 不应允许签发");
            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("TENANT_NOT_ACTIVE", ex.reasonCode());
            assertEquals("TENANT_NOT_ACTIVE", h.audit.events.get(0).reasonCode());
            // 当前状态要出现在文案里：否则「为什么被拒」还得再查一次租户
            assertTrue(ex.getMessage().contains(state),
                    "文案应说明被拒的当前状态，实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("⚠️ 主体必须真实存在且 ACTIVE：归属与计费的指向不能是虚构身份")
    void principalMustExistAndBeActive() {
        Harness missing = new Harness(true, 60L);
        missing.seedTenant(TenantLifecycle.ACTIVE);
        TicketRejectedException notFound = assertThrows(TicketRejectedException.class,
                () -> missing.service.issue(superAdmin(), TENANT_ID, "ghost"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());
        assertEquals("PRINCIPAL_NOT_FOUND", notFound.reasonCode());
        assertEquals("PRINCIPAL_NOT_FOUND", missing.audit.events.get(0).reasonCode());

        Harness locked = new Harness(true, 60L);
        locked.seedTenant(TenantLifecycle.ACTIVE);
        locked.seedPrincipal("lab-user", "LOCKED", Set.of("AGENT_AUTHOR"));
        TicketRejectedException inactive = assertThrows(TicketRejectedException.class,
                () -> locked.service.issue(superAdmin(), TENANT_ID, "lab-user"));
        assertEquals(HttpStatus.CONFLICT, inactive.status());
        assertEquals("PRINCIPAL_NOT_ACTIVE", inactive.reasonCode());
        assertEquals("PRINCIPAL_NOT_ACTIVE", locked.audit.events.get(0).reasonCode());
    }

    @Test
    @DisplayName("⚠️⚠️ 主体未持有 AGENT_AUTHOR 时拒绝签发，而不是把角色凭空塞进令牌")
    void roleIsIntersectedWithDatabaseNotAssumed() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        h.seedPrincipal("lab-user", "ACTIVE", Set.of("STUDENT"));

        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "lab-user"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("TICKET_ROLE_NOT_HELD", ex.reasonCode());
        assertEquals("TICKET_ROLE_NOT_HELD", h.audit.events.get(0).reasonCode());
        assertTrue(h.rateLimit.ticketTokens() > 0);
    }

    @Test
    @DisplayName("非 UUID 的 tenantId 先拒：否则审计写入会把 400 伪装成 500")
    void nonUuidTenantIdIsBadRequest() {
        Harness h = new Harness(true, 60L);
        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), "not-a-uuid", "lab-user"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_TENANT_ID", ex.reasonCode());
        assertTrue(h.audit.events.isEmpty(), "非 UUID 连审计都写不了，不该留半条记录");
    }

    @Test
    @DisplayName("缺 loginName → 400 MISSING_LOGIN_NAME：票证不许匿名")
    void blankLoginNameIsRejectedBeforeRateLimit() {
        Harness h = new Harness(true, 60L);
        h.seedTenant(TenantLifecycle.ACTIVE);
        TicketRejectedException ex = assertThrows(TicketRejectedException.class,
                () -> h.service.issue(superAdmin(), TENANT_ID, "  "));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("MISSING_LOGIN_NAME", ex.reasonCode());
        assertEquals(0, h.rateLimit.calls, "参数不合法不该消耗该租户的限流令牌");
    }

    @Test
    @DisplayName("TTL 小于 60 秒属配置错误：启动即失败")
    void tooShortTtlRefusesToStart() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Harness(true, 5L));
        assertTrue(ex.getMessage().contains("ttl-seconds"));
    }

    // ── 夹具 ──────────────────────────────────────────────

    /** 一次用例的装配：真实签发器 + 内存仓储/限流/审计。 */
    private static final class Harness {
        final JwtTokenService tokens;
        final FakeTenantPort tenants = new FakeTenantPort();
        final FakeAuditPort audit = new FakeAuditPort();
        final FakeRateLimiter rateLimit = new FakeRateLimiter();
        final FakePrincipalRepository principals;
        final PlatformExecutionTicketService service;

        Harness(boolean enabled, long ttlSeconds) {
            KeyPair keyPair = JwtTokenService.generateKeyPair();
            // 登录令牌的默认 TTL 故意保持 900 秒：票证必须明显更短才说明得了事
            this.tokens = new JwtTokenService(keyPair.getPrivate(), keyPair.getPublic(),
                    ISSUER, TENANT_AUDIENCE, Duration.ofSeconds(900), "k-test");
            this.principals = new FakePrincipalRepository();
            this.service = new PlatformExecutionTicketService(tenants,
                    new PlatformSecurity(new PlatformAuthorizationServiceImpl()),
                    audit, principals, tokens, rateLimit, enabled, ttlSeconds);
        }

        void seedTenant(String state) {
            tenants.map.put(TENANT_ID, new PlatformTenantRow(TENANT_ID, "acme", "Acme",
                    state, "PRO", 1L, 1L, Instant.now(), Instant.now()));
        }

        void seedPrincipal(String loginName, String status, Set<String> roles) {
            principals.put(new PrincipalStore.PrincipalRecord(
                            PRINCIPAL_ID, TENANT_ID, loginName, "$irrelevant$",
                            "实验室账号", status, 0, null, 7L),
                    roles);
        }
    }

    /**
     * 假账号仓储：只实现票证用到的两次查询（按登录名取账号、取角色）。
     * ⚠️ 2026-11-06 起实现端口契约而非 JDBC 仓储 —— 接口层测试不应把数据库实现
     *    拉进 classpath；写路径是空操作，票证用例不覆盖它们。
     */
    private static final class FakePrincipalRepository implements PrincipalStore {
        private final java.util.Map<String, PrincipalRecord> byLogin = new java.util.HashMap<>();
        private final java.util.Map<String, Set<String>> rolesByKey = new java.util.HashMap<>();

        void put(PrincipalRecord record, Set<String> roles) {
            byLogin.put(record.tenantId() + "|" + record.loginName(), record);
            rolesByKey.put(record.tenantId() + "|" + record.principalId(), roles);
        }

        @Override
        public Optional<PrincipalRecord> findByLogin(String tenantId, String loginName) {
            return Optional.ofNullable(byLogin.get(tenantId + "|" + loginName));
        }

        @Override
        public Set<String> findRoles(String tenantId, String principalId) {
            return rolesByKey.getOrDefault(tenantId + "|" + principalId, Set.of());
        }

        @Override
        public boolean verifyPassword(PrincipalRecord record, String rawPassword,
                                      java.time.Instant now) {
            return false;
        }

        @Override
        public void recordFailure(PrincipalRecord record, java.time.Instant now,
                                  int maxAttempts, Duration lockDuration) {
        }

        @Override
        public void recordSuccess(PrincipalRecord record, java.time.Instant now) {
        }

        @Override
        public void revokeAuthorization(PrincipalRecord record, java.time.Instant now) {
        }
    }

    /** 假限流器：三态可切换，用于分别验证 REJECTED 与 FAIL_CLOSED 都拒绝。 */
    private static final class FakeRateLimiter implements RateLimitPort {
        Decision decision = Decision.ALLOWED;
        int calls;

        @Override
        public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
            calls++;
            return decision;
        }

        @Override
        public long currentCount(@NonNull String tenantId, @NonNull String bucketKey) {
            return calls;
        }

        long ticketTokens() {
            return calls;
        }
    }
}

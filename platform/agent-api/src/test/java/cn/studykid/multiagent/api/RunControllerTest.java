package cn.studykid.multiagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.api.run.CreateRunRequest;
import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.core.compliance.DataClassification;
import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;
import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * D1 测试：Run 创建端点。
 *
 * <p><b>核心命题：授权闸门无法被绕过，且不可跳过。</b>
 * 因此除正向用例外，必须包含：
 * <ul>
 *   <li><b>证伪</b>：主动尝试越权，确认被拒；</li>
 *   <li><b>顺序断言</b>：授权未通过时，用例<b>绝不能</b>被调用
 *       （若授权放在创建之后，就出现了「先创建后拒绝」的窗口）。</li>
 * </ul>
 */
class RunControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";

    /** 测试用快照 ID（P4-D18：必须是确定的 UUID，不能是随机值）。 */
    private static final String SNAPSHOT_ID = "9c1d0f2a-4e6b-4a7c-8b3d-1f2e3a4b5c6d";

    /** 记录用例是否被调用——用于断言授权失败时未发生写入。 */
    private final AtomicBoolean useCaseInvoked = new AtomicBoolean(false);
    private final AtomicInteger callCount = new AtomicInteger();

    private AuthorizationService authorization;
    private ZeroRetentionGate retentionGate;
    private RunController.CurrentPrincipal principal;

    /** 当前请求的可信上下文 —— 由 CurrentPrincipal 提供，控制器不再直接接收。 */
    private TenantContext currentContext;

    @BeforeEach
    void setUp() {
        useCaseInvoked.set(false);
        callCount.set(0);
        currentContext = context("user-1", Set.of());
        principal = new RunController.CurrentPrincipal() {
            @Override
            public TenantContext context() { return currentContext; }
            @Override public String principalId() { return "user-1"; }
            @Override public String providerId() { return ModelProviders.DEEPSEEK; }
            @Override public String correlationId() { return "corr-1"; }
        };
        retentionGate = new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                .confirmed(ModelProviders.DEEPSEEK, "SUP-1", NOW, Duration.ofDays(365))
                .pending(ModelProviders.MINIMAX)
                .build(), CLOCK);
    }

    private TenantContext context(String principalId, Set<String> roles) {
        return new TenantContext(TENANT, principalId, roles, 0L);
    }

    private CreateRunRequest request() {
        return new CreateRunRequest(
                "idem-" + UUID.randomUUID(), "agent-1",
                Map.of("question", "一元二次方程", "process", "我用了求根公式"));
    }

    private CreateRunRequest requestWithInput(Map<String, Object> input) {
        return new CreateRunRequest("idem-" + UUID.randomUUID(), "agent-1", input);
    }

    /** 授权实现：可配置允许/拒绝，并记录是否被调用。 */
    private AuthorizationService allowAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                callCount.incrementAndGet();
                return true;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    private AuthorizationService denyAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                callCount.incrementAndGet();
                return false;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    @Nested
    @DisplayName("请求校验：tenantId 绝不可来自客户端")
    class RequestValidation {

        @Test
        @DisplayName("✅ 请求对象不包含 tenantId 字段")
        void requestHasNoTenantField() {
            var fields = java.util.Arrays.stream(CreateRunRequest.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(fields)
                    .as("⚠️ 请求对象一旦有 tenantId，攻击者即可尝试指定他人租户")
                    .doesNotContain("tenantId");
        }

        @Test
        @DisplayName("✅ 空 idempotencyKey 被拒")
        void blankIdempotencyKeyRejected() {
            assertThatThrownBy(() -> new CreateRunRequest(
                    " ", "agent-1", Map.of("q", "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ 空 input 被拒")
        void emptyInputRejected() {
            assertThatThrownBy(() -> new CreateRunRequest(
                    "idem-1", "agent-1", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ 超长 idempotencyKey 被拒")
        void overlongKeyRejected() {
            assertThatThrownBy(() -> new CreateRunRequest(
                    "k".repeat(129), "agent-1", Map.of("q", "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⚠️ 请求含身份证号 → 内容合规检查拦截")
        void identifyingContentRejected() {
            var req = requestWithInput(Map.of(
                    "question", "解方程", "note", "学生 110101200501011234 的错题"));

            assertThatThrownBy(req::assertNoIdentifyingContent)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("身份证");
        }

        @Test
        @DisplayName("⚠️ 嵌套 Map 中的手机号也要拦截")
        void nestedIdentifyingContentRejected() {
            var req = requestWithInput(Map.of(
                    "question", Map.of("contact", "13812345678")));

            assertThatThrownBy(req::assertNoIdentifyingContent)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("手机号");
        }

        @Test
        @DisplayName("✅ 正常数学题目不被误伤")
        void cleanMathContentAllowed() {
            request().assertNoIdentifyingContent();
        }
    }

    @Nested
    @DisplayName("⚠️ P3-D08 限流（D-2 预算击穿）")
    class RateLimitGate {

        /** 可脚本化的限流替身：让「是否真的调用了限流」可证伪。 */
        private static final class ScriptedRateLimiter
                implements cn.studykid.multiagent.ports.persistence.RateLimitPort {
            private final cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision forced;
            int checkCalls;
            String lastBucket;

            ScriptedRateLimiter(
                    cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision forced) {
                this.forced = forced;
            }

            @Override
            public Decision check(String tenantId, String bucketKey) {
                checkCalls++;
                lastBucket = bucketKey;
                return forced;
            }

            @Override
            public long currentCount(String tenantId, String bucketKey) {
                return 0L;
            }
        }

        @Test
        @DisplayName("⚠️⚠️ 超限(REJECTED) → 429 + Retry-After，且用例绝不被调用")
        void overLimitReturns429() {
            var limiter = new ScriptedRateLimiter(
                    cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision.REJECTED);
            var controller = new RunController(null, allowAll(), retentionGate, principal,
                    limiter);

            var response = controller.createRun(request());

            assertThat(response.getStatusCode().value())
                    .as("⚠️⚠️ 限流必须返回 429（否则客户端无法区分速率问题与参数错误）")
                    .isEqualTo(429);
            assertThat(response.getHeaders().getFirst("Retry-After"))
                    .as("⚠️ 必须带 Retry-After")
                    .isNotNull();
            assertThat(useCaseInvoked)
                    .as("⚠️⚠️ 被限流时绝不能创建 Run（否则限流形同虚设）")
                    .isFalse();
            assertThat(limiter.lastBucket)
                    .as("⚠️ 必须用 RUN_CREATE 桶")
                    .isEqualTo(
                            cn.studykid.multiagent.ports.persistence.RateLimitPort.Buckets.RUN_CREATE);
        }

        @Test
        @DisplayName("⚠️⚠️ 限流组件故障(FAIL_CLOSED) → 同样 429（禁止降级放行）")
        void limiterFailureAlsoReturns429() {
            var limiter = new ScriptedRateLimiter(
                    cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision.FAIL_CLOSED);
            var controller = new RunController(null, allowAll(), retentionGate, principal,
                    limiter);

            var response = controller.createRun(request());

            assertThat(response.getStatusCode().value())
                    .as("⚠️⚠️ 限流组件故障必须 fail-closed（放行 = 限流形同虚设）")
                    .isEqualTo(429);
            assertThat(useCaseInvoked).isFalse();
        }

        @Test
        @DisplayName("✅ 未超限(ALLOWED) → 正常创建（限流不被误伤）")
        void underLimitProceeds() {
            var limiter = new ScriptedRateLimiter(
                    cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision.ALLOWED);
            var controller = new RunController(new CountingRunCreationService(useCaseInvoked),
                    allowAll(), retentionGate, principal, limiter);

            var response = controller.createRun(request());

            assertThat(response.getStatusCode().value())
                    .as("⚠️ 未超限时不得被误拦（否则功能不可用）")
                    .isEqualTo(201);
            assertThat(limiter.checkCalls).isEqualTo(1);
            assertThat(useCaseInvoked).isTrue();
        }

        @Test
        @DisplayName("⚠️ 授权失败时不应消耗限流配额（限流在授权之后）")
        void authorizationCheckedBeforeRateLimit() {
            var limiter = new ScriptedRateLimiter(
                    cn.studykid.multiagent.ports.persistence.RateLimitPort.Decision.ALLOWED);
            var controller = new RunController(null, denyAll(), retentionGate, principal,
                    limiter);

            catchThrowableOfType(() -> controller.createRun(request()),
                    AccessDeniedException.class);

            assertThat(limiter.checkCalls)
                    .as("⚠️ 限流在授权之后——未授权请求不应占用租户的限流配额")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("⚠️ 授权闸门（红线 9）")
    class AuthorizationGate {

        @Test
        @DisplayName("⚠️ 授权拒绝 → 抛 AccessDeniedException")
        void deniedThrowsAccessDenied() {
            var controller = new RunController(null, denyAll(), retentionGate, principal);

            assertThat(catchThrowableOfType(
                    () -> controller.createRun(
                            request()),
                    AccessDeniedException.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️⚠️ 授权失败时用例绝不被调用")
        void useCaseNotInvokedWhenDenied() {
            var controller = new RunController(null, denyAll(), retentionGate, principal);

            catchThrowableOfType(
                    () -> controller.createRun(request()),
                    AccessDeniedException.class);

            assertThat(useCaseInvoked)
                    .as("⚠️ 若授权放在创建之后，会出现「先创建后拒绝」窗口——"
                            + "Run 已落库但调用方收到失败，攻击者可用它消耗资源")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 授权通过时创建成功")
        void authorizedSucceeds() {
            var controller = new RunController(
                    new CountingRunCreationService(useCaseInvoked),
                    allowAll(), retentionGate, principal);

            var resp = controller.createRun(
                    request()).getBody();

            assertThat(resp).isNotNull();
            assertThat(resp.runId()).isNotBlank();
            assertThat(resp.state()).isEqualTo("QUEUED");
            assertThat(useCaseInvoked).isTrue();
        }

        @Test
        @DisplayName("✅ 授权动作固定为 run:create（不随请求变化）")
        void authorizationActionIsFixed() {
            var recording = new AuthorizationService() {
                private String capturedAction;
                @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                    capturedAction = a;
                    return true;
                }
                String capturedAction() { return capturedAction; }
                @Override public long currentAuthzVersion(String t, String p) { return 0L; }
                @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
            };
            var controller = new RunController(
                    new CountingRunCreationService(useCaseInvoked),
                    recording, retentionGate, principal);

            controller.createRun(request());

            assertThat(recording.capturedAction())
                    .isEqualTo(AuthorizationService.Actions.RUN_CREATE);
        }
    }

    @Nested
    @DisplayName("零保留闸门联动")
    class RetentionGate {

        @Test
        @DisplayName("⚠️ 供应商未确认 → 拦截")
        void unconfirmedProviderBlocked() {
            var controller = new RunController(null, allowAll(), retentionGate, principal);

            var gate = new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                    .pending(ModelProviders.DEEPSEEK)
                    .build(), CLOCK);

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.DEEPSEEK, "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull();
            assertThat(controller).isNotNull();
        }

        @Test
        @DisplayName("✅ 已确认供应商 → 放行")
        void confirmedProviderAllowed() {
            retentionGate.assertAllowed(ModelProviders.DEEPSEEK, "题目",
                    DataClassification.REAL_STUDENT);
        }

        @Test
        @DisplayName("⚠️ 已确认但供应商不同 → 拦截")
        void differentProviderBlocked() {
            assertThat(catchThrowableOfType(
                    () -> retentionGate.assertAllowed(ModelProviders.MINIMAX, "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("幂等语义")
    class Idempotency {

        @Test
        @DisplayName("✅ 重放返回 replayed=true")
        void replayFlagPropagated() {
            var service = new CountingRunCreationService(useCaseInvoked);
            var controller = new RunController(service, allowAll(), retentionGate, principal);

            var resp = controller.createRun(request());
            assertThat(resp.getBody().replayed()).isFalse();

            var replayService = new ReplayRunCreationService();
            var replayController =
                    new RunController(replayService, allowAll(), retentionGate, principal);
            assertThat(replayController.createRun(
                    request()).getBody().replayed()).isTrue();
        }
    }

    @Nested
    @DisplayName("响应构造校验")
    class ResponseValidation {

        @Test
        @DisplayName("✅ 空 runId 被拒")
        void blankRunIdRejected() {
            assertThatThrownBy(() -> new cn.studykid.multiagent.api.run.CreateRunResponse(
                    "", false, "QUEUED", "corr-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ 空 state 被拒")
        void blankStateRejected() {
            assertThatThrownBy(() -> new cn.studykid.multiagent.api.run.CreateRunResponse(
                    "run-1", false, " ", "corr-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── 测试替身 ────────────────────────────────────────────────────

    /** 用例替身：记录是否被调用。 */
    private static class CountingRunCreationService
            extends cn.studykid.multiagent.application.run.RunCreationService {
        private final AtomicBoolean invoked;

        CountingRunCreationService(AtomicBoolean invoked) {
            super(null, new FixedSnapshotResolver(SNAPSHOT_ID), new NoopTransactionRunner(), CLOCK);
            this.invoked = invoked;
        }

        @Override
        public CreateRunResult createRun(TenantContext context, String agentId,
                                         cn.studykid.multiagent.core.idempotency.IdempotencyKey key,
                                         Map<String, Object> input) {
            invoked.set(true);
            return new CreateRunResult(UUID.randomUUID().toString(), false);
        }
    }

    /** 用例替身：模拟幂等重放。 */
    private static class ReplayRunCreationService
            extends cn.studykid.multiagent.application.run.RunCreationService {
        ReplayRunCreationService() {
            super(null, new FixedSnapshotResolver(SNAPSHOT_ID), new NoopTransactionRunner(), CLOCK);
        }

        @Override
        public CreateRunResult createRun(TenantContext context, String agentId,
                                         cn.studykid.multiagent.core.idempotency.IdempotencyKey key,
                                         Map<String, Object> input) {
            return new CreateRunResult(UUID.randomUUID().toString(), true);
        }
    }

    /**
     * 快照解析替身（P4-D18）。
     *
     * <p>⚠️ <b>不得返回随机 UUID</b>：那正是被修复的生产缺陷
     * （{@code run_snapshot_fk} 必然违反）。替身必须给一个<b>确定的</b>值，
     * 否则本类会重蹈「替身掩盖了生产缺陷」的覆辙。
     */
    private static class FixedSnapshotResolver
            implements cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort {
        private final String snapshotId;

        FixedSnapshotResolver(String snapshotId) {
            this.snapshotId = snapshotId;
        }

        @Override
        public java.util.Optional<String> resolveSnapshotId(String tenantId, String agentId) {
            return java.util.Optional.ofNullable(snapshotId);
        }
    }

    /**
     * 空事务执行器。
     *
     * <p>⚠️ 必须用匿名类而非 lambda：{@code execute} 是<b>泛型方法</b>，
     * 而 lambda 的函数式接口不能有泛型方法——编译期直接报错。
     */
    private static class NoopTransactionRunner
            implements cn.studykid.multiagent.application.run.RunCreationService.TransactionRunner {
        @Override
        public <T> T execute(String tenantId, TransactionalAction<T> action) {
            return action.run();
        }
    }
}
package cn.studykid.multiagent.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishBaselineConflictException;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishScopeNotAllowedException;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest.State;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 发布工作流端点测试（P2 · 发布中心读端点 + 既有闸门回归）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>{@code GET /publishes/{requestId}}：命中 200、缺失 404、未授权抛错、限流 429；</li>
 *   <li>{@code GET /releases}：发布时间线 200 + 游标透传 + 字段映射；</li>
 *   <li>既有闸门不回归：同人审批 409、平台级作用域 403、CAS 基线冲突 409。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：工作流服务以 Mockito 桩替代，聚焦于<b>端点契约与闸门口径</b>。
 * 三步工作流的真实状态机由 {@code ConfigPublishManagementIT} 覆盖。
 */
@DisplayName("P2 CapConfigPublishController · 发布工作流端点")
class CapConfigPublishControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PRINCIPAL = "principal-1";
    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";

    private final ConfigPublishWorkflowService workflow = mock(ConfigPublishWorkflowService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private CapConfigPublishController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext ctx = new TenantContext(TENANT, PRINCIPAL, Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
        when(currentPrincipal.principalId()).thenReturn(PRINCIPAL);
        return new CapConfigPublishController(workflow, authorization, rateLimiter,
                currentPrincipal);
    }

    private static ConfigPublishRequest sampleRequest() {
        return new ConfigPublishRequest(
                REQUEST_ID, TENANT, ConfigPublishRequest.Operation.PUBLISH,
                "{\"operation\":\"PUBLISH\"}", "扩容教师角色", PRINCIPAL,
                State.PENDING, null, null,
                Instant.parse("2026-10-24T10:00:00Z"), null, null, null);
    }

    private static ConfigRelease sampleRelease() {
        return new ConfigRelease(
                7L, 12L, ConfigRelease.Scope.TENANT, TENANT,
                "{\"agent\":{\"a-1\":3}}", "agg-fp",
                ConfigRelease.Status.PUBLISHED, PRINCIPAL, "principal-2",
                Instant.parse("2026-10-24T10:05:00Z"),
                Instant.parse("2026-10-24T10:05:00Z"));
    }

    // ── GET /publishes/{requestId} ─────────────────────────────────

    @Test
    @DisplayName("申请详情命中 → 200 + 字段映射")
    void getRequest_found() {
        CapConfigPublishController c = controller();
        when(workflow.findRequest(TENANT, REQUEST_ID)).thenReturn(Optional.of(sampleRequest()));

        var resp = c.getRequest(REQUEST_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().requestId()).isEqualTo(REQUEST_ID);
        assertThat(resp.getBody().operation()).isEqualTo("PUBLISH");
        assertThat(resp.getBody().state()).isEqualTo("PENDING");
        assertThat(resp.getBody().requesterId()).isEqualTo(PRINCIPAL);
        assertThat(resp.getBody().failureReason()).isNull();
    }

    @Test
    @DisplayName("申请详情缺失 → 404（不泄露存在性）")
    void getRequest_absent() {
        CapConfigPublishController c = controller();
        when(workflow.findRequest(TENANT, REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> c.getRequest(REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("申请详情未授权 → 直接抛出（不触达工作流）")
    void getRequest_unauthorized() {
        CapConfigPublishController c = controller();
        // ⚠️ requireAuthorized 返回 void：必须用 doThrow 形态，when(void) 编译不过
        doThrow(new RuntimeException("denied"))
                .when(authorization).requireAuthorized(any(),
                        eq(AuthorizationService.Actions.CONFIG_PUBLISH), any());

        assertThatThrownBy(() -> c.getRequest(REQUEST_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("denied");
    }

    @Test
    @DisplayName("申请详情限流 → 429")
    void getRequest_rateLimited() {
        CapConfigPublishController c = controller();
        // ⚠️ 必须在 controller() 之后再覆盖桩：helper 里默认 ALLOWED，先打会被盖掉
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.REJECTED);

        assertThat(c.getRequest(REQUEST_ID).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── GET /releases ──────────────────────────────────────────────

    @Test
    @DisplayName("发布时间线 → 200 + 映射 + 游标透传")
    void listReleases_ok() {
        CapConfigPublishController c = controller();
        when(workflow.listReleases(eq(TENANT), eq("cursor-1"), eq(20)))
                .thenReturn(Page.of(List.of(sampleRelease()), "cursor-2"));

        var resp = c.releases(20, "cursor-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().items()).hasSize(1);
        assertThat(resp.getBody().items().get(0).releaseId()).isEqualTo("7");
        assertThat(resp.getBody().items().get(0).configPublishSeq()).isEqualTo("12");
        assertThat(resp.getBody().items().get(0).status()).isEqualTo("PUBLISHED");
        assertThat(resp.getBody().items().get(0).requesterId()).isEqualTo(PRINCIPAL);
        assertThat(resp.getBody().nextCursor()).isEqualTo("cursor-2");
    }

    @Test
    @DisplayName("发布时间线限流 → 429")
    void listReleases_rateLimited() {
        CapConfigPublishController c = controller();
        // ⚠️ 同 getRequest_rateLimited：先建 controller 再覆盖限流桩
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.FAIL_CLOSED);

        assertThat(c.releases(null, null).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── 既有闸门回归（P0.5 红线不得因新端点松开） ───────────────────

    @Test
    @DisplayName("同人审批 → 409")
    void decide_selfApproval() {
        CapConfigPublishController c = controller();
        when(workflow.decide(eq(TENANT), eq(REQUEST_ID),
                eq(ConfigPublishRequestPort.Decision.APPROVE), any(), eq(PRINCIPAL)))
                .thenThrow(new ConfigPublishRequestPort.SelfApprovalException());

        assertThatThrownBy(() -> c.decide(REQUEST_ID,
                new CapConfigPublishController.DecisionRequest("APPROVE", "ok")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("平台级作用域申请 → 403")
    void request_platformScopeRejected() {
        CapConfigPublishController c = controller();
        // ⚠️ 必须 mock **带契约与策略列表**的那个重载：控制器走的是 8 参版本，
        //    只 mock 旧的重载会命中不到 → mock 返回 null → 抛 NPE 而非预期的 403。
        when(workflow.requestPublish(eq(TENANT), eq(ConfigRelease.Scope.PLATFORM), any(), any(),
                any(), any(), eq("reason"), eq(PRINCIPAL)))
                .thenThrow(new ConfigPublishScopeNotAllowedException("platform scope"));

        assertThatThrownBy(() -> c.request(new CapConfigPublishController.PublishRequest(
                "PUBLISH", "PLATFORM", null,
                List.of(new CapConfigPublishController.AgentRequest(
                        "a-1", "教师 Agent", "desc", null, null, null, null, null)),
                null, null, "reason")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("✅ 编排声明、输出契约与路由策略随申请提交（多级 Agent 可经治理通道发布）")
    void request_carriesOrchestrationAndResultContracts() {
        CapConfigPublishController c = controller();
        when(workflow.requestPublish(eq(TENANT), eq(ConfigRelease.Scope.TENANT), any(), any(),
                any(), any(), eq("reason"), eq(PRINCIPAL)))
                .thenReturn(sampleRequest());

        c.request(new CapConfigPublishController.PublishRequest(
                "PUBLISH", "TENANT", null,
                List.of(new CapConfigPublishController.AgentRequest(
                        "a-1", "教师 Agent", "desc", null, null, null, null, null,
                        "{\"mode\":\"SEQUENTIAL\",\"childAgentRefs\":[{\"id\":\"c-1\",\"version\":1}]}")),
                null, null,
                List.of(new CapConfigPublishController.ResultContractRequest(
                        "answer-contract", "{\"type\":\"object\"}")),
                List.of(new CapConfigPublishController.RoutingPolicyRequest(
                        "orders-router", "EXPLICIT_TARGETS", List.of("c-1"))),
                "reason"));

        // ⚠️ 治理通道若不暴露编排声明，多级 Agent 就**根本无法发布** ——
        //    契约引用同理（引用了却登记不了 = 引用了一个不生效的事实）。
        //    路由策略同理（第十四批）：ROUTER Agent 引用了却登记不了 =
        //    引用了一个从未生效的路由规则。
        var agentCaptor = org.mockito.ArgumentCaptor
                .forClass(java.util.List.class);
        var contractCaptor = org.mockito.ArgumentCaptor
                .forClass(java.util.List.class);
        var policyCaptor = org.mockito.ArgumentCaptor
                .forClass(java.util.List.class);
        org.mockito.Mockito.verify(workflow).requestPublish(eq(TENANT),
                eq(ConfigRelease.Scope.TENANT), agentCaptor.capture(), contractCaptor.capture(),
                policyCaptor.capture(), any(), eq("reason"), eq(PRINCIPAL));

        @SuppressWarnings("unchecked")
        java.util.List<cn.studykid.multiagent.application.cap.ConfigPublishPayload.AgentSpec>
                agentSpecs = agentCaptor.getValue();
        assertThat(agentSpecs).hasSize(1);
        assertThat(agentSpecs.get(0).orchestration())
                .as("⛔ 编排声明未被治理通道透传：多级 Agent 无法发布")
                .contains("SEQUENTIAL")
                .contains("childAgentRefs");

        @SuppressWarnings("unchecked")
        java.util.List<cn.studykid.multiagent.application.cap.ConfigPublishPayload
                .ResultContractSpec> contractSpecs = contractCaptor.getValue();
        assertThat(contractSpecs).hasSize(1);
        assertThat(contractSpecs.get(0).contractId()).isEqualTo("answer-contract");
        assertThat(contractSpecs.get(0).schemaJson()).isEqualTo("{\"type\":\"object\"}");

        @SuppressWarnings("unchecked")
        java.util.List<cn.studykid.multiagent.application.cap.ConfigPublishPayload
                .RoutingPolicySpec> policySpecs = policyCaptor.getValue();
        assertThat(policySpecs)
                .as("⛔ 路由策略未被治理通道透传：ROUTER Agent 引用了一个无法登记的策略")
                .hasSize(1);
        assertThat(policySpecs.get(0).policyId()).isEqualTo("orders-router");
        assertThat(policySpecs.get(0).strategy()).isEqualTo("EXPLICIT_TARGETS");
        assertThat(policySpecs.get(0).targets()).containsExactly("c-1");
    }

    @Test
    @DisplayName("执行时 CAS 基线过期 → 409")
    void apply_baselineConflict() {
        CapConfigPublishController c = controller();
        when(workflow.apply(TENANT, REQUEST_ID, PRINCIPAL))
                .thenThrow(new ConfigPublishBaselineConflictException("stale baseline"));

        assertThatThrownBy(() -> c.apply(REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}

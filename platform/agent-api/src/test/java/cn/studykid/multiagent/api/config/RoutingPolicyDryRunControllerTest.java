package cn.studykid.multiagent.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.config.CapConfigPublishController.RoutingPolicyRequest;
import cn.studykid.multiagent.api.config.RoutingPolicyDryRunController.DryRunRequest;
import cn.studykid.multiagent.api.config.RoutingPolicyDryRunController.DryRunResponse;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.RoutingPolicyDryRunService;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.routing.PublishGuards;
import cn.studykid.multiagent.routing.RoutingDryRun;

/**
 * 路由策略 dry-run 端点测试（10 号设计 §7 债 5 · A7-1b 的接入面）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>租户<b>只</b>来自已验签令牌，不来自请求体（G-6 的端点侧证明）；</li>
 *   <li>授权动作 = {@code config:publish}，且纳入限流（429）；</li>
 *   <li>「策略文法不过」是 200 + {@code parsed=false}，而「请求本身不合法」才是 400 ——
 *       两者搞混等于把配置缺陷伪装成系统错误；</li>
 *   <li>响应把引擎结论原样透出（含轨迹与投影后的输入），不在 API 层重算任何东西。</li>
 * </ul>
 *
 * <p>⚠️ 用例服务用<b>真身</b>（固定时钟），不 mock 引擎：mock 掉引擎就等于
 * 只测了字段搬运，而本端点的全部意义在于「跑的是运行期那份实现」。
 */
@DisplayName("S5 路由策略 dry-run 端点")
class RoutingPolicyDryRunControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PRINCIPAL = "principal-1";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T09:00:00Z"), ZoneOffset.UTC);

    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private RoutingPolicyDryRunController controller() {
        return controllerWith(new RoutingPolicyDryRunService(CLOCK));
    }

    private RoutingPolicyDryRunController controllerWith(RoutingPolicyDryRunService service) {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        TenantContext context = new TenantContext(TENANT, PRINCIPAL, Set.of("TENANT_ADMIN"), 1L);
        when(currentPrincipal.context()).thenReturn(context);
        return new RoutingPolicyDryRunController(service, authorization, rateLimiter,
                currentPrincipal);
    }

    /** 一份能跑出一份守卫结论的最小草稿（无样本 ⇒ V9 拦发布）。 */
    private static RoutingPolicyRequest policy(String mode, String evaluation) {
        return new RoutingPolicyRequest("orders-router", mode,
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}",
                evaluation, "faq-answer", "DEFAULT_TARGET", "RULE_WINS", "[]");
    }

    private static final String ONE_RULE = """
            [{"ruleId": "r-refund", "target": "refund-agent",
              "when": {"eq": ["in.topic", "refund"]}}]
            """;

    @Test
    @DisplayName("✅ 200：探针结论、命中规则与求值轨迹原样透出")
    void returnsEngineVerdictsUnchanged() {
        DryRunResponse body = controller().dryRun(new DryRunRequest(
                policy("RULES_ONLY", ONE_RULE),
                "[{\"label\":\"退款\",\"input\":{\"topic\":\"refund\"}}]")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.parsed()).isTrue();
        assertThat(body.replayDate()).isEqualTo("2026-03-01");
        assertThat(body.probes()).singleElement().satisfies(probe -> {
            assertThat(probe.decided()).isTrue();
            assertThat(probe.label()).isEqualTo("退款");
            assertThat(probe.resolution()).isEqualTo("RULE_MATCH");
            assertThat(probe.finalTarget()).isEqualTo("refund-agent");
            assertThat(probe.matchedRuleId()).isEqualTo("r-refund");
            assertThat(probe.projectedInput()).containsEntry("topic", "refund");
            assertThat(probe.trace()).singleElement().satisfies(entry -> {
                assertThat(entry.ruleId()).isEqualTo("r-refund");
                assertThat(entry.outcome()).isEqualTo("MATCH");
                assertThat(entry.ordinal()).isEqualTo(1);
            });
        });
    }

    @Test
    @DisplayName("✅ 守卫不过 = 200 + publishable=false，不是 400")
    void guardFindingsAreBusinessVerdicts() {
        DryRunResponse body = controller().dryRun(new DryRunRequest(
                policy("RULES_ONLY", ONE_RULE), "[]")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.publishable())
                .as("sampleSet 为空 ⇒ V9「有规则指向的目标从没被样本命中过」")
                .isFalse();
        assertThat(body.violations())
                .extracting(RoutingPolicyDryRunController.FindingResponse::code).contains("V9");
        assertThat(body.warnings()).isEmpty();
    }

    @Test
    @DisplayName("✅ 策略文法不过 = 200 + parsed=false + 位置与原因")
    void unparsablePolicyIsReportedNotThrown() {
        DryRunResponse body = controller().dryRun(new DryRunRequest(
                policy("RULES_ONLY",
                        "[{\"ruleId\":\"r\",\"target\":\"refund-agent\","
                                + "\"when\":{\"equals\":[\"in.topic\",\"refund\"]}}]"),
                "[]")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.parsed()).isFalse();
        assertThat(body.errorMessage()).contains("equals");
        assertThat(body.probes()).isEmpty();
    }

    @Test
    @DisplayName("⛔ 租户来自已验签令牌：请求体里没有 tenantId 这条路")
    void tenantComesFromTokenOnly() {
        RoutingPolicyDryRunService service = mock(RoutingPolicyDryRunService.class);
        when(service.run(anyString(), any(), any())).thenReturn(new RoutingDryRun.Outcome(
                null, new PublishGuards.Report(List.of()), List.of(),
                java.time.LocalDate.of(2026, 3, 1)));

        controllerWith(service).dryRun(new DryRunRequest(policy("RULES_ONLY", ONE_RULE), "[]"));

        verify(service).run(eq(TENANT), any(), eq("[]"));
        verify(authorization).requireAuthorized(any(TenantContext.class),
                eq(AuthorizationService.Actions.CONFIG_PUBLISH), isNull());
    }

    @Test
    @DisplayName("⛔ 没带草稿 = 400（没有草稿就没有结论，不能返回一份空报告装作跑过）")
    void missingDraftIsBadRequest() {
        assertThatThrownBy(() -> controller().dryRun(new DryRunRequest(null, "[]")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("⛔ 行级不合法（枚举名拼错）= 400，而不是 500")
    void rowLevelInvalidDraftIsBadRequest() {
        assertThatThrownBy(() -> controller().dryRun(new DryRunRequest(
                policy("RULE_ONLY", ONE_RULE), "[]")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("✅ 试跑纳入限流：一次 dry-run 要把每条探针过一遍引擎，治理通道上没有免费的读")
    void rateLimitedReturns429() {
        RoutingPolicyDryRunController controller =
                controllerWith(mock(RoutingPolicyDryRunService.class));
        // 桩在构造之后覆盖：controllerWith() 默认放行，这里要的是「超限」。
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.REJECTED);

        ResponseEntity<DryRunResponse> response = controller.dryRun(
                new DryRunRequest(policy("RULES_ONLY", ONE_RULE), "[]"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

package cn.studykid.multiagent.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;

/**
 * 租户侧反馈端点测试（P7-3 / P7-4）。
 *
 * <p><b>本类验证什么</b>（每条都能被证伪）：
 * <ul>
 *   <li>落库的 {@code tenantId} <b>必须</b>来自已验签主体，
 *       <b>绝不是</b>调用方在请求体里塞进来的值（跨租户污染防线）；</li>
 *   <li>落库的 {@code traceId} <b>由服务端解析</b>（前端只给 runId）——
 *       前端无法把反馈挂到任意 trace 上；</li>
 *   <li>解析不到（run 不存在 / 不属本租户 / 无会话标识）→ 404 且<b>不落库</b>；</li>
 *   <li>缺字段 / 非法 kind / 非法 runId → 400 且不落库；</li>
 *   <li>无令牌（主体抛 {@code UnauthenticatedException}）→ 401，且<b>不落库</b>。</li>
 * </ul>
 */
@DisplayName("P7-3/P7-4 TenantFeedbackController · 租户归属绑定")
class TenantFeedbackControllerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String RUN_ID = "33333333-3333-3333-3333-333333333333";
    private static final String TRACE_ID = "trace-from-server";

    private final AgentMetricPort metrics = mock(AgentMetricPort.class);
    private final TraceFactPort facts = mock(TraceFactPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private TenantFeedbackController controller() {
        return new TenantFeedbackController(metrics, facts, currentPrincipal);
    }

    private void stubContext(String tenantId) {
        when(currentPrincipal.context())
                .thenReturn(new TenantContext(tenantId, "principal-1", Set.of("TENANT_USER"), 1L));
    }

    private void stubTraceId(String traceId) {
        when(facts.resolveTraceId(eq(TENANT_ID), any(UUID.class)))
                .thenReturn(Optional.ofNullable(traceId));
    }

    // ── 正向：tenantId 取自已验签主体，traceId 取自服务端解析 ──

    @Test
    @DisplayName("反馈成功 → tenantId 取自已验签主体，traceId 取自服务端解析")
    void feedback_ok_records_with_verified_tenant_and_resolved_trace() {
        stubContext(TENANT_ID);
        stubTraceId(TRACE_ID);
        var resp = controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "THUMB_UP"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<AgentMetricPort.FeedbackEvent> captor =
                ArgumentCaptor.forClass(AgentMetricPort.FeedbackEvent.class);
        verify(metrics).recordFeedback(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().traceId()).isEqualTo(TRACE_ID);
        assertThat(captor.getValue().kind())
                .isEqualTo(AgentMetricPort.FeedbackEvent.FeedbackKind.THUMB_UP);
    }

    @Test
    @DisplayName("runId 解析时带的是已验签租户（跨租户 runId 解析不到 → 404）")
    void feedback_resolves_trace_with_verified_tenant_only() {
        stubContext(TENANT_ID);
        stubTraceId(TRACE_ID);
        controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "RETRY"));

        verify(facts).resolveTraceId(eq(TENANT_ID), eq(UUID.fromString(RUN_ID)));
        // 绝不能拿别的租户去解析
        verify(facts, never()).resolveTraceId(eq(OTHER_TENANT_ID), any(UUID.class));
    }

    @Test
    @DisplayName("kind 大小写不敏感（前端不必强制大写）")
    void feedback_accepts_lowercase_kind() {
        stubContext(TENANT_ID);
        stubTraceId(TRACE_ID);
        controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "thumb_down"));

        ArgumentCaptor<AgentMetricPort.FeedbackEvent> captor =
                ArgumentCaptor.forClass(AgentMetricPort.FeedbackEvent.class);
        verify(metrics).recordFeedback(captor.capture());
        assertThat(captor.getValue().kind())
                .isEqualTo(AgentMetricPort.FeedbackEvent.FeedbackKind.THUMB_DOWN);
    }

    // ── 解析不到：404 且不落库 ──

    @Test
    @DisplayName("runId 解析不到 traceId（不存在/跨租户/无会话标识）→ 404 且不落库")
    void feedback_not_found_when_trace_unresolvable() {
        stubContext(TENANT_ID);
        stubTraceId(null);
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "THUMB_UP")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(metrics, never()).recordFeedback(any());
    }

    // ── 校验：400 且不落库 ──

    @Test
    @DisplayName("缺 runId → 400 且不落库")
    void feedback_bad_request_when_run_id_blank() {
        stubContext(TENANT_ID);
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest("  ", "THUMB_UP")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(metrics, never()).recordFeedback(any());
    }

    @Test
    @DisplayName("缺 kind → 400 且不落库")
    void feedback_bad_request_when_kind_blank() {
        stubContext(TENANT_ID);
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(metrics, never()).recordFeedback(any());
    }

    @Test
    @DisplayName("runId 不是合法标识 → 400 且不落库")
    void feedback_bad_request_when_run_id_not_uuid() {
        stubContext(TENANT_ID);
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest("not-a-uuid", "THUMB_UP")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(metrics, never()).recordFeedback(any());
        verify(facts, never()).resolveTraceId(any(), any());
    }

    @Test
    @DisplayName("非法 kind → 400 且不落库（不静默吞掉）")
    void feedback_bad_request_when_kind_unknown() {
        stubContext(TENANT_ID);
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "HACKED")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(metrics, never()).recordFeedback(any());
    }

    // ── 未认证：401 且不落库 ──

    @Test
    @DisplayName("无令牌（主体抛 UnauthenticatedException）→ 401 且不落库")
    void feedback_unauthenticated_when_no_token() {
        when(currentPrincipal.context()).thenThrow(
                new cn.studykid.multiagent.api.security.CurrentPrincipal.UnauthenticatedException(
                        "缺少访问令牌"));
        assertThatThrownBy(() -> controller().recordFeedback(
                new TenantFeedbackController.FeedbackRequest(RUN_ID, "THUMB_UP")))
                .isInstanceOf(
                        cn.studykid.multiagent.api.security.CurrentPrincipal.UnauthenticatedException.class);
        verify(metrics, never()).recordFeedback(any());
    }
}

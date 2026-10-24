package cn.studykid.multiagent.api.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Agent 配置端点测试（P1 · 详情 / 版本历史读）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>{@code GET /api/agents/{id}} 命中返回 200 + 详情，未命中返回 404（不区分「不存在」与「无权限」）；</li>
 *   <li>{@code GET /api/agents/{id}/versions} 命中返回 200 + 版本快照列表，Agent 不存在返回 404；</li>
 *   <li>鉴权失败（{@code requireAuthorized} 抛 {@link AccessDeniedException}）时，请求被拒绝向上传播。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：仓储与限流均以 Mockito 桩替代，聚焦于<b>读路径与闸门</b>逻辑。
 */
@DisplayName("P1 AgentController · 详情 / 版本历史读")
class AgentControllerTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String AGENT_ID = "agent-1";

    private final AgentQueryPort agents = mock(AgentQueryPort.class);
    private final AgentManagePort agentManage = mock(AgentManagePort.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private AgentController controller() {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        return new AgentController(agents, agentManage, authorization, rateLimiter, currentPrincipal);
    }

    private void stubContext(Set<String> roles) {
        TenantContext ctx = new TenantContext(TENANT_ID, "principal-1", roles, 1L);
        when(currentPrincipal.context()).thenReturn(ctx);
    }

    private AgentItem sampleAgent() {
        return new AgentItem(AGENT_ID, "demo-agent", "demo", 2, 2, "{}", Instant.now());
    }

    // ── GET /api/agents/{id} ──

    @Test
    @DisplayName("详情命中 → 200 + 详情体")
    void getAgent_ok_when_found() {
        stubContext(Set.of("TENANT_ADMIN"));
        AgentController c = controller();
        when(agents.findAgent(TENANT_ID, AGENT_ID)).thenReturn(sampleAgent());

        var resp = c.getAgent(AGENT_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().agentId()).isEqualTo(AGENT_ID);
        assertThat(resp.getBody().name()).isEqualTo("demo-agent");
    }

    @Test
    @DisplayName("详情未命中 → 404")
    void getAgent_notFound_when_missing() {
        stubContext(Set.of("TENANT_ADMIN"));
        AgentController c = controller();
        when(agents.findAgent(TENANT_ID, AGENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> c.getAgent(AGENT_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── GET /api/agents/{id}/versions ──

    @Test
    @DisplayName("版本历史命中 → 200 + 版本快照列表")
    void listVersions_ok_when_found() {
        stubContext(Set.of("TENANT_ADMIN"));
        AgentController c = controller();
        when(agents.findAgent(TENANT_ID, AGENT_ID)).thenReturn(sampleAgent());
        when(agents.listVersions(TENANT_ID, AGENT_ID)).thenReturn(List.of(
                new AgentVersionItem("v-2", 2, "{}", Instant.now(), Instant.now()),
                new AgentVersionItem("v-1", 1, "{}", null, Instant.now())));

        var resp = c.listAgentVersions(AGENT_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0).versionNumber()).isEqualTo(2);
        assertThat(resp.getBody().get(1).versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("版本历史：Agent 不存在 → 404")
    void listVersions_notFound_when_missing() {
        stubContext(Set.of("TENANT_ADMIN"));
        AgentController c = controller();
        when(agents.findAgent(TENANT_ID, AGENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> c.listAgentVersions(AGENT_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── 鉴权闸门 ──

    @Test
    @DisplayName("无 AGENT_MANAGE 授权 → AccessDeniedException 向上传播")
    void getAgent_denied_without_authorization() {
        stubContext(Set.of("TENANT_VIEWER"));
        AgentController c = controller();
        doThrow(new AccessDeniedException("principal-1", "run:read", null))
                .when(authorization).requireAuthorized(any(), any(), any());

        assertThatThrownBy(() -> c.getAgent(AGENT_ID))
                .isInstanceOf(AccessDeniedException.class);
    }
}

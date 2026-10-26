package cn.studykid.multiagent.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 平台级能力写入口的闸门测试（第三十四批 · C34-2）。
 *
 * <p>⚠️ <b>本类钉住的是「默认拒绝」这条性质</b>：
 * 平台级对象（Tool / Skill / MCP Server）影响<b>全平台</b>，
 * 平台身份表 {@code principal} 是<b>租户内</b>的，没有跨租户主体 ——
 * 若闸门缺失，任一租户管理员自授角色即可改全平台配置。
 * 故本类的重点不是「能写」，而是「<b>不能写的人一定写不了</b>」。
 *
 * <p>⚠️ <b>「看得见」与「不该给的不给」成对断言</b>：
 * 每条「PLATFORM_ADMIN 能做」旁边都有一条「非 PLATFORM_ADMIN 一定被拒」。
 */
@DisplayName("C34-2 PlatformCapabilityController · 平台写入口闸门")
class PlatformCapabilityControllerTest {

    private static final String REQUESTER = "platform-admin-1";

    private final PlatformCapabilityPublishService platform = mock(PlatformCapabilityPublishService.class);
    private final RateLimitPort rateLimiter = mock(RateLimitPort.class);
    private final CurrentPrincipal currentPrincipal = mock(CurrentPrincipal.class);

    private PlatformCapabilityController controller(Set<String> roles) {
        when(rateLimiter.check(any(), any())).thenReturn(RateLimitPort.Decision.ALLOWED);
        when(currentPrincipal.context()).thenReturn(
                new TenantContext("tenant-1", REQUESTER, roles, 1L));
        return new PlatformCapabilityController(platform, rateLimiter, currentPrincipal);
    }

    private static ConfigPublishRequest stubRequest() {
        return new ConfigPublishRequest("req-1", null, ConfigPublishRequest.Operation.PUBLISH,
                "{}", "登记", REQUESTER, ConfigPublishRequest.State.PENDING, null, null,
                java.time.Instant.parse("2026-01-01T00:00:00Z"), null, null, null,
                ConfigRelease.Scope.PLATFORM);
    }

    @Test
    @DisplayName("✅ PLATFORM_ADMIN 可以提交平台级发布申请")
    void platformAdminCanRequestPublish() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));
        when(platform.requestPublish(any(), any(), any(), any(), any(), any()))
                .thenReturn(stubRequest());

        var resp = c.requestPublish(new PlatformCapabilityController.PlatformPublishRequest(
                List.of(new PlatformCapabilityController.PlatformToolSpec(
                        "tool-1", "工具", "NATIVE", "READ", null, null, null, null, null, "{}", "{}")),
                List.of(), List.of(), null, "登记一个工具"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().scope()).isEqualTo("PLATFORM");
    }

    @Test
    @DisplayName("⛔ 非 PLATFORM_ADMIN → 403（默认拒绝，一个字节都不许写）")
    void nonPlatformAdminIsForbidden() {
        PlatformCapabilityController c = controller(Set.of("TENANT_ADMIN"));

        assertThatThrownBy(() -> c.requestPublish(
                new PlatformCapabilityController.PlatformPublishRequest(
                        List.of(new PlatformCapabilityController.PlatformToolSpec(
                                "tool-1", "工具", "NATIVE", "READ", null, null, null, null, null, "{}", "{}")),
                        List.of(), List.of(), null, "登记")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // ⚠️ 「不该给的不给」的反面断言：**一个字节都不许写**。
        org.mockito.Mockito.verify(platform, org.mockito.Mockito.never())
                .requestPublish(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⛔ 角色集合为空（未认证 / 无角色）→ 403")
    void emptyRolesAreForbidden() {
        PlatformCapabilityController c = controller(Set.of());

        assertThatThrownBy(() -> c.listRequests(null, null, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("⛔ 变更理由为空 → 400（不得先落申请单再补理由）")
    void emptyReasonIsBadRequest() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));

        assertThatThrownBy(() -> c.requestPublish(
                new PlatformCapabilityController.PlatformPublishRequest(
                        List.of(new PlatformCapabilityController.PlatformToolSpec(
                                "tool-1", "工具", "NATIVE", "READ", null, null, null, null, null, "{}", "{}")),
                        List.of(), List.of(), null, "   ")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        org.mockito.Mockito.verify(platform, org.mockito.Mockito.never())
                .requestPublish(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⛔ 运维角色未接入 → 503（环境问题，不是用户错误）")
    void unavailableOpsChannelIsServiceUnavailable() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));
        when(platform.requestPublish(any(), any(), any(), any(), any(), any()))
                .thenThrow(new PlatformOpsUnavailableException(
                        "agent.platform-ops.datasource.enabled", "平台运维角色未接入"));

        assertThatThrownBy(() -> c.requestPublish(
                new PlatformCapabilityController.PlatformPublishRequest(
                        List.of(new PlatformCapabilityController.PlatformToolSpec(
                                "tool-1", "工具", "NATIVE", "READ", null, null, null, null, null, "{}", "{}")),
                        List.of(), List.of(), null, "登记")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("⛔ 自审批 → 409（双人复核）")
    void selfApprovalIsConflict() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));
        when(platform.decide(any(), any(), any(), any()))
                .thenThrow(new ConfigPublishRequestPort.SelfApprovalException());

        assertThatThrownBy(() -> c.decide("req-1",
                new PlatformCapabilityController.PlatformDecisionRequest("APPROVE", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("⛔ 非法 decision → 400")
    void illegalDecisionIsBadRequest() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));

        assertThatThrownBy(() -> c.decide("req-1",
                new PlatformCapabilityController.PlatformDecisionRequest("MAYBE", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("✅ 审批与执行都过闸门")
    void decideAndApplyPassTheGate() {
        PlatformCapabilityController c = controller(Set.of("PLATFORM_ADMIN"));
        when(platform.decide(any(), any(), any(), any())).thenReturn(stubRequest());
        when(platform.apply(any(), any())).thenReturn(stubRequest());

        assertThat(c.decide("req-1", new PlatformCapabilityController.PlatformDecisionRequest(
                "APPROVE", "同意")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(c.apply("req-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

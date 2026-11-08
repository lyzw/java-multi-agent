package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.TranscriptController;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.RunMeta;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * 平台侧跨租户回放端点测试（P1 · G1）。
 *
 * <p><b>本类守的是「顺序」而不是「结果」</b>：平台令牌不绑租户，
 * 端点必须<b>先过权限闸门</b>、再反查租户、最后以该租户身份读取。
 * 若把反查放在授权之前，一个没有 {@code platform.resource.read} 的主体
 * 也能借 404 与 403 的差异枚举全平台 Run 是否存在（归属信息泄露）。
 *
 * <p>另外断言 <b>ADMIN 受众看得到 TOOL 消息</b> ——
 * 与租户侧的 STUDENT 过滤互为对照，防止某天把两侧都改成同一受众。
 */
@DisplayName("P1 平台侧回放端点 · 先授权后反查 + ADMIN 完整时间线")
class PlatformTranscriptControllerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String RUN_ID = "33333333-3333-3333-3333-333333333333";
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private final PlatformSecurity security = mock(PlatformSecurity.class);
    private final TraceReplayService replay = mock(TraceReplayService.class);
    private final TraceReplayPort replayPort = mock(TraceReplayPort.class);

    private final PlatformPrincipal principal =
            new PlatformPrincipal("admin-1", Set.of(PlatformRoles.AUDITOR), 1, false);

    private PlatformTranscriptController controller() {
        return new PlatformTranscriptController(security, replay, replayPort);
    }

    private static TraceReplayService.Transcript transcript() {
        return new TraceReplayService.Transcript(
                new RunMeta(RUN_ID, "trace-1", "SUCCEEDED", null,
                        NOW, NOW.plusMillis(800), 800L),
                null,
                List.of(
                        new MessageView(1, "USER", 0, Map.of("text", "这道题怎么做"),
                                null, null, null, null, null, NOW),
                        new MessageView(2, "ASSISTANT", 1, Map.of("text", "先移项"),
                                null, null, null, null, null, NOW),
                        new MessageView(3, "TOOL", 1, Map.of("tool", "get_student_profile"),
                                null, null, null, null, null, NOW)),
                List.of(),
                null);
    }

    @Test
    @DisplayName("✅ 先按 platform.resource.read 授权，再以反查到的租户身份读完整时间线")
    void requiresPermissionThenReadsWithResolvedTenant() {
        when(replayPort.resolveTenantId(RUN_ID)).thenReturn(Optional.of(TENANT_ID));
        when(replay.buildTranscript(TENANT_ID, RUN_ID)).thenReturn(transcript());

        TranscriptController.TranscriptResponse resp =
                controller().transcript(principal, UUID.fromString(RUN_ID), null);

        assertThat(resp.runId()).isEqualTo(RUN_ID);
        assertThat(resp.messages())
                .as("⚠️ 平台审查者必须看得到 TOOL 消息（ADMIN 受众）")
                .extracting(TranscriptController.Message::role)
                .containsExactly("USER", "ASSISTANT", "TOOL");

        verify(security).require(eq(principal), eq(Namespaces.Platform.RESOURCE_READ),
                isNull(), isNull());
        verify(replay).buildTranscript(eq(TENANT_ID), eq(RUN_ID));
    }

    @Test
    @DisplayName("⚠️⚠️ 权限拒绝必须短路：绝不反查租户、绝不读回放")
    void permissionDeniedShortCircuitsBeforeAnyDataAccess() {
        doThrow(new AccessDeniedException("admin-1",
                Namespaces.Platform.RESOURCE_READ, null))
                .when(security).require(any(), any(), any(), any());

        assertThatThrownBy(() -> controller().transcript(principal, UUID.fromString(RUN_ID), null))
                .isInstanceOf(AccessDeniedException.class);

        verify(replayPort, never()).resolveTenantId(any());
        verify(replay, never()).buildTranscript(any(), any());
    }

    @Test
    @DisplayName("⚠️ 反查不到 Run → 404，且不读回放")
    void unknownRunIs404WithoutReadingTranscript() {
        when(replayPort.resolveTenantId(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().transcript(principal, UUID.fromString(RUN_ID), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(replay, never()).buildTranscript(any(), any());
    }

    @Test
    @DisplayName("⚠️ 反查到租户但回放组装为空 → 404（不泄露归属差异）")
    void nullTranscriptIs404() {
        when(replayPort.resolveTenantId(RUN_ID)).thenReturn(Optional.of(TENANT_ID));
        when(replay.buildTranscript(TENANT_ID, RUN_ID)).thenReturn(null);

        assertThatThrownBy(() -> controller().transcript(principal, UUID.fromString(RUN_ID), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("✅ 显式 tenantId 优先：覆盖「指标尚未采集」的 Run，且不做跨租户反查")
    void explicitTenantIdSkipsResolution() {
        when(replay.buildTranscript(TENANT_ID, RUN_ID)).thenReturn(transcript());

        TranscriptController.TranscriptResponse resp = controller().transcript(
                principal, UUID.fromString(RUN_ID), TENANT_ID);

        assertThat(resp.runId()).isEqualTo(RUN_ID);
        verify(security).require(eq(principal), eq(Namespaces.Platform.RESOURCE_READ),
                isNull(), isNull());
        verify(replayPort, never()).resolveTenantId(any());
        verify(replay).buildTranscript(eq(TENANT_ID), eq(RUN_ID));
    }

    @Test
    @DisplayName("⚠️ 显式 tenantId 非法 → 400，不下沉到 SQL 变成 500")
    void malformedExplicitTenantIdIs400() {
        assertThatThrownBy(() -> controller().transcript(
                principal, UUID.fromString(RUN_ID), "not-a-uuid"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(replay, never()).buildTranscript(any(), any());
    }

    @Test
    @DisplayName("⚠️ 反查失败 404 必须如实说明「无法定位租户」，不伪装成「Run 不存在」")
    void unresolvedTenantExplainsWhy() {
        when(replayPort.resolveTenantId(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().transcript(principal, UUID.fromString(RUN_ID), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((ResponseStatusException) e).getReason())
                            .as("⚠️ 把「无法定位租户」写成「Run 不存在」会诱导审查者"
                                    + "以为数据没了，真因其实是缺 tenantId 或未采集指标")
                            .contains("无法仅凭 runId 定位所属租户")
                            .contains("请显式传 tenantId");
                });
    }
}

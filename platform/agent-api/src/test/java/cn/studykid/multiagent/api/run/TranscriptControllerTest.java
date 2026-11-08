package cn.studykid.multiagent.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.CurrentPrincipal.UnauthenticatedException;
import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.RunMeta;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.StepView;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.TokenTotal;

/**
 * 租户侧回放端点测试（P1 · G1）。
 *
 * <p><b>为什么必须有这一类</b>：闭环 IT 验证「消息确实落库并被组装出来」，
 * 但<b>端点层</b>还有两件 IT 覆盖不到的事：
 * <ol>
 *   <li><b>租户归属</b>必须来自已验签主体，而不是任何请求参数；
 *       一旦这里写错，跨租户 runId 就能读到别人的学生对话（Sev-1）。</li>
 *   <li><b>学生端必须隐藏 TOOL 消息</b>：工具入参与结果摘要是运维视角，
 *       漏过滤等于把内部执行细节直接暴露给学生。</li>
 * </ol>
 * 这两条都属于「端点返回 200、数据也对、但边界错了」的静默失真，
 * 只能靠端点级断言挡住。
 */
@DisplayName("P1 租户侧回放端点 · 归属绑定与受众过滤")
class TranscriptControllerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String RUN_ID = "33333333-3333-3333-3333-333333333333";
    private static final String TRACE_ID = "trace-replay-1";
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private final TraceReplayService replay = mock(TraceReplayService.class);
    private final RunController.CurrentPrincipal principal =
            mock(RunController.CurrentPrincipal.class);

    private TranscriptController controller() {
        return new TranscriptController(replay, principal);
    }

    private void stubTenant() {
        when(principal.context()).thenReturn(
                new TenantContext(TENANT_ID, "principal-1", Set.of("TENANT_USER"), 1L));
    }

    private static MessageView message(long seq, String role, Map<String, Object> content) {
        return new MessageView(seq, role, 0, content, null, "fake-model",
                null, null, null, NOW);
    }

    private static TraceReplayService.Transcript transcript(boolean includeTool,
                                                            boolean includeTokens) {
        List<MessageView> messages = new ArrayList<>();
        messages.add(message(1, "USER", Map.of("text", "这道题怎么做")));
        messages.add(message(2, "ASSISTANT", Map.of("text", "先移项再求解")));
        if (includeTool) {
            messages.add(message(3, "TOOL",
                    Map.of("tool", "get_student_profile", "status", "SUCCEEDED")));
        }
        return new TraceReplayService.Transcript(
                new RunMeta(RUN_ID, TRACE_ID, "SUCCEEDED", null,
                        NOW, NOW.plusMillis(1200), 1200L),
                Map.of("snapshotId", "snap-1"),
                messages,
                List.of(new StepView(1, "THINK", 300L)),
                includeTokens ? new TokenTotal(10, 20, new BigDecimal("0.02")) : null);
    }

    @Test
    @DisplayName("✅ 回放成功：元信息/快照/步骤/用量齐全，且 tenantId 取自已验签主体")
    void transcriptUsesVerifiedTenantAndReturnsFullMetadata() {
        stubTenant();
        when(replay.buildTranscript(eq(TENANT_ID), eq(RUN_ID)))
                .thenReturn(transcript(true, true));

        TranscriptController.TranscriptResponse resp =
                controller().transcript(UUID.fromString(RUN_ID));

        assertThat(resp.runId()).isEqualTo(RUN_ID);
        assertThat(resp.traceId()).isEqualTo(TRACE_ID);
        assertThat(resp.state()).isEqualTo("SUCCEEDED");
        assertThat(resp.snapshot()).containsEntry("snapshotId", "snap-1");
        assertThat(resp.steps()).hasSize(1);
        assertThat(resp.tokens()).isNotNull();
        assertThat(resp.tokens().input()).isEqualTo(10L);
        assertThat(resp.tokens().output()).isEqualTo(20L);

        verify(replay).buildTranscript(eq(TENANT_ID), eq(RUN_ID));
        verify(replay, never()).buildTranscript(eq(OTHER_TENANT_ID), any());
    }

    @Test
    @DisplayName("⚠️⚠️ 学生端不得看到 TOOL 消息（ADMIN 才看完整时间线）")
    void studentAudienceHidesToolMessages() {
        stubTenant();
        when(replay.buildTranscript(eq(TENANT_ID), eq(RUN_ID)))
                .thenReturn(transcript(true, true));

        TranscriptController.TranscriptResponse resp =
                controller().transcript(UUID.fromString(RUN_ID));

        assertThat(resp.messages())
                .as("⚠️ 学生端漏过滤工具消息 = 把工具入参与结果摘要直接暴露给学生")
                .extracting(TranscriptController.Message::role)
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    @DisplayName("⚠️ 跨租户 / 不存在的 runId 一律 404（不泄露归属）")
    void foreignOrMissingRunIs404() {
        stubTenant();
        when(replay.buildTranscript(eq(TENANT_ID), eq(RUN_ID))).thenReturn(null);

        assertThatThrownBy(() -> controller().transcript(UUID.fromString(RUN_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("⚠️ 无令牌 → 401 语义，且绝不读取任何回放数据")
    void unauthenticatedNeverReadsTranscript() {
        when(principal.context()).thenThrow(new UnauthenticatedException("缺少访问令牌"));

        assertThatThrownBy(() -> controller().transcript(UUID.fromString(RUN_ID)))
                .isInstanceOf(UnauthenticatedException.class);
        verify(replay, never()).buildTranscript(any(), any());
    }

    @Test
    @DisplayName("⚠️ tokens=null 表示「未采集」，不是「这次没花钱」")
    void nullTokensMeansNotCollected() {
        stubTenant();
        when(replay.buildTranscript(eq(TENANT_ID), eq(RUN_ID)))
                .thenReturn(transcript(false, false));

        TranscriptController.TranscriptResponse resp =
                controller().transcript(UUID.fromString(RUN_ID));

        assertThat(resp.tokens())
                .as("⚠️ 把「未采集」压成 0 会让审查者误判这次辅导零成本")
                .isNull();
    }
}

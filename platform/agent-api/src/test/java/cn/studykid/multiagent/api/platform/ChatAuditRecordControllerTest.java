package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Detail;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Query;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Source;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Summary;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * 对话审计记录查询 Controller 单元测试（无容器，Fake Port + 真实授权服务）。
 *
 * <p>验证三件单测能守住的契约：
 * <ol>
 *   <li>鉴权：无 {@code platform.audit.read} 的角色 403；</li>
 *   <li>参数：非法 limit / 时间 / 终态 / UUID 一律 400，不静默空态；</li>
 *   <li>映射：列表摘要 + 游标透传；详情 payload 反序列化为 JsonNode；
 *       运维通道未接入的 fail-closed 异常<b>原样冒泡</b>（→ 全局 503），不得吞成空列表。</li>
 * </ol>
 */
class ChatAuditRecordControllerTest {

    private static final String AUDIT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String TRACE = "trace-abc";

    private FakeQueryPort port;
    private ChatAuditRecordController controller;
    private PlatformPrincipal auditor;
    private PlatformPrincipal noPerm;

    @BeforeEach
    void setUp() {
        port = new FakeQueryPort();
        PlatformSecurity security = new PlatformSecurity(new PlatformAuthorizationServiceImpl());
        controller = new ChatAuditRecordController(port, security);
        auditor = new PlatformPrincipal("u-audit", Set.of(PlatformRoles.AUDITOR), 1L, true);
        noPerm = new PlatformPrincipal("u-support", Set.of(PlatformRoles.SUPPORT), 1L, true);
    }

    @Test
    @DisplayName("鉴权：SUPPORT 角色无 AUDIT_READ → 403")
    void deniesWithoutAuditRead() {
        assertThatThrownBy(() -> controller.list(noPerm, null, null, null, null, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.detail(noPerm, "RECORD", AUDIT_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("列表：AUDITOR 可查；参数归一化 + 摘要映射 + 游标透传")
    void listMapsSummaryAndCursor() {
        port.next = Page.of(List.of(summary()), "next-cursor");

        ResponseEntity<GovernanceSupport.PageResponse<ChatAuditRecordController.AuditSummary>> resp =
                controller.list(auditor, "ALL", TENANT, "/api/agent/chat/stream",
                        "SUCCEEDED", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, 50);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().items()).hasSize(1);
        assertThat(resp.getBody().nextCursor()).isEqualTo("next-cursor");
        ChatAuditRecordController.AuditSummary item = resp.getBody().items().get(0);
        assertThat(item.auditId()).isEqualTo(AUDIT_ID);
        assertThat(item.terminalState()).isEqualTo("SUCCEEDED");
        // source=ALL → 两域合并（port 收到 null source）。
        assertThat(port.lastQuery.source()).isNull();
        assertThat(port.lastQuery.tenantId()).isEqualTo(TENANT);
        assertThat(port.lastQuery.limit()).isEqualTo(50);
    }

    @Test
    @DisplayName("参数非法：limit / 时间倒挂 / 终态 / UUID 一律被拒（400 语义）")
    void rejectsBadParams() {
        assertThatThrownBy(() -> controller.list(auditor, null, null, null, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.list(auditor, null, null, null, null,
                "2026-01-02T00:00:00Z", "2026-01-01T00:00:00Z", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.list(auditor, null, null, null, "UNKNOWN_STATE",
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.list(auditor, null, "not-a-uuid", null, null,
                null, null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("详情：source 非法 400；auditId 非法 400；不存在 404")
    void detailValidation() {
        assertThatThrownBy(() -> controller.detail(auditor, "GARBAGE", AUDIT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> controller.detail(auditor, "RECORD", "not-a-uuid"))
                .isInstanceOf(ResponseStatusException.class);

        port.detailResult = null;
        assertThatThrownBy(() -> controller.detail(auditor, "RECORD", AUDIT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("详情：payload JSON 反序列化为 JsonNode，完整保留信封字段")
    void detailDeserializesPayload() {
        String json = "{\"auditId\":\"" + AUDIT_ID + "\",\"result\":{\"finalAnswer\":\"完整回答\"},"
                + "\"chunks\":[{\"seq\":1,\"text\":\"分片\"}]}";
        port.detailResult = new Detail("RECORD", AUDIT_ID, TRACE, TENANT, "TENANT", "u-1",
                "/api/agent/chat/stream", "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z",
                1000L, "SUCCEEDED", "POST", 200, 1L, null, json);

        ResponseEntity<ChatAuditRecordController.AuditDetail> resp =
                controller.detail(auditor, "record", AUDIT_ID);

        assertThat(resp.getBody().payload().path("result").path("finalAnswer").asText())
                .isEqualTo("完整回答");
        assertThat(resp.getBody().payload().path("chunks").get(0).path("text").asText())
                .isEqualTo("分片");
        assertThat(port.lastDetailSource).isEqualTo(Source.RECORD);
    }

    @Test
    @DisplayName("fail-closed：运维通道未接入的异常原样冒泡，绝不吞成空列表")
    void opsUnavailableBubblesUp() {
        port.listThrows = new PlatformOpsUnavailableException(
                "agent.platform-ops.datasource.enabled", "平台运维角色未接入");
        assertThatThrownBy(() -> controller.list(auditor, null, null, null, null, null, null, null, null))
                .isInstanceOf(PlatformOpsUnavailableException.class);
    }

    private static Summary summary() {
        return new Summary(AUDIT_ID, TRACE, "RECORD", TENANT, "TENANT", "u-1",
                "/api/agent/chat/stream", "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z",
                1000L, "SUCCEEDED", "POST", 200, "问题预览", null);
    }

    /** 记录最后一次调用参数的 Fake 端口。 */
    private static final class FakeQueryPort implements ChatAuditRecordQueryPort {
        Page<Summary> next = Page.of(List.of(), null);
        Detail detailResult;
        RuntimeException listThrows;
        Query lastQuery;
        Source lastDetailSource;

        @Override
        public @NonNull Page<Summary> list(@NonNull Query query) {
            if (listThrows != null) throw listThrows;
            this.lastQuery = query;
            return next;
        }

        @Override
        public Detail detail(@NonNull Source source, @NonNull String auditId) {
            this.lastDetailSource = source;
            return detailResult;
        }
    }
}

package cn.studykid.multiagent.infrastructure.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Redaction;

/**
 * 审计脱敏的行为验证。
 *
 * <p><b>为什么值得单独测</b>：脱敏是「可以存原文」这一决策的唯一安全前提。
 * 它一旦失效，表现是<b>页面正常、审计完整性更好</b>——没有任何告警，
 * 只是凭据被复制进了审计库与目录文件。反向验证：把强制黑名单删掉，本测试立刻红。
 */
class AgentChatAuditRedactionTest {

    private final AuditRedactor redactor = new AuditRedactor(
            new Redaction("***", List.of("password", "token", "phone")));

    @Test
    @DisplayName("⛔ 强制黑名单：即使前端没配，Authorization/Cookie 也必须掩码")
    void forcedHeaderBlacklistCannotBeDisabled() {
        Map<String, String> out = redactor.redactHeaders(Map.of(
                "Authorization", "Bearer sk-live-123",
                "Cookie", "session=abc",
                "X-Api-Key", "k-1",
                "X-Request-Id", "req-1"));

        assertThat(out)
                .containsEntry("Authorization", "***")
                .containsEntry("Cookie", "***")
                .containsEntry("X-Api-Key", "***")
                // 非敏感头保留原值：审计要能定位请求
                .containsEntry("X-Request-Id", "req-1");
    }

    @Test
    @DisplayName("递归脱敏：嵌套对象 / 数组里的敏感字段都替换为掩码")
    void recursiveSensitiveFieldsAreMasked() {
        Map<String, Object> payload = Map.of(
                "user", Map.of("phone", "13800000000", "name", "小明"),
                "list", List.of(Map.of("token", "t-1"), Map.of("nested", Map.of("password", "p"))),
                "message", "你好");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) redactor.redact(payload);

        assertThat(String.valueOf(out)).doesNotContain("13800000000", "t-1", "\"p\"");
        assertThat(String.valueOf(out)).contains("小明", "你好");
    }

    @Test
    @DisplayName("JSON 文本脱敏：命中字段被掩码，非 JSON 原文原样返回")
    void jsonTextRedaction() {
        String masked = redactor.redactJsonText(
                "{\"message\":\"求助\",\"meta\":{\"phone\":\"13800000000\"}}");
        assertThat(masked).doesNotContain("13800000000").contains("求助");

        // 非 JSON：不存在「字段名」，原样返回（凭据靠 Header 黑名单兜住）
        assertThat(redactor.redactJsonText("not-json")).isEqualTo("not-json");
    }
}

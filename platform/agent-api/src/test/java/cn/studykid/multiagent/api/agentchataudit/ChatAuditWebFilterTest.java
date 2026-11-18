package cn.studykid.multiagent.api.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对话审计过滤器的纯函数部分（端点匹配 / 域判定 / 终态映射）。
 *
 * <p><b>为什么这些也要测</b>：它们决定了「哪些请求被审计」与「被记成什么终态」。
 * 端点匹配一旦写成无边界前缀，{@code /api/runsXYZ} 这类越界路径会被误记；
 * 终态映射一旦与 DDL 的 CHECK 白名单不一致，写入会被数据库拒绝，
 * 而 sink 的失败是 fail-open 吞掉的——表现就是「审计偶尔少几条，没人知道为什么」。
 */
class ChatAuditWebFilterTest {

    private static final List<String> ENDPOINTS = List.of(
            "/api/agent/chat/stream", "/api/knowledge/agent/chat/stream",
            "/api/chat/stream", "/api/runs", "/api/v1/retrieval");

    @Test
    @DisplayName("端点匹配：精确命中与子路径命中")
    void matchesExactAndNested() {
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/agent/chat/stream"))
                .isEqualTo("/api/agent/chat/stream");
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/runs/r-1"))
                .isEqualTo("/api/runs");
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/v1/retrieval/search"))
                .isEqualTo("/api/v1/retrieval");
    }

    @Test
    @DisplayName("⛔ 边界：/api/runsXYZ 不得命中 /api/runs")
    void doesNotMatchPrefixWithoutBoundary() {
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/runsXYZ")).isNull();
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/agent/chat")).isNull();
        assertThat(ChatAuditWebFilter.matchEndpoint(ENDPOINTS, "/api/health")).isNull();
    }

    @Test
    @DisplayName("域判定：knowledge 前缀为租户链，其余为平台链")
    void domainByPath() {
        assertThat(ChatAuditWebFilter.domainOf("/api/knowledge/agent/chat/stream"))
                .isEqualTo("TENANT");
        assertThat(ChatAuditWebFilter.domainOf("/api/agent/chat/stream")).isEqualTo("PLATFORM");
        assertThat(ChatAuditWebFilter.domainOf("/api/chat/stream")).isEqualTo("PLATFORM");
    }

    @Test
    @DisplayName("终态映射必须落在 DDL CHECK 白名单内")
    void terminalStatesAreWithinWhitelist() {
        assertThat(ChatAuditWebFilter.terminalOf(200, false)).isEqualTo("SUCCEEDED");
        assertThat(ChatAuditWebFilter.terminalOf(400, false)).isEqualTo("REJECTED");
        assertThat(ChatAuditWebFilter.terminalOf(429, false)).isEqualTo("REJECTED");
        assertThat(ChatAuditWebFilter.terminalOf(500, false)).isEqualTo("FAILED");
        assertThat(ChatAuditWebFilter.terminalOf(200, true)).isEqualTo("FAILED");

        List<String> whitelist = List.of("SUCCEEDED", "FAILED", "TIMED_OUT",
                "CANCELLED", "CLIENT_DISCONNECTED", "REJECTED");
        for (int status : new int[] {200, 204, 400, 401, 403, 404, 429, 500, 503}) {
            assertThat(whitelist)
                    .as("状态码 %d 的终态必须在 chat_audit_record.terminal_state 的 CHECK 白名单内",
                            status)
                    .contains(ChatAuditWebFilter.terminalOf(status, false));
        }
    }
}

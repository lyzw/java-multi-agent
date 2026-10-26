package cn.studykid.multiagent.infrastructure.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.http.HttpToolException;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 工具失败的**语义分类**（第三十七批 · C37-1 · P0-D03 §4 红线 7）。
 *
 * <p>⚠️ <b>「确定失败」与「结果未知」的区别是本平台最容易出现的 Sev-0 缺陷</b>：
 * 把「可能已生效」误报成「确定失败」而让上游重试，就是重复副作用的制造机。
 */
class HttpBackedToolHandlerFailureSemanticsTest {

    private static ToolExecutionException translate(HttpToolException.Kind kind, String method) {
        return HttpBackedToolHandler.translate(
                HttpToolException.of(kind, "boom", null), method, "tool-1");
    }

    @Test
    @DisplayName("✅ 请求没发出去（出站拦截 / 配置非法 / 连接失败）→ 确定失败，可重试")
    void notSentIsDefinitelyFailed() {
        for (HttpToolException.Kind kind : new HttpToolException.Kind[]{
                HttpToolException.Kind.SSRF_BLOCKED,
                HttpToolException.Kind.CONFIG_INVALID,
                HttpToolException.Kind.CONNECT_FAILED}) {
            assertThat(translate(kind, "POST").isResultUnknown())
                    .as("kind=%s：请求没发出去，必须是可重试的确定失败", kind)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("⛔ 请求已发出且方法有副作用（POST/PUT/PATCH/DELETE）→ 结果未知，禁止盲目重试")
    void sentWithSideEffectIsUnknownResult() {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            for (HttpToolException.Kind kind : new HttpToolException.Kind[]{
                    HttpToolException.Kind.TIMEOUT,
                    HttpToolException.Kind.PROTOCOL_ERROR,
                    HttpToolException.Kind.RESPONSE_TOO_LARGE,
                    HttpToolException.Kind.TOO_MANY_REDIRECTS}) {
                assertThat(translate(kind, method).isResultUnknown())
                        .as("method=%s kind=%s：副作用可能已发生，必须进 UNKNOWN 对账",
                                method, kind)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("✅ 请求已发出但方法无副作用（GET/HEAD）→ 确定失败（没有副作用可对账）")
    void sentWithoutSideEffectIsDefinitelyFailed() {
        for (String method : new String[]{"GET", "HEAD"}) {
            for (HttpToolException.Kind kind : new HttpToolException.Kind[]{
                    HttpToolException.Kind.TIMEOUT,
                    HttpToolException.Kind.PROTOCOL_ERROR,
                    HttpToolException.Kind.RESPONSE_TOO_LARGE,
                    HttpToolException.Kind.TOO_MANY_REDIRECTS}) {
                assertThat(translate(kind, method).isResultUnknown())
                        .as("method=%s kind=%s：读操作没有副作用可对账，不该制造人工工单",
                                method, kind)
                        .isFalse();
            }
        }
    }
}

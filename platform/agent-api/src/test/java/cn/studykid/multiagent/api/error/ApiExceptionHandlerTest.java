package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import cn.studykid.multiagent.api.run.RunController.ErrorResponse;

/**
 * 全局异常处理器的状态码语义（P4-D09 同族缺陷的第 3 例 · 2026-10-23 全栈 E2E 实测）。
 *
 * <p><b>本类要防的回归</b>：Spring 6+ 起，路径未映射到任何 {@code @RequestMapping}
 * 时抛 {@link NoResourceFoundException}，Spring 默认映射为 <b>404</b>。
 * 但本类的兜底 {@code @ExceptionHandler(Exception.class)} <b>优先于</b>
 * Spring 内置的 {@code DefaultHandlerExceptionResolver}，
 * 于是未映射路径被<b>吞成 500</b>。
 *
 * <p>危害：客户端无法区分「路径写错」（4xx，重试无用）
 * 与「服务端故障」（5xx，可重试），会做出<b>完全相反</b>的重试决策；
 * 同时把用户笔误计入服务端错误率，淹没真实故障。
 *
 * <p>⚠️ <b>为什么本类只做「处理器级」断言</b>：
 * {@code @ExceptionHandler} 的<b>选路优先级</b>（最具体匹配优先于 {@code Exception.class}）
 * 属于 Spring 框架行为，处理器自身的单元测试证明不了。
 * 该优先级由 {@code NotFoundMappingIT}（真实 HTTP + 真实过滤链）证明。
 * 本类证明的是<b>处理器自身的状态码与文案</b>——
 * 若有人把 404 改回 500，这里会红。
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("✅ 未映射路径 → 404（不是 500）：客户端数据错误不得被记成服务端故障")
    void unmappedPathIsNotFoundNotServerError() {
        ResponseEntity<ErrorResponse> resp = handler.handleNoResourceFound(
                new NoResourceFoundException(
                        HttpMethod.GET, "/api/nonexistent", "No static resource api/nonexistent"));

        assertThat(resp.getStatusCode())
                .as("⛔ 未映射路径是客户端数据错误，必须是 404；报 500 会诱导客户端无限重试")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getStatusCode().is5xxServerError())
                .as("⛔ 绝不可为 5xx")
                .isFalse();

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code())
                .as("404 归一化 code")
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("⛔ 404 响应不得回显请求路径原文（可能含客户端数据）")
    void notFoundBodyDoesNotEchoRequestPath() {
        ResponseEntity<ErrorResponse> resp = handler.handleNoResourceFound(
                new NoResourceFoundException(
                        HttpMethod.GET, "/api/secret-tenant-42/internal",
                        "No static resource api/secret-tenant-42/internal"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message())
                .as("⛔ 错误消息不得回显路径原文")
                .doesNotContain("/api/secret-tenant-42/internal")
                .doesNotContain("secret-tenant-42");
        assertThat(resp.getBody().code())
                .as("⛔ code 也不得含路径")
                .doesNotContain("secret-tenant-42");
    }

    @Test
    @DisplayName("✅ 未映射路径与「资源不存在」同码：两者都是 404/NOT_FOUND")
    void unmappedPathAndMissingResourceShareSameCode() {
        ErrorResponse unmapped = handler.handleNoResourceFound(
                new NoResourceFoundException(
                        HttpMethod.GET, "/api/nope", "No static resource api/nope"))
                .getBody();
        ErrorResponse declared = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND))
                .getBody();

        assertThat(unmapped).isNotNull();
        assertThat(declared).isNotNull();
        assertThat(unmapped.code())
                .as("⛔「路径不存在」与「资源不存在」必须同码，否则客户端要维护两套语义")
                .isEqualTo(declared.code());
        assertThat(unmapped.message()).isEqualTo(declared.message());
    }

    @Test
    @DisplayName("⛔ 兜底处理器仍为 500：本次修复不得意外放宽错误分类")
    void catchAllStillReturnsServerError() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(resp.getStatusCode())
                .as("⛔ 真故障必须是 5xx —— 与「拒绝伪装成故障」原则同等重要")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(resp.getBody().message())
                .as("⛔ 兜底消息不得透出异常详情")
                .doesNotContain("boom");
    }

    @Test
    @DisplayName("⛔ 兜底处理器不得回显异常详情（表名/SQL/供应商类型）")
    void catchAllDoesNotLeakExceptionDetails() {
        ResponseEntity<ErrorResponse> resp = handler.handleUnexpected(
                new IllegalStateException("relation \"principal_secret\" does not exist"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message())
                .as("⛔ 内部结构不得出现在返回给客户端的消息里")
                .doesNotContain("principal_secret")
                .doesNotContain("relation");
    }
}

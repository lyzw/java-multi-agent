package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 回归：SSE 端点（produces=text/event-stream）在「建连前失败」时必须返回
 * 结构化 JSON 错误（{@code {"code","message"}}），而不是被 Spring 的内容协商
 * 卡成 {@link HttpMediaTypeNotAcceptableException}（WARN: Failure in @ExceptionHandler）。
 *
 * <p><b>根因</b>：SSE 客户端固定发 {@code Accept: text/event-stream}，而全局异常处理器
 * 返回 {@code ResponseEntity<ErrorResponse>}（JSON）。若直接交给 Spring 消息转换器，
 * 因 Accept 与 producible 媒体类型不兼容会抛 {@link HttpMediaTypeNotAcceptableException}，
 * 导致异常处理器自身失败、客户端拿不到结构化错误。
 * 修复后处理器对 SSE 请求直写 application/json，绕过内容协商。
 *
 * <p><b>守卫</b>：撤掉 {@code ApiExceptionHandler#writeSseSafeError} 的直写分支，
 * 本测试会立刻由「400 + JSON」退化为「406 / 异常处理器自身失败」，从而转红。
 */
@DisplayName("SSE 端点错误路径：建连前失败必须返回结构化 JSON（不得冒泡 406）")
class AgentChatSseErrorHandlingTest {

    @RestController
    static class SseController {
        @PostMapping(value = "/api/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.springframework.http.ResponseEntity<SseEmitter> stream(
                @RequestBody java.util.Map<String, Object> body) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new SseController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("SSE + Accept text/event-stream：建连前 400 必须返回结构化 JSON，且不冒泡 406")
    void ssePreConnectionErrorReturnsJson() throws Exception {
        MvcResult r = mvc().perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"\"}"))
                .andReturn();

        assertThat(r.getResponse().getStatus())
                .as("⛔ 建连前失败必须是 400，不得因内容协商失败退化为 406")
                .isEqualTo(400);
        assertThat(r.getResponse().getContentType())
                .as("⛔ 必须是 application/json，否则 SSE 客户端（res.text + parseJson）拿不到结构化错误")
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(r.getResolvedException())
                .as("⛔ 不得冒泡成 HttpMediaTypeNotAcceptableException（异常处理器自身失败）")
                .isNotInstanceOf(HttpMediaTypeNotAcceptableException.class);

        String body = r.getResponse().getContentAsString();
        assertThat(body)
                .as("⛔ 错误体必须是结构化的 JSON（code/message），客户端据此归一化")
                .contains("\"code\":\"INVALID_REQUEST\"")
                .contains("\"message\"");
    }

    @Test
    @DisplayName("SSE + Accept */*：错误响应同样为结构化 JSON（兼容宽松客户端）")
    void sseWithAnyAcceptReturnsJson() throws Exception {
        MvcResult r = mvc().perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.ALL)
                        .content("{\"message\":\"\"}"))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(r.getResolvedException()).isNotInstanceOf(HttpMediaTypeNotAcceptableException.class);
    }
}

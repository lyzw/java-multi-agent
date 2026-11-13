package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 端到端证明：<b>真实 HTTP 请求发生时，全局异常处理器的日志行带上了接口地址与参数</b>。
 *
 * <p><b>为什么不能只靠 {@code RequestLogContextTest}</b>：那个用例证明的是
 * 「提取器能从请求里拿到东西」，证明不了「异常处理器真的把它写进了日志」——
 * 中间隔着一次「处理器有没有调用提取器」的连线，撤掉连线它照样绿。
 * 本类走真实 MockMvc 派发，把这条连线一起验掉。
 *
 * <p><b>反向验证</b>：
 * <ul>
 *   <li>把 {@code ApiExceptionHandler} 里任一处理器的 {@code requestContext()} 去掉
 *       → 第一个用例红（日志里不再有 uri）；</li>
 *   <li>把 {@link RequestLogContext} 的掩码分支去掉 → 第二个用例红
 *       （口令明文进了日志）。</li>
 * </ul>
 */
@DisplayName("ApiExceptionHandler · 日志带接口地址与参数")
class ApiExceptionHandlerRequestLoggingTest {

    /** 只为触发异常处理器存在的最小控制器。 */
    @RestController
    static class FailingController {

        @GetMapping("/api/v1/test/boom")
        public String boom(@RequestParam("tenantId") String tenantId) {
            throw new IllegalArgumentException("参数不合法: " + tenantId);
        }

        @GetMapping("/api/v1/test/explode")
        public String explode() {
            throw new IllegalStateException("底层炸了");
        }

        /** JSON 载荷全在 body 里：不打 body 等于什么都没打。 */
        @PostMapping("/api/v1/test/boom-json")
        public String boomJson(@RequestBody Map<String, Object> payload) {
            throw new IllegalArgumentException("参数不合法: " + payload.get("tenantId"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            // 与生产同构：过滤器把 JSON 请求包装成「body 可回读」的请求。
            .addFilters(new RequestBodyCachingConfiguration.RequestBodyCachingFilter())
            .build();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
        if (appender != null) {
            appender.stop();
        }
    }

    private String loggedText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("✅ 异常日志行带上方法、接口地址与参数")
    void exceptionLogCarriesRequestContext() throws Exception {
        mvc.perform(get("/api/v1/test/boom").param("tenantId", "t-1").param("page", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        String logs = loggedText();
        assertThat(logs)
                .as("⛔ 没有接口地址与参数，排障就得回头翻前端代码猜请求长什么样")
                .contains("method=GET")
                .contains("uri=/api/v1/test/boom")
                .contains("tenantId=t-1")
                .contains("page=2");
    }

    @Test
    @DisplayName("⛔ 口令类参数在日志里被掩码：响应体与日志都不泄密")
    void sensitiveParamsAreMaskedInLogs() throws Exception {
        mvc.perform(get("/api/v1/test/boom")
                        .param("tenantId", "t-1")
                        .param("password", "hunter2"))
                .andExpect(status().isBadRequest())
                // 响应体仍是归一化文案：上下文只进日志，不进响应
                .andExpect(jsonPath("$.message").value("请求参数不合法"));

        String logs = loggedText();
        assertThat(logs)
                .as("⛔ 口令明文不得进日志")
                .doesNotContain("hunter2");
        assertThat(logs).contains("password=***");
    }

    @Test
    @DisplayName("⛔ 响应体不得回显请求上下文：日志归日志，响应归响应")
    void responseBodyNeverEchoesRequestContext() throws Exception {
        String body = mvc.perform(get("/api/v1/test/boom")
                        .param("tenantId", "t-1")
                        .param("password", "hunter2"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("⛔ 响应体只给归一化 code 与文案")
                .doesNotContain("/api/v1/test/boom")
                .doesNotContain("t-1")
                .doesNotContain("hunter2");
    }

    @Test
    @DisplayName("⛔ 兜底处理器仍打印堆栈：补了请求上下文不等于把异常详情挤掉")
    void catchAllStillAttachesStackTrace() throws Exception {
        mvc.perform(get("/api/v1/test/explode").param("tenantId", "t-1"))
                .andExpect(status().isInternalServerError());

        boolean withThrowable = appender.list.stream()
                .anyMatch(event -> event.getThrowableProxy() != null
                        && event.getFormattedMessage().contains("uri=/api/v1/test/explode"));
        assertThat(withThrowable)
                .as("⛔ slf4j 在占位符用完后把最后一个 Throwable 当异常体；写法一变就会静默丢堆栈")
                .isTrue();
    }

    @Test
    @DisplayName("✅ JSON 请求的日志行带上 body：payload 全在 body 里，只打参数等于没打")
    void jsonRequestLogCarriesBody() throws Exception {
        mvc.perform(post("/api/v1/test/boom-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t-1\",\"question\":\"今天讲什么\"}"))
                .andExpect(status().isBadRequest());

        String logs = loggedText();
        assertThat(logs)
                .as("⛔ 看不到 body，排障就只能回头翻前端代码猜这次到底传了什么")
                .contains("body=")
                .contains("\"tenantId\":\"t-1\"")
                .contains("question");
    }

    @Test
    @DisplayName("⛔ body 里的口令同样掩码：打了 body 不等于放弃脱敏纪律")
    void jsonBodySensitiveFieldsAreMasked() throws Exception {
        mvc.perform(post("/api/v1/test/boom-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t-1\",\"password\":\"hunter2\"}"))
                .andExpect(status().isBadRequest());

        String logs = loggedText();
        assertThat(logs)
                .as("⛔ body 里的口令明文不得进日志")
                .doesNotContain("hunter2");
        assertThat(logs).contains("\"password\":\"***\"");
    }
}

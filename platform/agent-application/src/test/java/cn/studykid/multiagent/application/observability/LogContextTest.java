package cn.studykid.multiagent.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.observability.LogContext.Snapshot;

/**
 * 日志上下文（MDC）的三条不变量：<b>净化</b>、<b>不丢</b>、<b>不串</b>。
 *
 * <p><b>净化</b>：traceId 来自请求头，属于攻击者可控输入，
 * 换行/控制字符进日志即日志注入（伪造日志行、污染终端）。
 *
 * <p><b>不丢</b>：异步任务必须带着提交线程的上下文运行，
 * 否则子线程日志「一切正常，就是少了字段」——排障时最迷惑的形态。
 *
 * <p><b>不串</b>：线程池会复用线程，任务结束后必须<b>还原</b>执行线程原有的上下文
 * （不是清空、更不是留着），否则下一个请求会读到上一个请求的租户。
 */
@DisplayName("LogContext · traceId/租户/主体三要素")
class LogContextTest {

    @AfterEach
    void tearDown() {
        LogContext.clear();
    }

    @Test
    @DisplayName("生成的 traceId 是 32 位小写十六进制且互不相同")
    void newTraceIdIsHex32() {
        String first = LogContext.newTraceId();
        assertThat(first).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(first).isNotEqualTo(LogContext.newTraceId());
    }

    @Test
    @DisplayName("⚠️ CR/LF 等控制字符被丢弃：请求头不得成为日志注入入口")
    void sanitizeDropsControlCharacters() {
        assertThat(LogContext.sanitize("abc\r\nFAKE 500 ERROR")).isEqualTo("abcFAKE500ERROR");
        // ANSI 转义序列：引导符 ESC 与方括号被丢，剩下的只是无害普通字符，
        // 不再有任何能改变终端状态的控制序列。
        assertThat(LogContext.sanitize("\u001B[31mred\u001B[0m")).isEqualTo("31mred0m");
    }

    @Test
    @DisplayName("净化保留合法标识字符，空/全非法一律落占位值，超长截断")
    void sanitizeKeepsLegalAndBoundsLength() {
        assertThat(LogContext.sanitize("0b42a9c1-7f3e-4c1a-9b2d-1e5f3a6b8c01"))
                .isEqualTo("0b42a9c1-7f3e-4c1a-9b2d-1e5f3a6b8c01");
        assertThat(LogContext.sanitize("user:42@corp")).isEqualTo("user:42@corp");
        assertThat(LogContext.sanitize(null)).isEqualTo(LogContext.NONE);
        assertThat(LogContext.sanitize("   ")).isEqualTo(LogContext.NONE);
        assertThat(LogContext.sanitize("///")).isEqualTo(LogContext.NONE);
        assertThat(LogContext.sanitize("x".repeat(200))).hasSize(64);
    }

    @Test
    @DisplayName("三个键始终有值：未设置身份时是占位符，不是缺失")
    void identityFallsBackToPlaceholder() {
        LogContext.clearIdentity();
        Map<String, String> view = LogContext.snapshot().asMap();
        assertThat(view).containsEntry(LogContext.TENANT_ID, LogContext.NONE)
                .containsEntry(LogContext.PRINCIPAL_ID, LogContext.NONE);
    }

    @Test
    @DisplayName("setTraceId(null) 保留已有链路号；无有效值时就地生成")
    void setTraceIdKeepsExistingOrGenerates() {
        LogContext.setTraceId("upstream-trace");
        LogContext.setTraceId(null);
        assertThat(LogContext.traceId()).isEqualTo("upstream-trace");

        LogContext.clear();
        LogContext.setTraceId(null);
        assertThat(LogContext.traceId()).hasSize(32);

        // 非法头值等价于「没有」：不能把上游的垃圾原样留在链路上。
        LogContext.setTraceId("\r\n");
        assertThat(LogContext.traceId()).hasSize(32);
    }

    @Test
    @DisplayName("ensureTraceId 交出的值与写进 MDC 的值是同一个")
    void ensureTraceIdWritesBackToMdc() {
        LogContext.clear();
        String handedOver = LogContext.ensureTraceId();
        assertThat(handedOver).hasSize(32);
        assertThat(LogContext.traceId()).isEqualTo(handedOver);
    }

    @Test
    @DisplayName("push 关闭后恢复进入前的上下文（嵌套不是清空）")
    void pushRestoresPreviousSnapshot() {
        LogContext.setTraceId("outer");
        LogContext.setIdentity("tenant-outer", "user-outer");

        try (LogContext.Scope ignored = LogContext.push(
                new Snapshot("inner", "tenant-inner", "user-inner"))) {
            assertThat(LogContext.snapshot().asMap()).containsEntry(LogContext.TENANT_ID, "tenant-inner");
        }

        assertThat(LogContext.snapshot().asMap())
                .containsEntry(LogContext.TRACE_ID, "outer")
                .containsEntry(LogContext.TENANT_ID, "tenant-outer")
                .containsEntry(LogContext.PRINCIPAL_ID, "user-outer");
    }

    @Test
    @DisplayName("⚠️ wrap 把提交线程的上下文带进池线程，且执行完还原池线程自己的上下文")
    void wrapPropagatesAndRestoresOnPoolThread() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // 池线程先有自己的上下文（模拟「上一个请求遗留」）
            pool.submit(() -> LogContext.setIdentity("tenant-own", "user-own")).get();

            LogContext.setTraceId("parent");
            LogContext.setIdentity("tenant-parent", "user-parent");

            AtomicReference<Map<String, String>> duringTask = new AtomicReference<>();
            pool.submit(LogContext.wrap(
                    () -> duringTask.set(LogContext.snapshot().asMap()))).get();
            assertThat(duringTask.get())
                    .containsEntry(LogContext.TRACE_ID, "parent")
                    .containsEntry(LogContext.TENANT_ID, "tenant-parent");

            AtomicReference<Map<String, String>> afterTask = new AtomicReference<>();
            pool.submit(() -> afterTask.set(LogContext.snapshot().asMap())).get();
            assertThat(afterTask.get())
                    .as("任务结束必须还原执行线程原有上下文，否则线程复用即串号")
                    .containsEntry(LogContext.TENANT_ID, "tenant-own")
                    .containsEntry(LogContext.PRINCIPAL_ID, "user-own");
        }
    }

    @Test
    @DisplayName("wrap 的 Callable 版本同样传递上下文并回传结果")
    void wrapCallablePropagatesContext() throws Exception {
        LogContext.setTraceId("parent");
        LogContext.setIdentity("tenant-parent", "user-parent");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            String seen = pool.submit(LogContext.wrap(
                    () -> LogContext.traceId() + "/" + LogContext.snapshot().tenantId())).get();
            assertThat(seen).isEqualTo("parent/tenant-parent");
        }
    }
}

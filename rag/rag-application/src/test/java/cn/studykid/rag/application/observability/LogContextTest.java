package cn.studykid.rag.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.observability.LogContext.Snapshot;

/**
 * RAG 侧 {@link LogContext} 的<b>契约测试</b>。
 *
 * <p><b>为什么两边各测一份</b：rag 与主平台是两份独立实现（不同 reactor、无代码依赖），
 * 一旦有一处行为漂移，跨进程日志串联就会<b>静默失效</b>——
 * 症状是「同一个 traceId 查不到对端的日志」，而不是任何报错。
 * 本测试锁定的正是跨进程必须一致的四条：键名、占位值、净化规则、traceId 形态。
 *
 * <p>与主平台 {@code LogContextTest} 的断言逐条对齐，改一边就要改另一边。
 */
@DisplayName("rag LogContext · 与主平台同契约")
class LogContextTest {

    @AfterEach
    void tearDown() {
        LogContext.clear();
    }

    @Test
    @DisplayName("MDC 键名与占位值和主平台一致（跨进程检索的前提）")
    void keysMatchPlatformContract() {
        assertThat(LogContext.TRACE_ID).isEqualTo("traceId");
        assertThat(LogContext.TENANT_ID).isEqualTo("tenantId");
        assertThat(LogContext.PRINCIPAL_ID).isEqualTo("principalId");
        assertThat(LogContext.NONE).isEqualTo("-");
        assertThat(LogContext.SYSTEM).isEqualTo("system");
    }

    @Test
    @DisplayName("生成的 traceId 是 32 位小写十六进制")
    void newTraceIdIsHex32() {
        assertThat(LogContext.newTraceId()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("净化规则与主平台一致：X-Tenant-Id 头不可注入日志")
    void sanitizeMatchesPlatformContract() {
        assertThat(LogContext.sanitize("abc\r\nFAKE 500 ERROR")).isEqualTo("abcFAKE500ERROR");
        assertThat(LogContext.sanitize("\u001B[31mred\u001B[0m")).isEqualTo("31mred0m");
        assertThat(LogContext.sanitize("0b42a9c1-7f3e-4c1a-9b2d-1e5f3a6b8c01"))
                .isEqualTo("0b42a9c1-7f3e-4c1a-9b2d-1e5f3a6b8c01");
        assertThat(LogContext.sanitize("///")).isEqualTo(LogContext.NONE);
        assertThat(LogContext.sanitize("x".repeat(200))).hasSize(64);
    }

    @Test
    @DisplayName("setTraceId(null) 保留已有链路号；非法值等价于「没有」")
    void setTraceIdKeepsExistingOrGenerates() {
        LogContext.setTraceId("upstream-trace");
        LogContext.setTraceId(null);
        assertThat(LogContext.traceId()).isEqualTo("upstream-trace");

        LogContext.clear();
        LogContext.setTraceId("\r\n");
        assertThat(LogContext.traceId()).hasSize(32);
    }

    @Test
    @DisplayName("push 关闭后恢复进入前的上下文（嵌套不是清空）")
    void pushRestoresPreviousSnapshot() {
        LogContext.setTraceId("outer");
        LogContext.setIdentity("tenant-outer", "user-outer");

        try (LogContext.Scope ignored = LogContext.push(
                new Snapshot("inner", "tenant-inner", "user-inner"))) {
            assertThat(LogContext.traceId()).isEqualTo("inner");
        }

        assertThat(LogContext.snapshot().asMap())
                .containsEntry(LogContext.TRACE_ID, "outer")
                .containsEntry(LogContext.TENANT_ID, "tenant-outer");
    }

    @Test
    @DisplayName("⚠️ wrap 传递上下文并在池线程上还原（线程复用不串号）")
    void wrapPropagatesAndRestoresOnPoolThread() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
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
                    .containsEntry(LogContext.TENANT_ID, "tenant-own")
                    .containsEntry(LogContext.PRINCIPAL_ID, "user-own");
        }
    }
}

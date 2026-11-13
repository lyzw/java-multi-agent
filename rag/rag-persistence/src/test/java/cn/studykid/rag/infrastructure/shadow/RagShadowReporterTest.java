package cn.studykid.rag.infrastructure.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 影子观测基础设施单元测试（无 DB）。
 *
 * <p>覆盖：指标计数、采样决策、P1（仅记录）/ P2（阻断）翻转、静态门面注册/注销。
 */
class RagShadowReporterTest {

    @AfterEach
    void tearDown() {
        // 防止静态门面状态泄漏到其它测试
        RagShadow.unregister();
    }

    @Test
    void metricsIncrementOnSampleAndBind() {
        InMemoryRagShadowMetrics m = new InMemoryRagShadowMetrics();
        RagShadowReporter r = new RagShadowReporter(m, false, null);

        r.recordShadowSample("retrieval");
        r.recordShadowSample("retrieval");
        r.reportRlsBind("rag_session_scope");

        assertEquals(2, m.shadowSamples());
        assertEquals(1, m.rlsBindCalls());
        assertEquals(0, m.mismatches());
        assertEquals(0, m.taintViolations());
        // rlsDependent 来自 RlsDependencyProbe 静态预期（M4 前的基准值 30）
        assertEquals(30, m.rlsDependent());
    }

    @Test
    void mismatchOnlyRecordsInP1NotBlocking() {
        InMemoryRagShadowMetrics m = new InMemoryRagShadowMetrics();
        RagShadowReporter r = new RagShadowReporter(m, false, null);

        r.reportMismatch("retrieval", "scope != query");
        // P1：只计数、不抛
        assertEquals(1, m.mismatches());
        assertEquals("ragShadow[samples=0 rlsBind=0 mismatches=1 taint=0 rlsDependent=30]",
                m.snapshot());
    }

    @Test
    void mismatchBlocksInP2() {
        InMemoryRagShadowMetrics m = new InMemoryRagShadowMetrics();
        RagShadowReporter r = new RagShadowReporter(m, true, null);

        RagShadowViolationException ex = assertThrows(RagShadowViolationException.class,
                () -> r.reportMismatch("retrieval", "scope != query"));
        assertTrue(ex.getMessage().contains("RAG_SHADOW_MISMATCH"));
        assertEquals(1, m.mismatches());
    }

    @Test
    void taintViolationBlocksInP2() {
        InMemoryRagShadowMetrics m = new InMemoryRagShadowMetrics();
        RagShadowReporter r = new RagShadowReporter(m, true, null);

        RagShadowViolationException ex = assertThrows(RagShadowViolationException.class,
                () -> r.reportTaintViolation("corpus", "cross-tenant-uuid"));
        assertTrue(ex.getMessage().contains("RAG_SHADOW_TAINT"));
        assertEquals(1, m.taintViolations());
    }

    @Test
    void samplingRateControlsEvaluation() {
        assertTrue(new RagShadowSampleRatePolicy(1.0).shouldSample("retrieval", "k"));
        assertFalse(new RagShadowSampleRatePolicy(0.0).shouldSample("retrieval", "k"));
        // rate=0.1 下，确定性哈希决定是否采；断言同一键结果稳定
        RagShadowSampleRatePolicy p = new RagShadowSampleRatePolicy(0.1);
        boolean first = p.shouldSample("retrieval", "stable-key");
        assertEquals(first, p.shouldSample("retrieval", "stable-key"));
    }

    @Test
    void facadeIsNoOpWhenUnregistered() {
        RagShadow.unregister();
        assertFalse(RagShadow.isEnabled());
        // 不应抛异常，也不应改动任何指标（无采集器）
        RagShadow.collect("retrieval");
        RagShadow.recordRlsBind("rag_session_scope");
        RagShadow.reportMismatch("retrieval", "x");
        RagShadow.reportTaintViolation("corpus", "y");
    }

    @Test
    void facadeDelegatesWhenRegistered() {
        InMemoryRagShadowMetrics m = new InMemoryRagShadowMetrics();
        RagShadowReporter reporter = new RagShadowReporter(m, false, null);
        RagShadowCollector collector = new RagShadowCollector(m, reporter);
        collector.attach();

        assertTrue(RagShadow.isEnabled());
        assertSame(collector, RagShadow.current());

        RagShadow.collect("retrieval");
        RagShadow.recordRlsBind("rag_session_scope");

        assertEquals(1, m.shadowSamples());
        assertEquals(1, m.rlsBindCalls());

        collector.destroy();
        assertFalse(RagShadow.isEnabled());
        RagShadow.collect("retrieval"); // 注销后 no-op
        assertEquals(1, m.shadowSamples());
    }
}

package cn.studykid.multiagent.application.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.memory.MemoryEntry;
import cn.studykid.multiagent.ports.persistence.MemoryPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;

/**
 * 记忆整合（P1）。
 *
 * <p>⚠️ 锁定三条"不得"（每一条都对应一种事故形态）：
 * <ol>
 *   <li><b>不得</b>在非成功终态整合（失败过程不得污染长期记忆）；</li>
 *   <li><b>不得</b>因整合失败让 Run 失败（此时 Run 已提交，改状态即伪造结果）；</li>
 *   <li><b>不得</b>在没有学生主体时整合（没有可写的记忆行）。</li>
 * </ol>
 */
@DisplayName("P1 记忆整合服务")
class MemoryConsolidatorTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String STU = "stu_0123456789abcdef";
    private static final String RUN = "22222222-2222-2222-2222-222222222222";

    private static final class StubMemoryPort implements MemoryPort {
        int consolidateCalls;
        RuntimeException failure;
        int affected = 1;

        @Override
        public List<MemoryEntry> recall(String tenantId, String studentId, int k, int maxTokens) {
            return List.of();
        }

        @Override
        public int consolidate(String tenantId, String studentId, String runId) {
            consolidateCalls++;
            if (failure != null) {
                throw failure;
            }
            return affected;
        }

        @Override
        public int forget(String tenantId, String studentId) {
            return 0;
        }

        @Override
        public int countMemories(String tenantId, String studentId) {
            return 0;
        }
    }

    private static final class StubQueryPort implements ResultQueryPort {
        RunResult result;
        RuntimeException failure;

        @Override
        public RunResult findResult(String tenantId, String runId) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static ResultQueryPort.RunResult result(String state, boolean withWeakness) {
        List<ResultQueryPort.Weakness> weaknesses = withWeakness
                ? List.of(new ResultQueryPort.Weakness("一元二次方程", "概念不清",
                        new BigDecimal("0.80")))
                : List.of();
        return new ResultQueryPort.RunResult(
                RUN, state, Instant.parse("2026-03-01T00:00:00Z"), null, "讲解",
                weaknesses, List.of(),
                new ResultQueryPort.Reconciliation(false, List.of()));
    }

    private static MemoryConsolidator consolidator(StubMemoryPort mem, StubQueryPort query) {
        return new MemoryConsolidator(mem, query);
    }

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("成功的 Run + 有弱点 → 调用端口整合")
        void succeededRunConsolidates() {
            StubMemoryPort mem = new StubMemoryPort();
            StubQueryPort query = new StubQueryPort();
            query.result = result("SUCCEEDED", true);

            assertEquals(1, consolidator(mem, query).consolidate(TENANT, RUN, STU));
            assertEquals(1, mem.consolidateCalls);
        }
    }

    @Nested
    @DisplayName("跳过条件")
    class Skips {

        @Test
        @DisplayName("失败/取消的 Run → 不整合（失败过程不得沉淀为长期弱点）")
        void nonSucceededRunSkipped() {
            for (String state : List.of("FAILED", "CANCELLED", "TIMED_OUT", "RUNNING")) {
                StubMemoryPort mem = new StubMemoryPort();
                StubQueryPort query = new StubQueryPort();
                query.result = result(state, true);

                assertEquals(0, consolidator(mem, query).consolidate(TENANT, RUN, STU),
                        "state=" + state + " 不应整合");
                assertEquals(0, mem.consolidateCalls, "state=" + state + " 不应触达端口");
            }
        }

        @Test
        @DisplayName("没有弱点 → 不整合")
        void noWeaknessSkipped() {
            StubMemoryPort mem = new StubMemoryPort();
            StubQueryPort query = new StubQueryPort();
            query.result = result("SUCCEEDED", false);

            assertEquals(0, consolidator(mem, query).consolidate(TENANT, RUN, STU));
            assertEquals(0, mem.consolidateCalls);
        }

        @Test
        @DisplayName("查不到 Run 结果 → 不整合（不猜测）")
        void missingRunSkipped() {
            StubMemoryPort mem = new StubMemoryPort();
            StubQueryPort query = new StubQueryPort();
            query.result = null;

            assertEquals(0, consolidator(mem, query).consolidate(TENANT, RUN, STU));
            assertEquals(0, mem.consolidateCalls);
        }

        @Test
        @DisplayName("studentId 为空 → 不整合（没有可写的记忆行）")
        void blankStudentSkipped() {
            StubMemoryPort mem = new StubMemoryPort();
            StubQueryPort query = new StubQueryPort();
            query.result = result("SUCCEEDED", true);

            assertEquals(0, consolidator(mem, query).consolidate(TENANT, RUN, null));
            assertEquals(0, consolidator(mem, query).consolidate(TENANT, RUN, "  "));
            assertEquals(0, mem.consolidateCalls);
        }
    }

    @Nested
    @DisplayName("不得因整合失败让 Run 失败")
    class NeverThrows {

        @Test
        @DisplayName("端口抛异常 → 吞掉并返回 0")
        void portFailureSwallowed() {
            StubMemoryPort mem = new StubMemoryPort();
            mem.failure = new IllegalStateException("库连不上");
            StubQueryPort query = new StubQueryPort();
            query.result = result("SUCCEEDED", true);

            assertEquals(0, assertDoesNotThrow(
                    () -> consolidator(mem, query).consolidate(TENANT, RUN, STU)));
        }

        @Test
        @DisplayName("读结果抛异常 → 吞掉并返回 0")
        void queryFailureSwallowed() {
            StubMemoryPort mem = new StubMemoryPort();
            StubQueryPort query = new StubQueryPort();
            query.failure = new IllegalStateException("读超时");

            assertEquals(0, assertDoesNotThrow(
                    () -> consolidator(mem, query).consolidate(TENANT, RUN, STU)));
            assertEquals(0, mem.consolidateCalls);
        }
    }
}

package cn.studykid.multiagent.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标标签约束测试（P3-D10 / FR-017 / AT-OBS-002）。
 *
 * <p><b>核心命题</b>：*「指标<b>不含</b>用户/Run 等高基数标签」*。
 * 验收矩阵的证据类型是「<b>扫描报告</b>」——
 * 所以本类既有<b>行为</b>测试（M-2/M-3），也有<b>结构扫描</b>测试（M-6）。
 */
class AgentMetricsTest {

    private final AgentMetrics metrics = new AgentMetrics();

    // ── M-1：基本功能 ──────────────────────────────────────────────

    @Test
    @DisplayName("M-1 低基数标签正常记录与聚合")
    void lowCardinalityLabelsRecorded() {
        metrics.increment("run_completed_total", Map.of("run_state", "SUCCEEDED"));
        metrics.increment("run_completed_total", Map.of("run_state", "SUCCEEDED"));
        metrics.increment("run_completed_total", Map.of("run_state", "FAILED"));

        assertThat(metrics.valueOf("run_completed_total",
                Map.of("run_state", "SUCCEEDED"))).isEqualTo(2);
        assertThat(metrics.valueOf("run_completed_total",
                Map.of("run_state", "FAILED"))).isEqualTo(1);
        assertThat(metrics.snapshot()).hasSize(2);
    }

    @Test
    @DisplayName("M-1b 标签顺序不同 → 同一序列（不因书写顺序产生分裂序列）")
    void labelOrderDoesNotSplitSeries() {
        metrics.increment("tool_invoked_total",
                Map.of("tool_name", "record_weakness", "outcome", "SUCCEEDED"));
        metrics.increment("tool_invoked_total",
                Map.of("outcome", "SUCCEEDED", "tool_name", "record_weakness"));

        assertThat(metrics.snapshot())
                .as("⚠️ 同一组标签不同书写顺序必须归并到一条序列")
                .hasSize(1);
    }

    // ── M-2 / M-3：高基数标签必须被拒（FR-017 的核心）──────────────

    @Test
    @DisplayName("M-2 ⚠️⚠️ run_id 标签 → 抛异常（高基数会让时序库被打爆）")
    void runIdLabelRejected() {
        assertThatThrownBy(() -> metrics.increment("run_completed_total",
                Map.of("run_id", "6f5d33fc-723e-4f64-beda-9e4435c72691")))
                .as("⚠️⚠️ run_id 作为指标标签是监控系统最经典的自杀方式，必须 fail-fast")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_id")
                .hasMessageContaining("日志");
    }

    @Test
    @DisplayName("M-3 ⚠️⚠️ user_id / student_id / correlation_id / operation_id 全部被拒")
    void allHighCardinalityKeysRejected() {
        for (String forbidden : List.of("user_id", "student_id", "correlation_id",
                "operation_id", "idempotency_key", "session_id", "email", "trace_id")) {
            assertThatThrownBy(() -> metrics.increment("m", Map.of(forbidden, "v")))
                    .as("⚠️ 高基数字段 %s 必须被拒", forbidden)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("M-3b 大小写变体同样被拒（runId / RunID / RUN_ID）")
    void caseVariantsAlsoRejected() {
        for (String variant : List.of("runId", "RunID", "RUN_ID", "runid")) {
            assertThatThrownBy(() -> metrics.increment("m", Map.of(variant, "x")))
                    .as("⚠️ 绕过不能靠改大小写：%s", variant)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("M-4 白名单内的维度不被误伤")
    void allowedKeysNotBlocked() {
        assertThatCode(() -> {
            for (String key : AgentMetrics.ALLOWED_LABEL_KEYS) {
                metrics.increment("m", Map.of(key, "v"));
            }
        }).doesNotThrowAnyException();
    }

    // ── M-5：可观测 ────────────────────────────────────────────────

    @Test
    @DisplayName("M-5 snapshot 反映真实计数")
    void snapshotReflectsCounts() {
        metrics.increment("a_total");
        metrics.increment("a_total");
        metrics.increment("b_total");

        assertThat(metrics.snapshot()).containsEntry("a_total", 2L)
                .containsEntry("b_total", 1L);
        metrics.reset();
        assertThat(metrics.snapshot()).isEmpty();
    }

    // ── M-6：结构扫描（AT-OBS-002 的「扫描报告」）────────────────────

    @Test
    @DisplayName("M-6 ⚠️⚠️ 结构扫描：高基数字段绝不出现在任何 increment 调用的标签中")
    void structuralScanFindsNoHighCardinalityLabels() throws IOException {
        // ⚠️ 本用例是 FR-017 的「扫描报告」证据：
        //    即使白名单被人为放宽（加了 run_id），本扫描仍会红。
        Path src = Path.of("src/main/java");
        assertThat(Files.exists(src))
                .as("⚠️ 源码目录不存在，扫描用例等于没有执行")
                .isTrue();

        List<String> violations;
        try (Stream<Path> files = Files.walk(src)) {
            violations = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("AgentMetrics.java"))
                    .flatMap(p -> {
                        try {
                            return Files.lines(p).toList().stream();
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    })
                    .filter(line -> line.contains("increment("))
                    .filter(line -> AgentMetrics.FORBIDDEN_LABEL_KEYS.stream().anyMatch(line::contains))
                    .toList();
        }

        assertThat(violations)
                .as("⚠️⚠️ 以下 increment 调用使用了高基数字段作为标签（AT-OBS-002 违规）：%s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("M-6b 禁用清单本身覆盖了全部已知高基数字段")
    void forbiddenListIsComprehensive() {
        assertThat(AgentMetrics.FORBIDDEN_LABEL_KEYS)
                .as("⚠️ 禁用清单遗漏会让扫描用例失去防护作用")
                .contains("run_id", "user_id", "student_id", "operation_id",
                        "correlation_id", "idempotency_key");
    }
}
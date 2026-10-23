package cn.studykid.multiagent.application.cap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地权限判定基准的执行入口（P6R3 · R-15 · 08 §7 · 设计文档 §8.2）。
 *
 * <p>⚠️ <b>本测试不做耗时阈值断言</b>。原因有二：
 * <ol>
 *   <li><b>会把「机器繁忙」误判为「代码退化」</b> —— 共享构建机上的耗时抖动是常态，
 *       一旦把 p99 写成断言，全量构建就会随机变红，久而久之所有人都会无视红灯。</li>
 *   <li><b>目标值尚未标定</b> —— 设计文档 §8.2 明文「目标由压测标定」，
 *       在标定完成前写死阈值等于把一个<b>还没有依据的数字</b>升级成门禁。</li>
 * </ol>
 * 本测试把守的是<b>结构不变量</b>：判定结论、样本量、分位单调性、实测路径无数据库访问、
 * 报告口径完整 —— 这些不会随机器负载漂移。
 *
 * <p>⚠️ <b>结论口径</b>：本测试产出的是<b>实测数据</b>，不是「性能达标」的结论。
 * 标定与目标值建议见交付文档 {@code P6R3_本地判定性能基准.md}。
 *
 * <h2>运行模式</h2>
 * <pre>
 * # 默认（全量构建）：小样本量，结构自检 + 冒烟数据
 * mvn -pl agent-application -am test -Dtest=LocalDecisionBenchmarkTest
 *
 * # 标定：大样本量，产出归档报告
 * mvn -pl agent-application -am test -Dtest=LocalDecisionBenchmarkTest \
 *     -Dcap.benchmark.mode=calibration \
 *     -Dcap.benchmark.samples=30000 \
 *     -Dcap.benchmark.maxWarmup=60000 \
 *     -Dcap.benchmark.report=../../docs/统一配置与权限管控体系_开发计划/P6R3_本地判定性能基准_标定报告.json
 * </pre>
 */
class LocalDecisionBenchmarkTest {

    private static final int DEFAULT_SAMPLES = 3_000;
    private static final int DEFAULT_WARMUP_WINDOW = 800;
    private static final int DEFAULT_MAX_WARMUP = 12_000;
    private static final int DEFAULT_STABLE_WINDOWS = 3;
    private static final double DEFAULT_TOLERANCE = 0.05;
    private static final int DEFAULT_CONCURRENCY_WARMUP = 300;
    private static final List<Integer> DEFAULT_CONCURRENCY = List.of(1, 4, 8);

    private static List<LocalDecisionBenchmark.Measurement> measurements;
    private static List<LocalDecisionBenchmark.Breakdown> breakdowns;
    private static List<LocalDecisionBenchmark.Tier> selectedTiers;
    private static Map<String, Object> environment;
    private static Map<String, Object> settings;
    private static Map<String, Integer> warmupPerTier;
    private static Set<String> visitedClasses;
    private static Path reportPath;
    private static String reportJson;

    @BeforeAll
    static void runBenchmark() {
        int samples = intProperty("cap.benchmark.samples", DEFAULT_SAMPLES);
        int warmupWindow = intProperty("cap.benchmark.warmupWindow", DEFAULT_WARMUP_WINDOW);
        int maxWarmup = intProperty("cap.benchmark.maxWarmup", DEFAULT_MAX_WARMUP);
        int stableWindows = intProperty("cap.benchmark.stableWindows", DEFAULT_STABLE_WINDOWS);
        double tolerance = doubleProperty("cap.benchmark.tolerance", DEFAULT_TOLERANCE);
        int concurrencyWarmup = intProperty("cap.benchmark.concurrencyWarmup",
                DEFAULT_CONCURRENCY_WARMUP);
        List<Integer> concurrencyLevels = concurrencyProperty();
        String mode = System.getProperty("cap.benchmark.mode", "default");

        measurements = new ArrayList<>();
        breakdowns = new ArrayList<>();
        warmupPerTier = new LinkedHashMap<>();
        List<LocalDecisionBenchmark.Tier> tiers = tiers(mode);
        selectedTiers = tiers;
        for (LocalDecisionBenchmark.Tier tier : tiers) {
            LocalDecisionBenchmark.Scenario scenario = LocalDecisionBenchmark.scenario(tier);
            // ⚠️ 每档只预热一次：JIT 状态是 JVM 级的，三个并发度复用同一份已收敛的代码。
            int warmupUsed = LocalDecisionBenchmark.warmupUntilStable(
                    scenario, warmupWindow, maxWarmup, tolerance, stableWindows);
            warmupPerTier.put(tier.name(), warmupUsed);
            for (int concurrency : concurrencyLevels) {
                measurements.add(LocalDecisionBenchmark.measure(tier, scenario, concurrency,
                        concurrencyWarmup, samples));
            }
            breakdowns.add(LocalDecisionBenchmark.breakdown(tier, scenario, samples));
        }

        environment = LocalDecisionBenchmark.environment();
        settings = new LinkedHashMap<>();
        settings.put("mode", mode);
        settings.put("samplesPerCombination", samples);
        settings.put("warmupPolicy", "自适应预热（p50 收敛）");
        settings.put("warmupWindow", warmupWindow);
        settings.put("maxWarmupIterations", maxWarmup);
        settings.put("stableWindows", stableWindows);
        settings.put("tolerance", tolerance);
        settings.put("concurrencyWarmup", concurrencyWarmup);
        settings.put("concurrencyLevels", concurrencyLevels.toString());
        settings.put("tiers", selectedTiers.toString());
        settings.put("adaptiveWarmupIterations", warmupPerTier.toString());
        settings.put("metricExcludes",
                "授权 DB 读取、闭包回源重解析、MCP 实时清单拉取、端到端网络与执行");

        reportJson = LocalDecisionBenchmark.toJson(mode, environment, settings, measurements, breakdowns);
        reportPath = LocalDecisionBenchmark.writeReport(reportJson, null);

        visitedClasses = LocalDecisionBenchmark.assertNoDataSourceIn(
                LocalDecisionBenchmark.scenario(tiers.get(0)).validator());

        printSummary();
    }

    /**
     * 选择参与测量的规模档位。
     *
     * <p>⚠️ <b>默认（全量构建）只跑 S / M</b>：档位 L（320 个绑定）的单跳已达数百微秒，
     * 三档 × 三并发 × 大样本会让全量构建平白多花数秒。<b>标定模式跑全部档位</b> ——
     * 「大闭包」是标定结论的关键一端，不能因为构建慢就不测。
     *
     * @param mode 运行模式
     * @return 档位列表
     */
    private static List<LocalDecisionBenchmark.Tier> tiers(String mode) {
        String raw = System.getProperty("cap.benchmark.tiers");
        List<LocalDecisionBenchmark.Tier> selected = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String name = part.trim();
                LocalDecisionBenchmark.defaultTiers().stream()
                        .filter(t -> t.name().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresent(selected::add);
            }
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        if ("calibration".equals(mode)) {
            return LocalDecisionBenchmark.defaultTiers();
        }
        return LocalDecisionBenchmark.defaultTiers().stream()
                .filter(t -> !"L".equals(t.name()))
                .toList();
    }

    // ── 结构不变量 ──────────────────────────────────────────────────

    @Test
    @DisplayName("基准矩阵完整：每档都产出测量结果且样本量达标")
    void benchmarkMatrixIsComplete() {
        List<Integer> concurrencyLevels = concurrencyProperty();
        int expected = selectedTiers.size() * concurrencyLevels.size();
        assertThat(measurements)
                .as("「规模档位 × 并发档位」的组合数应与矩阵一致")
                .hasSize(expected);
        assertThat(measurements).allSatisfy(m -> {
            assertThat(m.measuredIterations()).isEqualTo(intProperty(
                    "cap.benchmark.samples", DEFAULT_SAMPLES));
            assertThat(m.warmupIterations()).isPositive();
            assertThat(m.tier().bindingCount()).isPositive();
            assertThat(selectedTiers).contains(m.tier());
        });
    }

    @Test
    @DisplayName("自适应预热生效：每档预热都跑到 p50 收敛（未收敛不得进入测量窗口）")
    void adaptiveWarmupConverged() {
        assertThat(warmupPerTier).isNotEmpty();
        assertThat(warmupPerTier.values())
                .as("每档的自适应预热都应消耗了足够迭代才能判定收敛")
                .allSatisfy(iterations -> assertThat(iterations)
                        .isGreaterThanOrEqualTo(intProperty("cap.benchmark.warmupWindow",
                                DEFAULT_WARMUP_WINDOW) * 2));
    }

    @Test
    @DisplayName("判定结论正确：单跳必须走完链路并 ALLOW（否则测的不是授权通过路径）")
    void everyMeasuredDecisionIsAllow() {
        assertThat(measurements).allSatisfy(m -> assertThat(m.nonAllowDecisions())
                .as("档位 %s / 并发 %d 出现非 ALLOW 决策 —— 基准测的不是授权通过路径",
                        m.tier().name(), m.concurrency())
                .isZero());
    }

    @Test
    @DisplayName("分位单调：p50 ≤ p95 ≤ p99 ≤ max（统计口径自洽）")
    void percentilesAreMonotonic() {
        assertThat(measurements).allSatisfy(m -> {
            assertThat(m.p50Nanos()).isLessThanOrEqualTo(m.p95Nanos());
            assertThat(m.p95Nanos()).isLessThanOrEqualTo(m.p99Nanos());
            assertThat(m.p99Nanos()).isLessThanOrEqualTo(m.maxNanos());
            assertThat(m.minNanos()).isLessThanOrEqualTo(m.p50Nanos());
            assertThat(m.meanNanos()).isBetween((double) m.minNanos(), (double) m.maxNanos());
        });
    }

    @Test
    @DisplayName("实测路径无数据库访问：08 §7「不含授权 DB 读取」的可执行核实")
    void measuredPathTouchesNoDatabase() {
        assertThat(visitedClasses)
                .as("本地判定对象图里不应出现任何 JDBC / 连接池 / 数据源实现")
                .noneMatch(name -> name.startsWith("org.postgresql")
                        || name.startsWith("com.zaxxer")
                        || name.startsWith("org.springframework.jdbc")
                        || name.startsWith("javax.sql")
                        || name.startsWith("java.sql"));
        assertThat(visitedClasses).contains("cn.studykid.multiagent.application.cap.InvocationValidator");
    }

    @Test
    @DisplayName("报告已落盘且口径完整：指标定义、排除项、环境指纹齐备")
    void reportIsWrittenWithCompleteScope() throws java.io.IOException {
        assertThat(reportPath).exists();
        assertThat(reportJson)
                .contains("InvocationValidator.validate 单跳")
                .contains("\"excludes\"")
                .contains("授权 DB 读取")
                .contains("\"percentileMethod\"")
                .contains("未在标定环境验证")
                .contains("osName")
                .contains("availableProcessors");
        assertThat(Files.readString(reportPath)).contains(LocalDecisionBenchmark.SCHEMA);
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static int intProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }

    private static double doubleProperty(String key, double fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
    }

    private static List<Integer> concurrencyProperty() {
        String raw = System.getProperty("cap.benchmark.concurrency");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CONCURRENCY;
        }
        List<Integer> levels = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                levels.add(Integer.parseInt(trimmed));
            }
        }
        return levels.isEmpty() ? DEFAULT_CONCURRENCY : levels;
    }

    private static void printSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
                .append("── 本地判定基准（08 §7 / 设计文档 §8.2）")
                .append(System.lineSeparator())
                .append(String.format("%-5s %-9s %-6s %-9s %-9s %-9s %-9s %-8s %-6s%n",
                        "档位", "闭包绑定", "并发", "p50(µs)", "p95(µs)", "p99(µs)", "预热", "吞吐/s", "GC"))
                .append("─".repeat(80));
        for (LocalDecisionBenchmark.Measurement m : measurements) {
            sb.append(String.format("%-5s %-10d %-7d %-10.2f %-10.2f %-10.2f %-10d %-9.0f %-6d%n",
                    m.tier().name(), m.tier().bindingCount(), m.concurrency(),
                    m.p50Nanos() / 1_000.0, m.p95Nanos() / 1_000.0, m.p99Nanos() / 1_000.0,
                    warmupPerTier.getOrDefault(m.tier().name(), 0),
                    m.throughputPerSecond(), m.gcCount()));
        }
        sb.append("成本分解（单线程 p99，µs）：").append(System.lineSeparator());
        for (LocalDecisionBenchmark.Breakdown b : breakdowns) {
            sb.append(String.format("  档位 %-4s 闭包绑定 %-6d 指纹重算 %-9.2f 闭包查表 %-9.2f%n",
                    b.tier().name(), b.tier().bindingCount(),
                    b.fingerprintP99() / 1_000.0, b.lookupP99() / 1_000.0));
        }
        sb.append("报告：").append(reportPath.toAbsolutePath()).append(System.lineSeparator())
                .append("⚠️ 实测数据，非「性能达标」结论；目标值须按 §8.2 标定后另行评审。")
                .append(System.lineSeparator());
        System.out.println(sb);
    }
}

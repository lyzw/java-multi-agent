package cn.studykid.multiagent.application.cap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 本地权限判定基准 harness（P6R3 · R-15 · 08 §7 · 设计文档 §8.2）。
 *
 * <p>⚠️ <b>本类测的是「本地判定」，不是端到端</b>。口径逐字对齐：
 * <ul>
 *   <li>08 §7：「本地权限判定 ｜ {@code InvocationValidator} 单跳（闭包查表+版本比对+策略）
 *       ｜ p99 &lt; 1ms ｜ 数据集/闭包规模/并发量明确；<b>不含授权 DB 读取的端到端耗时</b>」；</li>
 *   <li>设计文档 §8.2：「本地判定 ｜ 已获可信版本与快照后的<b>纯内存计算</b>；目标由压测标定」。</li>
 * </ul>
 *
 * <p>因此本 harness 把 {@code InvocationValidator} 的五个端口全部换成<b>内存实现</b>
 * （{@link CapFixture}），测量窗口内<b>不发生任何数据库访问</b> ——
 * 这不是假设，而是由 {@link #assertNoDataSourceIn(Object)} 在运行期逐对象图核实的事实。
 *
 * <p>⚠️ <b>闭包解析（回源重解析）不在本指标内</b>：解析要走权威版本读取，
 * 那属于「授权链」（§8.2 另行设定）。本 harness 测的是「已获可信版本与快照后」的纯计算段。
 *
 * <p>⚠️ <b>本 harness 不做耗时阈值断言</b>：耗时断言会让全量构建随机变红，
 * 且在共享构建机上把「机器繁忙」误判为「代码退化」。耗时只<b>产出数据</b>，
 * 由交付文档人工评审；机器把守的是<b>结构不变量</b>（决策结论、样本量、分位单调性、
 * 实测路径无数据源），这些不会随机器负载漂移。
 *
 * <h2>测量方法学（决定了数据可不可信）</h2>
 * <ul>
 *   <li><b>自适应预热</b>：预热不是「跑固定次数」，而是<b>跑到 p50 收敛</b>
 *       （连续若干窗口的 p50 互相偏离在容差内才停）。固定次数的预热会让 p99 测到
 *       解释执行/C1 代码 —— 那是<b>看起来更慢但与生产无关</b>的数。</li>
 *   <li><b>每档只预热一次</b>：JIT 状态是 JVM 级的，同一档位在三个并发度下
 *       复用同一份已收敛的代码。</li>
 *   <li><b>记录 GC 影响</b>：闭包指纹每次判定都要全量序列化 + 哈希，分配量很大；
 *       尾延迟里有多少是 GC 造成的，必须随数据一起报出来，否则 p99 无法解读。</li>
 *   <li><b>轮转目标位置</b>：{@code EffectiveClosure.skill/tool} 是线性扫描，
 *       只测命中首位会得出「查表几乎不要钱」的测量假象。</li>
 * </ul>
 */
final class LocalDecisionBenchmark {

    /** 报告 schema 版本（格式变化时递增）。 */
    static final String SCHEMA = "cap-local-decision-benchmark/v1";

    /** 报告落盘路径（可用系统属性 {@code cap.benchmark.report} 覆盖）。 */
    static final String REPORT_PATH_PROPERTY = "cap.benchmark.report";

    private static final String TENANT = "t-bench";
    private static final String PRINCIPAL = "user-bench";
    private static final String AGENT = "agent-bench";
    private static final String RUN = "run-bench";
    private static final String ROLE = "TEACHER";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalDecisionBenchmark() {
    }

    // ── 规模档位 ────────────────────────────────────────────────────

    /**
     * 一个闭包规模档位。
     *
     * @param name          档位名
     * @param skills        Agent 启用的 Skill 数
     * @param toolsPerSkill 每个 Skill 声明的原生 Tool 数
     */
    record Tier(@NonNull String name, int skills, int toolsPerSkill) {

        /** @return 闭包内的绑定对象总数（Skill + Tool）。 */
        int bindingCount() {
            return skills + skills * toolsPerSkill;
        }
    }

    /**
     * 默认规模档位。
     *
     * <p>⚠️ <b>这三个档位不是「生产规模」</b>，而是<b>标定用的规模梯度</b>：
     * 从「小闭包」到「大闭包」各取一点，用来看单跳耗时随闭包规模的增长形态。
     * 生产侧的真实闭包规模分布<b>尚未统计</b>（见交付文档「未覆盖项」）。
     */
    static List<Tier> defaultTiers() {
        return List.of(
                new Tier("S", 4, 4),
                new Tier("M", 16, 4),
                new Tier("L", 64, 4));
    }

    // ── 测量结果 ────────────────────────────────────────────────────

    /**
     * 一档「规模 × 并发」的测量结果。
     *
     * @param tier                 规模档位
     * @param concurrency          并发线程数
     * @param warmupIterations     预热次数（不计入统计）
     * @param measuredIterations   测量次数
     * @param p50Nanos             p50（最近秩法）
     * @param p95Nanos             p95
     * @param p99Nanos             p99
     * @param minNanos             最小值
     * @param maxNanos             最大值
     * @param meanNanos            平均值
     * @param wallClockNanos       整个测量窗口的墙钟耗时
     * @param throughputPerSecond  吞吐（次/秒）
     * @param nonAllowDecisions    非 ALLOW 的决策数（正常应为 0）
     * @param gcCount              测量窗口内的 GC 次数
     * @param gcMillis             测量窗口内的 GC 累计停顿（毫秒）
     */
    record Measurement(@NonNull Tier tier,
                       int concurrency,
                       int warmupIterations,
                       int measuredIterations,
                       long p50Nanos,
                       long p95Nanos,
                       long p99Nanos,
                       long minNanos,
                       long maxNanos,
                       double meanNanos,
                       long wallClockNanos,
                       double throughputPerSecond,
                       int nonAllowDecisions,
                       long gcCount,
                       long gcMillis) {

        /** @return p99（毫秒），便于文档直接引用。 */
        double p99Millis() {
            return p99Nanos / 1_000_000.0;
        }
    }

    /**
     * 单跳成本分解（单线程口径）。
     *
     * @param tier           规模档位
     * @param fingerprintP99 闭包指纹重算的 p99
     * @param lookupP99      闭包查表（Skill + Tool 线性查找）的 p99
     */
    record Breakdown(@NonNull Tier tier,
                     long fingerprintP99,
                     long lookupP99) {
    }

    // ── 场景装配 ────────────────────────────────────────────────────

    /**
     * 一次测量所需的全部对象。
     *
     * @param validator  待测判定器
     * @param requests   目标覆盖闭包不同位置的请求（避免总命中同一个位置）
     * @param closure    闭包（用于成本分解）
     */
    record Scenario(@NonNull InvocationValidator validator,
                    @NonNull List<InvocationValidator.@NonNull InvocationRequest> requests,
                    @NonNull EffectiveClosure closure) {
    }

    /**
     * 按档位装配一个<b>纯内存</b>场景。
     *
     * <p>五个端口全部由 {@link CapFixture} 提供内存实现；快照指纹与闭包一致，
     * 因此判定必然走完整链路并在最后一步（指纹比对）通过。
     */
    static @NonNull Scenario scenario(@NonNull Tier tier) {
        CapFixture fixture = new CapFixture();
        List<String> skillIds = new ArrayList<>(tier.skills());
        for (int s = 0; s < tier.skills(); s++) {
            String skillId = "skill-" + s;
            List<String> declared = new ArrayList<>(tier.toolsPerSkill());
            for (int t = 0; t < tier.toolsPerSkill(); t++) {
                String toolId = "tool-" + s + "-" + t;
                declared.add(toolId);
                fixture.withTool(toolId, 1, CapabilityRiskLevel.READ, "[]");
            }
            skillIds.add(skillId);
            fixture.withSkill(skillId, 1, CapabilityRiskLevel.WRITE_EXTERNAL, declared);
        }
        fixture.withAgent(TENANT, AGENT, 1, skillIds, "{}");
        fixture.withAuthzVersion(TENANT, PRINCIPAL, 1L);
        fixture.withRolePermission(ROLE, "tool:invoke");

        PermissionResolver resolver = new PermissionResolver(
                fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
        EffectiveClosure closure = resolver.resolve(TENANT, AGENT, 1);
        fixture.withSnapshot(new CapabilitySnapshot(RUN, TENANT, AGENT,
                ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.EPOCH));

        InvocationValidator validator = new InvocationValidator(
                fixture.authzVersions(), fixture.rolePermissions(),
                resolver, fixture.snapshots(), fixture.manifest());

        List<InvocationValidator.InvocationRequest> requests = new ArrayList<>();
        for (int s = 0; s < tier.skills(); s++) {
            for (int t = 0; t < tier.toolsPerSkill(); t++) {
                requests.add(new InvocationValidator.InvocationRequest(
                        new TenantContext(TENANT, PRINCIPAL, Set.of(ROLE), 1L),
                        AGENT, "skill-" + s, "tool-" + s + "-" + t, Map.of(), RUN, null));
            }
        }
        return new Scenario(validator, List.copyOf(requests), closure);
    }

    // ── 预热 ────────────────────────────────────────────────────────

    /**
     * 自适应预热：跑到 <b>p50 收敛</b>为止。
     *
     * <p>⚠️ 为什么要「跑到收敛」而不是「跑固定次数」：JIT 编译是<b>渐进</b>的，
     * 固定次数预热常常在 C2 编译完成前就结束，于是测量窗口里混着解释执行与 C1 代码，
     * 得到的 p99 比稳态高一个数量级 —— 那个数<b>与生产无关</b>，却会被误当成基线。
     *
     * @param scenario      场景
     * @param window        每个窗口的迭代数
     * @param maxIterations 预热迭代总数上限
     * @param tolerance     相邻窗口 p50 的相对偏离容差
     * @param stableWindows 需要连续稳定的窗口数
     * @return 实际执行的预热迭代数
     */
    static int warmupUntilStable(@NonNull Scenario scenario,
                                 int window,
                                 int maxIterations,
                                 double tolerance,
                                 int stableWindows) {
        int executed = 0;
        double previousP50 = -1;
        int stable = 0;
        while (executed < maxIterations) {
            int size = Math.min(window, maxIterations - executed);
            long[] timings = new long[size];
            for (int i = 0; i < size; i++) {
                timings[i] = timeOnce(scenario, i, new AtomicInteger());
            }
            executed += size;
            long[] sorted = timings.clone();
            Arrays.sort(sorted);
            double p50 = percentile(sorted, 0.50);
            if (previousP50 > 0
                    && Math.abs(p50 - previousP50) / previousP50 <= tolerance) {
                stable++;
                if (stable >= stableWindows) {
                    return executed;
                }
            } else {
                stable = 0;
            }
            previousP50 = p50;
        }
        return executed;
    }

    // ── 测量 ────────────────────────────────────────────────────────

    /**
     * 在给定并发度下测量单跳耗时。
     *
     * @param tier        规模档位
     * @param scenario    场景
     * @param concurrency 并发线程数
     * @param warmup      本档在该并发度下的追加预热次数
     * @param samples     测量次数
     * @return 测量结果
     */
    static @NonNull Measurement measure(@NonNull Tier tier,
                                        @NonNull Scenario scenario,
                                        int concurrency,
                                        int warmup,
                                        int samples) {
        int warmupCount = Math.max(warmup, 1);
        warmup(scenario, warmupCount);

        long[] timings = new long[samples];
        AtomicInteger nonAllow = new AtomicInteger();
        long gcBefore = gcCount();
        long gcMillisBefore = gcMillis();
        long wallClockStart = System.nanoTime();
        if (concurrency <= 1) {
            for (int i = 0; i < samples; i++) {
                timings[i] = timeOnce(scenario, i, nonAllow);
            }
        } else {
            measureConcurrently(scenario, concurrency, samples, timings, nonAllow);
        }
        long wallClock = System.nanoTime() - wallClockStart;
        long gcCountDelta = gcCount() - gcBefore;
        long gcMillisDelta = gcMillis() - gcMillisBefore;

        long[] sorted = timings.clone();
        Arrays.sort(sorted);
        double total = 0;
        for (long t : sorted) {
            total += t;
        }
        return new Measurement(
                tier,
                concurrency,
                warmupCount,
                samples,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted[0],
                sorted[sorted.length - 1],
                total / sorted.length,
                wallClock,
                samples / (wallClock / 1_000_000_000.0),
                nonAllow.get(),
                gcCountDelta,
                gcMillisDelta);
    }

    /**
     * 成本分解：把单跳耗时拆成「指纹重算」与「闭包查表」两段（单线程口径）。
     *
     * <p>⚠️ 两段都是<b>单跳里的真实子步骤</b>，但它们<b>不是</b>互斥穷举 ——
     * 剩余部分是主体授权、角色判定、风险上限、参数策略与端口调用的合计。
     *
     * <p>⚠️ <b>闭包查表必须轮转目标位置</b>：{@code EffectiveClosure.skill/tool} 是线性扫描，
     * 命中首位的耗时与命中尾部的耗时差一个数量级 —— 只测首位会得出「查表几乎不要钱」
     * 的<b>测量假象</b>，与真实调用分布不符。故此处按请求序列轮转取样。
     */
    static @NonNull Breakdown breakdown(@NonNull Tier tier,
                                        @NonNull Scenario scenario,
                                        int samples) {
        EffectiveClosure closure = scenario.closure();

        long[] fingerprint = new long[samples];
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            ClosureFingerprint.of(closure);
            fingerprint[i] = System.nanoTime() - t0;
        }
        long[] lookup = new long[samples];
        for (int i = 0; i < samples; i++) {
            InvocationValidator.InvocationRequest target = requestAt(scenario, i);
            long t0 = System.nanoTime();
            closure.skill(target.skillId());
            closure.tool(target.toolId());
            lookup[i] = System.nanoTime() - t0;
        }
        Arrays.sort(fingerprint);
        Arrays.sort(lookup);
        return new Breakdown(tier, percentile(fingerprint, 0.99), percentile(lookup, 0.99));
    }

    private static void warmup(@NonNull Scenario scenario, int iterations) {
        for (int i = 0; i < iterations; i++) {
            scenario.validator().validate(requestAt(scenario, i));
        }
    }

    private static InvocationValidator.@NonNull InvocationRequest requestAt(
            @NonNull Scenario scenario, int index) {
        List<InvocationValidator.InvocationRequest> requests = scenario.requests();
        return requests.get(index % requests.size());
    }

    private static long timeOnce(@NonNull Scenario scenario, int index, AtomicInteger nonAllow) {
        InvocationValidator.InvocationRequest request = requestAt(scenario, index);
        long t0 = System.nanoTime();
        AuthorizationDecision decision = scenario.validator().validate(request);
        long elapsed = System.nanoTime() - t0;
        if (!consume(decision)) {
            nonAllow.incrementAndGet();
        }
        return elapsed;
    }

    private static void measureConcurrently(@NonNull Scenario scenario,
                                            int concurrency,
                                            int samples,
                                            long[] timings,
                                            AtomicInteger nonAllow) {
        AtomicInteger cursor = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(concurrency);
        try {
            for (int t = 0; t < concurrency; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = cursor.getAndIncrement(); i < samples; i = cursor.getAndIncrement()) {
                        InvocationValidator.InvocationRequest request = requestAt(scenario, i);
                        long t0 = System.nanoTime();
                        AuthorizationDecision decision = scenario.validator().validate(request);
                        timings[i] = System.nanoTime() - t0;
                        if (!consume(decision)) {
                            nonAllow.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("基准测量被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("基准测量失败", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 消费判定结果（避免 JIT 把判定当作无用计算消除掉）。
     *
     * @param decision 判定结果
     * @return 是否为 ALLOW
     */
    private static boolean consume(@NonNull AuthorizationDecision decision) {
        return decision.authorization() == AuthorizationDecision.Authorization.ALLOW;
    }

    /**
     * 最近秩法分位数。
     *
     * @param sorted 已升序排序的样本
     * @param p      分位（0~1）
     * @return 分位对应的样本值
     */
    static long percentile(long[] sorted, double p) {
        int n = sorted.length;
        int index = (int) Math.ceil(p * n) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= n) {
            index = n - 1;
        }
        return sorted[index];
    }

    // ── GC 观测 ─────────────────────────────────────────────────────

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long millis = bean.getCollectionTime();
            if (millis > 0) {
                total += millis;
            }
        }
        return total;
    }

    // ── 「实测路径无数据库访问」的运行期核实 ────────────────────────

    /**
     * 核实对象图内<b>不存在</b>任何数据源 / JDBC 连接。
     *
     * <p>⚠️ 这是 08 §7「<b>不含授权 DB 读取的端到端耗时</b>」的<b>可执行</b>形式：
     * 不是在文档里声称「本测试不碰数据库」，而是把判定器持有的对象图逐层走一遍，
     * 确认里面没有任何 {@link DataSource} 或 {@link java.sql.Connection}。
     *
     * @param root 根对象
     * @return 遍历到的类名集合（供调用方进一步核对）
     * @throws IllegalStateException 发现数据源时抛出，并给出路径
     */
    static @NonNull Set<String> assertNoDataSourceIn(@NonNull Object root) {
        Set<String> visitedClasses = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (findDataSource(root, path, visited, visitedClasses, 0)) {
            throw new IllegalStateException(
                    "本地判定实测路径出现数据库访问，08 §7 口径被破坏: " + String.join(" -> ", path));
        }
        return visitedClasses;
    }

    private static boolean findDataSource(@Nullable Object node,
                                          @NonNull List<String> path,
                                          @NonNull Set<Object> visited,
                                          @NonNull Set<String> visitedClasses,
                                          int depth) {
        if (node == null || depth > 8) {
            return false;
        }
        if (node instanceof DataSource || node instanceof java.sql.Connection) {
            path.add(node.getClass().getName());
            return true;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (findDataSource(value, path, visited, visitedClasses, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (findDataSource(value, path, visited, visitedClasses, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof CharSequence || node instanceof Number || node instanceof Boolean
                || node instanceof Enum<?> || node instanceof Class<?>
                || node instanceof java.time.temporal.Temporal) {
            return false;
        }
        String className = node.getClass().getName();
        visitedClasses.add(className);
        // ⚠️ JDK 内部类不反射下钻（java.base 未开放深反射），
        //    它们本身也不可能是数据源；Map / Iterable 已在上面按公开接口展开。
        if (className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("jdk.") || className.startsWith("sun.")
                || className.startsWith("com.sun.")) {
            return false;
        }
        if (!visited.add(node)) {
            return false;
        }
        for (Field field : node.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(node);
            } catch (RuntimeException | IllegalAccessException e) {
                continue;
            }
            path.add(field.getName());
            if (findDataSource(value, path, visited, visitedClasses, depth + 1)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    // ── 报告 ────────────────────────────────────────────────────────

    /**
     * 把测量结果序列化为 JSON 文本。
     *
     * @param mode           运行模式（{@code default} / {@code calibration}）
     * @param environment    环境指纹
     * @param settings       运行设置
     * @param measurements   测量结果
     * @param breakdowns     成本分解
     * @return JSON 文本
     */
    static @NonNull String toJson(@NonNull String mode,
                                  @NonNull Map<String, Object> environment,
                                  @NonNull Map<String, Object> settings,
                                  @NonNull List<Measurement> measurements,
                                  @NonNull List<Breakdown> breakdowns) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("mode", mode);
        root.put("generatedAt", Instant.now().toString());

        ObjectNode metric = root.putObject("metric");
        metric.put("name", "InvocationValidator.validate 单跳（本地纯内存判定）");
        metric.put("designRef", "设计文档 §8.2 本地判定；08 §7 本地权限判定");
        metric.put("includes",
                "主体授权判定、闭包查表、风险上限、参数策略、闭包指纹重算与比对");
        metric.put("excludes",
                "授权 DB 读取、闭包回源重解析、MCP 实时清单拉取、端到端网络与执行");
        metric.put("percentileMethod", "最近秩法 index = ceil(p * n) - 1");
        metric.put("warmupPolicy", "自适应预热：连续窗口 p50 偏离在容差内即判定收敛");

        ObjectNode env = root.putObject("environment");
        environment.forEach((k, v) -> env.put(k, String.valueOf(v)));

        ObjectNode set = root.putObject("settings");
        settings.forEach((k, v) -> set.put(k, String.valueOf(v)));

        ArrayNode array = root.putArray("measurements");
        for (Measurement m : measurements) {
            ObjectNode node = array.addObject();
            node.put("tier", m.tier().name());
            node.put("skills", m.tier().skills());
            node.put("toolsPerSkill", m.tier().toolsPerSkill());
            node.put("closureBindings", m.tier().bindingCount());
            node.put("concurrency", m.concurrency());
            node.put("warmupIterations", m.warmupIterations());
            node.put("measuredIterations", m.measuredIterations());
            node.put("p50Nanos", m.p50Nanos());
            node.put("p95Nanos", m.p95Nanos());
            node.put("p99Nanos", m.p99Nanos());
            node.put("minNanos", m.minNanos());
            node.put("maxNanos", m.maxNanos());
            node.put("meanNanos", m.meanNanos());
            node.put("wallClockNanos", m.wallClockNanos());
            node.put("throughputPerSecond", m.throughputPerSecond());
            node.put("nonAllowDecisions", m.nonAllowDecisions());
            node.put("gcCount", m.gcCount());
            node.put("gcMillis", m.gcMillis());
        }

        ArrayNode breakdownArray = root.putArray("breakdowns");
        for (Breakdown b : breakdowns) {
            ObjectNode node = breakdownArray.addObject();
            node.put("tier", b.tier().name());
            node.put("closureBindings", b.tier().bindingCount());
            node.put("fingerprintP99Nanos", b.fingerprintP99());
            node.put("lookupP99Nanos", b.lookupP99());
        }

        ArrayNode notes = root.putArray("notes");
        notes.add("耗时为本次运行的实测值，不是承诺值；本 harness 不做耗时阈值断言。");
        notes.add("单跳耗时随闭包规模增长 —— 闭包指纹每次判定都要全量重算。");
        notes.add("p99 含 GC 停顿；gcCount / gcMillis 字段用于拆分尾延迟来源。");
        notes.add("未在标定环境验证 —— 不得据此声称性能达标。");
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * 把 JSON 报告写到磁盘。
     *
     * @param json 报告文本
     * @param path 目标路径（{@code null} = 用默认 {@code target/local-decision-benchmark.json}）
     * @return 实际写入的路径
     */
    static @NonNull Path writeReport(@NonNull String json, @Nullable Path path) {
        Path target = path != null ? path
                : Path.of(System.getProperty(REPORT_PATH_PROPERTY, "target/local-decision-benchmark.json"));
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, json, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("写入基准报告失败: " + target, e);
        }
    }

    /**
     * 采集环境指纹。
     *
     * @return 环境指纹
     */
    static @NonNull Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jvmVersion", System.getProperty("java.version"));
        env.put("jvmVendor", System.getProperty("java.vendor"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        env.put("note", "开发机环境，非标定的目标生产规格环境");
        return env;
    }
}

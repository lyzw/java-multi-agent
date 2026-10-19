package cn.studykid.multiagent.application.observability;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;

/**
 * 指标注册表（P3-D10 / FR-017 / AT-OBS-001/002）。
 *
 * <p><b>⚠️ 本类的存在理由是「强制」而非「记录」</b>：
 * FR-017 原文要求*「指标<b>不含</b>用户/Run 等高基数标签」*，
 * 且验收矩阵的证据类型是「<b>扫描报告</b>」——
 * 说明它要的是**可被扫描证明的约束**，不是文档承诺。
 *
 * <p><b>高基数标签是监控系统最经典的自杀方式</b>：
 * {@code run_id} 作为标签 → 每个 Run 一个时间序列 → 时序库被打爆，
 * 且<b>没有任何业务价值</b>（Run 级明细应查日志，不是查指标）。
 *
 * <p>因此本类在<b>运行期</b>强制：标签键必须在
 * {@link #ALLOWED_LABEL_KEYS} 白名单内，否则<b>抛异常</b>——
 * 让「加错标签」在<b>第一次调用时</b>就崩，
 * 而不是上线后由时序库崩溃来告知（D-1）。
 *
 * <p><b>⚠️ 刻意不引 Micrometer</b>：引入后标签约束会退化为文档约定。
 * P4 若接入 Micrometer，可把本类作为其数据源。
 *
 * <p><b>指标与日志的分工</b>（D-2）：
 * <ul>
 *   <li>明细（这个 runId 走到哪一步）→ <b>日志</b>（带 runId/correlationId）；</li>
 *   <li>聚合（今天失败了多少）→ <b>指标</b>（只带低基数维度）。</li>
 * </ul>
 */
public class AgentMetrics {

    /**
     * ⚠️ <b>唯一允许作为指标标签的键</b>（低基数）。
     *
     * <p>每一项的基数上界都是<b>可枚举且有限</b>的：
     * 状态机取值、工具登记表、风险等级表、错误枚举、供应商表。
     */
    public static final Set<String> ALLOWED_LABEL_KEYS = Set.of(
            "run_state",       // RunState 6 值
            "tool_name",       // 工具登记表（当前 2 个）
            "risk_level",      // ToolRiskLevel 枚举
            "outcome",         // 有限结局（SUCCEEDED/REJECTED/...）
            "error_kind",      // ModelErrorKind 6 值
            "provider",        // 供应商登记表
            "reason",          // 拒绝原因枚举
            "bucket"           // 限流桶键（有限集合）
    );

    /**
     * ⚠️ <b>显式禁止的高基数字段</b>。
     *
     * <p>单独列出（而非只靠白名单）有两个用途：
     * ① 报错信息能<b>指名道姓</b>告诉开发者「你加的是 run_id，它是什么」；
     * ② M-6 的<b>结构扫描</b>用例据此断言代码里不出现这些标识符——
     * 即使白名单被误改，扫描仍会红。
     */
    public static final Set<String> FORBIDDEN_LABEL_KEYS = Set.of(
            "run_id", "runid", "user_id", "userid", "student_id", "studentid",
            "operation_id", "correlation_id", "idempotency_key", "session_id",
            "email", "trace_id", "span_id", "principal_id", "agent_id", "snapshot_id"
    );

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * 计数 +1。
     *
     * @param name   指标名（约定 {@code <对象>_<动作>_total}）
     * @param labels 低基数标签；键必须在 {@link #ALLOWED_LABEL_KEYS} 内
     * @throws IllegalArgumentException 标签键不在白名单（<b>fail-fast</b>）
     */
    public void increment(@NonNull String name, @NonNull Map<String, String> labels) {
        requireSafeLabels(name, labels);
        String key = seriesKey(name, labels);
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /** 无标签计数。 */
    public void increment(@NonNull String name) {
        increment(name, Map.of());
    }

    /**
     * 校验标签合法性（<b>fail-fast</b>，D-1）。
     *
     * @throws IllegalArgumentException 含白名单外的键
     */
    public void requireSafeLabels(@NonNull String name,
                                  @NonNull Map<String, String> labels) {
        for (String key : labels.keySet()) {
            if (ALLOWED_LABEL_KEYS.contains(key)) {
                continue;
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            String hint = FORBIDDEN_LABEL_KEYS.contains(lower)
                    ? "（⚠️ 这是高基数字段，放进指标会让时序库被打爆；"
                      + "Run 级明细请查日志的 runId 字段）"
                    : "（低基数维度白名单见 AgentMetrics.ALLOWED_LABEL_KEYS）";
            throw new IllegalArgumentException(
                    "指标 " + name + " 的标签键非法：" + key + hint);
        }
    }

    /** @return 当前全部计数（序列键 → 值），仅供测试与运维端点。 */
    public @NonNull Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /** @return 某指标当前值（无则 0）。 */
    public long valueOf(@NonNull String name, @NonNull Map<String, String> labels) {
        AtomicLong v = counters.get(seriesKey(name, labels));
        return v == null ? 0L : v.get();
    }

    /** @return 已注册的序列键集合（供 M-6 结构扫描）。 */
    public @NonNull Set<String> seriesKeys() {
        return new LinkedHashSet<>(counters.keySet());
    }

    /**
     * 导出 Prometheus 文本格式（P4-D02 / D-4 / FR-017）。
     *
     * <p><b>⚠️ 刻意不引 Micrometer</b>：引入后标签白名单约束会退化为文档约定
     * （D10 已确立的立场）。本类作为唯一数据源，约束始终在自己手里。
     *
     * <p><b>格式</b>：{@code # HELP} / {@code # TYPE} + 样本行。
     * 序列键格式为 {@code name{k=v,...}}，与 Prometheus 的 label 语法一致。
     *
     * @return Prometheus 文本；无指标时返回仅含注释头的空文本
     */
    public @NonNull String exportPrometheus() {
        StringBuilder sb = new StringBuilder();
        // ⚠️ 按指标名分组输出，使同名的多序列共享一个 TYPE 头
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (String key : counters.keySet()) {
            int brace = key.indexOf('{');
            names.add(brace < 0 ? key : key.substring(0, brace));
        }
        for (String name : names) {
            sb.append("# HELP ").append(name).append(" ").append(name).append("\n");
            sb.append("# TYPE ").append(name).append(" counter\n");
            for (Map.Entry<String, AtomicLong> e : counters.entrySet()) {
                String key = e.getKey();
                int brace = key.indexOf('{');
                if (brace < 0) {
                    if (key.equals(name)) {
                        sb.append(name).append(' ').append(e.getValue().get()).append('\n');
                    }
                    continue;
                }
                if (key.substring(0, brace).equals(name)) {
                    // ⚠️ 键中已是 k=v 形式，直接拼即可
                    sb.append(key.substring(0, brace))
                            .append(key.substring(brace))
                            .append(' ').append(e.getValue().get()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 仅测试用：清空。 */
    public void reset() {
        counters.clear();
    }

    /**
     * 序列键 = {@code name{k=v,k2=v2}}，键<b>排序</b>保证同一组标签只有一个序列。
     */
    private static String seriesKey(String name, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return name;
        }
        Map<String, String> sorted = new java.util.TreeMap<>(labels);
        StringBuilder sb = new StringBuilder(name).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
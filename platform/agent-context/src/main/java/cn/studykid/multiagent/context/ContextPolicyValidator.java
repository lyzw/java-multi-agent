package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * {@code generation_params.context} 的<b>唯一</b>校验实现（ADR-013 / 《方案》§3.2-B、§3.4）。
 *
 * <p>⚠️ <b>发布期与执行期必须调同一份实现</b>：项目既有教训是「发布时判合法、
 * 跑起来判不同」（路由策略 §7 债 5）。本类放在纯算法层，两侧直接调用，
 * 禁止任何一侧自建解析器或另写一套范围常量。
 *
 * <p>⚠️ <b>未声明 = 走全局默认</b>：{@code context} 为 {@code null} / 空对象时
 * 返回空违规列表，旧 Agent 版本行为不变（[D4] 快照固化、在途 Run 不漂移）。
 *
 * <p>⚠️ <b>未知键一律拒绝</b>（fail-closed）：拼错的键若被静默忽略，
 * 运维会以为「已收紧压缩」，实际跑的仍是默认值 —— 这正是本项目反复出现的
 * 「不报错、只是数字悄悄错」形态。
 *
 * <p>⚠️ 硬约束 {@code targetRatio < triggerRatio}：否则压缩后立刻再次触发，
 * 形成抖动风暴（[D3] / RV-5 反向验证点）。
 */
public final class ContextPolicyValidator {

    /** 违规项：field 面向配置作者，message 可直接回显。 */
    public record Violation(@NonNull String field, @NonNull String message) {
    }

    /** 允许出现的键（与《方案》§3.2-B 逐字对齐）。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "enabled", "maxInputTokens", "triggerRatio", "targetRatio", "reserveRatio",
            "recentTurns", "pinFirstUserTurn", "ragRatio", "memoryRatio", "summaryRatio",
            "strategy", "summarizerModel", "summaryMaxTokens", "summaryTimeoutMs",
            "maxCompactionsPerRun", "carryOver", "checkpointTtlSeconds");

    private static final Set<String> STRATEGIES = Set.of("OFF", "TRIM_ONLY", "TIERED");
    private static final Set<String> CARRY_OVER = Set.of("CLIENT", "SERVER");

    private ContextPolicyValidator() {
    }

    /**
     * 校验 context 子对象。
     *
     * @param context 已解析的键值（值类型限定为 Number / Boolean / String / null）
     * @return 违规列表，空 = 合法；<b>不抛异常</b>，由调用方决定拒绝方式
     */
    public static @NonNull List<Violation> validate(@Nullable Map<String, Object> context) {
        List<Violation> out = new ArrayList<>();
        if (context == null || context.isEmpty()) {
            return out;
        }
        for (String key : new LinkedHashSet<>(context.keySet())) {
            if (!ALLOWED_KEYS.contains(key)) {
                out.add(new Violation(key, "未知的压缩策略键（拼写错误或版本不匹配）：" + key));
            }
        }

        bool(context, "enabled", out);
        integer(context, "maxInputTokens", 16_000, 2_048, 1_000_000, out);
        double trigger = number(context, "triggerRatio", 0.70, 0.50, 0.95, out);
        double target = number(context, "targetRatio", 0.40, 0.15, 0.80, out);
        number(context, "reserveRatio", 0.10, 0.03, 0.25, out);
        integer(context, "recentTurns", 6, 2, 20, out);
        bool(context, "pinFirstUserTurn", out);

        double rag = number(context, "ragRatio", 0.30, 0.0, 0.6, out);
        double memory = number(context, "memoryRatio", 0.15, 0.0, 0.6, out);
        double summary = number(context, "summaryRatio", 0.25, 0.0, 0.6, out);
        if (rag + memory + summary > 0.9 + 1e-9) {
            out.add(new Violation("ragRatio/memoryRatio/summaryRatio",
                    "弹性池三项比例之和必须 ≤ 0.9，实际为 " + (rag + memory + summary)));
        }

        enumeration(context, "strategy", STRATEGIES, out);
        text(context, "summarizerModel", out);
        integer(context, "summaryMaxTokens", 1_200, 200, 4_000, out);
        integer(context, "summaryTimeoutMs", 8_000, 1_000, 30_000, out);
        integer(context, "maxCompactionsPerRun", 3, 0, 10, out);
        enumeration(context, "carryOver", CARRY_OVER, out);
        integer(context, "checkpointTtlSeconds", 1_800, 60, 86_400, out);

        if (target >= trigger) {
            out.add(new Violation("targetRatio",
                    "targetRatio 必须严格小于 triggerRatio（否则压完立刻再触发）：target="
                            + target + ", trigger=" + trigger));
        }
        return List.copyOf(out);
    }

    private static double number(Map<String, Object> c, String key, double fallback,
                                 double min, double max, List<Violation> out) {
        Object raw = c.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            out.add(new Violation(key, "必须是有限数值，实际为 " + raw));
            return fallback;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            out.add(new Violation(key, "取值范围 [" + min + ", " + max + "]，实际为 " + v));
        }
        return v;
    }

    private static int integer(Map<String, Object> c, String key, int fallback,
                               int min, int max, List<Violation> out) {
        Object raw = c.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            out.add(new Violation(key, "必须是整数，实际为 " + raw));
            return fallback;
        }
        long v = n.longValue();
        if (v < min || v > max) {
            out.add(new Violation(key, "取值范围 [" + min + ", " + max + "]，实际为 " + v));
        }
        return (int) v;
    }

    private static void bool(Map<String, Object> c, String key, List<Violation> out) {
        Object raw = c.get(key);
        if (raw != null && !(raw instanceof Boolean)) {
            out.add(new Violation(key, "必须是布尔值，实际为 " + raw));
        }
    }

    private static void text(Map<String, Object> c, String key, List<Violation> out) {
        Object raw = c.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String s)) {
            out.add(new Violation(key, "必须是字符串或 null，实际为 " + raw));
        } else if (s.length() > 128) {
            out.add(new Violation(key, "长度不得超过 128，实际为 " + s.length()));
        }
    }

    private static void enumeration(Map<String, Object> c, String key, Set<String> allowed,
                                    List<Violation> out) {
        Object raw = c.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String s) || !allowed.contains(s)) {
            out.add(new Violation(key, "合法值为 " + allowed + "，实际为 " + raw));
        }
    }
}

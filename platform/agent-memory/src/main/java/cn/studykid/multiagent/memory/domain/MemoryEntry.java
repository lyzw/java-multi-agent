package cn.studykid.multiagent.memory.domain;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一条跨 Run 记忆（领域值对象，**零框架依赖**）。
 *
 * <p><b>与 {@code weakness_record} 的区别（本方案的核⼼判断）</b>：
 * <ul>
 *   <li>{@code weakness_record} 是<b>事件日志</b>：一次 Run 一条，幂等键含 {@code run_id}，
 *       语义是"这次错了"。用途是审计、统计、当次证据。</li>
 *   <li>本类型是<b>记忆条目</b>：一个学生一个知识点一条（聚合），幂等键
 *       不含 {@code run_id}，语义是"他总是错这里，强度 0.8"。用途是<b>召回进上下文</b>。</li>
 * </ul>
 *
 * <p><b>为什么不在事件表上加 strength 列</b>：事件表的幂等键含 {@code run_id}，
 * 一个知识点每次 Run 都产生新行。在事件表上做聚合等于每次读都要
 * {@code GROUP BY} + 窗口函数，既慢又无法承载"上次整合时间"。
 * <b>事件表只追加，记忆表承载聚合</b> —— 标准的 event-sourcing 拆分。
 *
 * <p><b>为什么 {@code studentId} 存明文而不是哈希</b>：见
 * {@code docs/Agent记忆机制分析与设计方案.md} §3.3.2。简言之：
 * 哈希在本域不提供保护（同租户内可关联），却会破坏
 * {@code ON DELETE CASCADE} —— 撤回删除时只能靠应用层"记得删"，
 * 而这正是"机制正确但漏接"的高发点。
 *
 * <p>⚠️ <b>本类不校验保留期</b>（属 {@code agent-application} 的
 * {@code RetentionPolicy}），也不计算强度（属 {@link MemoryStrength}）。
 */
public record MemoryEntry(
        @NonNull String studentId,
        @NonNull String knowledgePoint,
        @NonNull String errorType,
        double strength,
        int evidenceCount,
        @NonNull Instant firstSeenAt,
        @NonNull Instant lastSeenAt,
        @Nullable String lastRunId) {

    /** 学生标识格式（与 {@code student_profile_student_id_format_chk} 同源）。 */
    public static final Pattern STUDENT_ID = Pattern.compile("^stu_[a-z0-9]{16}$");

    /** 知识点长度上限（与 {@code weakness_kp_length_chk} 同源）。 */
    public static final int MAX_KNOWLEDGE_POINT_CHARS = 100;

    /**
     * 允许的错误类型（与 {@code weakness_error_type_chk} 的 6 值枚举同源）。
     *
     * <p>⚠️ <b>这是唯一权威清单</b>：DDL 的 CHECK 约束、{@code record_weakness}
     * 工具的 JSON Schema、以及记忆整合都必须引用同一份取值。
     * 三处各写一遍的结果是"工具能写、库拒绝"或"库里有、整合时被丢弃"。
     */
    public static final Set<String> ERROR_TYPES = Set.of(
            "概念不清", "公式记错", "符号错误", "计算失误", "审题偏差", "步骤缺失");

    public MemoryEntry {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("学生标识不可为空");
        }
        if (!STUDENT_ID.matcher(studentId).matches()) {
            throw new IllegalArgumentException(
                    "学生标识格式不合法（应为 stu_ 前缀 + 16 位小写十六进制）: " + studentId);
        }
        if (knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("知识点不可为空");
        }
        String kp = knowledgePoint.strip();
        if (kp.length() > MAX_KNOWLEDGE_POINT_CHARS) {
            throw new IllegalArgumentException(
                    "知识点长度超出上限 " + MAX_KNOWLEDGE_POINT_CHARS + " 字符: " + kp.length());
        }
        if (errorType == null || !ERROR_TYPES.contains(errorType)) {
            throw new IllegalArgumentException("错误类型不在允许取值内: " + errorType);
        }
        if (Double.isNaN(strength) || strength < 0.0 || strength > 1.0) {
            throw new IllegalArgumentException("记忆强度必须落在 [0, 1]: " + strength);
        }
        if (evidenceCount < 1) {
            throw new IllegalArgumentException("证据计数必须 ≥ 1，收到: " + evidenceCount);
        }
        if (firstSeenAt == null || lastSeenAt == null) {
            throw new IllegalArgumentException("首次/最后出现时间不可为空");
        }
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("最后出现时间早于首次出现时间");
        }
        // 归一化：知识点两端空白会让"同一条记忆"因录入差异裂成两条
        knowledgePoint = kp;
    }

    /**
     * 该记忆在 {@code now} 时刻的<b>实时强度</b>（读时衰减）。
     *
     * <p>⚠️ 不要用本对象的 {@link #strength()} 直接做召回判定：
     * 那是<b>写入时</b>的强度，可能已经过期数月。
     * 若靠"夜里跑批刷新"，白天读到的就是旧值 ——
     * 衰减必须发生在读取路径上。
     *
     * @param now 当前时刻
     * @return 实时强度
     */
    public double liveStrength(@NonNull Instant now) {
        return MemoryStrength.compute(evidenceCount, lastSeenAt, now);
    }

    /**
     * 是否达到召回门槛（领域下限）。
     *
     * @param now 当前时刻
     * @return 是否可召回
     */
    public boolean recallable(@NonNull Instant now) {
        return MemoryStrength.isRecallable(liveStrength(now));
    }

    /**
     * 是否满足"自然遗忘"条件。
     *
     * @param now 当前时刻
     * @return 是否可清理
     */
    public boolean naturallyForgotten(@NonNull Instant now) {
        return MemoryStrength.isNaturallyForgotten(evidenceCount, lastSeenAt, now);
    }

    /**
     * 渲染为**上下文片段**（供系统提示使用）。
     *
     * <p>⚠️ <b>只输出调用方已经知道的维度之外的信息</b>：
     * {@code get_student_profile} 工具已经能返回 knowledge_point / error_type /
     * confidence / created_at，故这里刻意<b>不重复</b>这些，
     * 只补"出现次数、当前强度、最近一次时间"——这三项工具查不到，
     * 而它们正是"该不该把这条当稳定弱点"的依据。
     *
     * <p>⚠️ 输出<b>不含</b>任何换行与围栏标记：围栏由调用方（PromptGuard）
     * 统一包裹，片段自己带围栏会造成"围栏里套围栏"，
     * 且让 {@code </memory_context>} 提前闭合。
     *
     * @param now 当前时刻
     * @return 单行片段，形如 {@code 一元二次方程｜概念不清｜出现 3 次｜强度 0.62｜最近 12 天前}
     */
    public String renderLine(@NonNull Instant now) {
        long daysAgo = Math.max(0L, java.time.Duration.between(lastSeenAt, now).toDays());
        return knowledgePoint
                + "｜" + errorType
                + "｜出现 " + evidenceCount + " 次"
                + "｜强度 " + String.format(java.util.Locale.ROOT, "%.2f", liveStrength(now))
                + "｜最近 " + daysAgo + " 天前";
    }

    /**
     * 渲染所需的估算 token 数上限（供预算扣减使用）。
     *
     * <p>⚠️ 用<b>保守</b>估算：中文按 1 字 1 token 计，其余按 3 字符 1 token。
     * 与 {@code ContextBudgetTrimmer} 的口径一致，但本类不能依赖它
     * （那个类在 agent-worker，依赖方向不允许 core → worker）。
     *
     * @param now 当前时刻
     * @return 估算 token 数
     */
    public int estimatedTokens(@NonNull Instant now) {
        return estimateTokens(renderLine(now));
    }

    /**
     * 与 {@code ContextBudgetTrimmer.estimateTokens(String)} 同口径的粗估。
     *
     * @param text 文本
     * @return 估算 token 数
     */
    public static int estimateTokens(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int codePoints = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            codePoints++;
            if (isWideScript(cp)) {
                cjk++;
            }
        }
        int others = codePoints - cjk;
        return cjk + (others + 2) / 3;
    }

    /**
     * 是否属于"宽字符脚本"（中日韩等，1 字 ≈ 1 token）。
     *
     * @param cp 码点
     * @return 是否宽字符
     */
    private static boolean isWideScript(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK 扩展 A
                || (cp >= 0x3040 && cp <= 0x30FF) // 平假名 / 片假名
                || (cp >= 0xAC00 && cp <= 0xD7AF) // 谚文
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK 兼容表意
                || (cp >= 0x20000 && cp <= 0x2FA1F); // CJK 扩展 B+
    }
}

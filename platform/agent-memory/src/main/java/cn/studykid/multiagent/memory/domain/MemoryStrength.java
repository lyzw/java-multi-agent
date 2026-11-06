package cn.studykid.multiagent.memory.domain;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 记忆强度模型（衰减 + 增强）——**纯函数，零框架依赖**。
 *
 * <p><b>为什么放在 agent-core 而不是 agent-application</b>：
 * 同一个强度公式会被三方同时使用 ——
 * {@code JdbcMemoryRepository}（写入时落库）、{@code MemoryRecallService}
 * （读取时按"当前时间"再算一次）、以及保留期清理（判定"自然遗忘"）。
 * 只要有一处自己抄一遍公式，两边迟早漂移，而漂移的表现是
 * 「召回的内容与强度排序对不上」——很难定位。
 * 放进 core 才能被三层同时依赖且不违反依赖方向
 * （{@code ModuleDependencyDirectionTest} 只允许 application → core/ports）。
 *
 * <p>⚠️ <b>本类不做保留期校验</b>：保留期属合规口径，由
 * {@code agent-application} 的 {@code RetentionPolicy} 持有 yml 原始值并
 * fail-closed 校验。这里只管"记忆有多强、该不该召回、该不该自然遗忘"。
 *
 * <h2>公式</h2>
 * <pre>
 * strength(t) = clamp( base(n) * e^(-λ * days_since_last_seen), 0, 1 )
 * base(n)     = 1 - e^(-0.35 * n)      // n = evidenceCount（**不同 Run** 的出现次数）
 * λ           = 0.01                   // 半衰期 ≈ 69 天
 * </pre>
 *
 * <table border="1">
 *   <caption>base(n) 取值（当天，未衰减）</caption>
 *   <tr><th>n</th><th>base(n)</th><th>能否进上下文（≥ 0.40）</th></tr>
 *   <tr><td>1</td><td>0.295</td><td><b>否</b></td></tr>
 *   <tr><td>2</td><td>0.503</td><td>是</td></tr>
 *   <tr><td>3</td><td>0.650</td><td>是</td></tr>
 *   <tr><td>5</td><td>0.826</td><td>是</td></tr>
 * </table>
 *
 * <p><b>为什么 {@code n = 1} 永远进不了上下文</b>：这是防「一次错误结论被
 * 固化放大」的第一道闸。模型在一次 Run 里偶然记错一个知识点，如果下次
 * 就直接把它当"这个学生的历史弱点"喂回去，错误会被自我强化（R-2）。
 * 要求至少两次**不同 Run** 的独立证据，才允许进入上下文。
 *
 * <p><b>为什么 λ 取 0.01 而非阶段二设计里的 0.05</b>：0.05 的半衰期只有
 * 13.9 天，连"错了 5 次"的记忆在 30 天后也会掉到 0.18（低于召回线）——
 * 那就不是长期记忆，而是"最近一周记忆"。0.01 下 {@code n = 5} 在 180 天
 * 时衰减到约 0.136，<b>刚好与 180 天自然遗忘阈值 0.15 咬合</b>，
 * 两条规则在同一时间点收敛，不会出现"该忘的没忘 / 不该忘的忘了"。
 *
 * <p><b>真正维持记忆的是"再次出现"，不是衰减公式</b>：学生在同一知识点上
 * 再错一次 → {@code last_seen_at} 刷新 → 强度回到 {@code base(n + 1)}。
 * 衰减只负责处理"很久没再出现"。
 */
public final class MemoryStrength {

    /**
     * 自然遗忘线：强度低于此值<b>且</b>超过 {@link #NATURAL_FORGET_DAYS} 天
     * 未再出现 → 可清理（属"自然遗忘"，与保留期解耦）。
     */
    public static final double MIN_STRENGTH = 0.15;

    /**
     * 召回门槛：强度低于此值的历史记忆<b>不进上下文</b>。
     *
     * <p>⚠️ 该常量是"可召回"的<b>领域下限</b>；运行时实际门槛由
     * {@code agent.memory.recall-min-strength}（默认同值）决定，
     * 且只允许在 {@link #MIN_STRENGTH} 与 1 之间上调 —— 下调到
     * {@link #MIN_STRENGTH} 以下等于"让即将被遗忘的记忆继续影响判断"。
     */
    public static final double RECALL_MIN_STRENGTH = 0.40;

    /**
     * 新建记忆条目的初始强度 = {@code base(1)}（保留两位小数）。
     *
     * <p>与 {@code student_memory.strength} 列的 {@code DEFAULT 0.30} 同源：
     * 建表默认值与代码常量必须是同一个数，否则"经代码写入"和
     * "经 DDL 默认值写入"会产生两种强度。
     */
    public static final double DEFAULT_STRENGTH = 0.30;

    /** 增强系数：{@code base(n) = 1 - e^(-BASE_RATE * n)}。 */
    public static final double BASE_RATE = 0.35;

    /** 衰减系数 λ。半衰期 = {@code ln(2) / λ ≈ 69.3} 天。 */
    public static final double DECAY_LAMBDA = 0.01;

    /** 自然遗忘的"久未出现"天数门槛。 */
    public static final long NATURAL_FORGET_DAYS = 180L;

    /** 一天。 */
    private static final double SECONDS_PER_DAY = 86_400.0;

    private MemoryStrength() {
    }

    /**
     * 计算"当天未衰减"的强度基线。
     *
     * @param evidenceCount 不同 Run 中出现次数；{@code < 1} 按 1 处理
     * @return {@code 1 - e^(-0.35 * n)}，落在 {@code [0.295, 1)}
     */
    public static double base(int evidenceCount) {
        int n = Math.max(1, evidenceCount);
        return 1.0 - Math.exp(-BASE_RATE * n);
    }

    /**
     * 计算记忆在 {@code now} 时刻的强度（读时衰减）。
     *
     * <p>⚠️ <b>必须支持 {@code lastSeenAt} 为 {@code null}</b>（历史数据/迁移缺口）：
     * 此时按"从未再出现"处理，取 {@link #NATURAL_FORGET_DAYS} 天前的衰减结果 ——
     * 即<b>最悲观</b>的强度。理由同本类整体纪律：宁可少召回，不可多召回。
     *
     * @param evidenceCount 不同 Run 中出现次数
     * @param lastSeenAt    最后一次出现时间；{@code null} = 未知（按最悲观处理）
     * @param now           当前时刻
     * @return 强度，落在 {@code [0, 1]}
     */
    public static double compute(int evidenceCount, @Nullable Instant lastSeenAt, @NonNull Instant now) {
        double baseValue = base(evidenceCount);
        double days = daysSince(lastSeenAt, now);
        double decayed = baseValue * Math.exp(-DECAY_LAMBDA * days);
        return clamp01(decayed);
    }

    /**
     * 同 {@link #compute}，但按 {@code student_memory.strength numeric(3,2)} 的
     * 精度<b>四舍五入到两位小数</b>。
     *
     * <p>写入时用本方法，读取判定时用 {@link #compute}（不取整）。
     * 两者关系是：<b>存储值恒 ≥ 重算值</b>（衰减单调递减），
     * 故"库里有、但重算后不够线"的条目只会被<b>多剔除</b>，不会被误召回。
     *
     * @param evidenceCount 不同 Run 中出现次数
     * @param lastSeenAt    最后一次出现时间；{@code null} = 按最悲观处理
     * @param now           当前时刻
     * @return 两位小数的强度
     */
    public static double computeRounded(int evidenceCount, @Nullable Instant lastSeenAt, @NonNull Instant now) {
        return Math.round(compute(evidenceCount, lastSeenAt, now) * 100.0) / 100.0;
    }

    /**
     * 判断某强度是否达到召回门槛（领域下限 {@link #RECALL_MIN_STRENGTH}）。
     *
     * @param strength 强度
     * @return 是否可召回
     */
    public static boolean isRecallable(double strength) {
        return isRecallable(strength, RECALL_MIN_STRENGTH);
    }

    /**
     * 判断某强度是否达到<b>运行时</b>召回门槛。
     *
     * @param strength 强度
     * @param threshold 运行时门槛（调用方负责校验其不低于 {@link #MIN_STRENGTH}）
     * @return 是否可召回
     */
    public static boolean isRecallable(double strength, double threshold) {
        return strength >= threshold;
    }

    /**
     * 判断是否满足"自然遗忘"条件（强度过低<b>且</b>久未再出现）。
     *
     * <p>⚠️ 两个条件必须<b>同时</b>成立：只满足强度低 → 可能是刚衰减下来的
     * 活跃记忆（比如学生半年没练这个点了，但随时会回来）；只满足时间久 →
     * 强度可能仍然很高（错了 10 次的知识点，180 天后强度还有 0.16）。
     * 任一单条件都会误删。
     *
     * @param evidenceCount 不同 Run 中出现次数
     * @param lastSeenAt    最后一次出现时间；{@code null} = 从未出现（视为满足时间条件）
     * @param now           当前时刻
     * @return 是否可自然遗忘
     */
    public static boolean isNaturallyForgotten(int evidenceCount,
                                               @Nullable Instant lastSeenAt,
                                               @NonNull Instant now) {
        // ⚠️ {@code lastSeenAt == null} 必须<b>直接</b>判定为"满足时间条件"：
        //    若靠 {@code daysSince} 返回的 180 再做 {@code > 180} 比较，
        //    相等会判 false —— 一个"从未再出现"的记忆反而永远删不掉。
        //    （这是实现时踩过的坑：两个常量同值，比较符把边界吃掉了。）
        boolean longEnough = lastSeenAt == null
                || daysSince(lastSeenAt, now) > NATURAL_FORGET_DAYS;
        return longEnough && compute(evidenceCount, lastSeenAt, now) < MIN_STRENGTH;
    }

    /**
     * 半衰期（天）：{@code ln(2) / λ}。
     *
     * <p>供文档与测试引用，避免"公式改了但注释没改"。
     *
     * @return 半衰期天数
     */
    public static double halfLifeDays() {
        return Math.log(2) / DECAY_LAMBDA;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 允许的"未来时间戳"容差。
     *
     * <p>⚠️ <b>为什么需要容差而不是"未来就按 180 天算"</b>：
     * 写入路径上 {@code last_seen_at} 与读取路径上的 {@code now}
     * 来自<b>不同的时钟采样点</b>（写库那次的 {@code clock.instant()}，
     * 与这次召回的 {@code clock.instant()}）。同一秒内先写后读，
     * 差值完全可能为 0 甚至毫秒级负数 —— 若判为"未来"并按 180 天衰减，
     * <b>刚产生的高强度记忆会当场变成 0.107，永远无法被召回</b>。
     * 这个 bug 的症状是"记忆功能看起来完全不生效"，很难定位。
     *
     * <p>容差取 5 分钟：足以覆盖写读之间的时钟粒度差与轻微 NTP 校正；
     * 又远小于衰减的时间尺度（半衰期 69 天），因此不影响"时钟回拨必须
     * 按最悲观处理"这条纪律 —— 真正的时钟错乱通常是小时/天级。
     */
    private static final long FUTURE_TOLERANCE_SECONDS = 300L;

    /**
     * 距上次出现的天数。
     *
     * <p>⚠️ 三种边界，各有明确取舍：
     * <ul>
     *   <li>{@code lastSeenAt == null}（迁移缺口/脏数据）→ 按
     *       {@link #NATURAL_FORGET_DAYS} 天，<b>最悲观</b>；</li>
     *   <li>落在未来但<b>在容差内</b>（写读时钟粒度差）→ 按 <b>0 天</b>，
     *       即"刚刚出现"，不衰减；</li>
     *   <li>落在未来<b>超出容差</b>（时钟回拨/错误数据）→ 按
     *       {@link #NATURAL_FORGET_DAYS} 天。<b>不能</b>按 0 天算：
     *       一次错误的时钟回拨会把所有记忆强度顶满，等于绕过全部衰减。</li>
     * </ul>
     */
    private static double daysSince(@Nullable Instant lastSeenAt, @NonNull Instant now) {
        if (lastSeenAt == null) {
            return (double) NATURAL_FORGET_DAYS;
        }
        Duration elapsed = Duration.between(lastSeenAt, now);
        if (elapsed.isNegative()) {
            long aheadSeconds = -elapsed.getSeconds();
            // 容差内：视为"刚刚出现"，按 0 天（不衰减）
            return aheadSeconds <= FUTURE_TOLERANCE_SECONDS ? 0.0 : (double) NATURAL_FORGET_DAYS;
        }
        return elapsed.getSeconds() / SECONDS_PER_DAY;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}

package cn.studykid.multiagent.infrastructure.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子比对 / 染色的<b>采样策略</b>（12 文 §7.1 R7 · 13 文 §5.2）。
 *
 * <p>⚠️ <b>为什么需要本类，而不是简单「按 0.1% 随机丢样本」</b>：
 * <ul>
 *   <li><b>纯随机会系统性漏掉「小流量租户 / 冷门资源」</b> —— 而越权恰恰更容易出现在
 *       少人走的路径上（导出、跨租户读、后台补偿）。随机采样对这类路径的覆盖率趋近于 0，
 *       却仍会给出「AUTHZ_MISMATCH=0」这个看起来很安心的读数。</li>
 *   <li><b>纯随机不可复现</b> —— 同一个 mismatch 复现不出来就无法定位，
 *       只能靠「再跑一次碰运气」。</li>
 * </ul>
 *
 * <p>因此本类采用<b>确定性分桶</b>：
 * <pre>
 *   bucket = floor(now / windowMillis)
 *   h      = mix(hash(subjectKey), hash(resourceType), bucket)
 *   sample = (h mod 65536) &lt; rate * 65536
 * </pre>
 * 同一时间窗内同一 (主体, 资源) 的采样结论<b>恒定</b>（可复现、可写断言），
 * 且窗口滚动后自动换一批样本 —— 长期看<b>每个 (主体, 资源) 都会被采到</b>，
 * 而不是「运气不好就永远不采」。
 *
 * <p><b>高敏资源永远 100%</b>：{@code alwaysSample} 里的资源类型（导出/检索/统计/跨租户读）
 * 直接返回 {@code true}，与 {@code rate} 无关（13 文 §5.2 验收项「高敏接口 100% 染色」）。
 *
 * <p>⚠️ <b>采样作用于「整次查询」而不是「单个结果行」</b>：一旦某次查询被采样，
 * 它内部的每一行都会逐行染色，违例<b>不会</b>被采样丢掉；被跳过的只是整次查询。
 * 这是成本与覆盖的折中（逐行染色要 new {@code TaintVerifier}/{@code RowAttribution}，
 * 大结果集上是可观测开销），用「高敏 100% + 窗口滚动」来补偿覆盖。
 */
public final class AuthzSamplingPolicy {

    /** 分桶模数 —— 2 的幂，便于用位与替代取模。 */
    private static final long BUCKETS = 65_536L;

    /** 完全关闭（不采样任何非高敏资源；高敏仍 100%）。 */
    public static final AuthzSamplingPolicy DISABLED =
            new AuthzSamplingPolicy(0.0d, 300_000L, Set.of(), System::currentTimeMillis);

    private final double rate;
    private final long windowMillis;
    private final Set<String> alwaysSample;
    private final LongSupplier clock;

    /**
     * @param rate          采样率，{@code [0,1]}；{@code >=1} 表示全采
     * @param windowMillis  分桶窗口（毫秒），必须为正
     * @param alwaysSample  永远采样的高敏资源类型（大小写敏感，与仓储传入的字面量一致）
     * @param clock         时钟（测试注入，避免依赖真实时间）
     */
    public AuthzSamplingPolicy(double rate,
                               long windowMillis,
                               @NonNull Set<String> alwaysSample,
                               @NonNull LongSupplier clock) {
        if (Double.isNaN(rate) || rate < 0.0d) {
            throw new IllegalArgumentException("采样率必须 >= 0: " + rate);
        }
        if (windowMillis <= 0L) {
            throw new IllegalArgumentException("采样窗口必须为正: " + windowMillis);
        }
        this.rate = Math.min(1.0d, rate);
        this.windowMillis = windowMillis;
        this.alwaysSample = Set.copyOf(new LinkedHashSet<>(alwaysSample));
        this.clock = clock;
    }

    /** 便捷构造：使用系统时钟。 */
    public static @NonNull AuthzSamplingPolicy of(double rate,
                                                 long windowMillis,
                                                 @NonNull Set<String> alwaysSample) {
        return new AuthzSamplingPolicy(rate, windowMillis, alwaysSample, System::currentTimeMillis);
    }

    /**
     * 解析逗号分隔的高敏资源清单（配置项形态，空串即空集）。
     *
     * <p>⚠️ 不在这里做 trim 之外的处理：资源类型是仓储传入的<b>字面量</b>，
     * 大小写与分隔符必须完全一致，否则「配了高敏却永远不采」是静默失效。
     */
    public static @NonNull Set<String> parseAlwaysSample(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 本次查询是否要做影子比对 / 染色。
     *
     * @param resourceType 仓储传入的资源类型字面量（如 {@code agent}、{@code run}）
     * @param subjectKey   采样键（通常是主体租户；为 {@code null} 时按空串参与哈希）
     */
    public boolean shouldSample(@NonNull String resourceType, @Nullable String subjectKey) {
        if (alwaysSample.contains(resourceType)) {
            return true;
        }
        if (rate <= 0.0d) {
            return false;
        }
        if (rate >= 1.0d) {
            return true;
        }
        long bucket = Math.floorDiv(clock.getAsLong(), windowMillis);
        // ⚠️ 只对齐大小写，不做 trim/规范化：采样键必须与判定键完全一致，否则「配了却没采」
        String key = subjectKey == null ? "" : subjectKey.toLowerCase(Locale.ROOT);
        long h = mix(hash(key), hash(resourceType), bucket);
        long threshold = (long) (rate * BUCKETS);
        return (h & (BUCKETS - 1)) < threshold;
    }

    public double rate() {
        return rate;
    }

    public long windowMillis() {
        return windowMillis;
    }

    public @NonNull Set<String> alwaysSample() {
        return alwaysSample;
    }

    // ── 哈希（SplitMix64 变体；只为打散，不用于安全用途） ─────────────
    private static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h, 0x9e3779b97f4a7c15L, s.length());
    }

    private static long mix(long a, long b, long c) {
        long z = a + 0x9e3779b97f4a7c15L * (b + 1) + c;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}

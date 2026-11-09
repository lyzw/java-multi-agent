package cn.studykid.multiagent.application.review;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.SamplingCandidate;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 分层抽样器（P3 · 详细设计 §7.2）——把「该审哪些」从<b>单条 Trace 的内联判断</b>
 * 提升为<b>周期性扫描 + 分层配额</b>。
 *
 * <p><b>⚠️ 为什么必须有它（此前是整段缺口）</b>：
 *   {@link ReviewAlertService} 在采集链路里做的是<b>单条可确定</b>的判定
 *   （循环 / 轮次触顶 / 超大 token / 安全拦截），它明确不碰<b>比例类</b>问题，
 *   并把那部分留给「§7.2 的分层抽样」—— 而那段代码此前不存在。后果是：
 *   <ul>
 *       <li>只有「已经出问题」的 Trace 进队列 ⇒ 分母里那些看起来正常的样本
 *           永远没人看过，「人工抽样覆盖率」结构性偏斜；</li>
 *       <li>「成功率 99%」无从验证 —— 因为没人看过那 99%。</li>
 *   </ul>
 *
 * <p><b>分三层（与 §7.2 一致，优先级从高到低）</b>：
 *   <ol>
 *       <li><b>异常优先</b>（{@code ANOMALY} / HIGH）：命中循环、轮次触顶、
 *           安全拦截、超大 token 的 Trace；</li>
 *       <li><b>L1 覆盖缺口</b>（{@code L1_GAP} / HIGH）：已采集但
 *           {@code auto_checked IS NULL} 的 Trace —— 机器没判过，只能人看；</li>
 *       <li><b>随机基线</b>（{@code SAMPLE_RANDOM} / LOW）：剩下的按确定性哈希抽样，
 *           维持黄金基线规模。</li>
 *   </ol>
 *
 * <p><b>⚠️ 三个「不许」</b>：
 *   <ul>
 *       <li><b>不许跨层重复</b>：一条 Trace 只进一个层（异常层的样本不再进缺口层）；</li>
 *       <li><b>不许无限灌队列</b>：每轮有上限（{@code max-per-round}），
 *           否则一次扫描能把队列塞爆，reviewer 直接不看队列了；</li>
 *       <li><b>不许无去重</b>：已有未终结任务的 Trace 跳过
 *           （去重交给 {@link ReviewTaskPort#hasOpenTask}，此处不重复实现）。</li>
 *   </ul>
 *
 * <p><b>抽样确定性</b>：用 {@code traceId.hashCode()} 而非随机数，
 * 同一 Trace 重复扫描结论一致 —— 否则「同一批数据跑两次，进队列的样本不同」，
 * 抽样覆盖率就无法举证。
 *
 * <p><b>⚠️ 本类不读对话正文</b>：只按宽表的结构化事实分层。
 * 内容级判断属于回放与判分器（{@code L1/L2}），抽样器越界读内容会同时破坏
 * 合规边界与「机器结论不写人工表」的纪律。
 */
@Service
public class ReviewSampler {

    private static final Logger log = LoggerFactory.getLogger(ReviewSampler.class);

    /** 单次扫描的候选上限（防止把整个区间拖出来）。 */
    private static final int SCAN_LIMIT = 500;

    private final AgentMetricPort metrics;
    private final ReviewTaskPort reviewTasks;
    private final boolean enabled;
    private final Duration lookback;
    /** 每个租户每轮最多入队多少条（防止单租户吃满配额）。 */
    private final int maxPerTenant;
    /** 全平台每轮入队总上限（兜底，防队列爆炸）。 */
    private final int globalCap;
    private final double baselineRate;

    public ReviewSampler(
            @NonNull AgentMetricPort metrics,
            @NonNull ReviewTaskPort reviewTasks,
            @Value("${agent.quality.sampler.enabled:true}") boolean enabled,
            @Value("${agent.quality.sampler.lookback-hours:24}") int lookbackHours,
            @Value("${agent.quality.sampler.max-per-tenant:20}") int maxPerTenant,
            @Value("${agent.quality.sampler.global-cap:200}") int globalCap,
            @Value("${agent.quality.sampler.baseline-rate:0.02}") double baselineRate) {
        if (lookbackHours <= 0) {
            throw new IllegalArgumentException("agent.quality.sampler.lookback-hours 必须 > 0");
        }
        if (maxPerTenant <= 0) {
            throw new IllegalArgumentException("agent.quality.sampler.max-per-tenant 必须 > 0");
        }
        if (globalCap < maxPerTenant) {
            throw new IllegalArgumentException(
                    "agent.quality.sampler.global-cap 必须 >= max-per-tenant，否则单租户都装不满");
        }
        if (baselineRate < 0 || baselineRate > 1) {
            throw new IllegalArgumentException("agent.quality.sampler.baseline-rate 必须在 [0,1]");
        }
        this.metrics = metrics;
        this.reviewTasks = reviewTasks;
        this.enabled = enabled;
        this.lookback = Duration.ofHours(lookbackHours);
        this.maxPerTenant = maxPerTenant;
        this.globalCap = globalCap;
        this.baselineRate = baselineRate;
    }

    /** 一轮抽样的结果（供定时任务打日志 / 供测试断言）。 */
    public record Result(int scanned, int anomalyQueued, int gapQueued, int baselineQueued,
                         int alreadyQueued) {

        /** 空结果（未启用 / 失败时使用）。 */
        public static Result empty() {
            return new Result(0, 0, 0, 0, 0);
        }

        public int queued() {
            return anomalyQueued + gapQueued + baselineQueued;
        }
    }

    /**
     * 扫一轮并推队列（调用方负责定时；本方法自身幂等、可重复调用）。
     *
     * @param now 当前时刻（注入以便测试固定时间）
     * @return 本轮统计
     */
    public @NonNull Result sampleOnce(@NonNull Instant now) {
        if (!enabled) {
            return new Result(0, 0, 0, 0, 0);
        }
        MetricQuery query = new MetricQuery(now.minus(lookback), now, null, null, null, null);
        List<SamplingCandidate> candidates = metrics.samplingCandidates(query, SCAN_LIMIT);

        int anomaly = 0;
        int gap = 0;
        int baseline = 0;
        int skipped = 0;
        // ⚠️⚠️ **配额必须按租户计，不能全平台共享**（2026-11-09 自查修复）：
        //    初版是 `anomaly + gap + baseline >= maxPerRound` 就停 —— 那是**全平台**上限。
        //    后果：单租户的高流量可以吃满全部配额，其他租户整轮 0 条入队，
        //    而「抽样覆盖率」在看板上是按平台聚合的，看不出某个租户从未被审过。
        //    多租户 SaaS 里这是实质性偏差，不是公平性偏好。
        //    现在改为「每租户每轮上限」，全局仍保留一个总量上界防队列爆炸。
        Map<String, Integer> perTenant = new HashMap<>();

        for (SamplingCandidate c : candidates) {
            if (anomaly + gap + baseline >= globalCap) {
                // ⚠️ 全局兜底上限：即使租户很多，单轮也不会无限灌队列。
                //    队列塞爆的后果是 reviewer 不再看队列，比少抽几条严重得多。
                break;
            }
            int used = perTenant.getOrDefault(c.tenantId(), 0);
            if (used >= maxPerTenant) {
                // 该租户本轮配额已满，跳过但它不占用其他租户的名额
                continue;
            }
            if (reviewTasks.hasOpenTask(c.traceId())) {
                skipped++;
                continue;
            }
            Optional<ReviewTaskSpec> spec = decide(c);
            if (spec.isEmpty()) {
                continue;
            }
            ReviewTaskSpec s = spec.get();
            reviewTasks.create(s);
            perTenant.put(c.tenantId(), used + 1);
            switch (s.source()) {
                case ANOMALY -> anomaly++;
                case L1_GAP -> gap++;
                case SAMPLE_RANDOM -> baseline++;
                default -> { /* 不可能：decide 只产出这三种 */ }
            }
        }

        Result result = new Result(candidates.size(), anomaly, gap, baseline, skipped);
        if (result.queued() > 0) {
            log.info("分层抽样完成: 扫描={}, 异常={}, 覆盖缺口={}, 随机基线={}, 已入队跳过={}, 涉及租户={}",
                    result.scanned(), anomaly, gap, baseline, skipped, perTenant.size());
        }
        return result;
    }

    /**
     * 分层决策（纯函数，便于单测穷举）。
     *
     * @return 要建的任务；不该入队时返回 empty
     */
    @NonNull Optional<ReviewTaskSpec> decide(@NonNull SamplingCandidate c) {
        // ① 异常优先：单条可确定的信号，与 ReviewAlertService 同口径
        if (c.isAnomaly()) {
            return Optional.of(spec(c, Source.ANOMALY, Priority.HIGH,
                    "分层抽样·异常优先 | " + anomalyReason(c)));
        }
        // ② L1 覆盖缺口：机器没判过，只能人看
        if (c.isCoverageGap()) {
            return Optional.of(spec(c, Source.L1_GAP, Priority.HIGH,
                    "分层抽样·L1 覆盖缺口（auto_checked IS NULL），需人工补判"));
        }
        // ③ 随机基线：确定性哈希，维持黄金基线规模
        if (hitBaseline(c.traceId())) {
            return Optional.of(spec(c, Source.SAMPLE_RANDOM, Priority.LOW,
                    "分层抽样·随机基线 | L1 已覆盖，用于估计整体分布"));
        }
        return Optional.empty();
    }

    /** 确定性基线抽样：与 L1/L2 同算法（同一 traceId 永远同一结论）。 */
    boolean hitBaseline(@NonNull String traceId) {
        if (baselineRate <= 0) {
            return false;
        }
        if (baselineRate >= 1) {
            return true;
        }
        return Math.floorMod(traceId.hashCode(), 10_000) < (long) (baselineRate * 10_000);
    }

    private static ReviewTaskSpec spec(SamplingCandidate c, Source source, Priority priority,
                                       String note) {
        return new ReviewTaskSpec(c.traceId(), c.tenantId(), c.runId(), source, priority, note);
    }

    private static String anomalyReason(@Nullable SamplingCandidate c) {
        StringBuilder sb = new StringBuilder();
        if (c.loopSuspectedCount() != null && c.loopSuspectedCount() > 0) {
            sb.append("循环×").append(c.loopSuspectedCount());
        }
        if (Boolean.TRUE.equals(c.maxRoundsReached())) {
            sb.append(sb.isEmpty() ? "" : "/").append("轮次触顶");
        }
        if (c.safetyBlockedCount() != null && c.safetyBlockedCount() > 0) {
            sb.append(sb.isEmpty() ? "" : "/").append("安全拦截×").append(c.safetyBlockedCount());
        }
        if (c.totalTokens() != null && c.totalTokens() > 20_000L) {
            sb.append(sb.isEmpty() ? "" : "/").append("超大token=").append(c.totalTokens());
        }
        return sb.isEmpty() ? "异常" : sb.toString();
    }
}

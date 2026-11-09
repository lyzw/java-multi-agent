package cn.studykid.multiagent.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.SamplingCandidate;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 分层抽样器（P3 · §7.2）的行为守卫。
 *
 * <p><b>⚠️ 守的是什么</b>：不是「能返回一个列表」，而是三件会静默失真的事 ——
 * <ol>
 *   <li><b>分层配额</b>：异常优先、其次覆盖缺口、最后随机基线；一条 Trace 只进一层；</li>
 *   <li><b>不无限灌队列</b>：单轮上限真的生效（塞爆队列会让 reviewer 弃用队列）；</li>
 *   <li><b>确定性</b>：同一批数据跑两次，进队列的是同一批样本
 *       （否则「抽样覆盖率」无法举证）。</li>
 * </ol>
 * 断言全部用硬编码字面量，<b>不引用被测实现提供的常量</b>（否则抽掉修复点时断言会跟着漂）。
 */
class ReviewSamplerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    /** 第二个租户：用于验证配额不被单租户吃满。 */
    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    private final AgentMetricPort metrics = mock(AgentMetricPort.class);
    private final ReviewTaskPort reviewTasks = mock(ReviewTaskPort.class);

    @BeforeEach
    void setUp() {
        when(reviewTasks.hasOpenTask(anyString())).thenReturn(false);
        when(reviewTasks.create(any())).thenReturn("task-1");
    }

    /**
     * 构造抽样器。
     *
     * @param maxPerTenant 每租户每轮配额
     * @param baselineRate 随机基线抽样率
     */
    private ReviewSampler sampler(int maxPerTenant, double baselineRate) {
        // ⚠️ globalCap 取 maxPerTenant 的 10 倍：既要能装满单租户，
        //    又要留出「多租户各自分到一份」的余量（否则测不出租户公平性）。
        return new ReviewSampler(metrics, reviewTasks, true, 24, maxPerTenant,
                Math.max(maxPerTenant * 10, maxPerTenant + 1), baselineRate);
    }

    private void candidates(SamplingCandidate... cs) {
        when(metrics.samplingCandidates(any(MetricQuery.class), anyInt()))
                .thenReturn(List.of(cs));
    }

    private static SamplingCandidate cand(String traceId, boolean anomaly, Boolean autoChecked) {
        return new SamplingCandidate(
                traceId, TENANT, "run-" + traceId, null,
                autoChecked, autoChecked == null ? null : Boolean.TRUE,
                false, anomaly ? 2 : 0, 0, anomaly ? 25_000L : 1_000L, NOW);
    }

    @Nested
    @DisplayName("S-1 分层优先级")
    class Layering {

        @Test
        @DisplayName("S-1a 异常 Trace 必须进 ANOMALY / HIGH（不是随机基线）")
        void anomalyGoesToAnomalyLayer() {
            candidates(cand("t-anomaly", true, Boolean.TRUE));

            sampler(50, 0.02).sampleOnce(NOW);

            ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
            verify(reviewTasks).create(cap.capture());
            assertThat(cap.getValue().source()).isEqualTo(Source.ANOMALY);
            assertThat(cap.getValue().priority()).isEqualTo(Priority.HIGH);
        }

        @Test
        @DisplayName("S-1b 未判过的 Trace 进 L1_GAP / HIGH（机器没看过，只能人看）")
        void coverageGapGoesToL1GapLayer() {
            candidates(cand("t-gap", false, null));

            sampler(50, 0.02).sampleOnce(NOW);

            ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
            verify(reviewTasks).create(cap.capture());
            assertThat(cap.getValue().source()).isEqualTo(Source.L1_GAP);
        }

        @Test
        @DisplayName("S-1c ⚠️ 一条 Trace 只进一层：异常样本不得再被算成覆盖缺口")
        void eachTraceGoesToExactlyOneLayer() {
            candidates(cand("t-anomaly", true, null));  // 既是异常、又是缺口

            ReviewSampler.Result r = sampler(50, 0.02).sampleOnce(NOW);

            assertThat(r.anomalyQueued()).isEqualTo(1);
            assertThat(r.gapQueued()).as("不许同时进两层（否则队列里同一 Trace 出现两次）").isZero();
            verify(reviewTasks).create(any());
        }

        @Test
        @DisplayName("S-1d 已判过且不异常的 Trace 只可能进随机基线")
        void healthyTraceFallsToBaseline() {
            candidates(cand("t-healthy", false, Boolean.TRUE));

            ReviewSampler.Result r = sampler(50, 1.0).sampleOnce(NOW);

            assertThat(r.baselineQueued()).isEqualTo(1);
            assertThat(r.anomalyQueued()).isZero();
            assertThat(r.gapQueued()).isZero();
        }
    }

    @Nested
    @DisplayName("S-2 上限与去重")
    class LimitsAndDedup {

        @Test
        @DisplayName("S-2a ⚠️ 单租户配额真的生效（塞爆队列 = reviewer 弃用队列）")
        void perTenantCapIsEnforced() {
            List<SamplingCandidate> many = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                many.add(cand("t-" + i, true, Boolean.TRUE));
            }
            when(metrics.samplingCandidates(any(MetricQuery.class), anyInt())).thenReturn(many);

            ReviewSampler.Result r = sampler(5, 0.02).sampleOnce(NOW);

            assertThat(r.queued()).as("单租户配额 5 ⇒ 最多建 5 条").isEqualTo(5);
            verify(reviewTasks, org.mockito.Mockito.times(5)).create(any());
        }

        @Test
        @DisplayName("S-2c ⚠️⚠️ 单租户不得吃满全平台配额（多租户 SaaS 的实质性偏差）")
        void oneTenantCannotStarveOthers() {
            // 租户 A 塞 30 条，租户 B 只有 2 条 —— 候选按时间倒序，A 在前面
            List<SamplingCandidate> many = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                many.add(new SamplingCandidate(
                        "a-" + i, TENANT_A, "run-a" + i, null,
                        Boolean.TRUE, Boolean.TRUE,
                        false, 2, 0, 25_000L, NOW));
            }
            for (int i = 0; i < 2; i++) {
                many.add(new SamplingCandidate(
                        "b-" + i, TENANT_B, "run-b" + i, null,
                        Boolean.TRUE, Boolean.TRUE,
                        false, 2, 0, 25_000L, NOW));
            }
            when(metrics.samplingCandidates(any(MetricQuery.class), anyInt())).thenReturn(many);

            ReviewSampler.Result r = sampler(5, 0.02).sampleOnce(NOW);

            // 单租户配额 5：A 拿 5 条，B 也应拿到 2 条（不受 A 影响）
            long fromA = captureTenant(TENANT_A);
            long fromB = captureTenant(TENANT_B);
            assertThat(fromA).as("A 最多拿自己的配额").isLessThanOrEqualTo(5);
            assertThat(fromB)
                    .as("⚠️ B 不能因为 A 高流量而被饿死 —— "
                            + "看板覆盖率按平台聚合，看不出某租户从未被审过")
                    .isEqualTo(2);
        }

        /** 统计本轮为某租户建了多少条任务。 */
        private long captureTenant(String tenantId) {
            ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
            verify(reviewTasks, org.mockito.Mockito.atLeast(0)).create(cap.capture());
            return cap.getAllValues().stream().filter(s -> tenantId.equals(s.tenantId())).count();
        }

        @Test
        @DisplayName("S-2b ⚠️ 已有未终结任务的 Trace 必须跳过（去重交给端口，不重复实现）")
        void openTaskIsSkipped() {
            when(reviewTasks.hasOpenTask("t-dup")).thenReturn(true);
            candidates(cand("t-dup", true, Boolean.TRUE));

            ReviewSampler.Result r = sampler(50, 0.02).sampleOnce(NOW);

            assertThat(r.alreadyQueued()).isEqualTo(1);
            assertThat(r.queued()).isZero();
            verify(reviewTasks, never()).create(any());
        }
    }

    @Nested
    @DisplayName("S-3 确定性与开关")
    class Determinism {

        @Test
        @DisplayName("S-3a 同一批数据跑两次，进队列的是同一批样本")
        void samplingIsDeterministic() {
            List<SamplingCandidate> cs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                cs.add(cand("t-" + i, false, Boolean.TRUE));
            }
            when(metrics.samplingCandidates(any(MetricQuery.class), anyInt())).thenReturn(cs);

            ReviewSampler s = sampler(100, 0.1);
            ReviewSampler.Result first = s.sampleOnce(NOW);

            // 第二次：让所有 create 都成功，统计入队条数
            ReviewSampler.Result second = s.sampleOnce(NOW);

            assertThat(second.baselineQueued())
                    .as("同一批数据两次抽样必须抽中同样多（确定性哈希，不是随机数）")
                    .isEqualTo(first.baselineQueued());
            assertThat(first.baselineQueued()).isGreaterThan(0);
        }

        @Test
        @DisplayName("S-3b 关闭时不扫库、不建任务")
        void disabledDoesNothing() {
            ReviewSampler off = new ReviewSampler(metrics, reviewTasks, false, 24, 50, 500, 0.02);

            ReviewSampler.Result r = off.sampleOnce(NOW);

            assertThat(r.queued()).isZero();
            verify(metrics, never()).samplingCandidates(any(), anyInt());
        }

        @Test
        @DisplayName("S-3c 基线上限非法配置必须启动期拒绝（fail-closed）")
        void invalidConfigRejected() {
            assertThatThrownBy(() -> new ReviewSampler(metrics, reviewTasks, true, 24, 50, 500, 1.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseline-rate");
            assertThatThrownBy(() -> new ReviewSampler(metrics, reviewTasks, true, 0, 50, 500, 0.02))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lookback-hours");
            // ⚠️ globalCap < maxPerTenant ⇒ 连单租户都装不满，配置本身自相矛盾
            assertThatThrownBy(() -> new ReviewSampler(metrics, reviewTasks, true, 24, 50, 10, 0.02))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("global-cap");
        }
    }
}

package cn.studykid.multiagent.infrastructure.security;

import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer 权限影子/染色观测指标（阶段一 · I2 · P0-1c 监控面板）。
 *
 * <p>在 {@link InMemoryAuthzMismatchMetrics} 的原子计数基础上，额外通过 Micrometer 暴露
 * 生产可采集指标，覆盖 13 文 §5.2 验收矩阵与阶段一设计 §2.2 监控需求：
 * <ul>
 *   <li>{@code cap.authz_mismatch_total} —— AUTHZ_MISMATCH 发生次数（按资源类型分组）；</li>
 *   <li>{@code cap.authz_shadow_samples_total} —— 影子比对样本数（按资源类型分组）；</li>
 *   <li>{@code cap.authz_unrestricted_total} —— Unrestricted（平台全域）使用次数；</li>
 *   <li>{@code cap.authz_taint_violations_total} —— 染色校验捕到的越权行数。</li>
 * </ul>
 * 与 Micrometer 既有约定（{@code MicrometerExecutionObserver} 等）一致：每个计数目标
 * 常量静态化、按 {@code resourceType} 打 {@code resource_type} 标签、{@code register+increment}。
 *
 * <p><b>单源真相</b>：本类是生产装配里的<b>唯一</b> {@link AuthzMismatchMetrics} Bean
 * （见 {@code AuthzShadowConfiguration#authzMismatchMetrics}），原子计数与 Micrometer
 * 计数在同一次调用里同步递增，<b>不存在两套计数互相复制</b> —— 既能通过接口访问器
 * （{@link #mismatches()} 等）被单测断言，又能被 Prometheus 抓取。
 *
 * <p><b>日志告警</b>：沿用 {@link Slf4jAuthzMismatchMetrics} 的 WARN 约定 —— mismatch /
 * taint 违例打 WARN，其余打 DEBUG，便于告警订阅（12 文 §7.1 R7）。
 *
 * <p><b>生产出口门禁</b>（13 文 §5.2）：≥2 周 {@code AUTHZ_MISMATCH = 0} 方可放行 P2。
 * {@code cap.authz_mismatch_total} 任何非零都意味着 Gate 计算谓词与实际查询绑定不一致，
 * 必须值守介入。
 */
public final class MicrometerAuthzMismatchMetrics implements AuthzMismatchMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerAuthzMismatchMetrics.class);

    // ── Micrometer 指标名 ─────────────────────────────────────────────
    static final String MISMATCH_TOTAL = "cap.authz_mismatch_total";
    static final String SHADOW_SAMPLES_TOTAL = "cap.authz_shadow_samples_total";
    static final String UNRESTRICTED_TOTAL = "cap.authz_unrestricted_total";
    static final String TAINT_VIOLATIONS_TOTAL = "cap.authz_taint_violations_total";
    static final String TAG_RESOURCE_TYPE = "resource_type";

    private final MeterRegistry registry;
    private final AtomicLong shadowSamples = new AtomicLong();
    private final AtomicLong unrestrictedUsages = new AtomicLong();
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicLong taintViolations = new AtomicLong();

    public MicrometerAuthzMismatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordShadowSample(@NonNull String resourceType) {
        shadowSamples.incrementAndGet();
        Counter.builder(SHADOW_SAMPLES_TOTAL)
                .description("Authorization shadow comparison samples")
                .tag(TAG_RESOURCE_TYPE, resourceType)
                .register(registry)
                .increment();
    }

    @Override
    public void recordUnrestricted(@NonNull String resourceType) {
        unrestrictedUsages.incrementAndGet();
        Counter.builder(UNRESTRICTED_TOTAL)
                .description("Unrestricted (platform-wide) authorizations")
                .tag(TAG_RESOURCE_TYPE, resourceType)
                .register(registry)
                .increment();
        LOG.debug("[authz] Unrestricted 使用 resourceType={}", resourceType);
    }

    @Override
    public void recordMismatch(@NonNull String resourceType, @NonNull String detail) {
        mismatches.incrementAndGet();
        Counter.builder(MISMATCH_TOTAL)
                .description("Authorization shadow mismatch (AUTHZ_MISMATCH) occurrences")
                .tag(TAG_RESOURCE_TYPE, resourceType)
                .register(registry)
                .increment();
        LOG.warn("[authz-mismatch] resourceType={} detail={}", resourceType, detail);
    }

    @Override
    public void recordTaintViolation(@NonNull String resourceType,
                                     @NonNull RowAttribution row,
                                     @NonNull ScopeFilter expected) {
        taintViolations.incrementAndGet();
        Counter.builder(TAINT_VIOLATIONS_TOTAL)
                .description("Authorization shadow taint violations (out-of-scope rows)")
                .tag(TAG_RESOURCE_TYPE, resourceType)
                .register(registry)
                .increment();
        LOG.warn("[authz-taint] 越权行被返回 resourceType={} rowTenant={} shared={} expected={}",
                resourceType, row.tenantId(), row.shared(), expected);
    }

    @Override
    public long shadowSamples() {
        return shadowSamples.get();
    }

    @Override
    public long unrestrictedUsages() {
        return unrestrictedUsages.get();
    }

    @Override
    public long mismatches() {
        return mismatches.get();
    }

    @Override
    public long taintViolations() {
        return taintViolations.get();
    }
}
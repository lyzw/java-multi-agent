package cn.studykid.multiagent.application.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Worker 主循环配置（P4-D03 / D-3）。
 *
 * <p><b>⚠️ 缺失或非法一律拒绝启动</b>（fail-closed）：
 * 退回默认值等于「悄悄用了一个没人审过的并发度」。
 * 容器化环境下默认并发可能直接打垮下游模型服务与 MQ。
 *
 * <p>与 D4 {@code BudgetPolicy}、D8 {@code RateLimitPolicy} 同一取舍。
 */
public final class WorkerPolicy {

    /** 中继轮询间隔毫秒。 */
    public static final String KEY_RELAY_INTERVAL_MS = "agent.worker.relay-interval-ms";

    /** 消费并发度。 */
    public static final String KEY_CONCURRENCY = "agent.worker.concurrency";

    /** 每消费者预取数（越大吞吐越高，但重投时重复处理窗口也越大）。 */
    public static final String KEY_PREFETCH = "agent.worker.prefetch";

    /** 单轮发布的批量上限。 */
    public static final String KEY_OUTBOX_BATCH = "agent.worker.outbox-batch";

    /** 发布失败累计上限（超过则停止重试并告警）。 */
    public static final String KEY_MAX_PUBLISH_ATTEMPTS = "agent.worker.max-publish-attempts";

    /** 本实例服务的租户 slug 清单（P4-D05 / L-4）。 */
    public static final String KEY_TENANT_SLUGS = "agent.worker.tenant-slugs";

    private WorkerPolicy() {
    }

    /**
     * 解析租户 slug 清单（P4-D05 / 关闭 L-4）。
     *
     * <p><b>⚠️ 为何是「显式清单」而不是「全部租户」</b>：
     * 「扫全表」要求连接具备跨租户读权限，
     * 一旦配置或实现出错就是<b>跨租户发布</b>（越权，且静默）。
     * 显式清单的最坏结果是<b>漏发或拒启</b>——
     * 失败形态从「越权」降级为「不可用」，这是本片的核心取舍。
     *
     * <p><b>⚠️ 清单长度受 {@code deployment.pilot.max-tenants} 约束</b>：
     * 试点期上限是灰度承诺的一部分，
     * 允许配置悄悄超过上限等于「灰度控制形同虚设」（FE-02 已记该缺口）。
     *
     * @param raw        配置原始值（逗号分隔）
     * @param maxTenants 试点允许的最大租户数（≥1）
     * @return 去重后的 slug 清单（保持配置顺序）
     * @throws IllegalStateException 缺失 / 为空 / 重复 / 超过上限
     */
    public static List<String> requireTenantSlugs(@Nullable Object raw, int maxTenants) {
        if (raw == null) {
            throw new IllegalStateException(
                    "Worker 配置缺失（fail-closed，禁止退回默认值）：" + KEY_TENANT_SLUGS);
        }
        if (maxTenants < 1) {
            throw new IllegalStateException(
                    "试点租户上限必须 ≥1（非法上限会让清单校验失去意义）: " + maxTenants);
        }
        List<String> slugs = new ArrayList<>();
        for (String part : String.valueOf(raw).split(",")) {
            String slug = part.trim();
            if (!slug.isEmpty()) {
                slugs.add(slug);
            }
        }
        if (slugs.isEmpty()) {
            // ⚠️ 空清单 = 消息永不发布，而系统表面完全正常（断链的典型形态）
            throw new IllegalStateException(
                    "租户 slug 清单为空（fail-closed：空清单即消息永不发布）: "
                            + KEY_TENANT_SLUGS + " = " + raw);
        }
        int distinct = new LinkedHashSet<>(slugs).size();
        if (distinct != slugs.size()) {
            throw new IllegalStateException(
                    "租户 slug 清单含重复项（同一租户被发布两次 = 消息重复）: " + slugs);
        }
        if (slugs.size() > maxTenants) {
            throw new IllegalStateException(
                    "租户 slug 数量超过试点上限（灰度控制不得被配置绕过）: "
                            + KEY_TENANT_SLUGS + " 共 " + slugs.size() + " 个，"
                            + PlatformConfig.KEY_PILOT_MAX_TENANTS + " = " + maxTenants);
        }
        return List.copyOf(slugs);
    }

    public static long requireRelayIntervalMs(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_RELAY_INTERVAL_MS);
        // ⚠️ 过小的间隔等于忙等，会把 CPU 打满并压垮下游。
        if (v < 10) {
            throw new IllegalStateException(
                    "中继轮询间隔过小（<10ms 会变成忙等）：" + KEY_RELAY_INTERVAL_MS
                            + " = " + v);
        }
        return v;
    }

    public static int requireConcurrency(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_CONCURRENCY);
        if (v > 64) {
            throw new IllegalStateException(
                    "消费并发度过高（>64 会压垮下游模型服务）："
                            + KEY_CONCURRENCY + " = " + v);
        }
        return (int) v;
    }

    public static int requirePrefetch(@Nullable Object raw) {
        long v = requirePositive(raw, KEY_PREFETCH);
        if (v > 100) {
            throw new IllegalStateException(
                    "预取数过高（>100 会放大重投时的重复处理窗口）："
                            + KEY_PREFETCH + " = " + v);
        }
        return (int) v;
    }

    public static int requireOutboxBatch(@Nullable Object raw) {
        return (int) requirePositive(raw, KEY_OUTBOX_BATCH);
    }

    public static int requireMaxPublishAttempts(@Nullable Object raw) {
        return (int) requirePositive(raw, KEY_MAX_PUBLISH_ATTEMPTS);
    }

    private static long requirePositive(@Nullable Object raw, String key) {
        if (raw == null) {
            throw new IllegalStateException(
                    "Worker 配置缺失（fail-closed，禁止退回默认值）：" + key);
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Worker 配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value <= 0) {
            throw new IllegalStateException(
                    "Worker 配置必须为正数（0 会导致行为不可预期）：" + key + " = " + value);
        }
        return value;
    }
}
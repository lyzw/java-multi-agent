package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * {@link RateLimitPort} 的固定窗口实现（P3-D08 / D-2）。
 *
 * <p><b>⚠️ 核心不变量：判定与计数必须在同一条 SQL 内完成</b>（D-1）：
 * <pre>
 *   INSERT INTO rate_limit_bucket (...) VALUES (窗口起点, 1)
 *   ON CONFLICT (tenant_id, bucket_key, window_start)
 *   DO UPDATE SET count = rate_limit_bucket.count + 1
 *   RETURNING count
 * </pre>
 *
 * <p>这是 D4 §2.1 同一反模式的翻版：check-then-act（先 SELECT 判断、
 * 再 INSERT）在并发下必然超发——N 个并发请求可同时读到「未超限」，
 * 随后全部成功计数。<b>唯一索引 {@code rate_limit_bucket_unique} 是原子性的基础</b>，
 * 去掉它则冲突目标不存在，upsert 退化为插入重复行。
 *
 * <p><b>连接所有权</b>：全部经 {@link TransactionalConnectionHolder#inTransaction}
 * （P2-D05 铁律）。
 */
public class JdbcRateLimiter implements RateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRateLimiter.class);

    private final DataSource dataSource;
    private final long limit;
    private final long windowSeconds;

    /**
     * 桶级限额覆盖（P4-D13）。
     *
     * <p>⚠️ <b>为什么需要它（本片实测驱动）</b>：
     * {@link #limit} 是<b>全局单一值</b>（30 次/60 秒），而它最初只服务
     * {@code RUN_CREATE} / {@code SSE_CONNECT} / {@code EVENT_QUERY}
     * ——全是<b>单个学生的个人动作</b>。
     * 治理端点（{@code GOVERNANCE_QUERY} / {@code GOVERNANCE_DECIDE}）语义完全不同：
     * <b>一个租户的多个运维共享同一个桶</b>（限流按租户，不按人），
     * 每个人翻几页列表就把 30 次/分用光，<b>运维之间互相限流</b>。
     *
     * <p>实测证据：{@code GovernanceEndpointIT} 的 19 个用例同租户跑，
     * 在共享限额下直接拿到 <b>429</b>。
     *
     * <p>⚠️ <b>缺省仍回落到全局 limit</b>（不 fail-closed）：
     * 治理桶的阈值是<b>容量规划</b>问题（该给多少），
     * 而非<b>安全开关</b>问题（能不能给）——它不会导致越权，只影响可用性。
     * 与 {@code BudgetPolicy}（缺额即拒绝启动）不同源，取舍有别。
     */
    private final java.util.Map<String, Long> bucketLimits;

    /**
     * @param limit         每窗口限额；<b>必须为正</b>（非正数 = 永久拒绝一切）
     * @param windowSeconds 窗口长度（秒）；<b>必须为正</b>
     */
    public JdbcRateLimiter(@NonNull DataSource dataSource, long limit, long windowSeconds) {
        this(dataSource, limit, windowSeconds, java.util.Map.of());
    }

    /**
     * @param bucketLimits 桶级覆盖；值必须为正，否则视为配置错误拒绝启动
     */
    public JdbcRateLimiter(@NonNull DataSource dataSource, long limit, long windowSeconds,
                           java.util.@NonNull Map<String, Long> bucketLimits) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "限流限额必须为正数（0 或负数意味着一律拒绝，属配置错误）：" + limit);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("限流窗口长度必须为正数（秒）：" + windowSeconds);
        }
        bucketLimits.forEach((bucket, value) -> {
            if (value == null || value <= 0) {
                // ⚠️ 覆盖值非法一律拒绝启动：静默忽略会让「以为调大了、实际没调」
                // 变成一个只有实测才发现的容量问题
                throw new IllegalArgumentException(
                        "桶级限额必须为正数：" + bucket + " = " + value);
            }
        });
        this.dataSource = dataSource;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
        this.bucketLimits = java.util.Map.copyOf(bucketLimits);
    }

    /** @return 该桶实际生效的限额（无覆盖时回落全局值）。 */
    public long limitFor(@NonNull String bucketKey) {
        Long override = bucketLimits.get(bucketKey);
        return override == null ? limit : override;
    }

    @Override
    public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
        long effectiveLimit = limitFor(bucketKey);
        try {
            long count = incrementAndCount(tenantId, bucketKey);
            if (count > effectiveLimit) {
                if (log.isDebugEnabled()) {
                    log.debug("限流拒绝: tenant={}, bucket={}, count={}, limit={}",
                            tenantId, bucketKey, count, effectiveLimit);
                }
                return Decision.REJECTED;
            }
            return Decision.ALLOWED;
        } catch (RuntimeException e) {
            // ⚠️ fail-closed（D-4）：限流组件故障时放行 = 限流形同虚设。
            log.error("限流组件故障，fail-closed 拒绝: tenant={}, bucket={}",
                    tenantId, bucketKey, e);
            return Decision.FAIL_CLOSED;
        }
    }

    @Override
    public long currentCount(@NonNull String tenantId, @NonNull String bucketKey) {
        return inTenantTx(tenantId, conn -> {
            String sql = """
                    SELECT count FROM rate_limit_bucket
                    WHERE tenant_id = ?::uuid AND bucket_key = ?
                      AND window_start = to_timestamp(
                            floor(extract(epoch from now()) / ?)::bigint * ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, bucketKey);
                ps.setLong(3, windowSeconds);
                ps.setLong(4, windowSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** 原子计数：插入或递增，返回递增后的值。 */
    private long incrementAndCount(String tenantId, String bucketKey) {
        return inTenantTx(tenantId, conn -> {
            // ⚠️ 窗口起点按窗口长度对齐：所有实例对同一时刻算出同一 window_start，
            //    计数才能跨实例共享（依赖各实例 now() 时钟一致，见 §4 L-3）。
            String sql = """
                    INSERT INTO rate_limit_bucket
                        (tenant_id, bucket_key, window_start, count, updated_at)
                    VALUES (?::uuid, ?,
                            to_timestamp(floor(extract(epoch from now()) / ?)::bigint * ?),
                            1, now())
                    ON CONFLICT (tenant_id, bucket_key, window_start)
                    DO UPDATE SET count = rate_limit_bucket.count + 1,
                                  updated_at = now()
                    RETURNING count
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, bucketKey);
                ps.setLong(3, windowSeconds);
                ps.setLong(4, windowSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "限流计数未返回（tenant=" + tenantId
                                        + ", bucket=" + bucketKey + "）");
                    }
                    return rs.getLong(1);
                }
            }
        });
    }

    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                action::run);
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T run(Connection conn) throws SQLException;
    }
}
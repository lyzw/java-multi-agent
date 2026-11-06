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

    public JdbcRateLimiter(@NonNull DataSource dataSource, long limit, long windowSeconds,
                           java.util.@NonNull Map<String, Long> bucketLimits) {
        this(dataSource, limit, windowSeconds, bucketLimits, false);
    }

    /**
     * @param bucketLimits   桶级覆盖；值必须为正，否则视为配置错误拒绝启动
     * @param slidingWindow  是否启用滑动窗口计数近似（P3-1 ①）；
     *                       {@code false} = 固定窗口（保留既有行为，窗口边界可达 2×limit）
     */
    public JdbcRateLimiter(@NonNull DataSource dataSource, long limit, long windowSeconds,
                           java.util.@NonNull Map<String, Long> bucketLimits,
                           boolean slidingWindow) {
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
        this.slidingWindow = slidingWindow;
        log.info("限流器装配: limit={}, window={}s, slidingWindow={}, 桶覆盖={}",
                limit, windowSeconds, slidingWindow, bucketLimits);
    }

    /**
     * 是否启用<b>滑动窗口</b>计数近似（P3-1 ①）。
     *
     * <p>⚠️ <b>固定窗口的真实缺口</b>：窗口起点按 {@code floor(epoch / w) * w} 对齐，
     * 于是「窗口末尾 + 下一窗口开头」这一小段里，
     * 实际放行量可达 <b>2 × limit</b>：
     * <pre>
     *   限额 30/60s：第 59.9s 打满 30 次 → 第 60.0s 窗口重置 → 再来 30 次
     *   客户端视角：不到 0.2 秒内通过了 60 次
     * </pre>
     * 对「单个学生点按钮」这类动作无所谓，
     * 但对 {@code SSE_CONNECT}（每个连接长期占用服务端资源）不是小事。
     *
     * <p>⚠️ <b>为什么近似是安全的</b>：滑动窗口 weigh 的「上一窗口计数」
     * 在其窗口结束的瞬间就<b>冻结</b>了 —— 后续任何请求都只会写当前窗口。
     * 故「读上一窗口 + 原子递增当前窗口」这个两步组合<b>不存在</b>
     * check-then-act 超发问题（那正是本类核心不变量要防的东西）。
     *
     * <p>⚠️ <b>默认 {@code false} 保留固定窗口</b>：切换会略微收紧判定
     * （带上一窗口衰减量），可能让压测类用例更早拿到 429。
     * 加固应当<b>可回滚</b>，故做成显式开关而不是顺手改掉默认行为。
     */
    private final boolean slidingWindow;

    /** @return 该桶实际生效的限额（无覆盖时回落全局值）。 */
    public long limitFor(@NonNull String bucketKey) {
        Long override = bucketLimits.get(bucketKey);
        return override == null ? limit : override;
    }

    @Override
    public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
        long effectiveLimit = limitFor(bucketKey);
        try {
            WindowCount counted = incrementAndCount(tenantId, bucketKey);
            double weighted = slidingWindow
                    ? slidingCount(counted.previousCount(), counted.currentCount(),
                            counted.elapsedInWindowSeconds(), windowSeconds)
                    : counted.currentCount();
            if (weighted > effectiveLimit) {
                if (log.isDebugEnabled()) {
                    log.debug("限流拒绝: tenant={}, bucket={}, weighted={}, count={}, limit={}",
                            tenantId, bucketKey, weighted, counted, effectiveLimit);
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

    /**
     * 滑动窗口计数的加权近似（纯函数，便于单测）。
     *
     * <p>公式：{@code current + previous × (window − elapsed) / window}。
     * 上一窗口的计数按「它离现在有多远」线性衰减：窗口刚翻页时衰减最小
     * （上一窗口刚结束，其用量几乎还「热」），窗口快结束时衰减到 0。
     *
     * @param previousCount           上一窗口的计数（其窗口已结束，该值冻结）
     * @param currentCount            当前窗口的计数
     * @param elapsedInWindowSeconds  当前窗口已过去的秒数；超出 [0, windowSeconds] 会先被夹取
     * @param windowSeconds           窗口长度（秒）；必须为正
     * @return 加权后的等效用量
     */
    static double slidingCount(long previousCount, long currentCount,
                               double elapsedInWindowSeconds, double windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("窗口长度必须为正数: " + windowSeconds);
        }
        double elapsed = Math.max(0.0, Math.min(windowSeconds, elapsedInWindowSeconds));
        double decay = (windowSeconds - elapsed) / windowSeconds;
        return currentCount + previousCount * decay;
    }

    /** 一次计数的结果：当前窗口计数 + 上一窗口计数 + 当前窗口已过秒数。 */
    record WindowCount(long currentCount, long previousCount, double elapsedInWindowSeconds) {
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

    /**
     * 原子计数：插入或递增，返回递增后的当前窗口计数，
     * 并在<b>同一次往返</b>里取回上一窗口计数与当前窗口已过秒数。
     *
     * <p>⚠️ <b>为什么合并成一条 SQL</b>：滑动窗口需要先看上一窗口。
     * 若拆成两次调用，两次之间虽无超发风险（上一窗口已冻结），
     * 但会多一次事务往返，而限流处在<b>每个请求的热路径</b>上；
     * 更关键的是：拆开后「上一窗口」的读取容易被人误改成「当前窗口」，
     * 那种改错不会报错，只会让加权失去作用 —— 用 CTE 一次取齐，
     * 让「读哪个窗口」这件事在 SQL 里就近可见。
     *
     * <p>⚠️ <b>数据修改型 CTE 的可见性</b>：{@code INSERT … RETURNING} 放在 CTE 里
     * 与外层 {@code SELECT} 属同一语句、同一快照，取到的是<b>本事务刚写入</b>的值。
     */
    private WindowCount incrementAndCount(String tenantId, String bucketKey) {
        return inTenantTx(tenantId, conn -> {
            // ⚠️ 窗口起点按窗口长度对齐：所有实例对同一时刻算出同一 window_start，
            //    计数才能跨实例共享（依赖各实例 now() 时钟一致，见 §4 L-3）。
            String sql = """
                    WITH cur AS (
                        INSERT INTO rate_limit_bucket
                            (tenant_id, bucket_key, window_start, count, updated_at)
                        VALUES (?::uuid, ?,
                                to_timestamp(floor(extract(epoch from now()) / ?)::bigint * ?),
                                1, now())
                        ON CONFLICT (tenant_id, bucket_key, window_start)
                        DO UPDATE SET count = rate_limit_bucket.count + 1,
                                      updated_at = now()
                        RETURNING count
                    ),
                    prev AS (
                        SELECT count FROM rate_limit_bucket
                        WHERE tenant_id = ?::uuid AND bucket_key = ?
                          AND window_start =
                              to_timestamp(floor(extract(epoch from now()) / ?)::bigint * ?)
                              - make_interval(secs => ?::int)
                    ),
                    timing AS (
                        SELECT extract(epoch from now())
                               - floor(extract(epoch from now()) / ?)::bigint * ? AS elapsed
                    )
                    SELECT (SELECT count FROM cur)       AS cur_count,
                           COALESCE((SELECT count FROM prev), 0) AS prev_count,
                           (SELECT elapsed FROM timing)  AS elapsed
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, bucketKey);
                ps.setLong(3, windowSeconds);
                ps.setLong(4, windowSeconds);
                ps.setString(5, tenantId);
                ps.setString(6, bucketKey);
                ps.setLong(7, windowSeconds);
                ps.setLong(8, windowSeconds);
                ps.setLong(9, windowSeconds);
                ps.setLong(10, windowSeconds);
                ps.setLong(11, windowSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "限流计数未返回（tenant=" + tenantId
                                        + ", bucket=" + bucketKey + "）");
                    }
                    return new WindowCount(rs.getLong("cur_count"),
                            rs.getLong("prev_count"),
                            rs.getDouble("elapsed"));
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
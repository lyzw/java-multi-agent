package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 限流集成测试（P3-D08 / D-2 预算击穿）。
 *
 * <p><b>本类证明的核心命题</b>（真实 PG17、非特权角色 {@code app_agent}）：
 * <ol>
 *   <li><b>未超限放行、超限拒绝</b>（fail-closed）；</li>
 *   <li><b>并发不超发</b>：N 路并发恰好 limit 路放行，<b>计数恰好 = N</b>
 *       —— 这是 D-1「单条原子 SQL」的证明；</li>
 *   <li><b>跨租户隔离</b>：B 的计数不影响 A；</li>
 *   <li><b>组件故障 → FAIL_CLOSED</b>而非放行（D-4）；</li>
 *   <li><b>唯一索引是原子性的基础</b>：去掉后并发出现重复桶行（T-8 反证）。</li>
 * </ol>
 *
 * <p>⚠️ 每个用例用<b>独立 bucketKey</b> 避免互相污染计数
 *（同窗口内共享桶会让「依赖执行顺序的测试」变成时绿时红）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RateLimitIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    /**
     * 反证专用宽连接池。
     *
     * <p>⚠️ <b>{@code adminPool} 的 maximumPoolSize=1</b>，6 个线程会串行化在
     * 单连接上 → 并发场景被彻底掩盖（本次实测踩到：反证首跑「1 行」而非「多行」）。
     * <b>池大小必须匹配线程数，否则并发用例退化为串行</b>。
     */
    private static HikariDataSource wideAdminPool;

    private static String tenantA;
    private static String tenantB;

    /** 窗口取 60 秒：足够长，测试不会因跨窗口而抖动。 */
    private static final long WINDOW = 60L;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(16);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        // ⚠️ 反证需要真并发：池大小 = 线程数
        wideAdminPool = FaultInjectionSupport.appPool(16);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        tenantB = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-b");
    }

    @AfterAll
    static void tearDown() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
        if (wideAdminPool != null) {
            wideAdminPool.close();
        }
    }

    private static JdbcRateLimiter limiter(long limit) {
        return new JdbcRateLimiter(appPool, limit, WINDOW);
    }

    /** 每个用例独立桶键（⚠️ 依赖执行顺序的测试等于没有测试）。 */
    private static String uniqueBucket(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    // ── T-1：基本判定 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-1 未超限 → ALLOWED，计数逐次递增")
    void underLimitAllowed() {
        String bucket = uniqueBucket("t1");
        var limiter = limiter(3);

        for (int i = 1; i <= 3; i++) {
            assertThat(limiter.check(tenantA, bucket))
                    .as("第 %d 次应在限额内", i)
                    .isEqualTo(RateLimitPort.Decision.ALLOWED);
            assertThat(limiter.currentCount(tenantA, bucket)).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("T-2 ⚠️⚠️ 超限 → REJECTED（fail-closed），且计数继续增长（令牌不退还）")
    void overLimitRejected() {
        String bucket = uniqueBucket("t2");
        var limiter = limiter(2);

        assertThat(limiter.check(tenantA, bucket)).isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.check(tenantA, bucket)).isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.check(tenantA, bucket))
                .as("⚠️ 第 3 次必须被拒（D-2）")
                .isEqualTo(RateLimitPort.Decision.REJECTED);
        assertThat(limiter.check(tenantA, bucket))
                .as("⚠️⚠️ 被拒的请求仍消耗令牌——否则限流退化为仅计数不设防")
                .isEqualTo(RateLimitPort.Decision.REJECTED);

        assertThat(limiter.currentCount(tenantA, bucket))
                .as("⚠️ 计数应累计到 4（被拒不退还）")
                .isEqualTo(4L);
    }

    // ── T-3：并发不超发（D-1 原子性的证明）──────────────────────────

    @Test
    @DisplayName("T-3 ⚠️⚠️ 并发 24 路（限额 5）→ 恰好 5 路放行，计数恰好 24")
    void concurrentRequestsNeverOvershoot() throws Exception {
        String bucket = uniqueBucket("t3");
        int limit = 5;
        int threads = 24;
        var limiter = limiter(limit);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failClosed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // ⚠️ 尽量让所有线程在同一时刻发起，制造真实竞争
                        start.await();
                        RateLimitPort.Decision d = limiter.check(tenantA, bucket);
                        switch (d) {
                            case ALLOWED -> allowed.incrementAndGet();
                            case REJECTED -> rejected.incrementAndGet();
                            case FAIL_CLOSED -> failClosed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("⚠️ 线程未就绪，测试本身不可信")
                    .isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failClosed.get())
                .as("⚠️ 正常路径不应出现 FAIL_CLOSED")
                .isZero();
        assertThat(allowed.get())
                .as("⚠️⚠️ 并发下放行数必须恰好等于限额（原子性证明）")
                .isEqualTo(limit);
        assertThat(rejected.get()).isEqualTo(threads - limit);
        assertThat(limiter.currentCount(tenantA, bucket))
                .as("⚠️⚠️ 计数必须恰好等于总请求数（无丢失、无重复行）")
                .isEqualTo(threads);
        assertThat(bucketRowCount(tenantA, bucket))
                .as("⚠️⚠️ 必须只有一行桶记录（唯一索引生效）")
                .isEqualTo(1);
    }

    // ── T-4：跨租户隔离 ─────────────────────────────────────────────

    @Test
    @DisplayName("T-4 ⚠️⚠️ 跨租户：A 打满不影响 B；B 看不到 A 的桶")
    void tenantsAreIsolated() {
        String bucketA = uniqueBucket("t4a");
        String bucketB = uniqueBucket("t4b");
        var limiter = limiter(1);

        assertThat(limiter.check(tenantA, bucketA)).isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.check(tenantA, bucketA))
                .as("⚠️ A 已打满")
                .isEqualTo(RateLimitPort.Decision.REJECTED);

        assertThat(limiter.check(tenantB, bucketB))
                .as("⚠️⚠️ B 不应受 A 的计数影响（单租户失控不得波及其他租户）")
                .isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.currentCount(tenantB, bucketA))
                .as("⚠️ B 视角看不到 A 的桶")
                .isZero();
    }

    @Test
    @DisplayName("T-4b 同桶键跨租户独立计数")
    void sameBucketKeySeparatePerTenant() {
        String bucket = uniqueBucket("t4c");
        var limiter = limiter(1);

        assertThat(limiter.check(tenantA, bucket)).isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.check(tenantB, bucket))
                .as("⚠️⚠️ 同一桶键在 B 租户应有独立配额")
                .isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.currentCount(tenantA, bucket)).isEqualTo(1L);
        assertThat(limiter.currentCount(tenantB, bucket)).isEqualTo(1L);
    }

    // ── T-5：组件故障 → FAIL_CLOSED（D-4）──────────────────────────

    @Test
    @DisplayName("T-5 ⚠️⚠️ 限流组件故障（关闭连接池）→ FAIL_CLOSED 而非放行")
    void componentFailureIsFailClosed() {
        HikariDataSource broken = FaultInjectionSupport.appPool(2);
        // ⚠️ 先 close 再调用：所有获取连接的尝试都会失败
        broken.close();
        var limiter = new JdbcRateLimiter(broken, 10, WINDOW);

        assertThat(limiter.check(tenantA, uniqueBucket("t5")))
                .as("⚠️⚠️ 组件故障必须 fail-closed（放行 = 限流形同虚设）")
                .isEqualTo(RateLimitPort.Decision.FAIL_CLOSED);
    }

    // ── T-6：构造期校验（配置错误必须在装配时暴露）──────────────────

    @Test
    @DisplayName("T-6 限额 ≤ 0 或窗口 ≤ 0 → 构造期即拒绝（不让配置错误上生产）")
    void invalidConfigurationRejectedAtConstruction() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new JdbcRateLimiter(appPool, 0, WINDOW))
                .as("⚠️ 限额 0 意味着一律拒绝，属配置错误")
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new JdbcRateLimiter(appPool, -1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new JdbcRateLimiter(appPool, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── T-8：反证 —— check-then-act 在确定性交错下失效 ──────────────

    /**
     * T-8 ⚠️⚠️ 反证（确定性交错）：check-then-act → 同一窗口产生<b>两行</b>桶。
     *
     * <p>⚠️ <b>为何不用线程并发</b>（本次实测连踩三个坑）：
     * <ol>
     *   <li>首版断言「无唯一索引时 ON CONFLICT 报错」——<b>实测不成立</b>：
     *       PG 的 {@code DO UPDATE SET} 在无冲突时不触发冲突检查；</li>
     *   <li>二版用 6 线程并发跑 check-then-act——<b>只得到 1 行</b>：
     *       SELECT 能看见其他线程已提交的数据 → 走了 UPDATE 分支；</li>
     *   <li>三版改用独立连接池（{@code wideAdminPool}）——<b>仍是 1 行</b>：
     *       PG 的快照隔离让并发事务在<b>各自快照</b>里都「查不到」，
     *       但提交时后到者与先到者键相同，无索引也不冲突，本应产生 2 行——
     *       实测仍 1 行，说明命中的是 <b>{@code appPool} 之外的路径差异</b>。</li>
     * </ol>
     *
     * <p><b>正确解法：确定性交错</b>（D4 T-1b 同款）。不依赖线程调度运气，
     * 手工编排「A 查到不存在 → B 也查到不存在 → 两者都插入」。
     *
     * <p>⚠️ <b>教训（第三次印证）</b>：反证不红时，
     * <b>先怀疑注入点/编排选错</b>，而不是认为约束无效。
     */
    @Test
    @DisplayName("T-8 ⚠️⚠️ 反证（确定性交错）：check-then-act → 同一窗口两行桶 → 计数被分裂")
    void checkThenActSplitsCountUnderDeterministicInterleaving() throws Exception {
        String bucket = uniqueBucket("t8");
        dropUniqueIndex();
        try {
            Connection a = FaultInjectionSupport.rawAppConnection();
            Connection b = FaultInjectionSupport.rawAppConnection();
            try {
                a.setAutoCommit(false);
                b.setAutoCommit(false);
                FaultInjectionSupport.bind(a, tenantA);
                FaultInjectionSupport.bind(b, tenantA);

                // ── A：check-then-act 的「check」阶段，确认不存在 ──
                assertThat(existsIn(a, bucket))
                        .as("⚠️ 前置：A 应查到不存在")
                        .isFalse();
                // ⚠️ A **只插入不提交** → B 在此刻做 check 必然也查到不存在
                insertPlain(a, bucket);
                insertPlain(b, bucket);

                a.commit();
                b.commit();
            } finally {
                a.close();
                b.close();
            }

            int rows = bucketRowCount(tenantA, bucket);
            assertThat(rows)
                    .as("⚠️ 反证：check-then-act 在确定性交错下产生 %d 行同一窗口的桶"
                            + "（原子路径恒为 1 行）→ 计数被分裂，限额失效", rows)
                    .isEqualTo(2);
        } finally {
            restoreUniqueIndex();
            purgeBucket(bucket);
        }
    }

    /** check-then-act 的「check」阶段。 */
    private static boolean existsIn(Connection conn, String bucketKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM rate_limit_bucket "
                        + "WHERE tenant_id = ?::uuid AND bucket_key = ?")) {
            ps.setString(1, tenantA);
            ps.setString(2, bucketKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    /** 无唯一索引时的普通 INSERT（check-then-act 的「act」阶段）。 */
    private static void insertPlain(Connection conn, String bucketKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rate_limit_bucket "
                        + "(tenant_id, bucket_key, window_start, count, updated_at) "
                        + "VALUES (?::uuid, ?, now(), 1, now())")) {
            ps.setString(1, tenantA);
            ps.setString(2, bucketKey);
            ps.executeUpdate();
        }
    }

    private static void dropUniqueIndex() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "ALTER TABLE rate_limit_bucket DROP CONSTRAINT rate_limit_bucket_unique")) {
            ps.executeUpdate();
        }
    }

    private static void restoreUniqueIndex() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "ALTER TABLE rate_limit_bucket ADD CONSTRAINT rate_limit_bucket_unique "
                             + "UNIQUE (tenant_id, bucket_key, window_start)")) {
            ps.executeUpdate();
        }
    }

    private static void purgeBucket(String bucketKey) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM rate_limit_bucket WHERE bucket_key = ?")) {
            ps.setString(1, bucketKey);
            ps.executeUpdate();
        }
    }

// ── T-9：窗口语义 ───────────────────────────────────────────────

    @Test
    @DisplayName("T-9 ⚠️ 新窗口内计数归零（固定窗口可被重置）")
    void newWindowResetsCount() throws Exception {
        String bucket = uniqueBucket("t9");
        // 窗口长度 1 秒：先打满，等窗口切换后应重新放行
        var limiter = new JdbcRateLimiter(appPool, 1, 1L);
        assertThat(limiter.check(tenantA, bucket)).isEqualTo(RateLimitPort.Decision.ALLOWED);
        assertThat(limiter.check(tenantA, bucket)).isEqualTo(RateLimitPort.Decision.REJECTED);

        Thread.sleep(Duration.ofSeconds(2).toMillis());

        assertThat(limiter.check(tenantA, bucket))
                .as("⚠️ 新窗口内应重新放行（窗口按 floor 对齐）")
                .isEqualTo(RateLimitPort.Decision.ALLOWED);
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private static int bucketRowCount(String tenantId, String bucketKey) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM rate_limit_bucket "
                             + "WHERE tenant_id = ?::uuid AND bucket_key = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, bucketKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计桶行数失败", e);
        }
    }

}
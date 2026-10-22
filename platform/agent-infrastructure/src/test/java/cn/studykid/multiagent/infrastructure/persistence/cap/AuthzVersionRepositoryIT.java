package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 授权版本仓储集成测试（W-PERM-P0-T3 · §6.1 / 06 §1.5）。
 *
 * <p><b>⚠️ 必须跑在真实 PG17 上</b>：原子自增依赖 PostgreSQL 的
 * {@code ON CONFLICT DO UPDATE} 唯一索引仲裁与行锁语义，替身验证等于没验证。
 *
 * <p>本类锁住的是<b>撤权即时全实例失效</b>的技术地基：
 * 授权版本若不可靠，「撤权」就只是日志里的一句话。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class AuthzVersionRepositoryIT extends CapITBase {

    private static final String PRINCIPAL = "principal-cap-it";

    private AuthzVersionPort versions() {
        return new JdbcAuthzVersionRepository(appPool);
    }

    // ── 初始值与单调递增 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 初始值为 1（06 §1.5 第 1 条）")
    void initialVersionIsOne() {
        AuthzVersionPort repo = versions();
        AuthzVersion created = repo.initialize(tenantA, PRINCIPAL);
        assertThat(created.authzVersion())
                .as("⛔ 初始版本不是 1：撤权后与之比较的基线就不对")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ initialize 幂等：重复调用不改变版本号")
    void initializeIsIdempotent() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);
        repo.bump(tenantA, PRINCIPAL);
        long afterBump = repo.require(tenantA, PRINCIPAL).authzVersion();

        AuthzVersion again = repo.initialize(tenantA, PRINCIPAL);
        assertThat(again.authzVersion())
                .as("⛔ initialize 把版本重置了：等于把已撤销的授权又变新鲜")
                .isEqualTo(afterBump);
    }

    @Test
    @DisplayName("✅ bump 原子递增，返回新版本号")
    void bumpIncrements() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);

        assertThat(repo.bump(tenantA, PRINCIPAL)).isEqualTo(2L);
        assertThat(repo.bump(tenantA, PRINCIPAL)).isEqualTo(3L);
        assertThat(repo.require(tenantA, PRINCIPAL).authzVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("✅ 重复撤权只继续递增，版本不回退（06 §1.5 第 7 条）")
    void repeatedRevocationNeverGoesBackwards() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);

        long previous = 0L;
        for (int i = 0; i < 5; i++) {
            long current = repo.bump(tenantA, PRINCIPAL);
            assertThat(current)
                    .as("⛔ 版本回退：撤权记录被抹掉，旧上下文会重新通过校验")
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    // ── 并发 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 并发自增单调无丢失（06 §1.5 第 2 条 · S3c/S3e）")
    void concurrentBumpsDoNotLoseUpdates() throws Exception {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);

        int threads = 8;
        int perThread = 10;
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long last = 0L;
                    for (int i = 0; i < perThread; i++) {
                        last = repo.bump(tenantA, PRINCIPAL);
                    }
                    return last;
                }));
            }
            start.countDown();
            for (Future<Long> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long expected = AuthzVersion.INITIAL_VERSION + total;
        long actual = repo.require(tenantA, PRINCIPAL).authzVersion();
        assertThat(actual)
                .as("⛔ 并发自增丢失更新：期望 %d，实际 %d —— 撤权会静默失效", expected, actual)
                .isEqualTo(expected);
    }

    // ── 强一致读与 fail-closed ─────────────────────────────────────

    @Test
    @DisplayName("✅ 读到的版本立即反映刚发生的递增（强一致，非异步缓存）")
    void readIsStronglyConsistent() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);
        long bumped = repo.bump(tenantA, PRINCIPAL);

        // ⚠️ 必须是**立即**可见：撤权 SLA 依赖于此。
        //    若实现走异步缓存，这里会读到旧值，撤权延迟不可控。
        assertThat(repo.find(tenantA, PRINCIPAL)).isPresent();
        assertThat(repo.require(tenantA, PRINCIPAL).authzVersion()).isEqualTo(bumped);
    }

    @Test
    @DisplayName("✅ 持有陈旧版本的上下文被判为 stale")
    void staleCandidateDetected() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);
        repo.bump(tenantA, PRINCIPAL);

        AuthzVersion current = repo.require(tenantA, PRINCIPAL);
        assertThat(current.isStale(current.authzVersion() - 1))
                .as("⛔ 陈旧版本未被识别：撤权后旧上下文仍能通过授权")
                .isTrue();
        assertThat(current.isStale(current.authzVersion()))
                .as("⛔ 当前版本被判为 stale：正常请求会被误拒")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 无记录时 require 抛 fail-closed 异常（06 §1.5 第 6 条）")
    void missingRecordFailsClosed() {
        AuthzVersionPort repo = versions();
        assertThatThrownBy(() -> repo.require(tenantA, "principal-never-seen"))
                .as("⛔ 读不到版本时未 fail-closed：系统会按『无授权』或『有授权』放行")
                .isInstanceOf(AuthzUnavailableException.class);
    }

    // ── 跨租户作用域 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ 版本作用域为 (tenant, principal)：A 的主体在 B 租户无记录")
    void scopeIsTenantPlusPrincipal() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);

        assertThat(repo.find(tenantB, PRINCIPAL))
                .as("⛔ 跨租户读到版本：主体身份变化未按租户分别 bump")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ RLS：非特权角色看不到其他租户的授权版本行")
    void rlsIsolatesAuthzVersionRows() {
        AuthzVersionPort repo = versions();
        repo.initialize(tenantA, PRINCIPAL);

        long visibleToB = appCountAsTenant(tenantB,
                "SELECT count(*) FROM cap_authz_version");
        assertThat(visibleToB)
                .as("⛔ B 能看到 A 的授权版本行 = RLS 失效")
                .isZero();
    }
}

package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 事务边界收口集成测试（P2-D05 §5.1）。
 *
 * <p><b>验证的真实性质</b>：外层 {@code TransactionRunner} 的事务
 * <b>真的覆盖</b> Repository 的写入，而不是各写各的。
 *
 * <p>⚠️ <b>为何必须用真实数据库</b>：此前 Repository 每个方法
 * <b>自己开连接自己 commit</b>，导致外层事务形同虚设。
 * 这类缺陷<b>单元测试永远抓不到</b>——单测直接 new 对象，
 * 看不到连接从哪来、何时提交。只有真实事务才能证伪。
 *
 * <p><b>⚠️ 用非特权角色（app_agent）运行</b>：超级用户绕过所有 RLS 策略，
 * 会得到「隔离失效」的虚假结论，方向完全相反。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class TransactionBoundaryIT {

    private static HikariDataSource appPool;
    private static JdbcRunCreationRepository repo;
    private static String tenantA;
    private static String snapshotId;

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        // ⚠️ 池大小=1：强制最严场景。
        //    池大于 1 时，「复用外层连接」与「另开连接」可能拿到同一物理连接，
        //    掩盖连接复用缺陷；池=1 时若代码错误地另开连接，会取不到连接而失败。
        cfg.setMaximumPoolSize(1);
        appPool = new HikariDataSource(cfg);
        repo = new JdbcRunCreationRepository(appPool);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
    }

    @AfterEach
    void clearHolder() {
        // ⚠️ 必须清理：否则残留的 ThreadLocal 会让后续测试
        // 复用上一个测试的连接，症状是「单跑绿、全跑红」
        TransactionalConnectionHolder.clear();
    }

    // ── T1：事务真实生效 ─────────────────────────────────────────────

    @Test
    @DisplayName("T1 外层事务内抛异常 → 四者全部零残留")
    void rollbackRemovesAllFourArtifacts() throws Exception {
        String runId = java.util.UUID.randomUUID().toString();
        IdempotencyKey key = new IdempotencyKey("run-create", "tx-" + runId);
        Map<String, Object> input = Map.of("topic", "分数");
        OutboxMessage msg = new OutboxMessage(runId, tenantA, 0, 0L, Instant.now());

        assertThatThrownBy(() -> TransactionalConnectionHolder.inTransaction(
                appPool, sysCtx(tenantA), conn -> {
                    repo.insertRunAtomically(tenantA, runId, snapshotId, key,
                            RequestDigest.of(input), input, "corr-" + runId, msg);
                    // 事务内制造失败：其后的回滚必须撤销上面全部写入
                    throw new IllegalStateException("故意失败以验证回滚");
                }))
                .isInstanceOf(IllegalStateException.class);

        // ⚠️ 四者必须全部为 0：这是红线 3「四者同生共死」的直接证据
        assertThat(countRun(tenantA, runId)).as("Run 不应残留").isZero();
        assertThat(countIdempotency(tenantA, key.keyValue())).as("幂等记录不应残留").isZero();
        assertThat(countOutbox(tenantA, runId)).as("Outbox 不应残留").isZero();
        assertThat(countEvent(tenantA, runId)).as("初始事件不应残留").isZero();
    }

    @Test
    @DisplayName("T2 事务提交成功 → 四者全部存在")
    void commitPersistsAllFourArtifacts() throws Exception {
        String runId = java.util.UUID.randomUUID().toString();
        IdempotencyKey key = new IdempotencyKey("run-create", "tx-ok-" + runId);
        Map<String, Object> input = Map.of("topic", "几何");
        OutboxMessage msg = new OutboxMessage(runId, tenantA, 0, 0L, Instant.now());

        TransactionalConnectionHolder.inTransaction(appPool, sysCtx(tenantA), conn -> {
            repo.insertRunAtomically(tenantA, runId, snapshotId, key,
                    RequestDigest.of(input), input, "corr-ok", msg);
            return null;
        });

        assertThat(countRun(tenantA, runId)).as("Run 应已提交").isEqualTo(1);
        assertThat(countIdempotency(tenantA, key.keyValue())).as("幂等记录应已提交").isEqualTo(1);
        assertThat(countOutbox(tenantA, runId)).as("Outbox 应已提交").isEqualTo(1);
        assertThat(countEvent(tenantA, runId)).as("初始事件应已提交").isEqualTo(1);
    }

    // ── T3：连接复用（这是本次改造的核心）───────────────────────────

    @Test
    @DisplayName("T3a ⚠️ Repository 确实复用外层连接（而非另开）")
    void repositoryReusesOuterConnection() throws Exception {
        // ⚠️ 池大小=1：若 Repository 另开连接，将因取不到连接而失败。
        //    这正是「连接复用」的可证伪断言。
        String runId = java.util.UUID.randomUUID().toString();
        IdempotencyKey key = new IdempotencyKey("run-create", "tx-reuse-" + runId);
        Map<String, Object> input = Map.of("topic", "函数");
        OutboxMessage msg = new OutboxMessage(runId, tenantA, 0, 0L, Instant.now());

        TransactionalConnectionHolder.inTransaction(appPool, sysCtx(tenantA), conn -> {
            assertThat(TransactionalConnectionHolder.current())
                    .as("事务内必须有当前连接").isNotNull();
            assertThat(TransactionalConnectionHolder.current())
                    .as("Repository 必须拿到外层同一个连接对象")
                    .isSameAs(conn);

            repo.insertRunAtomically(tenantA, runId, snapshotId, key,
                    RequestDigest.of(input), input, "corr-reuse", msg);

            // ⚠️ 此刻尚未提交，但同连接内应能读到自己的写入。
            //    断言包在 try/catch 里：TxAction 只允许 SQLException，
            //    而 JDBC 查询助手声明的是 Exception。
            try {
                assertThat(countRunOn(conn, tenantA, runId))
                        .as("同一事务内应能读到未提交的写入 —— "
                                + "读不到说明 Repository 用了别的连接，事务未生效")
                        .isEqualTo(1);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(countRun(tenantA, runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T3b 嵌套事务复用外层连接，不开新事务")
    void nestedTransactionReusesOuter() throws Exception {
        TransactionalConnectionHolder.inTransaction(appPool, sysCtx(tenantA), outer -> {
            Connection captured = TransactionalConnectionHolder.current();

            TransactionalConnectionHolder.inTransaction(appPool, sysCtx(tenantA), inner -> {
                assertThat(TransactionalConnectionHolder.current())
                        .as("嵌套必须复用同一连接").isSameAs(captured);
                return null;
            });
            return null;
        });
    }

    @Test
    @DisplayName("T3c ⚠️ 异常路径下 ThreadLocal 被清理（防跨请求泄漏）")
    void holderClearedOnFailure() throws Exception {
        assertThatThrownBy(() -> TransactionalConnectionHolder.inTransaction(
                appPool, sysCtx(tenantA), conn -> {
                    throw new IllegalStateException("故意失败");
                })).isInstanceOf(IllegalStateException.class);

        // ⚠️ 必须为 null：残留会让下一个请求沿用上一个租户的事务，
        //    构成跨租户数据泄漏（RLS 最隐蔽的失效模式）
        assertThat(TransactionalConnectionHolder.current())
                .as("异常路径必须清理 ThreadLocal").isNull();
    }

    @Test
    @DisplayName("T3d 重复绑定被拒绝（防止外层回滚被绕过）")
    void doubleBindRejected() {
        TransactionalConnectionHolder.inTransaction(appPool, sysCtx(tenantA), conn -> {
            assertThatThrownBy(() -> TransactionalConnectionHolder.bind(conn))
                    .as("重复绑定必须失败：另开连接会让外层回滚失效")
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
    }

    // ── 查询辅助 ────────────────────────────────────────────────────

    private static TenantContext sysCtx(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    private static String adminQuery(String sql) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        try (HikariDataSource adminPool = new HikariDataSource(cfg);
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next())
                    .as("种子数据缺失，请先执行 ./scripts/dev-postgres.sh start")
                    .isTrue();
            return rs.getString(1);
        }
    }

    private static long inTenant(String tenantId, String sql, Object... args) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn, sysCtx(tenantId));
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        return rs.getLong(1);
                    }
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    private static long countOn(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static long countRun(String t, String runId) throws Exception {
        return inTenant(t, "SELECT count(*) FROM run WHERE tenant_id=?::uuid AND run_id=?::uuid",
                t, runId);
    }

    private static long countRunOn(Connection c, String t, String runId) throws SQLException {
        return countOn(c, "SELECT count(*) FROM run WHERE tenant_id=?::uuid AND run_id=?::uuid",
                t, runId);
    }

    private static long countIdempotency(String t, String keyValue) throws Exception {
        return inTenant(t, "SELECT count(*) FROM idempotency_record "
                + "WHERE tenant_id=?::uuid AND idempotency_key=?", t, keyValue);
    }

    private static long countOutbox(String t, String runId) throws Exception {
        return inTenant(t, "SELECT count(*) FROM outbox_event "
                + "WHERE tenant_id=?::uuid AND run_id=?::uuid", t, runId);
    }

    private static long countEvent(String t, String runId) throws Exception {
        return inTenant(t, "SELECT count(*) FROM run_event "
                + "WHERE tenant_id=?::uuid AND run_id=?::uuid", t, runId);
    }
}
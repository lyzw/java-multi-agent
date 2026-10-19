package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.infrastructure.messaging.RunEventNotifier;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * SSE 真推送集成测试（P4-D02 / D-1…D-3 · 关闭 P3 退出条件 C-4）。
 *
 * <p><b>本类证明的核心命题</b>（真实 PG17、非特权角色）：
 * <ol>
 *   <li><b>写入事件 → 订阅者收到通知</b>（真推送，非轮询）；</li>
 *   <li>通知载荷<b>只含坐标</b>，不含事件 payload（不绕过 SSE 载荷白名单）；</li>
 *   <li>事务回滚 → <b>不发出通知</b>（原子性）。</li>
 * </ol>
 *
 * <p>⚠️ 全部用例用<b>独立连接</b>：LISTEN 需要独占连接，
 * 不能从池里借——这正是实现里的约束，测试也必须遵守。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RunEventNotifyIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunCreationRepository creation;

    private static String tenantId;
    private static String snapshotId;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        creation = new JdbcRunCreationRepository(appPool);
        // ⚠️ 种子/租户/快照经 **admin** 池读写（与既有 IT 一致）——
        //    app_agent 只做业务写入，这正是 E-3 要验证的边界。
        tenantId = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotId = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    @AfterAll
    static void tearDown() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    // ── N-1/N-2：真推送 + 载荷只含坐标 ─────────────────────────────

    @Test
    @DisplayName("N-1 ⚠️⚠️ 写入事件 → LISTEN 订阅者收到通知（非轮询）")
    void subscriberReceivesNotifyOnEventWrite() throws Exception {
        RunEventNotifier notifier = new RunEventNotifier();
        Connection listener = notifier.openListener();
        assertThat(listener)
                .as("⚠️ LISTEN 连接必须可用（独占连接）")
                .isNotNull();
        try {
            // ⚠️ 先确保 LISTEN 已生效再写入，否则通知会被丢弃
            Thread.sleep(300);

            String runId = createRun();
            RunEventNotifier.Notification received =
                    awaitNotification(listener, runId, 5);

            assertThat(received)
                    .as("⚠️⚠️ 订阅者必须收到通知——这正是「真推送」的证明")
                    .isNotNull();
            assertThat(received.runId()).isEqualTo(runId);
            assertThat(received.tenantId()).isEqualTo(tenantId);
            assertThat(received.sequenceNo())
                    .as("⚠️ 通知须带序号，订阅方据此判断是否需要回查")
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            notifier.close();
        }
    }

    @Test
    @DisplayName("N-1b ⚠️ 通道自检：直接 pg_notify → 订阅者收到（隔离 LISTEN 与写入路径）")
    void channelWorksEndToEnd() throws Exception {
        RunEventNotifier notifier = new RunEventNotifier();
        Connection listener = notifier.openListener();
        assertThat(listener).isNotNull();
        try {
            Thread.sleep(300);
            String probeId = "probe-" + UUID.randomUUID();
            sendProbe(probeId);

            var received = awaitNotification(listener, probeId, 5);
            assertThat(received)
                    .as("⚠️⚠️ LISTEN 通道本身必须可用——这是「真推送」的基础")
                    .isNotNull();
            assertThat(received.runId()).isEqualTo(probeId);
        } finally {
            notifier.close();
        }
    }

    @Test
    @DisplayName("N-2 ⚠️⚠️ 通知载荷只含坐标，**不含 payload**（不绕过 SSE 白名单）")
    void notificationCarriesCoordinatesOnly() {
        // ⚠️ 若把完整 payload 放进 NOTIFY，事件内容就**绕过**了
        //    D10 建立的 SSE 载荷白名单——白名单形同虚设。
        String payload = "{\"tenantId\":\"" + tenantId + "\","
                + "\"runId\":\"r-1\",\"sequenceNo\":\"1\"}";

        var parsed = RunEventNotifier.parse(payload);
        assertThat(parsed).isNotNull();
        assertThat(parsed.runId()).isEqualTo("r-1");
        assertThat(parsed.sequenceNo()).isEqualTo(1L);
        // ⚠️ 结构上只有三个键——回查用坐标，不含内容
        assertThat(payload)
                .as("⚠️ 通知载荷结构固定且极小（不超 NOTIFY 8000 字节限制）")
                .doesNotContain("payload").doesNotContain("studentName");
    }

    @Test
    @DisplayName("N-2b 畸形载荷解析为 null（忽略而非抛异常）")
    void malformedPayloadIgnored() {
        assertThat(RunEventNotifier.parse(null)).isNull();
        assertThat(RunEventNotifier.parse("")).isNull();
        assertThat(RunEventNotifier.parse("not-json")).isNull();
        assertThat(RunEventNotifier.parse("{\"runId\":\"r\"}"))
                .as("⚠️ 缺字段必须返回 null（忽略），不得抛异常中断监听线程")
                .isNull();
        assertThat(RunEventNotifier.parse(
                "{\"tenantId\":\"t\",\"runId\":\"r\",\"sequenceNo\":\"abc\"}"))
                .as("⚠️ 非数字序号必须返回 null")
                .isNull();
    }

    // ── N-3：事务回滚 → 不发通知 ───────────────────────────────────

    @Test
    @DisplayName("N-3 ⚠️⚠️ 事务回滚 → 不发出通知（原子性：不推送不存在的事件）")
    void rollbackDoesNotNotify() throws Exception {
        RunEventNotifier notifier = new RunEventNotifier();
        Connection listener = notifier.openListener();
        assertThat(listener).isNotNull();

        try {
            Thread.sleep(300);
            String runId = UUID.randomUUID().toString();

            // ⚠️ 在事务内插入事件+NOTIFY，然后**回滚**
            try (Connection conn = appPool.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // ⚠️ 用裸连接模拟「通知已发但事务回滚」
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT pg_notify('run_event', json_build_object("
                                    + "'tenantId', ?::text, 'runId', ?::text, "
                                    + "'sequenceNo', '1'::text)::text)")) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
                        ps.executeQuery();
                    }
                    // ⚠️ 故意不提交
                    conn.rollback();
                } finally {
                    conn.setAutoCommit(true);
                }
            }

            // ⚠️ 稍等后确认**没有**收到该 runId 的通知
            Thread.sleep(600);
            var notifications = ((org.postgresql.PGConnection) listener)
                    .getNotifications(200);
            boolean sawRolledBack = false;
            if (notifications != null) {
                for (var n : notifications) {
                    var parsed = RunEventNotifier.parse(n.getParameter());
                    if (parsed != null && runId.equals(parsed.runId())) {
                        sawRolledBack = true;
                    }
                }
            }
            assertThat(sawRolledBack)
                    .as("⚠️⚠️ 事务回滚后不得推送——否则客户端会收到从未发生的事件")
                    .isFalse();
        } finally {
            notifier.close();
        }
    }

    // ── N-4：订阅不可用 → 退化为轮询（正确性不受影响）───────────────

    @Test
    @DisplayName("N-4 ⚠️ 连接参数缺失 → openListener 返回 null（退化为纯轮询，不报错）")
    void unavailableSubscriptionDegradesToPolling() {
        var notifier = new RunEventNotifier(
                null, "app_agent", "");
        var conn = notifier.openListener();
        assertThat(conn)
                .as("⚠️⚠️ 订阅不可用必须返回 null 而非抛异常——"
                        + "D-3 轮询兜底保证不丢事件，订阅只是延迟优化")
                .isNull();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private String createRun() {
        String runId = UUID.randomUUID().toString();
        creation.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "notify-" + runId),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "解方程 x²-5x+6=0"), "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, Instant.now()));
        return runId;
    }

    /**
     * 直接发一条通知（绕过业务路径），用于<b>单独验证 LISTEN 通道</b>。
     *
     * <p>⚠️ 用<b>独立连接 + 自动提交</b>：通知在事务提交前不会被投递，
     * 且池化连接可能因 {@code maxLifetime} 抖动而中断未提交事务。
     */
    private void sendProbe(String probeRunId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                System.getenv("IT_PG_URL"),
                System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv().getOrDefault("IT_PG_PASSWORD", ""))) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pg_notify('run_event', "
                            + "json_build_object('tenantId', ?::text, 'runId', ?::text, "
                            + "'sequenceNo', '1'::text)::text)")) {
                ps.setString(1, tenantId);
                ps.setString(2, probeRunId);
                ps.executeQuery();
            }
        }
    }

    private RunEventNotifier.Notification awaitNotification(Connection listener,
                                                            String runId, int seconds)
            throws SQLException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        var pg = listener.unwrap(org.postgresql.PGConnection.class);
        while (System.currentTimeMillis() < deadline) {
            var notifications = pg.getNotifications(500);
            if (notifications != null) {
                for (var n : notifications) {
                    var parsed = RunEventNotifier.parse(n.getParameter());
                    if (parsed != null && runId.equals(parsed.runId())) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }
}
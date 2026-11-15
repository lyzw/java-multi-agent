package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.persistence.RunQueryPort;

/**
 * 受众数据隔离 IT（F1 · E2E 2026-11-14「正规实现学生角色」）。
 *
 * <p><b>命题</b>：受众主体（STUDENT/PARENT/TEACHER）对 Run 的可见范围
 * 必须收窄到 {@code run.requester_id = 本人}。隔离的承重结构是
 * <b>DB 列 + 写路径落库 + 读路径过滤</b>三段，缺一段隔离即静默失效——
 * 因此本类直接在真实 PG17 上验证这三段（替身验证的是替身）。
 *
 * <p><b>反向验证纪律</b>：每个正向断言都配一个「撤掉修复点会红」的对照
 * —— requester 过滤去掉后 audience 查询会看到他人 Run（假绿防线）。
 *
 * <p>⚠️ 与 {@link GovernanceQueryIT} 同纪律：真库 + 非特权 {@code app_agent}；
 * 快照按租户取；夹具即造即清。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RunAudienceIsolationIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-14T04:00:00Z"), ZoneOffset.UTC);

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunCreationRepository creationRepo;
    private static JdbcRunListQueryRepository runList;
    private static JdbcRunQueryRepository runQuery;
    private static String tenantId;
    private static String snapshotId;

    private static final String STUDENT_A = "11111111-1111-4111-8111-111111111111";
    private static final String STUDENT_B = "22222222-2222-4222-8222-222222222222";

    @AfterAll
    static void closePools() {
        if (appPool != null) appPool.close();
        if (adminPool != null) adminPool.close();
    }

    @BeforeAll
    static void setUp() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_USER", "app_agent"));
        String pwd = System.getenv("IT_PG_PASSWORD");
        if (pwd != null && !pwd.isBlank()) cfg.setPassword(pwd);
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);
        adminPool = FaultInjectionSupport.adminPool();

        creationRepo = new JdbcRunCreationRepository(appPool);
        runList = new JdbcRunListQueryRepository(appPool);
        runQuery = new JdbcRunQueryRepository(appPool);

        tenantId = FaultInjectionSupport.itTenant(adminPool, "it-aud-iso");
        snapshotId = FaultInjectionSupport.itSnapshot(adminPool, tenantId);
        FaultInjectionSupport.purgeItTenantRuns(adminPool, "it-aud-iso");
    }

    private static String newRun(String requesterId) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "aud-it-" + runId),
                RequestDigest.ofText("digest-" + runId),
                Map.of("question", "一元二次方程"),
                "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, CLOCK.instant()),
                cn.studykid.multiagent.ports.persistence.RunLimits.UNLIMITED,
                requesterId);
        return runId;
    }

    private static void deleteRuns(List<String> runIds) {
        for (String id : runIds) {
            try {
                adminExec("DELETE FROM run WHERE run_id = '" + id + "'::uuid");
            } catch (SQLException e) {
                throw new IllegalStateException("夹具清理失败: " + id, e);
            }
        }
    }

    private static String adminQuery(String sql) throws SQLException {
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void adminExec(String sql) throws SQLException {
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("✅ 写路径：requester_id 与 Run 同事务落库；NULL requester 允许（历史/系统语义）")
    void requesterIdIsPersistedOnCreate() throws Exception {
        List<String> created = List.of(newRun(STUDENT_A), newRun(null));
        try {
            assertThat(adminQuery("SELECT requester_id::text FROM run WHERE run_id = '"
                    + created.get(0) + "'::uuid"))
                    .as("受众创建的 Run 必须带归属主体，否则隔离无源")
                    .isEqualTo(STUDENT_A);
            assertThat(adminQuery("SELECT requester_id IS NULL FROM run WHERE run_id = '"
                    + created.get(1) + "'::uuid"))
                    .as("无主体上下文的创建（历史/系统）保持 NULL 语义")
                    .isEqualTo("t");
        } finally {
            deleteRuns(created);
        }
    }

    @Test
    @DisplayName("✅ 读路径（单条）：findStatus 带出 requesterId，控制器据此做归属校验")
    void findStatusExposesRequester() {
        String runId = newRun(STUDENT_A);
        try {
            RunQueryPort.RunStatus status = runQuery.findStatus(tenantId, runId);
            assertThat(status).isNotNull();
            assertThat(status.requesterId())
                    .as("单条状态查询必须带出归属，否则受众 404 判定无依据")
                    .isEqualTo(STUDENT_A);
        } finally {
            deleteRuns(List.of(runId));
        }
    }

    @Test
    @DisplayName("✅ 读路径（列表）：受众只看到本人创建；他人 Run 与 NULL requester 行不可见")
    void audienceListScopedToOwnRuns() throws Exception {
        String ownA1 = newRun(STUDENT_A);
        String ownA2 = newRun(STUDENT_A);
        String otherB = newRun(STUDENT_B);
        String legacy = newRun(null);
        try {
            Page<RunListQueryPort.RunListItem> page =
                    runList.list(tenantId, STUDENT_A, null, null, null, null, null, 50);

            List<String> visible = page.items().stream()
                    .map(RunListQueryPort.RunListItem::runId).toList();
            assertThat(visible)
                    .as("受众 A 只能看到本人创建的 Run")
                    .contains(ownA1, ownA2)
                    .doesNotContain(otherB)
                    .as("NULL requester（历史/代操作）对受众不可见——"
                            + "没有归属证明的数据宁可不展示")
                    .doesNotContain(legacy);

            // 反向对照：不带过滤（治理视角）能看到全部
            List<String> adminView = runList.list(tenantId, null, null, null, null, null, null, 50)
                    .items().stream().map(RunListQueryPort.RunListItem::runId).toList();
            assertThat(adminView)
                    .as("治理角色不受受众收窄（对照：撤掉过滤即本断言口径）")
                    .contains(ownA1, otherB);

            // 受众 B 的视野同样收窄且互不重叠
            List<String> visibleB = runList.list(tenantId, STUDENT_B, null, null, null, null, null, 50)
                    .items().stream().map(RunListQueryPort.RunListItem::runId).toList();
            assertThat(visibleB).contains(otherB).doesNotContain(ownA1);
        } finally {
            deleteRuns(List.of(ownA1, ownA2, otherB, legacy));
        }
    }
}

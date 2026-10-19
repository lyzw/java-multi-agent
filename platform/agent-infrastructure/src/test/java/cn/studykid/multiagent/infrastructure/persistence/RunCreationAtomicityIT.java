package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;

/**
 * Outbox 原子提交集成测试（P1-05 / 红线 3）。
 *
 * <p><b>验证「四者同生共死」</b>：
 * <blockquote>Run 创建、初始事件、幂等记录和 Outbox 必须在同一事务中提交。</blockquote>
 *
 * <p>四者任一缺失都会造成不可恢复的不一致——尤其缺 Outbox 会让 Run
 * 永远不被 Worker 领取，任务静默卡死且无任何报错。
 *
 * <p><b>⚠️ 本测试用非特权角色（app_agent）运行</b>——超级用户会绕过 RLS，
 * 导致「写入成功但查不到」或「隔离失效」的虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RunCreationAtomicityIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneOffset.UTC);

    private static HikariDataSource appPool;      // 非特权角色，用于验证 RLS
    private static JdbcRunCreationRepository repo;
    private static String tenantA;
    private static String tenantB;
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
        cfg.setMaximumPoolSize(2);
        appPool = new HikariDataSource(cfg);
        repo = new JdbcRunCreationRepository(appPool);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
        snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
    }

    // ── 查询辅助 ────────────────────────────────────────────────────

    /**
     * 以超级用户读取。
     *
     * <p><b>仅用于测试准备阶段</b>定位租户与快照。非特权角色受 RLS 限制，
     * 未绑定租户上下文时读不到 {@code tenant} 表——这正是期望行为。
     * 被测逻辑一律走非特权连接。
     */
    private static String adminQuery(String sql) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
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

    /**
     * 在租户上下文内执行查询。
     *
     * <p>⚠️ 必须关闭自动提交——{@code set_config(..., is_local=true)} 是
     * 事务局部语义，自动提交模式下每条语句独立事务，绑定立即失效
     * （实测：自动提交可见 0 行，事务内可见 114 行）。
     */
    private static <T> T inTenantScope(String tenantId, TenantQuery<T> body) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
                T result = body.apply(conn);
                conn.rollback();
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static long count(String tenantId, String sql, Object... args) throws Exception {
        return inTenantScope(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static String queryString(String tenantId, String sql, Object... args) throws Exception {
        return inTenantScope(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }

    // ── 测试辅助 ────────────────────────────────────────────────────

    private String createRun(String tenantId, String keySuffix, RequestDigest digest) {
        String runId = UUID.randomUUID().toString();
        repo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", keySuffix + "-" + runId),
                digest,
                Map.of("question", "一元二次方程"),
                "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, CLOCK.instant()));
        return runId;
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 四者同事务提交：Run + 初始事件 + 幂等记录 + Outbox 全部存在")
    void allFourRecordsCreatedAtomically() throws Exception {
        String runId = UUID.randomUUID().toString();
        String key = "atomic-" + runId;

        repo.insertRunAtomically(tenantA, runId, snapshotId,
                new IdempotencyKey("run.create", key),
                RequestDigest.ofText("d1"),
                Map.of("question", "一元二次方程"),
                "corr-" + runId,
                new OutboxMessage(runId, tenantA, 0, 0L, CLOCK.instant()));

        assertThat(count(tenantA,
                "SELECT count(*) FROM run WHERE tenant_id=?::uuid AND run_id=?::uuid", tenantA, runId))
                .as("Run 应存在").isEqualTo(1);
        assertThat(count(tenantA,
                "SELECT count(*) FROM run_event WHERE tenant_id=?::uuid AND run_id=?::uuid", tenantA, runId))
                .as("初始事件应存在（SSE 续传起点）").isEqualTo(1);
        assertThat(count(tenantA,
                "SELECT count(*) FROM idempotency_record WHERE tenant_id=?::uuid AND idempotency_key=?", tenantA, key))
                .as("幂等记录应存在").isEqualTo(1);
        assertThat(count(tenantA,
                "SELECT count(*) FROM outbox_event WHERE tenant_id=?::uuid AND run_id=?::uuid", tenantA, runId))
                .as("Outbox 应存在，否则任务会静默卡死").isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 初始事件 sequence_no 从 1 开始（SSE 续传依据）")
    void initialEventSequenceStartsAtOne() throws Exception {
        String runId = createRun(tenantA, "seq", RequestDigest.ofText("d2"));

        long seq = count(tenantA,
                "SELECT sequence_no FROM run_event WHERE tenant_id=?::uuid AND run_id=?::uuid", tenantA, runId);
        assertThat(seq).as("SSE 续传依赖序号，单调从 1 起").isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ Outbox 消息体载荷最小化 —— 不含 Prompt/学生数据/工具参数")
    void outboxPayloadIsMinimal() throws Exception {
        String runId = createRun(tenantA, "min", RequestDigest.ofText("d3"));

        String payload = queryString(tenantA,
                "SELECT payload::text FROM outbox_event WHERE tenant_id=?::uuid AND run_id=?::uuid", tenantA, runId);

        assertThat(payload)
                .as("MQ 消息难以精确删除，携带即扩大泄露面")
                .doesNotContain("prompt")
                .doesNotContain("studentId")
                .doesNotContain("toolArguments")
                .doesNotContain("一元二次方程");
        assertThat(payload)
                .contains("runId").contains("tenantId").contains("fencingToken");
    }

    @Test
    @DisplayName("⚠️ 事务失败 → 四者全部不落库（不留半成品）")
    void transactionRollsBackEntirely() throws Exception {
        String runId = UUID.randomUUID().toString();
        String key = "rollback-" + runId;

        long runBefore = count(tenantA, "SELECT count(*) FROM run WHERE tenant_id=?::uuid", tenantA);
        long eventBefore = count(tenantA, "SELECT count(*) FROM run_event WHERE tenant_id=?::uuid", tenantA);
        long idemBefore = count(tenantA, "SELECT count(*) FROM idempotency_record WHERE tenant_id=?::uuid", tenantA);
        long outboxBefore = count(tenantA, "SELECT count(*) FROM outbox_event WHERE tenant_id=?::uuid", tenantA);

        // 传入不存在的 snapshotId → 复合外键失败 → 应整体回滚
        assertThatThrownBy(() -> repo.insertRunAtomically(
                tenantA, runId, UUID.randomUUID().toString(),
                new IdempotencyKey("run.create", key),
                RequestDigest.ofText("d4"),
                Map.of("q", "x"), "corr-" + runId,
                new OutboxMessage(runId, tenantA, 0, 0L, CLOCK.instant())))
                .isInstanceOf(RuntimeException.class);

        assertThat(count(tenantA, "SELECT count(*) FROM run WHERE tenant_id=?::uuid", tenantA))
                .as("Run 不应残留").isEqualTo(runBefore);
        assertThat(count(tenantA, "SELECT count(*) FROM run_event WHERE tenant_id=?::uuid", tenantA))
                .as("初始事件不应残留").isEqualTo(eventBefore);
        assertThat(count(tenantA, "SELECT count(*) FROM idempotency_record WHERE tenant_id=?::uuid", tenantA))
                .as("幂等记录不应残留").isEqualTo(idemBefore);
        assertThat(count(tenantA, "SELECT count(*) FROM outbox_event WHERE tenant_id=?::uuid", tenantA))
                .as("Outbox 不应残留").isEqualTo(outboxBefore);
    }

    @Test
    @DisplayName("✅ 幂等：同键同摘要 → REPLAY，返回首次 Run")
    void idempotentReplay() throws Exception {
        String key = "replay-" + UUID.randomUUID();
        RequestDigest digest = RequestDigest.ofText("same-digest");
        IdempotencyKey idemKey = new IdempotencyKey("run.create", key);
        String firstRunId = UUID.randomUUID().toString();

        repo.insertRunAtomically(tenantA, firstRunId, snapshotId, idemKey, digest,
                Map.of("q", "x"), "corr-1",
                new OutboxMessage(firstRunId, tenantA, 0, 0L, CLOCK.instant()));

        IdempotencyDecision decision = repo.findExistingResource(tenantA, idemKey, digest);
        assertThat(decision.kind())
                .as("同键同摘要须判为重复，不得新建 Run")
                .isEqualTo(IdempotencyDecision.Kind.REPLAY);
        assertThat(decision.originalResourceId()).isEqualTo(firstRunId);
        assertThat(decision.shouldExecute()).isFalse();
    }

    @Test
    @DisplayName("⚠️ 幂等：同键异摘要 → CONFLICT（拒绝，绝不静默返回旧结果）")
    void idempotentConflict() throws Exception {
        String key = "conflict-" + UUID.randomUUID();
        IdempotencyKey idemKey = new IdempotencyKey("run.create", key);
        String runId = UUID.randomUUID().toString();

        repo.insertRunAtomically(tenantA, runId, snapshotId, idemKey,
                RequestDigest.ofText("original"),
                Map.of("q", "x"), "corr-2",
                new OutboxMessage(runId, tenantA, 0, 0L, CLOCK.instant()));

        IdempotencyDecision decision = repo.findExistingResource(tenantA, idemKey,
                RequestDigest.ofText("changed"));
        assertThat(decision.isConflict())
                .as("换了参数却复用键，必须判为冲突——否则用户改了参数却被告知已成功")
                .isTrue();
        assertThatThrownBy(decision::throwIfConflict)
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("✅ 幂等键跨租户隔离（同键在不同租户互不冲突）")
    void idempotencyIsTenantScoped() throws Exception {
        String key = "cross-" + UUID.randomUUID();
        IdempotencyKey idemKey = new IdempotencyKey("run.create", key);
        RequestDigest digest = RequestDigest.ofText("shared");

        String runIdA = UUID.randomUUID().toString();
        repo.insertRunAtomically(tenantA, runIdA, snapshotId, idemKey, digest,
                Map.of("q", "x"), "corr-a",
                new OutboxMessage(runIdA, tenantA, 0, 0L, CLOCK.instant()));

        IdempotencyDecision decisionB = repo.findExistingResource(tenantB, idemKey, digest);
        assertThat(decisionB.shouldExecute())
                .as("幂等键必须在租户维度独立计算")
                .isTrue();
    }

    @Test
    @DisplayName("✅ Outbox 发布状态流转：PENDING → PUBLISHED，且标记幂等")
    void outboxPublishMarking() throws Exception {
        String runId = createRun(tenantA, "pub", RequestDigest.ofText("d5"));

        RunCreationPort.PendingOutbox pending = repo.loadPendingOutbox(tenantA, 1000).events()
                .stream().filter(e -> e.runId().equals(runId)).findFirst()
                .orElseThrow(() -> new AssertionError("新建的 Outbox 应为待发布"));

        repo.markOutboxPublished(tenantA, pending.outboxId(), CLOCK.instant());

        assertThat(count(tenantA,
                "SELECT count(*) FROM outbox_event WHERE outbox_id=?::uuid AND state='PUBLISHED'",
                pending.outboxId()))
                .isEqualTo(1);

        // 重复标记须幂等，不报错
        repo.markOutboxPublished(tenantA, pending.outboxId(), CLOCK.instant());
    }

    @Test
    @DisplayName("✅ Outbox 失败标记：记录重试次数供告警")
    void outboxFailureMarking() throws Exception {
        String runId = createRun(tenantA, "fail", RequestDigest.ofText("d6"));

        RunCreationPort.PendingOutbox pending = repo.loadPendingOutbox(tenantA, 1000).events()
                .stream().filter(e -> e.runId().equals(runId)).findFirst()
                .orElseThrow(() -> new AssertionError("新建的 Outbox 应为待发布"));

        repo.markOutboxFailed(tenantA, pending.outboxId(), "broker unavailable");

        assertThat(count(tenantA,
                "SELECT attempts FROM outbox_event WHERE outbox_id=?::uuid", pending.outboxId()))
                .as("失败次数须累加，供积压告警使用")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("⚠️ 消息体安全校验：敏感字段被拦截")
    void forbiddenPayloadRejected() {
        assertThatThrownBy(() -> OutboxMessage.assertPayloadSafe(
                Map.of("runId", "r1", "prompt", "系统的秘密提示词")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");

        assertThatThrownBy(() -> OutboxMessage.assertPayloadSafe(
                Map.of("runId", "r1", "studentName", "张三")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> OutboxMessage.assertPayloadSafe(
                Map.of("runId", "r1", "unexpectedField", "x")))
                .as("白名单外字段一律拒绝，防止「顺手把整个 payload 塞进去」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("✅ 消息体往返序列化不丢字段")
    void messageRoundTrip() {
        OutboxMessage msg = new OutboxMessage("run-1", "tenant-1", 2, 7L, CLOCK.instant());
        OutboxMessage restored = OutboxMessage.fromPayload(msg.toPayload());
        assertThat(restored.runId()).isEqualTo("run-1");
        assertThat(restored.tenantId()).isEqualTo("tenant-1");
        assertThat(restored.attempt()).isEqualTo(2);
        assertThat(restored.fencingToken()).isEqualTo(7L);
    }
}
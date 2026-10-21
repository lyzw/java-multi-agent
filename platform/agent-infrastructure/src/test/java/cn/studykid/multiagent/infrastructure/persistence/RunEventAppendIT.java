package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunEventPort;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;

/**
 * Run 事件追加集成测试（P4-D12 / G4 · E-3～E-5）。
 *
 * <p><b>⛔ 本测试存在的理由（P4-D08 R-1 的同形态复现）</b>：
 * {@link JdbcRunEventAppendRepository} 在落地时**零测试**——
 * 「类写完了、端口定义了、Worker 也调用了」看起来全对，
 * 但**没有任何一个测试连过真实 Postgres**。
 * 若 SQL 里把列名写错、或序号分配逻辑在真实约束下会撞车，
 * <b>整个测试套件照样全绿</b>，而生产时间线永远是空的——
 * 这正是 R-1（工具未注册为 Bean → 工具从不执行 → 表永远为空）的翻版。
 *
 * <p><b>本测试必须跑在真实 PG 上</b>：序号唯一约束（{@code run_event_seq_unique}）、
 * RLS 强制策略、外键约束，全都是<b>只有真实数据库才存在</b>的规则；
 * 用 H2/内存替身验证等于没验证。
 *
 * <p><b>⚠️ 用非特权角色（app_agent）</b>：超级用户绕过 RLS，
 * 会得出「隔离失效」或「写进去读不到」的虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RunEventAppendIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);

    private static HikariDataSource appPool;
    private static JdbcRunEventAppendRepository appendRepo;
    private static JdbcRunEventQueryRepository queryRepo;
    private static RunCreationPort creationRepo;
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
        // ⚠️ 并发用例需要多条连接
        cfg.setMaximumPoolSize(8);
        appPool = new HikariDataSource(cfg);

        appendRepo = new JdbcRunEventAppendRepository(appPool);
        queryRepo = new JdbcRunEventQueryRepository(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
        snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ E-3/E-4 追加的事件真的落库且可回读（RUN_STARTED/TOOL_INVOKED/RUN_SUCCEEDED）")
    void appendedEventsArePersistedAndReadable() {
        String runId = newRun(tenantA);

        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_STARTED,
                Map.of("state", "RUNNING", "attempt", 1));
        appendRepo.append(tenantA, runId, RunEventPort.Types.TOOL_INVOKED,
                Map.of("toolName", "record_weakness", "operationState", "SUCCEEDED"));
        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_SUCCEEDED,
                Map.of("state", "SUCCEEDED"));

        List<RunEventQueryPort.RunEvent> events =
                queryRepo.findAfter(tenantA, runId, 0, 100);

        assertThat(events)
                .as("⚠️ 此前生产无写入方 → 时间线只有 RUN_CREATED；本用例证明追加链路真的通")
                .extracting(RunEventQueryPort.RunEvent::eventType)
                .containsExactly(
                        "RUN_CREATED",              // 创建事务写入的初始事件
                        RunEventPort.Types.RUN_STARTED,
                        RunEventPort.Types.TOOL_INVOKED,
                        RunEventPort.Types.RUN_SUCCEEDED);

        assertThat(events.get(1).payload())
                .as("载荷应原样可回读（小整数应还原为 Integer，与写入端对称）")
                .containsEntry("state", "RUNNING")
                .containsEntry("attempt", 1);
        assertThat(events.get(2).payload())
                .containsEntry("toolName", "record_weakness")
                .containsEntry("operationState", "SUCCEEDED");
    }

    @Test
    @DisplayName("✅ E-5 序号单调递增、不重复、严格大于已有最大值")
    void sequenceIsMonotonicAndUnique() {
        String runId = newRun(tenantA);

        IntStream.rangeClosed(1, 5).forEach(i -> appendRepo.append(
                tenantA, runId, RunEventPort.Types.TOOL_INVOKED,
                Map.of("toolName", "tool-" + i, "operationState", "SUCCEEDED")));

        List<Long> sequences = queryRepo.findAfter(tenantA, runId, 0, 100).stream()
                .map(RunEventQueryPort.RunEvent::sequenceNo)
                .toList();

        assertThat(sequences)
                .as("序号必须严格递增——它是 SSE 续传的唯一依据")
                .isSorted()
                .doesNotHaveDuplicates();

        // 再追加一条，序号必须接在最大值之后（而非复用或跳回）
        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_SUCCEEDED,
                Map.of("state", "SUCCEEDED"));
        List<RunEventQueryPort.RunEvent> after =
                queryRepo.findAfter(tenantA, runId, sequences.get(sequences.size() - 1), 10);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).sequenceNo())
                .as("续传游标之后的下一条，序号必须大于游标")
                .isGreaterThan(sequences.get(sequences.size() - 1));
    }

    @Test
    @DisplayName("⛔ E-5b 并发追加同一 Run 不撞唯一约束（锁 run 行是真起作用的）")
    void concurrentAppendsDoNotCollide() throws Exception {
        String runId = newRun(tenantA);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        appendRepo.append(tenantA, runId, RunEventPort.Types.TOOL_INVOKED,
                                Map.of("toolName", "tool-" + i, "operationState", "SUCCEEDED"));
                        return null;
                    })
                    .toList();
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> sequences = queryRepo.findAfter(tenantA, runId, 0, 100).stream()
                .map(RunEventQueryPort.RunEvent::sequenceNo)
                .toList();

        assertThat(sequences)
                .as("⚠️ 若序号分配没锁 run 行，并发下会算出相同序号并撞 run_event_seq_unique")
                .doesNotHaveDuplicates();
        assertThat(sequences)
                .as("并发追加的条数必须全部落库（不得静默丢弃）")
                .hasSize(threads + 1);   // +1 = RUN_CREATED
    }

    @Test
    @DisplayName("⛔ 租户隔离：A 追加的事件对 B 不可见")
    void crossTenantEventsAreInvisible() {
        String runIdA = newRun(tenantA);
        appendRepo.append(tenantA, runIdA, RunEventPort.Types.RUN_SUCCEEDED,
                Map.of("state", "SUCCEEDED"));

        assertThat(queryRepo.findAfter(tenantB, runIdA, 0, 100))
                .as("⛔ 跨租户读取必须为空（RLS + 显式 tenant_id 双保险）")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 非标量载荷被拒绝（fail-closed），而不是静默丢弃")
    void nonScalarPayloadIsRejected() {
        String runId = newRun(tenantA);

        assertThatThrownBy(() -> appendRepo.append(tenantA, runId,
                RunEventPort.Types.TOOL_INVOKED,
                Map.of("payload", Map.of("prompt", "学生提交的原文"))))
                .as("⛔ 非标量载荷必须拒绝——静默丢弃会让「我明明写了却没看到」无法排查")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(queryRepo.findAfter(tenantA, runId, 0, 100))
                .as("被拒绝的写入不得留下任何行")
                .hasSize(1);   // 仅 RUN_CREATED
    }

    @Test
    @DisplayName("⛔ Run 不存在时拒绝追加（不留孤儿行）")
    void appendToMissingRunIsRejected() {
        String ghost = UUID.randomUUID().toString();

        assertThatThrownBy(() -> appendRepo.append(tenantA, ghost,
                RunEventPort.Types.RUN_STARTED, Map.of("state", "RUNNING")))
                .as("⛔ 事件对已消失的 Run 无意义，且写入会撞外键")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("✅ 载荷中的引号/换行被正确转义（不会破坏 JSON）")
    void payloadSpecialCharsAreEscaped() {
        String runId = newRun(tenantA);

        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_FAILED,
                Map.of("state", "FAILED", "errorCode", "quote\" and \\ and\nnewline"));

        List<RunEventQueryPort.RunEvent> events =
                queryRepo.findAfter(tenantA, runId, 0, 100);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).payload())
                .containsEntry("errorCode", "quote\" and \\ and\nnewline");
    }

    @Test
    @DisplayName("✅ 空载荷合法（写入 {} 而非 NULL）")
    void emptyPayloadIsAllowed() {
        String runId = newRun(tenantA);

        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_CANCELLED, Map.of());

        List<RunEventQueryPort.RunEvent> events =
                queryRepo.findAfter(tenantA, runId, 0, 100);
        assertThat(events.get(1).payload())
                .as("载荷列 NOT NULL，空载荷应落为 {} 而非报错")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 载荷值内含逗号/冒号/方括号时不被切碎（旧手写解析器会劈成垃圾键值对）")
    void delimiterInsidePayloadValueIsNotSplit() {
        String runId = newRun(tenantA);

        // ⚠️ 这三个值各自都含解析器曾经的「分隔符」。
        //    旧实现 body.split(",") 会把 "E1,E2" 劈成两段，
        //    indexOf(':') 会在值内部的冒号处截断——两者都不抛异常，只是值悄悄变了。
        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_FAILED, Map.of(
                "state", "FAILED",
                "errorCode", "E1,E2,E3",
                "toolName", "ns:tool[x]"));

        Map<String, Object> payload =
                queryRepo.findAfter(tenantA, runId, 0, 100).get(1).payload();

        assertThat(payload)
                .as("⛔ 载荷值必须是原值——被切碎的值会让 SSE 消费者拿到错误的错误码")
                .containsEntry("errorCode", "E1,E2,E3")
                .containsEntry("toolName", "ns:tool[x]");
        assertThat(payload)
                .as("⛔ 不得产生垃圾键（劈碎后的残段）")
                .hasSize(3);
    }

    @Test
    @DisplayName("⛔ 载荷值内的换行/制表符被正确还原（旧解析器只处理 \\\" 与 \\\\）")
    void controlCharsInPayloadAreRestored() {
        String runId = newRun(tenantA);

        appendRepo.append(tenantA, runId, RunEventPort.Types.RUN_FAILED,
                Map.of("state", "FAILED", "errorCode", "line1\nline2\ttabbed\rcr"));

        assertThat(queryRepo.findAfter(tenantA, runId, 0, 100).get(1).payload())
                .as("⛔ 控制字符必须还原成真字符，而不是字面量两字符 \\n / \\t / \\r")
                .containsEntry("errorCode", "line1\nline2\ttabbed\rcr");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /**
     * 以超级用户读取——<b>仅用于测试准备阶段</b>定位种子数据。
     * 被测逻辑一律走非特权连接。
     */
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
                    .as("种子数据缺失，请先执行 ./scripts/dev-it-db.sh")
                    .isTrue();
            return rs.getString(1);
        }
    }

    /** 建一个真实 Run（走生产创建端口，顺带产出 RUN_CREATED 初始事件）。 */
    private static String newRun(String tenantId) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "evt-it-" + runId),
                RequestDigest.ofText("digest-" + runId),
                Map.of("question", "一元二次方程"),
                "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, CLOCK.instant()));
        return runId;
    }
}
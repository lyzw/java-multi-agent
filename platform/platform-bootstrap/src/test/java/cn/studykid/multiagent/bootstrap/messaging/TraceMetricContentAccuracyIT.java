package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.messaging.PgQueueListener;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P0 采集链路的<b>内容级</b>端到端验收（DB/PG 队列通道）。
 *
 * <p><b>为什么必须另立一类「内容级」验收</b>：
 * {@link TraceCollectDatabaseChannelEndToEndIT} 证明的是「<b>有一行</b>」——
 * 链路通了、指标落了库。但「有一行」与「<b>这一行的数字算对了</b>」是两件事，
 * 而后者才是看板真正展示的东西。
 * <p>本项目反复出现的失真形态是：<b>不报错，只是数字悄悄错</b>。
 * 已知的一例：事实端口曾把已知工具全集只取<b>目录侧</b>
 * （{@code cap_tool_def}），而 native 工具（{@code get_student_profile} /
 * {@code record_weakness}）<b>不在目录里</b> → 每次 native 调用都被判成
 * 「模型幻觉出的工具」→ {@code unknown_tool} 虚高 → 无效调用率失真。
 * 那个缺陷当时<b>全绿</b>：链路测试只断言「有一行」，没断言「数字对不对」。
 * <p>本类因此断言的是<b>具体数值</b>，而非「非空」。
 *
 * <p><b>断言的口径纪律</b>：{@code null} 表示「未采集」，与「采了是 0」<b>语义不同</b>。
 * 故本类对「有事实来源」的字段断言<b>具体数值</b>，对「无事实来源」的字段断言
 * <b>确为 null</b>（而不是 0）——把「未采集」写成 0 是本项目头号失真来源。
 *
 * <p>⚠️ <b>租户在上下文启动前必须存在</b>（{@code MessagingChannels.resolve}
 * 启动期按 slug 解析 tenantId，fail-closed）→ 静态初始化块里先 provision。
 *
 * <p><b>⚠️ S2 边界</b>：本类只验「同一输入重投 → 输出确定、无副作用、不二次计费」；
 * <b>不验</b>「压缩态不重复落库」——{@code checkpoint} 表 /
 * {@code ContextCheckpointPort} / compaction 幂等键尚未落地，归 S3-M8。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 走 **database 通道**（生产默认形态）。
        "agent.messaging.mode=database",
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类不轮询共享库的 outbox_event。
        "agent.worker.relay-enabled=false",
        // ⚠️ 本租户是本 IT 专用；启动期按 slug 解析 tenantId，故租户必须已存在。
        "agent.worker.tenant-slugs=it-trace-metric-content",
        "agent.messaging.poll-interval-ms=200"
})
@DisplayName("P0 采集链路内容级验收（指标数值必须算对，而非仅「有一行」）")
class TraceMetricContentAccuracyIT {

    private static final String TENANT_SLUG = "it-trace-metric-content";

    /** 重投后等待「第二次真的被消费且覆盖写」的上限；超时即断言红。 */
    private static final long REPLAY_TIMEOUT_MS = 15_000L;

    /** native 工具名：<b>代码注册</b>，不在 {@code cap_tool_def} 目录里。 */
    private static final String NATIVE_TOOL = "get_student_profile";

    /** 幻觉工具名：既不在目录、也不在代码注册表 → 应计入 unknown_tool。 */
    private static final String HALLUCINATED_TOOL = "totally_made_up_tool";

    /** 目录侧工具名：由 {@code cap_tool_def} 提供（造数据时写入）。 */
    private static final String CATALOG_TOOL = "tool-profile-read";

    private static DruidDataSource adminPool;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("traceCollectRoutedMessageSender")
    private MessageSender traceCollectSender;

    /** ⚠️ 生产 PG 队列监听器：用已处理条数证明「第二次投递真的被消费」。 */
    @Autowired
    private PgQueueListener pgQueueListener;

    private String tenantId;
    private String snapshotId;
    private UUID runId;
    private String traceId;
    private UUID catalogOperationId;

    @BeforeAll
    static void setup() {
        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaxActive(2);
        cfg.setName("trace-content-it-admin");
        adminPool = cfg;
        try {
            ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        } catch (SQLException e) {
            throw new IllegalStateException("准备 it- 租户失败: " + e.getMessage(), e);
        }
    }

    @AfterEach
    void cleanup() {
        if (tenantId == null) {
            return;
        }
        // ⚠️ FORCE RLS 下不绑租户的 DELETE **静默删 0 行** → 必须带 tenant_id 谓词。
        // ⚠️ 删除顺序：消息/子表 → run；平台派生表按 trace_id 收尾。
        exec("DELETE FROM public.agent_message_dead_letter WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.agent_message_queue WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.budget_reservation WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
        // ⚠️ 真实 Run 的质检信号会自动落 platform.review_task（L1_GAP / SAMPLE_RANDOM）；
        //    漏删会逐次 verify 累积，把 JdbcReviewTaskRepositoryIT 的待处理列表挤出 LIMIT 50
        //    （2026-11-11 事故）。按 tenant_id 谓词清理，绝不按种子租户字面量全表扫。
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenantId);
        if (traceId != null) {
            exec("DELETE FROM platform.agent_task_outcome WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_eval_sample WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_risk_event WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_trace_feedback WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_trace_metric WHERE trace_id = ?", traceId);
        }
        // ⚠️ **不造 cap_tool_def 行**：目录侧工具用种子里的 `tool-profile-read`
        //    （`kind` CHECK 只允许 native/mcp/http，且这是平台级全局对象，
        //    IT 往里插脏行会污染其它 IT 的「已知工具全集」判定）。
    }

    @Test
    @DisplayName("⚠️⚠️ 指标数值必须算对：工具计数/未知工具判定/token 聚合/未采集=null")
    void metricValuesAreComputedCorrectly() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-content-" + runId;

        // ── 造数：一个 SUCCEEDED 的 Run，带工具调用、用量、步骤 ──
        insertRun();
        insertUsage();          // input=120 / output=45，model_id=minimax-chat
        insertRunSteps();       // 3 个 THINK + 2 个 TOOL 步骤
        catalogOperationId = insertToolOperation(CATALOG_TOOL, "digest-catalog");
        insertToolAttempt(catalogOperationId, CATALOG_TOOL, "SUCCEEDED", null, 120L);
        // ⚠️ native 工具那次**故意用 UPSTREAM_ERROR 而非 INVALID_ARGUMENT**：
        //    这样它只贡献 failed 计数，不贡献 invalid 计数 ——
        //    于是 tool_call_invalid 的唯一来源就是「幻觉工具」，
        //    断言才能精确锁住「未知工具判定 = 目录 ∪ 代码注册表」这条口径。
        UUID nativeOperationId = insertToolOperation(NATIVE_TOOL, "digest-native");
        insertToolAttempt(nativeOperationId, NATIVE_TOOL, "FAILED", "UPSTREAM_ERROR", 30L);
        UUID halluOperationId = insertToolOperation(HALLUCINATED_TOOL, "digest-hallu");
        insertToolAttempt(halluOperationId, HALLUCINATED_TOOL, "SUCCEEDED", null, 10L);

        // ── 触发采集（生产发送器 → PG 队列 → 生产消费者）──
        OutboxMessage msg = new OutboxMessage(runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }
        assertThat(metricRowCount())
                .as("⚠️ 采集事件必须被消费并落一行")
                .isEqualTo(1);

        // ══════════════════════════════════════════════════════════════
        // 断言 1：token 聚合（来自 usage_record 求和）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("input_tokens"))
                .as("⚠️ input_tokens 必须等于 usage_record 的求和，不能是 0 或 null")
                .isEqualTo(120L);
        assertThat(metricLong("output_tokens"))
                .as("⚠️ output_tokens 必须等于 usage_record 的求和")
                .isEqualTo(45L);
        assertThat(metricString("model_id"))
                .as("⚠️ model_id 取模型行的标识，不能混入 tool: 前缀的工具名")
                .isEqualTo("minimax-chat");

        // ══════════════════════════════════════════════════════════════
        // 断言 2：工具计数（3 组工具操作，每组 1 次调用 → 总计 3）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_call_total"))
                .as("⚠️ 工具调用总数必须是 3（三个工具各 1 次）")
                .isEqualTo(3L);
        assertThat(metricLong("tool_call_failed"))
                .as("⚠️ 失败调用数必须是 1（native 工具那次 FAILED）")
                .isEqualTo(1L);

        // ══════════════════════════════════════════════════════════════
        // 断言 3：⚠️ 未知工具判定 = 目录 ∪ 代码注册表（**本类的核心**）
        //   目录侧：tool-profile-read（cap_tool_def）
        //   代码侧：get_student_profile（ToolNameRegistry）
        //   幻觉的：totally_made_up_tool（两侧都没有）
        //   → 只有幻觉工具那 1 次应计入 invalid
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_call_invalid"))
                .as("⚠️⚠️ 无效调用数必须是 1 —— 只有「两侧都没有」的工具才算幻觉。"
                        + "若为 2，说明 native 工具（代码注册但不在目录里）被误判成幻觉工具，"
                        + "unknown_tool 虚高 → 无效调用率失真（不报错，只是数字悄悄错）")
                .isEqualTo(1L);

        // ══════════════════════════════════════════════════════════════
        // 断言 4：tool_rounds 来自 run_step 的 TOOL 步骤数（P7-2 真实口径）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_rounds"))
                .as("⚠️ tool_rounds 必须取自 run_step 的 TOOL 步骤计数（=2），不是退化近似")
                .isEqualTo(2L);

        // ══════════════════════════════════════════════════════════════
        // 断言 5：未采集 = null（**不是 0**）—— 效果类指标本阶段无来源
        // ══════════════════════════════════════════════════════════════
        assertThat(metricIsNull("hallucination"))
                .as("⚠️ 效果类指标无采集来源时必须是 null（未采集），"
                        + "写成 0 会让「没测过幻觉」显示成「幻觉率 0%」")
                .isTrue();
        assertThat(metricIsNull("sampled"))
                .as("⚠️ 抽样标记未采集时必须是 null，不是 false")
                .isTrue();

        // ══════════════════════════════════════════════════════════════
        // 断言 6：工具维度明细表也落了行（按 trace_id + tool_name）
        // ══════════════════════════════════════════════════════════════
        List<String> toolNames = queryStrings(
                "SELECT tool_name FROM platform.agent_trace_tool_call WHERE trace_id = ?",
                traceId);
        assertThat(toolNames)
                .as("⚠️ 工具维度明细必须落 3 行（每个工具一组）")
                .containsExactlyInAnyOrder(CATALOG_TOOL, HALLUCINATED_TOOL, NATIVE_TOOL);
    }

    @Test
    @DisplayName("⚠️⚠️ 同一采集事件重投必须覆盖而不是翻倍（指标/工具/评估/计费/Run 事实逐项不重复）")
    void repeatedCollectIsIdempotent() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-idem-" + runId;

        insertRun();
        // ⚠️ 非零事实夹具：没有 usage / run_step 时，「不二次计费 / 不重跑」只是在比 0 与 0。
        insertUsage();
        insertRunSteps();
        UUID opId = insertToolOperation(CATALOG_TOOL, "digest-idem");
        insertToolAttempt(opId, CATALOG_TOOL, "SUCCEEDED", null, 5L);

        ExecutionFacts runBefore = captureExecutionFacts();
        OutboxMessage msg = new OutboxMessage(runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);

        awaitMetricRowCount(1);
        Instant computedAfterFirst = metricComputedAt();
        assertThat(computedAfterFirst)
                .as("⚠️ 首次采集必须写 computed_at；后续用它证明第二次真的被消费")
                .isNotNull();
        assertThat(captureExecutionFacts())
                .as("⚠️ 首次采集只读事实、不执行 Run：Run 状态/计费/步骤不得变化")
                .isEqualTo(runBefore);
        SideEffectFacts sideAfterFirst = captureSideEffectFacts();
        assertThat(sideAfterFirst.reviewTasks() + sideAfterFirst.taskOutcomes())
                .as("⚠️ 首次采集必须产生至少一条评估副作用（L1_GAP 或 TaskOutcome）—— "
                        + "否则「重投不重复副作用」是空转断言")
                .isPositive();
        long processedBefore = pgQueueListener.totalProcessed();

        // ── 同一事件二次投递：必须真的消费一次，且事实/副作用不重复 ──
        // ⚠️ 等一小会让 now() 必然前进，否则「computed_at 未变」无法区分
        //    「第二次没消费」与「两次落在同一时间精度内」。
        Thread.sleep(100);
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);
        awaitMetricRecomputedAfter(computedAfterFirst);
        // ⚠️ processedBefore 已在首次采集之后取，第二次投递只需 +1 即可证明被消费。
        awaitPgProcessedAtLeast(processedBefore + 1);

        assertThat(metricRowCount())
                .as("⚠️ 重复采集不得新增指标行（主键 trace_id 幂等覆盖）")
                .isEqualTo(1);
        assertThat(metricComputedAt())
                .as("⚠️⚠️ 第二次投递必须真正走到 upsert 覆盖写（computed_at 前移）；"
                        + "否则「行数仍为 1」是「消息没被消费」的假绿")
                .isAfter(computedAfterFirst);
        assertThat(queryInt("SELECT count(*) FROM platform.agent_trace_tool_call "
                + "WHERE trace_id = ?", traceId))
                .as("⚠️ 重复采集不得让工具明细翻倍（必须先删后插）")
                .isEqualTo(1);
        assertThat(metricLong("tool_call_total"))
                .as("⚠️ 重复采集后工具调用总数仍是 1，不是 2")
                .isEqualTo(1L);
        assertThat(metricLong("input_tokens"))
                .as("⚠️ 重复采集不得把同一条 usage_record 再计一次账")
                .isEqualTo(120L);
        assertThat(metricLong("output_tokens")).isEqualTo(45L);
        assertThat(metricLong("tool_rounds"))
                .as("⚠️ tool_rounds 仍来自 run_step 的 TOOL 步骤数，不因重投翻倍")
                .isEqualTo(2L);
        assertThat(captureExecutionFacts())
                .as("⚠️⚠️ 重投不得重跑已终态 Run：状态/fencing/attempt/计费/步骤必须逐项不变")
                .isEqualTo(runBefore);
        assertThat(captureSideEffectFacts())
                .as("⚠️⚠️ 重投不得重复派生评估/告警/工具明细副作用")
                .isEqualTo(sideAfterFirst);
        assertThat(pendingQueueRows())
                .as("⚠️ 两条采集消息都必须被 ack 删除，不得残留 PENDING")
                .isZero();
        assertThat(deadLetterRows())
                .as("⚠️ 幂等覆盖成功不得进死信")
                .isZero();
    }

    // ══════════════════════════════════════════════════════════════
    // 重投非空转证据
    // ══════════════════════════════════════════════════════════════

    /** 租户域执行事实：重投不得改变 Run 状态/计费/步骤/消息/预算。 */
    private record ExecutionFacts(String state, long fencingToken, int attempt,
                                  Instant completedAt, long usageRows, long inputTokens,
                                  long outputTokens, long stepRows, long messageRows,
                                  long reservationRows) {}

    /** 平台域派生副作用：重投不得让评估/告警/工具明细翻倍。 */
    private record SideEffectFacts(long reviewTasks, long taskOutcomes,
                                   long evalSamples, long toolCallRows) {}

    private ExecutionFacts captureExecutionFacts() {
        String sql = """
                SELECT r.state, r.fencing_token, r.attempt, r.completed_at,
                       (SELECT count(*) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT coalesce(sum(u.input_tokens), 0) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT coalesce(sum(u.output_tokens), 0) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT count(*) FROM public.run_step s
                         WHERE s.tenant_id = r.tenant_id AND s.run_id = r.run_id),
                       (SELECT count(*) FROM public.run_message m
                         WHERE m.tenant_id = r.tenant_id AND m.run_id = r.run_id),
                       (SELECT count(*) FROM public.budget_reservation b
                         WHERE b.tenant_id = r.tenant_id AND b.run_id = r.run_id)
                FROM public.run r
                WHERE r.tenant_id = ?::uuid AND r.run_id = ?::uuid
                """;
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("⚠️ Run 夹具必须存在，否则所有事实断言都在比空值")
                        .isTrue();
                Timestamp completed = rs.getTimestamp(4);
                return new ExecutionFacts(
                        rs.getString(1), rs.getLong(2), rs.getInt(3),
                        completed == null ? null : completed.toInstant(),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        rs.getLong(8), rs.getLong(9), rs.getLong(10));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 事实失败", e);
        }
    }

    private SideEffectFacts captureSideEffectFacts() {
        String sql = """
                SELECT
                  (SELECT count(*) FROM platform.review_task WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_task_outcome WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_eval_sample WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_trace_tool_call WHERE trace_id = ?)
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, traceId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new SideEffectFacts(rs.getLong(1), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取采集副作用失败", e);
        }
    }

    private Instant metricComputedAt() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT computed_at FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取指标 computed_at 失败", e);
        }
    }

    private void awaitMetricRowCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline && metricRowCount() != expected) {
            Thread.sleep(200);
        }
        assertThat(metricRowCount()).isEqualTo(expected);
    }

    private void awaitMetricRecomputedAfter(Instant baseline) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REPLAY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Instant current = metricComputedAt();
            if (current != null && current.isAfter(baseline)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(metricComputedAt())
                .as("⚠️⚠️ 第二次投递必须真正走到 upsert 覆盖写（computed_at 前移）；"
                        + "否则「行数仍为 1」是「消息没被消费」的假绿")
                .isAfter(baseline);
    }

    private void awaitPgProcessedAtLeast(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REPLAY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline
                && pgQueueListener.totalProcessed() < expected) {
            Thread.sleep(100);
        }
        assertThat(pgQueueListener.totalProcessed())
                .as("⚠️ PG 队列必须真的处理完两条采集消息 —— "
                        + "否则「指标未翻倍」可能是「第二条没消费」的假绿")
                .isGreaterThanOrEqualTo(expected);
    }

    private int pendingQueueRows() {
        return queryAdminInt("SELECT count(*) FROM public.agent_message_queue "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", tenantId, runId.toString());
    }

    private int deadLetterRows() {
        return queryAdminInt("SELECT count(*) FROM public.agent_message_dead_letter "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", tenantId, runId.toString());
    }

    /** owner 连接查询：租户域表在 FORCE RLS 下未绑租户会静默读 0 行。 */
    private int queryAdminInt(String sql, String... args) {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 造数
    // ══════════════════════════════════════════════════════════════

    private void insertRun() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at,
                            completed_at, trace_id)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUCCEEDED', 1, 0,
                            '{}'::jsonb, '{"content":"ok"}'::jsonb, ?, now(), now(), now(), ?)
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-trace-content-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private void insertUsage() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.usage_record (tenant_id, run_id, model_id,
                            input_tokens, output_tokens, cost_amount, currency)
                    VALUES (?::uuid, ?::uuid, 'minimax-chat', 120, 45, 0.012345, 'CNY')
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** ⚠️ 夹具与生产口径一致：run_step.state 恒写 'RUNNING'（已发生，非进行中），完成靠 completed_at。 */
    private void insertRunSteps() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run_step (tenant_id, run_id, step_number, state,
                            step_type, fencing_token, started_at, completed_at)
                    VALUES (?::uuid, ?::uuid, ?, 'RUNNING'::run_state, ?, 1, now(), now())
                    """)) {
                int n = 1;
                for (int i = 0; i < 3; i++) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.setInt(3, n++);
                    ps.setString(4, "THINK");
                    ps.executeUpdate();
                }
                for (int i = 0; i < 2; i++) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.setInt(3, n++);
                    ps.setString(4, "TOOL");
                    ps.executeUpdate();
                }
            }
            conn.commit();
        }
    }

    private UUID insertToolOperation(String toolName, String requestDigest) throws SQLException {
        UUID operationId = UUID.randomUUID();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.tool_operation (operation_id, tenant_id, run_id,
                            tool_name, risk_level, state, idempotency_key, request_digest,
                            tool_arguments, fencing_token)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'READ', 'PENDING', ?, ?, '{}'::jsonb, 1)
                    """)) {
                ps.setString(1, operationId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, runId.toString());
                ps.setString(4, toolName);
                ps.setString(5, "idem-" + operationId);
                ps.setString(6, requestDigest);
                ps.executeUpdate();
            }
            conn.commit();
        }
        return operationId;
    }

    private void insertToolAttempt(UUID operationId, String toolName, String state,
                                   String errorClass, Long durationMs) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            // ⚠️ 顺序有铁律：先插 attempt（operation 仍是 PENDING），
            //    再把 operation 推到终态 —— 触发器 `forbid_attempt_after_terminal`
            //    禁止「终态后再补 attempt」。
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.tool_operation_attempt (tenant_id, operation_id,
                            attempt_number, state, fencing_token, started_at, completed_at,
                            error_class)
                    VALUES (?::uuid, ?::uuid, 1, ?::public.tool_operation_state, 1,
                            now() - (? || ' milliseconds')::interval,
                            now() - (? || ' milliseconds')::interval, ?)
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId.toString());
                ps.setString(3, state);
                ps.setString(4, String.valueOf(durationMs));
                ps.setString(5, String.valueOf(durationMs));
                ps.setString(6, errorClass);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE public.tool_operation SET state = ?::public.tool_operation_state, "
                            + "updated_at = now() WHERE operation_id = ?::uuid")) {
                ps.setString(1, state);
                ps.setString(2, operationId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 断言辅助
    // ══════════════════════════════════════════════════════════════

    private int metricRowCount() {
        return queryInt("SELECT count(*) FROM platform.agent_trace_metric WHERE trace_id = ?",
                traceId);
    }

    private Long metricLong(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    private String metricString(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    /** ⚠️ 区分「未采集（NULL）」与「采了是 0」——本项目头号失真来源。 */
    private boolean metricIsNull(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " IS NULL FROM platform.agent_trace_metric "
                                + "WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next() || rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    private int queryInt(String sql, String arg) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private List<String> queryStrings(String sql, String arg) {
        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
        return out;
    }

    /** 用 owner 连接执行写/删（应用角色对租户域表受 RLS 且无写权）。 */
    private void exec(String sql, String... args) {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败: " + sql, e);
        }
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}

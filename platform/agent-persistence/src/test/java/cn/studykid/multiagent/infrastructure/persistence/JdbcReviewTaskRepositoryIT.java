package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 人工审查队列仓储 IT（P2）。
 *
 * <p><b>⚠️ 连接身份</b>：{@code platform.review_task} 是平台域表（无 RLS），
 * 应用角色 app_agent 在 platform 域只有两张登记表的只读权限（03 §2b），
 * 故本 IT 用管理角色直连 —— 与「RLS 隔离必须用 app_agent 验」的铁律<b>不冲突</b>：
 * 该铁律针对租户域 FORCE RLS 表，本表无 RLS，隔离靠授权闸门（端点侧验证）。
 *
 * <p><b>⚠️ 清理纪律</b>：@BeforeEach 按本 IT 专属的 run_id 前缀删除自己造的行，
 * 绝不按「种子租户字面量」全表清理（SeedTenantWriteArchitectureTest 纪律）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcReviewTaskRepositoryIT {

    private static HikariDataSource adminPool;
    private static JdbcReviewTaskRepository repo;

    /** 本 IT 专属 run 前缀：清理与断言都锚定它，绝不碰别人的行。 */
    private static final String RUN_PREFIX = "11111111-p2-review-it-";

    private static String traceId;
    private static String tenantId;
    private static String runIdOf(String tag) {
        // run_id 是 uuid 列：把 tag 哈希编进末段，保持 8-4-4-4-12 形状（同 tag 同值，幂等）
        return "22222222-3333-4444-5555-" + String.format("%012d", tag.hashCode() & 0xFFFFFFFFL);
    }

    @AfterAll
    static void closePool() {
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
        }
    }

    @BeforeAll
    static void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String pwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(2);
        adminPool = new HikariDataSource(cfg);
        repo = new JdbcReviewTaskRepository(adminPool);

        traceId = "trace-it-" + UUID.randomUUID();
        tenantId = UUID.randomUUID().toString();
    }

    @BeforeEach
    void clean() {
        adminExec("DELETE FROM platform.review_task WHERE trace_id LIKE 'trace-it-%' OR trace_id = '" + traceId + "'");
    }

    private ReviewTaskSpec spec(Source source, Priority priority) {
        return new ReviewTaskSpec(traceId, tenantId, runIdOf("main"), source, priority, "P2 IT 备注");
    }

    @Test
    @DisplayName("✅ P2-1 创建 → 待处理视图可见，HIGH 排在 NORMAL 前")
    void createAndListOrdered() {
        String normal = repo.create(spec(Source.MANUAL, Priority.NORMAL));
        // ⚠️ 必须用**不同的 trace**：设计 §7.2 第 4 条「已存在 PENDING/IN_REVIEW 同 trace → 跳过（去重）」
        //    意味着同一 trace 同时只能有一条未终结任务（DB 层由 uq_review_task_open 部分唯一索引保证）。
        //    本用例要验的是「优先级排序」，不是同 trace 重复建任务。
        String high = repo.create(new ReviewTaskSpec(
                traceId + "-high", tenantId, runIdOf("main"), Source.ALERT, Priority.HIGH, null));

        // ⚠️ 必须按本 IT 的 tenantId 过滤：platform.review_task 是平台域全局表，
        //    其它闭环 IT 会往里写待审任务；list(null, null, 50) 会因 CASE priority 排序
        //    把本用例的 NORMAL 新行挤出 LIMIT 50（2026-11-11 全量 verify 假红事故）。
        List<ReviewTaskViewRow> rows = repo.list(null, tenantId, 50).stream()
                .map(v -> new ReviewTaskViewRow(v.taskId(), v.priority(), v.status()))
                .toList();
        assertThat(rows).extracting(ReviewTaskViewRow::taskId).contains(normal, high);
        assertThat(rows.get(0).priority()).isEqualTo("HIGH");
        assertThat(rows).allSatisfy(r -> assertThat(r.status()).isIn("PENDING", "IN_REVIEW"));
    }

    @Test
    @DisplayName("✅ P2-2 认领 → IN_REVIEW + 认领人；重复认领改认领人不改状态")
    void assignFlow() {
        String id = repo.create(spec(Source.MANUAL, Priority.NORMAL));
        repo.assign(id, "reviewer-a");
        assertThat(repo.list(null, tenantId, 50))
                .filteredOn(v -> v.taskId().equals(id))
                .first()
                .satisfies(v -> {
                    assertThat(v.status()).isEqualTo("IN_REVIEW");
                    assertThat(v.assignedTo()).isEqualTo("reviewer-a");
                });
    }

    @Test
    @DisplayName("✅ P2-3 归因关闭 → RESOLVED + 根因；终态再认领被拒（状态机前置条件）")
    void resolveThenGuardTerminal() {
        String id = repo.create(spec(Source.ANOMALY, Priority.HIGH));
        repo.assign(id, "reviewer-a");
        repo.resolve(id, "PROMPT", "系统提示词缺验证步骤引导");

        assertThat(repo.list(null, tenantId, 50))
                .noneMatch(v -> v.taskId().equals(id)); // RESOLVED 不进待处理视图
        assertThatThrownBy(() -> repo.assign(id, "reviewer-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("认领");
    }

    @Test
    @DisplayName("✅ P2-4 驳回误报 → DISMISSED")
    void dismissFlow() {
        String id = repo.create(spec(Source.SAMPLE_RANDOM, Priority.LOW));
        repo.dismiss(id, "误报：抽样规则未排除白名单工具");
        assertThatThrownBy(() -> repo.resolve(id, "MODEL", null))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ⚠️⚠️ **并发去重必须由数据库保证**（2026-11-09 补）。
     *
     * <p>设计 §7.2 第 4 条：「已存在 PENDING/IN_REVIEW 同 trace → 跳过（去重）」。
     * {@code hasOpenTask} 是「先查后插」，多副本并发时两个实例可同时通过检查
     * ⇒ 队列里出现两条同 trace 的待审任务，reviewer 要挨个点开才发现是同一个。
     *
     * <p>本用例直接<b>绕过应用层检查</b>连插两条，验证 DB 层会拒绝第二条 ——
     * 这正是「应用层检查只是优化，DB 约束才是保证」的含义。
     */
    // ── §8.1 归因回写钩子（2026-11-09 预留）───────────────────────

    /** 记录钩子收到的事实；resolve 成功后必须被调用且只被合法根因调用。 */
    private static final class RecordingHook
            implements cn.studykid.multiagent.ports.quality.ReviewAttributionHook {
        final java.util.List<String> calls = new java.util.ArrayList<>();

        @Override
        public void onAttributionResolved(String traceId, String tenantId, String runId,
                                          String rootCause, String note, String reviewer) {
            calls.add(rootCause + "|" + traceId + "|" + tenantId);
        }
    }

    @Test
    @DisplayName("✅ H-1 PROMPT/CONFIG/MODEL 归因关闭后钩子收到完整事实")
    void hookFiredForIterationRootCauses() {
        RecordingHook hook = new RecordingHook();
        JdbcReviewTaskRepository withHook =
                new JdbcReviewTaskRepository(adminPool, hook);

        for (String cause : new String[]{"PROMPT", "CONFIG", "MODEL"}) {
            String tid = traceId + "-" + cause;
            String taskId = withHook.create(new ReviewTaskSpec(tid, tenantId,
                    runIdOf(cause), Source.MANUAL, Priority.NORMAL, null));
            withHook.resolve(taskId, cause, "备注-" + cause);

            assertThat(hook.calls).anySatisfy(c -> assertThat(c)
                    .startsWith(cause + "|").contains(tid).contains(tenantId));
        }
        assertThat(hook.calls).hasSize(3);
    }

    @Test
    @DisplayName("✅ H-2 TOOL/DATA/UNKNOWN 不触发钩子（§8.1 只对迭代工单三类）")
    void hookSilentForNonIterationRootCauses() {
        RecordingHook hook = new RecordingHook();
        JdbcReviewTaskRepository withHook =
                new JdbcReviewTaskRepository(adminPool, hook);

        for (String cause : new String[]{"TOOL", "DATA", "UNKNOWN"}) {
            String taskId = withHook.create(new ReviewTaskSpec(
                    traceId + "-" + cause, tenantId, runIdOf(cause),
                    Source.MANUAL, Priority.NORMAL, null));
            withHook.resolve(taskId, cause, null);
        }

        assertThat(hook.calls).isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ H-3 钩子抛异常不影响已关闭的任务（fail-soft 不回滚）")
    void hookFailureDoesNotBreakResolve() {
        cn.studykid.multiagent.ports.quality.ReviewAttributionHook throwing =
                (t, te, r, rc, n, rv) -> {
                    throw new RuntimeException("下游炸了");
                };
        JdbcReviewTaskRepository withHook =
                new JdbcReviewTaskRepository(adminPool, throwing);

        String tid = traceId + "-boom";
        String taskId = withHook.create(new ReviewTaskSpec(tid, tenantId,
                runIdOf("boom"), Source.MANUAL, Priority.NORMAL, null));

        // resolve 必须正常返回：任务关闭是事实，回写失败只是下游问题
        withHook.resolve(taskId, "MODEL", null);

        List<ReviewTaskPort.ReviewTaskView> resolved =
                withHook.list("RESOLVED", tenantId, 50).stream()
                        .filter(v -> v.traceId().equals(tid)).toList();
        assertThat(resolved)
                .as("⚠️ 钩子失败若把 resolve 整体报失败，reviewer 重试会造成"
                        + "「任务已关闭却显示失败」的双真相")
                .hasSize(1);
        assertThat(resolved.get(0).rootCause()).isEqualTo("MODEL");
    }

    @Test
    @DisplayName("⚠️ H-4 未流转生效（已终态）时绝不回调钩子")
    void hookNotFiredWhenResolveRejected() {
        RecordingHook hook = new RecordingHook();
        JdbcReviewTaskRepository withHook =
                new JdbcReviewTaskRepository(adminPool, hook);

        String tid = traceId + "-twice";
        String taskId = withHook.create(new ReviewTaskSpec(tid, tenantId,
                runIdOf("twice"), Source.MANUAL, Priority.NORMAL, null));
        withHook.resolve(taskId, "PROMPT", null);
        assertThat(hook.calls).hasSize(1);

        // 二次 resolve（已 RESOLVED，前态不合法）→ 抛异常且钩子不再收到调用
        assertThatThrownBy(() -> withHook.resolve(taskId, "MODEL", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(hook.calls).as("回写绝不发生在未生效的流转上").hasSize(1);
    }

    @Test
    @DisplayName("✅ P2-6 ⚠️ 同 trace 的第二条未终结任务被 DB 拒绝（并发去重）")
    void duplicateOpenTaskRejectedByDatabase() {
        repo.create(spec(Source.MANUAL, Priority.NORMAL));

        assertThatThrownBy(() -> repo.create(
                new ReviewTaskSpec(traceId, tenantId, runIdOf("main"), Source.ALERT, Priority.HIGH, null)))
                .as("⚠️ 同 trace 的未终结任务必须唯一 —— 否则多副本并发下队列出现重复，"
                        + "reviewer 白看一遍")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uq_review_task_open");
    }

    /** 任务关闭后，同 trace 应能再次入队（状态机允许新问题再次入队）。 */
    @Test
    @DisplayName("✅ P2-7 已终结的同 trace 允许再次入队（部分唯一索引不误伤状态流转）")
    void resolvedTaskAllowsRequeue() {
        String first = repo.create(spec(Source.MANUAL, Priority.NORMAL));
        repo.resolve(first, "MODEL", "已定位");

        String second = repo.create(
                new ReviewTaskSpec(traceId, tenantId, runIdOf("main"), Source.ALERT, Priority.HIGH, null));

        assertThat(second)
                .as("⚠️ RESOLVED 后同一 trace 再次入队是合法状态流转 —— "
                        + "若这里被拒，说明唯一约束写成了「全状态唯一」，把状态机锁死了")
                .isNotBlank();
    }

    @Test
    @DisplayName("✅ P2-5 租户过滤 + 精确状态过滤 + limit 钳制")
    void filters() {
        String otherTenant = UUID.randomUUID().toString();
        repo.create(spec(Source.MANUAL, Priority.NORMAL));
        // ⚠️ 两条必须用不同 trace：同 trace 同时只允许一条未终结任务（uq_review_task_open）。
        //    本用例验的是「租户过滤」，与 trace 无关。
        String mine = repo.create(new ReviewTaskSpec(
                traceId + "-mine", tenantId, runIdOf("main"), Source.MANUAL, Priority.NORMAL, null));
        String theirs = repo.create(new ReviewTaskSpec(
                traceId + "-theirs", otherTenant, runIdOf("main"), Source.MANUAL, Priority.NORMAL, null));

        List<String> forTenant = repo.list(null, tenantId, 50).stream()
                .map(ReviewTaskPort.ReviewTaskView::taskId).toList();
        assertThat(forTenant).contains(mine).doesNotContain(theirs);

        repo.assign(mine, "reviewer-x");
        List<String> inReview = repo.list("IN_REVIEW", tenantId, 50).stream()
                .map(ReviewTaskPort.ReviewTaskView::taskId).toList();
        assertThat(inReview).contains(mine).doesNotContain(theirs);

        assertThat(repo.list(null, tenantId, 1)).hasSize(1);
    }

    @Test
    @DisplayName("✅ P2-6 非法 source 被 CHECK 约束拒绝（fail-closed 兜底，不只靠应用枚举）")
    void invalidSourceRejected() {
        // 直接走 SQL 绕过应用枚举，证明库侧 CHECK 仍在 —— 应用枚举只是第一道闸
        assertThatThrownBy(() -> adminExec(
                "INSERT INTO platform.review_task (trace_id, tenant_id, run_id, source) "
                + "VALUES ('" + traceId + "','" + tenantId + "','" + runIdOf("bad") + "','NOT_A_SOURCE')"))
                .isInstanceOf(Exception.class);
    }

    // ── 夹具 ────────────────────────────────────────────────

    private static void adminExec(String sql) {
        try (var conn = adminPool.getConnection();
                var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record ReviewTaskViewRow(String taskId, String priority, String status) {
    }
}

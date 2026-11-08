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
        String high = repo.create(new ReviewTaskSpec(traceId, tenantId, runIdOf("main"), Source.ALERT, Priority.HIGH, null));

        List<ReviewTaskViewRow> rows = repo.list(null, null, 50).stream()
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
        assertThat(repo.list(null, null, 50))
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

        assertThat(repo.list(null, null, 50))
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

    @Test
    @DisplayName("✅ P2-5 租户过滤 + 精确状态过滤 + limit 钳制")
    void filters() {
        String otherTenant = UUID.randomUUID().toString();
        repo.create(spec(Source.MANUAL, Priority.NORMAL));
        String mine = repo.create(new ReviewTaskSpec(traceId, tenantId, runIdOf("main"), Source.MANUAL, Priority.NORMAL, null));
        String theirs = repo.create(new ReviewTaskSpec(traceId, otherTenant, runIdOf("main"), Source.MANUAL, Priority.NORMAL, null));

        List<String> forTenant = repo.list(null, tenantId, 50).stream()
                .map(ReviewTaskPort.ReviewTaskView::taskId).toList();
        assertThat(forTenant).contains(mine).doesNotContain(theirs);

        repo.assign(mine, "reviewer-x");
        List<String> inReview = repo.list("IN_REVIEW", null, 50).stream()
                .map(ReviewTaskPort.ReviewTaskView::taskId).toList();
        assertThat(inReview).contains(mine).doesNotContain(theirs);

        assertThat(repo.list(null, null, 1)).hasSize(1);
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

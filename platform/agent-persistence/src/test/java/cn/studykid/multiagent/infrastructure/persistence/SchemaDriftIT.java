package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.infrastructure.PlatformDdl;

/**
 * P3-2 门禁：<b>活库结构必须等于仓库里的 DDL 快照</b>。
 *
 * <p>⚠️⚠️ <b>本类要防的漂移</b>：
 * 本仓已删除迁移链（{@code db/migration/V1…V34}），
 * 库结构由 {@code pg_dump} 快照 + 带外 SQL 脚本落地。
 * 于是「快照改了、库没跟上」或「谁在线上顺手改了一下、快照没跟上」
 * 都<b>没有任何机制会报错</b>：
 * 结构门禁（{@link PlatformDdl} 系列）只扫快照文件，
 * 而一个个 IT 只验证它们自己关心的那几张表 ——
 * 两者之间缺的正是「库 == 快照」这一次直接比对。
 *
 * <p>漂移的真实代价在 RLS：如果某张表的
 * {@code ENABLE/FORCE ROW LEVEL SECURITY} 或策略在库中与快照不一致，
 * 租户隔离的<b>强度</b>就与代码假设的不同，
 * 而所有「隔离生效」的测试结论都是拿另一份结构得出的。
 *
 * <p><b>为什么必须是 IT</b>：要比的就是「真实库」，
 * 用内存替身比对等于拿快照和自己比，必然全绿且毫无意义。
 *
 * <p>⚠️ <b>无库时跳过而非通过</b>：{@code @EnabledIfEnvironmentVariable}
 * 让它在 CI 无凭据环境下被跳过。这与「跳过＝通过」是两回事，
 * 详见项目笔记：守门保证的是不在无 DB 环境报红，不保证 IT 真能跑。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class SchemaDriftIT {

    private static HikariDataSource appPool;

    @BeforeAll
    static void beforeAll() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv("IT_PG_USER"));
        cfg.setPassword(System.getenv("IT_PG_PASSWORD"));
        cfg.setMaximumPoolSize(2);
        appPool = new HikariDataSource(cfg);
    }

    @Test
    @DisplayName("⛔ 快照声明的 RLS 表，活库中必须同样启用且强制（否则隔离强度与代码假设不符）")
    void rlsFlagsMatchSnapshot() {
        // 快照侧：PlatformDdl 已解析好（与其余结构门禁同一套解析规则）
        List<String> forced = PlatformDdl.rlsTables("FORCE");
        List<String> enabled = PlatformDdl.rlsTables("ENABLE");
        assertThat(forced).as("快照必须解析出 FORCE RLS 表，否则本门禁在空跑").isNotEmpty();

        // 活库侧
        Set<String> liveForced = liveRlsFlags(true);
        Set<String> liveEnabled = liveRlsFlags(false);

        Set<String> missingForced = new TreeSet<>(forced);
        missingForced.removeAll(liveForced);
        Set<String> missingEnabled = new TreeSet<>(enabled);
        missingEnabled.removeAll(liveEnabled);

        assertThat(missingForced)
                .as("快照声明 FORCE RLS、但活库未开启 —— "
                        + "表 owner 的查询会绕过策略，隔离强度低于代码假设")
                .isEmpty();
        assertThat(missingEnabled)
                .as("快照声明 ENABLE RLS、但活库未开启 —— 该表在活库无任何行级隔离")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 快照声明的表，活库中必须存在（缺表会让运行期报 relation does not exist）")
    void snapshotTablesExistInLiveDatabase() {
        List<String> declared = PlatformDdl.createdTables();
        assertThat(declared).as("快照必须解析出表，否则本门禁在空跑").isNotEmpty();

        Set<String> live = liveTables();
        Set<String> missing = new TreeSet<>();
        for (String table : declared) {
            if (!live.contains(table)) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("快照里有、活库里没有的表：结构已漂移，"
                        + "相关功能会在运行期才崩，且报错点离真因（结构没同步）极远")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 快照声明的列，活库中必须存在（缺列会让运行期报 column does not exist）")
    void snapshotColumnsExistInLiveDatabase() {
        List<String> declared = PlatformDdl.createdTables();
        assertThat(declared).as("快照必须解析出表，否则本门禁在空跑").isNotEmpty();

        Map<String, Set<String>> live = liveColumns();
        assertThat(live).as("活库必须能读到列信息，否则本门禁在空跑").isNotEmpty();

        List<String> missing = new ArrayList<>();
        int checkedColumns = 0;
        for (String table : declared) {
            Set<String> liveCols = live.get(table);
            if (liveCols == null) {
                // 缺表由另一个用例负责报（这里不重复计数，避免把两种漂移混成一条）
                continue;
            }
            for (String col : PlatformDdl.columnNames(table)) {
                checkedColumns++;
                if (!liveCols.contains(col)) {
                    missing.add(table + "." + col);
                }
            }
        }
        assertThat(checkedColumns)
                .as("必须真的比对过列（防空转：解析失败会让空集恒绿）")
                .isGreaterThan(0);

        assertThat(missing)
                .as("⛔ 快照声明的列在活库中不存在 —— 任何引用该列的 SQL 都会在运行期报"
                        + "「column does not exist」，且报错点离真因（结构漂移）极远。"
                        + " 本仓曾因此让整条指标采集链路长期静默失败："
                        + "JdbcTraceFactRepository 读 cap_tool_def.tool_name，"
                        + "而该列的实名是 id —— 表在、列不在，报错却是「采集失败」，"
                        + "极易误判为 MQ 接线问题。列级漂移必须在这一层被拦住")
                .isEmpty();
    }

    /**
     * P3 实测教训（2026-11-08）：{@code 03_platform_roles.sql} 只给了 app_agent
     * 对 {@code platform.agent_task_outcome} 的 INSERT/SELECT，但
     * {@code JdbcAgentMetricRepository.recordTaskOutcome} 用的是
     * {@code INSERT … ON CONFLICT DO UPDATE}（幂等重采）→ 需要 UPDATE 权限。
     * 缺失时 PostgreSQL 报 permission denied，而采集器对评估异常是 fail-soft，
     * 只打一行 WARN 就继续：<b>指标表有数、看板正常、回执恒 0 行、auto_checked 永远 false</b>。
     * 这类漂移用「表/列存在」门禁完全照不出来，必须真跑一次 upsert。
     */
    @Test
    @DisplayName("⛔ 应用角色必须能对 L1 回执做幂等 upsert（缺 UPDATE 会让覆盖率恒 0 且不报错）")
    void applicationRoleCanUpsertEvaluationOutcome() {
        // 先确认这条连接真是「受限应用角色」：拿管理员连接验权限必然假绿。
        try (Connection conn = appPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("必须能读到 current_user 的角色属性，否则本门禁在空跑").isTrue();
            assertThat(rs.getBoolean(1)).as("本门禁必须用非超级用户连接，否则权限校验恒绿").isFalse();
            assertThat(rs.getBoolean(2)).as("本门禁必须用 NOBYPASSRLS 角色连接").isFalse();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取当前连接角色属性失败", e);
        }

        String probeTrace = "it-privilege-probe-" + java.util.UUID.randomUUID();
        String sql = """
                INSERT INTO platform.agent_task_outcome
                    (trace_id, tenant_id, checker_code, verified, evidence_digest)
                VALUES (?, '00000000-0000-0000-0000-000000000000'::uuid,
                        'PRIVILEGE_PROBE', false, 'probe')
                ON CONFLICT (trace_id, checker_code) DO UPDATE SET
                    verified = EXCLUDED.verified,
                    evidence_digest = EXCLUDED.evidence_digest,
                    checked_at = now()
                """;
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 连写两次 = 真实复现「重复采集」路径；缺 UPDATE 时第二次直接抛。
                for (int i = 0; i < 2; i++) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, probeTrace);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM platform.agent_task_outcome WHERE trace_id = ?")) {
                    ps.setString(1, probeTrace);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1))
                                .as("重复 upsert 必须幂等收敛为 1 行")
                                .isEqualTo(1);
                    }
                }
            } finally {
                // 探针数据绝不落库：本门禁只验权限，不制造跨用例污染
                conn.rollback();
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError("⛔ app_agent 无法对 platform.agent_task_outcome 做幂等 upsert —— "
                    + "L1 回执写入会被拒且被 fail-soft 吞掉，自动校验覆盖率恒 0。"
                    + " 请重放 03_platform_roles.sql（含 SELECT, INSERT, UPDATE）+ 04_retention_role.sql",
                    e);
        }
    }

    private static Map<String, Set<String>> liveColumns() {
        Map<String, Set<String>> out = new HashMap<>();
        withConn(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT table_schema || '.' || table_name, column_name "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema IN ('public','platform')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                            .add(rs.getString(2));
                }
            }
        });
        return out;
    }

    private static Set<String> liveTables() {
        Set<String> out = new HashSet<>();
        withConn(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT schemaname || '.' || tablename FROM pg_tables "
                            + "WHERE schemaname IN ('public','platform')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        });
        return out;
    }

    /** @param forced true = 取 FORCE RLS 的表；false = 取 ENABLE RLS 的表 */
    private static Set<String> liveRlsFlags(boolean forced) {
        Set<String> out = new HashSet<>();
        withConn(conn -> {
            String column = forced ? "relforcerowsecurity" : "relrowsecurity";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT n.nspname || '.' || c.relname FROM pg_class c "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname IN ('public','platform') "
                            + "AND c.relkind = 'r' AND c." + column);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        });
        return out;
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection conn) throws java.sql.SQLException;
    }

    private static void withConn(SqlAction action) {
        try (Connection conn = appPool.getConnection()) {
            action.run(conn);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取活库结构失败", e);
        }
    }
}

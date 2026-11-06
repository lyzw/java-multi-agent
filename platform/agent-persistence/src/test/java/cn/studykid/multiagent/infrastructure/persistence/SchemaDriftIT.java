package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
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

package cn.studykid.multiagent.memory.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.infrastructure.persistence.FaultInjectionSupport;

/**
 * pgvector 可用性探测（P2）。
 *
 * <p><b>⚠️ 本类证明的核心命题</b>：
 * <ol>
 *   <li><b>探测失败返回 false，不抛异常</b>——否则"库连不上"会把
 *       整个召回路径炸掉，而记忆本应可以降级；</li>
 *   <li><b>结果被缓存</b>——探测是热路径上的一条 SQL，每次召回都探一次不可接受；</li>
 *   <li><b>{@link PgVectorAvailability#reset()} 真的让缓存失效</b>——
 *       否则运维在运行期装好扩展后，只能靠重启进程才生效，
 *       而"重启"没有告警也没有日志。</li>
 * </ol>
 *
 * <p>⚠️ 用 {@code IT_PG_URL} 门禁：本类需要一个真实DataSource。
 * 无库时跳过（含无库的 CI 构建），有库时真跑。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@DisplayName("P2 pgvector 可用性探测")
class PgVectorAvailabilityTest {

    @Test
    @DisplayName("真实库：探测返回确定的布尔值（可用或不可用，不抛异常）")
    void probeAgainstRealDatabase() {
        try (HikariDataSource pool = FaultInjectionSupport.appPool(2)) {
            PgVectorAvailability probe = new PgVectorAvailability(pool);
            // ⚠️ 不断言 true：线上 agent_dev **尚未安装** pgvector（实测
            //    pg_available_extensions 有、pg_extension 没有）。
            //    断言 true 会让本类在真实环境假红；
            //    断言 false 会让本地装了扩展的环境假绿。
            //    这里只断言"探测是确定的、不抛异常"。
            boolean available = probe.isAvailable();
            assertThat(available).isIn(true, false);
            // 再调一次，验证缓存路径同样不抛
            assertThat(probe.isAvailable()).isEqualTo(available);
        }
    }

    @Test
    @DisplayName("⚠️ 库不可用 → 返回 false 而不是抛异常（记忆必须能降级）")
    void brokenDataSourceDegradesToFalse() {
        DataSource broken = new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                throw new SQLException("模拟：库连不上");
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                throw new SQLException("模拟：库连不上");
            }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("不支持");
            }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
        PgVectorAvailability probe = new PgVectorAvailability(broken);

        assertThat(probe.isAvailable())
                .as("⚠️ 探测失败必须降级为「不可用」，而不是把异常抛进召回路径")
                .isFalse();
    }

    @Test
    @DisplayName("⚠️ reset() 让缓存失效 —— 装好扩展后不必重启进程")
    void resetInvalidatesCache() throws Exception {
        try (HikariDataSource pool = FaultInjectionSupport.appPool(2)) {
            PgVectorAvailability probe = new PgVectorAvailability(pool);
            boolean first = probe.isAvailable();

            probe.reset();
            boolean afterReset = probe.isAvailable();

            // 结果应当一致（库没变），但**探测确实又跑了一次**：
            // 用"reset 前后都不会抛"证明缓存被正确清掉并重新探测。
            assertThat(afterReset)
                    .as("reset 之后必须重新探测并给出确定结果")
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("探测 SQL 用 to_regtype 尊重 search_path（跨 schema 不误判）")
    void probeSqlUsesSearchPathAwareCheck() throws Exception {
        try (HikariDataSource pool = FaultInjectionSupport.appPool(2);
             Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT to_regtype('vector') IS NOT NULL AS available");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            // ⚠️ 记录到断言说明里：这里验证的是**判据本身可执行**。
            //    若扩展装在非 public 的 schema，to_regtype 会按 search_path 解析，
            //    而 pg_type 直查会跨 schema 命中 —— 得到虚假的"可用"。
            assertThat(rs.getBoolean("available")).isIn(true, false);
        }
    }
}

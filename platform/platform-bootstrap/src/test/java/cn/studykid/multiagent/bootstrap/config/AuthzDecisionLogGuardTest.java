package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.security.JdbcAuthzDecisionLog;
import cn.studykid.multiagent.infrastructure.security.NoOpAuthzDecisionRecorder;

/**
 * 决策日志就绪守卫的<b>可证伪</b>用例（D6 / P2）。
 *
 * <p>⚠️ 本类要钉住的是三种「开关开着、却零记录」的形态，它们在生产里表现完全正常：
 * <ol>
 *   <li>容器里没有记录器 Bean（影子装配没开）→ 一条都不会写；</li>
 *   <li>记录器是同情的 NoOp 实现（装配被替换）→ 写调用全部丢弃；</li>
 *   <li>记录器是 Jdbc 实现但<b>表不可达</b>（缺表 / 缺 INSERT / 列漂移）→
 *       每次写失败被 60s 限频成一条 WARN，请求照常返回。</li>
 * </ol>
 * 第 3 条只能靠真的走一遍 INSERT 发现，因此这里用「永不建连的 DataSource」
 * 模拟不可达（真实环境由 {@code AuthzShadowLiveIT} 覆盖可写路径）。
 */
class AuthzDecisionLogGuardTest {

    @Test
    @DisplayName("关闭时：不校验、不报错（P1 默认态，判定本来就不落库）")
    void disabled_isSilent() {
        AuthzDecisionLogGuard guard = new AuthzDecisionLogGuard(false, provider(null));
        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⛔ 开启但容器里没有记录器：拒绝启动（否则「审计已开启」= 一行配置字样）")
    void enabledButNoRecorder_failsClosed() {
        AuthzDecisionLogGuard guard = new AuthzDecisionLogGuard(true, provider(null));
        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何 AuthzDecisionRecorder");
    }

    @Test
    @DisplayName("⛔ 开启但记录器是 NoOp 实现：拒绝启动（写调用会被静默丢弃）")
    void enabledButNoOpRecorder_failsClosed() {
        AuthzDecisionLogGuard guard = new AuthzDecisionLogGuard(
                true, provider(NoOpAuthzDecisionRecorder.INSTANCE));
        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NoOpAuthzDecisionRecorder.class.getName());
    }

    @Test
    @DisplayName("⛔ 开启但表不可达：拒绝启动（零行写入探测失败，而不是起来后静默丢审计）")
    void enabledButTableUnreachable_failsClosed() {
        AuthzDecisionLogGuard guard = new AuthzDecisionLogGuard(
                true, provider(new JdbcAuthzDecisionLog(new NoConnectDataSource())));
        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("零行写入探测失败");
    }

    @Test
    @DisplayName("探测失败只返回 false、绝不外抛（审计故障不得变成请求故障）")
    void probeNeverThrows() {
        JdbcAuthzDecisionLog log = new JdbcAuthzDecisionLog(new NoConnectDataSource());
        assertThat(log.probe()).isFalse();
    }

    // ── 装配辅助 ──────────────────────────────────────────────────

    private static ObjectProvider<AuthzDecisionRecorder> provider(
            @org.jspecify.annotations.Nullable AuthzDecisionRecorder recorder) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (recorder != null) {
            factory.registerSingleton("recorder", recorder);
        }
        return factory.getBeanProvider(AuthzDecisionRecorder.class);
    }

    /** 永不建连的 DataSource 桩：模拟「表不可达」。 */
    static final class NoConnectDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("表不可达（测试桩）");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("表不可达（测试桩）");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}

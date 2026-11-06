package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台运维数据源提供者与会话守卫的单测（P6R1 · 上线阻塞项接入点）。
 *
 * <p>⚠️ <b>本类锁的是三条不可让步的约束</b>：
 * <ol>
 *   <li><b>fail-closed</b>：未接入运维角色 → 明确失败，且报错点名配置项；</li>
 *   <li><b>会话闸门</b>：运维连接只在会话窗口内可取，
 *       窗口外取用一律拒绝（这是防「RLS 被整体绕过」的运行期防线）；</li>
 *   <li><b>会话不泄漏</b>：动作正常返回或抛异常，窗口都必须关闭 ——
 *       否则一次误用会让该线程后续所有连接都享有 BYPASSRLS。</li>
 * </ol>
 */
class PlatformOpsDataSourceProviderTest {

    /** 替身数据源在「通过守卫」后抛出，用于区分「被守卫拦下」与「确实到达了池子」。 */
    private static final class DelegateReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static DataSource delegate() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new DelegateReached();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new DelegateReached();
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
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    // ── fail-closed ──────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 未接入运维角色 → PLATFORM 发布 fail-closed，且报错点名配置项")
    void disabledProviderRejectsPlatformScope() {
        PlatformOpsDataSourceProvider provider = PlatformOpsDataSourceProvider.disabled();

        assertThat(provider.isEnabled()).isFalse();
        assertThatThrownBy(() -> provider.inPlatformScopeSession(ds -> "never"))
                .isInstanceOf(PlatformOpsUnavailableException.class)
                .hasMessageContaining("agent.platform-ops.datasource.enabled")
                .hasMessageContaining("BYPASSRLS")
                // ⚠️ 必须明说「不会退回应用角色」——
                //    否则运维看到报错会以为「换个角色再试」，
                //    而那正是被设计挡住的降级路径。
                .hasMessageContaining("不会退回到应用角色");
    }

    @Test
    @DisplayName("⛔ fail-closed 异常必须带上缺失的配置项路径")
    void disabledProviderReportsMissingConfiguration() {
        PlatformOpsDataSourceProvider provider = PlatformOpsDataSourceProvider.disabled();

        assertThatThrownBy(() -> provider.inPlatformScopeSession(ds -> "never"))
                .isInstanceOfSatisfying(PlatformOpsUnavailableException.class, e ->
                        assertThat(e.missingConfiguration())
                                .isEqualTo("agent.platform-ops.datasource.enabled"));
    }

    @Test
    @DisplayName("⛔ enabled=true 但未提供数据源实现 → 构造即拒（不留半配置状态）")
    void enabledWithoutDelegateIsRejected() {
        assertThatThrownBy(() -> new PlatformOpsDataSourceProvider(true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 会话闸门 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 会话内可正常取到运维连接（守卫放行，请求确实到达了池子）")
    void connectionReachesDelegateInsideSession() {
        PlatformOpsDataSourceProvider provider =
                new PlatformOpsDataSourceProvider(true, delegate());

        assertThatThrownBy(() -> provider.inPlatformScopeSession(ds -> ds.getConnection()))
                .as("⚠️ 会话内仍被拒绝：运维通道将完全不可用")
                .isInstanceOf(DelegateReached.class);
    }

    @Test
    @DisplayName("⛔ 逃出会话后使用受守卫数据源 → 拒绝")
    void guardedDataSourceRefusesDirectUse() {
        PlatformOpsDataSourceProvider provider =
                new PlatformOpsDataSourceProvider(true, delegate());
        DataSource[] captured = new DataSource[1];

        provider.inPlatformScopeSession(ds -> {
            captured[0] = ds;
            // ⚠️ 嵌套会话：退出内层后外层仍在会话内
            provider.inPlatformScopeSession(inner -> null);
            return null;
        });

        assertThatThrownBy(() -> captured[0].getConnection())
                .as("⛔ 嵌套会话退出会话后仍可取连接：会话深度未正确恢复")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS")
                .hasMessageContaining("租户隔离");
    }

    @Test
    @DisplayName("✅ 会话在动作正常结束后关闭（不留 BYPASSRLS 窗口）")
    void sessionIsClosedAfterNormalCompletion() {
        PlatformOpsDataSourceProvider provider =
                new PlatformOpsDataSourceProvider(true, delegate());
        DataSource[] captured = new DataSource[1];
        provider.inPlatformScopeSession(ds -> {
            captured[0] = ds;
            return null;
        });

        assertThatThrownBy(() -> captured[0].getConnection())
                .as("⛔ 会话未关闭：该线程后续连接都享有 BYPASSRLS，租户隔离整体失效")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("✅ 会话在动作抛异常后同样关闭")
    void sessionIsClosedAfterFailure() {
        PlatformOpsDataSourceProvider provider =
                new PlatformOpsDataSourceProvider(true, delegate());
        DataSource[] captured = new DataSource[1];

        assertThatThrownBy(() -> provider.inPlatformScopeSession(ds -> {
            captured[0] = ds;
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThatThrownBy(() -> captured[0].getConnection())
                .as("⛔ 动作失败后会话未关闭：失败路径留下了 BYPASSRLS 窗口")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("⛔ 运维数据源不接受外部传入的数据库凭据")
    void guardedDataSourceRejectsExternalCredentials() {
        PlatformOpsDataSourceProvider provider =
                new PlatformOpsDataSourceProvider(true, delegate());

        assertThatThrownBy(() -> provider.inPlatformScopeSession(
                ds -> ds.getConnection("someone_else", "guess")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不接受外部传入的数据库凭据");
    }
}

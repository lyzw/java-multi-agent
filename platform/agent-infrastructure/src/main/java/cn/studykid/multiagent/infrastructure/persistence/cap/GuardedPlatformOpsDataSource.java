package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;

/**
 * 受会话守卫的平台运维数据源（包内可见）。
 *
 * <p>⚠️ <b>本类的全部价值在于「拒绝」而不是「代理」</b>：
 * 它把一个 {@code BYPASSRLS} 数据源包起来，使后者<b>只能在平台运维会话内</b>
 * 交出连接。理由见 {@link PlatformOpsSessionGuard}。
 *
 * <p>⚠️ <b>为什么拒绝「外部传入凭据」的取连接方式</b>：
 * {@link #getConnection(String, String)} 允许调用方指定用户名与密码，
 * 那等于让任何持有本对象的人<b>绕过运维角色的凭据管理</b>，
 * 在运维池上另起一个身份 —— 包括一个更弱或更强的身份。
 * 运维数据源的身份必须<b>只有一种</b>，由配置唯一决定。
 */
final class GuardedPlatformOpsDataSource implements DataSource {

    private final DataSource delegate;
    private final PlatformOpsSessionGuard guard;

    GuardedPlatformOpsDataSource(@NonNull DataSource delegate,
                                  @NonNull PlatformOpsSessionGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public Connection getConnection() throws SQLException {
        guard.requireActiveSession();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // ⚠️ 先守会话再拒凭据：即便调用方本就无权使用，也要让报错指向
        //    「你不该用这个数据源」而不是「参数不合法」。
        guard.requireActiveSession();
        // ⚠️ 用 IllegalStateException 而非 SQLException：
        //    这不是 SQL 层错误，而是**策略违反**（与会话守卫同类），
        //    报成 SQLException 会让调用方误以为是数据库故障而重试。
        throw new IllegalStateException(
                "平台运维数据源不接受外部传入的数据库凭据："
                        + "运维角色的身份必须由配置唯一决定（agent.platform-ops.datasource.*），"
                        + "不接受在调用点临时指定用户名/口令");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}

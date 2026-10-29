package cn.studykid.rag.infrastructure.ops;

import java.sql.Connection;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * 只在运维会话窗内放行连接的 {@link DataSource} 包装。
 *
 * <p><b>为什么要包一层</b>：BYPASSRLS 角色一旦能被「随手拿到连接」，
 * 租户隔离就不再由数据库保证，而取决于每个调用点都记得正确事——
 * 而记错的那一次不会报错，只会安静地写到错的桶里（RLS 对它是透明的）。
 * 包装层把「记得」变成「拿不到」：窗口外取连接直接抛。
 *
 * <p>{@link RagOpsSessionPort} 不把 DataSource 暴露成 bean，与本类的拒绝对齐——
 * 两道堵的是同一条路：把运维凭据当成普通数据源注入出去。
 *
 * <p>继承 {@link AbstractDataSource} 而不是手写全部接口方法：
 * 后者的日志/超时/ParentLogger 一律 {@code SQLFeatureNotSupportedException}，
 * 正是我们想要的默认（本池只由 provider 内部使用，不接受外部凭据）。
 */
final class GuardedRagOpsDataSource extends AbstractDataSource {

    private final DataSource delegate;
    private final RagOpsSessionGuard guard;

    GuardedRagOpsDataSource(DataSource delegate, RagOpsSessionGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        guard.requireActiveSession();
        return delegate.getConnection();
    }

    /**
     * ⚠️ 恒拒：接受外部凭据等于让调用方带着自己的账号进来，
     * 「这条连接是不是运维通道」就不再是装配期能确定的事。
     */
    @Override
    public Connection getConnection(String username, String password) {
        throw new IllegalStateException(
                "平台运维数据源不接受外部传入的数据库凭据：凭据只能来自 rag.ops.datasource.* 配置");
    }
}

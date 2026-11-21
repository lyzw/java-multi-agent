package cn.studykid.rag.infrastructure.rls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG RLS 会话绑定拦截器（W1 RAG 试点）。
 *
 * <p><b>与 platform 的差异</b>：RAG 走 Spring {@code TransactionTemplate}，
 * MyBatis 默认 {@code SpringManagedTransaction} 经 DataSourceUtils 拿到的是<b>同一条事务连接</b>，
 * 无需自定义 TransactionFactory。但 RLS 会话绑定纪律相同——
 * 每条 SQL 前在同一连接 set_config(..., true)，且平台档为显式置空串。
 *
 * <p>⚠️ 保留 RagSessionScope 的语义：平台档置空（不是哨兵，不是跳过），
 * 否则 rag_current_tenant() 在哨兵上抛 42501。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class,
                        org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class RagRlsBindingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RagRlsBindingInterceptor.class);
    private static final String SQL_SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";
    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();

    @Override
    public Object intercept(@NonNull Invocation invocation) throws Throwable {
        String sessionTenant = RagTenantScope.requireBound(); // fail-closed；空串合法
        Executor executor = (Executor) invocation.getTarget();
        Connection connection = executor.getTransaction().getConnection();
        if (!sessionTenant.equals(BOUND.get())) {
            try (PreparedStatement ps = connection.prepareStatement(SQL_SET_TENANT)) {
                ps.setString(1, sessionTenant);
                ps.execute();
            } catch (SQLException e) {
                throw new IllegalStateException("RAG RLS 绑定失败", e);
            }
            BOUND.set(sessionTenant);
            if (log.isTraceEnabled()) {
                log.trace("RAG RLS 已绑定: tenant='{}'", sessionTenant);
            }
        }
        return invocation.proceed();
    }

    public static void onConnectionRelease() {
        BOUND.remove();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }
}

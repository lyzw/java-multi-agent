package cn.studykid.multiagent.infrastructure.persistence.rls;

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

import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;

/**
 * RLS 会话绑定拦截器（POC 核心，对应主文档 §10 D2/D3）。
 *
 * <p><b>职责</b>：在每一条 Mapper SQL 执行前，从 {@link TenantScope} 取主体租户，
 * 在<b>与业务 SQL 同一条连接</b>上执行
 * {@code SELECT set_config('app.tenant_id', ?, true)}，从而让 PG 的
 * {@code FORCE ROW LEVEL SECURITY} 策略对后续语句生效。
 *
 * <p>⚠️⚠️ <b>四条铁律（任何一条被改坏都会造成静默越权或「写入成功但查询为 0」）</b>：
 * <ol>
 *   <li><b>顺序</b>：绑定必须在业务 SQL <b>之前</b>；本拦截器在 {@code Executor#query/update}
 *       前置执行，满足顺序。</li>
 *   <li><b>同一连接</b>：从 {@codeExecutor} 当前事务取连接，绝不能另开连接；
 *       否则 {@code is_local=true} 的绑定对新连接无效。取不到事务连接时 <b>fail-closed</b>。</li>
 *   <li><b>事务局部</b>：第三个参数 {@code is_local=true}（与 {@link TenantSessionBinder#SQL_SET_TENANT} 一致），
 *       使变量随事务结束/回滚自动恢复。</li>
 *   <li><b>无主体拒绝</b>：{@link TenantScope#require()} 抛异常时不吞掉，直接中断 SQL。</li>
 * </ol>
 *
 * <p><b>运维通道豁免</b>：本拦截器<b>只装配在租户 SqlSessionFactory 上</b>；
 * BYPASSRLS 运维通道用独立的、不装本拦截器的 SqlSessionFactory（见 RlsGuardConfiguration）。
 *
 * <p><b>性能</b>：同一事务内对同一连接只绑定一次（ThreadLocal 记录已绑定的连接+租户），
 * 避免每条 SQL 多一次 set_config 往返；连接归还/事务结束时务必调用
 * {@link #onConnectionRelease()} 清理标记（由配置中的 AOP 切面在 finally 触发）。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class,
                        org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class RlsTenantBindingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RlsTenantBindingInterceptor.class);

    private static final String SQL_SET_TENANT =
            "SELECT set_config('" + TenantSessionBinder.SESSION_VAR + "', ?, true)";

    /** 同一连接+租户只绑定一次（key = connection identity + tenantId）。 */
    private static final ThreadLocal<BoundKey> BOUND = new ThreadLocal<>();

    @Override
    public Object intercept(@NonNull Invocation invocation) throws Throwable {
        // ① fail-closed：无租户主体直接拒绝（D2）
        String tenantId = TenantScope.require();

        // ② 同一事务连接（D3）：从 Executor 的 Transaction 取底层 Connection
        Connection connection = currentConnection(invocation);
        BoundKey key = new BoundKey(connection, tenantId);
        if (!key.equals(BOUND.get())) {
            try (PreparedStatement ps = connection.prepareStatement(SQL_SET_TENANT)) {
                ps.setString(1, tenantId);
                ps.execute();
            } catch (SQLException e) {
                throw new IllegalStateException("RLS 租户绑定失败：tenant=" + tenantId, e);
            }
            BOUND.set(key);
            if (log.isTraceEnabled()) {
                log.trace("RLS 已绑定: tenant={}, stmt={}", tenantId,
                        ((MappedStatement) invocation.getArgs()[0]).getId());
            }
        }

        // ③ 放行业务 SQL —— 此时 set_config 已在同一连接生效
        return invocation.proceed();
    }

    /** 从 Executor 的 SpringManagedTransaction 取连接；取不到（非事务）fail-closed。 */
    private static Connection currentConnection(Invocation invocation) throws SQLException {
        Executor executor = (Executor) invocation.getTarget();
        Object transaction = executor.getTransaction();
        if (transaction instanceof org.mybatis.spring.transaction.SpringManagedTransaction smt) {
            // getConnection() 在 SpringManagedTransaction 中会走 DataSourceUtils，
            // 与外层 @Transactional / TransactionTemplate 拿到同一条连接。
            Connection conn = smt.getConnection();
            if (conn == null) {
                throw new IllegalStateException("无法取得事务连接：RLS 绑定必须与业务 SQL 同连接");
            }
            return conn;
        }
        throw new IllegalStateException("不支持的事务类型：" + transaction
                + "。RLS 要求 MyBatis 使用 SpringManagedTransaction（与业务同事务同连接）");
    }

    /** 事务/请求结束时清理绑定标记（由 AOP 切面在 finally 调用）。 */
    public static void onConnectionRelease() {
        BOUND.remove();
        TenantScope.clear();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }

    /** 连接身份 + 租户的复合键，连接归还后必须失效。 */
    private record BoundKey(Connection connection, String tenantId) {
    }
}

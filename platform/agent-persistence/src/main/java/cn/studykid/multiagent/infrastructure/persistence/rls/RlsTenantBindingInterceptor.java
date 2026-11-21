package cn.studykid.multiagent.infrastructure.persistence.rls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * RLS 会话绑定拦截器（W0 核心，对应主文档 §10 D2/D3）。
 *
 * <p><b>职责</b>：在每条 Mapper SQL 执行前，从 {@link TenantScope} 取主体租户，
 * 在<b>与业务 SQL 同一条连接</b>上执行 {@code set_config('app.tenant_id', ?, true)}。
 *
 * <p>⚠️ <b>四条铁律</b>：
 * <ol>
 *   <li><b>无主体拒绝</b>：{@link TenantScope#require()} 抛异常时不吞掉，直接中断；</li>
 *   <li><b>同一连接</b>：连接来自 {@link TenantBoundTransactionFactory} 借用的外层事务连接，
 *       不另开连接（is_local=true 的绑定才有效）；</li>
 *   <li><b>租户漂移校验</b>：若该连接已绑定了<b>另一个</b>租户（连接池串租户），立即抛异常；</li>
 *   <li><b>归还清理</b>：请求结束由 {@link TenantScope#clear()} 触发
 *       {@link #onConnectionRelease()} 清空 ThreadLocal 标记。</li>
 * </ol>
 *
 * <p><b>运维通道豁免</b>：本拦截器只装在租户 SqlSessionFactory 上；BYPASSRLS 运维通道
 * 用独立工厂（不装本拦截器）。
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
    private static final String SQL_READ_TENANT =
            "SELECT current_setting('" + TenantSessionBinder.SESSION_VAR + "', true)";

    /**
     * PLATFORM 作用域哨兵租户（零 UUID）：与 JdbcConfigPointerRepository 等实现的
     * {@code PLATFORM_SCOPE_TENANT} 同值。嵌套事务里在租户绑定连接上执行
     * PLATFORM 作用域查询是合法路径（JDBC 实现嵌套复用连接、从不重绑），
     * 且 {@code cap_config_pointer_read} 策略对 {@code tenant_id IS NULL} 行
     * 天然放行 —— 故哨兵目标不视为漂移、也不重绑。
     */
    private static final String PLATFORM_SENTINEL = "00000000-0000-0000-0000-000000000000";

    /** 同一连接已绑定的租户（用于漂移校验；连接归还时清空）。 */
    private static final ThreadLocal<String> BOUND_TENANT = new ThreadLocal<>();

    @Override
    public Object intercept(@NonNull Invocation invocation) throws Throwable {
        // ① fail-closed：无租户主体直接拒绝
        String tenantId = TenantScope.require();

        // ② 取借用的外层事务连接（TenantBoundTransactionFactory 保证同连接）
        Executor executor = (Executor) invocation.getTarget();
        Connection connection = executor.getTransaction().getConnection();

        // ③ 漂移校验 + 绑定
        String existing = readBoundTenant(connection);
        // PLATFORM 哨兵例外：JDBC 嵌套事务语义是「复用外层连接、不重绑」——
        // 在租户绑定连接上执行 PLATFORM 作用域查询合法（读策略对 NULL 租户行放行，
        // 写策略对哨兵天然拒绝，fail-closed 方向不变）。反向（真租户进入哨兵连接）仍按漂移拒绝。
        if (PLATFORM_SENTINEL.equals(tenantId) && existing != null && !existing.isBlank()
                && !PLATFORM_SENTINEL.equals(existing)) {
            return invocation.proceed();
        }
        if (existing != null && !existing.isBlank() && !existing.equals(tenantId)) {
            throw new IllegalStateException("RLS 租户漂移：连接上已绑定 " + existing
                    + "，当前请求主体为 " + tenantId + "。连接池串租户，拒绝执行 SQL");
        }
        if (!tenantId.equals(BOUND_TENANT.get())) {
            try (PreparedStatement ps = connection.prepareStatement(SQL_SET_TENANT)) {
                ps.setString(1, tenantId);
                ps.execute();
            } catch (SQLException e) {
                throw new IllegalStateException("RLS 租户绑定失败：tenant=" + tenantId, e);
            }
            BOUND_TENANT.set(tenantId);
            if (log.isTraceEnabled()) {
                log.trace("RLS 已绑定: tenant={}, stmt={}", tenantId,
                        ((MappedStatement) invocation.getArgs()[0]).getId());
            }
        }

        return invocation.proceed();
    }

    /** 读取当前连接上的租户；读不到返回 null（不视为错误，由后续 set_config 建立）。 */
    private static String readBoundTenant(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SQL_READ_TENANT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 请求/事务结束时清空绑定标记（由 TenantScope.clear() 调用）。 */
    public static void onConnectionRelease() {
        BOUND_TENANT.remove();
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

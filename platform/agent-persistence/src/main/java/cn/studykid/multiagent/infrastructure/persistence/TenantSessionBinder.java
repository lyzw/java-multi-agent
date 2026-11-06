package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 租户上下文 ↔ PostgreSQL 会话变量绑定。
 *
 * <p><b>这是 RLS 能否真正生效的关键环节</b>（ADR-006）。数据库侧策略形如
 * {@code tenant_id = current_tenant_id()}，而 {@code current_tenant_id()}
 * 读取 {@code current_setting('app.tenant_id')}。该变量绑定在
 * <b>数据库连接</b> 上，而非线程上——这带来两个必须处理的问题：
 *
 * <ol>
 *   <li><b>上下文串租户</b>：连接池复用连接时，若上一次请求残留了
 *       {@code app.tenant_id}，下一个请求若未设置就会<b>沿用上一个租户的身份</b>。
 *       这比不设更危险——它静默产生越权数据。</li>
 *   <li><b>事务内一致</b>：同一事务内多条语句必须看到同一租户上下文，
 *       否则中途被改会破坏一致性。</li>
 * </ol>
 *
 * <p><b>处置</b>：使用 {@code set_config(..., true)} 的<b>事务局部</b>语义
 * （第三个参数 {@code is_local=true}），使变量在事务结束或回滚时自动恢复。
 * 同时在获取连接时<b>无条件重置</b>，不依赖「上一次是否设置过」。
 */
public final class TenantSessionBinder {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionBinder.class);

    /** 与 V1__baseline_schema.sql 中 current_tenant_id() 读取的键名保持一致。 */
    public static final String SESSION_VAR = "app.tenant_id";

    private static final String SQL_SET_TENANT =
            "SELECT set_config('" + SESSION_VAR + "', ?, true)";

    /** 置空而非设为 NULL：current_tenant_id() 用 NULLIF(...,'') 转 NULL。 */
    private static final String SQL_CLEAR_TENANT =
            "SELECT set_config('" + SESSION_VAR + "', '', true)";

    private TenantSessionBinder() {
    }

    /**
     * 将租户上下文绑定到当前事务。
     *
     * <p>必须在事务内、且在<b>该连接首次执行任何业务 SQL 之前</b>调用。
     *
     * @param connection 当前事务连接（由 Spring 事务管理保证同一事务内同一连接）
     * @param context    租户上下文
     */
    public static void bind(@NonNull Connection connection, @NonNull TenantContext context)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SQL_SET_TENANT)) {
            ps.setString(1, context.tenantId());
            ps.execute();
        }
        if (log.isTraceEnabled()) {
            log.trace("租户上下文已绑定: {}", context.auditString());
        }
    }

    /**
     * 显式清空租户上下文。
     *
     * <p>用于「以系统身份执行、不属于任何租户」的场景（如调度器扫描待处理任务、
     * 迁移校验）。<b>调用后该连接将无法看到任何租户数据</b>（RLS 默认拒绝），
     * 这是刻意设计——避免以系统身份意外读取全租户数据。
     */
    public static void clear(@NonNull Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SQL_CLEAR_TENANT)) {
            ps.execute();
        }
    }

    /**
     * 读取当前连接上绑定的租户上下文（供诊断与断言使用）。
     *
     * @return 租户 ID；未绑定时返回 {@code null}
     */
    public static String currentTenantId(@NonNull Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT current_setting('" + SESSION_VAR + "', true)")) {
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * 在指定租户上下文中执行操作。
     *
     * <p>用于不需要 Spring 事务管理器的场景（如启动检查、后台任务）。
     * 自动提交模式下每个语句独立事务，故每条语句都需重新绑定——
     * 这也是本方法为每条语句单独执行 {@code set_config} 的原因。
     */
    public static void runInTenantScope(@NonNull DataSource dataSource,
                                        @NonNull TenantContext context,
                                        @NonNull TenantScopedAction action) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                bind(conn, context);
                action.run(conn);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** 租户作用域内的数据库操作。 */
    @FunctionalInterface
    public interface TenantScopedAction {
        void run(@NonNull Connection connection) throws SQLException;
    }
}
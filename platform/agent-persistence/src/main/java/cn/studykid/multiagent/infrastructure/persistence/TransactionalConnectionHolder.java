package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 事务连接持有者（P2-D05 §2 D-1）。
 *
 * <p><b>要解决的问题</b>：{@code JdbcRunCreationRepository} 的每个方法都
 * <b>自己开连接、自己 commit</b>。这意味着应用层的
 * {@code RunCreationService.TransactionRunner} 即使被正确实现，
 * 外层事务对 Repository 也<b>完全无效</b>——红线 3「四者同事务」
 * 只能靠「恰好只有一个方法写这四张表」这一脆弱假设维持。
 *
 * <p><b>为什么用 ThreadLocal 而不改方法签名</b>：
 * 给端口的 6 个方法加 {@code Connection} 参数会
 * 把持久化细节泄漏进领域端口，且需改动全部调用方与测试。
 * ThreadLocal 只改 {@code tenantConnection()} 一处。
 *
 * <p>⚠️ <b>代价与必须遵守的纪律</b>：ThreadLocal 在线程池场景
 * 若不清理会<b>跨请求泄漏连接</b>——下一个请求会沿用上一个租户的事务，
 * 这比不设更危险（静默越权）。因此：
 * <ol>
 *   <li>{@link #bind} 必须配合 try/finally 使用；</li>
 *   <li>{@link #clear} 必须在 finally 中<b>无条件</b>调用；</li>
 *   <li>不得把本类的连接跨线程传递。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么不做传播语义</b>（REQUIRED / NESTED 等）：
 * 本项目事务边界极浅（最深两层）。引入传播语义会让
 * 「哪些语句在事务内」无法静态判定——而这正是 RLS 最怕的模糊地带。
 * 这里只区分两种情形：<b>外层有连接则复用，否则自开</b>。
 */
public final class TransactionalConnectionHolder {

    private static final Logger log = LoggerFactory.getLogger(TransactionalConnectionHolder.class);

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private TransactionalConnectionHolder() {
    }

    /**
     * 取得当前事务连接；无则返回 {@code null}。
     *
     * <p>供 Repository 内部判断「是否处于外层事务中」。
     */
    @Nullable
    public static Connection current() {
        return CURRENT.get();
    }

    /**
     * 判断当前线程是否已处于事务中。
     */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * 绑定事务连接。
     *
     * @param connection 已绑定租户上下文、且自动提交已关闭的连接
     * @throws IllegalStateException 若已绑定 —— 说明事务发生了意外嵌套，
     *         此时必须复用外层连接而非另开（另开会让外层的回滚失效）
     */
    public static void bind(@NonNull Connection connection) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "事务连接重复绑定。实现须遵循「外层有连接则复用」原则，"
                            + "另开连接会使外层回滚失效，进而破坏原子性。");
        }
        CURRENT.set(connection);
        if (log.isTraceEnabled()) {
            log.trace("事务连接已绑定");
        }
    }

    /**
     * 清除事务连接。
     *
     * <p>⚠️ <b>必须在 finally 中无条件调用</b>。
     * 忘记清理会让连接泄漏到下一个请求，构成跨租户数据泄漏。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在事务中执行一段逻辑，并管理连接的获取、提交与回滚。
     *
     * <p>⚠️ <b>本方法不做事务传播</b>：若当前线程已在事务中，
     * 直接执行动作并复用外层连接，<b>不提交</b>。
     *
     * @param dataSource 仅在需要自开连接时使用
     * @param context   租户上下文
     * @param action    事务内动作
     */
    public static <T> T inTransaction(@NonNull DataSource dataSource,
                                       @NonNull TenantContext context,
                                       @NonNull TxAction<T> action) {
        // 情形一：嵌套调用 —— 复用外层连接，不开新事务、不提交
        if (isActive()) {
            try {
                return action.run(CURRENT.get());
            } catch (SQLException e) {
                throw new IllegalStateException("事务内操作失败", e);
            }
        }

        // 情形二：顶层调用 —— 开连接 + 绑定租户 + 执行 + 提交/回滚
        // ⚠️ 复用 TenantConnectionLease 的生命周期语义，避免两套重复实现
        try (TenantConnectionLease lease = openTenantConnection(dataSource, context)) {
            Connection conn = lease.connection();
            bind(conn);
            try {
                T result = action.run(conn);
                lease.commitQuietly();
                return result;
            } finally {
                // ⚠️ 必须无条件清理 —— 忘记清理会让连接泄漏到下一个请求，
                //    下一个请求会沿用上一个租户的事务，构成跨租户数据泄漏。
                clear();
            }
        } catch (SQLException e) {
            // ⚠️ 覆盖两类来源：openTenantConnection 失败，以及
            //    Action.run 抛出的 SQL 异常。二者都必须转成运行时异常，
            //    否则调用方（RunCreationService）无法统一处理。
            throw new IllegalStateException("事务执行失败", e);
        }
    }

    /**
     * 开启一个已绑定租户的自持连接。
     */
    public static TenantConnectionLease openTenantConnection(@NonNull DataSource dataSource,
                                                              @NonNull TenantContext context)
            throws SQLException {
        Connection conn = dataSource.getConnection();
        try {
            conn.setAutoCommit(false);
            // ⚠️ RLS 前置条件：set_config(..., is_local=true) 必须配合事务，
            //    且必须在任何业务 SQL 之前。顺序颠倒或漏掉 → 可见 0 行。
            TenantSessionBinder.bind(conn, context);
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 关闭失败不掩盖原始异常
            }
            throw e;
        }
        return TenantConnectionLease.open(conn);
    }

    /** 事务内动作。 */
    @FunctionalInterface
    public interface TxAction<T> {
        T run(@NonNull Connection connection) throws SQLException;
    }
}
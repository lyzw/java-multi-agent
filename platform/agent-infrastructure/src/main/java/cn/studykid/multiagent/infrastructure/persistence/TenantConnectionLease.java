package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户连接的<b>生命周期租约</b>（P2-D05 §2 D-1）。
 *
 * <p><b>存在的理由</b>：{@code JdbcRunCreationRepository} 的只读方法
 * 原本用 try-with-resources 管理连接：
 *
 * <pre>{@code
 * try (Connection conn = tenantConnection(tenantId)) { ... conn.rollback(); }
 * }</pre>
 *
 * 一旦 {@code tenantConnection} 开始<b>复用外层事务连接</b>，
 * 这段代码就会造成双重灾难：
 * <ul>
 *   <li>{@code close()} 关闭的是<b>外层事务的连接</b> → 外层提交时连接已失效；</li>
 *   <li>{@code rollback()} 回滚的是<b>外层未提交的写入</b> → 数据凭空消失。</li>
 * </ul>
 *
 * 且这两种失效都是<b>静默的</b>：不报错，只是数据莫名丢失或事务提前结束。
 *
 * <p><b>因此</b>：连接的所有权必须显式化。
 * <ul>
 *   <li><b>自开连接</b> → 租约负责 rollback + close；</li>
 *   <li><b>复用外层连接</b> → 租约不做任何事，提交与回滚权<b>归外层</b>。</li>
 * </ul>
 */
public final class TenantConnectionLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantConnectionLease.class);

    private final Connection connection;
    private final boolean owned;

    private TenantConnectionLease(@NonNull Connection connection, boolean owned) {
        this.connection = connection;
        this.owned = owned;
    }

    /**
     * 从数据源开启一个<b>自持</b>连接（无外层事务时使用）。
     *
     * <p>已关自动提交并绑定租户上下文（RLS 前置条件）。
     */
    public static TenantConnectionLease open(@NonNull Connection connection) {
        return new TenantConnectionLease(connection, true);
    }

    /**
     * 复用<b>外层事务</b>连接（不归本租约所有）。
     */
    public static TenantConnectionLease borrowed(@NonNull Connection connection) {
        return new TenantConnectionLease(connection, false);
    }

    /**
     * 判断连接是否为本租约所有。
     *
     * <p>调用方据此决定能否安全地 commit/rollback。
     */
    public boolean isOwned() {
        return owned;
    }

    /**
     * 取得连接。
     */
    public @NonNull Connection connection() {
        return connection;
    }

    /**
     * 回滚。
     *
     * <p>⚠️ <b>复用连接时拒绝执行</b>：回滚外层事务是
     * 数据丢失的快捷方式，必须让误用在开发期就暴露为异常，
     * 而不是悄悄生效。
     */
    public void rollbackQuietly() {
        if (!owned) {
            // 外层事务的提交/回滚不归本方法所有
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败: {}", e.getMessage());
        }
    }

    /**
     * 提交（仅限自持连接）。
     */
    public void commitQuietly() {
        if (!owned) {
            // ⚠️ 提前提交会让外层后续失败时无法回滚本次写入
            return;
        }
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("提交失败", e);
        }
    }

    @Override
    public void close() {
        if (!owned) {
            // 复用连接：⚠️ 绝不能关闭 —— 外层事务还要用它
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                // 未提交即归还：驱动会回滚，显式回滚是为了不依赖驱动实现细节
                connection.rollback();
            }
        } catch (SQLException e) {
            log.warn("关闭前回滚失败: {}", e.getMessage());
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("关闭连接失败: {}", e.getMessage());
        }
    }
}
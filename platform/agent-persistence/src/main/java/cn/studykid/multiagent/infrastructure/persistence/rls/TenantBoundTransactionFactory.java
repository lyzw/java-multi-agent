package cn.studykid.multiagent.infrastructure.persistence.rls;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;

/**
 * 把 MyBatis 接到本项目「显式连接事务」的桥（W0 RLS 地基，对应主文档 §6 / §10 D3）。
 *
 * <p><b>为什么不能用 MyBatis 默认的 SpringManagedTransaction</b>：
 * 本项目的写事务由 {@link TransactionalConnectionHolder#inTransaction} 显式持有
 * {@code Connection}（ThreadLocal），<b>不走 Spring TransactionManager</b>。
 * SpringManagedTransaction 会通过 {@code DataSourceUtils} 另开连接 —— 与外层事务
 * <b>不是同一条连接</b>，于是 RLS 会话变量（set_config is_local=true）落在外层连接上，
 * 而 Mapper SQL 跑在新连接上，结果就是「写入成功但查询恒为 0」或越权。
 *
 * <p><b>本工厂的行为</b>：
 * <ul>
 *   <li>{@code getConnection()}：<b>借用</b> {@link TransactionalConnectionHolder#current()}；
 *       无外层事务时 fail-closed（不允许「无事务的隐式 RLS 查询」）；</li>
 *   <li>{@code commit/rollback/close()}：<b>全部 no-op</b> —— 连接所有权与提交权归外层
 *       {@code TransactionalConnectionHolder}（与 {@code TenantConnectionLease.borrowed()} 同语义）。
 *       若在此处 commit，会让外层后续失败无法回滚，破坏原子性。</li>
 * </ul>
 *
 * <p>⚠️ 该工厂只装配在租户 SqlSessionFactory 上；运维 BYPASSRLS 通道用独立的默认工厂。
 */
public class TenantBoundTransactionFactory implements TransactionFactory {

    @Override
    public @NonNull Transaction newTransaction(@NonNull Connection connection) {
        // MyBatis 传入已有连接（本场景不会走到）：仍以 ThreadLocal 外层连接为准，fail-closed
        return new BorrowedTransaction();
    }

    @Override
    public @NonNull Transaction newTransaction(@NonNull DataSource dataSource,
                                                @Nullable TransactionIsolationLevel level,
                                                boolean autoCommit) {
        return new BorrowedTransaction();
    }

    @Override
    public void setProperties(Properties props) {
        // no-op
    }

    /** 只借用、不持有、不提交的连接事务。 */
    private static final class BorrowedTransaction implements Transaction {

        private @Nullable Connection borrowed;

        @Override
        public @NonNull Connection getConnection() throws SQLException {
            Connection current = TransactionalConnectionHolder.current();
            if (current == null) {
                throw new SQLException("无外层事务连接：RLS 要求所有 Mapper 调用必须处于 "
                        + "TransactionalConnectionHolder.inTransaction(...) 内。"
                        + "禁止无事务查询（会导致 RLS 会话变量失效）");
            }
            if (borrowed != null && borrowed != current) {
                throw new SQLException("事务连接被替换：疑似嵌套事务另开连接，外层回滚会失效");
            }
            this.borrowed = current;
            return current;
        }

        @Override
        public void commit() {
            // no-op：提交权归外层 TransactionalConnectionHolder
        }

        @Override
        public void rollback() {
            // no-op：回滚权归外层；此处回滚等于提前终止外层事务
        }

        @Override
        public void close() {
            // no-op：连接归外层所有，绝不能关闭（否则外层提交时连接已失效）
        }

        @Override
        public @Nullable Integer getTimeout() {
            return null;
        }
    }
}

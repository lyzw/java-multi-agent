package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import java.sql.Connection;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;

/**
 * 租户事务执行器装配（2026-11-06 数据库层收敛）。
 *
 * <p>原先位于 {@code ApplicationConfiguration}，但它直接持有
 * {@link Connection} 与 {@link TransactionalConnectionHolder} ——
 * 属于数据库实现细节。迁入 {@code agent-persistence} 后，组合根只依赖
 * {@link RunCreationService.TransactionRunner} 这个应用层契约，
 * 更换 ORM / 事务实现时组合根无需改动。
 *
 * <p>⚠️ Bean 名称、类型、签名、语义完全不变；组件扫描仍覆盖本包。
 */
@Configuration
public class PersistenceTransactionConfiguration {

    /**
     * 事务执行器（P2-D5 生产实现）。
     *
     * <p>⚠️ <b>此处曾是「直接抛异常」的占位</b>，导致
     * {@code POST /api/runs} <b>必然 500</b>，端到端链路从未跑通。
     *
     * <p><b>为什么不用 Spring 的 {@code TransactionTemplate}</b>：
     * 事务管理器只管连接复用，<b>不管 RLS 租户绑定</b>。
     * 本项目必须在事务内执行 {@code set_config('app.tenant_id', ..., true)}，
     * 且该绑定必须在任何业务 SQL <b>之前</b>完成。
     * 用 Spring 事务管理器需要在事务内插一个 hook，
     * 反而不如显式持有连接来得可控 —— 这正是 P2-D05 §2 D-1 的取舍。
     *
     * <p>⚠️ <b>RLS 铁律</b>：实现必须「关自动提交 + 绑定租户 + commit/rollback」。
     * 少了任何一步，{@code set_config(..., is_local=true)} 立即失效，
     * 表现为「写入成功但查询恒为 0」—— 本项目曾有 6 处这样的缺陷。
     */
    @Bean
    public RunCreationService.TransactionRunner transactionRunner(DataSource dataSource) {
        return new RunCreationService.TransactionRunner() {
            @Override
            public <T> T execute(@NonNull String tenantId,
                                 RunCreationService.TransactionRunner.TransactionalAction<T> action) {
                // ⚠️ 租户 ID 来自认证上下文（RunCreationService 传入），
                //    **不是**来自请求参数。此处不得引入任何客户端输入。
                return TransactionalConnectionHolder.inTransaction(
                        dataSource,
                        systemContext(tenantId),
                        // ⚠️ TxAction<T> 声明 throws SQLException，
                        //    而 TransactionalAction<T> 不允许 ——
                        //    故必须用匿名类适配，不能直接传 lambda 引用。
                        new TransactionalConnectionHolder.TxAction<T>() {
                            @Override
                            public T run(@NonNull Connection connection) {
                                // ⚠️ action.run() 不声明受检异常，
                                //    但实现内部可能包了 IllegalStateException —— 原样透传
                                return action.run();
                            }
                        });
            }
        };
    }

    /**
     * 事务内使用的系统上下文。
     *
     * <p>⚠️ <b>为何用「系统」身份而非业务主体</b>：
     * 事务边界需要的是<b>RLS 租户绑定</b>，而 RLS 只依据
     * {@code app.tenant_id}，与角色无关。用空角色集的系统身份
     * 可避免误导——让人误以为这里做了授权检查。
     * <b>授权在 {@code RunController} 中已完成，此处绝不重复授权</b>。
     */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

}

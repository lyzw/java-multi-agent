package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.api.security.JwtCurrentPrincipal;
import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.RoleBasedAuthorizationService;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 应用层装配。
 *
 * <p><b>本类的存在理由</b>：D1 的端点虽已实现，但端口 Bean 从未注册，
 * 上下文启动失败 → <b>整个应用无法部署</b>。
 * 这类缺口不会体现在单元测试里（测试直接 new 对象），
 * 只有端到端测试能发现。
 *
 * <p><b>⚠️ 写装配前必须先确认「接口是否已有实现」</b>。
 * 本次曾写了一个「生产实现待接入」的 {@code RunCreationPort} 占位 Bean，
 * 而 {@code JdbcRunCreationRepository} 早已带 {@code @Repository} 被扫描注册 ——
 * 结果是两个候选 Bean，{@code NoUniqueBeanDefinitionException}。
 *
 * <p>⚠️ <b>本类只做「组装」，不做业务逻辑</b>：
 * 端口实现属基础设施层，授权逻辑属安全层。
 */
@Configuration
public class ApplicationConfiguration {

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

    /**
     * 授权服务。
     *
     * <p>⚠️ <b>P1 内存实现</b>：{@link RoleBasedAuthorizationService} 用内存维护
     * {@code authzVersion}，<b>多实例部署时状态不共享</b> ——
     * 实例 A 撤销的授权，实例 B 不知情。
     * 生产须换数据库实现（D-001 后续项 / P2-D05 §6 L3）。
     */
    @Bean
    public AuthorizationService authorizationService() {
        return new RoleBasedAuthorizationService();
    }

    /**
     * 零保留闸门。
     *
     * <p>⚠️ <b>当前无供应商确认</b>：DEC-10 GATE-2 已取得三家书面承诺，
     * 但本项目<b>刻意不在代码中硬编码确认状态</b> ——
     * 确认是运营事实，应由配置驱动。
     * 未登记供应商一律拒绝（fail-closed），
     * 接入真实数据前须在配置中登记。
     *
     * <p>⚠️ 这意味着<b>所有供应商调用当前均被拒绝</b>，
     * 这是设计内的 fail-closed 行为，不是缺陷。
     */
    @Bean
    public ZeroRetentionGate zeroRetentionGate() {
        return new ZeroRetentionGate(ZeroRetentionRegistry.empty(), Clock.systemUTC());
    }

    @Bean
    public RunCreationService runCreationService(
            cn.studykid.multiagent.ports.persistence.RunCreationPort port,
            RunCreationService.TransactionRunner tx,
            Clock clock) {
        return new RunCreationService(port, tx, clock);
    }

    /**
     * 预算仓储（P3-D04 / FR-012）。
     *
     * <p>⚠️ <b>刻意不加 {@code @Service}</b>：与 {@code JdbcToolOperationLedger}
     * 一致，装配统一归本类，避免被测试切片的组件扫描意外拾取
     * （E2E 15 项曾因此全错）。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository(
                dataSource);
    }

    /**
     * 审批消费仓储（P3-D05 / FR-013）。
     *
     * <p>⚠️ <b>刻意不加 {@code @Service}</b>：与 {@code JdbcBudgetRepository} 一致，
     * 装配统一归本类，避免被测试切片的组件扫描意外拾取。
     *
     * <p><b>默认 fail-closed</b>：审批权威（人类审批台 / 策略引擎）尚未接入，
     * 需要审批的工具在查不到有效审批时一律被拒——这是设计内的安全行为。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ApprovalPort approvalPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcApprovalRepository(
                dataSource);
    }

    /**
     * 预算对账服务（P3-D04 §4.3）。
     *
     * <p>⚠️ <b>无调度器</b>：本 Bean 只提供能力，触发时机待 D7 审计链路接入
     * （P3-D04 §6 L-3）。在对账被调度之前，「悬挂预留」只会被发现、不会被处置。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.budget.BudgetReconciliationService
            budgetReconciliationService(
                    cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort,
                    cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository repo) {
        return new cn.studykid.multiagent.infrastructure.budget.BudgetReconciliationService(
                budgetPort, repo);
    }

    /**
     * 单次工具调用的预算预留额（P3-D04 §2.6 D-6）。
     *
     * <p>⚠️ <b>装配期即校验</b>：缺失或非正数在此处抛异常拒绝启动，
     * 而不是等到某个工具调用时才被拒。后者会让「预算闸门形同虚设」
     * 在测试中不易察觉（F-4 的教训）。
     */
    @Bean
    public java.math.BigDecimal budgetReserveAmountPerToolCall(
            @org.springframework.beans.factory.annotation.Value(
                    "${" + cn.studykid.multiagent.application.config.BudgetPolicy
                            .KEY_RESERVE_PER_TOOL_CALL + "}") Object raw) {
        return cn.studykid.multiagent.application.config.BudgetPolicy
                .requireReserveAmount(raw);
    }

    /**
     * 当前主体。
     *
     * <p>⚠️ <b>本实现只在真实 HTTP 请求下有意义</b>：它从请求属性取
     * Security 过滤器已验签的上下文。无请求上下文时（如 Worker 进程）
     * 恒失败 —— <b>Worker 不应依赖它</b>。
     */
    @Bean
    public RunController.CurrentPrincipal currentPrincipal(
            AuthenticationService authentication) {

        return new RunController.CurrentPrincipal() {

            @Override
            public TenantContext context() {
                // ⚠️ 优先取过滤器已验签的结果 ——
                //    自行解析令牌会形成两处实现，一旦不一致即语义分叉。
                if (RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attrs) {
                    Object verified = attrs.getRequest().getAttribute(
                            SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE);
                    if (verified instanceof TenantContext ctx) {
                        return ctx;
                    }
                }

                // 兜底：过滤器未设置（如白名单端点）时自行解析。
                // ⚠️ 无令牌即抛 401，**不得**返回任何默认租户。
                return new JwtCurrentPrincipal(
                        authentication, Clock.systemUTC(), currentAuthorizationHeader())
                        .require();
            }

            @Override
            public String principalId() {
                return context().principalId();
            }

            @Override
            public String providerId() {
                // ⚠️ 多供应商场景应按路由选择。当前返回第一家 ——
                //    未在零保留注册表登记的供应商会被闸门拒绝，不会被错误放行。
                //    已知限制：P2-D05 §6 L4。
                return ModelProviders.DEEPSEEK;
            }

            @Override
            public String snapshotId() {
                // ⚠️ 每次随机生成 → 同一幂等键重放时快照不一致。
                //    已知限制：P2-D05 §6 L5。
                return UUID.randomUUID().toString();
            }

            @Override
            public String correlationId() {
                if (RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attrs) {
                    Object id = attrs.getRequest().getAttribute("correlationId");
                    if (id != null) {
                        return String.valueOf(id);
                    }
                }
                return UUID.randomUUID().toString();
            }

            private String currentAuthorizationHeader() {
                if (RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attrs) {
                    return attrs.getRequest().getHeader("Authorization");
                }
                return null;
            }
        };
    }
}
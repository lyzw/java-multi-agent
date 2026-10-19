package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.util.UUID;

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
     * 事务执行器。
     *
     * <p>⚠️ <b>RLS 铁律</b>：实现必须「关自动提交 + 绑定租户 + commit/rollback」。
     * 少了任何一步，{@code set_config(..., is_local=true)} 立即失效，
     * 表现为「写入成功但查询恒为 0」—— 本项目曾有 6 处这样的缺陷。
     *
     * <p>TODO(P2-D5)：改用 Spring {@code TransactionTemplate} +
     * {@code TenantSessionBinder}，避免手写 JDBC 事务。
     */
    @Bean
    public RunCreationService.TransactionRunner transactionRunner() {
        return new RunCreationService.TransactionRunner() {
            @Override
            public <T> T execute(String tenantId,
                                 RunCreationService.TransactionRunner.TransactionalAction<T> action) {
                throw new IllegalStateException(
                        "TransactionRunner 生产实现待接入（P2-D5）。"
                                + "RLS 要求事务内绑定租户，该路径不可省略。");
            }
        };
    }

    /**
     * 授权服务。
     *
     * <p>⚠️ <b>P1 内存实现</b>：{@link RoleBasedAuthorizationService} 用内存维护
     * {@code authzVersion}，<b>多实例部署时状态不共享</b> ——
     * 实例 A 撤销的授权，实例 B 不知情。
     * 生产须换数据库实现（D-001 后续项）。
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
                return ModelProviders.DEEPSEEK;
            }

            @Override
            public String snapshotId() {
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
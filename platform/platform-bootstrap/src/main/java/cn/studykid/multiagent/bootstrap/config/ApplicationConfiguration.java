package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.api.run.RunEventStreamer;
import cn.studykid.multiagent.api.security.JwtCurrentPrincipal;
import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.RoleBasedAuthorizationService;
import cn.studykid.multiagent.application.config.RateLimitPolicy;
import cn.studykid.multiagent.infrastructure.persistence.JdbcRateLimiter;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
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
@org.springframework.context.annotation.Import(WorkerExecutionConfig.class)
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
     * <p>⚠️ <b>配置驱动登记（P4-D07 / C9 接线）</b>：
     * 从 yml {@code agent.compliance.zero-retention.confirmed-providers}
     * 读取声明的供应商清单，逐家注册为 {@code pending()}（已登记但未确认）。
     *
     * <p><b>⚠️ 诚实、fail-closed 安全</b>：
     * {@code ZeroRetentionRegistry.confirm()} <b>强制 {@code evidenceRef} 非空</b>
     * （CM-1 红线），而 yml 仅提供供应商清单、<b>不含证据出处</b>——
     * 本片<b>不伪造证据</b>，故只注册为 {@code pending}。
     * {@code pending} ≠ {@code confirmed}，{@code allows()} 对 pending 返回 false
     * → 闸门<b>仍拒绝</b>真实学生数据，<b>运行时行为不变（fail-closed 维持）</b>，
     * 仅注册表不再为空，合规页得以诚实展示「已声明 N 家 / 均无有效确认 → 仍拒绝」。
     *
     * <p>⚠️ 真正按证据确认（CM-1）需新增证据承载配置
     * （如 {@code agent.compliance.zero-retention.provider-evidence.<id>}），
     * 属后续独立 CHG，本片不引入。
     */
    @Bean
    public ZeroRetentionGate zeroRetentionGate(
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.compliance.zero-retention.confirmed-providers:}") String confirmedProviders) {
        ZeroRetentionRegistry.Builder builder = ZeroRetentionRegistry.builder();
        if (confirmedProviders != null && !confirmedProviders.isBlank()) {
            for (String id : confirmedProviders.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    builder = builder.pending(trimmed);
                }
            }
        }
        return new ZeroRetentionGate(builder.build(), Clock.systemUTC());
    }

    /**
     * 配置只读投影服务（P4-D07 / C1）。
     *
     * <p>⚠️ <b>纯只读</b>：仅从 {@link org.springframework.core.env.Environment}
     * 读出 yml 声明值并标注每条配置的 {@code effective}（是否接入运行时）。
     * <b>不含任何业务逻辑</b>，故放在 api 层作为读模型。
     *
     * <p>⚠️ 刻意<b>不</b>加 {@code @Service}：与本项目装配统一归本类的约定一致
     * （见 {@code budgetPort} / {@code runClaimPort}），避免被测试切片的组件扫描
     * 意外拾取导致 Bean 候选冲突。
     */
    @Bean
    public cn.studykid.multiagent.api.config.ConfigReadService configReadService(
            org.springframework.core.env.Environment environment,
            ZeroRetentionGate zeroRetentionGate) {
        return new cn.studykid.multiagent.api.config.ConfigReadService(
                environment, zeroRetentionGate);
    }

    /**
     * 生效配置解析器（P4-D15 / C3 生效侧）。
     *
     * <p>⚠️ <b>不加 {@code @Service}</b>：与本类统一装配约定一致
     * （见 {@code configReadService} / {@code budgetPort}），
     * 避免被测试切片的组件扫描意外拾取导致 Bean 候选冲突。
     *
     * <p>⚠️ <b>无本地缓存</b>：每次快照读一次 DB，故多实例天然一致；
     * 若将来加缓存，<b>必须</b>同时实现失效，否则会出现
     * 「实例 A 已生效、实例 B 仍用旧值」的分裂（P4-D15 §6 L-3）。
     */
    @Bean
    public cn.studykid.multiagent.application.config.EffectiveConfigResolver
            effectiveConfigResolver(cn.studykid.multiagent.ports.persistence.ConfigOverridePort port) {
        return new cn.studykid.multiagent.application.config.EffectiveConfigResolver(port);
    }

    @Bean
    public RunCreationService runCreationService(
            cn.studykid.multiagent.ports.persistence.RunCreationPort port,
            cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort snapshots,
            RunCreationService.TransactionRunner tx,
            Clock clock,
            cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort) {
        // ⚠️ 预算端口必须装配：缺了它，「声明了 Run 级额度」的创建请求会被
        //    fail-closed 拒绝（校验缺席 ≠ 校验通过）。
        return new RunCreationService(port, snapshots, tx, clock, budgetPort);
    }

    /**
     * 执行快照解析端口（P4-D18）。
     *
     * <p>⚠️ <b>不可加 {@code @Repository}</b>：与 {@code JdbcRateLimiter} 同理，
     * 装配统一归本类，避免被测试切片的组件扫描意外拾取。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort executionSnapshotPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcExecutionSnapshotResolver(
                dataSource);
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
    /**
     * Run 领取仓储（P3-D07）。
     *
     * <p>⚠️ <b>刻意去掉 {@code @Repository} 组件扫描</b>（P3-D07）：
     * 本类新增 {@code AuditPort} 构造参数后有<b>两个</b>构造器，
     * 组件扫描无法选择 → {@code No default constructor found}，
     * Spring 上下文启动失败、E2E 15 项全红。
     * <p>改为在本类显式 {@code @Bean} 注册，可同时做到：
     * ① 注入<b>真实</b> {@link cn.studykid.multiagent.ports.persistence.AuditPort}
     * （否则取消动作在生产中无痕，R-1 名存实亡）；
     * ② 与 budgetPort / approvalPort 的装配风格一致。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RunClaimPort runClaimPort(
            DataSource dataSource,
            cn.studykid.multiagent.ports.persistence.AuditPort auditPort) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRunClaimRepository(
                dataSource, auditPort);
    }

    /**
     * SSE 长连接推送器（P3-D10 / FR-014 部分）。
     *
     * <p>⚠️ 轮询 500ms / 最大存活 30 分钟：最大存活是<b>防连接泄漏的兜底</b>——
     * 即便 Run 永不终结（如 Worker 全挂），连接也不会无限占用资源。
     */
    @Bean
    public RunEventStreamer runEventStreamer() {
        return new RunEventStreamer(500L, 30 * 60 * 1000L);
    }

    /**
     * 指标注册表（P3-D10 / FR-017）。
     *
     * <p>⚠️ 刻意<b>不引 Micrometer</b>：引入后「指标不含高基数标签」会退化为
     * 文档约定。本类在<b>运行期</b>强制标签白名单——加错标签时**第一次调用就抛异常**，
     * 而非上线后由时序库崩溃来告知。
     */
    @Bean
    public cn.studykid.multiagent.application.observability.AgentMetrics agentMetrics() {
        return new cn.studykid.multiagent.application.observability.AgentMetrics();
    }

    /**
     * Prompt 注入防护（P3-D09 / FR-016）。
     *
     * <p>⚠️ 上限取<b>保守档</b>：宁可误拒超长输入，也不能让 Prompt 无限膨胀
     * （token 爆炸 → 成本失控，与 D8 限流同源威胁）。
     */
    @Bean
    public cn.studykid.multiagent.application.prompt.PromptGuard promptGuard() {
        return new cn.studykid.multiagent.application.prompt.PromptGuard();
    }

    /**
     * 限流器（P3-D08 / D-2）。
     *
     * <p>⚠️ <b>限额与窗口取自配置，缺失即拒绝启动</b>（fail-closed）：
     * 退回默认值等于「悄悄用了一个没人审过的限额」。
     *
     * <p>初值取<b>保守</b>档（30 次 / 60 秒），接入真实流量后按实测调整。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RateLimitPort rateLimitPort(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value(
                    "${" + cn.studykid.multiagent.application.config.RateLimitPolicy
                            .KEY_LIMIT + "}") Long limit,
            @org.springframework.beans.factory.annotation.Value(
                    "${" + cn.studykid.multiagent.application.config.RateLimitPolicy
                            .KEY_WINDOW_SECONDS + "}") Long windowSeconds,
            @org.springframework.beans.factory.annotation.Value(
                    "${" + cn.studykid.multiagent.application.config.RateLimitPolicy
                            .KEY_GOVERNANCE_QUERY_LIMIT + ":}") Long governanceQueryLimit,
            @org.springframework.beans.factory.annotation.Value(
                    "${" + cn.studykid.multiagent.application.config.RateLimitPolicy
                            .KEY_GOVERNANCE_DECIDE_LIMIT + ":}") Long governanceDecideLimit) {
        // ⚠️ 治理桶限额走**独立的配置键 + 缺省回落**，不走 requireLimit 的 fail-closed：
        //    治理台阈值配错只影响可用性，不会造成越权（见 RateLimitPolicy#governanceLimitFor）。
        //    但**全局限额仍 fail-closed**（它服务学生侧动作，配错就是安全边界）。
        java.util.Map<String, Long> bucketLimits = new java.util.LinkedHashMap<>();
        bucketLimits.put(RateLimitPort.Buckets.GOVERNANCE_QUERY,
                optionalLimit(governanceQueryLimit, RateLimitPort.Buckets.GOVERNANCE_QUERY));
        bucketLimits.put(RateLimitPort.Buckets.GOVERNANCE_DECIDE,
                optionalLimit(governanceDecideLimit, RateLimitPort.Buckets.GOVERNANCE_DECIDE));

        return new JdbcRateLimiter(
                dataSource,
                RateLimitPolicy.requireLimit(limit),
                RateLimitPolicy.requireWindowSeconds(windowSeconds),
                bucketLimits);
    }

    /** 治理桶限额：未配置则用 {@link RateLimitPolicy} 的缺省值；配置了则必须为正。 */
    private static long optionalLimit(Long configured, String bucket) {
        if (configured == null || configured.toString().isBlank()) {
            return RateLimitPolicy.governanceLimitFor(bucket);
        }
        long value;
        try {
            value = Long.parseLong(configured.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("治理桶限额不是整数（拒绝启动）：" + bucket);
        }
        if (value <= 0) {
            throw new IllegalStateException("治理桶限额必须为正数（0 会锁死治理台）："
                    + bucket + " = " + value);
        }
        return value;
    }

    @Bean
    public cn.studykid.multiagent.ports.persistence.AuditPort auditPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository(
                dataSource);
    }

    @Bean
    public cn.studykid.multiagent.ports.persistence.ApprovalPort approvalPort(
            DataSource dataSource,
            cn.studykid.multiagent.ports.persistence.AuditPort auditPort) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcApprovalRepository(
                dataSource, auditPort);
    }

    // ── 工具注册（P4-D08 R-1 修复）────────────────────────────────────
    //
    // ⚠️⚠️ **本块存在的唯一理由**：修复一处**断链**——
    // GetStudentProfileTool / RecordWeaknessTool / StudentDataRepository
    // 三个类**均无 Spring 注解**，且全仓非测试代码**零处**实例化它们，
    // 于是 WorkerExecutionConfig 注入的 `List<ToolHandler>` 在生产中**恒为空**。
    //
    // 后果：ToolInvocationGate.findHandler 恒返回 null → 记「未登记的工具」→
    // **工具从不执行** → `weakness_record` 表在生产中**永远为空**。
    // 而系统表面完全正常：Run 仍 SUCCEEDED、模型仍返回文本、全部测试仍全绿
    // （测试直接 `new` 这些类，验证的是「被直接调用时行为正确」，
    //   没有任何一个验证「它是否真被调用」——这正是 P4-D01 确立的方法论）。
    //
    // ⚠️ **沿用 P4-D07 装配惯例**：显式 `@Bean` 而非 `@Service`/`@Component`
    // （避免测试切片扫描冲突）。
    //
    // ⚠️ **两个工具都声明为 ToolHandler 接口类型**：
    // 闸门按接口收集，新增工具只需再加一个 @Bean，不必改闸门装配
    // （符合「闸门是唯一入口」ADR-011 §3.3）。
    //
    // ⚠️ **DataSource 用主池（非 adminPool）**：
    // StudentDataRepository 走 TenantSessionBinder（关自动提交 + 事务内绑定租户），
    // 必须在**应用角色**连接上执行，RLS 策略才生效。

    /**
     * 画像与薄弱点仓储（P2-D04 / P4-D08 R-1）。
     *
     * <p>⚠️ 走 {@code TenantSessionBinder}：关自动提交 + 事务内绑定租户，
     * 少任何一步 RLS 绑定即失效，表现为「查询恒返回 0 行」。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.tool.StudentDataRepository
            studentDataRepository(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.tool.StudentDataRepository(dataSource);
    }

    /**
     * {@code get_student_profile} 工具（只读，READ 风险等级）。
     *
     * <p>⚠️ 声明为 {@link cn.studykid.multiagent.ports.tool.ToolHandler} 接口类型，
     * 由 Spring 收集进 {@code List<ToolHandler>} 注入闸门。
     */
    @Bean
    public cn.studykid.multiagent.ports.tool.ToolHandler getStudentProfileTool(
            cn.studykid.multiagent.infrastructure.tool.StudentDataRepository repository) {
        return new cn.studykid.multiagent.infrastructure.tool.GetStudentProfileTool(repository);
    }

    /**
     * {@code record_weakness} 工具（首期唯一有副作用工具，WRITE_PROFILE 风险等级）。
     *
     * <p>⚠️ 同上：声明为接口类型。
     */
    @Bean
    public cn.studykid.multiagent.ports.tool.ToolHandler recordWeaknessTool(
            cn.studykid.multiagent.infrastructure.tool.StudentDataRepository repository) {
        return new cn.studykid.multiagent.infrastructure.tool.RecordWeaknessTool(repository);
    }

    /**
     * 平台级**映射型**配置（C8 接线 / P4-D11）。
     *
     * <p>⚠️ <b>fail-closed</b>：映射缺失 → <b>拒绝启动</b>。
     * 退回默认值等于「悄悄用了一个没人审过的风险映射」。
     */
    @Bean
    public PlatformConfigMaps platformConfigMaps(
            @NonNull Environment environment) {
        Map<String, cn.studykid.multiagent.core.config.ToolRiskLevel> riskMap =
                org.springframework.boot.context.properties.bind.Binder.get(environment)
                        .bind("agent.tool.risk-level-map",
                                org.springframework.boot.context.properties.bind.Bindable
                                        .mapOf(String.class,
                                                cn.studykid.multiagent.core.config.ToolRiskLevel.class))
                        .orElseThrow(() -> new IllegalStateException(
                                "agent.tool.risk-level-map 缺失或非法，拒绝启动（fail-closed）"));
        Map<cn.studykid.multiagent.core.config.ToolRiskLevel,
                cn.studykid.multiagent.core.config.ApprovalRequirement> approvalMap =
                org.springframework.boot.context.properties.bind.Binder.get(environment)
                        .bind("agent.approval.required-by-risk",
                                org.springframework.boot.context.properties.bind.Bindable
                                        .mapOf(cn.studykid.multiagent.core.config.ToolRiskLevel.class,
                                                cn.studykid.multiagent.core.config.ApprovalRequirement.class))
                        .orElseThrow(() -> new IllegalStateException(
                                "agent.approval.required-by-risk 缺失或非法，拒绝启动（fail-closed）"));
        return new PlatformConfigMaps(riskMap, approvalMap);
    }

    /**
     * ⛔ 工具风险级别**一致性守卫**（C8 接线 / P4-D11 D11-3）。
     *
     * <p>⚠️⚠️ <b>为什么必须有这道守卫</b>：
     * 工具的**风险级别由代码决定**（{@code ToolHandler.riskLevel()}）——
     * 一个工具有没有副作用是<b>代码事实</b>，不是运维能「配置」出来的东西。
     * 而 yml 的 {@code agent.tool.risk-level-map} 是同一事实的<b>镜像声明</b>。
     *
     * <p><b>在接线前，这个镜像是完全惰性的</b>：
     * 改 yml 把 {@code record_weakness} 写成 {@code READ}，
     * 工具**仍然**按代码里的 {@code WRITE_PROFILE} 判定 ——
     * 声明与实际分叉，而<b>系统表面完全正常</b>。
     * （与 P4-D08 的 R-1「工具未注册为 Bean」、RAG-D09 的 R-D1「挂接方向反了」同构。）
     *
     * <p>⚠️ <b>更危险的是「看起来能放松」</b>：若运维把
     * {@code record_weakness} 改成 {@code READ}，
     * 按 {@code required-by-risk} 映射它就只需 {@code NOT_REQUIRED} 审批
     * → <b>有副作用的工具绕过审批</b>。虽然当前实现取严（代码权威），
     * 但这依赖「实现恰好更严」这一偶然，<b>不是保证</b>。
     *
     * <p>⚠️ <b>fail-closed</b>：二者不一致 → <b>拒绝启动</b>并打印差异明细。
     * 绝不「以代码为准、忽略 yml」——那等于让声明继续惰性，
     * 管理员仍然会以为改了有用。
     *
     * @param handlers 已注册的工具（{@code List<ToolHandler>}）
     * @param declared yml 声明的 工具→风险级别 映射
     */
    @Bean
    public ToolRiskLevelConsistencyGuard toolRiskLevelConsistencyGuard(
            @NonNull List<cn.studykid.multiagent.ports.tool.ToolHandler> handlers,
            @NonNull PlatformConfigMaps configMaps) {
        return new ToolRiskLevelConsistencyGuard(handlers, configMaps.toolRiskLevelMap());
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

    // =====================================================================
    // 统一配置与权限管控（W-PERM-P0 · 地基）
    //
    // ⚠️ 本段只注册**端口实现**，不含任何授权语义。
    //    Phase A 校验（P1）、执行准入（P2）、审批双哈希（P3）、MCP 传输（P4）、
    //    发布 CAS（P5）均属后续阶段；P0 只交付「可持久化 + 可回退」的底座。
    // =====================================================================

    /**
     * 迁移双写开关（P0-T6）。
     *
     * <p>⚠️ <b>默认 false</b>：双写会增加旧路径的写放大与延迟，
     * 未经验证的灰度不应直接作用于生产写路径。
     * 回退路径：运行时置 false 即让旧读/写路径完全接管，且不删除任何已写数据。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.CapDualWriteSwitch capDualWriteSwitch(
            @Value("${" + cn.studykid.multiagent.application.cap.CapDualWriteSwitch.KEY
                    + ":false}") boolean enabled) {
        return new cn.studykid.multiagent.application.cap.CapDualWriteSwitch(enabled);
    }

    /** Agent 结构化定义仓储（P0-T4/T6）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapAgentDefPort capAgentDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapAgentDefRepository(
                dataSource);
    }

    /**
     * 父子 Agent 关系投影仓储（多级 Agent v1.1 · 06 变更补充「关系数据」）。
     *
     * <p>⚠️ <b>投影不是权威来源</b>：权威定义是不可变 Agent release 里的
     * {@code childAgentRefs}；本表只是把「边」投影出来便于查询与分析，可重建。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort capAgentChildRefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapAgentChildRefRepository(dataSource);
    }

    /**
     * 输出契约仓储（多级 Agent v1.1 · FR-027 · MA-T16）。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapResultContractPort capResultContractPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapResultContractRepository(dataSource);
    }

    /**
     * 路由策略仓储（多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort capRoutingPolicyPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapRoutingPolicyRepository(dataSource);
    }

    /**
     * 委派调用记录仓储（多级 Agent v1.1 · FR-022 · MA-T12）。
     *
     * <p>⚠️ <b>本 Bean 缺失会让平台起不来</b>：
     * {@code delegationExecutor} 与 {@code inFlightDelegationAdopter}
     * 都注入 {@code AgentInvocationPort}，却没有生产者 →
     * Spring 上下文加载失败（{@code NoSuchBeanDefinitionException}）。
     * <p>⚠️ 该缺口此前被「bootstrap 的集成测试从未真实运行」掩盖 ——
     * 单测不加载完整 Spring 上下文，看不到缺 Bean；
     * 接入真实 PG 跑 IT 后立刻暴露。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.AgentInvocationPort agentInvocationPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcAgentInvocationRepository(dataSource);
    }

    /**
     * 多级 Agent 发布期闭包校验器（FR-023 · MA-T02～T04）。
     *
     * <p>⚠️ 装配它意味着「声明了子 Agent 引用但引用不合法」在<b>发布期</b>就被拒，
     * 而不是留到运行期让新 Run 集体失败。
     *
     * <p>⚠️ <b>契约仓储必须一并装配</b>：声明了 {@code resultContractRef} 的 Agent
     * 若在发布期不校验契约，运行期每次委派都会因「取不到契约」fail-closed ——
     * 配置看起来生效了，实际委派全废。
     *
     * <p>⚠️ <b>路由策略仓储同样必须一并装配</b>（第十四批）：声明了
     * {@code routingPolicyRef} 的 ROUTER Agent 若在发布期不校验策略，
     * 运行期每次委派都会因「取不到策略」fail-closed ——
     * 而此前该引用根本就是<b>悬空</b>的（全仓无人持有被引用的对象）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.AgentPublishClosureValidator
            agentPublishClosureValidator(
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.CapResultContractPort contracts,
            cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort routingPolicies) {
        return new cn.studykid.multiagent.application.cap.AgentPublishClosureValidator(
                agentDefs, contracts, routingPolicies);
    }

    /**
     * 迁移双写服务（P0-T6）。
     *
     * <p>⚠️ <b>fail-soft</b>：双写失败<b>不</b>让旧路径写入失败。
     * 旧路径是当前生产真源，让它因「顺带写一份新表」而失败，
     * 等于用迁移期的附加动作打断正在服务的业务。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapDualWritePort capDualWritePort(
            cn.studykid.multiagent.application.cap.CapDualWriteSwitch dualWriteSwitch,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.application.cap.PermissionResolver resolver,
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            Clock clock) {
        return new cn.studykid.multiagent.application.cap.CapDualWriteService(
                dualWriteSwitch, agentDefs, resolver, authzVersions, snapshots, clock);
    }

    /**
     * Agent 管理端口（**双写装饰器**）。
     *
     * <p>⚠️ <b>刻意不在 JDBC 仓储里直接写双写</b>：装饰器让「旧模型写入」与
     * 「新模型双写」在同一个应用服务边界上可见，
     * 且不改动既有仓储的事务与审计时序（该仓储的审计在提交<b>之后</b>落库，
     * 是既有刻意设计，不能被顺带改掉）。
     *
     * <p>⚠️ <b>本类不再有 {@code @Repository} 组件扫描</b>：
     * 否则上下文里会出现两个 {@code AgentManagePort} 候选 Bean → 启动失败。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.AgentManagePort agentManagePort(
            DataSource dataSource,
            cn.studykid.multiagent.ports.persistence.AuditPort auditPort,
            cn.studykid.multiagent.ports.persistence.CapDualWritePort capDualWrite) {
        return new cn.studykid.multiagent.application.cap.DualWriteAgentManagePort(
                new cn.studykid.multiagent.infrastructure.persistence.JdbcAgentManageRepository(
                        dataSource, auditPort),
                capDualWrite);
    }

    /**
     * 授权版本仓储（P0-T3）。
     *
     * <p>⚠️ <b>DB 支撑的强一致版本源</b>：多实例部署下，
     * 内存版 {@code RoleBasedAuthorizationService} 无法保证「撤权即时全实例失效」。
     * P1 起，授权判定必须读本端口。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersionPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcAuthzVersionRepository(
                dataSource);
    }

    /** Run 级能力闭包快照仓储（P0-T4）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort
            capabilitySnapshotPort(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapabilitySnapshotRepository(dataSource);
    }

    /** 配置发布头仓储（P0-T4；CAS 属 P5）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigReleasePort configReleasePort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcConfigReleaseRepository(
                dataSource);
    }

    /** 确定结果事件仓储（P0-T5）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ResultEventPort resultEventPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcResultEventRepository(
                dataSource);
    }

    /** 执行意图仓储（P0-T5）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ExecutionIntentPort executionIntentPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcExecutionIntentRepository(dataSource);
    }

    /** CAP 审批仓储（P3-T3 · §5.3.1 / §5.3.3）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapApprovalPort capApprovalPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapApprovalRepository(
                dataSource);
    }

    /** 补偿任务仓储（P3-T4 · §5.3.5）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CompensationTaskPort compensationTaskPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCompensationTaskRepository(dataSource);
    }

    /** 双写一致性比对工具（P0-T7）。 */
    @Bean
    public cn.studykid.multiagent.infrastructure.persistence.cap.CapDualWriteComparator
            capDualWriteComparator(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.CapDualWriteComparator(
                dataSource);
    }

    // ── W-PERM-P1：解析校验 ─────────────────────────────────────────

    /** Tool 定义版本行仓储（P1-T1）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapToolDefPort capToolDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapToolDefRepository(
                dataSource);
    }

    /** Skill 定义版本行仓储（P1-T1）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapSkillDefPort capSkillDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapSkillDefRepository(
                dataSource);
    }

    /** MCP Server 定义版本行仓储（P1-T1）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort capMcpServerDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapMcpServerDefRepository(dataSource);
    }

    /**
     * 发布版本解析（P5 替换为「指针指向的 PUBLISHED release」读）。
     *
     * <p>⚠️ <b>P1 的过渡实现已被整体替换</b>：
     * 原实现退化为「对象级解析」（取该对象最新已发布版本行），
     * 并在「存在 ROLLED_BACK 发布」时一律解析失败 ——
     * 因为没有「当前指针」这一实体，回滚后无从判断「当前该用哪个版本」。
     * 现在的口径：<b>平台指针 → 指针指向的 PUBLISHED release → object_versions → id→精确版本</b>，
     * <b>绝不以 max(seq) 选择当前版本</b>（§6.3 第 2/5 条，PUB-2）。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.PublishedVersionPort publishedVersionPort(
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            cn.studykid.multiagent.ports.persistence.ConfigReleasePort releases) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcPublishedVersionPort(
                pointers, releases);
    }

    // ── W-PERM-P5：发布审计与生命周期 ───────────────────────────────

    /**
     * 当前发布指针仓储（P5-T1 · §6.3 第 3/5 条）。
     *
     * <p>⚠️ <b>PLATFORM 指针的写入受 RLS 限制</b>：
     * {@code cap_config_pointer} 的写策略只放行「本租户的 TENANT 指针」
     * （与 {@code cap_config_release} 同一处置，见 V15 注释）。
     * 平台级发布须由<b>平台运维角色</b>（BYPASSRLS）执行，
     * 这是刻意的隔离：任一租户都能改写全局当前指针 = 任一租户单方面替换全平台生效配置。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigPointerPort configPointerPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcConfigPointerRepository(
                dataSource);
    }

    /** 发布事务边界（P5-T1 · §6.3 第 1 条：版本行 + release + 指针 CAS 同事务）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction
            configPublishTransaction(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcConfigPublishTransaction(dataSource);
    }

    /** 四字段决策日志仓储（P5-T6 · §5.1 / 08 §7）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.DecisionAuditPort decisionAuditPort(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.alerts.webhook-url:}") String alertWebhookUrl) {
        // ⚠️ 告警外发默认关闭（空白 url → Noop）：不配置目标就不外发，
        //    避免「悄悄把审计内容发给一个没人审过的地址」。
        cn.studykid.multiagent.ports.monitoring.AlertWebhookPort webhook =
                (alertWebhookUrl == null || alertWebhookUrl.isBlank())
                        ? new cn.studykid.multiagent.infrastructure.monitoring.NoopAlertWebhook()
                        : new cn.studykid.multiagent.infrastructure.monitoring.HttpAlertWebhook(
                                alertWebhookUrl);
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcDecisionAuditRepository(dataSource, webhook);
    }

    /** 生命周期状态迁移仓储（P5-T4 · §6.6 / 06 §3）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort configLifecyclePort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcConfigLifecycleRepository(dataSource);
    }

    /** 生命周期状态机（P5-T4）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine
            configLifecycleStateMachine() {
        return new cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine();
    }

    /** 生命周期迁移服务（P5-T4 · 状态机 + CAS 端口的合体）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigLifecycleService configLifecycleService(
            cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort port,
            cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine machine) {
        return new cn.studykid.multiagent.application.cap.ConfigLifecycleService(port, machine);
    }

    /** 配置期校验门禁（P5-T5 · §6.4 / 06 §3）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigValidationGate configValidationGate() {
        return new cn.studykid.multiagent.application.cap.ConfigValidationGate();
    }

    /** 四字段决策日志服务（P5-T6/T7）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.DecisionAuditService decisionAuditService(
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort port,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.DecisionAuditService(port, clock);
    }

    /**
     * 委派守卫（多级 Agent v1.1 · FR-024 / FR-025）。
     *
     * <p>⚠️ <b>预算探针口径是「租户级余额」，不是 Run 级预算</b>：
     * 现行预算模型（{@code budget_account}）只有「租户 + 币种」维度，
     * 没有「单个 Run 可花多少」的独立口径。
     * 因此它保证「租户整体还有余额」，<b>不</b>保证「这一棵调用树没超预算」——
     * Run 级预算需新增维度，属于待办。
     * 嵌套 Agent 无法重置额度的最终保证仍在<b>工具级预算预留</b>：
     * 同一 {@code runId} 的所有预留汇入同一账户，嵌套层无法另起一份额度。
     */
    @Bean
    public cn.studykid.multiagent.application.orchestration.DelegationGuard delegationGuard(
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.security.AuthorizationService authorization,
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.persistence.BudgetPort budget,
            cn.studykid.multiagent.application.orchestration.RunBudgetProbe runBudgetProbe) {
        return new cn.studykid.multiagent.application.orchestration.DelegationGuard(
                agentDefs, authorization, authzVersions,
                new cn.studykid.multiagent.application.orchestration.BudgetBalanceProbe(
                        budget, "CNY", runBudgetProbe));
    }

    /**
     * 在途委派接管器（多级 Agent v1.1 · MA-T12 · P3-MA-02）。
     *
     * <p>⚠️ <b>它补的是接管场景的实质缺口</b>：Worker 崩溃后由新世代接管，
     * 但在途委派记录仍带旧世代 token —— 新世代写不了终态、旧世代也写不了，
     * 那条记录会<b>永远悬在 RUNNING</b>，真实原因被掩盖成普通子调用失败。
     */
    @Bean
    public cn.studykid.multiagent.application.orchestration.InFlightDelegationAdopter
            inFlightDelegationAdopter(
            cn.studykid.multiagent.ports.persistence.AgentInvocationPort invocations,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.orchestration.InFlightDelegationAdopter(
                invocations, clock);
    }

    /**
     * Run 级预算端口（多级 Agent v1.1 · FR-024 · MA-T17）。
     *
     * <p>⚠️ <b>「全调用树共享预算上限」的读口径</b>：按 {@code run_id} 聚合
     * {@code budget_reservation} —— 所有深度的工具调用都记在同一个 Run 下，
     * 嵌套 Agent 无法通过「自己再开一份额度」绕过上限。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RunBudgetPort runBudgetPort(
            javax.sql.DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRunBudgetRepository(
                dataSource);
    }

    /**
     * Run 级预算探针（多级 Agent v1.1 · FR-024 · MA-T17）。
     */
    @Bean
    public cn.studykid.multiagent.application.orchestration.RunBudgetProbe runBudgetProbe(
            cn.studykid.multiagent.ports.persistence.RunBudgetPort runBudgetPort) {
        return new cn.studykid.multiagent.application.orchestration.RunBudgetProbe(runBudgetPort);
    }

    /**
     * 委派结果契约提供端口（多级 Agent v1.1 · P2-MA-04 · FR-027）。
     *
     * <p>⚠️ <b>只认已发布的固定版本</b>：引用 {@code id@version} 解析不到
     * 已发布契约时返回 {@code null}，调用方按 fail-closed 拒绝这次委派
     * （{@code CHILD_RESULT_INVALID}）——「契约写了却无法校验」等于契约没生效。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.ResultContractProvider resultContractProvider(
            cn.studykid.multiagent.ports.persistence.CapResultContractPort contracts) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcResultContractProvider(contracts);
    }

    /**
     * 委派执行器（多级 Agent v1.1 · P2-MA-01）。
     *
     * <p>⚠️ <b>子执行端口必须显式指定 {@code childAgentRunner}</b>：
     * 容器里有两个 {@code ChildAgentRunner} 候选 ——
     * 懒解析的端口 Bean（{@code childAgentRunner}）与它的真实实现 Bean
     * （{@code workerChildAgentRunner}，其返回类型是子类型，同样按类型匹配）。
     * 不加限定符则 Spring 无法决定注入哪一个 →
     * {@code NoUniqueBeanDefinitionException} → <b>平台起不来</b>。
     * 且必须选<b>懒解析</b>的那个：直接注入真实实现会与闸门形成构造期环。
     */
    @Bean
    public cn.studykid.multiagent.application.orchestration.DelegationExecutor delegationExecutor(
            cn.studykid.multiagent.application.orchestration.DelegationGuard guard,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.persistence.AgentInvocationPort invocations,
            @org.springframework.beans.factory.annotation.Qualifier("childAgentRunner")
            cn.studykid.multiagent.ports.orchestration.ChildAgentRunner childRunner,
            cn.studykid.multiagent.ports.orchestration.ResultContractProvider contracts,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.orchestration.DelegationExecutor(
                guard, agentDefs, closureResolver, snapshots, authzVersions, invocations,
                childRunner, contracts, clock);
    }

    /**
     * 原子发布与回滚服务（P5-T1/T2/T3 · §6.3）。
     *
     * <p>⚠️ <b>CAP 链路的生产开启前置条件已在本阶段解除</b>：
     * P2/P3 交付说明登记的「P5 发布指针读落地前不得生产开启 CAP」，
     * 其技术前提（{@code JdbcPublishedVersionPort} 的 §6.3 偏差）已由本次替换消除。
     * 剩余的运营前置（平台运维角色的 DB 凭据接入）见 P5 交付说明。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigPublishService configPublishService(
            @org.springframework.beans.factory.annotation.Qualifier("configPublishTransaction")
            cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction tx,
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            cn.studykid.multiagent.ports.persistence.ConfigReleasePort releases,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort audit,
            cn.studykid.multiagent.application.cap.ConfigValidationGate gate,
            cn.studykid.multiagent.application.cap.AgentPublishClosureValidator closureValidator,
            cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort childRefs,
            cn.studykid.multiagent.ports.persistence.CapResultContractPort contracts,
            cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort routingPolicies,
            java.time.Clock clock) {
        // ⚠️ 输出契约仓储必须注入：未装配时「发布命令带契约」会被拒（fail-closed），
        //    绝不静默跳过 —— 那等于「发布成功但契约没落库」。
        // ⚠️ 路由策略仓储同理（第十四批）：未装配时「发布命令带策略」会被拒。
        return new cn.studykid.multiagent.application.cap.ConfigPublishService(
                tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, cn.studykid.multiagent.application.cap.ConfigPublishService.DEFAULT_MAX_ATTEMPTS,
                closureValidator, childRefs, contracts, routingPolicies);
    }

    /**
     * 权威配置变更的三步工作流（P6R4 · 管理端点）。
     *
     * <p>⚠️ <b>注入的是应用角色通道的 {@code configPublishService}</b>：
     * 本工作流只服务**租户级**对象（Agent 定义），走应用角色连接 ——
     * 写入本租户行由 RLS 强制，写 PLATFORM 行会被 DB 层拒写。
     *
     * <p>⚠️ <b>不注入 {@code platformOpsPublishService}</b>：
     * 平台级发布不经租户身份暴露（P6R4 的作用域闸门），
     * 运维通道由带外运维工具驱动，不接在租户可达的 HTTP 路径上。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService
            configPublishWorkflowService(
            cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort requests,
            cn.studykid.multiagent.application.cap.ConfigPublishService publisher,
            cn.studykid.multiagent.application.cap.ConfigLifecycleService lifecycle,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            cn.studykid.multiagent.ports.persistence.ConfigReleasePort releases,
            cn.studykid.multiagent.ports.persistence.CapResultContractPort contracts,
            cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort routingPolicies,
            java.time.Clock clock) {
        // ⚠️ 输出契约仓储必须注入：未装配时「提交带契约的发布申请」会被拒，
        //    免得申请单看起来带了契约、执行时却被静默丢掉。
        // ⚠️ 路由策略仓储同理（第十四批）。
        return new cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService(
                requests, publisher, lifecycle, agentDefs, pointers, releases, contracts,
                routingPolicies, clock);
    }

    /**
     * 平台运维角色的发布事务（P6R1 · 上线阻塞项的接入点）。
     *
     * <p>⚠️ <b>刻意与 {@link #configPublishTransaction} 分开</b>：
     * 前者用应用角色连接（PLATFORM 作用域写入会被 RLS 拒写 = 刻意的隔离），
     * 本者用平台运维角色连接（BYPASSRLS，是唯一能写 PLATFORM 行的通道）。
     *
     * <p>⚠️ <b>不得合并为「按 scope 自动选池」</b>：
     * 那会让「调用方把 scope 写成 PLATFORM」等价于「拿到运维凭据」，
     * 把 DB 层的越权防线降级成应用层的一行判断。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction
            platformOpsPublishTransaction(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcPlatformOpsConfigPublishTransaction(platformOps);
    }

    /**
     * 平台运维角色的发布入口（P6R1）。
     *
     * <p>⚠️ <b>本 Bean 是运维凭据的唯一可达路径</b>，且<b>只接受 PLATFORM 作用域</b>。
     *
     * <p>⚠️ <b>接入前置条件（硬性）</b>：
     * 调用方必须已完成<b>平台运维身份认证</b>。
     * 本服务只负责「用哪条数据库通道写」，不负责「谁有资格发平台级配置」——
     * 当前仓库尚未接入任何管理端点（P6 登记限制 7），
     * 故本 Bean 暂无生产调用方，这是<b>刻意</b>的：
     * 先有凭据通道，再有认证，二者不可颠倒。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.PlatformOpsPublishService
            platformOpsPublishService(
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsPublishTransaction")
            cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction
                    platformOpsPublishTransaction,
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            cn.studykid.multiagent.ports.persistence.ConfigReleasePort releases,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort audit,
            cn.studykid.multiagent.application.cap.ConfigValidationGate gate,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.PlatformOpsPublishService(
                platformOpsPublishTransaction, pointers, releases, toolDefs, skillDefs,
                serverDefs, agentDefs, audit, gate, clock);
    }

    /**
     * MCP 固定清单端口（P1-T6 · P4-T2 落地真实适配）。
     *
     * <p>⚠️ <b>清单「不可用」与「清单为空」必须区分</b>：
     * 前者是运行故障（server 宕机 / SSRF 拦截 / 熔断打开），
     * 后者是配置事实（这个 server 确实没有工具）。
     * 两者在授权语义上都是「无法确认操作仍在清单内」，
     * 但运维处置方式完全不同 —— 靠日志区分，不靠原因码。
     */
    @Bean
    public cn.studykid.multiagent.ports.mcp.McpManifestPort mcpManifestPort(
            cn.studykid.multiagent.ports.mcp.McpTransportPort mcpTransport,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs) {
        return new cn.studykid.multiagent.infrastructure.mcp.TransportMcpManifestPort(
                mcpTransport, serverDefs);
    }

    /**
     * MCP 地址解析端口（P4-T5）。
     *
     * <p>抽成端口是为了让<b>防 rebinding 的时序行为可测</b>：
     * 测试可以精确控制「第一次解析返回 A、第二次解析返回 B」。
     */
    @Bean
    public cn.studykid.multiagent.ports.mcp.McpAddressResolverPort mcpAddressResolverPort() {
        return new cn.studykid.multiagent.infrastructure.mcp.SystemMcpAddressResolver();
    }

    /**
     * MCP 端点安全守卫（P4-T5 · 06 §5 SSRF）。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard mcpEndpointGuard(
            cn.studykid.multiagent.ports.mcp.McpAddressResolverPort resolver) {
        return new cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard(resolver);
    }

    /**
     * MCP 凭据解析端口（P4-T6 · 06 §5 凭据）。
     *
     * <p>⚠️ 只支持 {@code env://} 与 {@code prop://} 两种定位符：
     * 数据库里永远只有定位符，材料在解析那一刻才从外部取，取到即用即弃。
     */
    @Bean
    public cn.studykid.multiagent.ports.mcp.McpCredentialPort mcpCredentialPort(
            org.springframework.core.env.Environment environment) {
        return new cn.studykid.multiagent.infrastructure.mcp.RefMcpCredentialPort(
                name -> {
                    String value = System.getenv(name);
                    return value == null ? java.util.Optional.empty() : java.util.Optional.of(value);
                },
                key -> java.util.Optional.ofNullable(environment.getProperty(key)));
    }

    /**
     * MCP 熔断器（P4-T1 · §6.5 超时/限流/熔断）。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.mcp.McpCircuitBreaker mcpCircuitBreaker() {
        return new cn.studykid.multiagent.infrastructure.mcp.McpCircuitBreaker(
                5, java.time.Duration.ofSeconds(30));
    }

    /**
     * MCP 传输端口（P4-T1 · §6.5）。
     *
     * <p>⚠️ <b>这是唯一的 MCP 外部副作用出口</b>：
     * SSRF 校验、超时、限流、熔断、响应上限、凭据脱敏全在这里集中施加。
     */
    @Bean
    public cn.studykid.multiagent.ports.mcp.McpTransportPort mcpTransportPort(
            cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard guard,
            cn.studykid.multiagent.ports.mcp.McpCredentialPort credentials,
            cn.studykid.multiagent.infrastructure.mcp.McpCircuitBreaker breaker) {
        return new cn.studykid.multiagent.infrastructure.mcp.HttpMcpTransport(
                guard, credentials,
                cn.studykid.multiagent.infrastructure.mcp.McpTransportOptions.defaults(),
                breaker);
    }

    /**
     * MCP-backed 工具解析器（P4 · ADR-011 / 07 §3）。
     *
     * <p>⚠️ MCP 工具<b>不另开执行入口</b>：它们与原生工具共用
     * {@code ToolInvocationGate} 这一条路径。
     */
    @Bean
    public cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlerProvider(
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.PublishedVersionPort publishedVersions,
            cn.studykid.multiagent.ports.mcp.McpTransportPort transport) {
        return new cn.studykid.multiagent.infrastructure.mcp.DbMcpHandlerProvider(
                toolDefs, serverDefs, publishedVersions, transport);
    }

    /** 角色→动作权限端口（P1-T3）。 */
    @Bean
    public cn.studykid.multiagent.ports.security.RolePermissionPort rolePermissionPort() {
        return new cn.studykid.multiagent.infrastructure.security.RolePermissionAdapter();
    }

    /**
     * 无副作用能力闭包解析器（P1-T1）。
     *
     * <p>⚠️ <b>本 Bean 是<b>无缓存</b>的原始解析器</b>，仅作为
     * {@link #closureResolver} 的被装饰对象。
     * 执行链上的所有闭包读取都必须走缓存装饰器
     * （校验器与闸门共享<b>同一个</b>实例，否则两条路径可能读出不同版本的闭包）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.PermissionResolver permissionResolver(
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.PublishedVersionPort publishedVersions,
            cn.studykid.multiagent.ports.mcp.McpManifestPort manifest) {
        return new cn.studykid.multiagent.application.cap.PermissionResolver(
                agentDefs, skillDefs, toolDefs, serverDefs, publishedVersions, manifest);
    }

    /**
     * 能力依赖图谱与影响分析（管理面 M7 · 只读）。
     *
     * <p>⚠️ 与 {@link #permissionResolver} 刻意分开：后者是授权路径
     * （fail-closed、按发布指针），前者是管理面（含草稿、按最高版本行），
     * 二者口径不同，不共享实例也不互相委托。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.DependencyGraphService dependencyGraphService(
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs) {
        return new cn.studykid.multiagent.application.cap.DependencyGraphService(
                agentDefs, toolDefs, skillDefs, serverDefs);
    }

    /**
     * 租户运行监控聚合服务（管理面 P4 运行监控 · 只读）。
     *
     * <p>⚠️ <b>与 {@link #decisionAuditService} 共用同一 {@code DecisionAuditPort}</b>：
     * 监控读的是同一张决策日志表。刻意<b>不新建独立的读端口</b> ——
     * 两个端口读同一张表会让「监控看到的拒绝」与「审计记录的拒绝」出现口径差，
     * 而运维恰恰会用后者去核对前者。
     */
    @Bean
    public cn.studykid.multiagent.application.monitoring.MonitoringViewService
            monitoringViewService(
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort decisionAudit,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.monitoring.MonitoringViewService(
                authzVersions, decisionAudit, clock);
    }

    /**
     * 仪表盘概览聚合服务（管理面 · M1 概览 · GET /api/admin/dashboard）。
     *
     * <p>⚠️ <b>不做 fail-closed</b>：仪表盘是导航入口而非决策依据，各计数源独立 try/catch，
     * 个别源不可用只让该项记 0，不整页 503（与 {@link #monitoringViewService} 的严格 fail-closed 不同）。
     */
    @Bean
    public cn.studykid.multiagent.application.monitoring.DashboardViewService
            dashboardViewService(
            cn.studykid.multiagent.ports.persistence.AdminTenantPort tenantPort,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefPort,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefPort,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort mcpDefPort,
            cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort publishPort,
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort decisionAudit,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.monitoring.DashboardViewService(
                tenantPort, toolDefPort, skillDefPort, mcpDefPort, publishPort, decisionAudit, clock);
    }

    /**
     * 闭包解析缓存装饰器（P2-T9 · §7.3 缓存）。
     *
     * <p>⚠️ <b>必须是单例且被校验器与闸门共享</b>：
     * 两条路径各读各的闭包会在同一请求内出现「校验器比的是快照指纹、
     * 闸门用的是另一份闭包」的不一致，表现为不可复现的误判。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.ClosureResolver closureResolver(
            cn.studykid.multiagent.application.cap.PermissionResolver resolver,
            cn.studykid.multiagent.ports.persistence.PublishedVersionPort publishedVersions,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            cn.studykid.multiagent.ports.mcp.McpManifestPort manifest) {
        return new cn.studykid.multiagent.application.cap.CachingClosureResolver(
                resolver, publishedVersions, toolDefs, agentDefs, manifest);
    }

    /**
     * Phase A 授权校验器（P1-T3 · P2 接线）。
     *
     * <p>⚠️ <b>接线置于开关之后</b>：本 Bean 一直存在，但它<b>只在</b>
     * CAP 授权链路开启时才被执行链调用。
     * P5 的发布指针读落地前，{@code PublishedVersionPort} 的对象级解析与 §6.3
     * 存在已知偏差，故默认不开关。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.InvocationValidator invocationValidator(
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.security.RolePermissionPort rolePermissions,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            cn.studykid.multiagent.ports.mcp.McpManifestPort manifest) {
        return new cn.studykid.multiagent.application.cap.InvocationValidator(
                authzVersions, rolePermissions, closureResolver, snapshots, manifest);
    }

    /**
     * CAP 授权链路迁移开关（P2）。
     *
     * <p>⚠️ <b>默认 false</b>：P5 的发布指针读落地前不得在生产开启
     * （见 {@code CapEnforcementSwitch} 的类注释）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.CapEnforcementSwitch capEnforcementSwitch(
            @Value("${" + cn.studykid.multiagent.application.cap.CapEnforcementSwitch.KEY
                    + ":false}") boolean enabled) {
        return new cn.studykid.multiagent.application.cap.CapEnforcementSwitch(enabled);
    }

    /** 执行准入检查器（P2-T5 · §5.5 准入线性化）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ExecutionAdmissionChecker
            executionAdmissionChecker(
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots) {
        return new cn.studykid.multiagent.application.cap.ExecutionAdmissionChecker(
                authzVersions, closureResolver, snapshots);
    }

    /** 幂等键校验器（P2-T2 · §5.3 步骤 8）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.IdempotencyGuard idempotencyGuard(
            cn.studykid.multiagent.ports.persistence.RunToolLedgerPort ledger,
            cn.studykid.multiagent.ports.persistence.ResultEventPort resultEvents) {
        return new cn.studykid.multiagent.application.cap.IdempotencyGuard(ledger, resultEvents);
    }

    // ── W-PERM-P3：审批与故障恢复 ───────────────────────────────────

    /** 审批绑定服务（P3-T1 · §5.3.1 双哈希）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ApprovalBindingService approvalBindingService() {
        return new cn.studykid.multiagent.application.cap.ApprovalBindingService();
    }

    /** 审批状态机（P3-T3 · §5.3.3）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ApprovalStateMachine approvalStateMachine(
            cn.studykid.multiagent.ports.persistence.CapApprovalPort capApprovalPort,
            cn.studykid.multiagent.application.cap.ApprovalBindingService approvalBindingService) {
        return new cn.studykid.multiagent.application.cap.ApprovalStateMachine(
                capApprovalPort, approvalBindingService);
    }

    /** 补偿框架（P3-T4 · §5.3.5）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.CompensationFramework compensationFramework(
            cn.studykid.multiagent.ports.persistence.CapApprovalPort capApprovalPort,
            cn.studykid.multiagent.ports.persistence.BudgetPort budget,
            cn.studykid.multiagent.ports.persistence.CompensationTaskPort compensationTaskPort) {
        return new cn.studykid.multiagent.application.cap.CompensationFramework(
                capApprovalPort, budget, compensationTaskPort);
    }

    /** 结果事件恢复服务（P3-T6 · §5.3.4 / §7.4 · COMP-6 硬门槛）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ResultRecoveryService resultRecoveryService(
            cn.studykid.multiagent.ports.persistence.ExecutionIntentPort executionIntentPort,
            cn.studykid.multiagent.ports.persistence.ResultEventPort resultEventPort,
            cn.studykid.multiagent.ports.persistence.RunToolLedgerPort runToolLedgerPort,
            cn.studykid.multiagent.ports.persistence.CompensationTaskPort compensationTaskPort,
            cn.studykid.multiagent.ports.persistence.CapApprovalPort capApprovalPort) {
        return new cn.studykid.multiagent.application.cap.ResultRecoveryService(
                executionIntentPort, resultEventPort, runToolLedgerPort,
                compensationTaskPort, capApprovalPort);
    }

    /**
     * CAP 执行准入编排器（P2-T1 · §5.3 唯一协议；P3 接入 CAP 审批链路）。
     *
     * <p>⚠️ 本 Bean <b>始终装配</b>，但只有开关开启时才会被闸门调用。
     * 开关在编排器内部，故不存在「以为走了新链路、其实没走」的静默降级。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.CapExecutionFlow capExecutionFlow(
            cn.studykid.multiagent.application.cap.InvocationValidator validator,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.application.cap.ExecutionAdmissionChecker admission,
            cn.studykid.multiagent.application.cap.IdempotencyGuard idempotency,
            cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersions,
            cn.studykid.multiagent.ports.security.RolePermissionPort rolePermissions,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            cn.studykid.multiagent.ports.security.ApprovalPolicy approvalPolicy,
            cn.studykid.multiagent.application.cap.ApprovalStateMachine approvalStateMachine,
            cn.studykid.multiagent.application.cap.CompensationFramework compensationFramework,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort capToolDefPort,
            cn.studykid.multiagent.ports.persistence.BudgetPort budget,
            cn.studykid.multiagent.ports.persistence.RunToolLedgerPort ledger,
            cn.studykid.multiagent.ports.persistence.ExecutionIntentPort intents,
            cn.studykid.multiagent.ports.persistence.ResultEventPort resultEvents,
            cn.studykid.multiagent.application.cap.CapEnforcementSwitch enforcementSwitch,
            cn.studykid.multiagent.application.cap.DecisionAuditService decisionAuditService,
            cn.studykid.multiagent.application.orchestration.RunBudgetProbe runBudgetProbe) {
        return new cn.studykid.multiagent.application.cap.CapExecutionFlow(
                validator, closureResolver, admission, idempotency, authzVersions, rolePermissions,
                snapshots, approvalPolicy, approvalStateMachine, compensationFramework,
                capToolDefPort, budget, ledger, intents, resultEvents,
                cn.studykid.multiagent.application.config.BudgetPolicy.DEFAULT_RESERVE_AMOUNT,
                cn.studykid.multiagent.application.cap.CapExecutionFlow.DEFAULT_PRINCIPAL_ID,
                enforcementSwitch, decisionAuditService, runBudgetProbe);
    }
}
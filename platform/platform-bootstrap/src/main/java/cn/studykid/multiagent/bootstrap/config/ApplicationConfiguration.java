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
            Clock clock) {
        return new RunCreationService(port, snapshots, tx, clock);
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
}
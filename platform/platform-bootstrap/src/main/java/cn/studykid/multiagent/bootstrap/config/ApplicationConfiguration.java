package cn.studykid.multiagent.bootstrap.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.chat.ConsoleChatController;
import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.api.run.RunEventStreamer;
import cn.studykid.multiagent.api.security.JwtCurrentPrincipal;
import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;
import cn.studykid.multiagent.infrastructure.security.RoleBasedAuthorizationService;
import cn.studykid.multiagent.application.config.RateLimitPolicy;
import cn.studykid.multiagent.infrastructure.persistence.JdbcRateLimiter;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApplicationConfiguration.class);

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
            cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort,
            cn.studykid.multiagent.ports.persistence.CapDualWritePort dualWritePort,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.run.require-timeout:false}")
            boolean requireRunTimeout) {
        // ⚠️ 预算端口必须装配：缺了它，「声明了 Run 级额度」的创建请求会被
        //    fail-closed 拒绝（校验缺席 ≠ 校验通过）。
        // ⚠️ 双写端口必须装配：Run 创建是 execution_capability_snapshot 的
        //    <b>唯一生产写入点</b>（G-3）。缺了它六个 fail-closed 读方一律拒跑。
        // ⚠️ P2-1：require-timeout 让「不限时 Run 是否允许」成为显式环境决策
        //    （默认 false = 沿用 CreateRunRequest 既定语义，生产可按配置收紧）。
        return new RunCreationService(port, snapshots, tx, clock, budgetPort, dualWritePort,
                requireRunTimeout);
    }

    /**
     * 执行快照解析端口（P4-D18）。
     *
     * <p>⚠️ <b>不可加 {@code @Repository}</b>：与 {@code JdbcRateLimiter} 同理，
     * 装配统一归本类，避免被测试切片的组件扫描意外拾取。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort executionSnapshotPort(
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.ExecutionSnapshotMapper>
                    executionSnapshotMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && executionSnapshotMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisExecutionSnapshotRepository(executionSnapshotMapper.getObject(), dataSource);
        }
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
    /**
     * Jdbc 预算仓储：<b>始终注册</b>。
     *
     * <p>⚠️ {@link cn.studykid.multiagent.infrastructure.budget.BudgetReconciliationService}
     * 除 {@link cn.studykid.multiagent.ports.persistence.BudgetPort} 外还需要具体类型的
     * {@code findUnknownHeld} 做对账统计。此前它只在「未切 MyBatis」时被隐式创建，
     * 打开迁移开关后直接缺 Bean —— 这是编译期看不见的装配缺口。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository
            jdbcBudgetRepository(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository(
                dataSource);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort(
            DataSource dataSource,
            cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository
                    jdbcBudgetRepository,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.BudgetMapper>
                    budgetMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && budgetMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisBudgetRepository(budgetMapper.getObject(), dataSource);
        }
        return jdbcBudgetRepository;
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
            cn.studykid.multiagent.ports.persistence.AuditPort auditPort,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.RunClaimMapper>
                    runClaimMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && runClaimMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisRunClaimRepository(runClaimMapper.getObject(), dataSource, auditPort);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRunClaimRepository(
                dataSource, auditPort);
    }

    /**
     * 执行步骤写入端口（P7-2 事实写入方）。
     *
     * <p>⚠️ 沿用 P4-D07 装配惯例：显式 {@code @Bean} 注册（repository 不挂 {@code @Repository}，
     * 组件扫描选不到），否则 {@link RunStepPort} 在生产环境恒为 {@code null}，
     * {@code RunExecutionLoop.appendStep} 静默跳过，P7-2 真实埋点缺口不闭合。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RunStepPort runStepPort(
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.RunStepMapper>
                    runStepMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && runStepMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisRunStepRepository(runStepMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRunStepRepository(
                dataSource);
    }

    /**
     * 安全事件写入端口（P7-4 · SEC-2/SEC-3 明细事实）。
     *
     * <p>⚠️ 沿用 P4-D07 装配惯例：显式 {@code @Bean} 注册，与 {@link #runStepPort} 同理——
     * 不注册则 Worker 执行循环里的发射点静默跳过，{@code agent_risk_event}
     * 永远没有行，SEC-2/SEC-3 看板永远显示「未采集」而<b>没有任何报错</b>。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RiskEventPort riskEventPort(
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.RiskEventMapper>
                    riskEventMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && riskEventMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisRiskEventRepository(riskEventMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRiskEventRepository(
                dataSource);
    }

    /**
     * 对话消息写入端口（P1 · G1 回放底座）。
     *
     * <p>⚠️ 沿用 P4-D07 装配惯例：显式 {@code @Bean} 注册（repository 不挂 {@code @Repository}，
     * 组件扫描选不到），否则 {@code RunExecutionLoop.appendMessage} 静默跳过，
     * {@code run_message} 永远没有行 ——
     * 症状是「回放永远是空的」且<b>没有任何报错</b>，
     * 极易被误判为「学生那次辅导确实没说什么」。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.RunMessagePort runMessagePort(
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.RunMessageMapper>
                    runMessageMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && runMessageMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisRunMessageRepository(runMessageMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcRunMessageRepository(
                dataSource);
    }

    /**
     * 对话回放读取端口（P1 · G1 闭环）。
     *
     * <p>⚠️ 沿用同一装配惯例：不注册则回放端点恒 404/空，且无报错。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.TraceReplayPort traceReplayPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcTraceReplayRepository(
                dataSource);
    }

    /**
     * 人工审查队列端口（P2 · G3「人审」半边）。
     *
     * <p>⚠️ 沿用同一装配惯例（显式 {@code @Bean}）：不注册则告警联动
     * 「静默跳过」，队列永远为空 —— 症状是「审查流程看起来在、但没有一条任务」，
     * 无任何报错。
     */
    /**
     * ⚠️ 显式 {@code @Qualifier(PrimaryDataSourceConfiguration.BEAN_NAME)}：
     * 容器里存在两个 {@code DataSource}（应用主池 + 保留期清理池），
     * 靠参数名匹配太脆弱。审查队列是业务数据，必须由应用主池写入。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ReviewTaskPort reviewTaskPort(
            @Qualifier("dataSource") DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.ports.quality.ReviewAttributionHook> hookProvider,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.ReviewTaskMapper>
                    reviewTaskMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.platform-ops.datasource.enabled:false}") boolean platformOpsEnabled) {
        // review_task 的 statement 只在 mapper/ops/**：运维通道未接入时 Mapper 不会被注册，
        // 必须留在 Jdbc 实现（其数据源替身会 fail-closed），否则启动期缺 Bean。
        if (mybatisEnabled && platformOpsEnabled && reviewTaskMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisReviewTaskRepository(reviewTaskMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcReviewTaskRepository(
                dataSource, hookProvider.getIfAvailable());
    }

    /**
     * 对话回放服务（P1 · G1 闭环）。
     *
     * <p>⚠️ 必须显式注册：它是回放端点的唯一数据入口，
     * 缺失则端点启动即失败（fail-fast），而不是静默返回空回放。
     */
    @Bean
    public cn.studykid.multiagent.application.replay.TraceReplayService traceReplayService(
            cn.studykid.multiagent.ports.persistence.TraceReplayPort replayPort) {
        return new cn.studykid.multiagent.application.replay.TraceReplayService(replayPort);
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
            org.springframework.beans.factory.ObjectProvider<cn.studykid.multiagent.infrastructure.persistence.mapper.RateLimitBucketMapper> rateLimitBucketMapper,
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
                            .KEY_GOVERNANCE_DECIDE_LIMIT + ":}") Long governanceDecideLimit,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.rate-limit.sliding-window:false}") boolean slidingWindow,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        java.util.Map<String, Long> bucketLimits = new java.util.LinkedHashMap<>();
        bucketLimits.put(RateLimitPort.Buckets.GOVERNANCE_QUERY,
                optionalLimit(governanceQueryLimit, RateLimitPort.Buckets.GOVERNANCE_QUERY));
        bucketLimits.put(RateLimitPort.Buckets.GOVERNANCE_DECIDE,
                optionalLimit(governanceDecideLimit, RateLimitPort.Buckets.GOVERNANCE_DECIDE));

        long realLimit = RateLimitPolicy.requireLimit(limit);
        long realWindow = RateLimitPolicy.requireWindowSeconds(windowSeconds);

        if (mybatisEnabled) {
            return new cn.studykid.multiagent.infrastructure.persistence.MybatisRateLimiter(
                    rateLimitBucketMapper.getObject(), dataSource,
                    realLimit, realWindow, bucketLimits, slidingWindow);
        }
        return new JdbcRateLimiter(
                dataSource, realLimit, realWindow, bucketLimits, slidingWindow);
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
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.AuditMapper>
                    auditMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && auditMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisAuditRepository(auditMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository(
                dataSource);
    }

    @Bean
    public cn.studykid.multiagent.ports.persistence.ApprovalPort approvalPort(
            DataSource dataSource,
            cn.studykid.multiagent.ports.persistence.AuditPort auditPort,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.ApprovalMapper>
                    approvalMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && approvalMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisApprovalRepository(approvalMapper.getObject(), dataSource);
        }
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
     * 已注册工具名注册表（P7-2 采集口径）。
     *
     * <p><b>用途</b>：指标里的「未知工具数」要判定「模型请求的工具名是否在
     * 平台已定义的工具全集里」。该全集有两侧：能力目录（{@code cap_tool_def}，
     * 由事实端口读）与<b>代码注册表</b>（本 Bean）。
     *
     * <p><b>⚠️ 为什么必须有这个 Bean</b>：native 工具
     * （{@code get_student_profile} / {@code record_weakness}）的注册名
     * <b>不在 {@code cap_tool_def} 里</b>（目录里是目录 id）。
     * 缺了它，native 工具的每次调用都会被判成「模型幻觉出的工具」→
     * {@code unknown_tool} 虚高 → 无效调用率失真（不报错，只悄悄算错）。
     *
     * <p>⚠️ 只收 <b>进程内注册的 {@link cn.studykid.multiagent.ports.tool.ToolHandler}</b>：
     * HTTP / MCP 工具由 provider <b>按需</b>创建，不是 Bean，
     * 它们的工具名（目录 id）由能力目录侧覆盖。
     */
    @Bean
    public cn.studykid.multiagent.ports.tool.ToolNameRegistry toolNameRegistry(
            @NonNull List<cn.studykid.multiagent.ports.tool.ToolHandler> tools) {
        java.util.Set<String> names = tools.stream()
                .map(cn.studykid.multiagent.ports.tool.ToolHandler::toolName)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("装配工具名注册表: {} 个已注册工具 {}", names.size(), names);
        return () -> names;
    }

    // 跨 Run 记忆的装配已迁入 agent-memory 模块（MemoryConfiguration）。
    // 组合根不再持有任何记忆类型的全限定名；Bean 由组件扫描自动发现。

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
                    cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository repo,
                    cn.studykid.multiagent.application.orchestration
                            .DelegationBudgetReconciliationService delegationReconciliation) {
        return new cn.studykid.multiagent.infrastructure.budget.BudgetReconciliationService(
                budgetPort, repo, delegationReconciliation);
    }

    /**
     * 委派预算预留的反查端口（第二十二批 · C22-1 · A2）。
     *
     * <p>⚠️ <b>为什么必须单独装配</b>：委派的预留记在 {@code agent_invocation} 里，
     * <b>没有</b>对应的 {@code tool_operation} 行 —— 工具侧的对账查询
     * （{@code findOrphaned} / {@code findUnknownHeld}）用 INNER JOIN，
     * <b>看不见委派的预留</b>。不装配这个端口 = 委派侧的额度泄漏永远不被发现。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.DelegationBudgetPort
            delegationBudgetPort(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcDelegationBudgetRepository(dataSource);
    }

    /**
     * 委派预算对账服务（第二十二批 · C22-1 · A2）。
     */
    @Bean
    public cn.studykid.multiagent.application.orchestration.DelegationBudgetReconciliationService
            delegationBudgetReconciliationService(
                    cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort,
                    cn.studykid.multiagent.ports.persistence.DelegationBudgetPort
                            delegationBudgetPort) {
        return new cn.studykid.multiagent.application.orchestration
                .DelegationBudgetReconciliationService(budgetPort, delegationBudgetPort);
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
     * 控制台「直接对话」的调用方标识（限流分桶键）。
     *
     * <p><b>为什么不能复用 {@link #currentPrincipal}</b>：{@code /api/chat/**} 已归入
     * 平台安全链，验签后写入的是 {@link PlatformPrincipal}（刻意无 tenantId），
     * 而 {@code currentPrincipal} 要求 {@link TenantContext}，取不到就会回落去解析
     * 租户令牌 → 401。本端点不读写租户数据，只需一个限流分桶 / 日志定位键。
     *
     * <p>⚠️ <b>返回值必须是合法 UUID 字符串</b>：{@code JdbcRateLimiter} 把它当作
     * {@code rate_limit_bucket.tenant_id}（{@code ?::uuid}）并在事务里绑定为 RLS 上下文。
     * 直接塞 {@code "platform:ops-1"} 会触发 {@code invalid input syntax for type uuid}，
     * 被限流器 fail-closed 判成 429 —— 症状从「跳登录页」变成「一发送就 429」。
     * 平台主体不属任何租户，故用 {@code nameUUIDFromBytes} 从 userId 派生一个
     * <b>确定性 v3 UUID</b> 作为平台域计数命名空间：与真实租户 UUID（v4）天然不碰撞，
     * 也不会把管理员的对话流量算进任何租户的配额桶。
     *
     * <p>⚠️ <b>身份只从已验签的请求属性取</b>，两者都没有时 fail-closed 抛 401，
     * 绝不编造默认身份，也不接受请求体/请求头自称的身份。
     */
    @Bean
    public ConsoleChatController.ChatCaller chatCaller() {
        return () -> {
            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attrs) {
                var request = attrs.getRequest();
                Object platform = request.getAttribute(
                        SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE);
                if (platform instanceof PlatformPrincipal p) {
                    return UUID.nameUUIDFromBytes(
                            ("platform-chat:" + p.userId())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .toString();
                }
                Object tenant = request.getAttribute(
                        SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE);
                if (tenant instanceof TenantContext ctx) {
                    return ctx.tenantId();
                }
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        };
    }

    /**
     * 平台主体在合成 {@link TenantContext} 中使用的哨兵租户。
     *
     * <p>仅服务于「模型配置」等平台级端点的 {@code PLATFORM_ADMIN} 角色闸门——
     * 这些端点的底层表无租户无 RLS，仓储从不按 {@code tenantId} 访问数据，
     * 故合成上下文的 {@code tenantId} 仅为占位、绝不被用于数据隔离。
     */
    private static final String PLATFORM_SENTINEL_TENANT = "00000000-0000-0000-0000-000000000000";

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
                    // ⚠️ 平台身份域回退（2026-11-07 修复，与 CONSOLE_CHAT_PREFIX 同源）：
                    //    /api/admin/models 与 /api/admin/model-credentials 已归入平台安全链，
                    //    验签后写入的是 PlatformPrincipal（刻意无 tenantId）。这两个端点是
                    //    平台级、跨租户共享的模型配置资源（表上无租户无 RLS），其仓储不依赖
                    //    tenantId，故把平台主体收敛成仅用于 PLATFORM_ADMIN 角色闸门的 TenantContext。
                    //    ⚠️ 仅当 TENANT_CONTEXT_ATTRIBUTE 缺失（请求落在平台链）时触发；
                    //       租户链的端点仍拿到真实 TenantContext，不受影响。
                    //    ⚠️ 此合成上下文<b>绝不可</b>用于任何按 tenantId 的数据访问——
                    //       那会把平台身份伪装成某个租户，正是 ConsoleChatController 明确反对的。
                    Object platform = attrs.getRequest().getAttribute(
                            SecurityConfiguration.PLATFORM_PRINCIPAL_ATTRIBUTE);
                    if (platform instanceof PlatformPrincipal pp) {
                        // ⚠️ 桥接：平台治理角色 → 租户侧 PLATFORM_ADMIN 闸门。
                        //    平台账号（plat-superadmin / plat-resource 等）实际持有的是
                        //    PLATFORM_SUPER_ADMIN / PLATFORM_RESOURCE_ADMIN 等<b>平台侧</b>角色，
                        //    而模型配置等平台级端点用租户侧
                        //    {@code AuthorizationService.requireAuthorized(PLATFORM_ADMIN)} 做闸门，
                        //    且<b>没有任何平台账号被授予租户侧 PLATFORM_ADMIN</b>
                        //    （knownRoles 白名单与种子脚本均无此角色）—— 若不桥接，
                        //    平台治理者访问模型配置会 403（「无权执行该操作」），
                        //    前端仍显示「密钥列表加载失败」，与最初的 401 跨域是同一页面的两种失败态。
                        //    这里仅把「平台资源治理者」（超级管理员 + 资源管理员）映射到 PLATFORM_ADMIN，
                        //    与 {@code PlatformRolePermissionMatrix} 对齐：模型目录属 RESOURCE_ADMIN 职责、
                        //    超级管理员持全部平台权限；安全管理员/审计员/支持人员不授予，避免扩权。
                        //    ⚠️ 合成上下文的 tenantId 仅为占位（哨兵值），绝不被用于按 tenantId 的数据访问。
                        java.util.Set<String> bridgedRoles =
                                new java.util.LinkedHashSet<>(pp.roles());
                        if (bridgedRoles.contains(PlatformRoles.SUPER_ADMIN)
                                || bridgedRoles.contains(PlatformRoles.RESOURCE_ADMIN)) {
                            bridgedRoles.add(AuthorizationService.Roles.PLATFORM_ADMIN);
                        }
                        return new TenantContext(
                                PLATFORM_SENTINEL_TENANT, pp.userId(), bridgedRoles, pp.authzVersion());
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
                    // 退化到入口链路号：落库的 correlation_id 与日志里的 traceId
                    // 是同一个值，排障时可以拿其中一个直接查到另一侧。
                    Object trace = attrs.getRequest()
                            .getAttribute(RequestLoggingConfiguration.TRACE_ID_ATTRIBUTE);
                    if (trace != null) {
                        return String.valueOf(trace);
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
     * <p>⚠️ <b>默认 true</b>：写出的 {@code execution_capability_snapshot} 是执行准入的
     * <b>前置条件</b>（{@code RootAgentModelConfigProvider} 靠它确定根 Agent 是谁），
     * 默认关等于默认跑不了任何 Run。回退仍可用：置 false 即让旧读/写路径完全接管，
     * 且不删除任何已写数据 —— 但要知道回退同时关闭了执行准入。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.CapDualWriteSwitch capDualWriteSwitch(
            @Value("${" + cn.studykid.multiagent.application.cap.CapDualWriteSwitch.KEY
                    + ":true}") boolean enabled) {
        return new cn.studykid.multiagent.application.cap.CapDualWriteSwitch(enabled);
    }

    /** Agent 结构化定义仓储（P0-T4/T6）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapAgentDefPort capAgentDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapAgentDefRepository(
                dataSource);
    }

    /**
     * Agent 发布归档仓储（2026-11-09 · 权威源定 A）。
     *
     * <p>⚠️ <b>归档不是真源</b>：真源是 {@code cap_agent_def}。
     * 本仓储把真源<b>派生</b>归档到旧表 {@code agent_version} +
     * {@code execution_snapshot}，唯一理由是维持
     * {@code run → execution_snapshot → agent_version} 的外键链 ——
     * 没有归档行，{@code run.snapshot_id} 外键违约，<b>建 Run 直接失败</b>。
     *
     * <p>⚠️ <b>必须装配</b>：未装配时治理通道发布会 fail-closed 被拒
     * （而不是「发布成功但永远建不出 Run」）。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort
            agentReleaseArchivePort(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcAgentReleaseArchiveRepository(dataSource);
    }

    /**
     * 父子 Agent 关系投影仓储（多级 Agent v1.1 · 06 变更补充「关系数据」）。
     *
     * <p>⚠️ <b>投影不是权威来源</b>：权威定义是不可变 Agent release 里的
     * {@code childAgentRefs}；本表只是把「边」投影出来便于查询与分析，可重建。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort capAgentChildRefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapAgentChildRefRepository(dataSource);
    }

    /**
     * 输出契约仓储（多级 Agent v1.1 · FR-027 · MA-T16）。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapResultContractPort capResultContractPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapResultContractRepository(dataSource);
    }

    /**
     * 路由策略仓储（多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort configPointers,
            Clock clock) {
        return new cn.studykid.multiagent.application.cap.CapDualWriteService(
                dualWriteSwitch, agentDefs, resolver, authzVersions, snapshots, clock,
                configPointers);
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
            cn.studykid.multiagent.ports.persistence.CapDualWritePort capDualWrite,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.AgentManageMapper>
                    agentManageMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        // ⚠️ 双写装饰器必须保留：切换底层实现只换 delegate，不拆装饰器。
        cn.studykid.multiagent.ports.persistence.AgentManagePort delegate = mybatisEnabled
                ? new cn.studykid.multiagent.infrastructure.persistence
                        .MybatisAgentManageRepository(agentManageMapper.getObject(),
                                dataSource, auditPort)
                : new cn.studykid.multiagent.infrastructure.persistence
                        .JdbcAgentManageRepository(dataSource, auditPort);
        return new cn.studykid.multiagent.application.cap.DualWriteAgentManagePort(
                delegate, capDualWrite);
    }

    /**
     * 授权版本仓储（P0-T3）。
     *
     * <p>⚠️ <b>DB 支撑的强一致版本源</b>：多实例部署下，
     * 内存版 {@code RoleBasedAuthorizationService} 无法保证「撤权即时全实例失效」。
     * P1 起，授权判定必须读本端口。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.AuthzVersionPort authzVersionPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcAuthzVersionRepository(
                dataSource);
    }

    /** Run 级能力闭包快照仓储（P0-T4）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort
            capabilitySnapshotPort(DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcCapabilitySnapshotRepository(dataSource);
    }

    /** 配置发布头仓储（P0-T4；CAS 属 P5）。 */
    @Bean
    @org.springframework.context.annotation.Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.ConfigReleasePort configReleasePort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcConfigReleaseRepository(
                dataSource);
    }

    /** 确定结果事件仓储（P0-T5）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.ResultEventPort resultEventPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcResultEventRepository(
                dataSource);
    }

    /** 执行意图仓储（P0-T5）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.ExecutionIntentPort executionIntentPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcExecutionIntentRepository(dataSource);
    }

    /** CAP 审批仓储（P3-T3 · §5.3.1 / §5.3.3）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.CapApprovalPort capApprovalPort(
            DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.CapApprovalMapper>
                    capApprovalMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && capApprovalMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisCapApprovalRepository(capApprovalMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapApprovalRepository(
                dataSource);
    }

    /** 补偿任务仓储（P3-T4 · §5.3.5）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapToolDefPort capToolDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapToolDefRepository(
                dataSource);
    }

    /** Skill 定义版本行仓储（P1-T1）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.CapSkillDefPort capSkillDefPort(
            DataSource dataSource) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapSkillDefRepository(
                dataSource);
    }

    /** MCP Server 定义版本行仓储（P1-T1）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
    @org.springframework.context.annotation.Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
    @org.springframework.context.annotation.Primary
    public cn.studykid.multiagent.ports.persistence.DecisionAuditPort decisionAuditPort(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.alerts.webhook-url:}") String alertWebhookUrl,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.DecisionAuditMapper>
                    decisionAuditMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        // ⚠️ 告警外发默认关闭（空白 url → Noop）：不配置目标就不外发，
        //    避免「悄悄把审计内容发给一个没人审过的地址」。
        cn.studykid.multiagent.ports.monitoring.AlertWebhookPort webhook =
                (alertWebhookUrl == null || alertWebhookUrl.isBlank())
                        ? new cn.studykid.multiagent.infrastructure.monitoring.NoopAlertWebhook()
                        : new cn.studykid.multiagent.infrastructure.monitoring.HttpAlertWebhook(
                                alertWebhookUrl);
        if (mybatisEnabled && decisionAuditMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisDecisionAuditRepository(decisionAuditMapper.getObject(), dataSource);
        }
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcDecisionAuditRepository(dataSource, webhook);
    }

    /** 生命周期状态迁移仓储（P5-T4 · §6.6 / 06 §3）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
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
            javax.sql.DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.RunBudgetMapper>
                    runBudgetMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        if (mybatisEnabled && runBudgetMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisRunBudgetRepository(runBudgetMapper.getObject(), dataSource);
        }
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
     * 路由策略提供端口（多级 Agent v1.1 第十四批 · MA-T05 / MA-T15）。
     *
     * <p>⚠️ <b>只认已发布的固定版本</b>：引用 {@code id@version} 解析不到
     * 已发布策略时返回 {@code null}，调用方按 fail-closed 拒绝这次委派
     * （{@code ROUTING_POLICY_UNAVAILABLE}）——
     * 「策略写了却无法应用」等于策略没生效。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider routingPolicyProvider(
            cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort policies) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcRoutingPolicyProvider(policies);
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
            cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider routingPolicies,
            java.time.Clock clock) {
        // ⚠️ 路由策略提供端口必须注入（第十四批）：
        //    未装配时 ROUTER 模式的委派一律 fail-closed（ROUTING_POLICY_UNAVAILABLE），
        //    绝不降级成「按父声明的子 Agent 随便选一个」。
        return new cn.studykid.multiagent.application.orchestration.DelegationExecutor(
                guard, agentDefs, closureResolver, snapshots, authzVersions, invocations,
                childRunner, contracts, routingPolicies, clock);
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
    @org.springframework.context.annotation.Primary
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
            @org.springframework.beans.factory.annotation.Qualifier("agentReleaseArchivePort")
            cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort archive,
            java.time.Clock clock) {
        // ⚠️ 输出契约仓储必须注入：未装配时「发布命令带契约」会被拒（fail-closed），
        //    绝不静默跳过 —— 那等于「发布成功但契约没落库」。
        // ⚠️ 路由策略仓储同理（第十四批）：未装配时「发布命令带策略」会被拒。
        // ⚠️ 归档端口必须注入（2026-11-09 · 权威源定 A）：未装配时发布会被拒 ——
        //    归档缺失会让 run.snapshot_id 外键链断裂，
        //    发布出去的 Agent 一个 Run 都建不出来（症状在 Run 创建处，离真因很远）。
        return new cn.studykid.multiagent.application.cap.ConfigPublishService(
                tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, cn.studykid.multiagent.application.cap.ConfigPublishService.DEFAULT_MAX_ATTEMPTS,
                closureValidator, childRefs, contracts, routingPolicies, archive);
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
     * 路由策略 dry-run 用例（10 号设计 §7 债 5 · 管理台试跑面板的后端入口）。
     *
     * <p>⚠️ <b>无仓储依赖</b>：试跑不落库、不占版本号，因此它不需要发布通道的那些端口 ——
     * 也正因为它不碰库，接入面可以放在租户可达的 HTTP 路径上而不触碰运维通道。
     * 时钟仍然注入：回放用的 UTC 日影响引用 {@code ctx.now.utcDate} 的规则，
     * 引擎不读系统时钟（同 {@code RoutingEngine} 的纯函数口径）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.RoutingPolicyDryRunService
            routingPolicyDryRunService(java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.RoutingPolicyDryRunService(clock);
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

    // ── 平台通道（第三十四批 · C34-2）────────────────────────────────
    //
    // ⚠️ **下面是另一条通道，与上面的租户通道彻底分开**：
    //   · 租户通道：应用角色连接，PLATFORM 行会被 RLS 拒写（刻意的隔离）；
    //   · 平台通道：平台运维角色连接（BYPASSRLS），是唯一能写 PLATFORM 行与
    //     PLATFORM 申请单的通道。
    // ⚠️ **不得合并为「按 scope 自动选池」**：那会让「调用方把 scope 写成 PLATFORM」
    //   等价于「拿到运维凭据」，把 DB 层的越权防线降级成应用层的一行判断。
    // ⚠️ 平台通道的端口一律用**受会话守卫**的运维数据源 ——
    //   裸运维数据源一旦被装配到业务读路径上，租户隔离就整体失效。

    /** 平台通道的当前发布指针仓储（运维连接）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigPointerPort
            platformOpsConfigPointerPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcConfigPointerRepository(platformOps.guardedOrUnavailableDataSource());
    }

    /**
     * 平台控制台的跨租户 Agent 注册表（只读 · 第三十九批）。
     *
     * <p>⚠️ 与上面平台通道的端口同一纪律：运维连接 + 会话窗（调用方负责包会话）。
     * 仅管理元数据，绝无业务指令正文（禁区红线）。
     */
    @Bean
    @ConditionalOnProperty(name = "persistence.mybatis.enabled",
             havingValue = "false", matchIfMissing = true)
    public cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort
            platformAgentDirectoryPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcPlatformAgentDirectoryRepository(platformOps.guardedOrUnavailableDataSource());
    }

    /** 平台通道的发布头仓储（运维连接）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigReleasePort
            platformOpsConfigReleasePort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcConfigReleaseRepository(platformOps.guardedOrUnavailableDataSource());
    }

    /** 平台通道的决策日志仓储（运维连接；平台级审计行的 tenant_id 为 NULL）。 */
    @Bean
    public cn.studykid.multiagent.ports.persistence.DecisionAuditPort
            platformOpsDecisionAuditPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcDecisionAuditRepository(platformOps.guardedOrUnavailableDataSource());
    }

    /**
     * 平台租户生命周期仓储（{@code platform.tenant} / tenant_quota / capability_grant）。
     *
     * <p>⚠️ <b>走平台运维角色数据源，不走主数据源</b>（2026-11-01）：
     * app_agent 对 {@code platform.tenant} 只有 SELECT（登记元数据只读），
     * 开通/状态迁移/配额/能力授予的写路径必须由 platform_ops 执行
     * （03_platform_roles.sql §2b）。运维数据源未接入时 fail-closed。
     * ⚠️ 消费方含 {@code RunRecoverySweeper}（30s 轮询读活跃租户），
     * 该轮询将经由运维连接池 —— 池小（默认 2），轮询是毫秒级短查询，可接受。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.PlatformTenantPort
            platformTenantPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformTenantMapper>
                    platformTenantMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled) {
        javax.sql.DataSource platformIdentity = platformOps.platformIdentityDataSource();
        if (mybatisEnabled && platformTenantMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisPlatformTenantRepository(platformTenantMapper.getObject(), platformIdentity);
        }
        return new cn.studykid.multiagent.infrastructure.persistence
                .JdbcPlatformTenantRepository(platformIdentity);
    }

    /**
     * 系统字典仓储（{@code platform.sys_dictionary} / sys_dictionary_item）。
     *
     * <p>⚠️ <b>走平台运维角色数据源，不走主数据源</b>（2026-11-01）：
     * app_agent 对 platform 域字典表无授权（§2b 刻意不给整域），
     * 主数据源实现会在运行期报 permission denied。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.DictionaryPort
            platformDictionaryPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.persistence.mapper.DictionaryMapper>
                    dictionaryMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${persistence.mybatis.enabled:false}") boolean mybatisEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.platform-ops.datasource.enabled:false}") boolean platformOpsEnabled) {
        // sys_dictionary 的 Mapper 绑定在 opsSqlSessionFactory：运维通道未接入时不切换。
        if (mybatisEnabled && platformOpsEnabled && dictionaryMapper.getIfAvailable() != null) {
            return new cn.studykid.multiagent.infrastructure.persistence
                    .MybatisDictionaryRepository(dictionaryMapper.getObject(),
                            platformOps.platformIdentityDataSource());
        }
        return new cn.studykid.multiagent.infrastructure.persistence
                .JdbcDictionaryRepository(platformOps.platformIdentityDataSource());
    }

    /**
     * 平台通道的 Agent 发布归档仓储（运维连接）。
     *
     * <p>全局 Agent 在 PLATFORM 作用域发布时也要归档到 agent_version /
     * execution_snapshot（否则 run.snapshot_id 外键断裂）。这两张表已全局化、
     * 无 tenant_id 列，用零 UUID 哨兵绑定上下文即可；走运维连接是因为发布
     * 头与指针是 PLATFORM 行，整笔事务必须在同一条运维连接上。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort
            platformOpsAgentReleaseArchivePort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence.cap
                .JdbcAgentReleaseArchiveRepository(platformOps.guardedOrUnavailableDataSource());
    }

    /**
     * 平台通道的变更申请单仓储（运维连接）。
     *
     * <p>⚠️ **必须是运维连接**：本表的 RLS 读写策略是严格相等
     * （{@code tenant_id = current_tenant_id()}），
     * 平台申请单（{@code tenant_id IS NULL}）对应用角色连接<b>天然不可见</b> ——
     * 用应用连接实现，读不到也写不进。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort
            platformOpsConfigPublishRequestPort(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps) {
        return new cn.studykid.multiagent.infrastructure.persistence
                .JdbcConfigPublishRequestRepository(
                        platformOps.guardedOrUnavailableDataSource(),
                        new cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository(
                                platformOps.guardedOrUnavailableDataSource()));
    }

    /**
     * 平台通道的发布服务（运维事务 + 运维通道的发布端口）。
     *
     * <p>⚠️ **与租户版的 {@code configPublishService} 是两个独立实例** ——
     * 事务边界不同（应用角色 vs BYPASSRLS）、端口连接不同。
     * 刻意**不**做成「按 scope 自动选池」的单实例。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigPublishService
            platformOpsConfigPublishService(
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsPublishTransaction")
            cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction
                    platformOpsPublishTransaction,
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsConfigPointerPort")
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsConfigReleasePort")
            cn.studykid.multiagent.ports.persistence.ConfigReleasePort releases,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsDecisionAuditPort")
            cn.studykid.multiagent.ports.persistence.DecisionAuditPort audit,
            cn.studykid.multiagent.application.cap.ConfigValidationGate gate,
            java.time.Clock clock,
            @org.springframework.beans.factory.annotation.Qualifier(
                    "platformOpsAgentReleaseArchivePort")
            cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort archive) {
        // ⚠️ 闭包校验器 / 关系投影 / 契约 / 策略一律**不装配**（null）：
        //    PLATFORM 作用域的发布**不含**租户级对象；全局 Agent 只支持 LEAF。
        //    ⚠️ 归档端口必须装配：全局 Agent 发布也要归档到 agent_version /
        //    execution_snapshot，否则 run.snapshot_id 外键断裂（fail-closed 见 publish）。
        return new cn.studykid.multiagent.application.cap.ConfigPublishService(
                platformOpsPublishTransaction, pointers, releases, toolDefs, skillDefs,
                serverDefs, agentDefs, audit, gate, clock, archive);
    }

    /**
     * 平台级能力对象（Tool / Skill / MCP Server）的登记与生命周期服务（第三十四批）。
     *
     * <p>⚠️ <b>本服务是平台级写入口的唯一后端</b>：管理台的 Tool / Skill / MCP
     * 页此前是只读目录，变更只能靠手写 SQL 或带外运维工具。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService
            platformCapabilityPublishService(
            cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider
                    platformOps,
            @org.springframework.beans.factory.annotation.Qualifier(
                    "platformOpsConfigPublishRequestPort")
            cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort requests,
            @org.springframework.beans.factory.annotation.Qualifier(
                    "platformOpsConfigPublishService")
            cn.studykid.multiagent.application.cap.ConfigPublishService publisher,
            cn.studykid.multiagent.application.cap.ConfigLifecycleService lifecycle,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.CapSkillDefPort skillDefs,
            cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort serverDefs,
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs,
            @org.springframework.beans.factory.annotation.Qualifier("platformOpsConfigPointerPort")
            cn.studykid.multiagent.ports.persistence.ConfigPointerPort pointers,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService(
                platformOps, requests, publisher, lifecycle, toolDefs, skillDefs, serverDefs,
                pointers, clock, agentDefs);
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

    /**
     * 远程 HTTP 工具的出站执行器（第三十七批 · 设计文档 §13.1）。
     *
     * <p>⚠️ <b>复用 {@code McpEndpointGuard} 是刻意的</b>：
     * MCP 端点与 HTTP 工具的出站防线必须<b>同口径</b> ——
     * 「DNS 解析后按实际 IP 判定」「两次解析比对防 rebinding」
     * 这两条一旦在两处各写一遍，迟早有一处漏掉，
     * 而漏掉的那处就是「MCP 挡了、HTTP 没挡」的缺口。
     */
    @Bean
    public cn.studykid.multiagent.infrastructure.http.HttpToolExecutor httpToolExecutor(
            cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard guard) {
        return new cn.studykid.multiagent.infrastructure.http.HttpToolExecutor(
                guard,
                cn.studykid.multiagent.infrastructure.http.HttpToolOptions.defaults());
    }

    /**
     * HTTP-backed 工具解析器（第三十七批 · ADR-011 / 07 §3）。
     *
     * <p>⚠️ HTTP 工具<b>不另开执行入口</b>：它们与原生工具、MCP-backed 工具
     * 共用 {@code ToolInvocationGate} 这一条路径。
     */
    @Bean
    public cn.studykid.multiagent.ports.http.HttpToolHandlerProvider httpToolHandlerProvider(
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.ports.persistence.PublishedVersionPort publishedVersions,
            cn.studykid.multiagent.infrastructure.http.HttpToolExecutor executor) {
        return new cn.studykid.multiagent.infrastructure.http.DbHttpToolHandlerProvider(
                toolDefs, publishedVersions, executor);
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
            cn.studykid.multiagent.ports.mcp.McpManifestPort manifest,
            cn.studykid.multiagent.application.cap.ReferencedContentResolver referencedContent) {
        return new cn.studykid.multiagent.application.cap.PermissionResolver(
                agentDefs, skillDefs, toolDefs, serverDefs, publishedVersions, manifest,
                referencedContent);
    }

    /**
     * 被引用行（输出契约 / 路由策略）的内容指纹解析器（第二十四批 · A4）。
     *
     * <p>⚠️ <b>为什么必须装配</b>：不装配时，声明了被引用行的 Agent
     * 会在构建闭包时被 <b>fail-closed 拒绝</b>（而不是「悄悄放弃这道防线」）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.ReferencedContentResolver
            referencedContentResolver(
                    cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort policies,
                    cn.studykid.multiagent.ports.persistence.CapResultContractPort contracts) {
        return new cn.studykid.multiagent.application.cap.ReferencedContentResolver(
                policies, contracts);
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
            cn.studykid.multiagent.ports.mcp.McpManifestPort manifest,
            cn.studykid.multiagent.application.cap.ReferencedContentResolver referencedContent) {
        return new cn.studykid.multiagent.application.cap.CachingClosureResolver(
                resolver, publishedVersions, toolDefs, agentDefs, manifest,
                cn.studykid.multiagent.application.cap.CachingClosureResolver.DEFAULT_MAX_ENTRIES,
                referencedContent);
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
     * <p>⚠️ <b>默认 false</b>：P5 的发布指针读落地前不得在生产整体开启
     * （见 {@code CapEnforcementSwitch} 的类注释）。
     *
     * <p><b>租户级灰度（阶段一 · P0-1c）</b>：额外解析
     * {@code config.flag.cap-authorization-enforced.tenants} 逗号分隔列表，
     * 作为灰度白名单传给开关。全局关闭时仍可仅对白名单内租户开启 CAP 链路；
     * 白名单为空 = 无租户级灰度（与旧行为逐字节一致）。
     */
    @Bean
    public cn.studykid.multiagent.application.cap.CapEnforcementSwitch capEnforcementSwitch(
            @Value("${" + cn.studykid.multiagent.application.cap.CapEnforcementSwitch.KEY
                    + ":false}") boolean enabled,
            @Value("${" + cn.studykid.multiagent.application.cap.CapEnforcementSwitch.KEY
                    + ".tenants:}") String tenantAllowlistCsv) {
        return new cn.studykid.multiagent.application.cap.CapEnforcementSwitch(
                enabled, parseCsvSet(tenantAllowlistCsv));
    }

    /**
     * CAP 授权链路状态指标（阶段一 · I2 · P0-1c 监控面板）。
     *
     * <p>注册 {@code cap.enforcement_status} Gauge（0=关 / 1=灰度 / 2=开），
     * 由 {@code MeterRegistry}（Spring Boot 自动装配）采样。
     *
     * <p>⚠️ <b>这只是观测</b>，不翻转开关 —— 与 {@link CapEnforcementReadinessGuard}
     * 同纪律：P5 指针读落地前默认保持 {@code 0}（见 {@code CapEnforcementSwitch} 类注释）。
     */
    @Bean
    public CapEnforcementMetrics capEnforcementMetrics(
            cn.studykid.multiagent.application.cap.CapEnforcementSwitch capSwitch,
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new CapEnforcementMetrics(capSwitch, registry);
    }

    /**
     * 解析逗号分隔的标识列表为 {@code Set}；空串/全空白 → 空集合。
     *
     * <p>⚠️ 本项目 {@code application.yml} 的列表值一律用引号包成字符串
     * （裸 YAML 序列会被 {@code YamlPropertiesFactoryBean} 丢弃），故这里做 CSV 解析。
     */
    private static java.util.Set<String> parseCsvSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * P0-2 启动自检：主数据源连接角色不得为超级用户 / 不得带 BYPASSRLS，
     * 否则租户隔离 RLS 被静默绕过（dev 用 ai_agent 即此情形）。
     * 失败即拒绝启动（fail-closed），与 {@code RagStartupSelfCheck} 同纪律。
     */
    @Bean
    public PlatformStartupSelfCheck platformStartupSelfCheck(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @Value("${agent.platform-ops.datasource.username:}") String opsUsername) {
        return new PlatformStartupSelfCheck(jdbcTemplate, opsUsername);
    }

    /**
     * P0-1 就绪守卫：把「CAP 授权链路关闭 = 不按 Agent 能力闭包校验工具」这一
     * 静默缺口变为显式、可观测；并可经 config.flag.cap-enforcement-required 升级为硬失败。
     * 不替 P5 翻转开关。
     */
    @Bean
    public CapEnforcementReadinessGuard capEnforcementReadinessGuard(
            cn.studykid.multiagent.application.cap.CapEnforcementSwitch capSwitch,
            @Value("${config.flag.cap-enforcement-required:false}") boolean enforcementRequired,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.application.cap.CapExecutionFlow> capFlow,
            @Value("${config.flag.cap-closure-enforcement:warn}") String closureEnforcementMode) {
        // ⚠️ P0-1a：闭包校验模式一并纳入就绪判定 ——
        //    「硬失败」要覆盖的是强制层到底有没有校验，
        //    而强制层有两条通路（CAP 链路 / 既有链路的闭包守卫），都得处于强制状态。
        return new CapEnforcementReadinessGuard(capSwitch, enforcementRequired, capFlow,
                closureEnforcementMode);
    }

    /**
     * P1-C 审批就绪守卫：把「运行时人工审批未接线 → 需审批工具恒被拒，但无任何可见信号」
     * 这一静默缺口变为显式可观测；并可经 agent.approval.require-live-source 升级为硬失败。
     * 不改变任何既有审批判定，也不放松任何门禁。
     */
    @Bean
    public ApprovalReadinessGuard approvalReadinessGuard(
            java.util.List<cn.studykid.multiagent.ports.tool.ToolHandler> tools,
            cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy approvalPolicy,
            @Value("${agent.approval.live-source-enabled:false}") boolean liveSourceEnabled,
            @Value("${agent.approval.require-live-source:false}") boolean requireLiveSource) {
        return new ApprovalReadinessGuard(tools, approvalPolicy,
                liveSourceEnabled, requireLiveSource);
    }

    /**
     * P2-2 审计防篡改漂移守卫。
     *
     * <p>⚠️ {@code 04_retention_role.sql} 执行后一旦有人重跑授权脚本，
     * {@code audit_record} 的 DELETE 会被<b>悄悄还给应用角色</b>，
     * 「不可篡改」当场失效而无任何报错。本守卫在每次启动时复核该事实。
     * 默认告警；{@code agent.retention.require-isolated-credential=true}
     * 时升级为拒绝启动。
     */
    @Bean
    public AuditTamperProofGuard auditTamperProofGuard(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.retention.require-isolated-credential:false}")
            boolean requireIsolatedCredential) {
        return new AuditTamperProofGuard(jdbcTemplate, requireIsolatedCredential);
    }

    /**
     * 决策日志就绪守卫（D6 / P2）。
     *
     * <p>⚠️ 开关开着却零记录，是本项目最熟悉的失败形态（{@code RetentionConfig} 已记过两次）。
     * 本守卫用<b>零行写入探测</b>把「配了」升级为「实测过」；探测失败即拒绝启动。
     */
    @Bean
    public AuthzDecisionLogGuard authzDecisionLogGuard(
            @Value("${agent.authz.decision-log.enabled}") boolean enabled,
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder> recorders) {
        return new AuthzDecisionLogGuard(enabled, recorders);
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

    // ── 发布流程管理（Phase 2+） ──────────────────────────────────

    /** 发布策略服务（三层优先级解析）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.PublishPolicyService publishPolicyService(
            cn.studykid.multiagent.ports.persistence.PublishPolicyPort publishPolicyPort) {
        return new cn.studykid.multiagent.application.cap.PublishPolicyService(publishPolicyPort);
    }

    /** 业务审计日志服务（cap_audit_log）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.AuditLogService auditLogService(
            cn.studykid.multiagent.ports.persistence.AuditLogPort auditLogPort,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.AuditLogService(auditLogPort, clock);
    }

    /** 变更草稿服务（编辑工作流 + 策略分流 + 结构化失败）。 */
    @Bean
    public cn.studykid.multiagent.application.cap.ConfigDraftService configDraftService(
            cn.studykid.multiagent.ports.persistence.ConfigDraftPort configDraftPort,
            cn.studykid.multiagent.application.cap.PublishPolicyService publishPolicyService,
            cn.studykid.multiagent.application.cap.AuditLogService auditLogService,
            cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort configPublishRequestPort,
            cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService workflowService,
            java.time.Clock clock) {
        return new cn.studykid.multiagent.application.cap.ConfigDraftService(
                configDraftPort, publishPolicyService, auditLogService,
                configPublishRequestPort, workflowService, clock);
    }
}

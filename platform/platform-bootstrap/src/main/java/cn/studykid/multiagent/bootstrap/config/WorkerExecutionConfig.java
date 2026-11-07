package cn.studykid.multiagent.bootstrap.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.application.config.BudgetPolicy;
import cn.studykid.multiagent.application.config.EffectiveConfigResolver;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.RunConfigSnapshotFactory;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;import cn.studykid.multiagent.worker.execute.ToolInvocationGate;

/**
 * Worker 执行链装配（P4-D01 · 关闭 P3 退出条件 C-3）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复一处**断链**——
 * {@code RunDispatchConsumer.consume} 领到租约后<b>直接返回</b>，
 * {@code task} 拿到手即被丢弃，导致
 * {@code RunExecutionLoop} / {@code ToolInvocationGate} <b>从未被实例化</b>。
 * 后果是 D5–D11 交付的<b>全部</b>闸门（审批 / 预算 / 取消 / 注入防护 /
 * 编造出处拦截）在生产中<b>一次都不会执行</b>，
 * 而所有集成测试都直接 {@code new} 这些类，因此<b>每一项都测得通过</b>。
 *
 * <p><b>⚠️ 本类刻意独立于 {@link ApplicationConfiguration}</b>：
 * 执行链有 8 个依赖，堆在主装配类里会让其不可读。
 * 主类只留 {@code @Import}。
 *
 * <p><b>⚠️ 关于 Worker 主循环</b>（L-3）：本类只接通
 * 「MQ 消息 → 领取 → 执行」这一条链路；
 * 批量轮询 / 优雅停机 / 并发度控制属后续切片。
 */
@Configuration
@EnableScheduling
public class WorkerExecutionConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecutionConfig.class);

    /** 租约时长：须与 {@code RunClaimPort.DEFAULT_LEASE} 同源。 */
    @Value("${agent.worker.lease-seconds:30}")
    long leaseSeconds;

    // ── C8 接线（P4-D11 D11-1）────────────────────────────────────
    //
    // ⚠️⚠️ 这三个值此前**完全没有生产读取点**：生产快照是硬编码的
    //    （maxCost=10.00 / maxSteps=20 / maxLlmCalls=8），
    //    而 yml 声明 max-cost-per-run=2.00 —— **声明比实际紧 5 倍**。
    //    运维读配置页看到的数字与真实成本敞口不一致。
    //
    // ⚠️ **不加默认值**：缺配置必须**拒绝**（fail-closed），
    //    退回默认值等于「悄悄用了一个没人审过的额度」。
    @Value("${agent.run.max-cost-per-run}")
    String maxCostPerRun;

    @Value("${agent.run.max-llm-calls}")
    String maxLlmCalls;

    @Value("${agent.run.max-steps}")
    String maxSteps;

    /**
     * 平台级**映射型**配置（风险映射 + 审批要求）。
     *
     * <p>⚠️ 必须经 {@link PlatformConfigMaps}（Binder）绑定：
     * {@code @Value} 无法绑定 YAML map（会抛 Could not resolve placeholder）。
     * 缺失 → 该 Bean 的创建失败 → <b>拒绝启动</b>（fail-closed）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    PlatformConfigMaps configMaps;

    /** Worker 身份（生产应为实例唯一标识）。 */
    @Value("${agent.worker.id:worker-local}")
    String workerId;

    /**
     * 上下文 token 预算（第二十六批 · A7）。
     *
     * <p>⚠️ <b>默认值是「本批自定」的保守值</b>：
     * 本仓**没有**「模型上下文窗口」的权威来源，
     * 故只能给一个「足够宽松、但能拦住极端膨胀」的默认。
     * ⚠️ 该值**只约束发给模型的消息序列**，不含系统提示与工具 Schema。
     */
    @Value("${agent.worker.context-token-budget:16000}")
    int contextTokenBudget;

    /** MiniMax 真实调用：api-key 非空即启用（仅环境变量注入，不得写死或入库）。 */
    @Value("${agent.model.minimax.api-key:}")
    String minimaxApiKey;

    @Value("${agent.model.minimax.base-url:https://api.minimax.cn/v1}")
    String minimaxBaseUrl;

    @Value("${agent.model.minimax.default-model:MiniMax-M2.5}")
    String minimaxDefaultModel;

    /**
     * 模型网关（P4-D01 / L-4）。
     *
     * <p><b>配置了 {@code agent.model.minimax.api-key} 时走真实 MiniMax 调用</b>；
     * 未配置时回落 {@code FakeModelGateway} 占位，保证链路结构完整。
     *
     * <p>⚠️ <b>不得</b>把 Fake 占位当作「模型已接通」的证据：
     * {@code ZeroRetentionGate} 仍按供应商判定，真实学生数据须供应商
     * 在确认清单（{@code agent.compliance.zero-retention.confirmed-providers}）内。
     */
    /**
     * 原始模型网关（非 Primary，由 ObservableModelGateway 包装）。
     *
     * <p>配置了 {@code agent.model.minimax.api-key} 时走真实 MiniMax 调用；
     * 未配置时回落 {@code FakeModelGateway} 占位，保证链路结构完整。
     */
    @Bean
    public cn.studykid.multiagent.ports.model.ModelGateway rawModelGateway() {
        return modelGateway();
    }

    /**
     * 控制台「直接对话」流式网关（真·LLM 多轮流式，{@code POST /api/chat/stream}）。
     *
     * <p>复用 {@link #rawModelGateway()} 这<b>同一个</b>网关实例——
     * {@code MiniMaxModelGateway} 与 {@code FakeModelGateway} 均<span
     * style="white-space:nowrap">同时</span>实现 {@code ModelGateway} 与
     * {@code ChatStreamGateway} 两个端口，故同一实例既可作为一次性调用网关
     * （被 {@code ObservableModelGateway} 包装后供 Worker），也可作为
     * 流式对话网关（供 {@code ConsoleChatController}），共享一个 HTTP 客户端。
     *
     * <p>⚠️ <b>这是之前漏接的 Bean</b>：{@code ConsoleChatController}
     * 构造器需要 {@code ChatStreamGateway}，但此前从未注册该类型 Bean，
     * 导致应用上下文无法启动（单元测试用直接构造绕过了这一步，故一直没暴露）。
     */
    @Bean
    public ChatStreamGateway chatStreamGateway() {
        // rawModelGateway() 返回的实例必然实现 ChatStreamGateway（MiniMax/Fake 均双实现）
        return (ChatStreamGateway) rawModelGateway();
    }

    /**
     * 模型网关实例（raw，未包装观测链）。
     *
     * <p>配置了 {@code agent.model.minimax.api-key} 时走真实 MiniMax 调用；
     * 未配置时回落 {@code FakeModelGateway} 占位，保证链路结构完整。
     * 统一收口到此处，确保两个端口 Bean 永远指向同一决策。
     */
    private cn.studykid.multiagent.infrastructure.model.AbstractModelGateway modelGateway() {
        if (minimaxApiKey != null && !minimaxApiKey.isBlank()) {
            log.info("装配 MiniMaxModelGateway: 真实模型调用已启用, defaultModel={}",
                    minimaxDefaultModel);
            return new cn.studykid.multiagent.infrastructure.model.MiniMaxModelGateway(
                    minimaxBaseUrl, minimaxApiKey, minimaxDefaultModel);
        }
        log.warn("⚠️⚠️ 使用 FakeModelGateway 占位（未配置 agent.model.minimax.api-key）——"
                + "链路可跑通，但**不是真实模型调用**");
        return new cn.studykid.multiagent.infrastructure.model.FakeModelGateway(
                cn.studykid.multiagent.infrastructure.model.FakeModelGateway.FaultMode.NONE,
                new cn.studykid.multiagent.ports.model.ModelResponse(
                        "这道题考查一元一次方程。请移项求解。",
                        java.util.Map.of(),
                        new cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage(
                                10, 20),
                        cn.studykid.multiagent.ports.model.ModelResponse.FinishReason.STOP));
    }

    /**
     * 审批策略：按风险等级判定「是否需要审批」。
     *
     * <p>⚠️ <b>fail-closed</b>：{@code READ} 不需审批，
     * 其余（含未知等级）一律需要审批。
     *
     * <p>⚠️ <b>P4-D08 R-3</b>：本 Bean 改为<b>读取 {@code agent.approval.required-by-risk}</b>，
     * 与 yml 声明对齐。原实现硬编码「非 READ 一律需审批」，
     * 与 yml 的 {@code WRITE_PROFILE: OPTIONAL} 矛盾——
     * 配置的 {@code OPTIONAL} 语义从未被任何代码读取，
     * 致使 {@code record_weakness} 工具即便修好注册也仍会被审批闸门拦死。
     *
     * <p>⚠️ <b>只能收紧不能放松</b>：每个等级的实际要求 =
     * {@code defaultApproval().maxWith(配置值)}——
     * 配置只能把要求<b>调严</b>，不能调松。
     * 例：某等级默认 REQUIRED，配置写 NOT_REQUIRED → 实际仍为 REQUIRED。
     */
    @Bean
    public RiskBasedApprovalPolicy riskBasedApprovalPolicy(
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.READ:NOT_REQUIRED}")
            String readRequirement,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.WRITE_PROFILE:OPTIONAL}")
            String writeProfileRequirement,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.approval.required-by-risk.WRITE_EXTERNAL:REQUIRED}")
            String writeExternalRequirement) {
        Map<ToolRiskLevel, ApprovalRequirement> byRisk = new java.util.EnumMap<>(
                ToolRiskLevel.class);
        byRisk.put(ToolRiskLevel.READ, parseRequirement(readRequirement, ToolRiskLevel.READ));
        byRisk.put(ToolRiskLevel.WRITE_PROFILE,
                parseRequirement(writeProfileRequirement, ToolRiskLevel.WRITE_PROFILE));
        byRisk.put(ToolRiskLevel.WRITE_EXTERNAL,
                parseRequirement(writeExternalRequirement, ToolRiskLevel.WRITE_EXTERNAL));
        log.info("装配审批策略: {}", byRisk);
        return new RiskBasedApprovalPolicy(byRisk);
    }

    /**
     * 解析审批要求（fail-closed）。
     *
     * <p>⚠️ 非法值不回落默认值，而是<b>按最严（REQUIRED）处理</b>——
     * 配置写错时放行有副作用工具 = Sev-0。
     */
    private static ApprovalRequirement parseRequirement(String raw, ToolRiskLevel level) {
        if (raw == null || raw.isBlank()) {
            return ApprovalRequirement.REQUIRED;
        }
        ApprovalRequirement parsed;
        try {
            parsed = ApprovalRequirement.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.error("审批要求配置非法，按最严处理: level={}, value={}", level, raw);
            return ApprovalRequirement.REQUIRED;
        }
        // ⚠️ 只能收紧不能放松：实际要求 = 默认要求 maxWith 配置值
        return level.defaultApproval().maxWith(parsed);
    }

    /**
     * 审批结论来源（P4-D01 / E-2）。
     *
     * <p>⚠️ <b>默认为空策略 = 恒 {@code FAIL_CLOSED}</b>：
     * 外部人类审批台未接入前，需要审批的工具一律被拒。
     * 这不是缺陷而是<b>设计内的安全状态</b>——
     * 一旦真实审批权威实现本接口并装配，有副作用工具即可被真实审批驱动。
     */
    @Bean
    public RiskBasedApprovalPolicy.ApprovalSource approvalSource(
            @Value("${agent.approval.source.mode:none}") String mode,
            @Value("${agent.approval.live-source-enabled:false}") boolean liveSourceEnabled,
            @NonNull Environment environment) {
        String normalized = mode == null ? "none" : mode.trim().toLowerCase(Locale.ROOT);
        Map<String, Map<String, RiskBasedApprovalPolicy.ApprovalSource.Verdict>> policy =
                loadApprovalPolicy(environment);
        boolean wired = "policy-engine".equals(normalized) && !policy.isEmpty();

        // ⚠️⚠️ **一致性 fail-closed（M2-3）**：
        //    「来源模式」与「是否已接活体通道」是两个键，若允许它们不一致，
        //    就会出现「以为接上了、其实没有」的静默失效 ——
        //    例如 mode=policy-engine 但策略表为空（= 全部 FAIL_CLOSED），
        //    或 live-source-enabled=true 但 mode=none（= 根本没有来源实现）。
        //    这类配置错位在旧实现里是无声的（审批恒拒，日志只有普通拒绝），
        //    故此处直接**拒绝启动**。
        if (wired != liveSourceEnabled) {
            throw new IllegalStateException(
                    "审批来源配置不一致：agent.approval.source.mode=" + normalized
                            + "（已配置策略租户数=" + policy.size() + "）与 "
                            + "agent.approval.live-source-enabled=" + liveSourceEnabled
                            + " 矛盾。两者必须同时为 true（已接入）或同时为 false（未接入）。");
        }

        return switch (normalized) {
            case "none" -> {
                log.info("审批来源模式=none：需审批的工具在运行时恒 FAIL_CLOSED（安全默认）");
                yield new RiskBasedApprovalPolicy.PolicyEngineApprovalSource();
            }
            case "policy-engine" -> {
                log.info("审批来源模式=policy-engine：已配置租户 {} 个", policy.size());
                yield new RiskBasedApprovalPolicy.PolicyEngineApprovalSource(policy);
            }
            default -> throw new IllegalStateException(
                    "未知的审批来源模式: " + mode
                            + "（支持 none / policy-engine；未识别模式一律拒绝启动）");
        };
    }

    /**
     * 读取审批策略表（{@code agent.approval.source.policy}）。
     *
     * <p>⚠️ <b>未知结论值一律拒绝启动</b>（fail-closed）：
     * 把 {@code "MAYBE"} 当成「不批准」固然安全，但把配置写错这件事
     * 本身必须可见 —— 否则运维会以为策略生效了。
     *
     * @param environment 环境
     * @return 租户 → (工具名 → 结论)
     */
    private static Map<String, Map<String, RiskBasedApprovalPolicy.ApprovalSource.Verdict>>
            loadApprovalPolicy(@NonNull Environment environment) {
        // ⚠️ 策略表用**扁平规则列表**而非嵌套 map：
        //    Spring 的 Bindable 没有「map of map」的可用重载，
        //    嵌套 map 绑定在不同 Boot 版本上行为不一致（本次实测编译不过）。
        //    扁平列表（"租户:工具=结论"）绑定稳定、解析一目了然。
        List<String> rules = org.springframework.boot.context.properties.bind.Binder
                .get(environment)
                .bind("agent.approval.source.policy.rules",
                        org.springframework.boot.context.properties.bind.Bindable
                                .listOf(String.class))
                .orElseGet(List::of);
        Map<String, Map<String, RiskBasedApprovalPolicy.ApprovalSource.Verdict>> result =
                new java.util.LinkedHashMap<>();
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            int eq = rule.indexOf('=');
            int colon = rule.indexOf(':');
            if (eq <= 0 || colon <= 0 || colon >= eq) {
                throw new IllegalStateException(
                        "审批策略规则格式错误: " + rule + "（应为 租户ID:工具名=APPROVED|REJECTED）");
            }
            String tenantId = rule.substring(0, colon).trim();
            String toolName = rule.substring(colon + 1, eq).trim();
            String rawVerdict = rule.substring(eq + 1).trim().toUpperCase(Locale.ROOT);
            RiskBasedApprovalPolicy.ApprovalSource.Verdict verdict = switch (rawVerdict) {
                case "APPROVED" -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.APPROVED;
                case "REJECTED" -> RiskBasedApprovalPolicy.ApprovalSource.Verdict.REJECTED;
                default -> throw new IllegalStateException(
                        "未知的审批结论: " + rule + "（支持 APPROVED / REJECTED）");
            };
            result.computeIfAbsent(tenantId, k -> new java.util.LinkedHashMap<>())
                    .put(toolName, verdict);
        }
        return result;
    }

    /**
     * 工具调用闸门（D3–D9 的全部安全机制汇合点）。
     *
     * @param tools 本进程注册的工具
     */
    @Bean
    public ToolInvocationGate toolInvocationGate(
            List<ToolHandler> tools,
            AuthorizationService authorization,
            RiskBasedApprovalPolicy approvalPolicy,
            BudgetPort budget,
            RunToolLedgerPort ledger,
            ApprovalPort approvalPort,
            ToolInvocationGate.CancelGuard cancelGuard,
            RiskBasedApprovalPolicy.ApprovalSource approvalSource,
            BigDecimal reserveAmount,
            cn.studykid.multiagent.application.cap.CapExecutionFlow capExecutionFlow,
            cn.studykid.multiagent.ports.mcp.McpHandlerProvider mcpHandlerProvider,
            cn.studykid.multiagent.ports.http.HttpToolHandlerProvider httpToolHandlerProvider,
            cn.studykid.multiagent.application.orchestration.DelegationExecutor delegation,
            java.time.Clock clock,
            cn.studykid.multiagent.application.orchestration.RunBudgetProbe runBudgetProbe,
            cn.studykid.multiagent.worker.execute.CapabilityClosureGuard closureGuard,
            cn.studykid.multiagent.application.approval.ApprovalRequestSettings
                    approvalRequestSettings) {
        log.info("装配工具闸门: 工具 {} 个，审批策略=按风险等级，审批来源=策略引擎，"
                        + "CAP 授权链路={}，多级委派={}，Run 级预算={}，HTTP 出站={}，"
                        + "闭包校验={}",
                tools.size(), capExecutionFlow.isEnabled(), delegation != null,
                runBudgetProbe != null, httpToolHandlerProvider != null,
                closureGuard == null ? "未装配" : closureGuard.mode());
        // ⚠️⚠️ **闭包守卫必须真实注入**（P0-1a）：
        //    不注入时，既有链路**完全没有**能力闭包校验 ——
        //    「闭包」只在呈现层生效（模型看不到越权工具），
        //    强制层却没有这道校验：任何已注册工具都可被任意 Agent 调用。
        return new ToolInvocationGate(
                tools, authorization, approvalPolicy, budget, ledger, approvalPort,
                BudgetPolicy.requireReserveAmount(reserveAmount),
                cancelGuard, approvalSource, capExecutionFlow, mcpHandlerProvider, delegation,
                clock, runBudgetProbe, httpToolHandlerProvider, closureGuard,
                // ⚠️ P0-2：必须真实注入 —— 否则需要审批的工具被拒绝时，
                //    审批台**看不到任何待办**（approval 表无写入方），
                //    「审批闭环」仍是一扇通向墙壁的门。
                approvalRequestSettings);
    }

    /**
     * 审批请求登记参数（P0-2）。
     *
     * <p>⚠️ <b>这两个键此前没有任何运行时读取点</b>
     * （{@code agent.approval.ttl} / {@code agent.approval.approver-scope}）——
     * 于是「审批记录」连有效期与审批人范围都没有落库依据。
     * 现在它们有了真实消费者，故同时把
     * {@code ConfigReadService} 里的 {@code effective} 标注改为 true。
     *
     * <p>⚠️ <b>缺配置即拒绝启动</b>（fail-closed）：
     * 退回默认值等于「悄悄用了一个没人审过的有效期」。
     */
    @Bean
    public cn.studykid.multiagent.application.approval.ApprovalRequestSettings
            approvalRequestSettings(
            @Value("${agent.approval.ttl}") Duration ttl,
            @Value("${agent.approval.approver-scope}") String approverScope) {
        return new cn.studykid.multiagent.application.approval.ApprovalRequestSettings(
                approverScope, ttl);
    }

    /**
     * 能力闭包守卫（P0-1a · 关闭「越权调用工具」这个洞的强制层）。
     *
     * <p>⚠️ <b>模式由配置决定</b>（{@code config.flag.cap-closure-enforcement}）：
     * <ul>
     *   <li>{@code off}：不校验（历史行为）；</li>
     *   <li>{@code warn}：校验但只告警 —— 用于「能力目录尚未装载」的过渡期，
     *       把缺口变成<b>可见</b>的，而不是继续悄悄放行；</li>
     *   <li>{@code enforce}：校验且拒绝未声明的工具。</li>
     * </ul>
     *
     * <p>⚠️ <b>为什么默认不是 {@code enforce}</b>：
     * 闭包判定的输入是<b>能力目录</b>。目录为空时，任何强制模式都会把
     * <b>所有</b>工具调用拒掉 —— 那不是「校验生效」，是「平台不可用」。
     * 与其让运维在不知情的情况下遭遇全拒绝，不如让缺口明确可见。
     * 装载能力目录后应切到 {@code enforce}，并配合
     * {@code config.flag.cap-enforcement-required=true} 让「未开启强制」在生产
     * <b>拒绝启动</b>（见 {@code CapEnforcementReadinessGuard}）。
     */
    @Bean
    public cn.studykid.multiagent.worker.execute.CapabilityClosureGuard capabilityClosureGuard(
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort capabilitySnapshots,
            @Value("${config.flag.cap-closure-enforcement:warn}") String enforcementMode) {
        cn.studykid.multiagent.worker.execute.CapabilityClosureGuard.Mode mode =
                cn.studykid.multiagent.worker.execute.CapabilityClosureGuard.Mode
                        .parse(enforcementMode);
        if (mode != cn.studykid.multiagent.worker.execute.CapabilityClosureGuard.Mode.ENFORCE) {
            log.warn("⚠️ 能力闭包校验未强制（模式={}）："
                    + "既有链路只告警不拒绝 —— 越权调用工具仍可能通过，"
                    + "请先装载能力目录（cap_tool_def / cap_skill_def）再切到 enforce", mode);
        }
        return new cn.studykid.multiagent.worker.execute.CapabilityClosureGuard(
                closureResolver, capabilitySnapshots, mode);
    }

    /**
     * 委派工具 Schema 装配器（第二十批 · FR-035）。
     *
     * <p>⚠️ <b>根路径与子路径共用这一份</b>：两处各写一份必然漂移，
     * 而漂移的后果是「根能委派、子不能」（或反之）—— 那正是「两套语义」本身。
     */
    @Bean
    public cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
            delegationToolSchemaAssembler(
            cn.studykid.multiagent.ports.persistence.CapAgentDefPort agentDefs) {
        return new cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler(agentDefs);
    }

    /**
     * 子 Agent 模型配置装配（多级 Agent v1.1 · P2-MA-02）。
     *
     * <p>⚠️ <b>必须注入委派工具 Schema 装配器（第二十批）</b>：
     * 委派工具<b>不进</b> Skill→Tool 能力闭包 → 闭包解析给不出它，
     * 必须按 Agent <b>自己</b>的编排声明<b>显式装配</b> ——
     * 否则模型看不见委派工具，运行期<b>物理上不可能</b>发起委派。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider
            childAgentModelConfigProvider(
            cn.studykid.multiagent.ports.persistence.AgentQueryPort agentQuery,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                    delegationToolSchemaAssembler) {
        return new cn.studykid.multiagent.worker.execute.WorkerChildAgentModelConfigProvider(
                agentQuery, closureResolver, toolDefs, delegationToolSchemaAssembler);
    }

    /**
     * 子 Agent 执行器（多级 Agent v1.1 · P2-MA-02）。
     *
     * <p>⚠️ <b>懒加载</b>：它依赖闸门，而闸门依赖委派执行器，委派执行器又依赖
     * 本端口 —— 直接构造会成环。{@code LazyChildAgentRunner} 把解析推迟到
     * 第一次真实委派，那时闸门已就绪。
     */
    @Bean
    public cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner workerChildAgentRunner(
            cn.studykid.multiagent.ports.observer.ObservableModelGateway modelGateway,
            ToolInvocationGate gate,
            cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider configs,
            cn.studykid.multiagent.ports.orchestration.CancellationProbe cancellation) {
        return new cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner(
                modelGateway, gate, configs,
                cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner.DEFAULT_MAX_CHILD_STEPS,
                cancellation, System::nanoTime,
                java.time.Clock.systemUTC(),
                contextTokenBudget);
    }

    /**
     * 取消探针（多级 Agent v1.1 · MA-T11）。
     *
     * <p>⚠️ <b>与闸门的取消守卫同源</b>：都接 {@code RunClaimPort.isCancelled} ——
     * 取消是 <b>Run 级</b>状态，父 Run 取消必须能传播到运行中的子 Agent；
     * 两处若读的不是同一个事实，就会出现「闸门以为没取消、子执行却停了」
     * 或反之的错位。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.CancellationProbe cancellationProbe(
            @NonNull RunClaimPort claimPort) {
        return claimPort::isCancelled;
    }

    /**
     * 子 Agent 执行端口（懒解析）。
     */
    @Bean
    public cn.studykid.multiagent.ports.orchestration.ChildAgentRunner childAgentRunner(
            org.springframework.beans.factory.ObjectProvider<
                    cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner> delegate) {
        return new cn.studykid.multiagent.worker.execute.LazyChildAgentRunner(
                () -> delegate.getObject());
    }

    /**
     * 抢占式单步超时执行器（P2-1）。
     *
     * <p>⚠️⚠️ <b>为什么单独开一个 Bean 而不是共用通用线程池</b>：
     * 本执行器的任务会被 {@code Future.cancel(true)} 中断或被遗弃到超时为止，
     * 与业务线程池混用会把「模型卡住」的后果扩散到无关任务上
     * （线程被占死 → 连带 MQ 消费、租约续期一起排队）。
     *
     * <p>⚠️ <b>为什么用虚拟线程</b>（Java 21）：任务体几乎全是
     * 「等 HTTP 响应」的阻塞 I/O，虚拟线程在被阻塞时让出载体，
     * 不会像平台线程那样把并发上限压到几百。
     *
     * <p>⚠️ <b>不设池化上限</b>：上限引入后，一次「配额耗尽」会让超时保护
     * 本身排队等待，反而放大故障。真正的并发闸门在更外层的执行信号量，
     * 不在这里重复发明。
     */
    @Bean(destroyMethod = "close")
    public java.util.concurrent.ExecutorService stepPreemptionExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 执行循环（FR-004）。
     */
    /**
     * Worker 执行循环（含事件写入，P4-D12 / G4）。
     *
     * <p>⚠️⚠️ <b>事件端口必须在这里真实注入</b>：
     * P4-D08 的 R-1 教训——「工具未注册为 Bean → 工具从不执行，
     * 而系统表面完全正常」；本片若只把端口留在执行循环里而**不注入**，
     * 事件就会**永远不写**（默认 {@code null} = 不写），
     * 进度页时间线依旧只有一条，而**没有任何报错**。
     * 故本片的连通性测试专门断言「事件端口**确实**被注入」。
     */
    @Bean
    public RunExecutionLoop runExecutionLoop(
            cn.studykid.multiagent.ports.observer.ObservableModelGateway modelGateway,
            RunClaimPort claimPort,
            ToolInvocationGate gate,
            PromptGuard promptGuard,
            cn.studykid.multiagent.ports.persistence.RunEventPort eventPort,
            cn.studykid.multiagent.application.orchestration.InFlightDelegationAdopter adopter,
            cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe lifecycleProbe,
            cn.studykid.multiagent.ports.persistence.BudgetPort budgetPort,
            cn.studykid.multiagent.application.config.ModelPricingPolicy modelPricingPolicy,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.ports.persistence.RunStepPort runStepPort,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.ports.persistence.RiskEventPort riskEventPort,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.ports.observer.ExecutionObserverChain observerChain,
            java.util.concurrent.ExecutorService stepPreemptionExecutor,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            org.springframework.beans.factory.ObjectProvider<RunDispatchConsumer.RunExecutor>
                    executorProvider,
            // ⚠️⚠️ 记忆两件套（P1）**可选注入** —— 但它们缺失时的后果
            //    与 turn 预算端口那种"账不平"不同：记忆缺失的表现是
            //    「模型看起来很了解这个学生，其实一无所知」，
            //    以及「student_memory 永远为空」。两者都不会报错。
            //    → 由 MemoryWiringIT 断言生产装配下二者**非 null**。
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.memory.application.MemoryRecallService memoryRecallService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.memory.application.MemoryConsolidator memoryConsolidator) {
        // ⚠️⚠️ **接管器必须在这里真实注入**（第九批 · MA-T12）：
        //    不注入时，Worker 接管后 RUNNING 的委派记录**永远无人能收敛**
        //    （新世代 token 不匹配、旧世代已被接管），
        //    真实原因（Worker 崩溃）会被掩盖成一次普通的子调用失败。
        // ⚠️⚠️ **租户生命周期探针必须在这里真实注入**（重规划 §14 / 验收矩阵 #4）：
        //    不注入时，平台暂停/退役租户后 Worker 仍会执行其任务（越权执行，Sev-1）。
        // ⚠️ **执行步骤写入端口（P7-2）**：可选注入（required=false）——
        //    缺失时循环不写 run_step（向后兼容旧环境），但指标步数类会显示「未采集」。
        //    生产环境必须装配（infrastructure 的 JdbcRunStepRepository），否则 P7-2 退化近似缺口不闭合。
        // ⚠️ **安全事件写入端口（P7-4）**：可选注入（required=false）——
        //    缺失时不落 agent_risk_event（SEC-2/SEC-3 明细显示「未采集」）。
        //    生产环境必须装配（infrastructure 的 JdbcRiskEventRepository）。
        // ⚠️⚠️ **预算端口与单价表必须真实注入**（P0-3b）：
        //    不注入时，模型调用的成本**不入账** —— usage_record 的 token 两列永远为 0，
        //    cost_amount 只有工具调用的固定代理值，
        //    「单 Run 成本上限」（maxCostPerRun）也因为「不知道花了多少」而无从判定。
        //    这正是 P0-3 的核心缺口，不能靠默认值糊过去。
        return new RunExecutionLoop(modelGateway, claimPort, gate,
                Duration.ofSeconds(leaseSeconds), workerId, promptGuard, eventPort, adopter,
                contextTokenBudget, lifecycleProbe, runStepPort, riskEventPort, observerChain,
                // ⚠️ P2-1：必须真实注入，否则单步调用不受 deadline 约束
                //    （循环只在每轮开头检查，FR-024 在单步粒度形同虚设）
                stepPreemptionExecutor, budgetPort, modelPricingPolicy,
                // ⚠️ M1-4b / G2：恢复续跑接缝。
                //    ⚠️ **必须复用与主路径同一个 RunExecutor** ——
                //    另起一套「恢复专用执行逻辑」必然与主路径语义漂移
                //    （兜底收敛、取消检查、预算口径各写一遍，越修越不一致）。
                //    ObjectProvider 是为了打破循环依赖：RunExecutor 本身依赖 loop。
                continuation(executorProvider),
                // ⚠️ P1：记忆召回与整合。null = 关闭（agent.memory.enabled=false），
                //    此时 RunExecutionLoop 会打 WARN，行为回到本次改动之前。
                memoryRecallService, memoryConsolidator);
    }

    /**
     * 恢复续跑接缝（M1-4b / G2）。
     *
     * <p>⚠️ <b>为什么用 {@code ObjectProvider} 延迟取</b>：
     * {@code RunExecutor} bean 依赖 {@code RunExecutionLoop}，
     * 而循环构造时又需要接缝 —— 直接注入会形成循环依赖。
     * 延迟取既打破环，又保证「取到的就是主路径那个执行器」。
     *
     * <p>⚠️ <b>接缝缺失 = fail-closed</b>：{@code getIfAvailable()} 返回 {@code null}
     * 时传 {@code null} 给循环 → 循环在恢复批次执行成功后
     * <b>显式收敛 FAILED</b>（{@code RESUME_CONTINUATION_UNAVAILABLE}），
     * 绝不把 Run 悬在 RUNNING 等租约过期被接管（那等于整单重跑）。
     */
    private static RunExecutionLoop.@Nullable RunContinuation continuation(
            ObjectProvider<RunDispatchConsumer.RunExecutor> executorProvider) {
        if (executorProvider == null) {
            return null;
        }
        return (tenantId, task) -> {
            RunDispatchConsumer.RunExecutor executor = executorProvider.getIfAvailable();
            if (executor == null) {
                return RunExecutionLoop.ExecutionOutcome.failed(
                        "RESUME_CONTINUATION_UNAVAILABLE");
            }
            // ⚠️ 结局映射必须与 RunDispatchConsumer 的 MQ 语义一致：
            //    UNKNOWN_RESULT 仍是「不可重试」，绝不能在此变成可重试
            //    （重投 = 重复副作用 = Sev-0）。
            return switch (executor.execute(tenantId, task)) {
                case SUCCEEDED -> RunExecutionLoop.ExecutionOutcome.succeeded();
                case FAILED -> RunExecutionLoop.ExecutionOutcome.failed("EXECUTION_FAILED");
                case CANCELLED -> RunExecutionLoop.ExecutionOutcome.cancelled();
                case RETRYABLE_FAILURE ->
                        RunExecutionLoop.ExecutionOutcome.retryableFailure("RETRYABLE_FAILURE");
                case UNKNOWN_RESULT ->
                        RunExecutionLoop.ExecutionOutcome.unknownResult(null);
                case STALE_GENERATION ->
                        RunExecutionLoop.ExecutionOutcome.skippedStaleGeneration();
            };
        };
    }

    /**
     * 审批决定唤醒器（M1-6 / G2 · PARK → RESUME 的后半段）。
     *
     * <p>⚠️ <b>为什么必须装配</b>：Run 在工具需要审批时进入
     * {@code WAITING_APPROVAL} 并释放租约让出 worker 线程；
     * 人批准后若没人把它拉回执行，审批台显示「已批准」而工具永远不执行
     * —— 这正是 G2「HITL 恒拒」的最后一环。
     *
     * <p>⚠️ <b>依赖方向</b>：本 bean 在 bootstrap（组合根）层把
     * {@code agent-worker} 的实现接到 {@code agent-ports} 的端口上，
     * API 层只认端口、不知道 Worker 的存在（分层不倒置）。
     */
    @Bean
    public cn.studykid.multiagent.ports.persistence.ApprovalWakeupPort approvalWakeupPort(
            cn.studykid.multiagent.ports.persistence.ApprovalQueryPort approvals,
            RunClaimPort claimPort,
            cn.studykid.multiagent.ports.persistence.RunToolLedgerPort ledger,
            RunExecutionLoop loop) {
        log.info("装配审批唤醒器: workerId={}, 租约={}s", workerId, leaseSeconds);
        return new cn.studykid.multiagent.worker.execute.ApprovalDecidedWakeupConsumer(
                approvals, claimPort, ledger, loop, workerId,
                Duration.ofSeconds(leaseSeconds));
    }

    /**
     * 模型单价表（P0-3b · 成本入账）。
     *
     * <p>⚠️ <b>为什么用 {@code Binder} 而不是 {@code @Value}</b>：
     * {@code agent.pricing.models} 在 yml 里是 <b>map of map</b>，
     * {@code @Value} 期望标量 → 必然解析失败（同 {@code agent.tool.risk-level-map}）。
     *
     * <p>⚠️ <b>缺配置不拒绝启动</b>（与 {@code risk-level-map} 的 fail-closed 不同）：
     * 单价表缺失只是「成本不可计量」，不是「安全门禁缺失」。
     * 缺表时 token 仍如实记录、成本按 0 记并打 WARN ——
     * 让平台因为「没配价」而拒绝启动，是拿可用性换一个可观测性缺口，不划算。
     * 但「哪些模型还没配价」必须在启动日志里<b>可见</b>。
     */
    @Bean
    public cn.studykid.multiagent.application.config.ModelPricingPolicy modelPricingPolicy(
            @NonNull Environment environment) {
        // ⚠️ 值类型取 Object：每个模型条目本身是一个 map，
        //    用嵌套泛型（mapOf(String, mapOf(String, Object))) 会撞上 Binder
        //    的重载解析歧义；绑定成 Object 后再逐条转型即可，语义等价。
        Map<String, Object> entries = org.springframework.boot.context.properties.bind.Binder
                .get(environment)
                .bind("agent.pricing.models",
                        org.springframework.boot.context.properties.bind.Bindable
                                .mapOf(String.class, Object.class))
                .orElseGet(Map::of);
        Map<String, Map<String, Object>> raw = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> values) {
                Map<String, Object> perModel = new java.util.LinkedHashMap<>();
                values.forEach((k, v) -> perModel.put(String.valueOf(k), v));
                raw.put(entry.getKey(), perModel);
            } else {
                throw new IllegalStateException(
                        "agent.pricing.models." + entry.getKey() + " 必须是价格映射（"
                                + "input-per-1k-tokens / output-per-1k-tokens）");
            }
        }
        cn.studykid.multiagent.application.config.ModelPricingPolicy policy =
                cn.studykid.multiagent.application.config.ModelPricingPolicy.of(raw);
        if (policy.isEmpty()) {
            log.warn("模型单价表为空（agent.pricing.models 未配置）："
                    + "模型调用的 token 会如实记录，但成本按 0 记，成本类指标不可用");
        } else {
            log.info("模型单价表已加载: 已配置模型 {} 个", policy.configuredModels().size());
        }
        return policy;
    }

    /**
     * 单任务执行器（第二十一批 · 抽离供崩溃恢复复用）。
     *
     * <p>⚠️ 本执行器同时被 MQ 消费路径（{@link #runDispatchConsumer}）与
     * 崩溃恢复扫描器（{@code RunRecoverySweeper}）复用——
     * 两者拿到 {@code LeasedTask} 后走完全相同的执行与兜底收敛逻辑，
     * 避免「恢复路径」与「主路径」两套语义漂移。
     *
     * <p>⚠️ fail-closed：拿不到根 Agent 模型配置 → 拒绝执行并兜底收敛终态，
     * 绝不回落硬编码占位模型（回落 = 把「配置缺失」伪装成「配置正常」）。
     *
     * <p>⚠️ <b>Run 输入同样 fail-closed</b>（L-4 闭合）：输入取自
     * {@code run.input_payload}（创建事务内落库，不可变）。
     * 此前这里传的是<b>装配层写死的占位问题</b>，后果不是「参数不准」而是
     * <b>用户提交的内容根本没进过模型上下文</b> —— Run 表面成功、
     * 结果与用户问的东西毫无关系。读不到输入就拒绝执行，
     * 绝不用假问题换一次「看起来跑通了」。
     */
    @Bean
    public RunDispatchConsumer.RunExecutor runTaskExecutor(
            RunClaimPort claimPort, RunExecutionLoop loop, RunExecutionInputs inputs,
            cn.studykid.multiagent.ports.persistence.RunInputPort runInputPort) {
        return (tenantId, task) -> {
            // ⚠️⚠️ **fail-closed（第二十一批）**：拿不到根 Agent 的模型配置
            // → **拒绝执行该 Run**，绝不回落硬编码占位模型。
            // 回落等于让「配置缺失」被伪装成「配置正常」——
            // 根 Agent 会拿着假模型跑完整个 Run，而日志里看不出任何异常。
            java.util.Optional<RunExecutionLoop.AgentModelConfig> config =
                    inputs.agentConfig(tenantId, task.runId());
            if (config.isEmpty()) {
                // ⚠️⚠️ 孤儿 Run 兜底（第二十一批 · 高危修复）：
                // Run 此刻已是 RUNNING（已被 claimSpecificRun 置位），
                // 若只 return FAILED 让消息被 ack 丢弃，Run 永久卡死。
                // 先 best-effort commit FAILED（终态），再返回 FAILED。
                log.warn("根 Agent 模型配置不可用，Run 拒绝执行并收敛终态: run={}",
                        task.runId());
                try {
                    claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                            "FAILED", RunExecutionLoop.failurePayload("config_unavailable"),
                            "CONFIG_UNAVAILABLE");
                } catch (RuntimeException ce) {
                    log.warn("孤儿 Run 兜底收敛失败（可能已被接管）: run={}, cause={}",
                            task.runId(), ce.toString());
                }
                return RunDispatchConsumer.Outcome.FAILED;
            }
            // ── L-4：Run 输入取自 run.input_payload（不再用占位问题）──────
            java.util.Map<String, Object> runInput =
                    runInputPort.inputOf(tenantId, task.runId());
            if (runInput.isEmpty()) {
                log.warn("Run 输入不可用，拒绝执行并收敛终态: run={}", task.runId());
                try {
                    claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                            "FAILED", RunExecutionLoop.failurePayload("input_unavailable"),
                            "INPUT_UNAVAILABLE");
                } catch (RuntimeException ce) {
                    log.warn("孤儿 Run 兜底收敛失败（可能已被接管）: run={}, cause={}",
                            task.runId(), ce.toString());
                }
                return RunDispatchConsumer.Outcome.FAILED;
            }
            RunExecutionLoop.ExecutionOutcome outcome = loop.execute(
                    tenantId, task, config.get(),
                    inputs.snapshotFor(task, tenantId), runInput);
            return switch (outcome.kind()) {
                case SUCCEEDED -> RunDispatchConsumer.Outcome.SUCCEEDED;
                case FAILED -> RunDispatchConsumer.Outcome.FAILED;
                case CANCELLED -> RunDispatchConsumer.Outcome.CANCELLED;
                case RETRYABLE_FAILURE -> RunDispatchConsumer.Outcome.RETRYABLE_FAILURE;
                // ⚠️⚠️ UNKNOWN 必须映射为「丢弃」——重投 = 重复副作用 = Sev-0
                case UNKNOWN_RESULT -> RunDispatchConsumer.Outcome.UNKNOWN_RESULT;
                case SKIPPED_STALE_GENERATION ->
                        RunDispatchConsumer.Outcome.STALE_GENERATION;
                // ⚠️ 租户暂停/退役 → 终态失败，不重投（重投只会再次被拒）
                case TENANT_BLOCKED -> RunDispatchConsumer.Outcome.FAILED;
                // ⚠️ M1-4 / G2：PARKED = Run 已迁 WAITING_APPROVAL 并释放租约，
                //    挂起是「暂停」而非失败——MQ 消息必须 ack 丢弃（映射同 SUCCEEDED），
                //    绝不可重投（重投会被再次领取 = 重复登记待办 + 幂等键撞车）；
                //    恢复由 approval.decided 事件（WakeupConsumer）驱动，与本消息无关。
                case PARKED -> RunDispatchConsumer.Outcome.SUCCEEDED;
            };
        };
    }

    /**
     * MQ 消费者（P4-D01 断链修复点）。
     *
     * <p>⚠️ 这里把 {@code RunExecutionLoop} 适配为 {@code RunExecutor}：
     * 消费者只关心「执行结局 → MQ 处置」的映射。
     */
    @Bean
    public RunDispatchConsumer runDispatchConsumer(
            RunClaimPort claimPort,
            RunDispatchConsumer.RunExecutor executor) {
        log.info("装配 Worker 消费者: 租约={}s（执行器已接入）", leaseSeconds);
        return new RunDispatchConsumer(claimPort, Duration.ofSeconds(leaseSeconds), executor);
    }

    /**
     * 执行输入供给（Agent 配置 + Run 快照）。
     *
     * <p>⚠️ <b>L-4 已闭合</b>：Run 输入由 {@code runTaskExecutor} 经
     * {@link cn.studykid.multiagent.ports.persistence.RunInputPort}
     * 从 {@code run.input_payload} 读取（创建事务内落库，不可变）。
     */
    @Bean
    public RunExecutionInputs runExecutionInputs(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.ports.persistence.ConfigOverridePort configOverridePort,
            cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                    delegationToolSchemaAssembler,
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider
                    rootAgentModelConfigProvider) {
        // ⚠️ P4-D15：ConfigOverridePort 可选注入——缺失时本 Bean 与改动前完全等价，
        //    使配置表尚未迁移的环境也能启动（fail-open 于**装配**，
        //    但**收紧校验与审计**仍在写入路径 fail-closed）。
        EffectiveConfigResolver resolver =
                configOverridePort == null ? null : new EffectiveConfigResolver(configOverridePort);
        return new RunExecutionInputs(resolver, maxCostPerRun, maxLlmCalls, maxSteps,
                configMaps.toolRiskLevelMap(), configMaps.approvalRequiredByRisk(),
                delegationToolSchemaAssembler, snapshots, rootAgentModelConfigProvider);
    }

    /**
     * 根 Agent 模型配置提供器（第二十一批 · FR-036）。
     *
     * <p>⚠️ <b>根 Agent 此前走硬编码占位</b>（{@code fake-model} + 空工具表），
     * 意味着根 Agent <b>没有任何普通工具可用</b> ——
     * 「多级 Agent 可用」在根这一层是假的。现在根按
     * <b>Run 能力快照里的固定 release</b> 读真实定义，
     * 与子路径<b>共用同一份装配实现</b>。
     *
     * <p>⚠️ <b>读不到即拒绝执行</b>：拿不到根 Agent 的模型配置 → 该 Run 不执行，
     * 绝不回落占位模型。
     */
    @Bean
    public cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider
            rootAgentModelConfigProvider(
            cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots,
            cn.studykid.multiagent.ports.persistence.AgentQueryPort agentQuery,
            cn.studykid.multiagent.application.cap.ClosureResolver closureResolver,
            cn.studykid.multiagent.ports.persistence.CapToolDefPort toolDefs,
            cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                    delegationToolSchemaAssembler) {
        return new cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider(
                snapshots,
                new cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler(
                        agentQuery, closureResolver, toolDefs),
                delegationToolSchemaAssembler);
    }

    /**
     * 执行输入供给。
     *
     * <p>⚠️ 当前为占位实现（P4-D01 §4 L-4）：
     * 真实实现须读 {@code run.input_payload} 与 Agent 配置表。
     */
    public static final class RunExecutionInputs {

        /**
         * P4-D15：租户配置覆盖解析器（可为 null——未装配时行为与改动前逐位相同）。
         *
         * <p>⚠️ <b>为什么允许为 null 而不直接依赖注入</b>：本类由
         * {@link #runExecutionInputs()} 无参构造；若强制依赖 {@code ConfigOverridePort}，
         * 则任何只关心 Worker 的上下文测试/装配都得先准备配置表。
         * 保持可空 + <b>缺失即回落 yml</b>，使本切片对既有行为零影响（D15-2）。
         */
        private final EffectiveConfigResolver effectiveResolver;

        // ⚠️ C8 接线（P4-D11）：快照必须来自 yml，不得硬编码。
        private final String maxCostPerRun;
        private final String maxLlmCalls;
        private final String maxSteps;
        private final Map<String, ToolRiskLevel> toolRiskLevelMap;
        private final Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk;

        /**
         * 委派工具 Schema 装配器（第二十批 · FR-035）。
         *
         * <p>⚠️ <b>可为空</b>：未装配时根路径<b>不装配委派工具</b> ——
         * 无从判断根 Agent 有没有委派能力时，宁可不给。
         */
        private final cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                delegationToolSchemaAssembler;

        /**
         * Run 能力快照仓储（根 Agent 身份的权威来源）。
         *
         * <p>⚠️ <b>可为空</b>：未装配时根路径<b>不装配委派工具</b> ——
         * 读不到快照就不知道根 Agent 是谁。
         */
        private final cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort snapshots;

        /**
         * 根 Agent 模型配置提供器（第二十一批）。
         *
         * <p>⚠️ <b>可为空 = 未装配</b>：此时根路径<b>拒绝执行该 Run</b> ——
         * 拿不到根 Agent 的模型配置时，<b>不得</b>回落硬编码占位
         * （回落等于让「配置缺失」被伪装成「配置正常」）。
         */
        private final cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider
                rootAgentModelConfigProvider;

        RunExecutionInputs(EffectiveConfigResolver effectiveResolver,
                           String maxCostPerRun, String maxLlmCalls, String maxSteps,
                           Map<String, ToolRiskLevel> toolRiskLevelMap,
                           Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk) {
            this(effectiveResolver, maxCostPerRun, maxLlmCalls, maxSteps,
                    toolRiskLevelMap, approvalRequiredByRisk, null, null);
        }

        /**
         * 最完整构造器（第二十一批 · 增加根 Agent 模型配置装配）。
         *
         * @param delegationToolSchemaAssembler 委派工具 Schema 装配器（可空）
         * @param snapshots                    Run 能力快照仓储（可空）
         * @param rootAgentModelConfigProvider 根 Agent 模型配置提供器（可空 = 拒绝执行）
         */
        RunExecutionInputs(EffectiveConfigResolver effectiveResolver,
                           String maxCostPerRun, String maxLlmCalls, String maxSteps,
                           Map<String, ToolRiskLevel> toolRiskLevelMap,
                           Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk,
                           cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                                   delegationToolSchemaAssembler,
                           cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort
                                   snapshots,
                           cn.studykid.multiagent.worker.execute.RootAgentModelConfigProvider
                                   rootAgentModelConfigProvider) {
            this.effectiveResolver = effectiveResolver;
            this.maxCostPerRun = maxCostPerRun;
            this.maxLlmCalls = maxLlmCalls;
            this.maxSteps = maxSteps;
            this.toolRiskLevelMap = toolRiskLevelMap;
            this.approvalRequiredByRisk = approvalRequiredByRisk;
            this.delegationToolSchemaAssembler = delegationToolSchemaAssembler;
            this.snapshots = snapshots;
            this.rootAgentModelConfigProvider = rootAgentModelConfigProvider;
        }

        /**
         * 兼容构造器（第二十批形态 · 根 Agent 配置未装配）。
         *
         * <p>⚠️ <b>根路径会拒绝执行</b>：未装配根 Agent 模型配置提供器时，
         * {@link #agentConfig(String, String)} 返回空 → 该 Run 不执行。
         */
        RunExecutionInputs(EffectiveConfigResolver effectiveResolver,
                           String maxCostPerRun, String maxLlmCalls, String maxSteps,
                           Map<String, ToolRiskLevel> toolRiskLevelMap,
                           Map<ToolRiskLevel, ApprovalRequirement> approvalRequiredByRisk,
                           cn.studykid.multiagent.worker.execute.DelegationToolSchemaAssembler
                                   delegationToolSchemaAssembler,
                           cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort
                                   snapshots) {
            this(effectiveResolver, maxCostPerRun, maxLlmCalls, maxSteps,
                    toolRiskLevelMap, approvalRequiredByRisk, delegationToolSchemaAssembler,
                    snapshots, null);
        }

        /**
         * Agent 模型配置（<b>按 Run 装配真实定义</b> · 第二十一批）。
         *
         * <p>⚠️ <b>修复前的真实缺陷</b>：根 Agent 的模型标识 / 提示词 /
         * <b>普通工具表</b>此前是<b>硬编码占位</b>（{@code fake-model} +
         * 写死的提示词 + 空工具表）。后果不是「配置不准」，而是
         * <b>根 Agent 实际上没有任何普通工具可用</b> ——
         * 它能看见的只有委派工具。于是「多级 Agent 可用」这句话在根这一层是假的：
         * <b>根只能委派、不能自己干活</b>。
         *
         * <p>⚠️ <b>根 Agent 身份来自 Run 能力快照</b>：
         * 同一个租户的不同 Run 可能由不同根 Agent 执行，
         * 按全局装配会让 A 的 Run 拿到 B 的模型与工具。
         *
         * <p>⚠️ <b>读不到即 fail-closed</b>：拿不到根 Agent 的模型配置
         * → 返回空 → 由调用方<b>拒绝执行该 Run</b>。
         * 绝不回落 {@code fake-model} 占位 ——
         * 回落等于让「配置缺失」被伪装成「配置正常」。
         *
         * @param tenantId 租户标识
         * @param runId    Run 标识
         * @return Agent 模型配置；<b>读不到时返回空</b>（fail-closed）
         */
        public java.util.Optional<RunExecutionLoop.AgentModelConfig> agentConfig(
                @NonNull String tenantId, @NonNull String runId) {
            if (rootAgentModelConfigProvider == null) {
                // ⚠️ 未装配根 Agent 配置提供器 → 无从知道根 Agent 的模型 → 拒绝执行。
                log.warn("未装配根 Agent 模型配置提供器，Run 拒绝执行: run={}", runId);
                return java.util.Optional.empty();
            }
            return rootAgentModelConfigProvider.configFor(tenantId, runId)
                    .map(c -> new RunExecutionLoop.AgentModelConfig(
                            c.modelId(), c.systemPrompt(), c.temperature(),
                            c.maxTokens(), c.timeout(), c.toolSchemas()));
        }

        /**
         * Run 配置快照。
         *
         * <p>⚠️⚠️ <b>C8 接线（P4-D11 D11-1）</b>：此前此处返回<b>硬编码</b>快照
         * （成本上限 {@code 10.00} / 步数 {@code 20} / 调用 {@code 8}），
         * 而 yml 声明的 {@code agent.run.max-cost-per-run=2.00} <b>没有任何生产读取点</b>
         * —— 声明值比真实敞口<b>紧 5 倍</b>，运维读到的数字是假的。
         *
         * <p>⚠️ <b>fail-closed</b>：配置缺失/非法 → {@code ConfigResolutionException}
         * → <b>拒绝执行该 Run</b>，<b>不</b>退回硬编码默认值。
         * 退回默认值等于「悄悄用了一个没人审过的额度」——
         * 那正是本片要消除的分叉。
         *
         * <p>⚠️ <b>审批要求按「最严」解析</b>：本次 Run 的主工具未知
         * （{@code run.input_payload} 尚未接通，L-4），故按
         * {@code WRITE_EXTERNAL} 兜底 → 必然 {@code REQUIRED}——
         * 与改造前的硬编码行为一致，<b>不放松</b>任何门禁。
         */
        public RunConfigSnapshot snapshotFor(RunClaimPort.LeasedTask task) {
            return snapshotFor(task, null);
        }

        /**
         * P4-D15：叠加租户配置覆盖后生成快照。
         *
         * <p>⚠️ <b>为什么不把覆盖读进构造</b>：构造期注入的是 yml（启动期 fail-closed 守卫），
         * 而覆盖是<b>租户级、运行期可变</b>的。两者混在一起会把「启动期校验」
         * 退化成「运行期才发现配错」——原本 yml 配错 = 启动失败，是好的。
         *
         * <p>⚠️ <b>tenantId 为 null（无租户上下文）时直接用 yml</b>：
         * 保持与 P4-D15 之前逐位相同的行为，不让覆盖查询在无租户时抛错。
         *
         * @param tenantId 租户；为 null 时不叠加覆盖
         */
        public RunConfigSnapshot snapshotFor(RunClaimPort.LeasedTask task, String tenantId) {
            String cost = maxCostPerRun;
            String calls = maxLlmCalls;
            String steps = maxSteps;

            // ⚠️ P4-D15：DB 覆盖 ⊕ yml 默认。有覆盖才替换，无覆盖逐位沿用原值。
            if (effectiveResolver != null && tenantId != null) {
                var effective = effectiveResolver.resolve(tenantId, Map.of(
                        PlatformConfig.KEY_MAX_COST_PER_RUN, maxCostPerRun,
                        PlatformConfig.KEY_MAX_LLM_CALLS, maxLlmCalls,
                        PlatformConfig.KEY_MAX_STEPS, maxSteps));
                String c = effective.get(PlatformConfig.KEY_MAX_COST_PER_RUN);
                String l = effective.get(PlatformConfig.KEY_MAX_LLM_CALLS);
                String s = effective.get(PlatformConfig.KEY_MAX_STEPS);
                if (c != null) {
                    cost = c;
                }
                if (l != null) {
                    calls = l;
                }
                if (s != null) {
                    steps = s;
                }
            }

            return new RunConfigSnapshotFactory(java.time.Clock.systemUTC()).create(
                    task.runId(),
                    cost,
                    calls,
                    steps,
                    toolRiskLevelMap,
                    approvalRequiredByRisk,
                    // ⚠️ 主工具未知 → 未登记工具按 WRITE_EXTERNAL 兜底（fail-closed）
                    UNKNOWN_PRIMARY_TOOL);
        }

        /**
         * 「主工具未知」的占位工具名。
         *
         * <p>⚠️ 它<b>不在</b> yml 的 risk-level-map 中 → 按
         * {@code ToolRiskLevel.resolve} 规则落到 {@code WRITE_EXTERNAL}
         * → 审批要求 {@code REQUIRED}。这是<b>最严</b>方向，符合
         * 「判据只增不减」（RAG-D00 §3 原则 7 / P0-D03 红线 15）。
         */
        private static final String UNKNOWN_PRIMARY_TOOL = "__unknown_primary_tool__";
    }

    /**
     * 取消守卫 Bean（P3-D06 D-2）。
     *
     * <p>⚠️ 装配为 {@code RunClaimPort.isCancelled} —— D6 交付的取消检查
     * 在此才真正接入生产链路（此前闸门用的是默认 {@code NO_CANCEL} 桩）。
     */
    @Bean
    public ToolInvocationGate.CancelGuard cancelGuard(@NonNull RunClaimPort claimPort) {
        return claimPort::isCancelled;
    }
}

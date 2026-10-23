package cn.studykid.multiagent.testsupport.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * §9 场景注册表（验收证据的唯一登记处 · W-PERM-P6-T1/T5）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：P6 §6 风险登记明确写着
 * 「追踪矩阵虚标」是已知风险，缓解决策是「§10 强制证据路径；CI 报告可关联，
 * <b>禁止手工勾选代替</b>」。
 * 而「手工勾选」的反面正是<b>把矩阵写成散文</b> ——
 * 一张写着「S3f ✅」的 Markdown 表格，无法回答「那条用例现在还在吗？」
 *
 * <p>故本注册表把三件事<b>绑在一起</b>：
 * <ol>
 *   <li><b>场景 ID 与期望的四字段</b>—— 必须与设计文档 §9.1 的表格<b>逐字一致</b>
 *       （由 {@link Section9ScenarioSpec} 解析文档得到，覆盖测试逐字段比对）；</li>
 *   <li><b>证据落点</b>—— 具体到「测试类 + 测试方法」，
 *       由覆盖测试回到源码里核对该方法<b>确实存在</b>；</li>
 *   <li><b>断言内容</b>—— 该方法体内确实出现了期望的原因码，
 *       否则「有个同名方法」也可能是空壳。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么允许一条场景有多组期望</b>：
 * §9.1 的 S3f（准入后撤权）允许三种终态（SUCCEEDED / FAILED / UNKNOWN），
 * S5c（清单下线/漂移）允许两个原因码（按 §5.8 分流）。
 * 把它们压成一条会<b>丢掉真实语义</b>，逼着测试只覆盖其中一支。
 *
 * <p>⚠️ <b>本表登记的是「证据在哪」，不是「有没有覆盖」</b>：
 * 覆盖与否由 {@code Section9ScenarioCoverageTest} 判定 ——
 * 若某条场景只有间接证据（如与另一场景共用同一方法），
 * 应在 {@link Scenario#note()} 里<b>如实写明</b>，
 * 而不是把间接证据伪装成独立证据。
 */
public final class Section9ScenarioRegistry {

    /** 场景类别。 */
    public enum Kind {
        /** §9.1 行为测试（四字段）。 */
        BEHAVIOR,
        /** §9.2 不变量。 */
        INVARIANT,
        /** §9.2 故障注入。 */
        FAULT
    }

    /**
     * 一组期望的四字段元组。
     *
     * @param authz      授权结论
     * @param exec       执行准入结论
     * @param status     执行状态
     * @param reasonCode 原因码
     */
    public record Expectation(AuthorizationDecision.Authorization authz,
                              AuthorizationDecision.Execution exec,
                              AuthorizationDecision.Status status,
                              ReasonCode reasonCode) {
    }

    /**
     * 证据落点：测试类 + 测试方法。
     *
     * @param className  测试类简单名
     * @param methodName 测试方法名
     */
    public record Evidence(String className, String methodName) {

        /**
         * @return 可粘贴进追踪矩阵的引用形式
         */
        public String ref() {
            return className + "#" + methodName;
        }
    }

    /**
     * 一个场景。
     *
     * @param key          场景键（§9.1 的编号；无编号的场景用场景名）
     * @param title        场景名
     * @param kind         类别
     * @param expectations 期望的四字段（可多组；不变量/故障注入为空）
     * @param evidences    证据落点（可多个）
     * @param note         如实备注（如「间接证据」「仅单测层」）
     */
    public record Scenario(String key,
                           String title,
                           Kind kind,
                           List<Expectation> expectations,
                           List<Evidence> evidences,
                           String note) {

        public Scenario {
            expectations = List.copyOf(expectations);
            evidences = List.copyOf(evidences);
        }
    }

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    private Section9ScenarioRegistry() {
    }

    // ── 登记辅助 ──────────────────────────────────────────────────

    private static void behavior(String key, String title,
                                 AuthorizationDecision.Authorization authz,
                                 AuthorizationDecision.Execution exec,
                                 AuthorizationDecision.Status status,
                                 ReasonCode reasonCode,
                                 String note,
                                 String... evidenceRefs) {
        register(new Scenario(key, title, Kind.BEHAVIOR,
                List.of(new Expectation(authz, exec, status, reasonCode)),
                evidences(evidenceRefs), note));
    }

    private static void behaviorMulti(String key, String title,
                                       AuthorizationDecision.Authorization authz,
                                       AuthorizationDecision.Execution exec,
                                       List<AuthorizationDecision.Status> statuses,
                                       List<ReasonCode> reasonCodes,
                                       String note,
                                       String... evidenceRefs) {
        List<Expectation> expectations = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            expectations.add(new Expectation(authz, exec, statuses.get(i),
                    reasonCodes.get(Math.min(i, reasonCodes.size() - 1))));
        }
        register(new Scenario(key, title, Kind.BEHAVIOR, expectations,
                evidences(evidenceRefs), note));
    }

    /**
     * 登记「同一状态 × 多个原因码」的场景（如 S5c：NOT_STARTED 下有两个可能码）。
     */
    private static void behaviorExplicit(String key, String title,
                                          AuthorizationDecision.Authorization authz,
                                          AuthorizationDecision.Execution exec,
                                          List<AuthorizationDecision.Status> statuses,
                                          List<ReasonCode> reasonCodes,
                                          String note,
                                          String... evidenceRefs) {
        List<Expectation> expectations = new ArrayList<>();
        for (AuthorizationDecision.Status status : statuses) {
            for (ReasonCode reasonCode : reasonCodes) {
                expectations.add(new Expectation(authz, exec, status, reasonCode));
            }
        }
        register(new Scenario(key, title, Kind.BEHAVIOR, expectations,
                evidences(evidenceRefs), note));
    }

    private static void invariant(String key, String title, String note, String... evidenceRefs) {
        register(new Scenario(key, title, Kind.INVARIANT, List.of(),
                evidences(evidenceRefs), note));
    }

    private static void fault(String key, String title, String note, String... evidenceRefs) {
        register(new Scenario(key, title, Kind.FAULT, List.of(),
                evidences(evidenceRefs), note));
    }

    private static List<Evidence> evidences(String... refs) {
        List<Evidence> result = new ArrayList<>();
        for (String ref : refs) {
            int hash = ref.indexOf('#');
            if (hash <= 0 || hash == ref.length() - 1) {
                throw new IllegalArgumentException("证据引用格式应为 Class#method: " + ref);
            }
            result.add(new Evidence(ref.substring(0, hash), ref.substring(hash + 1)));
        }
        return result;
    }

    private static void register(Scenario scenario) {
        if (SCENARIOS.putIfAbsent(scenario.key(), scenario) != null) {
            throw new IllegalStateException("场景键重复注册: " + scenario.key());
        }
    }

    /**
     * @return 全部登记场景（按登记顺序）
     */
    public static List<Scenario> scenarios() {
        return List.copyOf(SCENARIOS.values());
    }

    /**
     * @return 全部场景键
     */
    public static Set<String> keys() {
        return new LinkedHashSet<>(SCENARIOS.keySet());
    }

    /**
     * 文档中以散文给出的原因码占位 → 平台实际使用的码。
     *
     * <p>⚠️ <b>这张表是「显式登记」而非「自动推断」</b>：
     * 文档写「稳定失败码」时，必须由人指明它对应平台上的哪一个码；
     * 推断出来的映射会让「文档与实现一致」变成一句无法证伪的话。
     */
    public static final Map<String, ReasonCode> PROSE_PLACEHOLDER_CODES = Map.of(
            "稳定失败码", ReasonCode.TOOL_EXECUTION_FAILED);

    static {
        // ── §9.1 行为测试 ──────────────────────────────────────────
        behavior("S1", "已授权成功",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.SUCCEEDED,
                ReasonCode.SUCCESS,
                "单测 + IT 双层",
                "InvocationValidatorTest#allowsAuthorizedInvocation",
                "CapExecutionFlowTest#successProducesExactFourFields",
                "McpSecurityBehaviorTest#positiveMcpOperationIsAllowed",
                "CapApprovalBehaviorTest#approved",
                "InvocationValidatorIT#allowsAuthorizedInvocationOnRealDatabase");

        behavior("S2", "未启用 Skill",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.SKILL_NOT_ENABLED,
                "单测 + IT 双层",
                "InvocationValidatorTest#skillNotEnabledIsDenied",
                "InvocationValidatorIT#skillNotEnabledIsDeniedOnRealDatabase");

        behavior("S3", "主体撤权",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.AUTHZ_STALE,
                "单测 + IT 双层",
                "InvocationValidatorTest#staleAuthzVersionIsDenied",
                "InvocationValidatorIT#revokedPrincipalIsDeniedByDatabaseVersion");

        behavior("S3a", "跨实例撤权后调用",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.AUTHZ_STALE,
                "单测 + IT 双层；IT 用**独立连接池**模拟另一实例"
                        + "（无进程内共享状态，才能证明版本来自权威库而非实例内存）",
                "InvocationValidatorTest#crossInstanceRevocationIsDenied",
                "InvocationValidatorIT#crossInstanceRevocationIsDeniedImmediately");

        behavior("S3b", "撤权先于准入",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.EXEC_ADMISSION_STALE,
                "单测 + IT 双层",
                "CapExecutionFlowTest#staleAdmissionBlocksSideEffect",
                "CapExecutionFlowTest#staleAdmissionReleasesBudget",
                "CapCompensationIT#admissionFailureCancelsApprovalAndReleasesBudget");

        behavior("S3d", "版本服务不可用",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.AUTHZ_UNAVAILABLE,
                "单测 + IT 双层；IT 同时覆盖「版本记录缺失」与「**真·存储不可用**"
                        + "（连接必然被拒）」两种形态",
                "InvocationValidatorTest#authzUnavailableIsDenied",
                "CapExecutionFlowTest#unavailableAuthzVersionFailsClosed",
                "InvocationValidatorIT#missingAuthzVersionIsDenied",
                "InvocationValidatorIT#unreachableAuthzStoreFailsClosed");

        behaviorMulti("S3f", "准入后撤权",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                List.of(AuthorizationDecision.Status.SUCCEEDED,
                        AuthorizationDecision.Status.FAILED,
                        AuthorizationDecision.Status.UNKNOWN),
                List.of(ReasonCode.SUCCESS, ReasonCode.TOOL_EXECUTION_FAILED,
                        ReasonCode.EXEC_UNKNOWN),
                "三个终态各有独立落点（P6-T8 回流补齐了原缺的 FAILED / UNKNOWN 两支）",
                "CapExecutionFlowTest#inFlightCallIsNotCancelled",
                "CapExecutionFlowTest#inFlightCallFailureIsNotCancelled",
                "CapExecutionFlowTest#inFlightCallUnknownIsNotCancelled");

        behavior("S4a", "Skill 从 enabled 移除",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.SKILL_NOT_ENABLED,
                "单测 + IT 双层；IT 覆盖**移除转移**（真实 PG：启用时 ALLOW → 移除后新调用 DENY）",
                "InvocationValidatorTest#skillRemovedFromEnabledIsDenied",
                "RuntimeDecisionScenarioIT#skillRemovedFromEnabledIsDeniedOnRealDatabase");

        behavior("S4b", "riskCeiling 收紧",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.RISK_EXCEEDS_CEILING,
                "单测 + IT 双层；IT 覆盖**收紧转移**（真实 PG：上限宽松时 ALLOW → 收紧后 DENY）",
                "InvocationValidatorTest#riskCeilingTightenedIsDenied",
                "PermissionResolverTest#overridesCannotLoosenRiskCeiling",
                "RuntimeDecisionScenarioIT#tightenedRiskCeilingIsDeniedOnRealDatabase");

        behavior("S4c", "参数策略收紧",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.PARAM_POLICY_FAIL,
                "单测 + IT 双层；IT 覆盖**策略收紧转移**（真实 PG）；"
                        + "ParamPolicyEngineTest 为引擎层断言（不断言四字段）",
                "InvocationValidatorTest#paramPolicyTightenedIsDenied",
                "RuntimeDecisionScenarioIT#tightenedParamPolicyIsDeniedOnRealDatabase",
                "ParamPolicyEngineTest#allowlistRejectsUnlistedValue",
                "ParamPolicyEngineTest#lteRejectsAboveThreshold",
                "ParamPolicyEngineTest#typeMismatchIsRejected",
                "ParamPolicyEngineTest#missingRequiredParamIsRejected");

        behavior("S5a", "闭包指纹变化",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.CONFIG_FINGERPRINT_STALE,
                "单测 + IT 双层",
                "InvocationValidatorTest#closureFingerprintChangedIsDenied",
                "McpSecurityBehaviorTest#s5aClosureFingerprintChanged",
                "InvocationValidatorIT#republishedToolInvalidatesFingerprint");

        behavior("S5b", "操作级越界",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.MCP_OP_NOT_ALLOWED,
                "单测 + IT 双层；IT 用**真实库闭包**（Skill 操作清单 ∩ 已发布服务定义）判越界",
                "InvocationValidatorTest#mcpOperationOutsideAllowlistIsDenied",
                "McpSecurityBehaviorTest#s5bOperationOutsideAllowlist",
                "RuntimeDecisionScenarioIT#mcpOperationOutsideAllowlistIsDeniedOnRealDatabase");

        behaviorExplicit("S5c", "清单下线/漂移",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                List.of(AuthorizationDecision.Status.NOT_STARTED),
                List.of(ReasonCode.MCP_TOOL_DRIFTED, ReasonCode.CONFIG_FINGERPRINT_STALE),
                "单测 + IT 双层；两个码分支各有 IT 落点："
                        + "CONFIG_FINGERPRINT_STALE = 已发布白名单收紧（**纯 DB 漂移**）；"
                        + "MCP_TOOL_DRIFTED = 清单在指纹重算与操作复核之间漂移（TOCTOU；"
                        + "DB 侧全真，仅 MCP 传输边界替身）",
                "InvocationValidatorTest#mcpManifestDriftIsCaughtByFingerprint",
                "InvocationValidatorTest#mcpManifestToctouDriftIsDenied",
                "McpSecurityBehaviorTest#s5cManifestRemoved",
                "McpSecurityBehaviorTest#s5cToctouDrift",
                "RuntimeDecisionScenarioIT#narrowedServerAllowlistInvalidatesFingerprintOnRealDatabase",
                "RuntimeDecisionScenarioIT#mcpManifestToctouDriftIsDeniedOnRealDatabase");

        behavior("S6a", "审批不存在",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.APPROVAL_NOT_FOUND,
                "单测 + IT 双层；IT 为仓储层「审批不存在 → NOT_FOUND」",
                "CapApprovalBehaviorTest#notFound",
                "ApprovalStateMachineTest#notFound",
                "CapApprovalRepositoryIT#missingApprovalCannotBeReserved");

        behavior("S6b", "审批过期",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.APPROVAL_EXPIRED,
                "单测 + IT 双层；IT 为仓储层「过期 → EXPIRED」",
                "CapApprovalBehaviorTest#expired",
                "ApprovalStateMachineTest#expired",
                "CapApprovalRepositoryIT#expiredApprovalCannotBeReserved");

        behavior("S6c", "范围/绑定不匹配",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.APPROVAL_SCOPE_MISMATCH,
                "单测 + IT 双层；三层重复验同一码（编排器 / 状态机 / 绑定服务）"
                        + "，IT 层验「绑定项是预占的前置条件」",
                "CapApprovalBehaviorTest#scopeMismatch",
                "ApprovalStateMachineTest#replacedArgument",
                "ApprovalStateMachineTest#scopeViolation",
                "ApprovalStateMachineTest#closureFingerprintChanged",
                "ApprovalStateMachineTest#authzVersionChanged",
                "ApprovalScopeTest#replacedArgumentIsRejected",
                "ApprovalScopeTest#tamperedScopeIsRejected",
                "CapApprovalRepositoryIT#bindingMismatchBlocksReservation");

        behavior("S6d", "被他调用预占",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.APPROVAL_RESERVED_BY_OTHER,
                "单测 + IT 双层（IT 为仓储层预占独占性）",
                "CapApprovalBehaviorTest#reservedByOther",
                "ApprovalStateMachineTest#reservedByOther",
                "CapApprovalRepositoryIT#reservationIsExclusive");

        behavior("S6e", "已消费重放",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.APPROVAL_REPLAY_REJECTED,
                "单测 + IT 双层；IT 为仓储层「已消费 → ALREADY_CONSUMED（换调用标识重放）」",
                "CapApprovalBehaviorTest#replayRejected",
                "ApprovalStateMachineTest#consumedReplay",
                "CapApprovalRepositoryIT#consumedApprovalCannotBeReservedAgain");

        behavior("S7", "预算不足",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.BUDGET_EXCEEDED,
                "单测 + IT 双层",
                "CapApprovalBehaviorTest#budgetExceeded",
                "CapCompensationIT#approvalIsCancelledAndBudgetHasNoResidue");

        behavior("S8", "响应丢失",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.UNKNOWN,
                ReasonCode.EXEC_UNKNOWN,
                "单测 + IT 双层",
                "CapApprovalBehaviorTest#resultUnknown",
                "CapExecutionFlowTest#unknownResultRequiresReconciliation",
                "CapCompensationIT#unknownIsNotRetriedAndHasSingleTerminalState");

        behavior("S9", "成功但 POST 失败",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.SUCCEEDED,
                ReasonCode.SUCCESS,
                "单测 + IT 双层；ledger=RETRYING 由用例另行断言。"
                        + "与 COMP-4 是同一注入点（failLedgerPost），非两个独立证据",
                "CapApprovalBehaviorTest#succeededButPostFailed",
                "CapApprovalBehaviorTest#postFailureEnqueuesCompensation",
                "CapCompensationIT#succeededButNotPostedIsRetrying");

        behavior("Schema 失败", "Schema 失败",
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.PARAM_SCHEMA_FAIL,
                "单测 + IT 双层；IT 用**真实 JDBC 仓储**装配执行流，断言落在真实表上："
                        + "决策日志恰好 1 行且四字段与决策一致，"
                        + "而执行意图/审批/预算/账本/结果事件**全部 0 行**（真·零副作用）",
                "CapExecutionFlowTest#schemaFailureDeniesWithoutSideEffects",
                "ExecutionPhaseBScenarioIT#schemaFailureDeniesWithoutSideEffectsOnRealDatabase");

        behavior("参数策略失败", "参数策略失败",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.PARAM_POLICY_FAIL,
                "单测 + IT 双层；与 S4c 共用同一落点（同一码、同一条判定路径）",
                "InvocationValidatorTest#paramPolicyTightenedIsDenied",
                "RuntimeDecisionScenarioIT#tightenedParamPolicyIsDeniedOnRealDatabase");

        behavior("未知风险", "未知风险",
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                ReasonCode.RISK_LEVEL_UNKNOWN,
                "⚠️ 运行期四字段仍为**仅单测层**，但原因不是「没验」而是**验不了**："
                        + "cap_tool_def.risk_level 是 NOT NULL + CHECK，取值域只含三个已知等级，"
                        + "**未知风险在数据层不可能存在**。"
                        + "故 IT 层证明的是「不可达」（unknownRiskCannotBePersisted），"
                        + "与单测的「若出现则拒为 RISK_LEVEL_UNKNOWN」合起来才是完整结论："
                        + "状态不可达 + 不可达时仍 fail-closed；"
                        + "配置期拒发布另有 ConfigPublishIT 落点",
                "InvocationValidatorTest#unknownRiskIsDeniedWithDedicatedCode",
                "RuntimeDecisionScenarioIT#unknownRiskCannotBePersisted");

        // ── §9.2 不变量与故障注入 ──────────────────────────────────
        invariant("S3c", "并发撤权 → authzVersion 单调无丢失",
                "IT 层（8 线程 × 10 次 → 版本 = 初始 + 80）",
                "AuthzVersionRepositoryIT#concurrentBumpsDoNotLoseUpdates");

        invariant("S3e", "重复撤权 → 版本继续递增",
                "IT 层（连续 5 次 bump 严格递增）",
                "AuthzVersionRepositoryIT#repeatedRevocationNeverGoesBackwards");

        invariant("S3g-a", "单次调用配置有效性",
                "单测 + IT 双层；IT 覆盖「无关对象变更不致本 Run 失效」"
                        + "与「单次调用内配置自洽」",
                "InvocationValidatorIT#singleCallSeesSelfConsistentConfiguration",
                "InvocationValidatorIT#unrelatedObjectChangeDoesNotInvalidate",
                "CachingClosureResolverTest#unchangedDependenciesHitCache",
                "CachingClosureResolverTest#changedDependencyInvalidatesCache");

        invariant("S3g-b", "新调用只见完整已发布配置",
                "单测 + IT 双层；IT 覆盖「发布后新调用立刻看到完整新配置」"
                        + "与「发布三者同事务生效」「高序号未发布行不得被选中」",
                "InvocationValidatorIT#newCallSeesCompleteNewConfigurationAfterPublish",
                "ConfigPublishIT#atomicPublishPersistsAllThree",
                "CapRepositoryIT#currentReleaseIgnoresNonPublished");

        invariant("S3g-c", "已准入在途按约定继续",
                "⚠️ 「在途不被撤销」与「新调用按新配置生效」是同一不变量的两面，"
                        + "分别由撤权语境与配置发布语境两条用例承担；"
                        + "S3f 侧与 S3f 共用同一方法（撤权 flavor）",
                "CapExecutionFlowTest#inFlightCallIsNotCancelled",
                "InvocationValidatorIT#newCallSeesCompleteNewConfigurationAfterPublish");

        invariant("PUB-1", "CAS 失败事务无半成品当前发布",
                "单测（内存事务）+ IT（真实 PG）双层",
                "ConfigPublishServiceTest#casFailureRollsBackEverything",
                "ConfigPublishIT#casFailureLeavesNoHalfPublishedState");

        invariant("PUB-2", "seq 分配序 ≠ 提交序时指针仍以 CAS 为准",
                "仅 IT 层（并发编排单测未覆盖「seq 序 ≠ 提交序」）",
                "ConfigPublishIT#pointerFollowsCasNotSeqOrder",
                "ConfigPublishIT#tenantPublishDoesNotStealPlatformCurrent");

        fault("COMP-1", "RESERVED 后预算失败 → 审批 CANCELLED、预算无残留",
                "IT 层 + 框架层单测（正向）",
                "CapCompensationIT#approvalIsCancelledAndBudgetHasNoResidue",
                "CompensationFrameworkTest#bothActionsSucceed");

        fault("COMP-2", "预算成功后 PRE 失败 → cancel + 释放；补偿失败入队",
                "⚠️ 文档字面「PRE 失败」在实现中不可达（PRE 登记前移至审批/预算之前，"
                        + "P3 交付说明已登记该偏差）；现落点对应「预算成功后准入失败」",
                "CapCompensationIT#admissionFailureCancelsApprovalAndReleasesBudget",
                "CompensationFrameworkTest#budgetFailureEnqueuesTask",
                "CompensationFrameworkTest#approvalFailureEnqueuesTask");

        fault("COMP-3", "准入失败后释放失败 → 持久化补偿任务",
                "IT 层 + 框架层单测",
                "CapCompensationIT#failedCompensationIsPersisted",
                "CompensationFrameworkTest#bothFailuresEnqueueBothTasks");

        fault("COMP-4", "成功 + POST 失败 → SUCCEEDED + ledger 重试",
                "IT 层 + 框架层单测；与 S9 共用注入点",
                "CapCompensationIT#succeededButNotPostedIsRetrying",
                "CompensationFrameworkTest#ledgerRetryEnqueueIsIdempotent");

        fault("COMP-5", "UNKNOWN 上幂等并发重试 + 人工确认 → 仅一次终态",
                "IT 层（编排器 + 账本触发器两处）",
                "CapCompensationIT#unknownIsNotRetriedAndHasSingleTerminalState",
                "ToolLedgerReconciliationIT#attemptAfterUnknownRejectedByTrigger");

        fault("COMP-6", "确定成功 → 结果持久化失败 → 进程重启 → 落账 SUCCEEDED 且外部只执行一次",
                "IT 层（重启恢复 + 只执行一次）",
                "CapCompensationIT#restartRecoversLedgerWithoutReExecuting");
    }
}

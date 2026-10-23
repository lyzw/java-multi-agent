package cn.studykid.multiagent.testsupport.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冻结门槛的证据登记处（§8.3 三类门槛 · W-PERM-P6-T4/T6）。
 *
 * <p>⚠️ <b>为什么三类门槛也要机器化</b>：
 * §9 场景有测试方法可指，但「安全门槛」与「工程门槛」里的
 * 「防绕过 0」「RLS 跨租户隔离」「迁移双写可回退」「审计完整」
 * 这些<b>复合命题</b>，在人工填写的矩阵里很容易退化成
 * 一行「✅ 已验证」—— 那正是「冻结矩阵虚标」的高发区。
 * 故本类把每一项拆成<b>可指名的测试方法</b>，
 * 由 {@code FreezeGateEvidenceTest} 逐条回源码核对。
 *
 * <p>⚠️ <b>「防绕过 0」为什么登记成 9 条而不是 1 条</b>：
 * 07 §3 明确写明「静态扫描无法单独证明闭环」，防绕过必须由
 * <b>代码扫描 + 架构测试 + 运行时测试</b>三者共同验证，缺一结论不成立。
 * 故本类按三层分别登记 —— 只登记其中一层，就是把三证变一证。
 */
public final class FreezeGateEvidenceRegistry {

    /** 一个门槛项：编号、说明、证据落点。 */
    public record GateItem(String id, String title, String note, List<String> evidenceRefs) {

        public GateItem {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    /** 一个门槛分组。 */
    public record GateGroup(String id, String title, List<GateItem> items) {

        public GateGroup {
            items = List.copyOf(items);
        }
    }

    private static final Map<String, GateGroup> GROUPS = new LinkedHashMap<>();

    private FreezeGateEvidenceRegistry() {
    }

    /**
     * @return 全部分组（按登记顺序）
     */
    public static List<GateGroup> groups() {
        return List.copyOf(GROUPS.values());
    }

    private static void group(String id, String title, GateItem... items) {
        GROUPS.put(id, new GateGroup(id, title, List.of(items)));
    }

    static {
        // ── 功能门槛（§8.3 第一条）────────────────────────────────
        group("FUNC", "功能门槛：§9 行为测试四字段 + 具体原因码全绿",
                new GateItem("FUNC-1", "§9.1 全部 23 条行为场景的四字段与原因码",
                        "逐条证据见 Section9ScenarioRegistry；"
                                + "覆盖测试已把「场景集合 / 四字段 / 证据存在 / 证据真断言」四件事"
                                + "对着设计文档 §9.1 表格逐字核对",
                        List.of("Section9ScenarioCoverageTest#behaviorScenarioSetMatchesDesignDoc",
                                "Section9ScenarioCoverageTest#behaviorFourFieldsMatchDesignDoc",
                                "Section9ScenarioCoverageTest#everyBehaviorEvidenceAssertsItsReasonCode")),
                new GateItem("FUNC-2", "§9.2 全部 13 条不变量 / 故障注入",
                        "PUB-1/PUB-2/COMP-1~6 的证据见 Section9ScenarioRegistry",
                        List.of("Section9ScenarioCoverageTest#invariantScenarioSetMatchesDesignDoc",
                                "Section9ScenarioCoverageTest#everyEvidenceMethodExists")),
                new GateItem("FUNC-3", "正向授权与越权拒绝成对",
                        "项目安全铁律：只测「挡住所有人」证明不了「挡住该挡的人」",
                        List.of("InvocationValidatorTest#allowsAuthorizedInvocation",
                                "InvocationValidatorTest#skillNotEnabledIsDenied",
                                "CapExecutionFlowTest#successProducesExactFourFields",
                                "CapExecutionFlowTest#undeclaredToolIsDenied",
                                "ToolBypassArchitectureTest#unauthorizedToolIsNeverExecuted")));

        // ── 安全门槛（§8.3 第二条）────────────────────────────────
        group("SEC", "安全门槛：RLS / 防绕过 0 / 审批一次性与范围绑定 / MCP / fail-closed",
                new GateItem("SEC-1", "防绕过 0 · 静态扫描层",
                        "主源码中只有闸门与 CAP 编排器可调用 ToolHandler.execute",
                        List.of("ToolBypassArchitectureTest#onlyGateMayExecuteTools",
                                "ToolBypassArchitectureTest#readersMustNotExecute",
                                "ToolBypassArchitectureTest#springAiAutoExecutionIsForbidden",
                                "ToolBypassArchitectureTest#toolHandlerHoldersAreKnown")),
                new GateItem("SEC-2", "防绕过 0 · 架构/包边界层",
                        "只读持有者白名单 + agent-core 纯度（无框架依赖）",
                        List.of("ToolBypassArchitectureTest#toolHandlerHoldersAreKnown",
                                "AgentCorePurityTest#coreHasNoFrameworkDependencies",
                                "AgentCorePurityTest#coreHasNoSpringAnnotations",
                                "AgentCorePurityTest#coreExternalDepsAreMinimal")),
                new GateItem("SEC-3", "防绕过 0 · 运行时注入层",
                        "绕过闸门 → 控制面零记录；经闸门 → 记录齐全；"
                                + "另含扫描器自证（注入的绕过代码必须被检出）",
                        List.of("ToolBypassArchitectureTest#bypassingTheGateProducesNoControlPlaneRecords",
                                "ToolBypassArchitectureTest#gateMediatedCallProducesFullControlPlane",
                                "ToolBypassArchitectureTest#scannerDetectsInjectedBypass",
                                "ToolBypassArchitectureTest#scannerDoesNotFlagCompliantCode")),
                new GateItem("SEC-4", "防绕过 0 · 上游执行层",
                        "闸门层授权被拒 / 账本登记失败 → 工具零执行",
                        List.of("ToolGateAuthorizationContextTest#authorizationSeesWorkerRole",
                                "ToolGateAuthorizationContextTest#toolNotExecutedWhenAuthorizationDenied",
                                "RunExecutionLoopTest#authorizationDeniedBlocksTool",
                                "RunExecutionLoopTest#ledgerFailureBlocksTool")),
                new GateItem("SEC-5", "RLS 跨租户隔离（非特权角色验）",
                        "⚠️ 基座用 app_agent（NOSUPERUSER NOBYPASSRLS）；"
                                + "管理角色只用于造夹具",
                        List.of("CapRepositoryIT#rlsIsolatesAllTenantTables",
                                "AuthzVersionRepositoryIT#rlsIsolatesAuthzVersionRows",
                                "InvocationValidatorIT#crossTenantAgentIsNotResolvable",
                                "CapTransactionContractIT#sameTransactionDoesNotLeak",
                                "CapTransactionContractIT#crossTenantReferenceRejected",
                                "CapApprovalRepositoryIT#crossTenantApprovalIsInvisible",
                                "CapApprovalRepositoryIT#ownTenantSeesOwnApproval",
                                "CapApprovalRepositoryIT#compensationTasksAreTenantIsolated",
                                "CapRepositoryIT#tenantCannotWritePlatformRelease",
                                "ConfigOverrideRepositoryIT#crossTenantIsolation",
                                "FaultInjectionIT#rlsIsolationUnderPoolChurn",
                                "FaultInjectionIT#bindingResetsOnReusedConnection")),
                new GateItem("SEC-6", "审批一次性与范围绑定（双哈希 + 消费时重验）",
                        "范围哈希 + 有效参数哈希；参数替换重放必须被拒",
                        List.of("ApprovalScopeTest#replacedArgumentIsRejected",
                                "ApprovalScopeTest#tamperedScopeIsRejected",
                                "ApprovalStateMachineTest#replacedArgument",
                                "ApprovalStateMachineTest#consumedReplay",
                                "CapApprovalBehaviorTest#replayRejected",
                                "CapApprovalRepositoryIT#reservationIsExclusive")),
                new GateItem("SEC-7", "MCP 具名安全用例（SSRF / DNS / 重定向 / 凭据）",
                        "06 §5 逐条；含凭据脱敏与端点静态形态",
                        List.of("McpEndpointGuardTest#metadataAddressIsBlocked",
                                "McpEndpointGuardTest#privateAddressIsBlocked",
                                "McpEndpointGuardTest#rebindingIsDetected",
                                "McpEndpointGuardTest#redirectToPrivateAddressIsBlocked",
                                "McpEndpointGuardTest#redirectToMetadataIsBlocked",
                                "McpCredentialSecurityTest#credentialToStringIsRedacted",
                                "McpCredentialSecurityTest#serverDefToStringRedactsCredentialRef",
                                "McpCredentialSecurityTest#fingerprintExcludesCredentialRef",
                                "McpCredentialSecurityTest#failurePathDoesNotLeakSecret",
                                "McpAddressPolicyTest#metadataAddressesAreAlwaysForbidden",
                                "McpAddressPolicyTest#loopbackAndPrivateNeedInternalTier",
                                "McpServerConfigValidatorTest#dynamicDiscoveryIsRejected",
                                "McpServerConfigValidatorTest#stdioIsRejected",
                                "McpUntrustedOutputTest#outputIsWrappedInEnvelope",
                                "McpUntrustedOutputTest#contentIsNotRewritten")),
                new GateItem("SEC-8", "fail-closed 全场景",
                        "版本不可用 / 存储不可用 / 未知风险 / 参数策略非法",
                        List.of("InvocationValidatorTest#authzUnavailableIsDenied",
                                "InvocationValidatorTest#unknownRiskIsDeniedWithDedicatedCode",
                                "InvocationValidatorTest#paramPolicyTightenedIsDenied",
                                "CapExecutionFlowTest#unavailableAuthzVersionFailsClosed",
                                "ParamPolicyEngineTest#unknownOpIsRejected",
                                "ParamPolicyEngineTest#malformedPolicyIsRejected",
                                "ConfigValidationGateTest#unknownRiskLevelRejectsPublish",
                                "ConfigValidationGateTest#unknownRiskCeilingRejectsPublish")));

        // ── 工程门槛（§8.3 第三条）────────────────────────────────
        group("ENG", "工程门槛：迁移双写可回退 / 发布 CAS 与回滚 / 审计完整 / 故障注入",
                new GateItem("ENG-1", "迁移双写可回退",
                        "关开关 → 旧路径仍权威、零新模型行；漂移比对能识别真实漂移",
                        List.of("CapDualWriteIT#switchOffLeavesOldPathAuthoritative",
                                "CapDualWriteIT#switchOffWritesNothing",
                                "CapDualWriteIT#dualWriteProducesCapAgentDef",
                                "CapDualWriteIT#inheritsIsRejectedNotCoerced",
                                "CapDualWriteIT#comparatorReportsNoDrift",
                                "CapDualWriteIT#comparatorDetectsMissing",
                                "CapDualWriteIT#comparatorSeparatesRejectionsFromDrift")),
                new GateItem("ENG-2", "发布 CAS / 回滚（PUB-1 / PUB-2 / 回滚不恢复撤权）",
                        "含 seq 序 ≠ 提交序时指针仍以 CAS 为准",
                        List.of("ConfigPublishIT#atomicPublishPersistsAllThree",
                                "ConfigPublishIT#casFailureLeavesNoHalfPublishedState",
                                "ConfigPublishIT#pointerFollowsCasNotSeqOrder",
                                "ConfigPublishIT#rollbackCreatesNewReleaseAndSwitchesPointer",
                                "ConfigPublishIT#rollbackDoesNotRestoreRevokedAuthorization",
                                "ConfigPublishServiceTest#casFailureRollsBackEverything")),
                new GateItem("ENG-3", "审计完整（四字段 + ledger + 上下文 id 100% 结构化）",
                        "⚠️ 08 §7 的两条指标：四字段完整率 100% 且非笼统 DENY 占比 100%",
                        List.of("DecisionAuditIT#auditCoverageIsComplete",
                                "DecisionAuditIT#allowedInvocationIsFullyAudited",
                                "DecisionAuditIT#deniedInvocationIsAuditedWithSpecificReasonCode",
                                "DecisionAuditIT#everyCallIsTraceableByRunAndInvocation",
                                "DecisionAuditIT#publicationIsAudited")),
                new GateItem("ENG-4", "审计脱敏（NFR-06）",
                        "表结构层面无参数字段 → 脱敏不靠约定",
                        List.of("DecisionAuditIT#auditLogCarriesNoSensitivePayload")),
                new GateItem("ENG-5", "故障注入 COMP-1~6 全绿",
                        "含 COMP-6 崩溃恢复（重启后落账 SUCCEEDED 且外部只执行一次）",
                        List.of("CapCompensationIT#approvalIsCancelledAndBudgetHasNoResidue",
                                "CapCompensationIT#admissionFailureCancelsApprovalAndReleasesBudget",
                                "CapCompensationIT#failedCompensationIsPersisted",
                                "CapCompensationIT#succeededButNotPostedIsRetrying",
                                "CapCompensationIT#unknownIsNotRetriedAndHasSingleTerminalState",
                                "CapCompensationIT#restartRecoversLedgerWithoutReExecuting")),
                new GateItem("ENG-6", "生命周期与「未发布不可启用」",
                        "发布 = 发新版本行 + CAS 切指针；状态机非法迁移被拒",
                        List.of("ConfigLifecycleIT#unpublishedObjectCannotBeEnabled",
                                "ConfigLifecycleIT#publishedRowContentIsImmutableAndDeprecatedIsTerminal",
                                "ConfigLifecycleIT#validatedCannotBePromotedToPublishedWithoutReleaseAction",
                                "ConfigLifecycleServiceTest#publishedCannotBeReachedByColumnUpdate",
                                "ConfigValidationGateTest#unknownRiskLevelRejectsPublish")));

        // ── 硬约束 ────────────────────────────────────────────────
        group("HARD", "硬约束：Demo ≠ 验收 / 证据可追溯",
                new GateItem("HARD-1", "Demo 与平台分列，无需求误标 PLATFORM_IMPLEMENTED",
                        "追踪终态矩阵按 §10 七态填列，Demo 验证与平台成熟度分列",
                        List.of("Section9ScenarioCoverageTest#registryIsSelfConsistent")),
                new GateItem("HARD-2", "证据路径可追溯且机器可核对",
                        "「禁止手工勾选代替」：证据方法的存在性与断言内容都由测试核对",
                        List.of("Section9ScenarioCoverageTest#everyEvidenceMethodExists",
                                "Section9ScenarioCoverageTest#everyBehaviorEvidenceAssertsItsReasonCode",
                                "Section9ScenarioCoverageTest#designDocIsParseable")));
    }

    /**
     * @return 全部门槛项
     */
    public static List<GateItem> allItems() {
        List<GateItem> items = new ArrayList<>();
        GROUPS.values().forEach(g -> items.addAll(g.items()));
        return items;
    }
}

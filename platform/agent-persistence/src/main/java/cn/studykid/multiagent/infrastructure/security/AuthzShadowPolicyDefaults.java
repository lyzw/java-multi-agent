package cn.studykid.multiagent.infrastructure.security;

import java.util.List;

import cn.studykid.multiagent.core.security.Action;

import org.jspecify.annotations.NonNull;

/**
 * 影子期（P1）的<b>内置策略规则</b> —— 只覆盖「已经接了影子钩子」的那批资源类型。
 *
 * <p>⚠️ <b>为什么必须有内置规则，而不是等配置中心下发</b>：
 * {@link RuleBasedPolicyEngine} 对「无匹配规则」返回 {@code Deny(NO_RULE)}（fail-closed，正确）。
 * 但影子期一旦某资源类型没有规则，每一次查询都会落到「Gate 拒绝」这条 mismatch 上 ——
 * <b>恒定的假 mismatch 会把真实违例淹掉</b>，比不接更糟。
 * 所以接线时必须同时保证「钩子用到的每个资源类型都有对应规则」，
 * 这条对应关系由 {@code ShadowPolicyCoverageTest} 扫描源码钉死（新加资源类型不登记即红）。
 *
 * <p><b>规则来源</b>：逐条对齐 D1 基线
 * {@code docs/权限管控体系/authz/policy-mapping.yaml}（{@code agent_dev}，50 条策略，
 * 全部 {@code TENANT_ONLY}）。因此这里同域资源一律 {@code TENANT_ONLY} + {@code TENANT}。
 *
 * <p>⚠️ <b>两个例外，必须记住原因</b>：
 * <ol>
 *   <li>{@code platform.agent_trace_metric} —— 逻辑资源名（非物理表名）。其基线是 {@code NONE}
 *       （该表带 {@code tenant_id} 但<b>没有 RLS 策略</b>，属 D1 记的 9 张缺口之一）。
 *       缺口表的隔离<b>本来就只靠应用层 WHERE</b>，故期望值仍是 {@code TENANT_ONLY} ——
 *       影子期正好用它来证明「应用层谓词确实在」。</li>
 *   <li>哨兵（共享）行：平台域无哨兵模型 ⇒ {@code TENANT_WITH_SHARED} 不出现。
 *       RAG 域的哨兵规则在 {@code policy-mapping-rag.yaml}，随 RAG 侧接线（D5 后续）再引入。</li>
 *   <li>平台级目录（{@code cap_tool_def} / {@code cap_skill_def} / {@code cap_mcp_server_def}）：
 *       物理表<b>没有 {@code tenant_id} 列</b>、无 RLS ⇒ 不存在租户行归属，
 *       租户级影子<b>无从观测</b>。这类类型<b>刻意不登记</b>，而是登记在
 *       {@link #PLATFORM_LEVEL_RESOURCE_TYPES} 并在架构守卫里显式豁免 ——
 *       登记成 {@code TENANT_ONLY} 反而会制造恒定假违例。
 *       见 {@code ScopeBypassArchitectureTest} 的平台级豁免台账。</li>
 * </ol>
 */
public final class AuthzShadowPolicyDefaults {

    /**
     * 已接影子钩子的资源类型（与各仓库传入 {@code AuthzShadowHooks} 的字面量一致）。
     *
     * <p>顺序即 {@link PolicyRule} 的 id 顺序，便于日志比对。
     */
    public static final List<String> RESOURCE_TYPES = List.of(
            "agent",
            "agent_version",
            "run",
            "run_step",
            "weakness_record",
            "student_profile",
            "tool_operation",
            "budget_reservation",
            "budget_account",
            "agent_invocation",
            "audit_record",
            "config_change_request",
            "tenant_config_override",
            "run_message",
            "execution_snapshot",
            "usage_record",
            // ── CAP 域（2026-11-09 补登记）──────────────────────────────
            // ⚠️ 这 4 个类型此前**未登记**，但已接入的仓库
            //    （JdbcCapAgentDefRepository / JdbcCapAgentChildRefRepository /
            //      JdbcAuthzVersionRepository / JdbcConfigReleaseRepository）
            //    **用常量传参**调用影子钩子 ⇒ 覆盖扫描器（只认字面量）看不见它们
            //    ⇒ Gate 恒返 Deny(NO_RULE) ⇒ 每次被采样的查询都记一条
            //    「Gate 拒绝: NO_RULE」假 mismatch ⇒ AUTHZ_MISMATCH 恒非零
            //    ⇒ P1 出口门禁（连续 2 周 =0）永远达不成，真实违例被恒定噪声淹没。
            //    形状对齐 policy-mapping.yaml：四者均 TENANT_ONLY + forced=t。
            "cap_agent_def",
            "cap_agent_child_ref",
            "cap_authz_version",
            // ⚠️ cap_config_release 的读策略是 (tenant_id IS NULL) OR (tenant_id = current_tenant_id())
            //    —— 含 tenant_id IS NULL 的「平台全局行」。policy-mapping.yaml 将其归类为
            //    TENANT_ONLY；影子期沿用 yaml 口径（行级归属由结果行 tenant_id 判定，
            //    IS NULL 的全局行在染色时因 rowTenantId==null 被跳过，见 AuthzShadowHooks.taint）。
            //    ⚠️ P2 应用层翻译时必须单独处理 IS NULL 分支，否则会丢掉全局发布行。
            "cap_config_release",
            // ── 2026-11-09 第二批 CAP/治理域 ────────────────────────────
            // cap_decision_log：决策审计（08 §7）—— 高敏审计面，只存决策与上下文标识，
            // 不存明文凭据；4 处读查询均自带显式 tenant_id 谓词。
            "cap_decision_log",
            // approval：审批台（G8 · FR-014）—— 与 tool_operation 按 tenant 等值 JOIN，
            // 读路径带显式 a.tenant_id 谓词；写入路径（decide）属 P2 观测范围外。
            "approval",
            // ── 2026-11-09 第三批 CAP 域（4 仓，形态一致：1 参构造 + 显式租户谓词）──
            "cap_result_contract",
            "cap_routing_policy",
            "cap_execution_intent",
            "cap_result_event",
            // ── 2026-11-09 第四批 CAP 域（3 仓）────────────────────────
            // cap_approval：CAP 审批。⚠️ 写路径（grant / requestHumanApproval / reserve /
            // consume / cancel）用 INSERT…RETURNING，**RETURNING 也是 executeQuery**，
            // 但那是写路径 ⇒ P1 不观测（影子钩子只挂在 3 处读查询上）。
            "cap_approval",
            // cap_compensation_task：补偿任务（2 处读，均带显式租户谓词）。
            "cap_compensation_task",
            // execution_capability_snapshot：执行期能力快照（1 处读）。
            "execution_capability_snapshot",
            // ── 2026-11-09 第五批（A 组剩余读路径）─────────────────
            // cap_config_publish_request：配置变更申请（第三十四批起支持 PLATFORM 作用域）。
            // 读路径用 `tenant_id IS NOT DISTINCT FROM ?::uuid`（兼容平台级 NULL 行）；
            // 影子期沿用 TENANT_ONLY 口径，平台 NULL 行在染色时因 rowTenantId==null 被跳过。
            "cap_config_publish_request",
            // idempotency_record：幂等记录（Run 创建原子写入的一部分）。读带显式 tenant_id 谓词。
            "idempotency_record",
            // outbox_event：唤醒信号表（Run 创建写、Worker 读）。读带显式 tenant_id 谓词。
            "outbox_event",
            // ── 2026-11-09 第六批（A 组：删除/撤回域租户读，P4-D04）──────
            // consent_withdrawal：监护人撤回同意。读（findDueWithdrawals）带显式 tenant_id 谓词。
            "consent_withdrawal",
            // erasure_certificate：删除凭证。读（findCertificates）带显式 tenant_id 谓词
            //   （SELECT 已补 tenant_id::text 供逐行染色取行归属）。
            "erasure_certificate",
            // 逻辑资源名（物理表 agent_trace_metric，基线 scope=NONE 的缺口表）
            "platform.agent_trace_metric");

    /**
     * <b>平台级目录</b>：<b>刻意不纳入</b> {@link #RESOURCE_TYPES} 的资源类型。
     *
     * <p>判定依据（不是「懒得登记」）：
     * <ul>
     *   <li>物理表<b>没有 {@code tenant_id} 列</b>（{@code cap_tool_def} /
     *       {@code cap_skill_def} / {@code cap_mcp_server_def}）—— 没有行归属，
     *       「染色」在语义上无从谈起；</li>
     *   <li>policy-mapping.yaml 中<b>没有它们的策略条目</b>（无 RLS），
     *       属平台域对象，按 {@code platform.resource.read} 等平台权限走带外运维通道；</li>
     *   <li>它们的连接走 {@code platformContext()}（平台上下文），与租户上下文无关。</li>
     * </ul>
     *
     * <p>⚠️ <b>为什么不能简单登记成 TENANT_ONLY</b>：那会要求 Gate 认为这些行有租户归属，
     * 而它们根本没有 ⇒ 登记即制造恒定假违例（同 NO_RULE 噪声）。
     * <p>⚠️ 这些类型的仓储<b>不得持有 {@code ScopedDataAccess}</b>：持有却不观测
     * 就是「装饰性接线」，会让 P1 的 {@code AUTHZ_MISMATCH=0} 变成假绿。
     * 见 {@code ScopeBypassArchitectureTest} 的平台级豁免台账。
     */
    public static final List<String> PLATFORM_LEVEL_RESOURCE_TYPES = List.of(
            "cap_tool_def",
            "cap_skill_def",
            "cap_mcp_server_def");

    private AuthzShadowPolicyDefaults() {
    }

    /**
     * 内置规则集：{@code RETRIEVE + TENANT + TENANT_ONLY}。
     *
     * <p>⚠️ 动作只有 {@code RETRIEVE}：影子钩子当前只观测「读路径」
     * （{@code AuthzShadowHooks} 内部固定 {@code Action.RETRIEVE}）。
     * 写路径的隔离由 RLS 的 {@code WITH CHECK} 与各仓 {@code WHERE tenant_id} 保证，
     * 属 P2 双写期的接入范围 —— <b>不要</b>在此假装覆盖。
     */
    public static @NonNull List<PolicyRule> rules() {
        return RESOURCE_TYPES.stream()
                .map(t -> new PolicyRule("shadow-" + t, t, Action.RETRIEVE,
                        ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY))
                .toList();
    }

    /** 内置规则直接构成的策略存储（构造期校验 R3 不变式）。 */
    public static @NonNull PolicyStore store() {
        return new InMemoryPolicyStore(rules());
    }
}

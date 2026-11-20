package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 数据层越权「防绕过」架构守卫（12 文 §4.5 / D7 · 13 文 §3 红线 1）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：退役 RLS 后，数据库层不再兜底，
 * 「应用层漏写过滤」直接等于越权。类型系统（`ScopeFilter` 无 null 路径）只能保证
 * <b>拿到 filter 之后</b>不会退化，<b>拿不到 filter</b> 的那条路径它管不住。
 * 因此必须由代码扫描把三条可证伪命题钉死：
 *
 * <ol>
 *   <li><b>R3 不变式</b>：`ScopeFilter.Unrestricted` 的产出点<b>唯一</b>，
 *       且只可能是策略引擎在确认「主体是平台运维」之后才产出。
 *       任何业务代码直接 new 一个 `Unrestricted` 就是对隔离的彻底绕过。</li>
 *   <li><b>接线诚实性</b>：一个仓库如果声明自己「已接 Gate」（持有
 *       {@code ScopedDataAccess}），那它必须<b>真的</b>调用了影子钩子 ——
 *       只注入不调用的接线是装饰，影子期会给出「0 mismatch」的假绿。</li>
 *   <li><b>台账完整性</b>：新增的 Gate 持有者必须显式登记。
 *       未登记的持有者既不会被扫描，也不会被评审，是漏过滤的标准入口。</li>
 * </ol>
 *
 * <p>每条断言都配一个<b>反证用例</b>：把违规代码喂给扫描器，必须变红。
 * 若扫描器恒返回 false（正则写错 / 剥离逻辑吃掉全部代码），
 * 这些用例会先红 —— 避免「看起来在防守、实际什么都没检查」的假绿。
 */
class ScopeBypassArchitectureTest {

    /** `Unrestricted` 的唯一合法产出点（策略引擎，且前置 isPlatformOps 校验）。 */
    private static final Set<String> UNRESTRICTED_PRODUCERS = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/RuleBasedPolicyEngine.java");

    /**
     * {@code SubjectContext} 的唯一合法构造点（WP-M1-4 · 12 文 §2.2）。
     *
     * <p>⚠️ <b>为什么只许记录自身</b>：{@code scope} 是「平台全域放行」的唯一开关，
     * 产出 {@code Scope.PLATFORM} 的唯一路径是<b>运维通道认证入口</b>。
     * 把构造点钉死在工厂所在的记录自身，意味着当前主代码里
     * <b>没有任何业务/接口代码能以任意 scope 构造主体</b>（{@code forTenant}
     * 硬编码 {@code TENANT}）。将来运维通道入口接入时，必须把该文件
     * 显式登记进本清单并过评审 —— 与 {@link #UNRESTRICTED_PRODUCERS} 同一纪律。
     */
    private static final Set<String> SUBJECT_CONTEXT_CONSTRUCTORS = Set.of(
            "agent-core/src/main/java/cn/studykid/multiagent/core/tenant/SubjectContext.java");

    /**
     * 已接 Gate 的仓库台账（D5 逐域改造进度）。
     *
     * <p>登记条件：持有 {@code ScopedDataAccess} 字段。
     * 一旦登记，必须<b>真的</b>在查询路径调用 {@code AuthzShadowHooks.*}；
     * 否则影子比对永远不跑，P1 的 `AUTHZ_MISMATCH=0` 毫无信息量。
     *
     * <p>⚠️ <b>「定义辅助方法」不算「已接」</b>（2026-11-09 实测）：辅助方法体里
     * 天然含有 {@code AuthzShadowHooks.compare(...)}，只看这一句会把「装饰性接线」放过去。
     * 故本台账额外要求<b>查询路径上有真实调用点</b>（见
     * {@code integratedRepositoriesActuallyInvokeHooks}）。
     *
     * <p>⚠️ <b>平台级目录不在本台账</b>：{@code cap_tool_def} / {@code cap_skill_def} /
     * {@code cap_mcp_server_def} 三张表<b>没有 {@code tenant_id} 列</b>、无 RLS ⇒ 没有行归属，
     * 染色无从谈起；它们<b>刻意不持有 {@code ScopedDataAccess}</b>，走平台级运维通道。
     */
    private static final Set<String> INTEGRATED = Set.of(
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentMetricRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcBudgetRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcResultQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunListQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAuthzVersionRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapAgentChildRefRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapAgentDefRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigReleaseRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcDelegationBudgetRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcUnknownOperationRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunEventQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcProfileQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunInputRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunBudgetRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcToolOperationLedger.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAgentInvocationRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigOverrideRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAuditQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigChangeRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcTraceReplayRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcTraceFactRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcDecisionAuditRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcApprovalQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapResultContractRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapRoutingPolicyRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcExecutionIntentRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcResultEventRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapApprovalRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCompensationTaskRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapabilitySnapshotRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAuditRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcApprovalRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigPublishRequestRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunCreationRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunStepRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunMessageRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunClaimRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcExecutionSnapshotResolver.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentChatAuditRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunEventAppendRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRateLimiter.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAgentReleaseArchiveRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentManageRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/statistics/JdbcStatisticsRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcErasureRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRiskEventRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigLifecycleRepository.java",
            // ── W2/W3 MyBatis-Plus 适配层（同一 Gate 契约，逐个与旧实现等价）──
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisConfigChangeRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisConfigOverrideRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisConfigPublishRequestRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisRunEventAppendRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisRunInputRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisAgentInvocationRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisAuthzVersionRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCapAgentChildRefRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCapAgentDefRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCapResultContractRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCapRoutingPolicyRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCapabilitySnapshotRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisCompensationTaskRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisConfigLifecycleRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisConfigReleaseRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisExecutionIntentRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/MybatisResultEventRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisAgentManageRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisAgentQueryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/MybatisProfileQueryRepository.java");

    /**
     * 平台级 / 混合作用域仓储（刻意<b>不</b>接 Gate、<b>不</b>登记为已接）：
     * 这些物理表要么<b>没有 {@code tenant_id} 列</b>（{@code sys_dictionary} /
     * {@code tenant} / {@code model_catalog} / {@code provider_credential} /
     * {@code review_task} / 三个平台级 CAP 目录），要么<b>走平台 schema / 运维连接</b>
     * （{@code cap_config_pointer} 的 CAS 指针用 {@code pointer_key} 编码 scope+tenant，
     * {@code JdbcPlatformAgentDirectoryRepository} 做跨租户列举）。
     * 它们不存在「逐行租户归属」可供染色，故走平台级运维通道，P1 影子期不观测。
     *
     * <p>⚠️ <b>若将来要观测其中某个</b>：必须先登记资源类型 + 接钩子，并把对应文件
     * 从本豁免名单移到 {@link #INTEGRATED}，否则 {@code scopedHoldersAreRegistered}
     * 会把它当成「漏登记的 Gate 持有者」而报错。
     */
    private static final Set<String> PLATFORM_LEVEL_EXEMPT = Set.of(
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigPointerRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcPlatformAgentDirectoryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcDictionaryRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcPlatformTenantRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcModelCatalogRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcProviderCredentialRepository.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcReviewTaskRepository.java");

    /** 影子钩子的调用形态（静态入口，避免逐仓重复实现）。 */
    private static final Pattern HOOK_CALL =
            Pattern.compile("AuthzShadowHooks\\s*\\.\\s*(compare|taint)\\s*\\(");

    /**
     * 仓库侧影子辅助方法的<b>方法声明</b>形态。
     *
     * <p>⚠️ <b>为什么必须把「声明」和「调用」分开</b>（2026-11-09 实测踩坑）：
     * {@code HOOK_CALL} 匹配的是 {@code AuthzShadowHooks.compare(...)}，它出现在
     * <b>辅助方法体内部</b>。一个仓只要<b>定义</b>了 {@code void shadowCompare(...)}
     * 辅助方法，就算一个查询路径都没调用，也照样能通过「接线诚实性」检查 ——
     * 这正是 3 个 CAP 平台目录仓（cap_tool_def / cap_skill_def / cap_mcp_server_def）
     * 曾经的形态：持有 Gate、登记在台账、辅助方法齐备，<b>但查询路径从不调用</b> ⇒
     * 零观测 ⇒ 影子期 {@code AUTHZ_MISMATCH=0} 是<b>假绿</b>。
     *
     * <p>本仓的辅助方法一律声明为 {@code void} 且方法体只调用
     * {@code AuthzShadowHooks.*}（不递归调用自身），因此
     * 「去掉声明头后剩余的 {@code shadowCompare/shadowTaint/shadowEnter} 出现次数」
     * 就是真实的查询路径调用点数。
     */
    private static final Pattern HOOK_DECLARATION =
            Pattern.compile("\\bvoid\\s+shadow(?:Compare|Taint|Enter)\\s*\\(");

    /** 仓库侧影子辅助方法的调用形态（去掉方法声明后即为真实调用点）。 */
    private static final Pattern HOOK_INVOCATION =
            Pattern.compile("\\bshadow(?:Compare|Taint|Enter)\\s*\\(");

    /** `Unrestricted` 构造形态。 */
    private static final Pattern UNRESTRICTED_NEW =
            Pattern.compile("new\\s+ScopeFilter\\s*\\.\\s*Unrestricted\\s*\\(");

    /** {@code SubjectContext} 的直接构造 / 工厂调用形态（WP-M1-4）。 */
    private static final Pattern SUBJECT_CONSTRUCT =
            Pattern.compile("\\bnew\\s+SubjectContext\\s*\\(|\\bSubjectContext\\s*\\.\\s*from\\s*\\(");

    /** 持有 `ScopedDataAccess` 的形态（字段 / 参数 / 局部变量声明）。 */
    private static final Pattern SCOPED_HOLDER =
            Pattern.compile("\\bScopedDataAccess\\b\\s+[A-Za-z_]");

    // ── 静态扫描 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ Unrestricted 的唯一产出点是策略引擎（R3 不变式）")
        void unrestrictedIsOnlyProducedByPolicyEngine() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!UNRESTRICTED_NEW.matcher(code).find()) {
                    continue;
                }
                String rel = relative(file);
                if (!UNRESTRICTED_PRODUCERS.contains(rel)) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 主源码中除策略引擎外不得构造 Unrestricted："
                            + "它等价于「不受任何租户约束」，业务代码一旦拿到就是全量越权")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ SubjectContext 构造点唯一（scope 推导反证 · WP-M1-4）")
        void subjectContextConstructionIsCentralized() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!SUBJECT_CONSTRUCT.matcher(code).find()) {
                    continue;
                }
                String rel = relative(file);
                if (!SUBJECT_CONTEXT_CONSTRUCTORS.contains(rel)) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 主源码中除工厂所在记录外不得构造 SubjectContext："
                            + "scope 是「平台全域放行」的唯一开关，产出 Scope.PLATFORM 的唯一路径"
                            + "是运维通道认证入口 —— 新构造点必须显式登记进许可清单并过评审，"
                            + "否则租户请求可能伪造 scope=PLATFORM 拿到 Unrestricted")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 已接 Gate 的仓库必须真的调用影子钩子（接线诚实性）")
        void integratedRepositoriesActuallyInvokeHooks() {
            List<String> violations = new ArrayList<>();

            for (String rel : INTEGRATED) {
                Path file = platformRoot().resolve(rel);
                if (!Files.exists(file)) {
                    violations.add(rel + "（文件不存在，台账漂移）");
                    continue;
                }
                String code = stripCommentsAndStrings(read(file));
                if (!HOOK_CALL.matcher(code).find()) {
                    violations.add(rel + "（持有 ScopedDataAccess 但从不调用 AuthzShadowHooks）");
                    continue;
                }
                // ⚠️ 仅「辅助方法体内有 AuthzShadowHooks 调用」不够：
                //    必须存在<b>查询路径上的真实调用点</b>，否则是装饰性接线。
                String withoutDeclarations = HOOK_DECLARATION.matcher(code).replaceAll("void __decl__(");
                if (!HOOK_INVOCATION.matcher(withoutDeclarations).find()) {
                    violations.add(rel + "（只定义了影子辅助方法、查询路径从不调用 ⇒ 装饰性接线，影子期必然假绿）");
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 「注入但不调用」的接线是装饰：影子期会给出 0 违规的假绿，"
                            + "而 P1 出口条件正是依赖这个 0")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 已接 Gate 的仓库不得是平台级对象（无 tenant_id ⇒ 染色无从谈起）")
        void integratedRepositoriesAreNotPlatformLevel() {
            // 平台级目录表（cap_tool_def / cap_skill_def / cap_mcp_server_def）没有 tenant_id 列，
            // 登记成「已接 Gate」等于宣称一个不存在的租户维度。
            List<String> platformLevel = List.of(
                    "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapToolDefRepository.java",
                    "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapSkillDefRepository.java",
                    "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapMcpServerDefRepository.java");

            List<String> registered = platformLevel.stream()
                    .filter(INTEGRATED::contains)
                    .toList();

            assertThat(registered)
                    .as("⚠️ 平台级目录没有 tenant_id，登记进台账会让人误以为它们受租户影子覆盖")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 平台级/混合作用域仓储不得持有 ScopedDataAccess（否则会假装受租户影子覆盖）")
        void platformLevelRepositoriesDoNotHoldScopedDataAccess() {
            List<String> violations = new ArrayList<>();
            for (String rel : PLATFORM_LEVEL_EXEMPT) {
                Path file = platformRoot().resolve(rel);
                if (!Files.exists(file)) {
                    violations.add(rel + "（豁免名单漂移：文件不存在）");
                    continue;
                }
                String code = stripCommentsAndStrings(read(file));
                if (SCOPED_HOLDER.matcher(code).find()) {
                    violations.add(rel + "（平台级/混合作用域对象不应持有 ScopedDataAccess）");
                }
            }
            assertThat(violations)
                    .as("平台级/混合作用域仓储刻意不接 Gate：它们没有租户行归属，"
                            + "持有 ScopedDataAccess 会制造恒定假违例（同 NO_RULE 噪声）")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 持有 ScopedDataAccess 的类必须登记在台账内（防漏过滤入口）")
        void scopedHoldersAreRegistered() {
            List<String> unknown = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!SCOPED_HOLDER.matcher(code).find()) {
                    continue;
                }
                String rel = relative(file);
                if (INTEGRATED.contains(rel) || NON_REPO_SURFACES.contains(rel)
                        || isScopeInfrastructure(rel)) {
                    continue;
                }
                unknown.add(rel);
            }

            assertThat(unknown)
                    .as("⚠️ 新增的 Gate 持有者必须显式登记：要么进 INTEGRATED 台账并真的调用钩子，"
                            + "要么进 NON_REPO_SURFACES 并说明豁免理由")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 台账无漂移：登记的每个文件都真实存在")
        void inventoryHasNoDrift() {
            List<String> missing = new ArrayList<>();
            for (String rel : INTEGRATED) {
                if (!Files.exists(platformRoot().resolve(rel))) {
                    missing.add(rel);
                }
            }
            for (String rel : UNRESTRICTED_PRODUCERS) {
                if (!Files.exists(platformRoot().resolve(rel))) {
                    missing.add(rel);
                }
            }
            for (String rel : SUBJECT_CONTEXT_CONSTRUCTORS) {
                if (!Files.exists(platformRoot().resolve(rel))) {
                    missing.add(rel);
                }
            }

            assertThat(missing)
                    .as("台账里的路径必须真实存在，否则守卫在保护一个已经不存在的文件（静默失效）")
                    .isEmpty();
        }
    }

    // ── 扫描器自证（反证） ────────────────────────────────────────

    @Nested
    @DisplayName("扫描器自证")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️ 注入的业务侧 Unrestricted 必须被检出")
        void detectsInjectedUnrestricted() {
            String violating = """
                    package evil;
                    import cn.studykid.multiagent.core.security.ScopeFilter;
                    class Evil {
                        ScopeFilter leak() {
                            return new ScopeFilter.Unrestricted("方便一下");
                        }
                    }
                    """;

            assertThat(UNRESTRICTED_NEW.matcher(stripCommentsAndStrings(violating)).find())
                    .as("⚠️⚠️ 注入的越权构造必须被检出，否则 R3 守卫是空转")
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️⚠️ 业务代码里伪造 SubjectContext 构造必须被检出（WP-M1-4 反证）")
        void detectsInjectedSubjectConstruction() {
            String violating = """
                    package evil;
                    import cn.studykid.multiagent.core.security.Scope;
                    import cn.studykid.multiagent.core.tenant.SubjectContext;
                    import cn.studykid.multiagent.core.tenant.TenantContext;
                    class EvilAuth {
                        SubjectContext forge(TenantContext ctx) {
                            return SubjectContext.from(ctx, Scope.PLATFORM);
                        }
                    }
                    """;

            assertThat(SUBJECT_CONSTRUCT.matcher(stripCommentsAndStrings(violating)).find())
                    .as("⚠️⚠️ 业务侧伪造 SubjectContext 构造必须被检出，"
                            + "否则 scope 推导守卫是空转")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ SubjectContext.forTenant（硬编码租户态）不误报（负样本）")
        void doesNotFlagForTenantFactory() {
            String compliant = """
                    package ok;
                    import cn.studykid.multiagent.core.tenant.SubjectContext;
                    class OkRepository {
                        void shadow(String tenantId) {
                            var subject = SubjectContext.forTenant(tenantId);
                        }
                    }
                    """;

            assertThat(SUBJECT_CONSTRUCT.matcher(stripCommentsAndStrings(compliant)).find())
                    .as("forTenant 是硬编码 TENANT 的合法工厂，不得被误判")
                    .isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ 注入的「持有 Gate 但从不调用钩子」必须被检出")
        void detectsSilentIntegration() {
            String violating = """
                    package evil;
                    class EvilRepository {
                        private final ScopedDataAccess scoped;
                        EvilRepository(ScopedDataAccess scoped) { this.scoped = scoped; }
                        void read() { /* 只注入，从不调用 AuthzShadowHooks */ }
                    }
                    """;

            String code = stripCommentsAndStrings(violating);
            assertThat(SCOPED_HOLDER.matcher(code).find())
                    .as("必须识别出 Gate 持有者")
                    .isTrue();
            assertThat(HOOK_CALL.matcher(code).find())
                    .as("⚠️⚠️ 未调用钩子的接线必须被判为未接线")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 合规接线不误报（负样本）")
        void doesNotFlagCompliantIntegration() {
            String compliant = """
                    package ok;
                    class OkRepository {
                        private final ScopedDataAccess scoped;
                        void read() {
                            AuthzShadowHooks.compare(scoped, reporter, true, "agent",
                                    subject, tenantId, true);
                        }
                    }
                    """;

            String code = stripCommentsAndStrings(compliant);
            assertThat(HOOK_CALL.matcher(code).find())
                    .as("正常调用钩子不得被误判")
                    .isTrue();
            assertThat(UNRESTRICTED_NEW.matcher(code).find())
                    .as("正常代码不得被误判为越权构造")
                    .isFalse();
        }

        @Test
        @DisplayName("⚠️⚠️ 注入的「只定义辅助方法、查询路径从不调用」必须被检出")
        void detectsDecorativeIntegration() {
            // 2026-11-09 实测形态：3 个 CAP 平台目录仓持有 Gate、辅助方法齐备，
            // 但查询路径一个都没调用 ⇒ 零观测 ⇒ AUTHZ_MISMATCH=0 是假绿。
            String decorative = """
                    package evil;
                    class DecorativeRepository {
                        private final ScopedDataAccess scoped;
                        DecorativeRepository(ScopedDataAccess scoped) { this.scoped = scoped; }
                        void shadowCompare(String resourceType, String tenantId, boolean hasPredicate) {
                            AuthzShadowHooks.compare(scoped, reporter, true, resourceType,
                                    subject, tenantId, hasPredicate);
                        }
                        void read() { }
                    }
                    """;

            String code = stripCommentsAndStrings(decorative);
            assertThat(HOOK_CALL.matcher(code).find())
                    .as("辅助方法体内确有 AuthzShadowHooks 调用 —— 旧检查会误放行")
                    .isTrue();

            String withoutDeclarations = HOOK_DECLARATION.matcher(code).replaceAll("void __decl__(");
            assertThat(HOOK_INVOCATION.matcher(withoutDeclarations).find())
                    .as("⚠️⚠️ 只定义不调用必须被判为装饰性接线，否则 P1 出口门禁拿到的是假绿")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 真实调用不会被误判为装饰性接线（负样本）")
        void doesNotFlagRealInvocation() {
            String real = """
                    package ok;
                    class RealRepository {
                        private final ScopedDataAccess scoped;
                        void shadowCompare(String resourceType, String tenantId, boolean hasPredicate) {
                            AuthzShadowHooks.compare(scoped, reporter, true, resourceType,
                                    subject, tenantId, hasPredicate);
                        }
                        void read() {
                            shadowCompare("agent", tenantId, true);
                        }
                    }
                    """;

            String code = stripCommentsAndStrings(real);
            String withoutDeclarations = HOOK_DECLARATION.matcher(code).replaceAll("void __decl__(");
            assertThat(HOOK_INVOCATION.matcher(withoutDeclarations).find())
                    .as("查询路径确有调用点，不得被误判为装饰性接线")
                    .isTrue();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    /**
     * 豁免名单：持有 `ScopedDataAccess` 但<b>不是「仓库」</b>的基础设施 / 装配类。
     *
     * <p>这些类提供接缝本身（被仓库注入），不直接查询业务表。
     */
    private static final Set<String> NON_REPO_SURFACES = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/DefaultAccessGate.java",
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/security/AuthzShadowHooks.java",
            // P1 影子的装配层：构造 ScopedDataAccess 并交给各仓库，
            // 自身不查询任何业务表（装配 ≠ 数据访问面）。
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/AuthzShadowConfiguration.java");

    /** 鉴权层自身（接缝与门禁实现），不属于数据访问面。 */
    private static boolean isScopeInfrastructure(String rel) {
        // 2026-11-06 数据库层收敛：影子鉴权接缝的一部分随 JDBC 仓储迁入
        // agent-persistence，两个模块的 security 包同属「非数据访问面」。
        String securityPackage = "src/main/java/cn/studykid/multiagent/infrastructure/security/";
        return (rel.startsWith("agent-infrastructure/" + securityPackage)
                    || rel.startsWith("agent-persistence/" + securityPackage))
                && !rel.endsWith("Test.java")
                && (rel.endsWith("AccessGate.java")
                    || rel.endsWith("ScopedDataAccess.java")
                    || rel.endsWith("AuthzShadowReporter.java")
                    || rel.endsWith("AuthzShadowBinding.java")
                    || rel.endsWith("AuthzSamplingPolicy.java")
                    || rel.endsWith("AuthzShadowPolicyDefaults.java")
                    || rel.endsWith("AuthzMismatchMetrics.java")
                    || rel.endsWith("InMemoryAuthzMismatchMetrics.java")
                    || rel.endsWith("Slf4jAuthzMismatchMetrics.java")
                    || rel.endsWith("AuthzDecisionRecorder.java")
                    || rel.endsWith("NoOpAuthzDecisionRecorder.java")
                    || rel.endsWith("InMemoryAuthzDecisionRecorder.java")
                    || rel.endsWith("JdbcAuthzDecisionLog.java")
                    || rel.endsWith("PolicyStore.java")
                    || rel.endsWith("InMemoryPolicyStore.java")
                    || rel.endsWith("PolicyRule.java")
                    || rel.endsWith("RuleBasedPolicyEngine.java")
                    || rel.endsWith("SubjectContextResolver.java")
                    || rel.endsWith("AccessDeniedException.java")
                    || rel.endsWith("IllegalPolicyException.java")
                    || rel.endsWith("ScopeRequirement.java")
                    || rel.endsWith("ScopeEffect.java"));
    }

    private static List<Path> mainSourceFiles() {
        List<Path> files = new ArrayList<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    tree.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描主源码失败: " + root, e);
        }
        return files;
    }

    /** 定位 platform 源码根目录（从当前工作目录向上找含 {@code agent-core} 的目录）。 */
    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    private static String relative(Path file) {
        return platformRoot().relativize(file.toAbsolutePath()).toString()
                .replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 去掉注释与字符串字面量，只留<b>可执行代码</b>。
     *
     * <p>⚠️ 必须连字符串一起去掉：本仓大量 Javadoc 与错误提示里写着
     * {@code Unrestricted} / {@code ScopedDataAccess}，只去注释会把这些
     * 「只是提到这个词」误判成「真的构造 / 真的持有」。
     */
    private static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"'
                        && src.charAt(i + 2) == '"')) {
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append(' ');
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('"');
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('\'');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}

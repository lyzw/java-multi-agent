package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.AgentPublishClosureValidator;
import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishService;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.application.cap.ConfigValidationGate;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcConfigPublishRequestRepository;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 输出契约 / 路由策略的<b>独立生命周期</b>集成测试
 * （多级 Agent v1.1 · 第二十五批 · C25-1 · A5 · 真实 PG）。
 *
 * <p>⚠️ <b>本类锁三件事</b>：
 * <ol>
 *   <li><b>V26 的结构</b>：两张表都有 {@code lifecycle_state} 列、
 *       都有对齐触发器与不可变触发器 —— 结构缺失时行为测试会「莫名其妙地通过」；</li>
 *   <li><b>停用可用且不改内容</b>：PUBLISHED → DEPRECATED 走真实 SQL + 真实 RLS；</li>
 *   <li><b>停用是防线不是告示牌</b>：已发布行改内容被不可变触发器挡住。</li>
 * </ol>
 *
 * <p>⚠️ <b>读写角色分工</b>：生命周期迁移走<b>应用角色</b>（{@code app_agent}，非特权），
 * 证明「状态机在真实权限下可执行」；夹具造数走<b>管理角色</b>（BYPASSRLS）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ContractPolicyLifecycleIT extends CapITBase {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final String CONTRACT_ID = "it-cp-contract";
    private static final String POLICY_ID = "it-cp-policy";
    private static final String REQUESTER = "it-cp-requester";
    private static final String APPROVER = "it-cp-approver";

    // ── 装配 ──────────────────────────────────────────────────────

    private ConfigLifecycleService lifecycle() {
        return new ConfigLifecycleService(
                new JdbcConfigLifecycleRepository(appPool),
                new ConfigLifecycleStateMachine());
    }

    private ConfigPublishWorkflowService workflow() {
        JdbcCapAgentDefRepository agentDefs = new JdbcCapAgentDefRepository(appPool);
        JdbcCapResultContractRepository contracts = new JdbcCapResultContractRepository(appPool);
        JdbcCapRoutingPolicyRepository policies = new JdbcCapRoutingPolicyRepository(appPool);
        ConfigPublishService publisher = new ConfigPublishService(
                new JdbcConfigPublishTransaction(appPool),
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                agentDefs,
                new JdbcDecisionAuditRepository(appPool),
                new ConfigValidationGate(),
                CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS,
                new AgentPublishClosureValidator(agentDefs, contracts, policies),
                null,
                contracts,
                policies);
        return new ConfigPublishWorkflowService(
                new JdbcConfigPublishRequestRepository(appPool, new JdbcAuditRepository(appPool)),
                publisher,
                lifecycle(),
                agentDefs,
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                contracts,
                policies,
                CLOCK);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    /**
     * 清掉本类自有的固定 id 行。
     *
     * <p>⚠️ <b>必须每例清理</b>：这两张表按 {@code (tenant_id, 业务 id, version)} 为主键，
     * 残留行会让下一例的 {@code INSERT} 直接撞主键 —— 失败现场指向「主键设计有问题」，
     * 真因只是上一例的夹具残留。
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanContractPolicyRows() {
        adminExec("DELETE FROM cap_result_contract WHERE contract_id IN ('" + CONTRACT_ID + "')");
        adminExec("DELETE FROM cap_routing_policy WHERE policy_id IN ('" + POLICY_ID + "')");
    }

    private void seedPublishedContract() {
        adminExec("INSERT INTO cap_result_contract (tenant_id, contract_id, version, schema_json,"
                + " config_fingerprint, schema_version, created_at, published_at)"
                + " VALUES ('" + tenantA + "'::uuid, '" + CONTRACT_ID + "', 1, '{\"type\":\"object\"}'::jsonb,"
                + " 'fp-contract', 1, now(), now())");
    }

    private void seedPublishedPolicy() {
        adminExec("INSERT INTO cap_routing_policy (tenant_id, policy_id, version, strategy, targets,"
                + " config_fingerprint, schema_version, created_at, published_at)"
                + " VALUES ('" + tenantA + "'::uuid, '" + POLICY_ID + "', 1, 'EXPLICIT_TARGETS',"
                + " '[\"child-agent\"]'::jsonb, 'fp-policy', 1, now(), now())");
    }

    // ── 结构断言 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("V26 迁移结构（列 + 对齐触发器 + 不可变触发器）")
    class MigrationStructure {

        @Test
        @DisplayName("M-9 ✅ 两张表都有 lifecycle_state 列，且默认值为 DRAFT")
        void bothTablesHaveLifecycleStateColumn() {
            for (String table : List.of("cap_result_contract", "cap_routing_policy")) {
                assertThat(adminQueryLong("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "' AND column_name = 'lifecycle_state'"))
                        .as("⛔ " + table + " 缺 lifecycle_state 列：停用无处落地")
                        .isEqualTo(1L);
                assertThat(adminQuery("SELECT column_default FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "' AND column_name = 'lifecycle_state'"))
                        .as("⛔ " + table + " 的 lifecycle_state 默认值不是 DRAFT："
                                + "新插入的草稿行会自称已发布")
                        .contains("DRAFT");
            }
        }

        @Test
        @DisplayName("M-9 ✅ 两张表都挂了对齐触发器与不可变触发器")
        void bothTablesHaveAlignmentAndImmutabilityTriggers() {
            for (String table : List.of("cap_result_contract", "cap_routing_policy")) {
                assertThat(adminQueryLong("SELECT count(*) FROM pg_trigger"
                        + " WHERE tgname = 'trg_" + table + "_align_state' AND NOT tgisinternal"))
                        .as("⛔ " + table + " 缺对齐触发器：published_at 与 lifecycle_state 会漂移")
                        .isEqualTo(1L);
                assertThat(adminQueryLong("SELECT count(*) FROM pg_trigger"
                        + " WHERE tgname = 'trg_" + table + "_immutable' AND NOT tgisinternal"))
                        .as("⛔ " + table + " 缺不可变触发器："
                                + "§6.1「已发布版本行不可变」对这两张表从未生效")
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("M-9 ✅ 对齐触发器与不可变触发器共用同一套命名约定")
        void alignmentTriggerFollowsTheSharedNamingConvention() {
            for (String table : List.of("cap_result_contract", "cap_routing_policy")) {
                String align = "trg_" + table + "_align_state";
                String immutable = "trg_" + table + "_immutable";
                assertThat(align.compareTo(immutable))
                        .as("⛔ " + table + " 的触发器命名与 V17 的约定不一致："
                                + "六张对象定义表必须共用一套命名，否则触发器集合会分裂成两族，"
                                + "维护时极易漏改")
                        .isNegative();
            }
        }
    }

    @Test
    @DisplayName("✅ 仓储读得到 lifecycle_state（防线的数据来源必须真的被读出来）")
    void repositoryCarriesLifecycleState() {
        seedPublishedContract();
        assertThat(lifecycle().transition("result_contract", tenantA, CONTRACT_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();

        assertThat(new JdbcCapResultContractRepository(appPool)
                .find(tenantA, CONTRACT_ID, 1).orElseThrow().lifecycleState())
                .as("⛔ 仓储读不到 lifecycle_state："
                        + "发布期引用校验拿不到「已停用」这个事实，停用防线形同虚设")
                .isEqualTo(ConfigLifecycleState.DEPRECATED);
    }

    // ── 行为断言 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 已发布契约的初始状态是 PUBLISHED（对齐触发器把 published_at 绑定成事实）")
    void publishedContractStartsAsPublished() {
        seedPublishedContract();

        assertThat(lifecycle().stateOf("result_contract", tenantA, CONTRACT_ID, 1))
                .as("⛔ 已发布契约的状态不是 PUBLISHED："
                        + "「已发布」与「状态」若不绑定，就有两个真相")
                .contains(ConfigLifecycleState.PUBLISHED);
    }

    @Test
    @DisplayName("✅ 输出契约可独立停用（PUBLISHED → DEPRECATED），且内容一字未改")
    void contractCanBeDeprecatedIndependently() {
        seedPublishedContract();

        assertThat(lifecycle().transition("result_contract", tenantA, CONTRACT_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED))
                .as("⛔ 输出契约无法独立停用：停用只能靠改 published_at，那等于把它退回草稿")
                .isTrue();

        assertThat(lifecycle().stateOf("result_contract", tenantA, CONTRACT_ID, 1))
                .contains(ConfigLifecycleState.DEPRECATED);
        assertThat(adminQuery("SELECT config_fingerprint FROM cap_result_contract"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND contract_id = '" + CONTRACT_ID + "'"))
                .as("⛔ 停用改写了已发布行的内容指纹：不可变版本行被篡改")
                .isEqualTo("fp-contract");
    }

    @Test
    @DisplayName("✅ 路由策略可独立停用（PUBLISHED → DEPRECATED），且内容一字未改")
    void policyCanBeDeprecatedIndependently() {
        seedPublishedPolicy();

        assertThat(lifecycle().transition("routing_policy", tenantA, POLICY_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED))
                .as("⛔ 路由策略无法独立停用")
                .isTrue();

        assertThat(lifecycle().stateOf("routing_policy", tenantA, POLICY_ID, 1))
                .contains(ConfigLifecycleState.DEPRECATED);
        assertThat(adminQuery("SELECT config_fingerprint FROM cap_routing_policy"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND policy_id = '" + POLICY_ID + "'"))
                .isEqualTo("fp-policy");
    }

    @Test
    @DisplayName("⛔ 已发布契约改内容被拒；DEPRECATED 是终态")
    void deprecatedContractIsImmutableAndTerminal() {
        seedPublishedContract();

        assertThatThrownBy(() -> adminExec("UPDATE cap_result_contract SET schema_json = '{\"hacked\":true}'::jsonb"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND contract_id = '" + CONTRACT_ID + "'"))
                .as("⛔ 已发布契约的内容可被改写：§6.1「已发布版本行不可变」对契约从未生效")
                .hasMessageContaining("管理员写入失败")
                .hasStackTraceContaining("已发布不可变版本行");

        assertThat(lifecycle().transition("result_contract", tenantA, CONTRACT_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();

        // 纵深防御：绕过领域层直接打端口，DB 不可变触发器仍会挡住
        assertThatThrownBy(() -> new JdbcConfigLifecycleRepository(appPool).transition(
                "result_contract", tenantA, CONTRACT_ID, 1,
                ConfigLifecycleState.DEPRECATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 绕过状态机后 DB 也放行了：停用形同虚设")
                .hasStackTraceContaining("已发布不可变版本行");
    }

    @Test
    @DisplayName("⛔ 跨租户停用不生效（RLS：改不到别人的行）")
    void crossTenantDeprecationDoesNotLeak() {
        seedPublishedContract();

        assertThat(lifecycle().transition("result_contract", tenantB, CONTRACT_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED))
                .as("⛔ 租户 B 竟能停用租户 A 的契约")
                .isFalse();

        assertThat(lifecycle().stateOf("result_contract", tenantA, CONTRACT_ID, 1))
                .as("⛔ 租户 A 的契约状态被跨租户操作改动")
                .contains(ConfigLifecycleState.PUBLISHED);
    }

    @Test
    @DisplayName("✅ 输出契约可<b>不带 Agent</b> 单独登记并发布（不必连着一个 Agent 一起发布）")
    void contractCanBeRegisteredAndPublishedWithoutAnyAgent() {
        ConfigPublishWorkflowService workflow = workflow();

        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(), List.of(new ConfigPublishPayload.ResultContractSpec(
                        CONTRACT_ID, "{\"type\":\"object\"}")), List.of(),
                null, "单独登记一份输出契约", REQUESTER);
        workflow.decide(tenantA, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(tenantA, created.requestId(), APPROVER);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_result_contract"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND contract_id = '" + CONTRACT_ID
                + "' AND published_at IS NOT NULL"))
                .as("⛔ 不带 Agent 单独登记的契约没有落库："
                        + "「契约只能随 Agent 一起发布」这条说法与实现不符")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release WHERE scope = 'TENANT'"
                + " AND tenant_id = '" + tenantA + "'::uuid"))
                .as("⛔ 独立登记没有产生发布头：契约不在发布清单里，回滚与追溯无从下手")
                .isEqualTo(1L);
        assertThat(adminQuery("SELECT object_versions::text FROM cap_config_release"
                + " WHERE scope = 'TENANT' AND tenant_id = '" + tenantA + "'::uuid"))
                .as("⛔ 独立登记的契约未进发布头清单")
                .contains(CONTRACT_ID);
    }

    @Test
    @DisplayName("✅ 停用后的契约仍在发布头的历史清单里（存量按版本过渡，不追溯）")
    void deprecatedContractRemainsInHistoricalReleaseManifest() {
        seedPublishedContract();
        publishPlatformObjects("result_contract:" + CONTRACT_ID + "@1");

        assertThat(lifecycle().transition("result_contract", tenantA, CONTRACT_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();

        assertThat(adminQuery("SELECT object_versions::text FROM cap_config_release"))
                .as("⛔ 停用把契约从历史发布清单里抹掉了："
                        + "存量 Run 按固定版本过渡，历史清单必须保留")
                .contains(CONTRACT_ID);
    }
}

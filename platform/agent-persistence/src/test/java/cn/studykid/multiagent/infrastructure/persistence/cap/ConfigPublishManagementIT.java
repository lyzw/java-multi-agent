package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishService;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishBaselineConflictException;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishScopeNotAllowedException;
import cn.studykid.multiagent.application.cap.ConfigValidationGate;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcAgentReleaseArchiveRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcConfigPublishRequestRepository;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W-PERM-P6R4 配置变更管理端点的<b>真实 PG</b> 集成测试。
 *
 * <p>⚠️ <b>本类锁的是「不变量」，不是「返回值」</b>：
 * <ul>
 *   <li><b>三步不可合并</b>：未审批不得执行；</li>
 *   <li><b>双人复核</b>：申请人不得审批自己的申请（应用层 + DB CHECK 双保险）；</li>
 *   <li><b>作用域闸门</b>：平台级发布不经租户身份暴露，且<b>不留申请单</b>；</li>
 *   <li><b>零副作用</b>：被拒的请求不得写入版本行 / 发布头 / 指针。</li>
 * </ul>
 *
 * <p>⚠️ <b>应用角色连接</b>（{@code appPool}）：租户通道用非特权角色，
 * 让 RLS 与写策略成为<b>真实</b>防线，而不是「用管理角色绕过去再声称隔离有效」。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ConfigPublishManagementIT extends CapITBase {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final String REQUESTER = "it-r4-requester";
    private static final String APPROVER = "it-r4-approver";

    // ── 装配 ──────────────────────────────────────────────────────

    private ConfigPublishService tenantPublishService() {
        return new ConfigPublishService(
                new JdbcConfigPublishTransaction(appPool),
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcCapAgentDefRepository(appPool),
                new JdbcDecisionAuditRepository(appPool),
                new ConfigValidationGate(),
                CLOCK,
                ConfigPublishService.DEFAULT_MAX_ATTEMPTS, null, null, null, null,
                // ⚠️ 归档仓储必须装配（2026-11-09 · 权威源定 A）：
                //    未装配时发布会被拒 —— 归档缺失会让 run.snapshot_id 外键链断裂，
                //    发布出去的 Agent 一个 Run 都建不出来。
                new JdbcAgentReleaseArchiveRepository(appPool));
    }

    private ConfigPublishWorkflowService workflow() {
        return new ConfigPublishWorkflowService(
                new JdbcConfigPublishRequestRepository(appPool, new JdbcAuditRepository(appPool)),
                tenantPublishService(),
                new ConfigLifecycleService(new JdbcConfigLifecycleRepository(appPool),
                        new ConfigLifecycleStateMachine()),
                new JdbcCapAgentDefRepository(appPool),
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                CLOCK);
    }

    private static ConfigPublishPayload.AgentSpec agentSpec(String agentId) {
        return new ConfigPublishPayload.AgentSpec(agentId, "it agent", "desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(), "{}");
    }

    /**
     * 造一个**只有主体行、没有旧归档版本行**的 Agent（与生产
     * {@code JdbcAgentManageRepository#createAgent} 同形态）。
     *
     * <p>⚠️ 不能用 {@link #createAgentWithVersion}：它会预建
     * {@code agent_version} v1，令发布事务里的归档 INSERT 撞唯一约束被
     * {@code ON CONFLICT DO NOTHING} 跳过 —— 那样归档内容就是夹具的旧 JSON，
     * **验不出「归档是否由本次真源派生」**。
     */
    private static String createBareAgent(String tenantId, String name) {
        // Agent 全局化：建全局行 + 目标租户的 ACTIVE 绑定（名字全局唯一）。
        adminExec("INSERT INTO agent (name) VALUES ('" + name + "') "
                + "ON CONFLICT (name) DO NOTHING");
        String agentId = adminQuery("SELECT agent_id::text FROM agent WHERE name = '" + name + "'");
        adminExec("INSERT INTO tenant_agent_binding (tenant_id, agent_id, status) VALUES ('"
                + tenantId + "'::uuid, '" + agentId + "'::uuid, 'ACTIVE') "
                + "ON CONFLICT (tenant_id, agent_id) DO UPDATE SET status='ACTIVE'");
        return agentId;
    }

    private static long requestRows() {
        return adminQueryLong("SELECT count(*) FROM cap_config_publish_request");
    }

    // ── 三步流程 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 三步发布：申请 → 审批 → 执行，版本行/发布头/指针三者同时生效")
    void threeStepPublishProducesPublishedVersionAndPointer() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);

        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId)), null, "上线新 Agent 定义", REQUESTER);
        assertThat(created.state()).isEqualTo(ConfigPublishRequest.State.PENDING);
        assertThat(created.requesterId()).isEqualTo(REQUESTER);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 申请阶段就写入了版本行：申请不得触碰配置表")
                .isZero();

        ConfigPublishRequest decided = workflow.decide(tenantA, created.requestId(),
                Decision.APPROVE, "同意", APPROVER);
        assertThat(decided.state()).isEqualTo(ConfigPublishRequest.State.APPROVED);
        assertThat(decided.approverId()).isEqualTo(APPROVER);

        ConfigPublishRequest applied = workflow.apply(tenantA, created.requestId(), APPROVER);
        assertThat(applied.state()).isEqualTo(ConfigPublishRequest.State.APPLIED);
        assertThat(applied.appliedAt()).isNotNull();

        assertThat(adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE agent_id = '"
                + agentId + "'::uuid AND published_at IS NOT NULL"))
                .as("⛔ 执行后没有已发布的 Agent 版本行")
                .isEqualTo(1L);
        // ⚠️ 归档断言（2026-11-09 · 权威源定 A）：
        //    真源 cap_agent_def 落行的同时，必须同事务把这一版**派生**归档到
        //    agent_version + execution_snapshot —— 否则 run.snapshot_id 的外键链断裂，
        //    这个 Agent 永远建不出 Run。
        assertThat(adminQueryLong("SELECT count(*) FROM agent_version WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 执行后没有归档版本行（run.snapshot_id 外键链会断裂）")
                .isGreaterThanOrEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM execution_snapshot es "
                + "JOIN agent_version av ON av.version_id = es.version_id "
                + "WHERE av.agent_id = '" + agentId + "'::uuid"))
                .as("⛔ 执行后没有归档执行快照（run.snapshot_id 外键会违约）")
                .isGreaterThanOrEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release WHERE scope = 'TENANT'"
                + " AND tenant_id = '" + tenantA + "'::uuid"))
                .as("⛔ 执行后没有租户级发布头")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer WHERE scope = 'TENANT'"
                + " AND tenant_id = '" + tenantA + "'::uuid"))
                .as("⛔ 执行后当前发布指针没有指向新发布")
                .isEqualTo(1L);
        assertThat(workflow.currentPointer(tenantA)).isPresent();
    }

    /**
     * <b>跨表面闭环守卫</b>（2026-11-10 · 审查 §7 第 1 条）。
     *
     * <p>从<b>三步发布编排入口</b>进，带齐提示词 / 模型 / 生成参数，
     * 逐层断言：发布行 → 归档派生 → 运行期仓储读回，三处同源。
     *
     * <p>⚠️ <b>反向验证</b>：把
     * {@code ConfigPublishService.withPublished(CapAgentDef)} 改回 15 参兼容构造，
     * 本用例的发布行 / 归档 / 读回断言会<b>同时变红</b>（三字段被清成
     * {@code "{}"} / {@code null} / {@code "{}"}）—— 这正是 2026-11-09 实测的
     * Sev-0：全量测试全绿却照不出「发布态把新字段丢了」。
     * ⚠️ 断言值<b>不引用被测实现里的常量</b>，撤掉修复点时不会跟着漂。
     */
    @Test
    @DisplayName("✅ 跨表面闭环：三步发布带提示词/模型/生成参数 → 发布行、归档、运行期读回同源")
    void publishedAgentKeepsInstructionsModelAndGenerationParams() {
        String agentId = createBareAgent(tenantA, freshName("it-r4-closed-loop"));
        String instructions = "{\"inlineInstructions\":\"你是初中数学辅导助手\"}";
        String modelRef = "MiniMax-M2.5";
        String generationParams = "{\"temperature\":0.3,\"maxOutputTokens\":2048}";
        ConfigPublishPayload.AgentSpec spec = new ConfigPublishPayload.AgentSpec(
                agentId, "闭环 Agent", "带提示词与模型", List.of("TEACHER"),
                List.of(), List.of(), List.of(), "{}", "{}",
                instructions, modelRef, generationParams);

        ConfigPublishWorkflowService workflow = workflow();
        ConfigPublishRequest created = workflow.requestPublish(tenantA,
                ConfigRelease.Scope.TENANT, List.of(spec), null,
                "发布带提示词与模型的 Agent", REQUESTER);
        workflow.decide(tenantA, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(tenantA, created.requestId(), APPROVER);

        // ① 已发布版本行（运行期真正读的权威源）
        String row = " FROM cap_agent_def WHERE agent_id = '" + agentId
                + "'::uuid AND published_at IS NOT NULL";
        assertThat(adminQuery("SELECT instructions->>'inlineInstructions'" + row))
                .as("⚠️ 发布态把提示词清空了：三步发布写进库的是另一个对象")
                .isEqualTo("你是初中数学辅导助手");
        assertThat(adminQuery("SELECT model_ref" + row))
                .as("⚠️ 发布态把模型清空了：运行期取不到 modelRef → 该 Agent 的所有 Run fail-closed")
                .isEqualTo(modelRef);
        assertThat(adminQuery("SELECT generation_params->>'temperature'" + row))
                .isEqualTo("0.3");
        assertThat(adminQuery("SELECT generation_params->>'maxOutputTokens'" + row))
                .isEqualTo("2048");

        // ② 归档必须由本次真源派生（不是夹具遗留的旧 definition）
        String av = " FROM agent_version WHERE agent_id = '" + agentId
                + "'::uuid AND version_number = 1";
        assertThat(adminQuery("SELECT definition->>'modelRef'" + av))
                .as("⚠️ 归档与真源分叉：definition 不是从本次发布的 cap_agent_def 派生的")
                .isEqualTo(modelRef);
        assertThat(adminQuery("SELECT definition->'instructions'->>'inlineInstructions'" + av))
                .isEqualTo("你是初中数学辅导助手");
        assertThat(adminQueryLong("SELECT count(*) FROM execution_snapshot es "
                + "JOIN agent_version v ON v.version_id = es.version_id "
                + "WHERE v.agent_id = '" + agentId + "'::uuid "
                + "AND es.agent_config->>'modelRef' = '" + modelRef + "'"))
                .as("⚠️ 执行快照的 agent_config 没带上模型（run.snapshot_id 链路上的配置被清空）")
                .isEqualTo(1L);

        // ③ 运行期仓储按租户读回（不是只看管理连接的裸 SQL）
        CapAgentDef fromRuntime = new JdbcCapAgentDefRepository(appPool)
                .findLatest(tenantA, agentId)
                .orElseThrow(() -> new AssertionError("运行期读不到刚发布的 Agent 定义"));
        assertThat(fromRuntime.instructions()).contains("你是初中数学辅导助手");
        assertThat(fromRuntime.modelRef()).isEqualTo(modelRef);
        assertThat(fromRuntime.generationParams()).contains("0.3");
    }

    @Test
    @DisplayName("✅ CAP 发布归档：generationParams.context 派生为规范 context_policy 快照（单一实现）")
    void publishedAgentSnapshotsContextPolicy() {
        String agentId = createBareAgent(tenantA, freshName("it-r4-ctx-policy"));
        String instructions = "{\"inlineInstructions\":\"你是初中数学辅导助手\"}";
        String modelRef = "MiniMax-M2.5";
        String generationParams = """
                {"temperature":0.3,"maxOutputTokens":2048,
                 "context":{"maxInputTokens":12000,"strategy":"TIERED","recentTurns":7}}
                """;
        ConfigPublishPayload.AgentSpec spec = new ConfigPublishPayload.AgentSpec(
                agentId, "策略快照 Agent", "带 context 发布", List.of("TEACHER"),
                List.of(), List.of(), List.of(), "{}", "{}",
                instructions, modelRef, generationParams);

        ConfigPublishWorkflowService workflow = workflow();
        ConfigPublishRequest created = workflow.requestPublish(tenantA,
                ConfigRelease.Scope.TENANT, List.of(spec), null,
                "发布带压缩策略的 Agent", REQUESTER);
        workflow.decide(tenantA, created.requestId(), Decision.APPROVE, "同意", APPROVER);
        workflow.apply(tenantA, created.requestId(), APPROVER);

        String where = " FROM execution_snapshot es "
                + "JOIN agent_version v ON v.version_id = es.version_id "
                + "WHERE v.agent_id = '" + agentId + "'::uuid "
                + "AND v.version_number = 1";
        String expected = """
                {"maxInputTokens":12000,"recentTurns":7,"strategy":"TIERED"}
                """.trim();

        assertThat(adminQueryLong("SELECT count(*)" + where
                + " AND es.context_policy = '" + expected + "'::jsonb"))
                .as("⛔ CAP 归档未写入由 generationParams.context 派生的规范快照")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*)" + where
                + " AND es.context_policy = '{}'::jsonb"))
                .as("⛔ 已声明 context 却落成默认 {}：快照派生空转")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*)" + where
                + " AND es.agent_config->'generationParams'->'context'->>'strategy' = 'TIERED'"))
                .as("⛔ agent_config 的生成参数被 context_policy 派生污染")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*)" + where
                + " AND es.run_config->>'maxLlmCalls' = '12'"))
                .as("⛔ run_config（DEC-9 容量配置）被 context_policy 派生污染")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("⛔ 未审批不得执行：PENDING 状态直接 apply 被拒，且零副作用")
    void applyWithoutApprovalIsRejected() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);
        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId)), null, "上线新 Agent 定义", REQUESTER);

        assertThatThrownBy(() -> workflow.apply(tenantA, created.requestId(), APPROVER))
                .isInstanceOf(ConfigPublishRequestPort.StateConflictException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 被拒的执行仍然写入了版本行")
                .isZero();
        assertThat(itCount("cap_config_release")).isZero();
        assertThat(itPointerCount()).isZero();
    }

    @Test
    @DisplayName("⛔ 双人复核：申请人不得审批自己的申请（应用层拦截）")
    void selfApprovalIsRejected() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);
        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId)), null, "上线新 Agent 定义", REQUESTER);

        assertThatThrownBy(() -> workflow.decide(tenantA, created.requestId(),
                Decision.APPROVE, "自己批", REQUESTER))
                .isInstanceOf(ConfigPublishRequestPort.SelfApprovalException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_publish_request"
                + " WHERE state <> 'PENDING'"))
                .as("⛔ 自审批被拒后申请单状态却被改动了")
                .isZero();
    }

    // ── 作用域闸门 ────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 平台级发布不经租户身份暴露：显式拒绝，且不留申请单")
    void platformScopeIsRejectedWithoutLeavingRequest() {
        ConfigPublishWorkflowService workflow = workflow();

        assertThatThrownBy(() -> workflow.requestPublish(tenantA, ConfigRelease.Scope.PLATFORM,
                List.of(agentSpec("it-r4-agent")), null, "发布平台配置", REQUESTER))
                .as("租户通道不得能发起平台级发布")
                .isInstanceOf(ConfigPublishScopeNotAllowedException.class);

        assertThat(requestRows())
                .as("⛔ 被拒的平台级申请仍落了单：闸门必须发生在落单之前")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 平台级对象类型不得经租户通道做生命周期迁移")
    void platformObjectTypeIsRejectedForLifecycle() {
        ConfigPublishWorkflowService workflow = workflow();

        assertThatThrownBy(() -> workflow.requestLifecycle(tenantA, ConfigRelease.Scope.TENANT,
                new ConfigPublishPayload.LifecycleSpec("tool", "it-cap-tool", 1,
                        "DRAFT", "VALIDATED"), "停用平台工具", REQUESTER))
                .isInstanceOf(ConfigPublishScopeNotAllowedException.class);

        assertThat(requestRows()).isZero();
    }

    @Test
    @DisplayName("⛔ 申请阶段门禁预检：非法发布集合被拒，且不占用审批名额")
    void illegalPublishSetIsRejectedAtRequestTime() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);

        assertThatThrownBy(() -> workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId), agentSpec(agentId)), null, "重复对象", REQUESTER))
                .as("同一 id 出现两个版本会让「id→精确版本」的解析不确定")
                .isInstanceOf(RuntimeException.class);

        assertThat(requestRows())
                .as("⛔ 非法发布集合仍落了申请单")
                .isZero();
    }

    // ── CAS 基线 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ CAS 基线过期被拒：客户端看到的已不是当前配置时不得发布")
    void staleBaselineIsRejected() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);

        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId)), 999L, "基于旧基线发布", REQUESTER);
        workflow.decide(tenantA, created.requestId(), Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> workflow.apply(tenantA, created.requestId(), APPROVER))
                .isInstanceOf(ConfigPublishBaselineConflictException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 基线冲突仍写入了版本行")
                .isZero();
        assertThat(itCount("cap_config_release")).isZero();
    }

    // ── 终态与留痕 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 执行失败落 FAILED 终态：失败原因留痕，且失败不得留下半成品发布")
    void failedExecutionIsRecordedAsTerminalState() {
        ConfigPublishWorkflowService workflow = workflow();
        // ⚠️ 故意用一个**不存在**的 agent 行 → 写入时撞外键 → 执行失败
        String ghostAgentId = java.util.UUID.randomUUID().toString();

        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(ghostAgentId)), null, "发布不存在的 Agent", REQUESTER);
        workflow.decide(tenantA, created.requestId(), Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> workflow.apply(tenantA, created.requestId(), APPROVER))
                .isInstanceOf(RuntimeException.class);

        ConfigPublishRequest failed = new JdbcConfigPublishRequestRepository(
                appPool, new JdbcAuditRepository(appPool))
                .find(tenantA, created.requestId()).orElseThrow();
        assertThat(failed.state())
                .as("⛔ 执行失败后申请单未落 FAILED 终态：失败必须留痕")
                .isEqualTo(ConfigPublishRequest.State.FAILED);
        assertThat(failed.failureReason()).isNotBlank();
        assertThat(itCount("cap_config_release"))
                .as("⛔ 失败执行留下了发布头")
                .isZero();
        assertThat(itPointerCount()).isZero();
    }

    // ── 租户隔离 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 租户隔离：另一租户看不到本租户的变更申请（RLS）")
    void publishRequestsAreTenantIsolated() {
        ConfigPublishWorkflowService workflow = workflow();
        String agentId = createAgentWithVersion(tenantA, agentName("it-r4-agent"), "{}", true);
        ConfigPublishRequest created = workflow.requestPublish(tenantA, ConfigRelease.Scope.TENANT,
                List.of(agentSpec(agentId)), null, "上线新 Agent 定义", REQUESTER);

        assertThat(workflow.list(tenantB, null, null, 20).items())
                .as("⛔ 租户 B 读到了租户 A 的变更申请")
                .isEmpty();
        assertThat(workflow.list(tenantA, null, null, 20).items())
                .hasSize(1);
        assertThat(workflow.list(tenantA, null, null, 20).items().get(0).requestId())
                .isEqualTo(created.requestId());
    }
}

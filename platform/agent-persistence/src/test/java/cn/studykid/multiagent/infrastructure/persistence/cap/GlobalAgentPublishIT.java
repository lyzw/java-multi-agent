package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;
import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishService;
import cn.studykid.multiagent.application.cap.ConfigValidationGate;
import cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcConfigPublishRequestRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentManageRepository;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无初始绑定租户的全局 Agent —— <b>真实 PG</b> 端到端闭环（运营代配置 · 2026-11-20）。
 *
 * <p>⚠️ 本类锁的是「新建 Agent 不强制指定初始绑定租户」这条链路的<b>真实语义</b>：
 * <ol>
 *   <li>{@code createAgent(null, ...)} 只建全局 agent 行，<b>不写</b> tenant_agent_binding；</li>
 *   <li>无租户发布申请走 <b>PLATFORM</b> 作用域（tenant_id IS NULL），租户工作流零触达；</li>
 *   <li>审批 + 执行后：权威定义 cap_agent_def、平台发布头、平台指针、归档版本与执行快照
 *       <b>同时</b>生效（缺一即 fail-closed 链路断了）；</li>
 *   <li>{@code findLatestGlobal} 能按全局身份读回刚发布的定义（不依赖任何租户绑定）。</li>
 * </ol>
 *
 * <p>⚠️ 用 NOSUPERUSER + BYPASSRLS 的运维角色连接（与生产同形态），
 * 避免用超级用户验证得到「凭超级用户才写得进平台行」的假绿。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class GlobalAgentPublishIT extends CapITBase {

    private static final String OPS_ROLE = "platform_ops_it";
    private static final String OPS_PASSWORD = "platform_ops_it_pw";
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String REQUESTER = "it-global-requester";
    private static final String APPROVER = "it-global-approver";

    private static HikariDataSource opsPool;

    /**
     * 本类创建的申请单 id。
     *
     * <p>⚠️ 必须自清理：{@link ConfigPublishManagementIT#requestRows()} 断言的是
     * <b>全表</b>申请单数（不是按租户过滤）。若本类留下的 PLATFORM 申请单过夜，
     * 后续用例会拿到「被拒的操作仍落了单」的<b>假红</b>——污染点离报错点极远。
     */
    private final java.util.List<String> createdRequestIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanRequests() {
        for (String requestId : createdRequestIds) {
            adminExec("DELETE FROM cap_config_publish_request WHERE request_id = '"
                    + requestId + "'::uuid");
        }
        createdRequestIds.clear();
    }

    @BeforeAll
    static void initOpsPool() {
        adminExec("DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + OPS_ROLE + "') THEN "
                + "CREATE ROLE " + OPS_ROLE + " LOGIN NOSUPERUSER BYPASSRLS "
                + "NOCREATEDB NOCREATEROLE INHERIT PASSWORD '" + OPS_PASSWORD + "'; "
                + "END IF; "
                + "ALTER ROLE " + OPS_ROLE + " LOGIN NOSUPERUSER BYPASSRLS "
                + "NOCREATEROLE NOCREATEDB INHERIT PASSWORD '" + OPS_PASSWORD + "'; "
                + "END $$;");
        adminExec("GRANT USAGE ON SCHEMA public TO " + OPS_ROLE);
        adminExec("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + OPS_ROLE);
        adminExec("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + OPS_ROLE);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(OPS_ROLE);
        cfg.setPassword(OPS_PASSWORD);
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("it-global-agent-ops-pool");
        opsPool = new HikariDataSource(cfg);
    }

    @AfterAll
    static void closeOpsPool() {
        if (opsPool != null) {
            opsPool.close();
            opsPool = null;
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private PlatformOpsDataSourceProvider provider() {
        return new PlatformOpsDataSourceProvider(true, opsPool);
    }

    /** 平台通道的发布服务：运维事务 + 归档端口（全局 Agent 发布必需）。 */
    private ConfigPublishService platformPublisher() {
        DataSource ds = appPool;
        return new ConfigPublishService(
                new JdbcPlatformOpsConfigPublishTransaction(provider()),
                new JdbcConfigPointerRepository(ds),
                new JdbcConfigReleaseRepository(ds),
                new JdbcCapToolDefRepository(ds),
                new JdbcCapSkillDefRepository(ds),
                new JdbcCapMcpServerDefRepository(ds),
                new JdbcCapAgentDefRepository(ds),
                new JdbcDecisionAuditRepository(ds),
                new ConfigValidationGate(),
                CLOCK,
                new JdbcAgentReleaseArchiveRepository(ds));
    }

    /** 平台运维通道的三步发布服务（申请单仓储用运维连接）。 */
    private PlatformCapabilityPublishService platformService() {
        return new PlatformCapabilityPublishService(
                provider(),
                new JdbcConfigPublishRequestRepository(
                        opsPool, new JdbcAuditRepository(opsPool)),
                platformPublisher(),
                new ConfigLifecycleService(
                        new JdbcConfigLifecycleRepository(appPool),
                        new ConfigLifecycleStateMachine()),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcConfigPointerRepository(appPool),
                CLOCK,
                new JdbcCapAgentDefRepository(appPool));
    }

    private static ConfigPublishPayload.AgentSpec leafSpec(String agentId) {
        return new ConfigPublishPayload.AgentSpec(
                agentId, "全局助手", "无初始绑定租户", List.of("TEACHER"),
                List.of(), List.of(), List.of(), "{}", "{}",
                "{\"inlineInstructions\":\"你是初中数学辅导助手\"}",
                "MiniMax-M2.5", "{\"temperature\":0.3}");
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ createAgent(null)：只建全局行，不写 tenant_agent_binding（无租户 ≠ 绑到零 UUID）")
    void createAgentWithoutTenantCreatesOnlyGlobalRow() {
        String name = freshName("it-global-create");
        var created = new JdbcAgentManageRepository(appPool, new JdbcAuditRepository(appPool))
                .createAgent(null, name, "无初始租户", REQUESTER);
        String agentId = created.agentId();

        assertThat(adminQueryLong("SELECT count(*) FROM agent WHERE agent_id = '"
                + agentId + "'::uuid AND name = '" + name + "'"))
                .as("全局 agent 行没落库")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM tenant_agent_binding WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ tenantId=null 却建了绑定：运营尚未指定租户就被写死了")
                .isZero();
    }

    @Test
    @DisplayName("✅ 全局 Agent 三步闭环：PLATFORM 申请 → 审批 → 执行 → 按全局身份读回（零绑定依赖）")
    void globalAgentThreeStepPublishAndReadBack() {
        String name = freshName("it-global-publish");
        String agentId = new JdbcAgentManageRepository(appPool, new JdbcAuditRepository(appPool))
                .createAgent(null, name, "全局发布闭环", REQUESTER).agentId();
        assertThat(adminQueryLong("SELECT count(*) FROM tenant_agent_binding WHERE agent_id = '"
                + agentId + "'::uuid")).isZero();

        PlatformCapabilityPublishService platform = platformService();

        // ① 申请：无租户 → PLATFORM 作用域，落单时 tenant_id 必须 NULL
        ConfigPublishRequest request = platform.requestPublishAgents(
                List.of(leafSpec(agentId)), null, "全局 Agent 首发", REQUESTER);
        createdRequestIds.add(request.requestId());
        assertThat(request.state()).isEqualTo(ConfigPublishRequest.State.PENDING);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_publish_request"
                + " WHERE request_id = '" + request.requestId() + "'::uuid"
                + " AND tenant_id IS NULL AND scope = 'PLATFORM'"))
                .as("⛔ 无租户申请没有落成 PLATFORM 行（仍走租户工作流？）")
                .isEqualTo(1L);
        // 申请阶段不得触碰配置表
        assertThat(adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 申请阶段就写入了权威定义")
                .isZero();

        // ② 审批（申请人 ≠ 审批人）
        ConfigPublishRequest decided = platform.decide(
                request.requestId(), Decision.APPROVE, "同意", APPROVER);
        assertThat(decided.state()).isEqualTo(ConfigPublishRequest.State.APPROVED);

        // ③ 执行：权威定义 + 平台发布头 + 平台指针 + 归档版本/快照同时生效
        ConfigPublishRequest applied = platform.apply(request.requestId(), APPROVER);
        assertThat(applied.state()).isEqualTo(ConfigPublishRequest.State.APPLIED);

        String whereDef = " FROM cap_agent_def WHERE agent_id = '" + agentId
                + "'::uuid AND published_at IS NOT NULL";
        assertThat(adminQueryLong("SELECT count(*)" + whereDef))
                .as("⛔ 执行后没有已发布的全局权威定义")
                .isEqualTo(1L);
        assertThat(adminQuery("SELECT model_ref" + whereDef)).isEqualTo("MiniMax-M2.5");
        assertThat(adminQuery("SELECT instructions->>'inlineInstructions'" + whereDef))
                .isEqualTo("你是初中数学辅导助手");
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"
                + " WHERE scope = 'PLATFORM' AND tenant_id IS NULL"
                + " AND object_versions::text LIKE '%" + agentId + "%'"))
                .as("⛔ 没有平台级发布头（tenant_id 必须 NULL）")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"
                + " WHERE scope = 'PLATFORM' AND tenant_id IS NULL"))
                .as("⛔ 平台当前指针未落库")
                .isGreaterThanOrEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM agent_version WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 归档版本行缺失：run.snapshot_id 外键链会断裂")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM execution_snapshot es "
                + "JOIN agent_version v ON v.version_id = es.version_id "
                + "WHERE v.agent_id = '" + agentId + "'::uuid"))
                .as("⛔ 执行快照缺失")
                .isEqualTo(1L);

        // ④ 运行期读回：不按任何租户过滤，纯全局身份
        CapAgentDef fromGlobal = new JdbcCapAgentDefRepository(appPool)
                .findLatestGlobal(agentId)
                .orElseThrow(() -> new AssertionError("⛔ 按全局身份读不到刚发布的 Agent 定义"));
        assertThat(fromGlobal.modelRef()).isEqualTo("MiniMax-M2.5");
        assertThat(fromGlobal.instructions()).contains("你是初中数学辅导助手");

        // ⑤ 全程零绑定：发布不等于偷偷建绑定
        assertThat(adminQueryLong("SELECT count(*) FROM tenant_agent_binding WHERE agent_id = '"
                + agentId + "'::uuid"))
                .as("⛔ 全局发布后仍无绑定才对；出现绑定 = RLS 可见性被悄悄改写")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 平台通道未装配全局 Agent 定义端口 → 申请期 fail-closed（不落申请单）")
    void missingAgentDefsPortFailsClosed() {
        PlatformCapabilityPublishService broken = new PlatformCapabilityPublishService(
                provider(),
                new JdbcConfigPublishRequestRepository(
                        opsPool, new JdbcAuditRepository(opsPool)),
                platformPublisher(),
                new ConfigLifecycleService(
                        new JdbcConfigLifecycleRepository(appPool),
                        new ConfigLifecycleStateMachine()),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcConfigPointerRepository(appPool),
                CLOCK,
                null);

        String name = freshName("it-global-failclosed");
        String agentId = new JdbcAgentManageRepository(appPool, new JdbcAuditRepository(appPool))
                .createAgent(null, name, "fail-closed", REQUESTER).agentId();
        long before = adminQueryLong("SELECT count(*) FROM cap_config_publish_request");
        try {
            broken.requestPublishAgents(List.of(leafSpec(agentId)), null, "不该成功", REQUESTER);
            throw new AssertionError("⛔ 未装配 agentDefs 却成功提交了全局 Agent 申请");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("未装配全局 Agent 定义端口");
        }
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_publish_request"))
                .as("⛔ fail-closed 仍落了申请单")
                .isEqualTo(before);
    }
}

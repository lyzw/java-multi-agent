package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.CapDualWriteService;
import cn.studykid.multiagent.application.cap.CapDualWriteSwitch;
import cn.studykid.multiagent.application.cap.DualWriteAgentManagePort;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.infrastructure.mcp.UnconfiguredMcpManifestPort;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentManageRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移双写集成测试（W-PERM-P0-T6 / T7 · 06 §3.3）。
 *
 * <p>锁住三件事：
 * <ol>
 *   <li>开关开启时，旧模型写入会同步产出新模型版本行；</li>
 *   <li>开关关闭时，旧路径<b>完全接管</b>且<b>不丢数据</b>；</li>
 *   <li>比对工具在同一内容上给出<b>0 漂移</b>。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapDualWriteIT extends CapITBase {

    private static final String DEFINITION = """
            {"name":"cap-agent","description":"双写用例",
             "roles":["TEACHER"],"enabledSkills":["skill-a","skill-b"],
             "overrides":{"removeSkills":["skill-x"]}}
            """;

    private CapDualWriteSwitch dualWriteSwitch;
    private CapDualWriteService dualWrite;
    private CapAgentDefPort agentDefs;
    private AgentManagePort agentManage;

    private void build(boolean enabled) {
        dualWriteSwitch = new CapDualWriteSwitch(enabled);
        agentDefs = new JdbcCapAgentDefRepository(appPool);
        // ⚠️ P1 起快照双写需要闭包解析器 + 版本源 + 快照仓储。
        //    本 IT 只覆盖「Agent 版本双写」路径，但依赖仍按真实实现装配，
        //    避免测试与生产装配不一致（那会让「测得过但跑不起来」）。
        PermissionResolver resolver = new PermissionResolver(
                agentDefs,
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                new UnconfiguredMcpManifestPort());
        dualWrite = new CapDualWriteService(dualWriteSwitch, agentDefs, resolver,
                new JdbcAuthzVersionRepository(appPool),
                new JdbcCapabilitySnapshotRepository(appPool),
                Clock.systemUTC());
        agentManage = new DualWriteAgentManagePort(
                new JdbcAgentManageRepository(appPool, new JdbcAuditRepository(appPool)),
                dualWrite);
    }

    private String createAgent(String name) {
        return agentManage.createAgent(tenantA, name, "desc", "creator-1").agentId();
    }

    // ── 开关开启 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 开关开启：发布版本后新模型产出结构化版本行")
    void dualWriteProducesCapAgentDef() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-on"));

        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");

        assertThat(agentDefs.find(tenantA, agentId, 1))
                .as("⛔ 双写未产出 cap_agent_def：迁移后新模型无数据可切")
                .isPresent();

        CapAgentDef def = agentDefs.find(tenantA, agentId, 1).orElseThrow();
        assertThat(def.enabledSkills()).containsExactly("skill-a", "skill-b");
        assertThat(def.roles()).containsExactly("TEACHER");
        assertThat(def.name()).isEqualTo("cap-agent");
        assertThat(def.isPublished()).isTrue();
    }

    // ── 开关关闭 = 回退 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 开关关闭：旧路径完全接管，且旧数据不丢")
    void switchOffLeavesOldPathAuthoritative() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-off"));

        // 先双写一次（新模型有数据）
        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");
        assertThat(agentDefs.find(tenantA, agentId, 1)).isPresent();

        // 关开关后再发一版
        dualWriteSwitch.disable();
        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");

        // 新模型<b>没有</b>第 2 版（旧路径完全接管）
        assertThat(agentDefs.find(tenantA, agentId, 2))
                .as("⛔ 开关关闭后仍在双写：回退不彻底")
                .isEmpty();

        // 旧路径数据一条不少
        long legacyVersions = adminQueryLong("SELECT count(*) FROM agent_version "
                + "WHERE tenant_id = '" + tenantA + "'::uuid AND agent_id = '" + agentId
                + "'::uuid AND version_number IN (1,2)");
        assertThat(legacyVersions)
                .as("⛔ 旧路径数据丢失：回退必须做到无数据丢失")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("✅ 开关关闭时完全不产生新模型行")
    void switchOffWritesNothing() {
        build(false);
        String agentId = createAgent(uniqueName("cap-dw-never"));

        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");

        assertThat(agentDefs.find(tenantA, agentId, 1))
                .as("⛔ 开关关闭仍写入新模型：回退开关无效")
                .isEmpty();
        assertThat(dualWrite.failureCount()).isZero();
    }

    // ── V1 硬边界：inherits 拒绝投影 ───────────────────────────────

    @Test
    @DisplayName("✅ 带 inherits 的存量定义被拒绝投影（V1 写入即拒）")
    void inheritsIsRejectedNotCoerced() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-inherits"));

        agentManage.publishVersion(tenantA, agentId,
                "{\"name\":\"x\",\"inherits\":\"parent-skill\"}", "publisher-1");

        assertThat(agentDefs.find(tenantA, agentId, 1))
                .as("⛔ 带 inherits 的定义被照常投影：嵌套 Skill 的抬权面被静默带入")
                .isEmpty();
        assertThat(dualWrite.rejectedProjectionCount())
                .as("⛔ 拒绝未计数：迁移报告将漏掉需人工处理的项")
                .isEqualTo(1L);
    }

    // ── P0-T7 比对工具 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 比对工具：双写后新旧模型 0 漂移")
    void comparatorReportsNoDrift() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-compare"));
        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");

        CapDualWriteComparator comparator = new CapDualWriteComparator(appPool);
        CapDualWriteReport report = comparator.compare(tenantA, agentId);

        assertThat(report.driftCount())
                .as("⛔ 比对发现漂移: missing=%s extra=%s mismatched=%s",
                        report.missingInCap(), report.extraInCap(), report.mismatched())
                .isZero();
        assertThat(report.matched()).isGreaterThanOrEqualTo(1);
        assertThat(report.isClean()).isTrue();
    }

    @Test
    @DisplayName("✅ 比对工具：能识别「旧有新无」的真实漂移")
    void comparatorDetectsMissing() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-drift"));
        agentManage.publishVersion(tenantA, agentId, DEFINITION, "publisher-1");

        // 人为制造漂移：删掉新模型行（旧模型仍在）
        adminExec("DELETE FROM cap_agent_def WHERE tenant_id = '" + tenantA
                + "'::uuid AND agent_id = '" + agentId + "'::uuid");

        CapDualWriteComparator comparator = new CapDualWriteComparator(appPool);
        CapDualWriteReport report = comparator.compare(tenantA, agentId);

        assertThat(report.driftCount())
                .as("⛔ 比对工具未能发现漂移：它必须是有效的收敛依据")
                .isGreaterThan(0);
        assertThat(report.missingInCap())
                .anyMatch(d -> d.agentId().equals(agentId));
    }

    @Test
    @DisplayName("✅ 比对工具：拒绝项与漂移分开统计（拒绝不是漂移）")
    void comparatorSeparatesRejectionsFromDrift() {
        build(true);
        String agentId = createAgent(uniqueName("cap-dw-reject"));
        agentManage.publishVersion(tenantA, agentId,
                "{\"inherits\":\"p\"}", "publisher-1");

        CapDualWriteComparator comparator = new CapDualWriteComparator(appPool);
        CapDualWriteReport report = comparator.compare(tenantA, agentId);

        assertThat(report.rejectedCount())
                .as("⛔ 拒绝项未登记：迁移报告会漏掉需人工处理的存量")
                .isGreaterThan(0);
        assertThat(report.driftCount())
                .as("⛔ 拒绝项被误算成漂移：两类问题需要不同的处置")
                .isZero();
    }
}

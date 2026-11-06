package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.CapDualWriteSwitch;
import cn.studykid.multiagent.application.cap.CapDualWriteService;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 能力快照<b>写入链</b>的端到端验证（2026-10-31 实测缺陷的回归锁）。
 *
 * <p><b>⚠️ 本类要证明的事</b>：在真实库上，{@code CapDualWriteService
 * .onExecutionSnapshotCreated} 能真的写出一行
 * {@code execution_capability_snapshot}。
 *
 * <p><b>⚠️ 为什么必须有这个测试</b>：修复前该表<b>全库 0 行</b>，
 * 而单元测试全绿 —— 因为断链发生在<b>数据前置条件</b>上，
 * 不在代码逻辑上：
 * <ol>
 *   <li>{@code cap_agent_def} 无行 → {@code resolver.resolve()} 抛
 *       {@code RESOURCE_UNKNOWN} → 双写 fail-soft 跳过；</li>
 *   <li>{@code cap_authz_version} 无该主体行 →
 *       {@code authzVersions.require()} 抛 {@code AuthzUnavailableException}
 *       → 双写 fail-soft 跳过。</li>
 * </ol>
 * 两处都被 {@code catch (RuntimeException)} 吞掉并记 WARN ——
 * <b>fail-soft 让「链路断了」这件事不体现为失败</b>，
 * 只体现为「表一直是空的」。而表空的后果不是少一份数据，
 * 是<b>依赖它的读方一律 fail-closed</b>
 * （{@code RootAgentModelConfigProvider} 把每个 Run 判成 CONFIG_UNAVAILABLE）。
 *
 * <p><b>⚠️ 为什么单测锁不住</b>：单元测试用替身仓储，永远「有行」；
 * 而真实缺陷恰恰是<b>真库没行</b>。故本类必须连真库。
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapabilitySnapshotWriteChainIT extends CapITBase {

    private static final String SKILL_ID = "it-snapchain-skill";
    private static final String TOOL_ID = "it-snapchain-tool";
    private static final String PRINCIPAL = "it-snapchain-principal";

    private static final McpManifestPort EMPTY_MANIFEST =
            serverId -> McpManifestPort.ManifestSnapshot.available(Set.of());

    @Test
    @DisplayName("⚠️⚠️ 快照写入链：cap_agent_def + cap_authz_version 齐备 → 真的写出快照行")
    void writesSnapshotWhenPrerequisitesPresent() {
        // ── 前置 1：cap_skill_def / cap_tool_def 行 + 发布指针 ──
        // ⚠️ ON CONFLICT DO NOTHING：共享 dev 库上重跑时行已存在，
        //    直接 INSERT 会撞唯一约束报错（而这不是被测行为）。
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + SKILL_ID + "', 1, 'it skill', '[\"" + TOOL_ID + "\"]', '[]'::jsonb,"
                + " 'WRITE_EXTERNAL', '[]'::jsonb, 'platform', 'fp-skill', now())"
                + " ON CONFLICT (id, version) DO NOTHING");
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 1, 'it tool', 'native', 'READ', '{}'::jsonb,"
                + " '[]'::jsonb, 'platform', 'fp-tool', now())"
                + " ON CONFLICT (id, version) DO NOTHING");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");

        // ── 前置 2：cap_agent_def 行（闭包解析的入口）──
        String agentId = createAgentWithVersion(tenantA, agentName("it-snapchain-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantA + "'::uuid, '" + agentId + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '[\"" + SKILL_ID + "\"]'::jsonb, '{}'::jsonb,"
                + " 'fp-agent', now())");

        // ── 前置 3：cap_authz_version 行（授权版本；缺行即 AUTHZ_UNAVAILABLE）──
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version, version)"
                + " VALUES ('" + tenantA + "'::uuid, '" + PRINCIPAL + "', 1, 0)"
                + " ON CONFLICT (tenant_id, principal_id) DO NOTHING");

        CapabilitySnapshotPort snapshots = new JdbcCapabilitySnapshotRepository(appPool);
        CapDualWriteService dualWrite = new CapDualWriteService(
                new CapDualWriteSwitch(true),
                new JdbcCapAgentDefRepository(appPool),
                resolver(),
                new JdbcAuthzVersionRepository(appPool),
                snapshots,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        String runId = createRun(tenantA, agentId, createExecutionSnapshot(tenantA, agentId));
        dualWrite.onExecutionSnapshotCreated(tenantA, runId, agentId, 1, PRINCIPAL);

        assertThat(snapshots.find(tenantA, runId))
                .as("⚠️⚠️ 前置齐备时必须真的写出快照行 —— "
                        + "写不出 = 断链仍在，Run 会被判成 CONFIG_UNAVAILABLE")
                .isPresent();
    }

    @Test
    @DisplayName("⚠️ 缺 cap_authz_version 行 → 不写快照且不抛（fail-soft，但计数可观测）")
    void skipsSnapshotWhenAuthzVersionMissing() {
        // ⚠️ 本用例钉住 fail-soft 契约：缺行**不得**让 Run 创建失败。
        //    但它**必须**体现为「快照没写出来」—— 否则断链又是静默的。
        String agentId = createAgentWithVersion(tenantA, agentName("it-snapchain-noauthz"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantA + "'::uuid, '" + agentId + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'fp-agent', now())");

        CapabilitySnapshotPort snapshots = new JdbcCapabilitySnapshotRepository(appPool);
        CapDualWriteService dualWrite = new CapDualWriteService(
                new CapDualWriteSwitch(true),
                new JdbcCapAgentDefRepository(appPool),
                resolver(),
                new JdbcAuthzVersionRepository(appPool),
                snapshots,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        String runId = createRun(tenantA, agentId, createExecutionSnapshot(tenantA, agentId));
        // ⚠️ 主体「it-snapchain-absent-principal」刻意**不**建 cap_authz_version 行
        dualWrite.onExecutionSnapshotCreated(tenantA, runId, agentId, 1,
                "it-snapchain-absent-principal");

        assertThat(snapshots.find(tenantA, runId))
                .as("缺授权版本行时**不得**写快照（不得臆造 authz_version）")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 缺 cap_agent_def 行 → 不写快照且不抛（闭包不可解析即跳过）")
    void skipsSnapshotWhenAgentDefMissing() {
        // ⚠️ 只有 legacy agent_version 行、没有 cap_agent_def 行
        String agentId = createAgentWithVersion(tenantA, agentName("it-snapchain-nocapdef"), "{}", true);
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version, version)"
                + " VALUES ('" + tenantA + "'::uuid, '" + PRINCIPAL + "', 1, 0)"
                + " ON CONFLICT (tenant_id, principal_id) DO NOTHING");

        CapabilitySnapshotPort snapshots = new JdbcCapabilitySnapshotRepository(appPool);
        CapDualWriteService dualWrite = new CapDualWriteService(
                new CapDualWriteSwitch(true),
                new JdbcCapAgentDefRepository(appPool),
                resolver(),
                new JdbcAuthzVersionRepository(appPool),
                snapshots,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        String runId = createRun(tenantA, agentId, createExecutionSnapshot(tenantA, agentId));
        dualWrite.onExecutionSnapshotCreated(tenantA, runId, agentId, 1, PRINCIPAL);

        assertThat(snapshots.find(tenantA, runId))
                .as("闭包不可解析时**不得**写快照（不得臆造空闭包）")
                .isEmpty();
    }

    private PermissionResolver resolver() {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                EMPTY_MANIFEST);
    }
}

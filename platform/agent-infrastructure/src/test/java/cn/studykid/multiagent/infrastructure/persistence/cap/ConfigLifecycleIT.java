package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.application.cap.ConfigLifecycleStateMachine;
import cn.studykid.multiagent.application.cap.ConfigPublishRejectedException;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W-PERM-P5 生命周期的<b>真实 PG</b> 集成测试（§6.6 / 06 §3 · P5-T4/T8）。
 *
 * <p>⚠️ <b>本类锁三件事</b>：
 * <ol>
 *   <li><b>状态迁移受 CAS 前置条件保护</b>：期望原状态不符 → 拒绝；</li>
 *   <li><b>校验失败保持可修订</b>：VALIDATED → DRAFT 之后草稿仍可编辑；</li>
 *   <li><b>未发布不可被 Agent 启用</b>：不在当前发布里的对象根本解析不出来。</li>
 * </ol>
 *
 * <p>⚠️ <b>「未发布不可启用」不是靠约定，是靠机制</b>：
 * 未发布的对象不在<b>当前发布头</b>的 {@code object_versions} 里，
 * 闭包解析根本找不到它 —— 启用链路上它压根不会出现。
 *
 * <p>⚠️ <b>读写角色分工</b>：
 * 生命周期迁移走<b>应用角色</b>（{@code app_agent}，非特权），
 * 证明「状态机在真实权限下可执行」；夹具造数走<b>管理角色</b>（BYPASSRLS）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ConfigLifecycleIT extends CapITBase {

    private static final String TOOL_ID = "it-p5l-tool";
    private static final String SKILL_ID = "it-p5l-skill";

    /** 应用层迁移服务（状态机 + CAS 端口）。 */
    private ConfigLifecycleService lifecycle() {
        return new ConfigLifecycleService(
                new JdbcConfigLifecycleRepository(appPool),
                new ConfigLifecycleStateMachine());
    }

    /** 裸端口（绕过状态机，用于验证 DB 兜底）。 */
    private ConfigLifecyclePort rawLifecycle() {
        return new JdbcConfigLifecycleRepository(appPool);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private void seedDraftTool() {
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint)"
                + " VALUES ('" + TOOL_ID + "', 1, 'draft tool', 'native', 'READ', '{}'::jsonb,"
                + " '{}'::jsonb, 'platform', 'fp-draft')");
    }

    private void seedPublishedTool() {
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 1, 'published tool', 'native', 'READ', '{}'::jsonb,"
                + " '{}'::jsonb, 'platform', 'fp-published', now())");
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 草稿行初始状态为 DRAFT，且草稿可编辑")
    void draftRowStartsAsDraftAndRemainsEditable() {
        seedDraftTool();

        assertThat(lifecycle().stateOf("tool", null, TOOL_ID, 1))
                .as("⛔ 草稿行的生命周期状态不是 DRAFT")
                .contains(ConfigLifecycleState.DRAFT);

        // 草稿行可编辑（06 §3「草稿编辑不修改已发布版本行」）
        adminExec("UPDATE cap_tool_def SET name = 'edited' WHERE id = '" + TOOL_ID
                + "' AND version = 1");
        assertThat(adminQuery("SELECT name FROM cap_tool_def WHERE id = '" + TOOL_ID
                + "' AND version = 1")).isEqualTo("edited");
    }

    @Test
    @DisplayName("✅ DRAFT → VALIDATED 合法，且带 CAS 前置条件")
    void draftToValidatedIsGuardedByExpectedState() {
        seedDraftTool();
        ConfigLifecycleService port = lifecycle();

        // 期望原状态不符 → 拒绝（不能把别人的并发迁移覆盖掉）
        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.DRAFT))
                .as("⛔ 期望原状态不符却迁移成功：并发迁移被静默覆盖")
                .isFalse();

        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.DRAFT, ConfigLifecycleState.VALIDATED))
                .isTrue();
        assertThat(port.stateOf("tool", null, TOOL_ID, 1))
                .contains(ConfigLifecycleState.VALIDATED);
    }

    @Test
    @DisplayName("✅ 校验失败保持可修订：VALIDATED → DRAFT 后仍可编辑")
    void validationFailureKeepsRowEditable() {
        seedDraftTool();
        ConfigLifecycleService port = lifecycle();

        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.DRAFT, ConfigLifecycleState.VALIDATED)).isTrue();
        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.DRAFT)).isTrue();

        // 回到 DRAFT 后仍可编辑 —— 「校验失败保持可修订」不是一句口号
        adminExec("UPDATE cap_tool_def SET name = 'revised' WHERE id = '" + TOOL_ID
                + "' AND version = 1");
        assertThat(adminQuery("SELECT name FROM cap_tool_def WHERE id = '" + TOOL_ID
                + "' AND version = 1")).isEqualTo("revised");
        assertThat(port.stateOf("tool", null, TOOL_ID, 1))
                .contains(ConfigLifecycleState.DRAFT);
    }

    @Test
    @DisplayName("✅ 已发布行可停用（PUBLISHED → DEPRECATED），且内容一字不改")
    void publishedRowCanBeDeprecated() {
        seedPublishedTool();
        ConfigLifecycleService port = lifecycle();

        assertThat(port.stateOf("tool", null, TOOL_ID, 1))
                .contains(ConfigLifecycleState.PUBLISHED);

        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();
        assertThat(port.stateOf("tool", null, TOOL_ID, 1))
                .contains(ConfigLifecycleState.DEPRECATED);
        assertThat(adminQuery("SELECT config_fingerprint FROM cap_tool_def WHERE id = '" + TOOL_ID
                + "' AND version = 1"))
                .as("⛔ 停用改写了已发布行的内容：不可变版本行被篡改")
                .isEqualTo("fp-published");
    }

    @Test
    @DisplayName("⛔ 已发布行改内容被拒；DEPRECATED 是终态")
    void publishedRowContentIsImmutableAndDeprecatedIsTerminal() {
        seedPublishedTool();
        ConfigLifecycleService port = lifecycle();

        // 内容改写 → DB 触发器拒绝
        assertThatThrownBy(() -> adminExec("UPDATE cap_tool_def SET name = 'hacked'"
                + " WHERE id = '" + TOOL_ID + "' AND version = 1"))
                .as("⛔ 已发布行的内容可被改写：不可变版本行形同虚设")
                .hasMessageContaining("管理员写入失败")
                .hasStackTraceContaining("已发布不可变版本行");

        // 停用
        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.PUBLISHED, ConfigLifecycleState.DEPRECATED)).isTrue();

        // 终态：DEPRECATED 不可迁出 —— 领域层给出可读拒绝
        assertThatThrownBy(() -> port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.DEPRECATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ DEPRECATED 可迁出：停用形同虚设")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("非法的生命周期迁移");

        // 纵深防御：绕过领域层直接打端口，DB 不可变触发器仍会挡住
        assertThatThrownBy(() -> rawLifecycle().transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.DEPRECATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 绕过状态机后 DB 也放行了：不可变触发器形同虚设")
                .hasStackTraceContaining("已发布不可变版本行");
    }

    @Test
    @DisplayName("⛔ 没有发布动作就不能自称 PUBLISHED（发布 = 发新版本行，不是改状态列）")
    void validatedCannotBePromotedToPublishedWithoutReleaseAction() {
        seedDraftTool();
        ConfigLifecycleService port = lifecycle();

        assertThat(port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.DRAFT, ConfigLifecycleState.VALIDATED)).isTrue();

        // 领域层：PUBLISHED 不能靠改状态列获得
        assertThatThrownBy(() -> port.transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 未发布的草稿能被直接置为 PUBLISHED：发布语义被架空")
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("PUBLISHED 不能通过修改状态列获得");

        // 纵深防御：绕过领域层直接打端口，DB 对齐触发器显式报错
        assertThatThrownBy(() -> rawLifecycle().transition("tool", null, TOOL_ID, 1,
                ConfigLifecycleState.VALIDATED, ConfigLifecycleState.PUBLISHED))
                .as("⛔ 绕过状态机后 DB 也放行了：未发布对象可自称已发布")
                .hasStackTraceContaining("必须同时设置 published_at");

        // 状态未被污染
        assertThat(port.stateOf("tool", null, TOOL_ID, 1))
                .contains(ConfigLifecycleState.VALIDATED);
    }

    @Test
    @DisplayName("⛔ 未发布的对象不可被 Agent 启用（不在当前发布 → 解析不出来）")
    void unpublishedObjectCannotBeEnabled() {
        // 只发布 Skill，不发布它声明的 Tool
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + SKILL_ID + "', 1, 'it skill', '[\"" + TOOL_ID + "\"]', '[]'::jsonb,"
                + " 'WRITE_EXTERNAL', '{}'::jsonb, 'platform', 'fp-skill', now())");
        publishPlatformObjects("skill:" + SKILL_ID + "@1");

        // 该 Tool 从未被发布 → 不在当前发布头里
        PublishedVersionPort versions = new JdbcPublishedVersionPort(appPool);
        assertThat(versions.currentVersion(CapObjectType.TOOL, TOOL_ID))
                .as("⛔ 未发布的 Tool 竟被解析出版本：未发布对象可被启用（§6.6）")
                .isEmpty();

        // Agent 启用该 Skill → 闭包解析必然失败
        String agentId = createAgentWithVersion(tenantA, uniqueName("it-lc-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantA + "'::uuid, '" + agentId + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '[\"" + SKILL_ID + "\"]'::jsonb, '{}'::jsonb,"
                + " 'fp-agent', now())");

        assertThatThrownBy(() -> resolver().resolve(tenantA, agentId, 1))
                .as("⛔ 未发布的 Tool 仍被解析进闭包：未发布对象可被 Agent 启用（§6.6）")
                .hasMessageContaining("已发布版本");
    }

    @Test
    @DisplayName("✅ 状态机与 DB 状态一致：PUBLISHED 是唯一可被新发布引用的状态")
    void onlyPublishedIsReferenceable() {
        ConfigLifecycleStateMachine machine = new ConfigLifecycleStateMachine();

        assertThat(ConfigLifecycleState.PUBLISHED.isReferenceable()).isTrue();
        assertThat(ConfigLifecycleState.DRAFT.isReferenceable()).isFalse();
        assertThat(ConfigLifecycleState.VALIDATED.isReferenceable()).isFalse();
        assertThat(ConfigLifecycleState.DEPRECATED.isReferenceable()).isFalse();

        assertThatThrownBy(() -> machine.assertReferenceable(ConfigLifecycleState.DEPRECATED,
                TOOL_ID))
                .isInstanceOf(ConfigPublishRejectedException.class);
    }

    // ── 夹具工具 ──────────────────────────────────────────────────

    /**
     * 闭包解析器（清单恒为空的替身 —— 本类只验证版本解析路径）。
     */
    private PermissionResolver resolver() {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                serverId -> McpManifestPort.ManifestSnapshot.available(Set.of()));
    }
}

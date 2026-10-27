package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.infrastructure.PlatformDdl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cap 核心表的<b>构建期结构门禁</b>（W-PERM-P0-T2）。
 *
 * <p>把「租户级表必须启用 RLS」「platform 级表必须<b>不</b>启用 RLS」
 * 这类约定变成构建期硬断言，而非依赖 Code Review 自觉。
 *
 * <p>⚠️ <b>为什么单独有一个结构测试</b>：{@code SchemaStructureTest} 面向全局基线，
 * 本类专门盯 cap_* 这批表 —— 若不补这个门禁，
 * 「新增 cap_* 表忘开 RLS」将<b>悄无声息</b>地进生产 ——
 * 而 RLS 缺失是 Sev-0 级跨租户泄漏（R-004）。
 *
 * <p>⚠️ <b>platform 表豁免 RLS 是刻意设计，不是遗漏</b>（§6.1 租户边界）：
 * {@code cap_tool_def} / {@code cap_skill_def} / {@code cap_mcp_server_def}
 * 是平台级定义，V1 只允许 platform 级定义。
 * 对它们误开 RLS 会让平台级定义对所有租户<b>不可见</b> → 全员授权失败。
 *
 * <p><b>取数源</b>：迁移脚本（原 V15）已随 2026-10-27 收口删除，
 * 现由 {@code deploy/sql/platform/01_platform_ddl.sql} 终态快照承担，见 {@link PlatformDdl}。
 */
class CapCoreSchemaStructureTest {

    /** 平台级表：V1 仅 platform 定义，**刻意不启用** RLS（§6.1）。 */
    private static final Set<String> PLATFORM_TABLES = Set.of(
            "cap_tool_def", "cap_skill_def", "cap_mcp_server_def");

    /** 租户级表：必须启用 RLS 且带 tenant_id。 */
    private static final Set<String> TENANT_TABLES = Set.of(
            "cap_agent_def", "cap_authz_version", "execution_capability_snapshot",
            "cap_result_event", "cap_execution_intent", "cap_config_release");

    @Test
    @DisplayName("结构权威源存在且非空")
    void ddlExists() {
        assertThat(PlatformDdl.text()).isNotBlank();
    }

    @Test
    @DisplayName("⚠️ 九张核心表全部建出")
    void allCoreTablesCreated() {
        assertThat(PlatformDdl.createdTables()).containsAll(qualified(TENANT_TABLES));
        assertThat(PlatformDdl.createdTables()).containsAll(qualified(PLATFORM_TABLES));
    }

    @Test
    @DisplayName("⚠️ 每个租户级表都必须启用 RLS")
    void tenantTablesAreRlsProtected() {
        List<String> rlsTables = PlatformDdl.rlsTables("ENABLE");
        List<String> unprotected = TENANT_TABLES.stream()
                .filter(t -> !rlsTables.contains("public." + t))
                .toList();
        assertThat(unprotected)
                .as("以下租户级表未启用 RLS，跨租户隔离存在缺口: %s", unprotected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ platform 级表必须**不**启用 RLS（误开会让平台定义对所有租户不可见）")
    void platformTablesHaveNoRls() {
        List<String> rlsTables = PlatformDdl.rlsTables("ENABLE");
        List<String> wronglyProtected = PLATFORM_TABLES.stream()
                .filter(t -> rlsTables.contains("public." + t))
                .toList();
        assertThat(wronglyProtected)
                .as("以下 platform 表被误开 RLS，平台级定义将对所有租户不可见: %s", wronglyProtected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 每个租户级表都必须声明 tenant_id 列")
    void tenantTablesHaveTenantIdColumn() {
        List<String> missing = new ArrayList<>();
        for (String table : TENANT_TABLES) {
            if (!PlatformDdl.tableBody(table).contains("tenant_id")) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("以下表缺少 tenant_id，无法参与 RLS 隔离: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 租户级表的 RLS 必须 FORCE（表 owner 也受策略约束）")
    void rlsIsForced() {
        List<String> forced = PlatformDdl.rlsTables("FORCE");
        List<String> notForced = TENANT_TABLES.stream()
                .filter(t -> !forced.contains("public." + t))
                .toList();
        assertThat(notForced)
                .as("以下表只 ENABLE 未 FORCE RLS —— 迁移角色正是表 owner，"
                        + "未 FORCE 时策略对 owner 完全不生效: %s", notForced)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 关键安全机制不得被移除")
    void securityMechanismsPresent() {
        String ddl = PlatformDdl.text();
        // 不可变版本行（§6.1）
        assertThat(ddl)
                .as("已发布版本行必须不可变，否则『改配置』可退化为原地改写")
                .contains("forbid_published_def_mutation");
        // 动态发现恒禁（N4）
        assertThat(ddl)
                .as("MCP 动态发现必须在 DB 层恒禁")
                .contains("cap_mcp_server_def_no_dynamic_ck");
        // 嵌套 Skill 恒禁（N1）
        assertThat(ddl)
                .as("嵌套 Skill（inherits）必须在 DB 层恒禁")
                .contains("cap_skill_def_no_inherits_ck");
        // 双人复核（06 §3）
        assertThat(ddl)
                .as("发布双人复核必须有 DB 层兜底")
                .contains("cap_config_release_two_person_ck");
        // 结果事件唯一（§7.4 只落账不重执行）
        assertThat(ddl)
                .as("同一 invocation 最多一条结果事件，是『只重试落账』的存储层保障")
                .contains("cap_result_event_invocation_unique");
        // 授权版本单调
        assertThat(ddl)
                .as("authzVersion 必须有下界约束")
                .contains("cap_authz_version_positive");
    }

    @Test
    @DisplayName("⚠️ 发布头必须用读写分离的 RLS 策略（租户不得写平台级发布）")
    void releasePoliciesSeparateReadAndWrite() {
        String policies = PlatformDdl
                .plain(String.join("\n", PlatformDdl.policies("cap_config_release")));
        assertThat(policies)
                .as("读策略必须放行平台级行（tenant_id IS NULL），否则全局发布对租户不可见")
                .contains("FOR SELECT USING tenant_id IS NULL OR tenant_id = current_tenant_id()");
        assertThat(policies)
                .as("发布头写策略必须只放行本租户行 —— 放行 NULL 等于任一租户都能发布全局配置")
                .contains("FOR INSERT WITH CHECK tenant_id = current_tenant_id()");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static List<String> qualified(Set<String> tables) {
        return tables.stream().map(t -> "public." + t).toList();
    }
}

package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15 核心表的<b>构建期结构门禁</b>（W-PERM-P0-T2）。
 *
 * <p>把「租户级表必须启用 RLS」「platform 级表必须<b>不</b>启用 RLS」
 * 这类约定变成构建期硬断言，而非依赖 Code Review 自觉。
 *
 * <p>⚠️ <b>为什么单独有一个结构测试</b>：既有 {@code SchemaStructureTest} 只扫
 * {@code V1__baseline_schema.sql}，新增迁移不在其范围内。
 * 若不补这个门禁，「新增 cap_* 表忘开 RLS」将<b>悄无声息</b>地进生产 ——
 * 而 RLS 缺失是 Sev-0 级跨租户泄漏（R-004）。
 *
 * <p>⚠️ <b>platform 表豁免 RLS 是刻意设计，不是遗漏</b>（§6.1 租户边界）：
 * {@code cap_tool_def} / {@code cap_skill_def} / {@code cap_mcp_server_def}
 * 是平台级定义，V1 只允许 platform 级定义。
 * 对它们误开 RLS 会让平台级定义对所有租户<b>不可见</b> → 全员授权失败。
 */
class CapCoreSchemaStructureTest {

    private static final Path DDL = Path.of(
            "src/main/resources/db/migration/V15__cap_core.sql");

    private static final String DDL_TEXT = readDdl();

    /** 平台级表：V1 仅 platform 定义，**刻意不启用** RLS（§6.1）。 */
    private static final Set<String> PLATFORM_TABLES = Set.of(
            "cap_tool_def", "cap_skill_def", "cap_mcp_server_def");

    /** 租户级表：必须启用 RLS 且带 tenant_id。 */
    private static final Set<String> TENANT_TABLES = Set.of(
            "cap_agent_def", "cap_authz_version", "execution_capability_snapshot",
            "cap_result_event", "cap_execution_intent", "cap_config_release");

    @Test
    @DisplayName("V15 迁移脚本存在且非空")
    void ddlExists() {
        assertThat(DDL_TEXT).isNotBlank();
    }

    @Test
    @DisplayName("⚠️ 九张核心表全部建出")
    void allCoreTablesCreated() {
        assertThat(createdTables()).containsAll(TENANT_TABLES);
        assertThat(createdTables()).containsAll(PLATFORM_TABLES);
    }

    @Test
    @DisplayName("⚠️ 每个租户级表都必须出现在 RLS 策略数组中")
    void tenantTablesAreRlsProtected() {
        List<String> rlsTables = rlsEnabledTables();
        List<String> unprotected = TENANT_TABLES.stream()
                .filter(t -> !rlsTables.contains(t))
                .toList();
        assertThat(unprotected)
                .as("以下租户级表未启用 RLS，跨租户隔离存在缺口: %s", unprotected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ platform 级表必须**不**启用 RLS（误开会让平台定义对所有租户不可见）")
    void platformTablesHaveNoRls() {
        List<String> rlsTables = rlsEnabledTables();
        List<String> wronglyProtected = PLATFORM_TABLES.stream()
                .filter(rlsTables::contains)
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
            if (!tableBody(table).contains("tenant_id")) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("以下表缺少 tenant_id，无法参与 RLS 隔离: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ RLS 必须 FORCE（表 owner 也受策略约束）")
    void rlsIsForced() {
        assertThat(DDL_TEXT).contains("FORCE ROW LEVEL SECURITY");
    }

    @Test
    @DisplayName("⚠️ 关键安全机制不得被移除")
    void securityMechanismsPresent() {
        // 不可变版本行（§6.1）
        assertThat(DDL_TEXT)
                .as("已发布版本行必须不可变，否则『改配置』可退化为原地改写")
                .contains("forbid_published_def_mutation");
        // 动态发现恒禁（N4）
        assertThat(DDL_TEXT)
                .as("MCP 动态发现必须在 DB 层恒禁")
                .contains("cap_mcp_server_def_no_dynamic_ck");
        // 嵌套 Skill 恒禁（N1）
        assertThat(DDL_TEXT)
                .as("嵌套 Skill（inherits）必须在 DB 层恒禁")
                .contains("cap_skill_def_no_inherits_ck");
        // 双人复核（06 §3）
        assertThat(DDL_TEXT)
                .as("发布双人复核必须有 DB 层兜底")
                .contains("cap_config_release_two_person_ck");
        // 结果事件唯一（§7.4 只落账不重执行）
        assertThat(DDL_TEXT)
                .as("同一 invocation 最多一条结果事件，是『只重试落账』的存储层保障")
                .contains("cap_result_event_invocation_unique");
        // 授权版本单调
        assertThat(DDL_TEXT)
                .as("authzVersion 必须有下界约束")
                .contains("cap_authz_version_positive");
    }

    @Test
    @DisplayName("⚠️ 发布头必须用读写分离的 RLS 策略（租户不得写平台级发布）")
    void releasePoliciesSeparateReadAndWrite() {
        assertThat(DDL_TEXT).contains("cap_config_release_read");
        assertThat(DDL_TEXT).contains("cap_config_release_write");
        // 写策略不得放行 tenant_id IS NULL —— 否则任一租户都能发布全局配置
        assertThat(DDL_TEXT)
                .as("发布头写策略必须只放行本租户行")
                .contains("WITH CHECK (tenant_id = current_tenant_id())");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String readDdl() {
        try {
            return Files.readString(DDL, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 DDL 失败: " + DDL, e);
        }
    }

    private static List<String> createdTables() {
        Matcher m = Pattern.compile("CREATE TABLE\\s+(\\w+)").matcher(DDL_TEXT);
        List<String> tables = new ArrayList<>();
        while (m.find()) {
            tables.add(m.group(1));
        }
        return tables;
    }

    /**
     * 提取所有启用了 RLS 的表。
     *
     * <p>⚠️ <b>两条来源都要取</b>：
     * <ul>
     *   <li>{@code tenant_tables} 数组 —— 由 DO 循环统一批量启用；</li>
     *   <li>显式 {@code ALTER TABLE ... ENABLE ROW LEVEL SECURITY} ——
     *       {@code cap_config_release} 走的是<b>读写分离</b>的独立策略
     *       （读放行 PLATFORM 行、写只放行本租户行），
     *       不能用统一的 {@code tenant_id = current_tenant_id()} 套模板，
     *       因此它不在数组里。</li>
     * </ul>
     * 只取其一都会得出错误结论：只取数组会误报 {@code cap_config_release} 缺 RLS，
     * 只取显式语句则会漏掉数组里的五张表。
     */
    private static List<String> rlsEnabledTables() {
        List<String> names = new ArrayList<>();

        Matcher m = Pattern.compile("tenant_tables\\s+TEXT\\[\\]\\s*:=\\s*ARRAY\\[(.*?)];",
                Pattern.DOTALL).matcher(DDL_TEXT);
        if (m.find()) {
            Matcher q = Pattern.compile("'(\\w+)'").matcher(m.group(1));
            while (q.find()) {
                names.add(q.group(1));
            }
        }

        Matcher e = Pattern.compile(
                "ALTER TABLE\\s+(\\w+)\\s+ENABLE ROW LEVEL SECURITY").matcher(DDL_TEXT);
        while (e.find()) {
            if (!names.contains(e.group(1))) {
                names.add(e.group(1));
            }
        }
        return names;
    }

    /** 提取单张表的建表体（含括号内全部内容）。 */
    private static String tableBody(String table) {
        int start = DDL_TEXT.indexOf("CREATE TABLE " + table);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        int i = DDL_TEXT.indexOf('(', start);
        if (i < 0) {
            return "";
        }
        for (int j = i; j < DDL_TEXT.length(); j++) {
            char c = DDL_TEXT.charAt(j);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return DDL_TEXT.substring(start, j);
                }
            }
        }
        return "";
    }
}

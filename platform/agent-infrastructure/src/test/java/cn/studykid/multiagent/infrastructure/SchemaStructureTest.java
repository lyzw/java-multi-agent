package cn.studykid.multiagent.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据模型结构约束测试（ADR-004 / ADR-006 / P1-02）。
 *
 * <p>把「所有租户表必须启用 RLS」「所有对外引用必须带 tenant_id」
 * 这类约定变成<b>构建期硬门禁</b>，而非依赖 Code Review 自觉。
 *
 * <p>背景：R-004（跨租户访问）定级 3/5 高，一旦新增表忘记启用 RLS，
 * 就是 Sev-0 泄漏。人工 Review 在表数量增长后必然失效。
 *
 * <p>本测试只做<b>结构断言</b>；约束是否真正生效由
 * {@code verify/V1_constraint_check.sql} 在真实 PG 上实测验证。
 */
class SchemaStructureTest {

    private static final Path DDL = Path.of(
            "src/main/resources/db/migration/V1__baseline_schema.sql");

    private static final String DDL_TEXT = readDdl();

    /** 无需 RLS 的表：租户自身即隔离单元，或为纯配置表。 */
    private static final Set<String> RLS_EXEMPT_TABLES = Set.of("tenant");

    /** 必须存在的安全约束关键词——缺一即视为安全能力退化。 */
    private static final List<String> REQUIRED_SECURITY_MECHANISMS = List.of(
            "FORCE ROW LEVEL SECURITY",          // 表 owner 也受策略约束
            "current_tenant_id",                 // 租户上下文函数
            "WITH CHECK",                        // 写保护
            "enforce_run_state_transition",      // 终态不可迁出
            "forbid_snapshot_mutation",          // 快照不可变
            "enforce_fencing_on_write",          // fencing 防回退
            "forbid_attempt_after_terminal",     // UNKNOWN 禁止盲目重试
            "budget_no_overspend"                // 预算不超发
    );

    @Test
    @DisplayName("DDL 迁移脚本存在且非空")
    void ddlExists() {
        assertThat(DDL_TEXT).isNotBlank();
    }

    @Test
    @DisplayName("⚠️ 每个租户级表都必须出现在 RLS 策略列表中")
    void allTenantTablesAreProtectedByRls() {
        List<String> tables = createdTables();
        assertThat(tables).as("应至少创建 15 张表").hasSizeGreaterThanOrEqualTo(15);

        List<String> unprotected = tables.stream()
                .filter(t -> !RLS_EXEMPT_TABLES.contains(t))
                .filter(t -> !tenantTablesArray().contains(t))
                .toList();

        assertThat(unprotected)
                .as("以下表未加入 RLS 策略数组，租户隔离存在缺口: %s", unprotected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 每个租户级表都必须声明 tenant_id 列")
    void allTenantTablesHaveTenantId() {
        List<String> missing = new ArrayList<>();
        for (String table : createdTables()) {
            if (RLS_EXEMPT_TABLES.contains(table)) {
                continue;
            }
            String body = tableBody(table);
            if (!body.contains("tenant_id")) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("以下表缺少 tenant_id，无法参与 RLS 隔离: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 关键安全机制不得被移除")
    void securityMechanismsPresent() {
        List<String> missing = REQUIRED_SECURITY_MECHANISMS.stream()
                .filter(m -> !DDL_TEXT.contains(m))
                .toList();
        assertThat(missing)
                .as("以下安全机制在 DDL 中缺失，属安全能力退化: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("三层 Attempt 语义在 DDL 中显式区分")
    void threeLayerAttemptSemantics() {
        // 逻辑操作表与 attempt 表必须分离（P0-D03 §4 红线 4）
        assertThat(createdTables()).contains("tool_operation", "tool_operation_attempt");
        // UNKNOWN 必须存在于 attempt 的状态枚举中
        assertThat(DDL_TEXT).contains("result_unknown");
        assertThat(DDL_TEXT)
                .as("UNKNOWN attempt 必须被标记，否则无法区分『明确失败可重试』与『未知须对账』")
                .contains("result_unknown   BOOLEAN");
    }

    @Test
    @DisplayName("幂等键在租户维度唯一（ADR-003）")
    void idempotencyKeysAreTenantScoped() {
        assertThat(DDL_TEXT)
                .as("Run 幂等键必须含 tenant_id，避免跨租户键冲突")
                .contains("UNIQUE (tenant_id, idempotency_key)");
        assertThat(DDL_TEXT)
                .as("ToolOperation 幂等键必须含 tenant_id")
                .contains("tool_operation_idempotency_unique UNIQUE (tenant_id, idempotency_key)");
    }

    @Test
    @DisplayName("Outbox 与 Run 同事务原子提交所需的字段齐备（红线 3）")
    void outboxSupportsAtomicCommit() {
        assertThat(createdTables()).contains("outbox_event", "idempotency_record", "run_event");
        assertThat(DDL_TEXT)
                .as("Outbox 需有发布状态以支持重试")
                .contains("CREATE TYPE outbox_state");
        assertThat(DDL_TEXT)
                .as("Outbox 待发布索引，用于 Publisher 轮询")
                .contains("idx_outbox_pending");
    }

    @Test
    @DisplayName("事件序号唯一，SSE 续传有依据（FR-014）")
    void eventSequenceSupportsSseResume() {
        assertThat(DDL_TEXT).contains("UNIQUE (tenant_id, run_id, sequence_no)");
        assertThat(DDL_TEXT).contains("idx_run_event_cursor");
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

    /** 提取 DO 块中 RLS 策略数组列出的表名。 */
    private static List<String> tenantTablesArray() {
        Matcher m = Pattern.compile("tenant_tables\\s+TEXT\\[\\]\\s*:=\\s*ARRAY\\[(.*?)];", Pattern.DOTALL)
                .matcher(DDL_TEXT);
        if (!m.find()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher q = Pattern.compile("'(\\w+)'").matcher(m.group(1));
        while (q.find()) {
            names.add(q.group(1));
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

    @Test
    @DisplayName("辅助方法：租户表数组解析正确")
    void helperParsesTenantTables() {
        assertThat(tenantTablesArray())
                .as("RLS 策略数组应覆盖全部租户表")
                .contains("agent", "run", "tool_operation", "audit_record")
                .doesNotContain("tenant");
    }

    @Test
    @DisplayName("辅助方法：表体提取正确")
    void helperExtractsTableBody() {
        assertThat(tableBody("run").toLowerCase(Locale.ROOT))
                .contains("fencing_token")
                .contains("idempotency_key");
        assertThat(tableBody("nonexistent_table")).isEmpty();
    }
}
package cn.studykid.multiagent.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * {@code db/migration/verify/V1_constraint_check.sql} 在真实 PG 上实测验证。
 *
 * <p><b>⚠️ 取数源已换</b>：2026-10-27 收口后迁移脚本被删，结构唯一权威源是
 * {@code deploy/sql/platform/01_platform_ddl.sql}（{@code pg_dump} 终态快照）。
 * 该文件同时含 {@code public}（租户域）与 {@code platform}（平台域）两个 schema，
 * 因此本类的表名一律用 <b>schema 限定名</b>，豁免判定见 {@link #isTenantScopedTable}。
 */
class SchemaStructureTest {

    /**
     * 无需 RLS 的租户域表：租户自身即隔离单元，或为平台级定义。
     *
     * <p>⚠️ 2026-11-07 统一模型配置：{@code model_catalog}（模型目录）与
     * {@code model_provider_credential}（供应商密钥密文）是<b>平台级全局资源</b>，
     * 由超级管理员经 {@code /api/admin/models}、{@code /api/admin/model-credentials}
     * 维护、所有租户共用，设计上<b>不带 tenant_id、不挂 RLS</b>（见
     * {@code docs/模型统一配置架构设计.md} §7.2 ③）。它们不是租户数据，
     * 故登记为有界豁免；密钥表落库为 AES-256-GCM 密文且仅平台管理端点可读写。
     */
    private static final Set<String> RLS_EXEMPT_TABLES = Set.of(
            "public.tenant",
            "public.cap_tool_def", "public.cap_skill_def", "public.cap_mcp_server_def",
            "public.model_catalog", "public.model_provider_credential");

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
    @DisplayName("结构权威源存在且非空")
    void ddlExists() {
        assertThat(PlatformDdl.text()).isNotBlank();
    }

    @Test
    @DisplayName("⚠️ 每个租户域表都必须出现在 RLS 策略列表中")
    void allTenantTablesAreProtectedByRls() {
        List<String> tables = tenantScopedTables();
        assertThat(tables).as("快照应至少建出 40 张租户域表").hasSizeGreaterThanOrEqualTo(40);

        List<String> unprotected = tables.stream()
                .filter(t -> !RLS_EXEMPT_TABLES.contains(t))
                .filter(t -> !PlatformDdl.policyTables().contains(t))
                .toList();

        assertThat(unprotected)
                .as("以下表未挂 RLS 策略，租户隔离存在缺口: %s", unprotected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 每个租户域表都必须声明 tenant_id 列")
    void allTenantTablesHaveTenantId() {
        List<String> missing = new ArrayList<>();
        for (String table : tenantScopedTables()) {
            if (RLS_EXEMPT_TABLES.contains(table)) {
                continue;
            }
            if (!PlatformDdl.tableBody(table).contains("tenant_id")) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("以下表缺少 tenant_id，无法参与 RLS 隔离: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 平台域（platform schema）表整体不挂 RLS 策略")
    void platformSchemaTablesAreNotRlsProtected() {
        List<String> wronglyProtected = PlatformDdl.createdTables().stream()
                .filter(t -> t.startsWith("platform."))
                .filter(PlatformDdl.policyTables()::contains)
                .toList();
        assertThat(wronglyProtected)
                .as("平台域表被挂了租户 RLS 策略，平台侧将读不到自己的定义: %s", wronglyProtected)
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 关键安全机制不得被移除")
    void securityMechanismsPresent() {
        List<String> missing = REQUIRED_SECURITY_MECHANISMS.stream()
                .filter(m -> !PlatformDdl.text().contains(m))
                .toList();
        assertThat(missing)
                .as("以下安全机制在结构权威源中缺失，属安全能力退化: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("三层 Attempt 语义在结构中显式区分")
    void threeLayerAttemptSemantics() {
        // 逻辑操作表与 attempt 表必须分离（P0-D03 §4 红线 4）
        assertThat(PlatformDdl.createdTables())
                .contains("public.tool_operation", "public.tool_operation_attempt");
        // UNKNOWN 必须存在于 attempt 的状态枚举中
        assertThat(PlatformDdl.text()).contains("result_unknown");
        assertThat(PlatformDdl.tableBody("public.tool_operation_attempt").toLowerCase(Locale.ROOT))
                .as("UNKNOWN attempt 必须被标记，否则无法区分『明确失败可重试』与『未知须对账』")
                .contains("result_unknown");
    }

    @Test
    @DisplayName("幂等键在租户维度唯一（ADR-003）")
    void idempotencyKeysAreTenantScoped() {
        assertThat(PlatformDdl.text())
                .as("Run 幂等键必须含 tenant_id，避免跨租户键冲突")
                .contains("UNIQUE (tenant_id, idempotency_key)");
        assertThat(PlatformDdl.text())
                .as("ToolOperation 幂等键必须含 tenant_id")
                .contains("tool_operation_idempotency_unique UNIQUE (tenant_id, idempotency_key)");
    }

    @Test
    @DisplayName("Outbox 与 Run 同事务原子提交所需的字段齐备（红线 3）")
    void outboxSupportsAtomicCommit() {
        assertThat(PlatformDdl.createdTables())
                .contains("public.outbox_event", "public.idempotency_record", "public.run_event");
        assertThat(PlatformDdl.text())
                .as("Outbox 需有发布状态以支持重试")
                .contains("CREATE TYPE public.outbox_state");
        assertThat(PlatformDdl.text())
                .as("Outbox 待发布索引，用于 Publisher 轮询")
                .contains("idx_outbox_pending");
    }

    @Test
    @DisplayName("事件序号唯一，SSE 续传有依据（FR-014）")
    void eventSequenceSupportsSseResume() {
        assertThat(PlatformDdl.text()).contains("UNIQUE (tenant_id, run_id, sequence_no)");
        assertThat(PlatformDdl.text()).contains("idx_run_event_cursor");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 租户域表 = 快照里建出的全部表，除去平台域（{@code platform} schema）。
     *
     * <p>判据用 <b>schema</b> 而不是表名清单：平台身份域（W-PERM-P0 之后的 V31）
     * 天生跨租户，给它加租户 RLS 反而会让平台侧读不到自己的定义。
     */
    private static List<String> tenantScopedTables() {
        return PlatformDdl.createdTables().stream()
                .filter(SchemaStructureTest::isTenantScopedTable)
                .toList();
    }

    private static boolean isTenantScopedTable(String qualifiedName) {
        return !qualifiedName.startsWith("platform.");
    }

    @Test
    @DisplayName("辅助方法：RLS 策略表解析正确")
    void helperParsesTenantTables() {
        assertThat(PlatformDdl.policyTables())
                .as("RLS 策略应覆盖租户域表")
                .contains("public.agent", "public.run", "public.tool_operation",
                        "public.audit_record")
                .doesNotContain("public.tenant");
    }

    @Test
    @DisplayName("辅助方法：表体提取正确")
    void helperExtractsTableBody() {
        assertThat(PlatformDdl.tableBody("public.run").toLowerCase(Locale.ROOT))
                .contains("fencing_token")
                .contains("idempotency_key");
        assertThat(PlatformDdl.tableBody("nonexistent_table")).isEmpty();
    }
}

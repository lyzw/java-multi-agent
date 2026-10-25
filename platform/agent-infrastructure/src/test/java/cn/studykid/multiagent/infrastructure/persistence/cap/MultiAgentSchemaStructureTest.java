package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多级 Agent 三张表的<b>迁移脚本结构门禁</b>（多级 Agent v1.1 · 第二十七批 · A8）。
 *
 * <p><b>⚠️ 本类要补的是一个真实的回归防护缺口</b>：
 * 多级 Agent 的三张表（{@code agent_invocation} / {@code cap_agent_child_ref} /
 * {@code cap_result_contract}）的表结构<b>只由迁移脚本定义</b>，而迁移脚本
 * <b>只在部署新环境时执行</b>。这带来一个容易被忽略的时序问题：
 *
 * <blockquote>
 * 脚本被改坏 → <b>已部署的环境察觉不到</b>（库里已经是旧结构）→
 * 现有测试照样全绿（行为断言跑在<b>早就建好的库</b>上）→
 * 直到某天<b>新建环境</b>，改坏的脚本才被执行 → 那时线上环境已经建起来了。
 * </blockquote>
 *
 * <p>既有的 {@code CapCoreSchemaStructureTest} 只扫 {@code V15__cap_core.sql}，
 * 这三张表<b>完全不在结构门禁的覆盖范围内</b>。
 *
 * <p><b>为什么不能只加「行为断言」</b>：行为断言（写入后能否读回、非法值是否被拒）
 * 验证的是<b>当前库</b>，不是<b>脚本</b>。脚本退化后，
 * 只有<b>新环境</b>才会中招 —— 而新环境恰恰是最不该出事的那个。
 * 故本类做的是<b>脚本级</b>断言：直接读迁移脚本，推出「部署后应当存在的结构」。
 *
 * <p><b>⚠️ 本类断言的是「跨多版本迁移后的有效结构」，不是任何单个脚本的原文</b>。
 * 这是必需的：{@code cap_result_contract.contract_id} 在 V20 建表时是 {@code UUID}，
 * V23 才改成 {@code TEXT}（对外契约把 id 定义为可读字符串）。
 * 若只看 V20 的原文会得到「契约 id 是 UUID」这个<b>已被修复的缺陷</b>，
 * 而真正要防的是「它被改回 UUID」。
 *
 * <p><b>⚠️ 解析的是 SQL 文本，不是「随便 grep 几个关键词」</b>：
 * 建表语句里的列定义、CHECK 约束、外键、索引、触发器都被解析成结构，
 * 断言落在<b>结构语义</b>上（例如「状态集合恰好是七态」而不是「脚本里出现过 SUCCEEDED」）。
 *
 * <p><b>⚠️ 反证纪律</b>：本类的每条断言都必须能被单独证伪 ——
 * 见 {@code scripts/c27-1-counterproof.sh}（若干处退化，逐一验证用例真的会红，
 * 且<b>不该受影响的用例必须保持绿</b>）。
 */
class MultiAgentSchemaStructureTest {

    /** 迁移脚本目录。 */
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** 三张被门禁覆盖的表。 */
    private static final List<String> GUARDED_TABLES =
            List.of("agent_invocation", "cap_agent_child_ref", "cap_result_contract");

    /** 委派记录的状态集合（07 §状态与失败语义）：<b>封闭</b>，不多不少。 */
    private static final Set<String> INVOCATION_STATUSES = Set.of(
            "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN");

    /** 全部迁移脚本，按版本号<b>数值</b>升序（V10 排在 V9 之后）。 */
    private static final List<Migration> MIGRATIONS = loadMigrations();

    /** 三张表的<b>有效结构</b>（跨全部迁移累计后的结果）。 */
    private static final Map<String, TableSchema> SCHEMAS = buildSchemas();

    // ------------------------------------------------------------------
    // 1. 表与列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三张表都由迁移脚本建出（缺表 = 整条多级 Agent 链路无处落地）")
    void guardedTablesAreCreated() {
        for (String table : GUARDED_TABLES) {
            assertThat(SCHEMAS)
                    .as("迁移脚本里没有建出表 %s", table)
                    .containsKey(table);
        }
    }

    @Test
    @DisplayName("⚠️ agent_invocation 的关键列必须存在且类型正确")
    void invocationColumnsAreCorrect() {
        TableSchema s = SCHEMAS.get("agent_invocation");

        assertThat(s.columns().keySet())
                .as("agent_invocation 缺少关键列")
                .contains("invocation_id", "tenant_id", "run_id", "parent_invocation_id",
                        "parent_agent_id", "parent_agent_version",
                        "target_agent_id", "target_agent_version",
                        "status", "depth", "trace_id",
                        "input_digest", "output_digest", "reason_code",
                        "fencing_token", "started_at", "finished_at");

        // ⚠️ 目标必须是**固定版本**（禁止 latest 漂移别名）——
        //    版本列必须是整数，且不允许出现「latest」之类的别名列。
        assertThat(s.columnType("target_agent_version")).isEqualToIgnoringCase("INTEGER");
        assertThat(s.columnType("parent_agent_version")).isEqualToIgnoringCase("INTEGER");
        assertThat(s.columns().keySet())
                .as("委派目标必须固定版本，不允许出现版本别名列")
                .noneMatch(c -> c.toLowerCase(Locale.ROOT).contains("latest"));

        // ⚠️ 摘要列允许为空（存量行可能没有摘要），但类型必须是文本。
        assertThat(s.columnType("input_digest")).isEqualToIgnoringCase("TEXT");
        assertThat(s.columnType("output_digest")).isEqualToIgnoringCase("TEXT");
        assertThat(s.notNullColumns())
                .as("摘要列允许为空（存量行可能没有摘要）")
                .doesNotContain("input_digest", "output_digest");

        // ⚠️ 第二十三批 A3 补的「模型原始候选」列必须还在（V25）。
        assertThat(s.columnType("requested_target_agent_id"))
                .as("V25 补的「模型原始候选」列丢失 → 路由降级再次无法审计")
                .isEqualToIgnoringCase("UUID");
    }

    @Test
    @DisplayName("⚠️ cap_agent_child_ref 的关键列必须存在且类型正确")
    void childRefColumnsAreCorrect() {
        TableSchema s = SCHEMAS.get("cap_agent_child_ref");

        assertThat(s.columns().keySet())
                .as("cap_agent_child_ref 缺少关键列")
                .contains("tenant_id", "parent_agent_id", "parent_release_version",
                        "child_agent_id", "child_release_version",
                        "ordinal", "required", "created_at");

        // ⚠️ 父子两侧都必须是**固定版本**（V19 的不可妥协约束 5）。
        assertThat(s.columnType("parent_release_version")).isEqualToIgnoringCase("INTEGER");
        assertThat(s.columnType("child_release_version")).isEqualToIgnoringCase("INTEGER");
        assertThat(s.columnType("ordinal")).isEqualToIgnoringCase("INTEGER");
        assertThat(s.columnType("required")).isEqualToIgnoringCase("BOOLEAN");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 的关键列必须存在，且 contract_id 必须是 TEXT 而非 UUID")
    void resultContractColumnsAreCorrect() {
        TableSchema s = SCHEMAS.get("cap_result_contract");

        assertThat(s.columns().keySet())
                .as("cap_result_contract 缺少关键列")
                .contains("tenant_id", "contract_id", "version",
                        "schema_json", "config_fingerprint",
                        "schema_version", "created_at", "published_at");

        // ⚠️ **这是一条真实修过的缺陷**（V23）：对外契约把契约 id 定义为可读字符串
        //    （common.schema.json#/$defs/id），治理通道也按字符串接收；
        //    而 DB 一度声明成 UUID → 非 UUID id 的发布事务**整笔回滚**，
        //    运行期每次查询都抛异常 → 委派恒 fail-closed。
        //    故这里断言的是「**有效类型**是 TEXT」，而不是 V20 的建表原文。
        assertThat(s.columnType("contract_id"))
                .as("契约 id 必须保持可读字符串（TEXT）；改回 UUID 会让非 UUID 形式的"
                        + "契约在发布链路整笔回滚、运行期恒 fail-closed（V23 已修）")
                .isEqualToIgnoringCase("TEXT");

        // ⚠️ 契约定义本身（JSON Schema）是发布期固化的事实 → 必填。
        assertThat(s.notNullColumns())
                .as("schema_json / config_fingerprint 必须 NOT NULL")
                .contains("schema_json", "config_fingerprint");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 必须带 lifecycle_state（V26 · 停用维度的落点）")
    void resultContractHasLifecycleState() {
        TableSchema s = SCHEMAS.get("cap_result_contract");

        assertThat(s.columns())
                .as("cap_result_contract 缺 lifecycle_state → 这张表只能表达"
                        + "「已发布/未发布」两态，「已停用」在数据层无法表达（V26 已补）")
                .containsKey("lifecycle_state");
        assertThat(s.columnType("lifecycle_state")).isEqualToIgnoringCase("cap_lifecycle_state");
        assertThat(s.notNullColumns()).contains("lifecycle_state");
    }

    // ------------------------------------------------------------------
    // 2. 主键与外键
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 三张表的主键都必须包含 tenant_id")
    void primaryKeysAreTenantScoped() {
        for (String table : GUARDED_TABLES) {
            assertThat(SCHEMAS.get(table).primaryKey())
                    .as("%s 的主键必须以 tenant_id 打头（否则跨租户寻址失去隔离维度）",
                            table)
                    .isNotEmpty()
                    .startsWith("tenant_id");
        }
    }

    @Test
    @DisplayName("⚠️ agent_invocation 主键必须是 (tenant_id, invocation_id)")
    void invocationPrimaryKeyIsExact() {
        assertThat(SCHEMAS.get("agent_invocation").primaryKey())
                .as("重复投递不创建重复逻辑调用（MA-T10）依赖这个主键")
                .containsExactly("tenant_id", "invocation_id");
    }

    @Test
    @DisplayName("⚠️ cap_agent_child_ref 必须有两条复合外键指向 cap_agent_def（父 + 子）")
    void childRefHasParentAndChildForeignKeys() {
        List<ForeignKey> fks = SCHEMAS.get("cap_agent_child_ref").foreignKeys();
        assertThat(fks)
                .as("cap_agent_child_ref 必须同时约束父版本与子版本")
                .hasSize(2);

        List<ForeignKey> toAgentDef = fks.stream()
                .filter(fk -> "cap_agent_def".equals(fk.referencedTable()))
                .toList();
        assertThat(toAgentDef)
                .as("父子两侧的外键都必须指向 cap_agent_def")
                .hasSize(2);

        for (ForeignKey fk : toAgentDef) {
            // ⚠️ 复合外键的第一列必须是 tenant_id —— 顺带保证租户一致。
            assertThat(fk.columns())
                    .as("复合外键 %s 必须以 tenant_id 打头（顺带保证租户一致）", fk.name())
                    .startsWith("tenant_id");
            assertThat(fk.referencedColumns())
                    .as("复合外键 %s 必须指向 (tenant_id, agent_id, version)", fk.name())
                    .containsExactly("tenant_id", "agent_id", "version");
            // ⚠️ 子引用被删 → 投影行必须跟着删（ON DELETE CASCADE），
            //    否则父版本删除后会留下悬空的边。
            assertThat(fk.onDelete())
                    .as("复合外键 %s 必须 ON DELETE CASCADE（否则留下悬空边）", fk.name())
                    .isEqualTo("CASCADE");
        }
    }

    // ------------------------------------------------------------------
    // 3. CHECK 约束（封闭集合 / 不变量）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ agent_invocation 的状态集合必须**恰好**是那七态（封闭，多一个少一个都不行）")
    void invocationStatusIsClosed() {
        String expr = SCHEMAS.get("agent_invocation")
                .checkExpression("agent_invocation_status_ck");
        assertThat(expr)
                .as("agent_invocation 的状态 CHECK 约束缺失 → 状态集合不再封闭，"
                        + "任意字符串都能写进终态（MA-T13）")
                .isNotNull();

        Set<String> actual = quotedLiterals(expr);
        assertThat(actual)
                .as("状态集合必须是封闭的七态：多一个（未定义状态能写进库）或"
                        + "少一个（合法状态被拒）都是缺陷")
                .containsExactlyInAnyOrderElementsOf(INVOCATION_STATUSES);
    }

    @Test
    @DisplayName("⚠️ agent_invocation 的世代令牌必须为正（MA-T12 的地基）")
    void fencingTokenMustBePositive() {
        String expr = SCHEMAS.get("agent_invocation")
                .checkExpression("agent_invocation_fencing_ck");
        assertThat(expr)
                .as("fencing_token > 0 的约束缺失 → 世代令牌可为 0/负，"
                        + "旧世代提交终态将不再被拒（MA-T12 失效）")
                .isNotNull();
        assertThat(expr.replaceAll("\\s+", " ")).contains("fencing_token > 0");
    }

    @Test
    @DisplayName("⚠️ agent_invocation 的根调用判定必须同时覆盖「depth=1 无父」与「depth>1 有父」")
    void invocationParentRelationIsBidirectional() {
        String expr = SCHEMAS.get("agent_invocation")
                .checkExpression("agent_invocation_parent_ck");
        assertThat(expr)
                .as("父子关系判定约束缺失 → 悬空根可写入，调用树无法重建")
                .isNotNull();

        String flat = expr.replaceAll("\\s+", " ");
        assertThat(flat)
                .as("根调用（depth=1）必须要求 parent_invocation_id 为空")
                .contains("depth = 1 AND parent_invocation_id IS NULL");
        assertThat(flat)
                .as("非根调用（depth>1）必须要求 parent_invocation_id 非空")
                .contains("depth > 1 AND parent_invocation_id IS NOT NULL");
    }

    @Test
    @DisplayName("⚠️ agent_invocation 的深度必须为正")
    void invocationDepthMustBePositive() {
        String expr = SCHEMAS.get("agent_invocation")
                .checkExpression("agent_invocation_depth_ck");
        assertThat(expr).as("depth > 0 约束缺失").isNotNull();
        assertThat(expr.replaceAll("\\s+", " ")).contains("depth > 0");
    }

    @Test
    @DisplayName("⚠️ cap_agent_child_ref 的 ordinal 与版本号必须非负/为正")
    void childRefOrdinalAndVersionAreBounded() {
        TableSchema s = SCHEMAS.get("cap_agent_child_ref");

        String ordinal = s.checkExpression("cap_agent_child_ref_ordinal_ck");
        assertThat(ordinal).as("ordinal >= 0 约束缺失").isNotNull();
        assertThat(ordinal.replaceAll("\\s+", " ")).contains("ordinal >= 0");

        String version = s.checkExpression("cap_agent_child_ref_version_ck");
        assertThat(version).as("版本号 > 0 约束缺失").isNotNull();
        assertThat(version.replaceAll("\\s+", " "))
                .contains("parent_release_version > 0")
                .contains("child_release_version > 0");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 的版本号与内容指纹必须非空")
    void resultContractVersionAndFingerprintAreBounded() {
        TableSchema s = SCHEMAS.get("cap_result_contract");

        String version = s.checkExpression("cap_result_contract_version_ck");
        assertThat(version).as("version > 0 约束缺失").isNotNull();
        assertThat(version.replaceAll("\\s+", " ")).contains("version > 0");

        String fp = s.checkExpression("cap_result_contract_fingerprint_ck");
        assertThat(fp).as("内容指纹非空约束缺失 → 空指纹行无法参与「契约变了没有」的判定")
                .isNotNull();
        assertThat(fp.replaceAll("\\s+", " ")).contains("config_fingerprint");
    }

    // ------------------------------------------------------------------
    // 4. RLS 租户隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 三张表都必须启用 RLS **且 FORCE**（未 FORCE 时表 owner 仍能跨租户读）")
    void allThreeTablesAreRlsForced() {
        for (String table : GUARDED_TABLES) {
            TableSchema s = SCHEMAS.get(table);
            assertThat(s.rlsEnabled())
                    .as("%s 未启用 RLS → 跨租户泄漏（R-004 · Sev-0）", table)
                    .contains(table);
            assertThat(s.rlsForced())
                    .as("%s 未 FORCE RLS → 表 owner（迁移角色正是 owner）不受策略约束，"
                            + "隔离形同虚设", table)
                    .contains(table);
        }
    }

    @Test
    @DisplayName("⚠️ RLS 策略必须**读写双向**收口（只写 USING 不写 WITH CHECK 等于只挡读）")
    void rlsPoliciesGuardBothReadsAndWrites() {
        for (String table : GUARDED_TABLES) {
            List<String> policies = SCHEMAS.get(table).policies();
            assertThat(policies)
                    .as("%s 没有 RLS 策略", table)
                    .isNotEmpty();

            boolean guarded = policies.stream().anyMatch(p ->
                    p.contains("tenant_id = current_tenant_id()")
                            && p.contains("WITH CHECK"));
            assertThat(guarded)
                    .as("%s 的 RLS 策略必须同时约束读（USING）与写（WITH CHECK）", table)
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 5. 触发器（不可变保证）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ cap_result_contract 必须挂不可变触发器（已发布版本行不可改写）")
    void resultContractIsImmutableOncePublished() {
        Set<String> triggers = SCHEMAS.get("cap_result_contract").triggers();

        assertThat(triggers)
                .as("cap_result_contract 缺不可变触发器 → §6.1「已发布版本行不可变」"
                        + "对契约表从未生效（已发布契约可被就地 UPDATE）")
                .contains("trg_cap_result_contract_immutable");

        assertThat(SCHEMAS.get("cap_result_contract").triggerFunction(
                "trg_cap_result_contract_immutable"))
                .isEqualTo("forbid_published_def_mutation");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 必须挂生命周期对齐触发器（published_at ⇄ lifecycle_state）")
    void resultContractAlignsLifecycleState() {
        Set<String> triggers = SCHEMAS.get("cap_result_contract").triggers();

        assertThat(triggers)
                .as("cap_result_contract 缺对齐触发器 → published_at 与 lifecycle_state "
                        + "可能自相矛盾（「已发布却自称 DRAFT」）")
                .contains("trg_cap_result_contract_align_state");

        assertThat(SCHEMAS.get("cap_result_contract").triggerFunction(
                "trg_cap_result_contract_align_state"))
                .isEqualTo("align_cap_lifecycle_state");
    }

    // ------------------------------------------------------------------
    // 6. 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 三张表的关键查询路径必须有索引")
    void keyQueryPathsAreIndexed() {
        TableSchema invocation = SCHEMAS.get("agent_invocation");
        assertThat(invocation.indexes())
                .as("按 run 查委派记录（重建调用树）的索引")
                .contains("idx_agent_invocation_run");
        assertThat(invocation.indexes())
                .as("按父调用查子调用（重建调用树）的索引")
                .contains("idx_agent_invocation_parent");
        assertThat(invocation.indexes())
                .as("按状态查在途委派的索引")
                .contains("idx_agent_invocation_status");
        assertThat(invocation.indexes())
                .as("路由降级记录的索引（第二十三批 A3）")
                .contains("idx_agent_invocation_routed_fallback");
        assertThat(invocation.indexes())
                .as("按「模型原始候选」反查的索引（第二十三批 A3）")
                .contains("idx_agent_invocation_requested_target");

        TableSchema childRef = SCHEMAS.get("cap_agent_child_ref");
        assertThat(childRef.indexes())
                .as("按父 release 查子声明的索引")
                .contains("idx_cap_agent_child_ref_parent");
        assertThat(childRef.indexes())
                .as("按子 release 反查被依赖方的索引")
                .contains("idx_cap_agent_child_ref_child");
    }

    @Test
    @DisplayName("⚠️ 路由降级索引的谓词必须排除存量行（NULL 不等于「发生过降级」）")
    void routedFallbackIndexExcludesNullRows() {
        // ⚠️ 必须**按索引**定位，不能在整份脚本里搜字符串：
        //    V25 里有两个索引都提到 requested_target_agent_id，
        //    文件级搜索会命中「另一个索引」的谓词 ——
        //    于是「把这个索引的谓词删掉」这种退化根本不会让用例变红
        //    （反证 G 实测踩过这个坑）。
        String stmt = indexStatement("V25__agent_invocation_requested_target.sql",
                "idx_agent_invocation_routed_fallback");
        assertThat(stmt)
                .as("降级索引必须带 `requested_target_agent_id IS NOT NULL` 谓词，"
                        + "否则存量行（NULL vs target）会被 IS DISTINCT FROM 判为「不同」，"
                        + "把没降级的历史行也算进降级集合")
                .contains("requested_target_agent_id IS NOT NULL")
                .contains("requested_target_agent_id IS DISTINCT FROM target_agent_id");
    }

    // ------------------------------------------------------------------
    // 7. 迁移脚本本身的工程约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 覆盖三张表的迁移脚本必须存在（脚本被删 = 新环境建不出这些表）")
    void coveringMigrationsExist() {
        Set<String> names = new LinkedHashSet<>();
        MIGRATIONS.forEach(m -> names.add(m.name()));
        assertThat(names)
                .as("建表脚本缺失")
                .contains("V19__multi_agent_orchestration.sql");
        assertThat(names)
                .as("契约建表脚本缺失")
                .contains("V20__result_contract.sql");
    }

    @Test
    @DisplayName("⚠️ 迁移脚本必须按**数值版本**排序生效（字典序会把 V10 排到 V9 之前）")
    void migrationsAreAppliedInNumericOrder() {
        List<Integer> versions = MIGRATIONS.stream().map(Migration::version).toList();
        assertThat(versions)
                .as("迁移必须按版本数值升序应用")
                .isSorted();
        assertThat(versions)
                .as("版本号不得重复（重复版本会让重放行为不确定）")
                .doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------
    // 辅助：解析
    // ------------------------------------------------------------------

    /** 一份迁移脚本。 */
    private record Migration(int version, String name, String text) {
    }

    /** 一列。 */
    private record Column(String name, String type, boolean notNull) {
    }

    /** 一条外键。 */
    private record ForeignKey(String name, List<String> columns, String referencedTable,
                               List<String> referencedColumns, String onDelete) {
    }

    /** 一张表的<b>有效结构</b>。 */
    private static final class TableSchema {
        private final Map<String, Column> columns = new LinkedHashMap<>();
        private List<String> primaryKey = List.of();
        private final Map<String, String> checks = new LinkedHashMap<>();
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final Set<String> rlsEnabled = new LinkedHashSet<>();
        private final Set<String> rlsForced = new LinkedHashSet<>();
        private final List<String> policies = new ArrayList<>();
        private final Set<String> triggers = new LinkedHashSet<>();
        private final Map<String, String> triggerFunctions = new LinkedHashMap<>();
        private final Set<String> indexes = new LinkedHashSet<>();

        Map<String, Column> columns() {
            return columns;
        }

        Set<String> notNullColumns() {
            Set<String> out = new LinkedHashSet<>();
            columns.forEach((n, c) -> {
                if (c.notNull()) {
                    out.add(n);
                }
            });
            return out;
        }

        String columnType(String name) {
            Column c = columns.get(name);
            return c == null ? null : c.type();
        }

        List<String> primaryKey() {
            return primaryKey;
        }

        String checkExpression(String name) {
            return checks.get(name);
        }

        List<ForeignKey> foreignKeys() {
            return foreignKeys;
        }

        Set<String> rlsEnabled() {
            return rlsEnabled;
        }

        Set<String> rlsForced() {
            return rlsForced;
        }

        List<String> policies() {
            return policies;
        }

        Set<String> triggers() {
            return triggers;
        }

        String triggerFunction(String trigger) {
            return triggerFunctions.get(trigger);
        }

        Set<String> indexes() {
            return indexes;
        }
    }

    private static List<Migration> loadMigrations() {
        List<Migration> out = new ArrayList<>();
        try (var stream = Files.list(MIGRATION_DIR)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.matches("V\\d+__.*\\.sql");
            }).forEach(p -> {
                String n = p.getFileName().toString();
                int version = Integer.parseInt(n.substring(1, n.indexOf("__")));
                // ⚠️ 解析前先剥掉行注释：D 里大量用 `--` 说明设计理由，
                //    不剥掉的话注释会粘在列定义前面，让列被整段丢弃。
                out.add(new Migration(version, n, stripLineComments(readText(p))));
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本目录失败: " + MIGRATION_DIR, e);
        }
        // ⚠️ 必须按<b>数值</b>版本排序：字典序会把 V10 排到 V9 之前，
        //    于是「谁最后改了这张表」会判错 —— 契约 id 的类型就断在这里。
        out.sort(Comparator.comparingInt(Migration::version));
        return List.copyOf(out);
    }

    private static String readText(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败: " + p, e);
        }
    }

    /**
     * 剥掉 {@code --} 行注释（字符串字面量内的内容原样保留）。
     *
     * <p>⚠️ 为什么必须剥：D 里每条约束前面都有一段 {@code --} 设计说明，
     * 不剥的话「注释 + 列定义」会被当成一整个分片，列定义就被静默丢弃了
     * —— 表现为「断言说缺列」，而脚本其实是对的。
     */
    private static String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                out.append(c);
            } else if (!inString && c == '-' && i + 1 < sql.length()
                    && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String ddlOf(String name) {
        return MIGRATIONS.stream()
                .filter(m -> m.name().equals(name))
                .map(Migration::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到迁移脚本: " + name));
    }

    /**
     * 取出某个索引的建索引语句（{@code CREATE INDEX ... ;}）。
     *
     * <p>⚠️ 按索引名定位到**这一条语句**，而不是在整份脚本里搜关键词 ——
     * 同一份脚本里常有多个索引提到同一个列名，文件级搜索会张冠李戴。
     */
    private static String indexStatement(String migration, String indexName) {
        String text = ddlOf(migration);
        Matcher m = Pattern.compile("CREATE INDEX(?:\\s+IF NOT EXISTS)?\\s+" + indexName
                + "\\b.*?;", Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            return "";
        }
        return m.group();
    }

    /** 按版本顺序累计所有迁移，得到每张表的<b>有效结构</b>。 */
    private static Map<String, TableSchema> buildSchemas() {
        Map<String, TableSchema> schemas = new LinkedHashMap<>();
        for (Migration m : MIGRATIONS) {
            applyCreateTables(m, schemas);
            applyDoBlocks(m, schemas);
            applyPlainAlters(m, schemas);
            applyIndexes(m, schemas);
        }
        return Map.copyOf(schemas);
    }

    private static TableSchema schemaOf(Map<String, TableSchema> schemas, String table) {
        return schemas.computeIfAbsent(table, t -> new TableSchema());
    }

    /** 解析 CREATE TABLE 语句（含约束定义）。 */
    private static void applyCreateTables(Migration m, Map<String, TableSchema> schemas) {
        Matcher cm = Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?(\\w+)\\s*\\(")
                .matcher(m.text());
        while (cm.find()) {
            String table = cm.group(1);
            String body = balancedBody(m.text(), cm.end() - 1);
            if (body == null) {
                continue;
            }
            TableSchema s = schemaOf(schemas, table);
            for (String part : splitTopLevel(body)) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                Matcher named = Pattern.compile(
                        "^CONSTRAINT\\s+(\\w+)\\s+(.*)$", Pattern.DOTALL).matcher(p);
                if (named.matches()) {
                    applyNamedConstraint(s, named.group(1), named.group(2));
                    continue;
                }
                if (p.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")
                        || p.toUpperCase(Locale.ROOT).startsWith("UNIQUE")) {
                    continue;
                }
                Column c = parseColumn(p);
                if (c != null) {
                    s.columns.put(c.name(), c);
                }
            }
        }
    }

    private static void applyNamedConstraint(TableSchema s, String name, String body) {
        String upper = body.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY")) {
            s.primaryKey = columnList(body.substring(body.indexOf('(')));
        } else if (upper.startsWith("CHECK")) {
            s.checks.put(name, balancedBody(body, body.indexOf('(')));
        } else if (upper.startsWith("FOREIGN KEY")) {
            int refIdx = upper.indexOf("REFERENCES");
            List<String> cols = splitColumns(
                    body.substring(body.indexOf('(') + 1, body.indexOf(')')));
            String refPart = body.substring(refIdx + "REFERENCES".length()).trim();
            Matcher rm = Pattern.compile(
                    "(\\w+)\\s*\\(([^)]*)\\)(.*)", Pattern.DOTALL).matcher(refPart);
            if (rm.matches()) {
                String onDelete = "NO ACTION";
                Matcher od = Pattern.compile("ON DELETE\\s+(\\w+)",
                        Pattern.CASE_INSENSITIVE).matcher(rm.group(3));
                if (od.find()) {
                    onDelete = od.group(1).toUpperCase(Locale.ROOT);
                }
                s.foreignKeys.add(new ForeignKey(name, cols, rm.group(1),
                        splitColumns(rm.group(2)), onDelete));
            }
        }
    }

    /**
     * 解析列定义。
     *
     * <p>⚠️ 必须用 {@code lookingAt()} 而**不是** {@code matches()}：
     * 列定义后面常常还跟着 {@code DEFAULT now()} / {@code DEFAULT TRUE}，
     * 用 {@code matches()} 要求整串匹配 → 这些列会被**静默丢弃**。
     */
    private static Column parseColumn(String p) {
        Matcher m = Pattern.compile(
                "^(\\w+)\\s+([A-Za-z][A-Za-z0-9_]*(?:\\[\\])?)(\\s+NOT NULL)?",
                Pattern.CASE_INSENSITIVE).matcher(p);
        if (!m.lookingAt()) {
            return null;
        }
        return new Column(m.group(1), m.group(2),
                m.group(3) != null);
    }

    /**
     * 解析 DO 块（V19 / V20 / V25 / V26 都用 DO 块做条件化 DDL）。
     *
     * <p>⚠️ DO 块里的表名可能是<b>参数化的</b>（{@code %I}），
     * 所以必须先算出「这个块作用于哪些表」：
     * 命名数组（{@code tenant_tables TEXT[] := ARRAY[...]}）、
     * 内联数组（{@code FOREACH t IN ARRAY ARRAY[...]}）、
     * 或单表赋值（{@code t TEXT := 'x'}）。
     */
    private static void applyDoBlocks(Migration m, Map<String, TableSchema> schemas) {
        for (String block : extractDoBlocks(m.text())) {
            List<String> scope = blockScope(block);
            if (scope.isEmpty()) {
                continue;
            }
            // ⚠️ 剥掉字符串字面量的引号：DO 块里的 SQL 常被拆成多段字符串拼接
            //    （'CREATE POLICY %I ON %I USING (...) ' 'WITH CHECK (...)'），
            //    不剥掉就跨不过字面量边界，匹配不到策略与触发器。
            String flat = block.replace("'", "");

            applyRlsStatements(flat, scope, schemas);

            // 策略：USING + WITH CHECK 都要在
            if (flat.contains("CREATE POLICY")) {
                Matcher pm = Pattern.compile(
                        "CREATE POLICY.*?USING\\s*\\(([^)]*)\\).*?WITH CHECK\\s*\\(([^)]*)\\)",
                        Pattern.DOTALL).matcher(flat);
                while (pm.find()) {
                    for (String table : scope) {
                        schemas.get(table).policies.add(
                                "USING (" + pm.group(1) + ") WITH CHECK (" + pm.group(2) + ")");
                    }
                }
            }

            // ADD COLUMN：V26 的 `ALTER TABLE %I ADD COLUMN lifecycle_state ...`
            Matcher ac = Pattern.compile(
                    "ALTER TABLE\\s+%I\\s+ADD COLUMN(?:\\s+IF NOT EXISTS)?\\s+(\\w+)\\s+"
                            + "([A-Za-z][A-Za-z0-9_]*)(\\s+NOT NULL)?",
                    Pattern.CASE_INSENSITIVE).matcher(flat);
            while (ac.find()) {
                for (String table : scope) {
                    schemaOf(schemas, table).columns.put(ac.group(1),
                            new Column(ac.group(1), ac.group(2), ac.group(3) != null));
                }
            }

            // ALTER COLUMN ... TYPE：契约 id 在 V23 由 UUID 改成 TEXT
            Matcher at = Pattern.compile(
                    "ALTER TABLE\\s+%I\\s+ALTER COLUMN\\s+(\\w+)\\s+TYPE\\s+"
                            + "([A-Za-z][A-Za-z0-9_]*)",
                    Pattern.CASE_INSENSITIVE).matcher(flat);
            while (at.find()) {
                for (String table : scope) {
                    TableSchema s = schemaOf(schemas, table);
                    Column old = s.columns.get(at.group(1));
                    boolean notNull = old != null && old.notNull();
                    s.columns.put(at.group(1), new Column(at.group(1),
                            at.group(2), notNull));
                }
            }

            // CREATE TRIGGER：名字里含 %I（trg_%I_immutable）
            // ⚠️ 必须带 DOTALL：DO 块里常把一条 SQL 拆成多段字符串拼接，
            //    去掉引号后中间会留下换行，`.*?` 跨不过换行就匹配不到。
            Matcher tr = Pattern.compile(
                    "CREATE TRIGGER\\s+(trg_%I_\\w+).*?EXECUTE FUNCTION\\s+(\\w+)\\(",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
            while (tr.find()) {
                for (String table : scope) {
                    String name = tr.group(1).replace("%I", table);
                    schemaOf(schemas, table).triggers.add(name);
                    schemaOf(schemas, table).triggerFunctions.put(name, tr.group(2));
                }
            }
        }
    }

    /**
     * 按<b>出现顺序</b>处理 RLS 开关语句。
     *
     * <p>⚠️ 顺序很关键：V26 的回填段是「NO FORCE → 回填 → FORCE」，
     * 若不按顺序处理，最后一次 NO FORCE 会把状态覆盖成「未 FORCE」，
     * 于是「已发布行不可改写」的保证在断言里凭空消失。
     */
    private static void applyRlsStatements(String flat, List<String> scope,
                                           Map<String, TableSchema> schemas) {
        Matcher m = Pattern.compile(
                "NO\\s+FORCE\\s+ROW LEVEL SECURITY"
                        + "|FORCE\\s+ROW LEVEL SECURITY"
                        + "|ENABLE\\s+ROW LEVEL SECURITY",
                Pattern.CASE_INSENSITIVE).matcher(flat);
        while (m.find()) {
            String stmt = m.group().toUpperCase(Locale.ROOT);
            for (String table : scope) {
                TableSchema s = schemaOf(schemas, table);
                if (stmt.startsWith("ENABLE")) {
                    s.rlsEnabled.add(table);
                } else if (stmt.startsWith("NO")) {
                    s.rlsForced.remove(table);
                } else {
                    s.rlsForced.add(table);
                }
            }
        }
    }

    /** 处理**不在** DO 块里的直接 ALTER / CREATE INDEX 语句。 */
    private static void applyPlainAlters(Migration m, Map<String, TableSchema> schemas) {
        String text = m.text();

        // ALTER TABLE <t> ALTER COLUMN <c> TYPE <type>
        Matcher at = Pattern.compile(
                "ALTER TABLE\\s+(\\w+)\\s+ALTER COLUMN\\s+(\\w+)\\s+TYPE\\s+([A-Za-z][A-Za-z0-9_]*)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (at.find()) {
            TableSchema s = schemaOf(schemas, at.group(1));
            Column old = s.columns.get(at.group(2));
            boolean notNull = old != null && old.notNull();
            s.columns.put(at.group(2), new Column(at.group(2), at.group(3), notNull));
        }

        // ALTER TABLE <t> ADD COLUMN <c> <type>
        Matcher ac = Pattern.compile(
                "ALTER TABLE\\s+(\\w+)\\s+ADD COLUMN(?:\\s+IF NOT EXISTS)?\\s+(\\w+)\\s+"
                        + "([A-Za-z][A-Za-z0-9_]*(?:\\[\\])?)(\\s+NOT NULL)?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (ac.find()) {
            TableSchema s = schemaOf(schemas, ac.group(1));
            s.columns.put(ac.group(2), new Column(ac.group(2),
                    ac.group(3), ac.group(4) != null));
        }

        // ALTER TABLE <t> ADD CONSTRAINT ...
        Matcher ak = Pattern.compile(
                "ALTER TABLE\\s+(\\w+)\\s+ADD CONSTRAINT\\s+(\\w+)\\s+(.*)",
                Pattern.DOTALL).matcher(text);
        while (ak.find()) {
            applyNamedConstraint(schemaOf(schemas, ak.group(1)), ak.group(2), ak.group(3));
        }
    }

    private static void applyIndexes(Migration m, Map<String, TableSchema> schemas) {
        Matcher im = Pattern.compile("CREATE INDEX(?:\\s+IF NOT EXISTS)?\\s+(\\w+)").matcher(m.text());
        while (im.find()) {
            String name = im.group(1);
            // 索引属于哪张表：由 `ON <table>` 判定
            Matcher on = Pattern.compile("CREATE INDEX(?:\\s+IF NOT EXISTS)?\\s+" + name
                    + "\\s+ON\\s+(\\w+)", Pattern.DOTALL).matcher(m.text());
            if (on.find()) {
                schemaOf(schemas, on.group(1)).indexes.add(name);
            }
        }
    }

    /** 算出 DO 块作用的表集合。 */
    private static List<String> blockScope(String block) {
        // ① 内联数组：FOREACH t IN ARRAY ARRAY['a','b'] LOOP
        Matcher inline = Pattern.compile(
                "FOREACH\\s+\\w+\\s+IN ARRAY\\s+ARRAY\\[([^]]*)]",
                Pattern.DOTALL).matcher(block);
        if (inline.find()) {
            return new ArrayList<>(quotedLiterals(inline.group(1)));
        }
        // ② 命名数组：tenant_tables TEXT[] := ARRAY['a','b'] + FOREACH ... IN ARRAY tenant_tables
        Matcher named = Pattern.compile(
                "(\\w+)\\s+TEXT\\[\\]\\s*:=\\s*ARRAY\\[([^]]*)]", Pattern.DOTALL).matcher(block);
        if (named.find()) {
            String arrayName = named.group(1);
            Matcher foreach = Pattern.compile(
                    "FOREACH\\s+\\w+\\s+IN ARRAY\\s+" + arrayName + "\\b").matcher(block);
            if (foreach.find()) {
                return new ArrayList<>(quotedLiterals(named.group(2)));
            }
        }
        // ③ 单表赋值：t TEXT := 'x'
        Matcher single = Pattern.compile("\\w+\\s+TEXT\\s*:=\\s*'(\\w+)'").matcher(block);
        if (single.find()) {
            return List.of(single.group(1));
        }
        return List.of();
    }

    /** 提取所有 DO $$ ... $$; 块。 */
    private static List<String> extractDoBlocks(String text) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = text.indexOf("DO $$", idx);
            if (start < 0) {
                break;
            }
            int end = text.indexOf("$$;", start);
            if (end < 0) {
                break;
            }
            out.add(text.substring(start, end));
            idx = end + 3;
        }
        return out;
    }

    /** 取从 open 处的 '(' 开始的<b>配平</b>括号内容。 */
    private static String balancedBody(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '(') {
            return null;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /** 按<b>顶层逗号</b>切分（括号内的逗号不切）。 */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        out.add(body.substring(start));
        return out;
    }

    /** 取括号里的列名列表。 */
    private static List<String> columnList(String text) {
        Matcher m = Pattern.compile("\\(([^)]*)\\)", Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            return List.of();
        }
        return splitColumns(m.group(1));
    }

    /**
     * 把逗号分隔的列名列表切成数组。
     *
     * <p>⚠️ 入参是<b>括号内</b>的内容（不含括号）：
     * 之前写成「先截到第一个 {@code )}」会把右括号也切掉，
     * 正则再也匹配不上，外键的列列表就变成了空列表。
     */
    private static List<String> splitColumns(String inner) {
        List<String> out = new ArrayList<>();
        for (String part : inner.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 取所有单引号字面量。 */
    private static Set<String> quotedLiterals(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([^']*)'").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}

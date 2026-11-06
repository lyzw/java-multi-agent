package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
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

import cn.studykid.multiagent.infrastructure.PlatformDdl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多级 Agent 三张表的<b>结构门禁</b>（多级 Agent v1.1 · 第二十七批 · A8）。
 *
 * <p><b>⚠️ 本类要补的是一个真实的回归防护缺口</b>：
 * 多级 Agent 的三张表（{@code agent_invocation} / {@code cap_agent_child_ref} /
 * {@code cap_result_contract}）的结构<b>只由结构定义文件决定</b>，而它<b>只在部署新环境时执行</b>。
 * 这带来一个容易被忽略的时序问题：
 *
 * <blockquote>
 * 定义被改坏 → <b>已部署的环境察觉不到</b>（库里已经是旧结构）→
 * 现有测试照样全绿（行为断言跑在<b>早就建好的库</b>上）→
 * 直到某天<b>新建环境</b>，改坏的定义才被执行 → 那时线上环境已经建起来了。
 * </blockquote>
 *
 * <p><b>为什么不能只加「行为断言」</b>：行为断言（写入后能否读回、非法值是否被拒）
 * 验证的是<b>当前库</b>，不是<b>定义</b>。定义退化后，
 * 只有<b>新环境</b>才会中招 —— 而新环境恰恰是最不该出事的那个。
 * 故本类做的是<b>文本级</b>断言：直接读结构定义，推出「部署后应当存在的结构」。
 *
 * <p><b>⚠️ 取数源</b>：2026-10-27 收口后 {@code db/migration/V1…V34} 已删除，
 * 结构唯一权威源是 {@code deploy/sql/platform/01_platform_ddl.sql}（终态快照）。
 * 过去「跨多版本累计出有效结构」（例如契约 id 在 V20 是 UUID、V23 才改 TEXT）
 * 现在就是<b>快照原文</b> —— 那条缺陷的守卫从「累计后是 TEXT」变成「快照里就是 TEXT」，
 * 要防的仍然是「它被改回 UUID」。
 *
 * <p><b>⚠️ 解析的是 {@code pg_dump} 输出</b>，不是手写脚本（形状差异见 {@link PlatformDdl}）：
 * 列与 CHECK 在 {@code CREATE TABLE} 体内，主键/外键是独立的
 * {@code ALTER TABLE ONLY … ADD CONSTRAINT}，名字与类型都带 schema 前缀。
 * 断言落在<b>结构语义</b>上（例如「状态集合恰好是七态」而不是「文本里出现过 SUCCEEDED」）。
 *
 * <p><b>⚠️ 反证纪律</b>：本类的每条断言都必须能被单独证伪 ——
 * 改坏快照里对应的一处结构，对应用例必须变红，且<b>不该受影响的用例保持绿</b>。
 */
class MultiAgentSchemaStructureTest {

    /** 三张被门禁覆盖的表（不限定 schema：三张都在 {@code public}）。 */
    private static final List<String> GUARDED_TABLES =
            List.of("agent_invocation", "cap_agent_child_ref", "cap_result_contract");

    /** 委派记录的状态集合（07 §状态与失败语义）：<b>封闭</b>，不多不少。 */
    private static final Set<String> INVOCATION_STATUSES = Set.of(
            "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN");

    /** 三张表的<b>有效结构</b>（直接由权威快照解析）。 */
    private static final Map<String, TableSchema> SCHEMAS = buildSchemas();

    // ------------------------------------------------------------------
    // 1. 表与列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三张表都由结构权威源建出（缺表 = 整条多级 Agent 链路无处落地）")
    void guardedTablesAreCreated() {
        for (String table : GUARDED_TABLES) {
            assertThat(SCHEMAS)
                    .as("结构权威源里没有建出表 %s", table)
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

        // ⚠️ 「模型原始候选」列必须还在（原 V25）。
        assertThat(s.columnType("requested_target_agent_id"))
                .as("「模型原始候选」列丢失 → 路由降级再次无法审计")
                .isEqualToIgnoringCase("UUID");

        // ── B4 · S3 决策快照（10 号设计 §6.1）──────────────────────
        // ⚠️ 三列全部<b>可空</b>：迁移前的存量行没有它们，而「当时未记录」
        //    不能写成 NOT NULL —— 那会让整个多级 Agent 链路的写入在回填前全挂。
        assertThat(s.columns().keySet())
                .as("agent_invocation 缺少决策快照列")
                .contains("routing_decision", "policy_ref", "policy_fingerprint");
        assertThat(s.notNullColumns())
                .as("决策快照三列必须可空（存量行为「当时未记录」）")
                .doesNotContain("routing_decision", "policy_ref", "policy_fingerprint");

        // ⚠️ 快照必须是 <b>jsonb</b> 而非 TEXT：
        //    降级排查问的是「哪条规则命中了」（`@>` / `->`），文本列只能全表扫 + 应用侧解析，
        //    而那份解析正是「看起来有数据、实际查不出来」的温床。
        assertThat(s.columnType("routing_decision"))
                .as("routing_decision 必须是 jsonb，否则决策轨迹在库里不可查询")
                .isEqualToIgnoringCase("JSONB");

        // ⚠️ `policy_ref` 单独成列（不进快照 JSON）：重放的主键是
        //    (policy_ref, canonicalInputSha256)，埋在 JSON 里就只能靠约定俗成的表达式取，
        //    键名一改历史查询静默返回 0 行。
        assertThat(s.columnType("policy_ref"))
                .as("policy_ref 必须是文本列（重放主键的一半，可索引、可直接查）")
                .isEqualToIgnoringCase("TEXT");
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

        // ⚠️ 父子两侧都必须是**固定版本**（原 V19 的不可妥协约束 5）。
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

        // ⚠️ **这是一条真实修过的缺陷**（原 V23）：对外契约把契约 id 定义为可读字符串
        //    （common.schema.json#/$defs/id），治理通道也按字符串接收；
        //    而 DB 一度声明成 UUID → 非 UUID id 的发布事务**整笔回滚**，
        //    运行期每次查询都抛异常 → 委派恒 fail-closed。
        assertThat(s.columnType("contract_id"))
                .as("契约 id 必须保持可读字符串（TEXT）；改回 UUID 会让非 UUID 形式的"
                        + "契约在发布链路整笔回滚、运行期恒 fail-closed")
                .isEqualToIgnoringCase("TEXT");

        // ⚠️ 契约定义本身（JSON Schema）是发布期固化的事实 → 必填。
        assertThat(s.notNullColumns())
                .as("schema_json / config_fingerprint 必须 NOT NULL")
                .contains("schema_json", "config_fingerprint");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 必须带 lifecycle_state（停用维度的落点）")
    void resultContractHasLifecycleState() {
        TableSchema s = SCHEMAS.get("cap_result_contract");

        assertThat(s.columns())
                .as("cap_result_contract 缺 lifecycle_state → 这张表只能表达"
                        + "「已发布/未发布」两态，「已停用」在数据层无法表达")
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
                    .as("%s 的主键必须以 tenant_id 打头（否则跨租户寻址失去隔离维度）", table)
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
        assertThat(PlatformDdl.plain(expr)).contains("fencing_token > 0");
    }

    @Test
    @DisplayName("⚠️ agent_invocation 的根调用判定必须同时覆盖「depth=1 无父」与「depth>1 有父」")
    void invocationParentRelationIsBidirectional() {
        String expr = SCHEMAS.get("agent_invocation")
                .checkExpression("agent_invocation_parent_ck");
        assertThat(expr)
                .as("父子关系判定约束缺失 → 悬空根可写入，调用树无法重建")
                .isNotNull();

        String flat = PlatformDdl.plain(expr);
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
        assertThat(PlatformDdl.plain(expr)).contains("depth > 0");
    }

    @Test
    @DisplayName("⚠️ cap_agent_child_ref 的 ordinal 与版本号必须非负/为正")
    void childRefOrdinalAndVersionAreBounded() {
        TableSchema s = SCHEMAS.get("cap_agent_child_ref");

        String ordinal = s.checkExpression("cap_agent_child_ref_ordinal_ck");
        assertThat(ordinal).as("ordinal >= 0 约束缺失").isNotNull();
        assertThat(PlatformDdl.plain(ordinal)).contains("ordinal >= 0");

        String version = s.checkExpression("cap_agent_child_ref_version_ck");
        assertThat(version).as("版本号 > 0 约束缺失").isNotNull();
        assertThat(PlatformDdl.plain(version))
                .contains("parent_release_version > 0")
                .contains("child_release_version > 0");
    }

    @Test
    @DisplayName("⚠️ cap_result_contract 的版本号与内容指纹必须非空")
    void resultContractVersionAndFingerprintAreBounded() {
        TableSchema s = SCHEMAS.get("cap_result_contract");

        String version = s.checkExpression("cap_result_contract_version_ck");
        assertThat(version).as("version > 0 约束缺失").isNotNull();
        assertThat(PlatformDdl.plain(version)).contains("version > 0");

        String fp = s.checkExpression("cap_result_contract_fingerprint_ck");
        assertThat(fp).as("内容指纹非空约束缺失 → 空指纹行无法参与「契约变了没有」的判定")
                .isNotNull();
        assertThat(PlatformDdl.plain(fp)).contains("config_fingerprint");
    }

    // ------------------------------------------------------------------
    // 4. RLS 租户隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 三张表都必须启用 RLS **且 FORCE**（未 FORCE 时表 owner 仍能跨租户读）")
    void allThreeTablesAreRlsForced() {
        for (String table : GUARDED_TABLES) {
            assertThat(PlatformDdl.rlsTables("ENABLE"))
                    .as("%s 未启用 RLS → 跨租户泄漏（R-004 · Sev-0）", table)
                    .contains("public." + table);
            assertThat(PlatformDdl.rlsTables("FORCE"))
                    .as("%s 未 FORCE RLS → 表 owner（部署角色正是 owner）不受策略约束，"
                            + "隔离形同虚设", table)
                    .contains("public." + table);
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
                    p.contains("tenant_id = current_tenant_id()") && p.contains("WITH CHECK"));
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
        // ⚠️ 必须**按索引**定位，不能在整份定义里搜字符串：
        //    快照里有两个索引都提到 requested_target_agent_id，
        //    文件级搜索会命中「另一个索引」的谓词 ——
        //    于是「把这个索引的谓词删掉」这种退化根本不会让用例变红（反证 G 实测踩过）。
        String stmt = PlatformDdl.plain(indexStatement("idx_agent_invocation_routed_fallback"));
        assertThat(stmt)
                .as("降级索引必须带 `requested_target_agent_id IS NOT NULL` 谓词，"
                        + "否则存量行（NULL vs target）会被 IS DISTINCT FROM 判为「不同」，"
                        + "把没降级的历史行也算进降级集合")
                .contains("requested_target_agent_id IS NOT NULL")
                .contains("requested_target_agent_id IS DISTINCT FROM target_agent_id");
    }

    // ------------------------------------------------------------------
    // 7. 结构权威源本身的工程约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 结构权威源必须是完整快照，不能是空壳或被截断的导出")
    void snapshotIsComplete() {
        String ddl = PlatformDdl.text();
        assertThat(PlatformDdl.file().getFileName().toString())
                .as("门禁取数源须是终态快照")
                .isEqualTo("01_platform_ddl.sql");
        assertThat(ddl)
                .as("缺少 pg_dump 头 —— 取数源被换成了别的文件，本类的解析规则即失效")
                .contains("-- PostgreSQL database dump");
        // 三张表只是探针：真正要防的是「重新导出时整段丢对象」，
        // 那种事故不会让某一列消失，而是让表的总数塌下去。
        assertThat(PlatformDdl.createdTables())
                .as("快照表数远低于既有基线（65），疑似导出被截断")
                .hasSizeGreaterThanOrEqualTo(60);
        assertThat(PlatformDdl.rlsTables("ENABLE"))
                .as("启用 RLS 的表数远低于既有基线（42），疑似 RLS 段被整段丢掉")
                .hasSizeGreaterThanOrEqualTo(40);
    }

    // ------------------------------------------------------------------
    // 辅助：解析
    // ------------------------------------------------------------------

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

    /** 按快照原文解析出每张表的结构。 */
    private static Map<String, TableSchema> buildSchemas() {
        Map<String, TableSchema> schemas = new LinkedHashMap<>();
        applyCreateTables(schemas);
        applyAlterConstraints(schemas);
        applyPolicies(schemas);
        applyTriggers(schemas);
        applyIndexes(schemas);
        return Map.copyOf(schemas);
    }

    /**
     * 按<b>末段名</b>归档表结构。
     *
     * <p>⚠️ 调用方必须先用 {@link #isPlatformSchema} 过滤掉 {@code platform} 域的表：
     * 快照里 {@code public.tenant} 与 {@code platform.tenant} <b>同名</b>，
     * 按末段归档会让平台域的列把租户域的那张表覆盖掉。
     */
    private static TableSchema schemaOf(Map<String, TableSchema> schemas, String table) {
        return schemas.computeIfAbsent(PlatformDdl.simpleName(table), t -> new TableSchema());
    }

    /** 该限定名是否属于平台身份域（本类只盯租户域）。 */
    private static boolean isPlatformSchema(String qualifiedName) {
        return qualifiedName.startsWith("platform.");
    }

    /** 解析 {@code CREATE TABLE public.x (列 …, CONSTRAINT … CHECK (…))}。 */
    private static void applyCreateTables(Map<String, TableSchema> schemas) {
        String ddl = PlatformDdl.text();
        Matcher cm = Pattern.compile("CREATE TABLE\\s+((?:\\w+\\.)?\\w+)\\s*\\(").matcher(ddl);
        while (cm.find()) {
            String table = cm.group(1);
            if (isPlatformSchema(table)) {
                continue;
            }
            String body = PlatformDdl.balancedBody(ddl, cm.end() - 1);
            if (body == null) {
                continue;
            }
            TableSchema s = schemaOf(schemas, table);
            for (String part : PlatformDdl.splitTopLevel(body)) {
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
                Column c = parseColumn(p);
                if (c != null) {
                    s.columns.put(c.name(), c);
                }
            }
        }
    }

    /** 解析独立的 {@code ALTER TABLE ONLY x ADD CONSTRAINT … PRIMARY KEY|FOREIGN KEY|UNIQUE}。 */
    private static void applyAlterConstraints(Map<String, TableSchema> schemas) {
        Matcher ak = Pattern.compile(
                "ALTER TABLE\\s+(?:ONLY\\s+)?((?:\\w+\\.)?\\w+)\\s+ADD CONSTRAINT\\s+(\\w+)\\s+"
                        + "([^;]*);", Pattern.DOTALL).matcher(PlatformDdl.text());
        while (ak.find()) {
            if (isPlatformSchema(ak.group(1))) {
                continue;
            }
            applyNamedConstraint(schemaOf(schemas, ak.group(1)), ak.group(2), ak.group(3));
        }
    }

    private static void applyNamedConstraint(TableSchema s, String name, String body) {
        String upper = body.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY")) {
            s.primaryKey = columnList(body.substring(body.indexOf('(')));
        } else if (upper.startsWith("CHECK")) {
            s.checks.put(name, PlatformDdl.balancedBody(body, body.indexOf('(')));
        } else if (upper.startsWith("FOREIGN KEY")) {
            int refIdx = upper.indexOf("REFERENCES");
            List<String> cols = splitColumns(body.substring(body.indexOf('(') + 1,
                    body.indexOf(')')));
            String refPart = body.substring(refIdx + "REFERENCES".length()).trim();
            // ⚠️ 目标表带 schema 前缀（pg_dump 全是限定名），不限定会 matches() 失败。
            Matcher rm = Pattern.compile("((?:\\w+\\.)?\\w+)\\s*\\(([^)]*)\\)(.*)",
                    Pattern.DOTALL).matcher(refPart);
            if (rm.matches()) {
                String onDelete = "NO ACTION";
                Matcher od = Pattern.compile("ON DELETE\\s+(\\w+)",
                        Pattern.CASE_INSENSITIVE).matcher(rm.group(3));
                if (od.find()) {
                    onDelete = od.group(1).toUpperCase(Locale.ROOT);
                }
                s.foreignKeys.add(new ForeignKey(name, cols,
                        PlatformDdl.simpleName(rm.group(1)), splitColumns(rm.group(2)), onDelete));
            }
        }
    }

    /**
     * 解析列定义。
     *
     * <p>⚠️ 三处为 pg_dump 特化：
     * <ul>
     *   <li>用 {@code lookingAt()} 而非 {@code matches()} —— 列后面还跟着
     *       {@code DEFAULT …}，要求整串匹配会让这些列<b>静默丢失</b>；</li>
     *   <li>类型可能是限定名（{@code public.run_state}），取末段才是枚举名；</li>
     *   <li>{@code NOT NULL} 常排在 {@code DEFAULT} 之后，所以整段扫描而不是
     *       「紧跟类型之后」。</li>
     * </ul>
     */
    private static Column parseColumn(String p) {
        Matcher m = Pattern.compile("^(\\w+)\\s+((?:\\w+\\.?)+[A-Za-z]\\w*)(\\[\\])?",
                Pattern.CASE_INSENSITIVE).matcher(p);
        if (!m.lookingAt()) {
            return null;
        }
        String type = m.group(2);
        int dot = type.lastIndexOf('.');
        return new Column(m.group(1), dot < 0 ? type : type.substring(dot + 1),
                Pattern.compile("(?i)\\bNOT\\s+NULL\\b").matcher(p).find());
    }

    /** 解析 {@code CREATE POLICY name ON public.x … ;}，按表归档。 */
    private static void applyPolicies(Map<String, TableSchema> schemas) {
        Matcher pm = Pattern.compile(
                "CREATE POLICY\\s+\\w+\\s+ON\\s+((?:\\w+\\.)?\\w+)[^;]*;",
                Pattern.DOTALL).matcher(PlatformDdl.text());
        while (pm.find()) {
            if (isPlatformSchema(pm.group(1))) {
                continue;
            }
            // 抹平限定名：策略体里的 current_tenant_id() 同样带 public. 前缀。
            schemaOf(schemas, pm.group(1)).policies.add(PlatformDdl.plain(pm.group()));
        }
    }

    /** 解析 {@code CREATE TRIGGER name … ON public.x … EXECUTE FUNCTION public.fn();}。 */
    private static void applyTriggers(Map<String, TableSchema> schemas) {
        Matcher tr = Pattern.compile(
                "CREATE TRIGGER\\s+(\\w+)\\s+\\w+.*?ON\\s+((?:\\w+\\.)?\\w+)\\s+.*?"
                        + "EXECUTE FUNCTION\\s+((?:\\w+\\.)?\\w+)\\s*\\(",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(PlatformDdl.text());
        while (tr.find()) {
            if (isPlatformSchema(tr.group(2))) {
                continue;
            }
            TableSchema s = schemaOf(schemas, tr.group(2));
            s.triggers.add(tr.group(1));
            s.triggerFunctions.put(tr.group(1), PlatformDdl.simpleName(tr.group(3)));
        }
    }

    private static void applyIndexes(Map<String, TableSchema> schemas) {
        Matcher im = Pattern.compile(
                "CREATE (?:UNIQUE )?INDEX\\s+(\\w+)\\s+ON\\s+((?:\\w+\\.)?\\w+)")
                .matcher(PlatformDdl.text());
        while (im.find()) {
            if (isPlatformSchema(im.group(2))) {
                continue;
            }
            schemaOf(schemas, im.group(2)).indexes.add(im.group(1));
        }
    }

    /**
     * 取出某个索引的建索引语句（{@code CREATE INDEX … ;}）。
     *
     * <p>⚠️ 按索引名定位到**这一条语句**，而不是在整份定义里搜关键词 ——
     * 同一份定义里常有多个索引提到同一个列名，文件级搜索会张冠李戴。
     */
    private static String indexStatement(String indexName) {
        Matcher m = Pattern.compile("CREATE INDEX\\s+" + indexName + "\\b[^;]*;",
                Pattern.DOTALL).matcher(PlatformDdl.text());
        return m.find() ? m.group() : "";
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

package cn.studykid.rag.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理面（RAG-2：语料/词典/巡检/别名/来源管理）「不得依赖 GUC」的源码级守卫（RLS 清理 · M3）。
 *
 * <p><b>背景</b>：检索面已由 {@link RagRetrievalScopeGuardTest} 钉死。
 * 本类负责<b>管理面</b>——写/读路径历史上用 {@code rag_current_tenant()} / {@code rag_global_tenant_id()}
 * 读会话变量，本次改造改为「显式 UUID 字面量」绑定（受信任的已校验 tenantId 直接拼成 {@code '...'::uuid}）。
 *
 * <p><b>⚠️ 为什么用字面量而非 {@code RagSqlScopeTranslator.render} + 绑定参数</b>：
 * 管理面 SQL 结构异构（{@code IN(租户,哨兵)} / {@code =} / 子查询 / {@code COALESCE} 混合），
 * 字面量模式零参数错位风险，且 tenantId 是受信任的已校验 UUID（非用户输入），无注入面。
 * 此选择已在改造计划 WP-M3-2（RAG-2）登记。
 *
 * <p><b>⚠️ 关键防护</b>：本守卫不仅查「GUC 函数没了」，<b>更查「显式谓词回来了」</b>。
 * 若有人把 {@code rag_current_tenant()} 删了却忘了补 {@code ::uuid} 字面量，
 * 管理面会在 M4 退役 RLS 后退化成跨租户污染——这正是风险登记册里最危险的一类静默失真。
 *
 * <p>复用 {@link RagRetrievalScopeGuardTest#stripComments(String)} 把注释剥离后再判定。
 */
class RagManagementScopeGuardTest {

    /** 管理面已改造为显式字面量谓词的文件（相对 rag-persistence 模块根）。 */
    private static final List<String> MANAGEMENT_SCOPE_FILES = List.of(
            "src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java",
            "src/main/java/cn/studykid/rag/management/infrastructure/JdbcDictionaryRepository.java",
            "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java");

    /**
     * 管理面<b>允许</b>保留的 GUC 绑定形态（RLS 在场期的兼容层）。
     *
     * <p>⚠️ 与检索面同一逻辑：{@code set_config('app.tenant_id', ?, true)} 是<b>刻意保留</b>的，
     * 因 FORCE RLS 策略仍读 GUC。一旦移除会让全部租户行被静默过滤。
     * 它随 <b>M4-R2</b> 批次（管理面表策略退役）移除。
     */
    private static final List<String> ALLOWED_GUC_BINDINGS = List.of(
            "set_config('app.tenant_id', ?, true)",
            "set_config('app.tenant_id', '', true)");

    @Test
    @DisplayName("⛔ 管理面 SQL 不得再出现 rag_current_tenant()（读会话变量，未绑定即静默失效）")
    void managementFaceMustNotCallGucReadingFunction() {
        for (String rel : MANAGEMENT_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            assertThat(code)
                    .as("管理面文件 %s 的可执行代码里不得出现 rag_current_tenant()："
                            + "该函数读会话变量 app.tenant_id，未绑定时返回 NULL，"
                            + "谓词 IN (NULL, 哨兵) 恒不匹配 ⇒ 租户记录静默消失。"
                            + "租户过滤必须绑定显式 UUID 字面量。", rel)
                    .doesNotContain("rag_current_tenant()");
        }
    }

    @Test
    @DisplayName("⛔ 管理面 SQL 不得再出现 rag_global_tenant_id()（M5 删函数时必须零改动）")
    void managementFaceMustNotCallSentinelFunction() {
        for (String rel : MANAGEMENT_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            assertThat(code)
                    .as("管理面文件 %s 的可执行代码里不得出现 rag_global_tenant_id()："
                            + "该函数虽不依赖 GUC，但随 M5 删除；应用侧应绑定 "
                            + "RagScope.GLOBAL_TENANT_ID 常量。", rel)
                    .doesNotContain("rag_global_tenant_id()");
        }
    }

    @Test
    @DisplayName("✅ 管理面仅保留 set_config 兼容层，且形态在白名单内（随 M4-R2 退役）")
    void onlyWhitelistedGucBindingsRemain() {
        for (String rel : MANAGEMENT_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            int idx = 0;
            while ((idx = code.indexOf("set_config(", idx)) >= 0) {
                int end = code.indexOf(")", idx);
                assertThat(end).as("set_config 调用未闭合：%s", rel).isGreaterThan(idx);
                String call = code.substring(idx, end + 1);
                assertThat(ALLOWED_GUC_BINDINGS)
                        .as("管理面 %s 出现了白名单外的 set_config 形态：%s。"
                                + "新增兼容层绑定必须先在改造计划里登记理由。", rel, call)
                        .contains(call);
                idx = end + 1;
            }
        }
    }

    @Test
    @DisplayName("✅ 管理面必须把 GUC 函数替换为显式 ::uuid 字面量（防「删了没补」静默失真）")
    void managementFaceUsesExplicitLiteralBinding() {
        for (String rel : MANAGEMENT_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            assertThat(code)
                    .as("管理面文件 %s 必须含显式 UUID 字面量绑定（'...'::uuid 或占位符 {rag_tenant}/{rag_sentinel} 的运行时替换）。"
                            + "若只有 set_config 兼容层而没有 ::uuid 显式谓词，"
                            + "M4 退役 RLS 后该面会退化成跨租户污染（Sev-0）。", rel)
                    .contains("::uuid");
        }
    }

    private static String readSource(String rel) {
        Path path = Path.of(rel);
        try {
            assertThat(Files.exists(path))
                    .as("找不到被扫文件：%s（工作目录应为 rag-persistence 模块）", rel)
                    .isTrue();
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取失败：" + rel, e);
        }
    }
}

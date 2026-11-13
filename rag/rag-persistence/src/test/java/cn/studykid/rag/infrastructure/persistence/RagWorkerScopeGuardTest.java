package cn.studykid.rag.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摄取/索引（worker）面（RAG-3）「不得依赖 GUC 读函数」的源码级守卫（RLS 清理 · M3）。
 *
 * <p><b>背景</b>：检索面由 {@link RagRetrievalScopeGuardTest}、管理面由
 * {@link RagManagementScopeGuardTest} 钉死。本类负责<b>摄取/索引面</b>——
 * 后台任务的租户过滤原本靠 {@code rag_current_tenant()} 读 GUC，本次改造改为
 * 「显式 UUID 字面量」绑定（受信任的已校验 tenantId 直接拼成 {@code '...'::uuid}）。
 *
 * <p><b>⚠️ 与检索/管理面的关键差异（M3 修正口径）</b>：
 * 本面保留 <b>{@code rag_global_tenant_id()}</b> 的显式调用——它是 <b>IMMUTABLE 常量函数</b>，
 * <b>不读 GUC</b>（guc-dependency.md G3），且被 8 处 CHECK 约束引用。
 * M3 修正明确将其<b>保留</b>（随 M5 删函数），故本守卫<b>不要求</b> {@code rag_global_tenant_id()} 归零，
 * 只要求危险的 <b>{@code rag_current_tenant()}</b>（读 GUC，未绑定即静默失效）必须归零。
 *
 * <p>复用 {@link RagRetrievalScopeGuardTest#stripComments(String)} 把注释剥离后再判定。
 */
class RagWorkerScopeGuardTest {

    /** 摄取/索引面已改造或已显式化的文件（相对 rag-persistence 模块根）。 */
    private static final List<String> WORKER_SCOPE_FILES = List.of(
            "src/main/java/cn/studykid/rag/infrastructure/indexing/IndexGenerationBuilder.java",
            "src/main/java/cn/studykid/rag/infrastructure/indexing/ChunkIndexer.java",
            "src/main/java/cn/studykid/rag/management/infrastructure/JdbcKnowledgeAliasRepository.java",
            "src/main/java/cn/studykid/rag/infrastructure/ingest/JdbcIngestJobRepository.java",
            "src/main/java/cn/studykid/rag/infrastructure/ingest/JdbcGlobalIngestSourceLoader.java",
            "src/main/java/cn/studykid/rag/management/infrastructure/IngestDedupPrecheck.java",
            "src/main/java/cn/studykid/rag/infrastructure/ingest/JdbcIngestSourceLoader.java");

    /**
     * 摄取/索引面<b>允许</b>保留的 GUC 绑定形态（RLS 在场期的兼容层）。
     * 同检索/管理面白名单。
     */
    private static final List<String> ALLOWED_GUC_BINDINGS = List.of(
            "set_config('app.tenant_id', ?, true)",
            "set_config('app.tenant_id', '', true)");

    @Test
    @DisplayName("⛔ 摄取/索引面 SQL 不得再出现 rag_current_tenant()（读 GUC，未绑定即静默失效）")
    void workerFaceMustNotCallGucReadingFunction() {
        for (String rel : WORKER_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            assertThat(code)
                    .as("摄取/索引面文件 %s 的可执行代码里不得出现 rag_current_tenant()："
                            + "该函数读会话变量 app.tenant_id，未绑定时返回 NULL，"
                            + "谓词 IN (NULL, 哨兵) 恒不匹配 ⇒ 租户记录静默消失。"
                            + "租户过滤必须绑定显式 UUID 字面量。", rel)
                    .doesNotContain("rag_current_tenant()");
        }
    }

    @Test
    @DisplayName("✅ 摄取/索引面仅保留 set_config 兼容层，且形态在白名单内（随 M4-R3 退役）")
    void onlyWhitelistedGucBindingsRemain() {
        for (String rel : WORKER_SCOPE_FILES) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            int idx = 0;
            while ((idx = code.indexOf("set_config(", idx)) >= 0) {
                int end = code.indexOf(")", idx);
                assertThat(end).as("set_config 调用未闭合：%s", rel).isGreaterThan(idx);
                String call = code.substring(idx, end + 1);
                assertThat(ALLOWED_GUC_BINDINGS)
                        .as("摄取/索引面 %s 出现了白名单外的 set_config 形态：%s。"
                                + "新增兼容层绑定必须先在改造计划里登记理由。", rel, call)
                        .contains(call);
                idx = end + 1;
            }
        }
    }

    @Test
    @DisplayName("✅ 摄取/索引面必须把 tenant 谓词替换为显式 ::uuid 字面量（防「删了没补」静默失真）")
    void workerFaceUsesExplicitLiteralBinding() {
        // 仅校验本次改造的三个文件：它们原先用 rag_current_tenant() 做租户过滤，
        // 现已收口为显式字面量占位符 {rag_tenant} → '...'::uuid。
        for (String rel : List.of(
                "src/main/java/cn/studykid/rag/infrastructure/indexing/IndexGenerationBuilder.java",
                "src/main/java/cn/studykid/rag/infrastructure/indexing/ChunkIndexer.java",
                "src/main/java/cn/studykid/rag/management/infrastructure/JdbcKnowledgeAliasRepository.java")) {
            String code = RagRetrievalScopeGuardTest.stripComments(readSource(rel));
            assertThat(code)
                    .as("摄取/索引面文件 %s 必须含显式 UUID 字面量绑定（'...'::uuid 或占位符 {rag_tenant} 的运行时替换）。"
                            + "若只有 set_config 兼容层而没有 ::uuid 显式谓词，"
                            + "M4 退役 RLS 后该面会退化成跨租户污染（Sev-0）。", rel)
                    .contains("::uuid");
        }
    }

    @Test
    @DisplayName("✅ 全局桶谓词仍显式收口在 rag_global_tenant_id()（IMMUTABLE 常量，随 M5 删函数；BYPASSRLS 下承重）")
    void globalBucketPredicateRemainsExplicitSentinel() {
        // 校验全局桶写入/读取仍钉死哨兵常量（防止有人误删全局桶谓词→运维角色跨桶污染）。
        String repo = RagRetrievalScopeGuardTest.stripComments(
                readSource("src/main/java/cn/studykid/rag/infrastructure/ingest/JdbcIngestJobRepository.java"));
        assertThat(repo)
                .as("JdbcIngestJobRepository 的全局桶操作必须显式收口在 rag_global_tenant_id()"
                        + "（BYPASSRLS 下无策略兜底，桶谓词是唯一隔离边界）")
                .contains("rag_global_tenant_id()");
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

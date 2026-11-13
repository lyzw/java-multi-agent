package cn.studykid.rag.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索面「不得依赖 GUC」的源码级守卫（RLS 清理 · M3）。
 *
 * <p><b>⚠️ 为什么必须用源码扫描而不是只靠行为测试</b>：
 * 行为测试证明的是「当前这条查询在当前数据下等价」，
 * 而它<b>证明不了</b>「将来没有人再写回 {@code rag_current_tenant()}」。
 * 后者是 M3 的<b>真正目标</b>——把「依赖 GUC 才能工作」的谓词形态从检索面清零。
 *
 * <p><b>⚠️ 为什么必须剥注释再判定</b>：本文件与被扫文件里大量出现
 * {@code rag_current_tenant()} 的<b>说明文字</b>（解释历史与迁移路径）。
 * 若不剥注释，守卫会因为「文档提到了它」而红——那是假红，会让人养成
 * 「看到红色就加豁免」的习惯，守卫随即失去意义。
 * 故本守卫只对<b>可执行代码</b>判定（字符串字面量与标识符保留，注释剥离）。
 */
class RagRetrievalScopeGuardTest {

    /** 检索面已改造为显式谓词的文件。 */
    private static final List<String> EXPLICIT_SCOPE_FILES = List.of(
            "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcRetrievalRepository.java",
            "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcTenantScopedRetrieval.java",
            "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcTenantScopedGeneration.java");

    /**
     * 检索面<b>允许</b>保留的 GUC 绑定形态（RLS 在场期的兼容层）。
     *
     * <p>⚠️ 这些 {@code set_config} 是<b>刻意保留</b>的：FORCE RLS 策略仍读 GUC，
     * 此刻移除会让<b>全部</b>租户行被静默过滤（检索恒空且不报错）——
     * 这正是风险登记册 R1（Sev-0）的场景。
     * 它们随 <b>M4-R1</b> 批次（该表策略退役）移除。
     *
     * <p>⚠️ 只比对 {@code set_config(...)} 调用本身，不比对整句 SQL：
     * 调用形态（参数个数、是否置空）才是「是否仍是兼容层」的决定性特征，
     * 而外层是 {@code SELECT} 还是别的什么与语义无关。
     */
    private static final List<String> ALLOWED_GUC_BINDINGS = List.of(
            "set_config('app.tenant_id', ?, true)",
            "set_config('app.tenant_id', '', true)");

    @Test
    @DisplayName("⛔ 检索面 SQL 不得再出现 rag_current_tenant()（它读会话变量，未绑定即静默失效）")
    void retrievalFaceMustNotCallGucReadingFunction() {
        for (String rel : EXPLICIT_SCOPE_FILES) {
            String code = stripComments(readSource(rel));

            assertThat(code)
                    .as("检索面文件 %s 的可执行代码里不得出现 rag_current_tenant()："
                            + "该函数读会话变量 app.tenant_id，未绑定时返回 NULL，"
                            + "谓词 IN (NULL, 哨兵) 恒不匹配 ⇒ 租户语料静默消失。"
                            + "租户过滤必须由 RagScopeFilter 渲染成绑定参数。", rel)
                    .doesNotContain("rag_current_tenant()");
        }
    }

    @Test
    @DisplayName("⛔ 检索面 SQL 不得再出现 rag_global_tenant_id()（M5 删函数时必须零改动）")
    void retrievalFaceMustNotCallSentinelFunction() {
        for (String rel : EXPLICIT_SCOPE_FILES) {
            String code = stripComments(readSource(rel));

            assertThat(code)
                    .as("检索面文件 %s 的可执行代码里不得出现 rag_global_tenant_id()："
                            + "该函数虽不依赖 GUC，但随 M5 删除；应用侧应绑定 "
                            + "RagScope.GLOBAL_TENANT_ID 常量。", rel)
                    .doesNotContain("rag_global_tenant_id()");
        }
    }

    @Test
    @DisplayName("✅ 检索面仅保留 set_config 兼容层，且形态在白名单内（随 M4-R1 退役）")
    void onlyWhitelistedGucBindingsRemain() {
        for (String rel : EXPLICIT_SCOPE_FILES) {
            String code = stripComments(readSource(rel));

            // 逐个取出所有 set_config 调用，形态必须都在白名单里。
            int idx = 0;
            while ((idx = code.indexOf("set_config(", idx)) >= 0) {
                int end = code.indexOf(")", idx);
                assertThat(end).as("set_config 调用未闭合：%s", rel).isGreaterThan(idx);
                String call = code.substring(idx, end + 1);
                assertThat(ALLOWED_GUC_BINDINGS)
                        .as("检索面 %s 出现了白名单外的 set_config 形态：%s。"
                                + "新增兼容层绑定必须先在改造计划里登记理由。", rel, call)
                        .contains(call);
                idx = end + 1;
            }
        }
    }

    @Test
    @DisplayName("✅ 检索面必须真的用上 scope 渲染（不是把 scope 收下不用）")
    void scopeMustActuallyBeUsed() {
        String repo = stripComments(readSource(
                "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcRetrievalRepository.java"));

        assertThat(repo)
                .as("JdbcRetrievalRepository 必须调用 scopeTranslator.render(scope, ...) 并把片段贴进 SQL")
                .contains("scopetranslator.render(scope,");
        // ⚠️ 反向对照：若有人把 render 的结果丢掉（只用 scope 的类型做校验），
        //    上面的 contains 仍会绿——所以额外要求片段真的进了 SQL 文本。
        assertThat(repo)
                .as("作用域片段必须被拼进 SQL（frag.sql()），否则等于没有显式谓词")
                .contains("frag.sql()");
    }

    @Test
    @DisplayName("⛔ 每处 render 都必须带列限定符（裸 tenant_id 在 JOIN 下报 ambiguous）")
    void everyRenderMustCarryColumnQualifier() {
        String repo = stripComments(readSource(
                "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcRetrievalRepository.java"));

        int idx = 0;
        int checked = 0;
        while ((idx = repo.indexOf("scopetranslator.render(scope", idx)) >= 0) {
            int end = repo.indexOf(")", idx);
            assertThat(end).as("render 调用未闭合").isGreaterThan(idx);
            String call = repo.substring(idx, end + 1);
            assertThat(call)
                    .as("render 必须带列限定符：%s"
                            + "。裸 tenant_id 在多表 JOIN 下会报 "
                            + "「column reference tenant_id is ambiguous」——"
                            + "JOIN 的每张表都有 tenant_id 列。", call)
                    .contains("\"");
            checked++;
            idx = end + 1;
        }

        assertThat(checked)
                .as("检索面至少应有一处 render 调用（若为 0，说明改造被整体回退了）")
                .isGreaterThan(0);
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

    /**
     * 剥离注释，保留字符串字面量。
     *
     * <p>⚠️ 只处理 {@code //} 行注释与 {@code /* *}{@code /} 块注释；
     * 字符串字面量内的 {@code //}（如 URL）不剥离——那会让判定失真。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';

            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                // 行注释：跳到行尾
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}

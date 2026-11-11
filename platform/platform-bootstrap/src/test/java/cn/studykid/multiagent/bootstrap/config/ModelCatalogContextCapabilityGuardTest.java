package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型目录 5 列全链路一致性守卫（ADR-013 / M1 / R9 / 铁律 #10）。
 *
 * <p>⚠️ <b>专防「加列只加一半」的静默失真</b>：
 * {@code JdbcModelCatalogRepository} 的 {@code ON CONFLICT (id) DO UPDATE SET}
 * 若漏掉新列，管理后台每次 PUT 都会把模型能力覆盖回默认值 ——
 * 后台页面看着保存成功，运行期却一直按 32768 估算（不报错、只是数字悄悄错）。
 * 同理，save 回填若改用 {@code defaults()} 会把用户配置清空。
 *
 * <p>⚠️ 每条断言都能「撤掉修复点变红」；本类扫描源码时<b>剥离注释但保留字符串</b>，
 * 避免把「注释里写了列名」误判成「SQL 里真的有这列」。
 */
class ModelCatalogContextCapabilityGuardTest {

    private static final List<String> COLS = List.of(
            "context_window_tokens", "max_input_tokens", "tokenizer_id",
            "supports_prompt_caching", "supports_tokenize_api");

    @Test
    @DisplayName("✅ 01 终态快照：建表 5 列 + 5 条列注释 + 种子列清单")
    void ddlSnapshotHasAllFiveColumns() {
        String ddl = read(repoRoot().resolve("deploy/sql/platform/01_platform_ddl.sql"));
        int start = ddl.indexOf("CREATE TABLE public.model_catalog (");
        assertThat(start).as("01 里必须有 model_catalog 终态定义").isGreaterThan(0);
        String create = ddl.substring(start, ddl.indexOf(");", start));
        for (String col : COLS) {
            assertThat(create).as("⛔ 01 建表缺少列 " + col).contains(col);
            assertThat(ddl).as("⛔ 01 缺少列注释 " + col)
                    .contains("COMMENT ON COLUMN public.model_catalog." + col);
        }
        assertThat(ddl)
                .as("⛔ 01 内嵌种子 INSERT 未同步 5 列：新建库的模型能力会全按默认")
                .contains("context_window_tokens, max_input_tokens, tokenizer_id,");
        assertThat(ddl).contains("supports_prompt_caching, supports_tokenize_api)");
    }

    @Test
    @DisplayName("✅ 07 幂等 ALTER：5 列 + 类型断言 fail-closed")
    void liveDatabaseUpgradeIsIdempotentAndAsserted() {
        String sql = read(repoRoot().resolve(
                "deploy/sql/platform/07_model_catalog_context_upgrade.sql"));
        for (String col : COLS) {
            assertThat(sql)
                    .as("⛔ 07 未给活库补 " + col + "：存量库发出去的新 SQL 会直接报列不存在")
                    .contains("ADD COLUMN IF NOT EXISTS " + col);
        }
        assertThat(sql).contains("RAISE EXCEPTION");
        assertThat(sql).contains("BEGIN;").contains("COMMIT;");
    }

    @Test
    @DisplayName("⛔ Repository 四处 SELECT 都必须读出 5 列")
    void everySelectProjectsCapabilities() {
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure"
                        + "/persistence/JdbcModelCatalogRepository.java")));
        Matcher m = Pattern.compile("SELECT[\\s\\S]*?FROM model_catalog").matcher(code);
        int found = 0;
        while (m.find()) {
            found++;
            for (String col : COLS) {
                assertThat(m.group())
                        .as("⛔ 第 " + found + " 处 SELECT 漏读 " + col
                                + "：该入口会拿到默认能力（静默失真）")
                        .contains(col);
            }
        }
        assertThat(found).as("⛔ SELECT 数量变了，列清单核对口径需同步更新").isEqualTo(4);
    }

    @Test
    @DisplayName("⛔ ON CONFLICT DO UPDATE SET 必须逐列含 EXCLUDED（防 PUT 覆盖回默认）")
    void upsertUpdatesEveryCapabilityColumn() {
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure"
                        + "/persistence/JdbcModelCatalogRepository.java")));
        int s = code.indexOf("ON CONFLICT (id) DO UPDATE SET");
        assertThat(s).isGreaterThan(0);
        String block = code.substring(s, code.indexOf("\"\"\"", s));
        for (String col : COLS) {
            assertThat(block)
                    .as("⛔ DO UPDATE SET 漏了 " + col
                            + "：后台每次 PUT 都会把该能力覆盖回默认值（R9）")
                    .contains("EXCLUDED." + col);
        }
        assertThat(code).contains("ps.setInt(15").contains("ps.setInt(16")
                .contains("ps.setString(17").contains("ps.setBoolean(18")
                .contains("ps.setBoolean(19");
    }

    @Test
    @DisplayName("⛔ save 回填 / map 投影必须逐字段带过能力，禁止重置为 defaults()")
    void copiesCarryCapabilities() {
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure"
                        + "/persistence/JdbcModelCatalogRepository.java")));
        assertThat(code)
                .as("⛔ 回填构造未带过 contextCapabilities：保存后目录能力被清空（铁律 #10）")
                .contains("definition.contextCapabilities()");
        assertThat(code)
                .as("⛔ Repository 不得用 defaults() 覆盖用户配置")
                .doesNotContain("ModelContextCapabilities.defaults()");
        assertThat(code)
                .as("⛔ map() 未从 ResultSet 读取能力列")
                .contains("contextCapabilities(rs)");
    }

    @Test
    @DisplayName("✅ ModelDefinition 唯一全参构造 + 17 组件，能力组件在末尾")
    void modelDefinitionHasNoCompatibilityConstructor() {
        assertThat(ModelDefinition.class.getDeclaredConstructors()).hasSize(1);
        var components = ModelDefinition.class.getRecordComponents();
        assertThat(components).hasSize(17);
        assertThat(components[16].getName()).isEqualTo("contextCapabilities");
    }

    @Test
    @DisplayName("⛔ 后台请求体新增字段必须是包装类型（primitive 会让旧 payload 判 400）")
    void requestUsesNullableWrappers() {
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-api/src/main/java/cn/studykid/multiagent/api/admin"
                        + "/AdminModelConfigController.java")));
        assertThat(code).contains("@Nullable Integer contextWindowTokens")
                .contains("@Nullable Integer maxInputTokens")
                .contains("@Nullable Boolean supportsPromptCaching")
                .contains("@Nullable Boolean supportsTokenizeApi");
    }

    @Test
    @DisplayName("✅ R10 默认值跨表面一致：DDL / 07 / Controller / 前端 / 值对象都是 32768 与 28672")
    void defaultsAreConsistentAcrossSurfaces() {
        String ddl = read(repoRoot().resolve("deploy/sql/platform/01_platform_ddl.sql"));
        String upgrade = read(repoRoot().resolve(
                "deploy/sql/platform/07_model_catalog_context_upgrade.sql"));
        String ctrl = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-api/src/main/java/cn/studykid/multiagent/api/admin"
                        + "/AdminModelConfigController.java")));
        String page = read(repoRoot().resolve(
                "admin-console/src/pages/models/ModelCatalogPage.tsx"));

        assertThat(ddl).contains("context_window_tokens   integer NOT NULL DEFAULT 32768")
                .contains("max_input_tokens        integer NOT NULL DEFAULT 28672");
        assertThat(upgrade).contains("NOT NULL DEFAULT 32768")
                .contains("NOT NULL DEFAULT 28672");
        assertThat(ctrl).contains("32_768").contains("window - 4_096");
        assertThat(page).contains("contextWindowTokens: 32768")
                .contains("maxInputTokens: 28672");

        // 值对象默认值用反射外的行为断言（不引用被测实现自己的常量）
        var caps = cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities.defaults();
        assertThat(caps.contextWindowTokens()).isEqualTo(32_768);
        assertThat(caps.maxInputTokens()).isEqualTo(28_672);
        assertThat(caps.supportsPromptCaching()).isFalse();
        assertThat(caps.supportsTokenizeApi()).isFalse();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 剥离 Java 注释但保留字符串字面量（text block 里的 SQL 必须留下）。 */
    private static String stripCommentsKeepStrings(String src) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean inTextBlock = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && n == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inTextBlock) {
                if (c == '"' && i + 2 < src.length()
                        && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                    // 保留结束分隔符，守卫才能按 """ 定位 SQL text block 边界。
                    inTextBlock = false;
                    out.append("\"\"\"");
                    i += 2;
                } else {
                    out.append(c);
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"' && i + 2 < src.length()
                    && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                inTextBlock = true;
                out.append("\"\"\"");
                i += 2;
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && n == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && n == '*') {
                inBlockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录");
    }

    private static Path repoRoot() {
        return platformRoot().getParent();
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }
}

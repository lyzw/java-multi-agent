package cn.studykid.rag.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RAG DDL 头注释「表数 / RLS 开启 / 策略数」一致性守卫（O14 · 2026-11-18）。
 *
 * <p><b>为什么需要</b>：头注释曾长期写「11 表 / 11 RLS 开启 / 35 RLS 策略」，
 * 而文件实计已是 13 / 13 / 37。platform 侧有 {@code PlatformDdlHeaderCountTest}
 * 盯同类数字，RAG 侧一直没有，数字漂移无人发现。
 *
 * <p><b>反向验证</b>：把头注释改回 11/11/35，或往 DDL 加一条 CREATE POLICY
 * 却不同步头注释，本测试立即变红。
 *
 * <p><b>刻意只校验这三项</b>（表数 / RLS 开启数 / 策略数）：口径明确、与 grep 行数
 * 一一对应。头注释其余数字（列/约束/枚举/索引）来自 pg_dump 统计口径，含隐式对象，
 * 强行比对只会制造恒定假红。同款取舍见 platform 守卫。
 */
class RagDdlHeaderCountTest {

    private static final String SNAPSHOT_RELATIVE_PATH = "deploy/sql/rag/01_rag_ddl.sql";

    /** 形如「13 表 / 135 列」。 */
    private static final Pattern HEADER_TABLES = Pattern.compile("(\\d+)\\s+表\\s*/\\s*\\d+\\s+列");
    /** 形如「13 RLS 开启 / 37 RLS 策略」。 */
    private static final Pattern HEADER_COUNTS = Pattern.compile(
            "(\\d+)\\s+RLS\\s*开启\\s*/\\s*(\\d+)\\s+RLS\\s*策略");

    private static Path findDdl() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(SNAPSHOT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 RAG 结构权威源 " + SNAPSHOT_RELATIVE_PATH);
    }

    private static String text() {
        try {
            return Files.readString(findDdl(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 RAG DDL 失败", e);
        }
    }

    /** 数真实 SQL 语句行，跳过 -- 注释行。 */
    private static int statementCount(String prefix, String marker, String excluded) {
        int n = 0;
        for (String line : text().split("\\R")) {
            String t = line.strip();
            if (t.startsWith("--") || t.isEmpty()) {
                continue;
            }
            if (excluded != null && t.contains(excluded)) {
                continue;
            }
            if (t.startsWith(prefix) && t.contains(marker)) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("✅ 头注释表数/RLS 开启/策略数必须等于文件实计值")
    void headerCountsMatchFile() {
        String ddl = text();
        Matcher t = HEADER_TABLES.matcher(ddl);
        assertThat(t.find()).as("头注释缺少「N 表 / M 列」统计行").isTrue();
        Matcher c = HEADER_COUNTS.matcher(ddl);
        assertThat(c.find()).as("头注释缺少「N RLS 开启 / M RLS 策略」统计行").isTrue();

        int declaredTables = Integer.parseInt(t.group(1));
        int declaredEnable = Integer.parseInt(c.group(1));
        int declaredPolicy = Integer.parseInt(c.group(2));

        int actualTables = statementCount("CREATE TABLE rag.", "CREATE TABLE", null);
        int actualEnable = statementCount("ALTER TABLE", "ENABLE ROW LEVEL SECURITY", null);
        int actualForce = statementCount("ALTER TABLE", "FORCE ROW LEVEL SECURITY", "NO FORCE");
        int actualPolicy = statementCount("CREATE POLICY", "CREATE POLICY", null);

        assertThat(declaredTables).as("头注释表数与文件实计 CREATE TABLE 数不一致").isEqualTo(actualTables);
        assertThat(declaredEnable).as("头注释 RLS 开启数与文件实计 ENABLE 数不一致").isEqualTo(actualEnable);
        assertThat(declaredPolicy).as("头注释 RLS 策略数与文件实计 CREATE POLICY 数不一致").isEqualTo(actualPolicy);
        assertThat(actualForce).as("FORCE RLS 表数必须与 ENABLE 表数一致").isEqualTo(actualEnable);
        assertThat(actualEnable).as("RLS 开启表数不得为 0——快照被换成空壳时守卫必须红").isPositive();
    }

    @Test
    @DisplayName("✅ 更正说明必须在，避免头注释被整段回退到旧值 11/11/35")
    void correctionNotePresent() {
        assertThat(text()).contains("2026-11-18 更正（O14）");
    }
}

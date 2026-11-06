package cn.studykid.multiagent.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构门禁的<b>唯一</b>取数入口：仓库根 {@code deploy/sql/platform/01_platform_ddl.sql}。
 *
 * <p><b>为什么不再是迁移脚本</b>：2026-10-27 收口后 {@code db/migration/V1…V34} 已删除，
 * 库结构只有终态快照这一份定义（见 {@code deploy/sql/README.md}）。
 * 门禁若继续指向已删除的脚本，就成了「永远绿的假守卫」——
 * 它断言的结构在新环境里根本不存在。
 *
 * <p><b>⚠️ 这份快照是 {@code pg_dump} 的输出</b>，与手写迁移脚本有四处形状差异，
 * 所有门禁都必须按这里的约定解析：
 * <ul>
 *   <li>对象名一律 <b>schema 限定</b>（{@code public.run} / {@code platform.tenant}）——
 *       两个 schema 里都有 {@code tenant} 表，不限定会认错；</li>
 *   <li>主键/外键是<b>独立的</b> {@code ALTER TABLE ONLY … ADD CONSTRAINT}，
 *       CHECK 与列定义则在 {@code CREATE TABLE} 体内；</li>
 *   <li>类型与函数也是限定的（{@code public.run_state}、{@code public.current_tenant_id()}），
 *       且大小写为小写；</li>
 *   <li>布尔判定被写成 {@code (a = ANY (ARRAY[...]))} 而不是 {@code a IN (...)}，
 *       比较表达式被逐层加括号。</li>
 * </ul>
 * 第 4 条由 {@link #plain(String)} 统一抹平：去类型转换、去 schema 前缀、括号转空格、压缩空白。
 */
public final class PlatformDdl {

    /** 权威结构文件相对仓库根的路径。 */
    public static final String SNAPSHOT_RELATIVE_PATH = "deploy/sql/platform/01_platform_ddl.sql";

    /** 匹配 {@code CREATE TABLE [schema.]name}，返回限定名。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE\\s+((?:\\w+\\.)?\\w+)\\s*\\(");

    /** 匹配 {@code ALTER TABLE [ONLY] [schema.]name ENABLE|FORCE ROW LEVEL SECURITY}。 */
    private static final Pattern RLS_TOGGLE = Pattern.compile(
            "ALTER TABLE\\s+(?:ONLY\\s+)?((?:\\w+\\.)?\\w+)\\s+"
                    + "(NO FORCE|FORCE|ENABLE) ROW LEVEL SECURITY",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 {@code CREATE POLICY name ON [schema.]name … ;}。 */
    private static final Pattern POLICY = Pattern.compile(
            "CREATE POLICY\\s+\\w+\\s+ON\\s+((?:\\w+\\.)?\\w+)[^;]*;", Pattern.DOTALL);

    private static String cachedText;

    private PlatformDdl() {
    }

    /** @return 权威结构文件（向上查找仓库根，模块目录/IDE 工作目录都能定位）
     *  @throws IllegalStateException 文件不存在时 —— 宁可让门禁报错，也不要静默跳过 */
    public static Path file() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(SNAPSHOT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到结构权威源 " + SNAPSHOT_RELATIVE_PATH
                + "（从 " + Path.of("").toAbsolutePath() + " 向上找 8 层）。"
                + " 若是仓库搬家或该文件被改名，请同步修改 PlatformDdl.SNAPSHOT_RELATIVE_PATH。");
    }

    /** @return 结构文件全文 */
    public static String text() {
        String t = cachedText;
        if (t == null) {
            try {
                t = Files.readString(file(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取结构权威源失败: " + file(), e);
            }
            cachedText = t;
        }
        return t;
    }

    /**
     * 把 pg_dump 形态的表达式抹平成可比较的形式：
     * 去掉 {@code ::type} 转换、{@code public.} 前缀、括号与多余空白。
     *
     * <p>抹平只用于<b>断言侧</b>的比较；原始文本仍由调用方保存。
     */
    /** 函数调用的空括号占位符，避免被「分组括号抹平」顺带吃掉。 */
    private static final String EMPTY_PARENS = "\u0000";

    /**
     * 把 pg_dump 形态的表达式抹平成可比较的形式：
     * 去掉 {@code ::type} 转换、{@code public.} 前缀、<b>分组</b>括号与多余空白。
     *
     * <p>⚠️ 函数调用的空括号（{@code current_tenant_id()}）<b>不</b>抹掉：
     * 抹分组括号是为了让 {@code ((a) AND (b))} 与 {@code a AND b} 可比，
     * 连带抹掉 {@code f()} 会逼断言写成裸标识符 {@code current_tenant_id} ——
     * 读的人无法判断那是「函数」还是「漏了括号」。
     *
     * <p>抹平只用于<b>断言侧</b>的比较；原始文本仍由调用方保存。
     */
    public static String plain(String sql) {
        return sql
                .replaceAll("(?i)::\\w+(\\.\\w+)?", "")
                .replaceAll("(?i)\\bpublic\\.", "")
                .replace("()", EMPTY_PARENS)
                .replaceAll("[()]", " ")
                .replaceAll("\\s+", " ")
                .replace(EMPTY_PARENS, "()")
                .trim();
    }

    /** @return 全部建出的表（<b>schema 限定名</b>，如 {@code public.run}、{@code platform.tenant}） */
    public static List<String> createdTables() {
        List<String> out = new ArrayList<>();
        Matcher m = CREATE_TABLE.matcher(text());
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * @param verb {@code ENABLE} 或 {@code FORCE}
     * @return 做过该 RLS 开关的表（schema 限定名，去重）
     */
    public static List<String> rlsTables(String verb) {
        List<String> out = new ArrayList<>();
        Matcher m = RLS_TOGGLE.matcher(text());
        while (m.find()) {
            if (m.group(2).equalsIgnoreCase(verb) && !out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** @return 挂了 RLS 策略的表（schema 限定名）；同一表多条策略只出现一次 */
    public static List<String> policyTables() {
        List<String> out = new ArrayList<>();
        Matcher m = POLICY.matcher(text());
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** @return 挂在指定表上的全部策略语句原文（已去 {@code public.} 前缀） */
    public static List<String> policies(String table) {
        List<String> out = new ArrayList<>();
        Matcher m = POLICY.matcher(text());
        while (m.find()) {
            if (simpleName(m.group(1)).equals(simpleName(table))) {
                out.add(m.group().replace("public.", ""));
            }
        }
        return out;
    }

    /**
     * @param table 表名（限定或不限定均可）
     * @return 该表 {@code CREATE TABLE} 括号内的原文；表不存在时返回空串
     */
    public static String tableBody(String table) {
        String simple = simpleName(table);
        Matcher m = CREATE_TABLE.matcher(text());
        while (m.find()) {
            if (!simpleName(m.group(1)).equals(simple)) {
                continue;
            }
            String body = balancedBody(text(), m.end() - 1);
            if (body != null) {
                return body;
            }
        }
        return "";
    }

    /**
     * @param table 表名（限定或不限定）
     * @return 快照为该表声明的<b>列名</b>（按声明顺序，不含约束行）
     */
    public static List<String> columnNames(String table) {
        List<String> out = new ArrayList<>();
        for (String part : splitTopLevel(tableBody(table))) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String head = p.split("[\\s(]", 2)[0].toUpperCase(java.util.Locale.ROOT);
            if (CONSTRAINT_HEADS.contains(head)) {
                continue;
            }
            Matcher m = Pattern.compile("^(\\w+)").matcher(p);
            if (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** pg_dump 建表体里以这些关键字开头的片段是<b>约束</b>，不是列。 */
    private static final List<String> CONSTRAINT_HEADS =
            List.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE");

    /** 按<b>顶层逗号</b>切分（括号内的逗号不切）。 */
    public static List<String> splitTopLevel(String body) {
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

    /** @return 末段名（{@code public.run} → {@code run}） */
    public static String simpleName(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    /** @return 从 {@code open} 处 '(' 开始的配平括号内容；不配平返回 {@code null} */
    public static String balancedBody(String text, int open) {
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
}

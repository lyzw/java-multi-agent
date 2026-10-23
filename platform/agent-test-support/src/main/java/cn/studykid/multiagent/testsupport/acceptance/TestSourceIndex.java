package cn.studykid.multiagent.testsupport.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 测试源码索引（验收证据的可核对性支撑 · W-PERM-P6-T1/T5）。
 *
 * <p>⚠️ <b>本类解决的问题</b>：追踪矩阵里写「{@code FooTest#bar} 已覆盖」，
 * 是一句<b>无法证伪</b>的话 —— 那个测试方法可能早就被改名或删掉了。
 * 要让「证据路径」名副其实，必须能回到源码里<b>核对该方法确实存在</b>，
 * 且它<b>确实断言了期望的原因码</b>。
 *
 * <p>⚠️ <b>为什么用「读源码」而不是「反射」</b>：
 * 证据落点横跨多个 Maven 模块，而模块之间<b>不共享 test classpath</b>
 * （没有 test-jar）—— 反射只能看到本模块的测试类。
 * 读源码则能横跨全仓，且顺带能拿到<b>方法体文本</b>：
 * 「方法里出现了 {@code ReasonCode.XXX}」这件事，用反射是看不到的。
 *
 * <p>⚠️ <b>与 {@code ToolBypassArchitectureTest} 的扫描基建同源</b>：
 * 两者都用「从当前工作目录向上找含 {@code agent-core} 的目录」来定位 platform 根，
 * 因此都能覆盖<b>全部子模块</b>而非仅本模块。
 */
public final class TestSourceIndex {

    /** 方法声明的形态（用于定位方法起点）。 */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?"
                    + "(?:<[^>]+>\\s*)?(?:void|boolean|int|long|double|String|"
                    + "Optional<[^>]+>|List<[^>]+>|Set<[^>]+>|Map<[^>]+>|"
                    + "[A-Z][A-Za-z0-9_]*)\\s+(\\w+)\\s*\\(");

    private static final Map<String, Path> INDEX = buildIndex();

    private TestSourceIndex() {
    }

    /**
     * 定位 platform 源码根目录。
     *
     * @return platform 根目录
     */
    public static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    /**
     * @param className 测试类简单名
     * @return 该类所在的源文件；未找到时为 {@link Optional#empty()}
     */
    public static Optional<Path> findSource(String className) {
        return Optional.ofNullable(INDEX.get(className));
    }

    /**
     * 读取测试方法的方法体（已剥离注释与字符串字面量）。
     *
     * <p>⚠️ <b>剥离字符串是为了让「方法体里提到了某个码」这件事有意义</b>：
     * 若断言文案里写了「期望 ReasonCode.XXX」，而真正断言的是别的码，
     * 不剥离字符串就会把「文案提到」误判成「断言了」。
     *
     * @param className  测试类简单名
     * @param methodName 测试方法名
     * @return 方法体文本；类或方法不存在时为 {@link Optional#empty()}
     */
    public static Optional<String> methodBody(String className, String methodName) {
        Optional<Path> source = findSource(className);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        String code = stripCommentsAndStrings(read(source.get()));
        Matcher matcher = METHOD_DECL.matcher(code);
        int start = -1;
        while (matcher.find()) {
            if (matcher.group(1).equals(methodName)) {
                start = matcher.start();
                break;
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        return Optional.of(extractBlock(code, start));
    }

    /**
     * @param className  测试类简单名
     * @param methodName 测试方法名
     * @return 该方法体是否包含给定片段
     */
    public static boolean methodBodyContains(String className, String methodName, String fragment) {
        return methodBody(className, methodName)
                .map(body -> body.contains(fragment))
                .orElse(false);
    }

    /**
     * 全部已索引的测试类简单名。
     *
     * @return 类名集合
     */
    public static java.util.Set<String> indexedClasses() {
        return INDEX.keySet();
    }

    // ── 内部实现 ──────────────────────────────────────────────────

    private static Map<String, Path> buildIndex() {
        Map<String, Path> index = new LinkedHashMap<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/test/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    tree.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        if (dot > 0) {
                            index.putIfAbsent(name.substring(0, dot), p);
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描测试源码失败: " + root, e);
        }
        return index;
    }

    /**
     * 从方法声明处截取到方法体结束（按花括号配平）。
     */
    private static String extractBlock(String code, int methodStart) {
        int open = code.indexOf('{', methodStart);
        if (open < 0) {
            return code.substring(methodStart);
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(methodStart, i + 1);
                }
            }
        }
        return code.substring(methodStart);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 去掉注释与字符串字面量，只留可执行代码。
     *
     * <p>⚠️ <b>必须连字符串一起去掉</b>：测试断言的失败文案里常写着
     * 「期望 {@code ReasonCode.AUTHZ_STALE}」——
     * 不剥离就会把「文案提到」误判成「断言了」，
     * 那正是本索引要防的假证据。
     */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"'
                        && src.charAt(i + 2) == '"')) {
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append(' ');
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('"');
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('\'');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 供外部复用的「相对路径」表示。
     *
     * @param file 文件
     * @return 相对 platform 根目录的路径
     */
    public static String relative(Path file) {
        List<String> parts = new ArrayList<>();
        Path current = file.toAbsolutePath();
        while (current != null && !current.equals(platformRoot())) {
            parts.add(0, current.getFileName().toString());
            current = current.getParent();
        }
        return String.join("/", parts);
    }
}

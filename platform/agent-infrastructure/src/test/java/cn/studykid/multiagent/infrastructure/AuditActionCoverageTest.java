package cn.studykid.multiagent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3-2 门禁：<b>声明了的审计动作，必须真的有写入方</b>。
 *
 * <p>⚠️⚠️ <b>本类要防的故障形态</b>：
 * {@code AuditPort.Actions} 里定义了一个动作常量（比如
 * {@code CONFIG_PUBLISH_FAILED}），契约文档写「失败也必须留痕」，
 * 但代码里<b>从头到尾没有任何一处调用它</b>。
 * 于是「批过但没生效」这类最需要追溯的场景，
 * 在 {@code audit_record} 里<b>一行都没有</b> ——
 * 而且这件事<b>不报任何错</b>：没有编译错误、没有运行异常，
 * 只有真正出事想查日志时才发现「这一项从来就没被记过」。
 *
 * <p>这正是本项目反复遭遇的那一类：<b>声明存在、实现缺席、系统静默</b>。
 * 常量定义本身会给人一种「已经覆盖」的错觉 —— 它出现在代码里、
 * 出现在文档里、出现在评审清单里，但没人验证过它被写入。
 *
 * <p>⚠️ <b>本类不依赖数据库</b>：只扫源码文本，CI 无库也能跑。
 */
class AuditActionCoverageTest {

    /** {@code audit_record} 写入方的源码根目录（相对仓库根）。 */
    private static final Path PLATFORM_DIR = Path.of("platform");

    /** 匹配 {@code public static final String <NAME> = "..."}。 */
    private static final Pattern CONSTANT = Pattern.compile(
            "public static final String\\s+(\\w+)\\s*=");

    /** 静态导入动作常量的写法：{@code import static …AuditPort.Actions.APPROVAL_GRANT;} */
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+[\\w.]*AuditPort\\.Actions\\.(\\w+)\\s*;");

    /** 仓库根（向上查找，模块目录/IDE 工作目录都能定位）。 */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(PLATFORM_DIR))) {
                return dir;
            }
        }
        throw new IllegalStateException("找不到仓库根（应含 " + PLATFORM_DIR
                + " 目录），从 " + Path.of("").toAbsolutePath() + " 向上找 8 层未命中");
    }

    /**
     * 已声明的动作常量：直接由 {@code AuditPort.Actions} 的字段反射得出。
     *
     * <p>⚠️ <b>为什么用反射而不是正则扫源码</b>：反射取的是<b>编译后的事实</b>，
     * 与「生产代码实际能引用到什么」严格一致；正则扫文本则会把注释、
     * 字符串字面量里的同名文本也算进来，产生假绿。
     */
    private static List<String> declaredActions() {
        return java.util.Arrays.stream(
                        cn.studykid.multiagent.ports.persistence.AuditPort.Actions.class
                                .getFields())
                .map(java.lang.reflect.Field::getName)
                .sorted()
                .toList();
    }

    /**
     * 生产源码里对 {@code AuditPort.Actions.<NAME>} 的引用。
     *
     * <p>⚠️ <b>不能只匹配 {@code Actions.(\w+)}</b>：仓库里还有别的
     * {@code Actions} 类（如 {@code AuthorizationService.Actions}），
     * 它们各自有 {@code RUN_READ} 之类的同名成员。
     * 粗匹配会把那些引用错误地算到审计头上，
     * 既污染覆盖率判定，也会让「反向检查」报出根本不存在的拼写错误。
     * 故：仅当文件确实引入了 {@code AuditPort} 时才认 {@code Actions.X} 简写。
     */
    private static List<String> referencedActions() {
        Path root = repoRoot().resolve(PLATFORM_DIR);
        List<String> refs = new ArrayList<>();
        Pattern qualified = Pattern.compile("AuditPort\\.Actions\\.(\\w+)");
        Pattern shortForm = Pattern.compile("(?<!\\w)Actions\\.(\\w+)");
        Pattern auditPortImported = Pattern.compile("import\\s+(static\\s+)?[\\w.]*AuditPort");
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .forEach(p -> {
                        String text = read(p);
                        Matcher q = qualified.matcher(text);
                        while (q.find()) {
                            refs.add(q.group(1));
                        }
                        if (STATIC_IMPORT.matcher(text).find()
                                || auditPortImported.matcher(text).find()) {
                            Matcher s = shortForm.matcher(text);
                            while (s.find()) {
                                refs.add(s.group(1));
                            }
                        }
                        Matcher imp = STATIC_IMPORT.matcher(text);
                        while (imp.find()) {
                            refs.add(imp.group(1));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return refs;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("⛔ 每个已声明的审计动作都必须有写入方（声明存在 + 实现缺席 = 静默漏审计）")
    void everyDeclaredActionHasAWriter() {
        List<String> declared = declaredActions();
        List<String> referenced = referencedActions();

        assertThat(declared)
                .as("必须真的解析到动作常量，否则本门禁是假的")
                .isNotEmpty();

        List<String> orphan = declared.stream()
                .filter(name -> !referenced.contains(name))
                .toList();

        assertThat(orphan)
                .as("这些审计动作被声明出来，却在全仓生产代码里没有任何写入方 —— "
                        + "契约里写了「必须留痕」，实际一行都查不到，且不报任何错。"
                        + "请补上写入，或删除这个并未落地的常量（留着它会制造「已覆盖」的错觉）")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 反向检查：代码里引用的动作必须是已声明的常量（拼错会静默写进库）")
    void referencedActionsAreAllDeclared() {
        // ⚠️ 反向检查同样重要：若有人手写 action 字符串拼错，
        // 审计行会正常写入（不报错），但查询侧按常量聚合时它永远不出现 ——
        // 「写进去了」和「能被查出来」是两件事。
        List<String> declared = declaredActions();
        List<String> orphan = referencedActions().stream()
                .filter(name -> !declared.contains(name))
                .distinct()
                .toList();

        assertThat(orphan)
                .as("代码里引用了未在 AuditPort.Actions 声明的动作常量（拼错或已删除）："
                        + "这类引用若来自字符串字面量，写入会成功但查询永远查不到")
                .isEmpty();
    }
}

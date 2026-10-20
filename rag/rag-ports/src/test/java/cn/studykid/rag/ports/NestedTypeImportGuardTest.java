package cn.studykid.rag.ports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端口层「类型顶层化」不变式（{@code LayoutBlock} 类注释所述规则的自动化守卫）。
 *
 * <p><b>⚠️ 这条不变式防的是什么</b>：
 * JDK 21.0.12 的 javac 存在一个可复现的符号解析缺陷——
 * 当某个编译单元<b>同时导入</b>外层类型 {@code X} 与<b>其嵌套类型</b> {@code X.Nested} 时，
 * javac 会提前「完成」{@code X}；若 {@code X} 的嵌套类型持有某个枚举类型的字段，
 * 该枚举会被登记为「仅读取了类文件头部」的依赖符号，<b>成员表始终为空</b>，
 * 于是源码里再引用它的常量就会报
 * {@code 找不到符号 / 位置: 类 XxxEnum}——<b>即使该 classfile 确实含全部常量</b>。
 *
 * <p>本项目已把规避方式固化为规则：
 * <b>端口层不得出现嵌套类型</b>，一律提升为顶层类型
 * （{@code LayoutBlock} 就是为此从 {@code DocumentParserPort} 的嵌套 record
 * 提升上来的）。本测试是该规则的<b>唯一自动化守卫</b>。
 *
 * <p>完整复现、机理与规避验证见 RAG-D09 §6「工具链已知缺陷与规避」。
 */
class NestedTypeImportGuardTest {

    private static final String PACKAGE = "cn.studykid.rag.ports";

    @Test
    @DisplayName("端口层不得存在嵌套类型——一律提升为顶层（规避 JDK 21 javac 符号解析缺陷）")
    void portTypesAreAllTopLevel() throws IOException, ClassNotFoundException {
        Path src = Path.of("src/main/java");
        assertThat(src).as("端口层源码目录应存在").exists();

        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(src)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(javaFiles).as("端口层应包含源码文件").isNotEmpty();

        for (Path file : javaFiles) {
            String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
            Class<?> type = Class.forName(PACKAGE + "." + simpleName);

            assertThat(type.getDeclaringClass())
                    .as("端口类型 %s 必须是顶层类型（不得嵌套在任何外层类型内部）", simpleName)
                    .isNull();

            assertThat(type.getDeclaredClasses())
                    .as("端口类型 %s 不得持有嵌套类型（会重新引入 javac 符号解析缺陷）", simpleName)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("端口层源码不得出现嵌套类型声明（class/interface/enum/record 关键字缩进嵌套）")
    void portSourcesDeclareNoNestedTypes() throws IOException {
        Path src = Path.of("src/main/java");
        List<String> nestedDeclarations = List.of(
                "    class ", "    interface ", "    enum ", "    record ");

        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String declaration : nestedDeclarations) {
                    assertThat(content)
                            .as("文件 %s 不得声明嵌套类型（%s）", file.getFileName(), declaration.trim())
                            .doesNotContain(declaration);
                }
            }
        }
    }
}

package cn.studykid.rag.premise;

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
 * premise 层「类型顶层化」不变式（{@code rag-ports} 的
 * {@code NestedTypeImportGuardTest} 同款规则，按 RAG-D26 §4 扩展覆盖）。
 *
 * <p><b>⚠️ 这条不变式防的是什么</b>：
 * JDK 21.0.12 的 javac 存在可复现的符号解析缺陷——编译单元同时导入外层类型
 * 与其嵌套类型时，嵌套类型持有的枚举会被登记为成员表为空的依赖符号，
 * 后续按名引用枚举常量报「找不到符号」。规避规则：
 * <b>SPI 与模型层不得出现嵌套类型</b>，一律顶层声明。
 *
 * <p>⚠️ 判定用「类内首个类型声明前的缩进 class/interface/enum/record」近似
 * 源码扫描（与 ports 守卫同口径）：四空格缩进的类型声明即视为嵌套。
 */
class PremiseTopLevelTypeGuardTest {

    private static final String PACKAGE = "cn.studykid.rag.premise";

    @Test
    @DisplayName("premise 类型必须是顶层且不得持有嵌套类型")
    void premiseTypesAreAllTopLevel() throws IOException, ClassNotFoundException {
        Path src = Path.of("src/main/java");
        assertThat(src).as("premise 源码目录应存在").exists();

        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(src)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(javaFiles).as("premise 应包含源码文件").isNotEmpty();

        for (Path file : javaFiles) {
            String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
            // 按目录结构推导 FQCN：src/main/java 之后的相对路径「就是」完整包路径
            // （cn/studykid/rag/premise/role/X.java → cn.studykid.rag.premise.role.X），
            // 不再额外拼根包前缀。
            String fqn = src.relativize(file.getParent()).toString()
                    .replace(java.io.File.separatorChar, '.')
                    + "." + simpleName;
            Class<?> type = Class.forName(fqn);

            assertThat(type.getDeclaringClass())
                    .as("premise 类型 %s 必须是顶层类型（不得嵌套）", fqn)
                    .isNull();

            assertThat(type.getDeclaredClasses())
                    .as("premise 类型 %s 不得持有嵌套类型", fqn)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("premise 源码不得声明嵌套类型")
    void premiseSourcesDeclareNoNestedTypes() throws IOException {
        Path src = Path.of("src/main/java");
        List<String> nestedDeclarations = List.of(
                "    class ", "    interface ", "    enum ", "    record ");

        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String declaration : nestedDeclarations) {
                    assertThat(content)
                            .as("文件 %s 不得声明嵌套类型（%s）", file.getFileName(),
                                    declaration.trim())
                            .doesNotContain(declaration);
                }
            }
        }
    }

    @Test
    @DisplayName("premise 不得依赖 rag-infrastructure（依赖方向 infrastructure → premise）")
    void premiseMustNotDependOnInfrastructure() throws IOException {
        Path pom = Path.of("pom.xml");
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        assertThat(content)
                .as("rag-premise 的 pom 不得依赖 rag-infrastructure（分层约束，"
                        + "Docling 包装类应放在 infrastructure 侧）")
                .doesNotContain("<artifactId>rag-infrastructure</artifactId>");

        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(source)
                        .as("premise 源码 %s 不得 import infrastructure 包", file.getFileName())
                        .doesNotContain("import cn.studykid.rag.infrastructure");
            }
        }
    }
}

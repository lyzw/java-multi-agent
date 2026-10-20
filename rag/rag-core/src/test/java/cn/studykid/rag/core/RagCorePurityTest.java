package cn.studykid.rag.core;

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
 * 领域层纯度不变式（与主平台 {@code AgentCorePurityTest} 同构）。
 *
 * <p><b>⚠️ 为什么必须有这条测试</b>：领域层承载的是<b>最需要长期稳定</b>的规则——
 * 版本过滤、泄题闸门、接地判定。它们一旦被框架类型污染（如引入
 * {@code jakarta.*}、Spring 的 {@code Pageable}、Jackson 的 {@code JsonNode}），
 * 就会在框架升级时被迫改动，而<b>这类改动的正确性无法靠编译期检查保证</b>。
 *
 * <p>主平台 ADR-011 §5 记录了这条不变式的实际收益：回滚 Boot 版本时
 * 领域层代码零改动。本测试是那条结论的<b>自动化守卫</b>。
 */
class RagCorePurityTest {

    @Test
    @DisplayName("领域层编译期依赖仅 JSpecify，无任何框架依赖")
    void coreHasNoFrameworkDependencies() throws IOException {
        Path pom = Path.of("pom.xml");
        assertThat(pom).exists();

        String content = Files.readString(pom, StandardCharsets.UTF_8);
        List<String> forbidden = List.of(
                "spring-boot-starter",
                "spring-context",
                "spring-web",
                "jakarta.",
                "jackson",
                "hibernate",
                "flyway",
                "postgresql",
                "mybatis",
                "jooq");

        for (String artifact : forbidden) {
            assertThat(content)
                    .as("领域层 pom 不得依赖 %s —— 领域规则必须可跨框架版本迁移", artifact)
                    .doesNotContain("<artifactId>" + artifact + "</artifactId>");
        }
    }

    @Test
    @DisplayName("领域层源码不 import 任何框架包")
    void coreSourcesHaveNoFrameworkImports() throws IOException {
        Path src = Path.of("src/main/java");
        assertThat(src).exists();

        List<String> forbiddenPrefixes = List.of(
                "import org.springframework.",
                "import jakarta.",
                "import com.fasterxml.",
                "import tools.jackson.",
                "import org.postgresql.",
                "import org.flywaydb.");

        try (Stream<Path> files = Files.walk(src)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).as("领域层应包含源码文件").isNotEmpty();

            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String prefix : forbiddenPrefixes) {
                    assertThat(content)
                            .as("文件 %s 不得 import %s", file.getFileName(), prefix)
                            .doesNotContain(prefix);
                }
            }
        }
    }

    @Test
    @DisplayName("领域层无 lombok / 依赖注解放松的痕迹（显式构造器校验不可被绕过）")
    void coreKeepsExplicitValidation() throws IOException {
        Path src = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(content)
                        .as("文件 %s 不得使用 lombok——会绕过 record 紧凑构造器的校验", file.getFileName())
                        .doesNotContain("lombok");
            }
        }
    }
}

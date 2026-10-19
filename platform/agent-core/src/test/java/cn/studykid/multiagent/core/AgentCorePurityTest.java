package cn.studykid.multiagent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构约束自动校验（v3.1 架构文档第 146 行 / P1-D01 模块边界）。
 *
 * <p>v3.1 原文：
 * <blockquote>agent-core 不直接依赖 Spring AI、MQ SDK、Web 框架或数据库驱动。</blockquote>
 *
 * <p>此约束若靠人工 Review 保证，必然随迭代腐化。本测试把它变成
 * <b>构建期硬门禁</b>：一旦领域层引入框架依赖，构建立即失败。
 *
 * <p>与 ISO-1～ISO-5 同属「结构性约束优于口头约定」的落地方式。
 */
class AgentCorePurityTest {

    private static final Path CORE_SRC = Path.of("src/main/java");

    /** 领域层<b>禁止</b>出现的 import 前缀。 */
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.springframework",   // Spring 全家桶（含 Spring AI）
            "jakarta.",               // Jakarta EE
            "org.hibernate",          // Hibernate
            "org.flywaydb",           // Flyway
            "org.apache.kafka",       // Kafka SDK
            "org.springframework.amqp",// AMQP
            "com.rabbitmq",           // RabbitMQ client
            "org.postgresql",         // PG 驱动
            "org.flywaydb.database.postgresql",
            "io.micrometer",          // 指标库
            "ch.qos.logback",         // 日志实现
            "org.apache.logging"
    );

    @Test
    @DisplayName("agent-core 不依赖任何框架（Spring/JPA/MQ/驱动/日志实现）")
    void coreHasNoFrameworkDependencies() {
        List<String> violations = collectViolations(
                imp -> FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imp::startsWith));
        assertThat(violations)
                .as("领域层出现框架依赖（违反 v3.1 架构约束）: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("agent-core 不使用 Spring 注解")
    void coreHasNoSpringAnnotations() {
        List<String> violations = collectViolations(imp -> imp.startsWith("org.springframework"));
        assertThat(violations).as("领域层出现 Spring 依赖").isEmpty();
    }

    @Test
    @DisplayName("agent-core 只允许 JSpecify 作为外部依赖")
    void coreExternalDepsAreMinimal() {
        // JSpecify 以类名形式 import（org.jspecify.annotations.NonNull 等），故按前缀匹配
        List<String> violations = collectViolations(imp -> !imp.startsWith("java.")
                && !imp.startsWith("cn.studykid.multiagent.")
                && !imp.startsWith("org.jspecify.annotations."));
        assertThat(violations)
                .as("领域层引入未预期的外部依赖: %s", violations)
                .isEmpty();
    }

    /** 遍历领域层源码，收集满足 predicate 的 import 及其来源文件。 */
    private static List<String> collectViolations(java.util.function.Predicate<String> predicate) {
        List<String> violations = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE_SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String imp : readImports(file)) {
                    if (predicate.test(imp)) {
                        violations.add(file.getFileName() + " → " + imp);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取领域层源码失败", e);
        }
        return violations;
    }

    private static List<String> readImports(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> l.startsWith("import "))
                .map(l -> l.substring("import ".length()).replace("static ", "").replace(";", "").trim())
                .toList();
    }
}

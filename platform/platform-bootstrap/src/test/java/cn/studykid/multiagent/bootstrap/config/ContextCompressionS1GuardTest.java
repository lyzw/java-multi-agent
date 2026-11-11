package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩 S1 影子期的架构守卫（ADR-013 / 开发计划 §0.4、§7.4）。
 *
 * <p>⚠️ <b>每条断言都能「撤掉修复点变红」</b>：把白名单登记删掉、
 * 把门禁调用删掉、把 {@code enabled} 默认改成 true、把独立 HMAC 键删掉、
 * 或给纯算法层加一个 Spring/JDBC import，本类对应用例立即失败。
 *
 * <p>⚠️ 本类是<b>阶段守卫</b>：S2 接线完成后按计划升级为
 * {@code NoDirectTrimCallOutsideAssemblerGuard} 等运行期守卫，
 * 届时可退役的断言须在评审记录里显式登记，不得直接删除了事。
 */
class ContextCompressionS1GuardTest {

    private static final Set<String> FORBIDDEN_IMPORT_PREFIXES = Set.of(
            "org.springframework.", "java.sql.", "javax.sql.",
            "com.zaxxer.", "org.flywaydb.", "org.postgresql.");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    @Test
    @DisplayName("✅ agent-context 是纯算法层：零 Spring / 零 JDBC / 零驱动 import")
    void contextModuleIsFrameworkFree() {
        Path src = platformRoot().resolve("agent-context/src/main/java");
        assertThat(Files.isDirectory(src)).as("agent-context 模块必须存在（[D1]）").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(src)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(read(file));
                while (matcher.find()) {
                    String imported = matcher.group(1);
                    if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                        violations.add(file.getFileName() + " → " + imported);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 agent-context 源码失败", e);
        }

        assertThat(violations)
                .as("⛔ 纯算法层被框架/持久层污染：C1 要求 DB 写入只能在 agent-persistence")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 发布期门禁调用同一份压缩策略校验器，不另写范围常量")
    void validationGateUsesSharedValidator() {
        // ⚠️ 必须剥离注释再扫：Javadoc 里解释「为什么」的表达式不是实现，
        //    不剥离会把「文档写得清楚」误判成「另写了一套校验」（假红）。
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-application/src/main/java/cn/studykid/multiagent/application/cap"
                        + "/ConfigValidationGate.java")));

        assertThat(code)
                .as("⛔ validate() 未调用压缩策略门禁：非法策略会活到配置生效之后")
                .contains("validateGenerationParams(command)");
        assertThat(code)
                .as("⛔ 未复用 agent-context 的唯一校验实现：发布期与执行期必然漂移")
                .contains("ContextPolicyValidator.validate(");
        assertThat(code)
                .as("⛔ 门禁里出现硬编码比例常量：两套范围常量迟早不一致")
                .doesNotContain("0.70")
                .doesNotContain("targetRatio >= triggerRatio");
        assertThat(code)
                .as("⛔ 发布期未复用运行期同一份 JSON 归一化：合法/非法判定会漂移")
                .contains("ContextPolicyReader.contextNode(")
                .contains("ContextPolicyReader.toPlainMap(")
                .doesNotContain("MAPPER.readTree(agent.generationParams())");
    }

    @Test
    @DisplayName("✅ 依赖方向守卫已登记 agent-context（撤掉登记即红）")
    void dependencyDirectionWhitelistIsRegistered() {
        String guard = read(platformRoot().resolve(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config"
                        + "/ModuleDependencyDirectionTest.java"));

        assertThat(guard)
                .as("⛔ 应用层依赖纯算法层却未登记白名单：ModuleDependencyDirectionTest 会红")
                .contains("\"agent-context\"");
    }

    @Test
    @DisplayName("✅ S1 影子期默认关闭，且 HMAC 密钥为独立键")
    void shadowPhaseIsDisabledByDefault() {
        String yml = read(platformRoot().resolve(
                "platform-bootstrap/src/main/resources/application.yml"));

        assertThat(yml)
                .as("⛔ 影子期默认开启会改变模型请求字节，违反 G1「影子对照零差异」")
                .contains("AGENT_CONTEXT_ENABLED:false");
        assertThat(yml)
                .as("⛔ 缺少独立 HMAC 键：contextState 验签会退化成复用 JWT 私钥")
                .contains("AGENT_CONTEXT_STATE_HMAC_KEY");
    }

    @Test
    @DisplayName("✅ env 模板声明独立密钥占位，且不含真实值")
    void envTemplateDeclaresIndependentKey() {
        String env = read(repoRoot().resolve("platform.env.example"));

        assertThat(env).contains("AGENT_CONTEXT_STATE_HMAC_KEY=<MUST-FILL>");
        assertThat(env)
                .as("⛔ 密钥必须独立于两套 JWT 私钥")
                .contains("绝不复用 JWT 私钥");
    }

    @Test
    @DisplayName("✅ S1 影子期：三条模型链路零压缩接线（G1「请求字节零差异」结构证据）")
    void shadowPhaseHasNoProductionWiring() {
        // ⚠️ 阶段守卫：S2 接线时按计划升级为
        //    NoDirectTrimCallOutsideAssemblerGuard，并在评审记录登记退役，不得直接删。
        List<String> chains = List.of(
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java",
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java",
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java",
                "agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java");
        List<String> forbidden = List.of(
                "ContextAssembler", "ContextPolicyResolver", "BudgetAllocator",
                "CompactionPipeline", "cn.studykid.multiagent.context.");

        List<String> violations = new ArrayList<>();
        for (String rel : chains) {
            Path file = platformRoot().resolve(rel);
            if (!Files.exists(file)) {
                continue;   // 文件被重构/移位时由 CH 契约单处理，此处不假红
            }
            String code = stripCommentsKeepStrings(read(file));
            for (String token : forbidden) {
                if (code.contains(token)) {
                    violations.add(rel + " → " + token);
                }
            }
        }
        assertThat(violations)
                .as("⛔ S1 不得接线：G1 要求 enabled=false 与「无压缩功能」逐位一致")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ ContextPolicy 只有唯一全参拷贝点，禁止新增旁路的兼容构造/投影")
    void contextPolicyHasSingleCopyPoint() {
        String src = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-context/src/main/java/cn/studykid/multiagent/context/ContextPolicy.java")));

        int constructions = countOccurrences(src, "new ContextPolicy(");
        assertThat(constructions)
                .as("⛔ 全参构造必须是 defaults() / off() / copy() 三处；"
                        + "新增旁路会让「加字段漏带」绕过编译期强制")
                .isEqualTo(3);
        assertThat(src)
                .as("⛔ 唯一拷贝点 copy() 消失，withXxx 将各自散写全参构造")
                .contains("private @NonNull ContextPolicy copy(");
        assertThat(src)
                .as("⛔ 出现了兼容构造器（铁律 #11）")
                .doesNotContain("public ContextPolicy(");
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 剥离 Java 注释但<b>保留字符串字面量</b>（项目既有源码守卫纪律）。
     * 不剥离会把 Javadoc 里的说明文字当成实现，产生结构性假红。
     */
    private static String stripCommentsKeepStrings(String src) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
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
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
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

    // ── 辅助 ──────────────────────────────────────────────────────

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

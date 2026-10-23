package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 防绕过架构测试（P2-T8 · 07 §3 · §7.2 · §8.3 防绕过 0 通过）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：07 §3 明确写明
 * 「静态扫描无法单独证明闭环」——
 * 因此防绕过必须由<b>代码扫描 + 架构测试 + 运行时测试</b>共同验证，
 * 三者缺一，结论就不成立。
 *
 * <p>三层各自的<b>可证伪命题</b>：
 * <ol>
 *   <li><b>静态扫描</b>：主源码中<b>没有任何</b>业务代码直接调用
 *       {@code ToolHandler.execute}；可执行者只有闸门与其委托的 CAP 编排器。</li>
 *   <li><b>架构边界</b>：其余持有 {@code ToolHandler} 的类<b>只读元信息</b>
 *       （工具名 / 风险等级），一个都不能执行。</li>
 *   <li><b>运行时注入</b>：绕过闸门直接调用工具 → 控制面<b>零记录</b>；
 *       经闸门调用 → 控制面记录齐全。
 *       这一条把「绕过会失去什么」变成可观测事实，
 *       而不是靠代码审查相信「大家都不会这么写」。</li>
 * </ol>
 */
class ToolBypassArchitectureTest {

    /** 可执行者白名单：唯一允许调用 {@code ToolHandler.execute} 的文件。 */
    private static final Set<String> EXECUTORS = Set.of(
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/CapExecutionFlow.java");

    /**
     * 只读持有者白名单：允许持有 {@code ToolHandler} 引用，但<b>不得执行</b>。
     *
     * <p>每一项都有正当理由：
     * <ul>
     *   <li>端口定义与工具实现 —— 提供 SPI 与实现；</li>
     *   <li>审批策略（端口 + 实现）—— 只读工具的<b>风险等级</b>以决定是否需要审批；</li>
     *   <li>启动期一致性守卫 —— 只读风险等级，与 yml 镜像比对；</li>
     *   <li>装配配置 —— 声明工具 Bean 与把工具集合注入闸门；</li>
     *   <li>MCP-backed 工具与其解析器（P4）—— <b>提供</b> MCP 工具的执行器，
     *       但<b>不执行</b>：真正的执行只发生在闸门内（它们只被闸门取得并调用）。</li>
     * </ul>
     */
    private static final Set<String> READERS = Set.of(
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/tool/ToolHandler.java",
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/security/ApprovalPolicy.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/approval/RiskBasedApprovalPolicy.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/tool/RecordWeaknessTool.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/tool/GetStudentProfileTool.java",
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/mcp/McpHandlerProvider.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/mcp/McpBackedToolHandler.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/mcp/DbMcpHandlerProvider.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerExecutionConfig.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ToolRiskLevelConsistencyGuard.java");

    /** Spring AI 自动执行路径（ADR-011 明令禁用）。 */
    private static final List<String> FORBIDDEN_AUTO_EXECUTION = List.of(
            "ToolCallingAdvisor", "ToolCallAdvisor", "ToolCallingManager",
            "DefaultToolCallingManager", "MethodToolCallback", "FunctionToolCallback");

    // ── 静态扫描 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ 主源码中只有闸门与 CAP 编排器可调用 ToolHandler.execute")
        void onlyGateMayExecuteTools() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!code.contains("ToolHandler")) {
                    continue;
                }
                if (callsToolExecute(code) && !EXECUTORS.contains(relative(file))) {
                    violations.add(relative(file));
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 业务代码直连 ToolHandler.execute = 绕过闸门（ADR-011 / 07 §3）"
                            + "；直连意味着 Schema 校验、执行时重授权、审批、预算、账本、"
                            + "执行准入、执行意图全部失效")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 只读持有者一个都不能执行")
        void readersMustNotExecute() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String rel = relative(file);
                if (!READERS.contains(rel)) {
                    continue;
                }
                String code = stripCommentsAndStrings(read(file));
                if (callsToolExecute(code)) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 只读持有者（审批策略 / 一致性守卫 / 装配）一旦执行即等于绕过闸门")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 禁止 Spring AI 自动执行路径（ADR-011）")
        void springAiAutoExecutionIsForbidden() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                for (String forbidden : FORBIDDEN_AUTO_EXECUTION) {
                    if (code.contains("import ") && code.contains(forbidden)) {
                        violations.add(relative(file) + " → " + forbidden);
                    }
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ Spring AI 的自动工具执行路径会绕过全部闸门（ADR-011 §3.3）")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 持有 ToolHandler 的主源码文件全部在白名单内（无新增持有者）")
        void toolHandlerHoldersAreKnown() {
            List<String> unknown = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!code.contains("ToolHandler")) {
                    continue;
                }
                String rel = relative(file);
                if (!EXECUTORS.contains(rel) && !READERS.contains(rel)) {
                    unknown.add(rel);
                }
            }

            assertThat(unknown)
                    .as("⚠️ 新增持有 ToolHandler 的类必须显式登记分类："
                            + "要么是执行者（白名单），要么是只读持有者并说明理由")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 扫描规则自身可证伪：注入的绕过代码必须被检出")
        void scannerDetectsInjectedBypass() {
            // ⚠️⚠️ <b>本用例是整个静态扫描的可信度前提</b>：
            //    若检测规则恒返回 false（例如正则写错、剥离逻辑吃掉了全部代码），
            //    静态扫描会「0 通过」且一片绿 —— 那是最危险的假绿：
            //    它看起来在防守，实际上什么都没检查。
            //    故必须用一段真实的绕过代码证明规则<b>确实会红</b>。
            String violating = """
                    package evil;
                    import cn.studykid.multiagent.ports.tool.ToolHandler;
                    class Evil {
                        void go(ToolHandler handler, ToolHandler.ToolExecutionContext ctx,
                                java.util.Map<String, Object> args) {
                            handler.execute(ctx, args);
                        }
                    }
                    """;

            assertThat(callsToolExecute(stripCommentsAndStrings(violating)))
                    .as("⚠️⚠️ 注入的绕过代码必须被检出")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 只读持有者不误报（负样本）")
        void scannerDoesNotFlagCompliantCode() {
            String compliant = """
                    package ok;
                    import cn.studykid.multiagent.ports.tool.ToolHandler;
                    class Ok {
                        cn.studykid.multiagent.core.config.ToolRiskLevel level(ToolHandler handler) {
                            return handler.riskLevel();
                        }
                        void other(RunLoop loop) {
                            loop.execute();
                        }
                    }
                    """;

            assertThat(callsToolExecute(stripCommentsAndStrings(compliant)))
                    .as("⚠️ 只读风险等级与无关对象的 execute 调用不得被误判为绕过")
                    .isFalse();
        }
    }

    // ── 运行时注入 ────────────────────────────────────────────────

    @Nested
    @DisplayName("运行时注入")
    class RuntimeInjection {

        @Test
        @DisplayName("⚠️⚠️ 绕过闸门直接调用工具 → 控制面零记录")
        void bypassingTheGateProducesNoControlPlaneRecords() {
            CapExecutionFixture exec = new CapExecutionFixture();
            CapFixture cap = fixture();

            // ── 绕过：直接调用工具，绕过闸门/编排器 ─────────────────
            ToolHandler tool = exec.tool("get_student_profile");
            tool.execute(new ToolHandler.ToolExecutionContext(
                    "t-cap-001", "run-cap-001", "worker", "key-1", 1),
                    Map.of("studentId", "stu_0123456789abcdef"));

            assertThat(exec.toolExecutions)
                    .as("工具确实被调用了（这就是绕过）")
                    .isEqualTo(1);
            assertThat(exec.ledger)
                    .as("⚠️⚠️ 绕过闸门 → 账本无任何记录（无 PRE/POST）："
                            + "没有可对账的逻辑操作，UNKNOWN 将无处安放")
                    .isEmpty();
            assertThat(exec.reservations)
                    .as("⚠️⚠️ 绕过闸门 → 预算未预留：成本失控且无法释放")
                    .isEmpty();
            assertThat(exec.intents)
                    .as("⚠️⚠️ 绕过闸门 → 无执行意图：崩溃后无从判断副作用是否已发生")
                    .isEmpty();
            assertThat(exec.resultEvents)
                    .as("⚠️⚠️ 绕过闸门 → 无结果事件：确定结果不可恢复")
                    .isEmpty();
            assertThat(cap).isNotNull();
        }

        @Test
        @DisplayName("✅ 经闸门调用 → 控制面记录齐全")
        void gateMediatedCallProducesFullControlPlane() {
            CapExecutionFixture exec = new CapExecutionFixture();
            fixture();

            CapExecutionFlow flow = flow(exec);
            CapToolDecision decision = flow.invoke("t-cap-001", task(), call(),
                    exec.tool("get_student_profile"));

            assertThat(decision.decision().status())
                    .isEqualTo(cn.studykid.multiagent.core.cap.AuthorizationDecision.Status.SUCCEEDED);
            assertThat(exec.ledger)
                    .as("✅ 经闸门 → 账本有记录")
                    .hasSize(1);
            assertThat(exec.reservations)
                    .as("✅ 经闸门 → 预算有预留")
                    .hasSize(1);
            assertThat(exec.intents)
                    .as("✅ 经闸门 → 执行意图已持久化")
                    .hasSize(1);
            assertThat(exec.resultEvents)
                    .as("✅ 经闸门 → 确定结果已落盘")
                    .hasSize(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 无授权上下文即拒绝：工具零执行")
        void unauthorizedToolIsNeverExecuted() {
            CapExecutionFixture exec = new CapExecutionFixture();
            fixture();

            CapExecutionFlow flow = flow(exec);
            // 未声明的工具：不在闭包内
            ModelResponse.ToolCallRequest foreign = new ModelResponse.ToolCallRequest(
                    "send_email", Map.of("to", "someone@example.com"), "call-2");

            CapToolDecision decision = flow.invoke("t-cap-001", task(), foreign,
                    exec.tool("send_email"));

            assertThat(decision.decision().reasonCode())
                    .isEqualTo(ReasonCode.TOOL_NOT_DECLARED);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 未授权的工具绝不能被执行")
                    .isZero();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    private static CapFixture fixture() {
        return new CapFixture()
                .withSkill("profile_skill", 1, CapabilityRiskLevel.READ,
                        List.of("get_student_profile"))
                .withTool("get_student_profile", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent("t-cap-001", "homework_tutor", 1, List.of("profile_skill"), "{}")
                .withAuthzVersion("t-cap-001", CapExecutionFlow.DEFAULT_PRINCIPAL_ID, 1L)
                .withRolePermission("WORKER", "tool:invoke");
    }

    private static CapExecutionFlow flow(CapExecutionFixture exec) {
        CapFixture cap = fixture();
        PermissionResolver resolver = new PermissionResolver(cap.agentDefs(), cap.skillDefs(),
                cap.toolDefs(), cap.serverDefs(), cap.publishedVersions(), cap.manifest());
        cap.withSnapshot(new cn.studykid.multiagent.core.cap.CapabilitySnapshot(
                "run-cap-001", "t-cap-001", "homework_tutor",
                ClosureFingerprint.canonicalJson(resolver.resolveCurrent("t-cap-001",
                        "homework_tutor")),
                1, 0L,
                ClosureFingerprint.of(resolver.resolveCurrent("t-cap-001", "homework_tutor")),
                1L, java.time.Instant.EPOCH));

        InvocationValidator validator = new InvocationValidator(cap.authzVersions(),
                cap.rolePermissions(), resolver, cap.snapshots(), cap.manifest());
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(cap.authzVersions(),
                resolver, cap.snapshots());
        IdempotencyGuard idempotency = new IdempotencyGuard(exec.ledgerPort(), exec.resultEventPort());
        // ⚠️ P3：审批走 CAP 状态机，不再注入既有 approval 表的一次性消费端口。
        ApprovalStateMachine stateMachine = new ApprovalStateMachine(exec.capApprovalPort(),
                new ApprovalBindingService());
        CompensationFramework compensation = new CompensationFramework(exec.capApprovalPort(),
                exec.budgetPort(), exec.compensationTaskPort());
        return new CapExecutionFlow(validator, resolver, admission, idempotency,
                cap.authzVersions(), cap.rolePermissions(), cap.snapshots(),
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy.ApprovalDecision
                        .notRequired(),
                stateMachine, compensation, cap.toolDefs(),
                exec.budgetPort(), exec.ledgerPort(), exec.intentPort(),
                exec.resultEventPort(), java.math.BigDecimal.ONE,
                new CapEnforcementSwitch(true));
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask("run-cap-001", 1L, java.time.Instant.now().plusSeconds(60), 1);
    }

    private static ModelResponse.ToolCallRequest call() {
        return new ModelResponse.ToolCallRequest("get_student_profile",
                Map.of("studentId", "stu_0123456789abcdef"), "call-1");
    }

    /**
     * 判定代码中是否存在「对 ToolHandler 类型变量的 execute 调用」。
     *
     * <p>实现：先收集本文件中<b>声明为 ToolHandler 类型</b>的变量名，
     * 再检查这些名字是否被 {@code .execute(...)} 调用。
     * 只看「文件里出现了 ToolHandler」会把
     * {@code loop.execute(...)} 这类无关调用误判为绕过。
     */
    private static boolean callsToolExecute(String code) {
        Set<String> handlerVars = new LinkedHashSet<>();
        Matcher decl = Pattern.compile(
                        "\\bToolHandler\\s*(?:\\.\\s*@\\w+\\s*)?([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(code);
        while (decl.find()) {
            handlerVars.add(decl.group(1));
        }
        for (String name : handlerVars) {
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\.\\s*execute\\s*\\(")
                    .matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉注释与字符串字面量，只留<b>可执行代码</b>。
     *
     * <p>⚠️ <b>为什么必须连字符串一起去掉</b>：
     * 本仓库有把 {@code "ToolHandler.riskLevel()"} 写进错误提示的字符串，
     * 只去注释会把「只是提到这个词」误判成「持有 ToolHandler」。
     * 反过来，若字符串先被去掉，URL 里的 {@code //} 也不会被误当成行注释。
     *
     * <p>实现是逐字符的状态机（而非正则），
     * 因为正则无法可靠区分「字符串里的引号」与「代码里的引号」。
     */
    private static String stripCommentsAndStrings(String src) {
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

    private static List<Path> mainSourceFiles() {
        List<Path> files = new ArrayList<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    tree.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描主源码失败: " + root, e);
        }
        return files;
    }

    /** 定位 platform 源码根目录（从当前工作目录向上找含 {@code agent-core} 的目录）。 */
    private static Path platformRoot() {
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

    private static String relative(Path file) {
        return platformRoot().relativize(file.toAbsolutePath()).toString()
                .replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }
}

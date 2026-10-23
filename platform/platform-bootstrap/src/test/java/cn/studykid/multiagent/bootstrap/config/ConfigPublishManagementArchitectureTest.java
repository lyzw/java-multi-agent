package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置变更管理端点的架构守卫（P6R4）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：本轮新增了一条<b>租户可达</b>的 HTTP 写路径
 * （{@code POST /api/cap/config/publishes/*}）。
 * 一旦这条路径与<b>平台运维通道</b>（BYPASSRLS 凭据）搭上，
 * 任何租户管理员就能改全平台生效配置 —— 而这种误用<b>在代码审查时极难一眼看出</b>。
 *
 * <p>⚠️ <b>本类锁的是「可达性」，不是「实现细节」</b>：
 * <ol>
 *   <li><b>租户工作流不得引用运维通道</b>：任何 {@code PlatformOps*} 引用都是越权；</li>
 *   <li><b>装配点不得把运维发布服务注入租户工作流</b>；</li>
 *   <li><b>租户工作流不得绕过发布门禁</b>（门禁在 {@code ConfigPublishService} 内部）；</li>
 *   <li><b>管理端点不得绕过工作流</b>（端点只做鉴权与编排）；</li>
 *   <li><b>发布权限只授予租户管理员</b>：其他角色一律不得持有。</li>
 * </ol>
 */
class ConfigPublishManagementArchitectureTest {

    /** 租户侧工作流（应用层）。 */
    private static final String WORKFLOW =
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/ConfigPublishWorkflowService.java";
    /** 租户侧管理端点（接口层）。 */
    private static final String CONTROLLER =
            "agent-api/src/main/java/cn/studykid/multiagent/api/config/CapConfigPublishController.java";
    /** 装配点。 */
    private static final String WIRING =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java";
    /** 角色 → 动作映射表。 */
    private static final String ROLE_MATRIX =
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/RoleBasedAuthorizationService.java";

    /** 运维通道的类型名（出现在租户侧即越权）。 */
    private static final List<String> OPS_CHANNEL_TYPES = List.of(
            "PlatformOpsPublishService",
            "JdbcPlatformOpsConfigPublishTransaction",
            "PlatformOpsDataSourceProvider",
            "platformOpsPublishService",
            "platformOpsPublishTransaction");

    @Test
    @DisplayName("⚠️⚠️ 租户工作流不得引用运维通道：BYPASSRLS 不得有第二条可达路径")
    void tenantWorkflowNeverReferencesOpsChannel() {
        String code = stripCommentsAndStrings(read(root().resolve(WORKFLOW)));
        List<String> hits = OPS_CHANNEL_TYPES.stream().filter(code::contains).toList();
        assertThat(hits)
                .as("⚠️ 租户可达的配置工作流引用了平台运维通道："
                        + "任一租户管理员即可经这条路径改全平台生效配置")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ 装配点不得把运维发布服务注入租户工作流")
    void wiringDoesNotInjectOpsServiceIntoTenantWorkflow() {
        String code = stripCommentsAndStrings(read(root().resolve(WIRING)));
        String bean = extractBeanBody(code, "configPublishWorkflowService");
        assertThat(bean)
                .as("⚠️ 租户工作流的装配点引用了运维发布服务："
                        + "租户通道一旦拿到 BYPASSRLS 连接，平台级隔离即失效")
                .doesNotContain("platformOpsPublishService")
                .doesNotContain("platformOpsPublishTransaction")
                .doesNotContain("PlatformOpsPublishService");
        // ⚠️ 反向确认：必须**确实**接的是租户通道，否则这条断言会空转
        assertThat(bean)
                .as("租户工作流必须接应用角色的发布服务（ConfigPublishService 类型）")
                .contains("ConfigPublishService");
    }

    @Test
    @DisplayName("⚠️ 租户工作流不得绕过发布门禁：门禁在 ConfigPublishService 内部")
    void tenantWorkflowNeverBypassesPublishGate() {
        String code = stripCommentsAndStrings(read(root().resolve(WORKFLOW)));
        assertThat(code)
                .as("⚠️ 工作流直接构造了发布事务："
                        + "配置期门禁（作用域 / 双人复核 / 风险等级 / 引用自洽）在 ConfigPublishService 内部，"
                        + "绕过它就等于绕过全部发布期校验")
                .doesNotContain("JdbcConfigPublishTransaction")
                .doesNotContain("ConfigPublishTransaction");
        // ⚠️ 写库只能经 ConfigPublishService —— 它内部才有门禁 + CAS + 审计
        assertThat(code)
                .as("租户工作流必须经 ConfigPublishService 发布（门禁 + CAS + 审计都在里面）")
                .contains("ConfigPublishService");
    }

    @Test
    @DisplayName("⚠️ 管理端点不得绕过工作流：端点只做鉴权与编排")
    void controllerNeverBypassesWorkflow() {
        String code = stripCommentsAndStrings(read(root().resolve(CONTROLLER)));
        assertThat(code)
                .as("⚠️ 管理端点直接调用了发布服务："
                        + "三步流程（申请/审批/执行）与作用域闸门都在工作流里，端点绕过它就没有复核")
                .doesNotContain("ConfigPublishService")
                .doesNotContain("ConfigLifecycleService")
                .doesNotContain("CapAgentDefPort");
        // ⚠️ ConfigPublishRequestPort 允许出现在端点里 —— 它只提供 Decision 枚举与异常类型，
        //    端点不碰它的仓储方法。真正要防的是「端点自己去落库」。
        assertThat(code)
                .as("管理端点必须经 ConfigPublishWorkflowService 编排")
                .contains("ConfigPublishWorkflowService");
    }

    @Test
    @DisplayName("⚠️ 发布权限只授予租户管理员：其他角色一律不得持有")
    void publishPermissionsAreAdminOnly() {
        String code = stripCommentsAndStrings(read(root().resolve(ROLE_MATRIX)));
        for (String role : List.of("AGENT_AUTHOR", "APPROVER", "VIEWER", "WORKER")) {
            String block = extractRoleBlock(code, role);
            assertThat(block)
                    .as("⚠️ 角色 %s 持有 CONFIG_PUBLISH / CONFIG_ROLLBACK："
                            + "发布是租户管理员的专属动作，"
                            + "作者 / 审批人 / 只读者 / Worker 都不该能改生效配置", role)
                    .doesNotContain("CONFIG_PUBLISH")
                    .doesNotContain("CONFIG_ROLLBACK");
        }
        String adminBlock = extractRoleBlock(code, "TENANT_ADMIN");
        assertThat(adminBlock)
                .as("租户管理员必须持有发布与回滚权限")
                .contains("CONFIG_PUBLISH")
                .contains("CONFIG_ROLLBACK");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static String extractBeanBody(String code, String beanName) {
        int start = code.indexOf(beanName);
        if (start < 0) {
            throw new IllegalStateException("未找到 Bean: " + beanName);
        }
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    // ⚠️ 含<b>方法签名</b>：注入点写在参数列表里，只看方法体会漏掉它
                    return code.substring(start, i + 1);
                }
            }
        }
        throw new IllegalStateException("Bean 体未闭合: " + beanName);
    }

    private static String extractRoleBlock(String code, String role) {
        String marker = "Roles." + role + ",";
        int start = code.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("未找到角色: " + role);
        }
        int open = code.indexOf("Set.of(", start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new IllegalStateException("角色动作集未闭合: " + role);
    }

    private static Path root() {
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

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 剥离注释与字符串字面量。
     *
     * <p>⚠️ <b>必须剥离</b>：否则「在注释里提到运维通道」会被算成引用，
     * 白名单会迅速失效并让本测试变成噪音。
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
}

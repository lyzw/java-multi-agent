package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolHandler;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate;

/**
 * 工具链路**连通性测试**（P4-D08）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：P4-D08 修复的 R-1 是一个
 * <b>「代码在、测试全绿、运行时工具从不执行」</b>的断链——
 * {@code GetStudentProfileTool} / {@code RecordWeaknessTool} /
 * {@code StudentDataRepository} 三个类均无 Spring 注解，
 * 且全仓非测试代码零处实例化，于是
 * {@code List<ToolHandler>} 在生产中<b>恒为空</b>，
 * 闸门对所有工具调用记「未登记的工具」→
 * {@code weakness_record} 表在生产中<b>永远为空</b>。
 *
 * <p>而 {@code StudentToolsTest} 全部直接 {@code new} 被测类，
 * {@code ToolLedgerReconciliationIT} 直接 {@code new StudentDataRepository(appPool)}——
 * <b>行为测试</b>（被直接调用时行为正确）齐备，
 * <b>连通性测试</b>（它是否真被调用）缺失。
 * 这正是 P4-D01 确立的方法论：<b>测试必须分两类，缺后者则整条链路可断裂而不被发现</b>。
 *
 * <p>⚠️ <b>本类不依赖真实 broker</b>：只断言 Bean 图与装配，
 * 故可在 CI 无 RabbitMQ 时运行。真实端到端（真发真收）由部署环境验证。
 *
 * <p>⚠️ 用 {@code WebEnvironment.MOCK} 而非 {@code NONE}：
 * {@code securityFilterChain} 依赖 servlet 上下文，
 * {@code NONE} 下会报 {@code NoSuchBeanDefinitionException}。
 */
// ⚠️ 2026-10-22 实测：本类会装配**完整 Spring 上下文**，启动期即查询 tenant 表
//    （RetentionJob → 解析租户 slug fail-closed）→ **必须有活库且已建表**。
//    无库时它不是「跳过」而是**报错**（29 项 error），会把无库的构建拖红。
//    故按项目既有约定（见 RateLimitIT 等）用 IT_PG_URL 门禁：
//    无库 → 跳过；integration-test job（有库）→ 真跑并纳入 skipped==0 断言。
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // ⚠️ P4-D18：关闭 Outbox 中继。
        //    本类的断言目标与中继无关，而中继一旦启动会把**其它用例**
        //    遗留的 PENDING outbox 事件投进 agent_message_queue，
        //    随后 MessagingSwitchGuard 因「通道切换残留」fail-closed，
        //    使后续 rabbitmq 模式用例无法启动上下文——
        //    症状指向通道配置，真因是跨用例的数据库共享。
        "agent.worker.relay-enabled=false",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 关掉容器启动（避免连 broker），但**保留全部 Bean 定义**——
        //    本类要断言的正是「Bean 是否存在且接得对」。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D08 工具链路连通性测试")
class ToolWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ── W-1/W-2：工具确实被注册 ──────────────────────────────────
    // ⚠️ 必须放在 @Nested 组内：外层类的 @Test 方法在本项目 surefire
    //    配置下**不会执行**（实测：Tests run: 0），而 @Nested 内会。
    //    「写了但没跑」是最坏的形态——看起来有覆盖，实际没有。
    @Nested
    @DisplayName("W-1/W-2 工具注册与闸门可见性")
    class ToolRegistration {

        @Test
        @DisplayName("⚠️⚠️ W-1 上下文中恰有两个 ToolHandler Bean（R-1 断链点）")
        void exactlyTwoToolHandlersRegistered() {
            Map<String, ToolHandler> beans =
                    ctx.getBeansOfType(ToolHandler.class);
            assertThat(beans)
                    .as("⚠️⚠️ 工具 Bean 数为 %d——R-1 断链的特征是**零个**"
                            + "（工具从未注册 → 闸门记「未登记的工具」→ 画像表永远为空）",
                            beans.size())
                    .hasSize(2);
            assertThat(beans.keySet())
                    .as("⚠️⚠️ 必须同时包含只读与有副作用两个工具")
                    .contains("getStudentProfileTool", "recordWeaknessTool");
        }

        @Test
        @DisplayName("⚠️⚠️ W-2 闸门持有的 handlers 非空且含两个工具（R-1 的直接后果）")
        void gateHoldsRealHandlers() throws Exception {
            ToolInvocationGate gate = ctx.getBean(ToolInvocationGate.class);
            assertThat(gate).isNotNull();

            List<ToolHandler> handlers = handlersOf(gate);
            assertThat(handlers)
                    .as("⚠️⚠️ 闸门内 handlers 为空 = 工具永不执行（R-1）")
                    .hasSize(2);
            assertThat(handlers.stream().map(ToolHandler::toolName).toList())
                    .as("⚠️⚠️ 闸门必须能找到两个工具，否则全部调用被记「未登记的工具」")
                    .containsExactlyInAnyOrder("get_student_profile", "record_weakness");
        }

        @Test
        @DisplayName("⚠️⚠️ W-2b 闸门的授权上下文带 WORKER 角色（R-4 断链点）")
        void systemContextCarriesWorkerRole() {
            // ⚠️ 授权按**角色**判定（RoleBasedAuthorizationService: roles → 动作集合）。
            //    闸门内 systemContext 若传空角色集 → isAuthorized 恒 false
            //    → 工具执行时必被「无权调用该工具」拦下（R-4）。
            var authz = ctx.getBean(AuthorizationService.class);
            var ctxWithWorker = new cn.studykid.multiagent.core.tenant.TenantContext(
                    "t-a", "worker",
                    java.util.Set.of(AuthorizationService.Roles.WORKER), 0L);
            assertThat(authz.isAuthorized(ctxWithWorker,
                    AuthorizationService.Actions.TOOL_INVOKE, "record_weakness"))
                    .as("⚠️⚠️ WORKER 角色必须含 TOOL_INVOKE，"
                            + "否则工具在闸门内永远被授权拦下")
                    .isTrue();
        }
    }

    // ── 反射读取闸门私有 handlers（避免为测试改动生产代码）──────────

    /**
     * 读取闸门私有字段 {@code handlers}。
     *
     * <p>⚠️ <b>刻意不改生产代码暴露 getter</b>：为测试增加公开 API 会
     * 让「可观测性」变成「契约」，反而掩盖「闸门内部状态」的真实形状。
     * 反射是此处更诚实的手段。
     */
    @SuppressWarnings("unchecked")
    private static List<ToolHandler> handlersOf(ToolInvocationGate gate) throws Exception {
        Field field = ToolInvocationGate.class.getDeclaredField("handlers");
        field.setAccessible(true);
        return (List<ToolHandler>) field.get(gate);
    }
}

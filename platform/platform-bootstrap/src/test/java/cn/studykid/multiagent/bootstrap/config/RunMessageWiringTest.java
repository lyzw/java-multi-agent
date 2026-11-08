package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.api.platform.PlatformTranscriptController;
import cn.studykid.multiagent.api.run.TranscriptController;
import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.infrastructure.persistence.JdbcRunMessageRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcTraceReplayRepository;
import cn.studykid.multiagent.ports.persistence.RunMessagePort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;

/**
 * 对话回放底座（P1 · G1）的<b>装配接线</b>守卫。
 *
 * <p><b>⚠️ 本类补的是 {@code WorkerExecutionConfig} 注释里承诺、
 * 却一直没有落地的那一层</b>：{@code runMessagePort} 是
 * {@code required=false} 的可选注入 —— 一旦装配漏了，
 * Spring 上下文<b>照常启动</b>、执行循环照常跑、Run 照常 SUCCEEDED，
 * 只是 {@code run_message} <b>永远没有一行</b>。
 * 而「这次辅导没留下对话」与「系统从来没记过对话」在外部完全无法区分：
 * 回放端点恒 404，所有仓储测试与端点测试全绿，<b>没有任何报错</b>。
 * 这是本项目已反复出现的「机制正确但从未接线」（P4-D08 R-1）形状。
 *
 * <p><b>为什么闭环 IT 不能替代本类</b>：
 * {@code TraceReplayClosedLoopIT} 验的是「真跑一次 Run 会不会自动落消息」，
 * 属于闭环级；本类验的是<b>装配图本身</b>——
 * 端口是不是 JDBC 实现（而非替身）、有没有真的传进执行循环、
 * 传进去的是不是容器里那一个实例。
 * 前者失败时，后者能立刻指出是「装配断链」还是「埋点逻辑错了」。
 *
 * <p>⚠️ 用 {@code WebEnvironment.MOCK}（同 {@code MemoryWiringTest}）：
 * {@code securityFilterChain} 依赖 servlet 上下文；且装配完整上下文需要连库，
 * 故用 {@code IT_PG_URL} 门禁 —— 无库跳过，有库真跑。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.messaging.mode=database",
        // ⚠️ 关掉后台轮询：本类只看装配图，不消费共享库的 outbox_event。
        "agent.worker.enabled=false",
        "agent.worker.relay-enabled=false",
        "agent.worker.tenant-slug=t-a",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P1 对话回放底座 · 装配接线守卫")
class RunMessageWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ⚠️ 必须放在 @Nested 组内：本项目实测外层类的 @Test 在 surefire 下
    //    不会执行（Tests run: 0）——"写了但没跑"看起来有覆盖，实际没有。
    @Nested
    @DisplayName("W-1 ⚠️ 端口必须是 JDBC 实现，回放端点必须已注册")
    class BeanPresence {

        @Test
        @DisplayName("W-1a ⚠️⚠️ RunMessagePort 必须是 JDBC 实现（替身 = 对话永远不落库）")
        void writePortIsJdbc() {
            assertThat(ctx.getBean(RunMessagePort.class))
                    .as("⚠️⚠️ 端口若被换成空实现，执行循环照常 SUCCEEDED，"
                            + "但 run_message 永远为空、回放恒 404，且无任何告警")
                    .isInstanceOf(JdbcRunMessageRepository.class);
        }

        @Test
        @DisplayName("W-1b ⚠️ TraceReplayPort 必须是 JDBC 实现（替身 = 回放恒空）")
        void readPortIsJdbc() {
            assertThat(ctx.getBean(TraceReplayPort.class))
                    .as("⚠️ 读端口若为替身，端点返回 200 但 messages 恒为空列表，"
                            + "极易被误判成「这次辅导确实没说什么」")
                    .isInstanceOf(JdbcTraceReplayRepository.class);
        }

        @Test
        @DisplayName("W-1c 回放服务与两端点都已装配（缺一即端点启动失败）")
        void replayServiceAndControllersAreRegistered() {
            assertThat(ctx.getBean(TraceReplayService.class)).isNotNull();
            assertThat(ctx.getBean(TranscriptController.class)).isNotNull();
            assertThat(ctx.getBean(PlatformTranscriptController.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("W-2 ⚠️⚠️ 端口真的被传进生产 Bean（Bean 存在 ≠ 接线完成）")
    class LoopWiring {

        @Test
        @DisplayName("W-2a ⚠️⚠️ RunExecutionLoop.messagePort 必须非 null")
        void loopHasMessagePort() throws Exception {
            Object injected = field(ctx.getBean(RunExecutionLoop.class), "messagePort");
            assertThat(injected)
                    .as("⚠️⚠️ 端口存在但没注入执行循环 = 与不存在完全等价；"
                            + "症状是「表和仓储都在、单测全绿、回放永远为空」")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-2b ⚠️ 注入的是容器里同一个实例（不是两套并行视图）")
        void loopUsesContainerInstance() throws Exception {
            assertThat(field(ctx.getBean(RunExecutionLoop.class), "messagePort"))
                    .as("执行循环另起一套写端口 = 两套连接/租户语义，行为不可预测")
                    .isSameAs(ctx.getBean(RunMessagePort.class));
        }

        @Test
        @DisplayName("W-2c ⚠️ TraceReplayService 接的是容器里的读端口")
        void replayServiceUsesContainerPort() throws Exception {
            assertThat(field(ctx.getBean(TraceReplayService.class), "replay"))
                    .as("回放服务若拿了另一个读端口，端点与组装逻辑会看到不同事实")
                    .isSameAs(ctx.getBean(TraceReplayPort.class));
        }
    }

    /**
     * 读私有字段。
     *
     * <p>⚠️ 用反射而不是给生产类加 getter：
     * 加 getter 等于为测试打开生产 API 面，还会诱导生产代码去读它。
     * 本类要验证的恰恰是「内部状态」，反射更诚实。
     */
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}

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

import cn.studykid.multiagent.memory.application.MemoryConsolidator;
import cn.studykid.multiagent.memory.application.MemoryRecallService;
import cn.studykid.multiagent.memory.port.MemoryPort;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;

/**
 * 跨 Run 记忆**连通性测试**（P1）。
 *
 * <p>⚠️⚠️ <b>本类存在的唯一理由：防第四次断链</b>。
 * <ul>
 *   <li>D01：{@code RunDispatchConsumer} 领到租约后不执行；</li>
 *   <li>D03：{@code RunDispatchConsumer} 根本没人调用；</li>
 *   <li>P4-D04：清理服务全在、单测全绿，但数据从不被删；</li>
 *   <li>P4-D08 R-1：三个类零生产实例化 → {@code weakness_record} 永远为空。</li>
 * </ul>
 * 记忆的失效形态是<b>最隐蔽的一种</b>：全部单元测试用内存替身，
 * 什么都能验证，唯独验证不了"生产容器里到底有没有这个 Bean"。
 * 而缺少它的症状是——模型<b>看起来</b>在正常工作，只是永远不知道
 * 这个学生以前错过什么；{@code student_memory} 永远是空表。
 * <b>无异常、无告警、健康检查全绿。</b>
 *
 * <p>⚠️ <b>因此本类断言两件事</b>：
 * <ol>
 *   <li>Bean 存在（{@link MemoryPort} / {@link MemoryRecallService} / {@link MemoryConsolidator}）；</li>
 *   <li><b>它们真的被传进了 {@code RunExecutionLoop}</b>。
 *       只断言 ① 是不够的 —— 一个存在的 Bean 若没被注入执行循环，
 *       效果与不存在完全相同（这正是 P4-D08 R-1 的形状）。</li>
 * </ol>
 *
 * <p>⚠️ 用 {@code WebEnvironment.MOCK}（同 {@code RetentionWiringTest}）：
 * {@code securityFilterChain} 依赖 servlet 上下文，{@code NONE} 下会失败。
 * 且本类装配<b>完整 Spring 上下文</b>，启动期即查库，故用 {@code IT_PG_URL} 门禁：
 * 无库跳过，有库真跑。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // ⚠️ 关闭 Outbox 中继（同 RetentionWiringTest 的理由）：
        //    中继会把其它用例遗留的 PENDING 事件投进队列，
        //    导致通道切换守卫 fail-closed，症状指向通道配置、真因是跨用例共享库。
        "agent.worker.relay-enabled=false",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ── 记忆：**显式打开**。本类要验证的正是"打开之后真的接了线"，
        //    若沿用 yml 默认值，某天默认值被改成 false 时本类会静默变绿 ——
        //    那正是它要防的那件事。
        "agent.memory.enabled=true",
        "agent.memory.recall-max-items=5",
        "agent.memory.recall-max-tokens=800",
        "agent.memory.recall-min-strength=0.40",
        // ── 保留期：配齐（RetentionConfig fail-closed，缺一即拒绝启动）──
        "agent.retention.enabled=true",
        "agent.retention.profile-months=12",
        "agent.retention.memory-months=12",
        "agent.retention.prompt-days=90",
        "agent.retention.audit-days=180",
        "agent.retention.trace-days=30",
        "agent.retention.withdrawal-grace-days=30",
        "agent.retention.batch-size=1000",
        "agent.retention.interval-ms=3600000",
        "agent.retention.actor=retention-job",
        "agent.worker.tenant-slug=t-a"
})
@DisplayName("P1 记忆接通性测试")
class MemoryWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ⚠️ 必须放在 @Nested 组内：本项目实测外层类的 @Test 在 surefire 下
    //    不会执行（Tests run: 0）——"写了但没跑"看起来有覆盖，实际没有。
    @Nested
    @DisplayName("W-1⚠️ Bean 存在性（防「机制正确但从未接线」）")
    class BeanPresence {

        @Test
        @DisplayName("W-1a⚠️⚠️ MemoryPort Bean 必须存在——否则记忆永远读不到也写不进")
        void memoryPortExists() {
            assertThat(ctx.getBean(MemoryPort.class))
                    .as("⚠️⚠️ 没有 MemoryPort，student_memory 将永远为空且无人察觉；"
                            + "缺 Bean 的表现是「代码都在、单测全绿、记忆从不生效」")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-1b MemoryRecallService 已装配（enable=true 时必须存在）")
        void recallServiceExists() {
            assertThat(ctx.getBean(MemoryRecallService.class))
                    .as("⚠️ agent.memory.enabled=true 时必须真的产出 Bean —— "
                            + "若这里拿到 null，说明开关解析把 true 当成了 false")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-1c MemoryConsolidator 已装配")
        void consolidatorExists() {
            assertThat(ctx.getBean(MemoryConsolidator.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("W-2⚠️⚠️ 记忆真的被注入执行循环（Bean 存在 ≠ 接线完成）")
    class LoopWiring {

        @Test
        @DisplayName("W-2a⚠️⚠️ RunExecutionLoop 的 memoryRecallService 必须非 null")
        void loopHasRecallService() throws Exception {
            RunExecutionLoop loop = ctx.getBean(RunExecutionLoop.class);
            Object injected = field(loop, "memoryRecallService");
            assertThat(injected)
                    .as("⚠️⚠️ Bean 存在但没注入执行循环 = 与不存在等价"
                            + "（P4-D08 R-1 的形状：三个类零生产实例化，全部测试全绿）")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-2b⚠️⚠️ RunExecutionLoop 的 memoryConsolidator 必须非 null")
        void loopHasConsolidator() throws Exception {
            RunExecutionLoop loop = ctx.getBean(RunExecutionLoop.class);
            Object injected = field(loop, "memoryConsolidator");
            assertThat(injected)
                    .as("⚠️⚠️ 没有整合器，student_memory 永远为空；"
                            + "而「永远为空」与「学生从没错过」在外部无法区分")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-2c 注入的实例与容器里的是**同一个**（不是两套并行视图）")
        void sameInstances() throws Exception {
            RunExecutionLoop loop = ctx.getBean(RunExecutionLoop.class);
            assertThat(field(loop, "memoryRecallService"))
                    .as("执行循环用两套记忆服务 = 两条独立视图，行为不可预测")
                    .isSameAs(ctx.getBean(MemoryRecallService.class));
            assertThat(field(loop, "memoryConsolidator"))
                    .isSameAs(ctx.getBean(MemoryConsolidator.class));
        }
    }

    /**
     * 读私有字段。
     *
     * <p>⚠️ 用反射而不是给 {@code RunExecutionLoop} 加 getter：
     * 加 getter 等于为测试打开生产 API 面，且会诱导生产代码去读它。
     * 这里要验证的恰恰是"内部状态"，反射更诚实。
     */
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}

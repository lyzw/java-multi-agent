package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;
import cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner;

/**
 * M7-b 生产 Bean 图连通性（G1 A.5 守卫升级的运行时侧证据）。
 *
 * <p>⚠️ <b>为什么必须有这一类</b>：{@code ContextRequestAssemblyAdapterTest} /
 * {@code RunExecutionLoopTest} 都是直接 {@code new} 被测对象——它们证明「接上以后行为正确」，
 * 却<b>不能证明生产真的接上了</b>。P4-D01 的教训正是：所有单测全绿，
 * 而 {@code OutboxRelay} 从未被装配成 Bean，消息永远不发。
 *
 * <p>本类断言四件事，缺一即静默断链：
 * <ol>
 *   <li>生产 Spring 上下文里 {@link RequestAssemblyPort} 存在且持有真实持久化端口；</li>
 *   <li>L1 {@link RunExecutionLoop} 与 L4 {@link WorkerChildAgentRunner} 都真的拿到了它；</li>
 *   <li>组合根里<b>只有一个</b> {@link ContextAssembler}，adapter 持有的就是该 Bean
 *       （⛔ 不是自己 {@code new} 的第二套内核 —— 否则 yml 阈值改了却不生效）；</li>
 *   <li>水位阈值来自 yml/配置，不是 {@link Thresholds#defaults()} 旁路。</li>
 * </ol>
 *
 * <p>⚠️ 与 {@code WorkerLoopWiringTest} 同口径：完整上下文启动期会查 tenant 表，
 * 故以 {@code IT_PG_URL} 门禁；无库时跳过，有库时纳入 {@code skipped=0} 断言。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "agent.worker.relay-enabled=false",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 与 defaults() 的 0.60 刻意不同：证明阈值确实从配置读取。
        "agent.context.tier-snip-ratio=0.55"
})
@DisplayName("M7-b 统一请求装配生产 Bean 图连通性")
class ContextRequestAssemblyWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ⚠️ 外层 @Test 在本项目 surefire 配置下不会执行（实测 Tests run: 0），
    //    必须放在 @Nested 组内，否则「写了但没跑」。
    @Nested
    @DisplayName("生产 Bean 图")
    class ProductionWiring {

        @Test
        @DisplayName("✅ RequestAssemblyPort 已装配，且由真实持久化端口支撑")
        void requestAssemblyPortIsWiredWithRealPorts() {
            RequestAssemblyPort port = ctx.getBean(RequestAssemblyPort.class);
            assertThat(port).as("⛔ 统一请求装配入口没进生产 Bean 图").isNotNull();
            assertThat(port).isInstanceOf(ContextRequestAssemblyAdapter.class);
            assertThat(ctx.getBean(CapAgentDefPort.class))
                    .as("⛔ 冻结策略读不到：开启态会 fail-closed 拒绝全部 Run").isNotNull();
            assertThat(ctx.getBean(ModelCatalogPort.class))
                    .as("⛔ 模型窗口读不到：能力按 unknown 处理，预算被无谓上调").isNotNull();
        }

        @Test
        @DisplayName("✅ 默认全局开关仍为关闭（G1 影子期承诺在接线后保持）")
        void globalSwitchDefaultsToOff() {
            assertThat(ctx.getBean(GlobalContextPolicy.class).enabled())
                    .as("⛔ 生产默认开启了压缩：G1 关闭等价承诺被破坏").isFalse();
        }

        @Test
        @DisplayName("✅ L1/L4 两条 Worker 链路都真的持有统一装配端口")
        void bothWorkerPathsHoldThePort() {
            RequestAssemblyPort expected = ctx.getBean(RequestAssemblyPort.class);
            assertThat(readField(ctx.getBean(RunExecutionLoop.class), "requestAssemblyPort"))
                    .as("⛔ L1 仍走 legacy trim：接线只发生在测试里")
                    .isSameAs(expected);
            assertThat(readField(ctx.getBean(WorkerChildAgentRunner.class),
                    "requestAssemblyPort"))
                    .as("⛔ L4 仍走 legacy trim：父子两套语义")
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("✅ 组合根只有一个 ContextAssembler，adapter 不另起第二套内核")
        void adapterSharesTheSingleAssemblerBean() {
            ContextAssembler assembler = ctx.getBean(ContextAssembler.class);
            assertThat(ctx.getBeansOfType(ContextAssembler.class))
                    .as("⛔ 多套装配内核并存：改了 yml 阈值可能有一套没生效")
                    .hasSize(1);
            assertThat(readField(ctx.getBean(RequestAssemblyPort.class), "assembler"))
                    .isSameAs(assembler);
        }

        @Test
        @DisplayName("✅ 水位阈值来自配置，不是 Thresholds.defaults() 旁路")
        void thresholdsComeFromConfiguration() {
            AgentContextProperties properties = ctx.getBean(AgentContextProperties.class);
            Thresholds thresholds = ctx.getBean(Thresholds.class);

            assertThat(thresholds.snipRatio())
                    .as("⛔ 配置被 defaults() 旁路：改 AGENT_CONTEXT_TIER_SNIP 不生效")
                    .isEqualTo(properties.getTierSnipRatio())
                    .isEqualTo(0.55d)
                    .isNotEqualTo(Thresholds.defaults().snipRatio());
            assertThat(thresholds.summaryRatio()).isEqualTo(properties.getTierSummaryRatio());
            assertThat(thresholds.minNewTokensRatio())
                    .isEqualTo(properties.getMinNewTokensRatio());
        }
    }

    private static @NonNull Object readField(@NonNull Object target, @NonNull String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value == null) {
                    throw new AssertionError("字段 " + name + " 为 null（断链）");
                }
                return value;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("读取字段 " + name + " 失败", e);
            }
        }
        throw new AssertionError("找不到字段 " + name + "（类名/字段名已漂移，请更新守卫）");
    }
}

package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import cn.studykid.multiagent.api.chat.ConsoleChatController;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;
import cn.studykid.multiagent.worker.execute.WorkerChildAgentRunner;

/**
 * S3-M9 L3 ConsoleChat 预算保护的<b>组合根 Bean 图连通性</b>（ADR-013 D52）。
 *
 * <p>⚠️ 为什么必须有本类：{@code ConsoleBudgetAdapterTest} 直接 {@code new} adapter，
 * 只能证明「接上以后行为正确」；若生产组合根少一个 {@code @Bean} 或 Controller 没拿到端口，
 * 所有行为单测仍全绿，而线上 Console 仍走 legacy。这里用轻量 Spring 上下文钉死：
 * <ol>
 *   <li>唯一 {@link ConsoleBudgetPort} Bean 存在，且是真 {@link ConsoleBudgetAdapter}；</li>
 *   <li>{@link ConsoleChatController} 生产构造器真的持有该 Bean（不是 Optional.empty 空转）；</li>
 *   <li>adapter 与 Run 装配入口共用<b>同一个</b> {@link ContextAssembler}，不另起第二套内核；</li>
 *   <li>水位阈值仍来自配置，M7 既有装配面不回退。</li>
 * </ol>
 *
 * <p>⚠️ 本类用 {@link ApplicationContextRunner} 只加载相关配置片与测试替身，
 * ⛔ 不启完整 DB / Rabbit 上下文，避免把 M9 的接线证据绑到集成环境上（无库时跳过＝假绿）。
 */
class ConsoleBudgetWiringTest {

    private static final String MODEL = "model-m9-wiring";

    @Nested
    @DisplayName("生产 Bean 图")
    class ProductionWiring {

        @Test
        @DisplayName("✅ ConsoleBudgetPort 唯一且为真 adapter，Controller 真的持有它")
        void consoleBudgetPortIsWiredIntoController() {
            runner().run(context -> {
                assertThat(context.getStartupFailure()).isNull();

                assertThat(context.getBeansOfType(ConsoleBudgetPort.class))
                        .as("⛔ L3 预算保护端口未装配 / 多 Bean：Controller 会空转或注入歧义")
                        .hasSize(1);
                ConsoleBudgetPort port = context.getBean(ConsoleBudgetPort.class);
                assertThat(port).isInstanceOf(ConsoleBudgetAdapter.class);

                ConsoleChatController controller = context.getBean(ConsoleChatController.class);
                assertThat(readField(controller, "consoleBudgetPort"))
                        .as("⛔ Controller 没持有组合根端口：W1/W2 从未进入 Console 链路")
                        .isSameAs(port);
            });
        }

        @Test
        @DisplayName("✅ L3 adapter 复用唯一 ContextAssembler，不另起第二套内核")
        void adapterSharesTheSingleAssemblerBean() {
            runner().run(context -> {
                assertThat(context.getStartupFailure()).isNull();

                ContextAssembler assembler = context.getBean(ContextAssembler.class);
                assertThat(context.getBeansOfType(ContextAssembler.class))
                        .as("⛔ 多套装配内核并存：改 yml 阈值会有一套不生效")
                        .hasSize(1);
                ConsoleBudgetPort console = context.getBean(ConsoleBudgetPort.class);
                assertThat(readField(console, "assembler")).isSameAs(assembler);
                assertThat(readField(console, "global"))
                        .isSameAs(context.getBean(GlobalContextPolicy.class));
                assertThat(readField(console, "catalog"))
                        .as("⛔ 模型窗口没接真目录：能力会静默按 unknown 粗估")
                        .isSameAs(context.getBean(ModelCatalogPort.class));
            });
        }

        @Test
        @DisplayName("✅ L3 与 Run 装配共用同一套水位阈值 / 摘要器占位，且默认全局开关仍关闭")
        void l3SharesRunCompositionRootBasics() {
            runner().run(context -> {
                assertThat(context.getStartupFailure()).isNull();

                AgentContextProperties properties = context.getBean(AgentContextProperties.class);
                Thresholds thresholds = context.getBean(Thresholds.class);
                assertThat(thresholds.snipRatio()).isEqualTo(properties.getTierSnipRatio());
                assertThat(thresholds.summaryRatio()).isEqualTo(properties.getTierSummaryRatio());
                assertThat(readField(context.getBean(ConsoleBudgetPort.class), "summarizer"))
                        .as("⛔ L3 私造摘要器：TRIM_ONLY 仍可能悄悄外调模型")
                        .isSameAs(context.getBean(
                                cn.studykid.multiagent.context.CompactionPipeline.Summarizer.class));
                assertThat(context.getBean(GlobalContextPolicy.class).enabled())
                        .as("⛔ 生产默认开启压缩：G1 影子期零差异承诺被破坏")
                        .isFalse();
                assertThat(context.getBeansOfType(RunExecutionLoop.class))
                        .as("⛔ M7 Run 接线面不应被 M9 覆盖")
                        .isEmpty();
            });
        }
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(
                        AgentContextConfiguration.class,
                        ContextRequestAssemblyConfiguration.class,
                        ConsoleBudgetConfiguration.class,
                        ConsoleChatController.class,
                        TestBeans.class);
    }

    /** 读取私有字段；字段名漂移立即失败，不允许静默返回 null 造成假绿。 */
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
        throw new AssertionError("找不到字段 " + name + "（请更新守卫，不要删除断言）");
    }

    /**
     * ⚠️ 必须用 {@link TestConfiguration} 而不是普通 {@code @Configuration}：
     * 本测试类位于 bootstrap 组件扫描包内，普通 {@code @Configuration} 会被
     * {@code PlatformApplication} 在**全量 verify 的其它 @SpringBootTest 里**扫到，
     * 与生产 {@code ApplicationConfiguration#rateLimitPort} 撞名并直接启动失败。
     * {@code @TestConfiguration} 带 TypeExcludeFilter，只在显式
     * {@code ApplicationContextRunner#withUserConfiguration} 时生效。
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentContextProperties.class)
    static class TestBeans {

        @Bean(name = "chatStreamGateway")
        ChatStreamGateway chatStreamGateway() {
            return (request, consumer) -> new ModelResponse("", Map.of(),
                    new ModelResponse.ModelUsage(0, 0), ModelResponse.FinishReason.STOP);
        }

        @Bean
        ConsoleChatController.ChatCaller chatCaller() {
            return () -> "00000000-3000-4000-8000-000000000001";
        }

        @Bean
        RateLimitPort rateLimitPort() {
            return new RateLimitPort() {
                @Override
                public Decision check(String tenantId, String bucketKey) {
                    return Decision.ALLOWED;
                }

                @Override
                public long currentCount(String tenantId, String bucketKey) {
                    return 0L;
                }
            };
        }

        @Bean
        ModelCatalogPort modelCatalogPort() {
            return new ModelCatalogPort() {
                @Override
                public List<ModelDefinition> list() {
                    return List.of(definition());
                }

                @Override
                public Optional<ModelDefinition> get(String id) {
                    return Optional.empty();
                }

                @Override
                public ModelDefinition save(ModelDefinition definition) {
                    return definition;
                }

                @Override
                public void delete(String id) {
                }

                @Override
                public Optional<ModelDefinition> defaultForScenario(String scenario) {
                    return Optional.empty();
                }

                @Override
                public Optional<ModelDefinition> byScenarioAndName(String scenario,
                                                                   String modelName) {
                    return Optional.empty();
                }
            };
        }
    }

    private static ModelDefinition definition() {
        return new ModelDefinition(UUID.nameUUIDFromBytes(MODEL.getBytes()).toString(),
                "test", "agent-chat", MODEL, null, null, false, true, true, false,
                null, true, 0, null, null, null,
                new ModelContextCapabilities(32_768, 28_672, null, false, false));
    }

    /** 让 import 不被编译器判为未使用：同步记录 M9 不引入 Run 侧组件的白名单意图。 */
    @SuppressWarnings("unused")
    private static final List<Class<?>> RUN_SIDE_TYPES = List.of(
            RunExecutionLoop.class, WorkerChildAgentRunner.class, ModelMessage.class,
            ModelRequest.class, ModelErrorKind.class, Duration.class);
}

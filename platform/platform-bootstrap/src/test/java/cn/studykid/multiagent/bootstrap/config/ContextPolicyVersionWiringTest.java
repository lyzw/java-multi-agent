package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort.Input;

/**
 * S3-M8-a 策略指纹裁决端口的 Spring 装配证据（非空转）。
 *
 * <p>只验装配边界：唯一 Bean、与 S1 同一个 {@link GlobalContextPolicy} Bean、
 * 关闭态仍返回 {@code null}。策略算法本身由 {@link ContextPolicyVersionAdapterTest} 覆盖。
 */
class ContextPolicyVersionWiringTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(ContextPolicyVersionConfiguration.class, TestGlobal.class);

    @Test
    @DisplayName("✅ 组合根产出唯一 ContextPolicyVersionPort，并共用 S1 全局策略 Bean")
    void singleBeanWiredFromSharedGlobalPolicy() {
        RUNNER.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBeansOfType(ContextPolicyVersionPort.class)).hasSize(1);
            ContextPolicyVersionPort port = context.getBean(ContextPolicyVersionPort.class);
            assertThat(port).isInstanceOf(ContextPolicyVersionAdapter.class);
            assertThat(context.getBean(GlobalContextPolicy.class)).isSameAs(TestGlobal.GLOBAL);
            assertThat(port.resolve(new Input("t", "a", 1, "m", 1024))).isNull();
        });
    }

    @Test
    @DisplayName("⛔ 自定义实现可直接覆盖：@ConditionalOnMissingBean 不制造第二个策略真相")
    void customImplementationOverridesDefault() {
        new ApplicationContextRunner()
                // 自定义 Bean 必须先注册，条件装配才有可判定的「已存在」事实。
                .withUserConfiguration(CustomPort.class, TestGlobal.class,
                        ContextPolicyVersionConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(ContextPolicyVersionPort.class)).hasSize(1);
                    assertThat(context.getBean(ContextPolicyVersionPort.class))
                            .isSameAs(context.getBean(CustomPort.class).port);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestGlobal {
        static final GlobalContextPolicy GLOBAL =
                new GlobalContextPolicy(false, 128_000, 16_000, 0.10);

        @Bean
        GlobalContextPolicy global() {
            return GLOBAL;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPort {
        final ContextPolicyVersionPort port = input -> "cp_custom";

        @Bean
        ContextPolicyVersionPort customContextPolicyVersionPort() {
            return port;
        }
    }
}

package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * 组件扫描构造器装配守卫（2026-11-07 启动事故）。
 *
 * <p><b>背景</b>：{@code JdbcProviderCredentialRepository} 与
 * {@code ConsoleChatController} 都是「{@code @Repository}/{@code @RestController} +
 * 两个构造器 + 无 {@code @Autowired}」——编译、单测全绿，但
 * {@code @SpringBootApplication(scanBasePackages = "cn.studykid.multiagent")}
 * 启动时 Spring 找不到候选构造、回落无参构造，抛
 * {@code Failed to instantiate [X]: No default constructor found}。
 * 单构造时 Spring 4.3+ 会隐式注入，多构造时<b>必须</b>显式标注。
 *
 * <p><b>守卫命题</b>：凡被组件扫描命中、声明了 ≥2 个构造器且没有无参构造的类，
 * 至少要有一个构造器带 {@code @Autowired}；否则必须在本类登记有界例外。
 *
 * <p>⚠️ <b>防空转</b>：路径/类路径发现失败会让空集合恒绿（本项目踩过多次），
 * 故同时断言「扫到的组件数」与「多构造组件数」下界。
 */
class ComponentScanConstructorWiringTest {

    /** 由组合根 {@code @Bean} 方法覆盖组件扫描定义的类：不走构造器注入。 */
    private static final Set<String> BEAN_METHOD_OVERRIDDEN = Set.of(
            // ApplicationConfiguration#runCreationService 显式 new，@Service 的扫描定义被 @Bean 覆盖
            "cn.studykid.multiagent.application.run.RunCreationService");

    @Test
    @DisplayName("⛔ 多构造的组件扫描类必须显式声明 @Autowired 构造")
    void multiConstructorComponentsDeclareAutowiredConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("cn.studykid.multiagent");

        List<String> violations = new ArrayList<>();
        List<String> loadFailures = new ArrayList<>();
        int scanned = 0;
        int multiConstructor = 0;

        for (BeanDefinition definition : candidates) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                loadFailures.add(className + ": " + e);
                continue;
            }
            // 测试类不在生产组件扫描范围内（surefire 会把 test-classes 放进 classpath）
            var location = type.getProtectionDomain().getCodeSource();
            if (location != null && location.getLocation().toString().contains("test-classes")) {
                continue;
            }
            scanned++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                continue;
            }
            multiConstructor++;
            boolean hasNoArg = Arrays.stream(constructors)
                    .anyMatch(c -> c.getParameterCount() == 0);
            boolean hasAutowired = Arrays.stream(constructors)
                    .anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            if (!hasNoArg && !hasAutowired && !BEAN_METHOD_OVERRIDDEN.contains(className)) {
                violations.add(className + "（" + constructors.length
                        + " 个构造，既无无参构造也无 @Autowired）");
            }
        }

        assertThat(loadFailures)
                .as("类路径扫描到的组件必须可加载（否则守卫会静默漏检）")
                .isEmpty();
        assertThat(candidates.size())
                .as("组件扫描防空转：至少应扫到 50 个生产组件")
                .isGreaterThanOrEqualTo(50);
        assertThat(multiConstructor)
                .as("多构造组件防空转：应至少覆盖 5 个已知多构造类")
                .isGreaterThanOrEqualTo(5);
        assertThat(violations)
                .as("这些类会在 Spring 启动期抛 No default constructor found；"
                        + "请给生产装配构造器加 @Autowired（全限定写法亦可）")
                .isEmpty();
    }
}

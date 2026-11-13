package cn.studykid.multiagent.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * 请求体缓存过滤器的<b>装配守卫</b>：注册了没有、装在哪一层、会不会被扫描到。
 *
 * <p><b>为什么要有它</b>：本过滤器是「异常日志能看见 JSON body」的<b>唯一来源</b>。
 * 它一旦没被扫描到（包名写错 / 组件扫描范围变了 / 注解被删），
 * 代码与单测仍然全绿，<b>只有生产环境的日志会静默少掉 body</b>——
 * 这正是「撤掉修复点也不会红」的那种假绿，必须由装配层守卫兜住。
 *
 * <p><b>⚠️ 为什么不能只靠 {@code @SpringBootTest(classes = …)}</b>：
 * 那个写法把配置类<b>显式</b>塞进上下文，于是「{@code @Configuration} 被删掉」
 * 这种最常见的退化<b>照样绿</b>（实测：删掉注解后本用例全绿 = 假绿）。
 * 因此另有一条<b>与生产同构</b>的扫描守卫：按
 * {@code @SpringBootApplication(scanBasePackages = "cn.studykid.multiagent")}
 * 的方式扫描，断言这个配置类确实在候选里。
 *
 * <p><b>反向验证</b>：把 {@code @Configuration} 从类上删掉 → 扫描守卫红；
 * 把 order 改到安全链之后 → 顺序守卫红；把 {@code addUrlPatterns} 删掉 → 注册守卫红。
 */
@SpringBootTest(classes = RequestBodyCachingConfiguration.class)
@DisplayName("RequestBodyCachingConfiguration · 过滤器装配")
class RequestBodyCachingConfigurationTest {

    @Autowired
    private FilterRegistrationBean<RequestBodyCachingConfiguration.RequestBodyCachingFilter> registration;

    @Test
    @DisplayName("✅ 过滤器被注册成 FilterRegistrationBean：没有它就没有 body 可打")
    void filterIsRegistered() {
        assertThat(registration).isNotNull();
        assertThat(registration.getFilter())
                .as("⛔ 过滤器没注册 → 生产日志会静默少掉 JSON body，代码与单测却全绿")
                .isInstanceOf(RequestBodyCachingConfiguration.RequestBodyCachingFilter.class);
        assertThat(registration.getUrlPatterns()).contains("/*");
    }

    @Test
    @DisplayName("✅ 装在安全链之前：未认证就失败的请求（401/限流）也要能看见 body")
    void registeredBeforeSecurityChain() {
        assertThat(registration.getOrder())
                .as("⛔ 装在安全链之后 → 最需要 body 的场景恰好拿不到 body")
                .isLessThan(OrderedFilterOrders.SECURITY_CHAIN_ORDER);
    }

    @Test
    @DisplayName("✅ 只在 REQUEST 派发包装：ERROR 二次派发不该再造一个包装对象")
    void onlyOnRequestDispatch() {
        assertThat(registration.determineDispatcherTypes()).containsExactly(DispatcherType.REQUEST);
    }

    @Test
    @DisplayName("⛔ 必须被组件扫描命中：注解被删 / 包名挪走 = 生产日志静默少掉 body")
    void configurationIsPickedUpByComponentScan() {
        // 与生产同构：PlatformApplication 的 scanBasePackages 就是 "cn.studykid.multiagent"。
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents("cn.studykid.multiagent");

        assertThat(candidates.stream().map(BeanDefinition::getBeanClassName).toList())
                .as("⛔ 这个配置类没进组件扫描 → 过滤器在生产环境根本不存在，"
                        + "而代码与单测全绿（假绿）")
                .contains("cn.studykid.multiagent.api.error.RequestBodyCachingConfiguration");
    }

    /** Spring Security 的过滤器链默认顺序（{@code OrderedFilter} 默认 −100）。 */
    private static final class OrderedFilterOrders {
        private static final int SECURITY_CHAIN_ORDER = -100;
    }
}

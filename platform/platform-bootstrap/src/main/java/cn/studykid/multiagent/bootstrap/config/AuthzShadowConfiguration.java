package cn.studykid.multiagent.bootstrap.config;

import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.infrastructure.security.AccessDeniedException;
import cn.studykid.multiagent.infrastructure.security.AccessGate;
import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.security.AuthzMismatchMetrics;
import cn.studykid.multiagent.infrastructure.security.AuthzSamplingPolicy;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowPolicyDefaults;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.DefaultAccessGate;
import cn.studykid.multiagent.infrastructure.security.JdbcAuthzDecisionLog;
import cn.studykid.multiagent.infrastructure.security.NoOpAuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.security.PolicyEngine;
import cn.studykid.multiagent.infrastructure.security.PolicyStore;
import cn.studykid.multiagent.infrastructure.security.RuleBasedPolicyEngine;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.infrastructure.security.Slf4jAuthzMismatchMetrics;
import cn.studykid.multiagent.infrastructure.security.SubjectContextResolver;

/**
 * 权限<b>影子态</b>装配（P1 / 12 文 §4.5 · 13 文 §5.2）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：D2–D7 的领域模型、策略引擎、染色与指标都已实现并有单测，
 * 但<b>没有任何装配把它们连起来</b> —— 影子钩子在任何容器里都拿不到
 * {@code ScopedDataAccess} / {@code AuthzShadowReporter}，于是
 * <blockquote>
 * 「影子期 2 周 AUTHZ_MISMATCH=0」会在<b>一次都没跑过</b>的情况下达成。
 * </blockquote>
 * 这是本项目反复出现的同一形态：<b>代码在、测试绿、接缝没插电</b>
 * （{@code RetentionConfig} 的注释里已记录过同类事故）。
 *
 * <p><b>开关语义（只有一个开关）</b>：{@code agent.authz.shadow.enabled=true} 时本类才生效，
 * 生效即产出 {@link AuthzShadowBinding} Bean；仓库通过 {@code @Autowired(required=false)}
 * 的 setter 拿到它、把影子态字段填上。<b>关闭时该 Bean 不存在</b>，仓库保持未接线状态，
 * 行为与接线前<b>逐字节一致</b>（零行为变更）。
 *
 * <p>⚠️ <b>为什么不设「Bean 在 + enabled 布尔」的双开关</b>：两个开关必然出现
 * 「Bean 在、开关关」的半开状态 —— 那正是 P6R1 中 {@code platform-ops} 配置块错挂
 * 导致「配了却恒为未接入、且无任何报错」的同一个坑。存在即开启，不存在即关闭。
 *
 * <p><b>主体解析器在影子期是 fail-closed 的</b>：本类提供的
 * {@link SubjectContextResolver} 直接抛 {@link AccessDeniedException}（{@code NO_SUBJECT}）。
 * 原因是<b>请求路径目前没有线程级主体上下文</b>（租户是逐查询显式传入的），
 * 所以已接线的仓库全部走「显式主体」重载；一旦有代码走「环境主体」重载，
 * 影子比对会立刻报出「Gate 拒绝」，<b>把缺口暴露出来而不是假装放行</b>。
 *
 * <p><b>决策日志</b>（{@code agent.authz.decision-log.enabled}，默认 {@code false}）：
 * 关闭时恒为 {@link NoOpAuthzDecisionRecorder}；开启后为 {@link JdbcAuthzDecisionLog}，
 * 写 {@code platform.authz_decision_log}（表结构见 {@code deploy/sql/platform/01_platform_ddl.sql}，
 * 授权见 {@code 03_platform_roles.sql} —— 应用角色<b>只有 INSERT</b>）。
 * ⚠️ 开关是否「真的能落库」由 {@code AuthzDecisionLogGuard} 在启动期用零行写入探测证实；
 * 探测不通过即拒绝启动，绝不允许「开关开着、每次判定都写失败、被限频告警吞掉」的静默无审计。
 */
@Configuration
@ConditionalOnProperty(name = "agent.authz.shadow.enabled", havingValue = "true")
public class AuthzShadowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthzShadowConfiguration.class);

    /**
     * 采样策略（12 文 §7.1 R7）。
     *
     * <p>⚠️ 12 文原写「生产 0.1%」，评审后上调默认值为 {@code 0.01}：0.1% 在
     * 「每 (主体, 资源) 300s 一桶」的确定性采样下，单个组合平均要等 ~100 小时才被采到一次，
     * 两个月观察期里小流量租户可能<b>一次都没被看过</b>，而读数仍然是 0。
     * 高敏资源（导出/检索/统计/跨租户读）另由 100% 清单兜底。
     */
    @Bean
    public AuthzSamplingPolicy authzSamplingPolicy(
            @Value("${agent.authz.shadow.sampling.rate}") String rate,
            @Value("${agent.authz.shadow.sampling.window-seconds}") String windowSeconds,
            @Value("${agent.authz.shadow.sampling.always-sample-resource-types}") String alwaysSample) {
        double r;
        long windowMillis;
        try {
            r = Double.parseDouble(rate.trim());
            windowMillis = Math.multiplyExact(Long.parseLong(windowSeconds.trim()), 1000L);
        } catch (NumberFormatException | ArithmeticException e) {
            // fail-closed：采样参数写错时拒绝启动，而不是悄悄退化成「不采样」或「全采样」
            throw new IllegalStateException(
                    "权限影子态采样参数非法（rate=" + rate + ", window-seconds=" + windowSeconds + "）", e);
        }
        Set<String> always = AuthzSamplingPolicy.parseAlwaysSample(alwaysSample);
        log.info("权限影子态采样策略: rate={} window={}s alwaysSample={}", r, windowMillis / 1000, always);
        return AuthzSamplingPolicy.of(r, windowMillis, always);
    }

    /** 观测指标出口：SLF4J 实现（mismatch / taint 直接打 WARN，便于告警订阅）。 */
    @Bean
    public AuthzMismatchMetrics authzMismatchMetrics() {
        return new Slf4jAuthzMismatchMetrics();
    }

    /**
     * 影子上报桥。
     *
     * <p>⚠️ {@code block-on-violation} 在 P1 必须为 {@code false}：影子期的承诺是
     * 「只观测，绝不改变请求结果」。P2 双写期才翻 {@code true}（违例直接 fail-closed）。
     */
    @Bean
    public AuthzShadowReporter authzShadowReporter(
            AuthzMismatchMetrics metrics,
            AuthzSamplingPolicy sampling,
            @Value("${agent.authz.shadow.block-on-violation}") String blockOnViolation) {
        boolean block = parseBoolean("agent.authz.shadow.block-on-violation", blockOnViolation);
        if (block) {
            log.warn("⚠️ 权限影子态已开启阻断模式（block-on-violation=true）："
                    + "这属于 P2 双写期行为，任何 mismatch 都会直接拒绝请求");
        }
        return new AuthzShadowReporter(metrics, block, sampling);
    }

    /** 策略存储（P1 用内置规则；规则集由 {@code ShadowPolicyCoverageTest} 钉住覆盖面）。 */
    @Bean
    public PolicyStore authzPolicyStore() {
        return AuthzShadowPolicyDefaults.store();
    }

    @Bean
    public PolicyEngine authzPolicyEngine(PolicyStore store) {
        return new RuleBasedPolicyEngine(store);
    }

    /**
     * 主体解析器 —— 影子期 fail-closed。
     *
     * <p>见类注释：请求路径没有线程级主体，已接线仓库全部走显式主体重载。
     * 任何走到这里的调用都应当<b>报出来</b>（记为 Gate 拒绝的 mismatch），而不是猜一个主体。
     */
    @Bean
    public SubjectContextResolver authzSubjectContextResolver() {
        return () -> {
            throw new AccessDeniedException("NO_SUBJECT",
                    "影子期未接入线程级主体解析器：请使用显式主体（SubjectContext）重载");
        };
    }

    /**
     * 决策日志记录器（D6 / P2）。
     *
     * <p>⚠️ 默认 {@link NoOpAuthzDecisionRecorder}：P1 影子期的目标是「比对结论是否可信」，
     * 不是「把每次判定写进库」。开启后每次<b>被采样到</b>的判定都会落一行流水
     * （采样在 {@code AuthzShadowHooks} 里先于 Gate 生效，故写放大受采样率约束）。
     *
     * <p>⚠️ <b>本 Bean 恒存在、只是实现不同</b>：这样 {@code DefaultAccessGate} 的构造签名
     * 不必随开关变化，也就不会出现「改了开关要改装配」的连带改动。
     * 开关打不开、或表不可写时由 {@code AuthzDecisionLogGuard} 在启动期 fail-closed。
     */
    @Bean
    public AuthzDecisionRecorder authzDecisionRecorder(
            DataSource dataSource,
            @Value("${agent.authz.decision-log.enabled}") String enabled) {
        boolean on = parseBoolean("agent.authz.decision-log.enabled", enabled);
        if (!on) {
            return NoOpAuthzDecisionRecorder.INSTANCE;
        }
        log.info("授权决策日志已开启：每次被采样到的判定将写入 platform.authz_decision_log");
        return new JdbcAuthzDecisionLog(dataSource);
    }

    @Bean
    public AccessGate authzAccessGate(SubjectContextResolver resolver, PolicyEngine engine,
                                      AuthzDecisionRecorder recorder) {
        return new DefaultAccessGate(resolver, engine, recorder);
    }

    @Bean
    public ScopedDataAccess authzScopedDataAccess(AccessGate gate) {
        return new ScopedDataAccess(gate);
    }

    /**
     * 接线载体 —— <b>它的存在本身就是「影子态开启」</b>。
     *
     * <p>仓库侧通过 {@code @Autowired(required = false)} 的 setter 接收；
     * 关闭影子态时本 Bean 不注册，仓库保持未接线（零行为变更）。
     */
    @Bean
    public AuthzShadowBinding authzShadowBinding(ScopedDataAccess scoped,
                                                 AuthzShadowReporter reporter,
                                                 @Value("${agent.authz.shadow.sentinel-tenant-id}") String sentinel) {
        AuthzShadowBinding binding = new AuthzShadowBinding(scoped, reporter, sentinel);
        log.info("权限影子态已开启：sentinelTenantId='{}' 采样率={} 阻断={}（P1 影子期只观测，不改变请求结果）",
                binding.sentinelTenantId(), reporter.sampling() == null ? "全量" : reporter.sampling().rate(),
                reporter.blockOnViolation());
        return binding;
    }

    /**
     * 布尔解析。
     *
     * <p>⚠️ 本项目 {@code application.yml} 的值一律加引号（裸标量会被
     * {@code YamlPropertiesFactoryBean} 丢弃）—— 这里显式 trim + 忽略大小写，
     * 并对非布尔值 fail-closed（拒绝启动），避免「配错一个单词 = 悄悄关掉阻断」。
     */
    private static boolean parseBoolean(String key, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(v)) {
            return true;
        }
        if ("false".equals(v)) {
            return false;
        }
        throw new IllegalStateException("配置项 " + key + " 不是合法布尔值: '" + raw + "'");
    }
}

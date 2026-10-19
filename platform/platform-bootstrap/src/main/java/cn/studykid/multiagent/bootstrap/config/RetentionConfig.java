package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.config.RetentionPolicy;
import cn.studykid.multiagent.application.retention.ConsentWithdrawalService;
import cn.studykid.multiagent.application.retention.RetentionPurgeService;
import cn.studykid.multiagent.infrastructure.persistence.JdbcErasureRepository;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.worker.dispatch.TenantSlugResolver;
import cn.studykid.multiagent.worker.retention.RetentionJob;

/**
 * 保留期清理装配（P4-D04）。
 *
 * <p>⚠️ <b>本配置类是本片「不断链」承诺的兑现点</b>：
 * {@link RetentionPurgeService} 本身不会自我驱动，
 * 必须有 {@link RetentionJob} Bean 并被 Spring 实例化。
 * 缺少本类的表现是：所有清理代码与测试都在、集成测试全绿，
 * 但数据从不被删除——且系统无任何异常信号。
 *（本项目 D01/D03 已两次遇到同一形态。）
 *
 * <p><b>⚠️ F-6 纪律</b>：本类纯用 {@code @Bean} 方法装配，
 * 不为任何 Bean 类增加第二个构造器——此前三次因「组件扫描类含两个构造器」
 * 导致 {@code No default constructor found}，进而 E2E 全军覆没。
 *
 * <p><b>为何租户来自 {@code agent.worker.tenant-slug}</b>：
 * 复用 D03 已确立的「配置放人类可读 slug、启动时解析成 UUID」约定，
 * 避免同一份配置里出现两套租户来源——两套来源迟早不一致。
 */
@Configuration
@ConditionalOnProperty(name = "agent.retention.enabled", havingValue = "true")
public class RetentionConfig {

    private static final Logger log = LoggerFactory.getLogger(RetentionConfig.class);

    @Bean
    public ErasurePort erasurePort(DataSource dataSource) {
        return new JdbcErasureRepository(dataSource);
    }

    @Bean
    public RetentionPurgeService retentionPurgeService(
            ErasurePort erasurePort,
            @Value("${" + RetentionPolicy.KEY_PROFILE_MONTHS + "}") String profileMonths,
            @Value("${" + RetentionPolicy.KEY_PROMPT_DAYS + "}") String promptDays,
            @Value("${" + RetentionPolicy.KEY_AUDIT_DAYS + "}") String auditDays,
            @Value("${" + RetentionPolicy.KEY_TRACE_DAYS + "}") String traceDays,
            @Value("${" + RetentionPolicy.KEY_WITHDRAWAL_GRACE_DAYS + "}") String graceDays,
            @Value("${" + RetentionPolicy.KEY_BATCH_SIZE + "}") String batchSize,
            @Value("${agent.retention.actor}") String actor) {
        RetentionPurgeService.RetentionSettings settings =
                new RetentionPurgeService.RetentionSettings(
                        RetentionPolicy.requireProfileMonths(profileMonths),
                        RetentionPolicy.requirePromptDays(promptDays),
                        RetentionPolicy.requireAuditDays(auditDays),
                        RetentionPolicy.requireTraceDays(traceDays),
                        RetentionPolicy.requireWithdrawalGraceDays(graceDays),
                        RetentionPolicy.requireBatchSize(batchSize));
        log.info("保留期清理配置已装载: profileMonths={}, promptDays={}, auditDays={}, "
                        + "traceDays={}, graceDays={}, batchSize={}, actor={}",
                settings.profileMonths(), settings.promptDays(), settings.auditDays(),
                settings.traceDays(), settings.withdrawalGraceDays(),
                settings.batchSize(), actor);
        return new RetentionPurgeService(erasurePort, settings, actor, Clock.systemUTC());
    }

    @Bean
    public ConsentWithdrawalService consentWithdrawalService(
            ErasurePort erasurePort,
            @Value("${" + RetentionPolicy.KEY_WITHDRAWAL_GRACE_DAYS + "}") String graceDays) {
        return new ConsentWithdrawalService(
                erasurePort,
                RetentionPolicy.requireWithdrawalGraceDays(graceDays),
                Clock.systemUTC());
    }

    /**
     * 清理任务 Bean。
     *
     * <p>⚠️ <b>{@code initMethod}/{@code destroyMethod} 必须显式写</b>：
     * Spring 不会猜你想调用 {@code start()}。漏掉表现为
     * 「Bean 存在但不工作」——又一种静默断链。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public RetentionJob retentionJob(
            RetentionPurgeService purgeService,
            DataSource dataSource,
            @Value("${agent.worker.tenant-slug}") String tenantSlug,
            @Value("${" + RetentionPolicy.KEY_INTERVAL_MS + "}") String intervalMs) {
        String tenantId = TenantSlugResolver.resolve(dataSource, tenantSlug);
        // ⚠️ 单实例多租户（D05 的范围）：此处启动解析一个 slug，
        //    多租户时改为解析多个——但清单一律显式，不得「全租户扫描」，
        //    后者会在配置错误时跨租户删除。
        return new RetentionJob(purgeService, List.of(tenantId),
                Duration.ofMillis(RetentionPolicy.requireIntervalMs(intervalMs)));
    }
}

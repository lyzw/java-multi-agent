package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.RetentionPolicy;
import cn.studykid.multiagent.application.config.WorkerPolicy;
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
 * <p><b>为何租户来自 {@code agent.worker.tenant-slugs}</b>：
 * 复用 D03 已确立的「配置放人类可读 slug、启动时解析成 UUID」约定，
 * 避免同一份配置里出现两套租户来源——两套来源迟早不一致。
 * （P4-D05 起该键为<b>清单</b>：中继与清理服务同一份租户来源，
 * 否则会出现「消息按 3 个租户发、清理只清 1 个租户」的静默缺口。）
 */
@Configuration
@ConditionalOnProperty(name = "agent.retention.enabled", havingValue = "true")
public class RetentionConfig {

    private static final Logger log = LoggerFactory.getLogger(RetentionConfig.class);

    /**
     * 擦除端口（保留期清理 / 撤回同意删除）。
     *
     * <p>⚠️⚠️ <b>P2-2：优先使用清理专用数据源</b>（{@code agent_retention}）。
     * 凭据隔离脚本撤销了应用角色对 {@code audit_record} /
     * {@code erasure_certificate} 的 DELETE —— 用应用凭据跑清理，
     * 只会在到期那天得到一个 {@code permission denied}，
     * 而那时现场是「清理任务失败了」，离「这条连接不该有权限的事实」极远。
     *
     * <p>⚠️ <b>为什么默认不强制</b>：
     * 隔离脚本尚未在各环境执行前，应用角色<b>仍然</b>持有 DELETE，
     * 此时强制要求独立凭据只会让实例起不来，却不增加任何安全性。
     * 故默认 <b>回落到主数据源 + 醒目 WARN</b>，把「清理通道 == 应用凭据」
     * 这一事实显式暴露出来；执行完隔离脚本的环境应把
     * {@code agent.retention.require-isolated-credential} 置为 {@code true}，
     * 届时缺清理凭据 → <b>拒绝启动</b>（fail-closed）。
     *
     * <p>⚠️ <b>绝不静默</b>：回落是一次 SECURITY 级告警而不是 INFO——
     * 「配了等于没配且没有报错」正是本项目已经吃过一次亏的故障形态。
     */
    @Bean
    public ErasurePort erasurePort(
            DataSource mainDataSource,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier(
                    RetentionDataSourceConfiguration.BEAN_NAME)
            javax.sql.DataSource retentionDataSource,
            @org.springframework.beans.factory.annotation.Value(
                    "${agent.retention.require-isolated-credential:false}")
            boolean requireIsolatedCredential) {
        if (retentionDataSource != null) {
            return new JdbcErasureRepository(retentionDataSource);
        }
        String message = "未接入保留期清理专用数据源（"
                + RetentionDataSourceConfiguration.KEY_ENABLED + "），"
                + "正在回落应用主数据源：清理通道的凭据 == 应用凭据，"
                + "持有应用凭据者即可删除 audit_record / erasure_certificate。"
                + "执行 deploy/sql/platform/04_retention_role.sql 后应将 "
                + "agent.retention.require-isolated-credential 设为 true。";
        if (requireIsolatedCredential) {
            throw new IllegalStateException(message
                    + " 本环境已声明要求凭据隔离，拒绝以应用凭据启动清理通道。");
        }
        log.error("⚠️⚠️ SECURITY: {}", message);
        return new JdbcErasureRepository(mainDataSource);
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
            @Value("${" + WorkerPolicy.KEY_TENANT_SLUGS + "}") String tenantSlugs,
            @Value("${" + PlatformConfig.KEY_PILOT_MAX_TENANTS + "}") String pilotMaxTenants,
            @Value("${" + RetentionPolicy.KEY_INTERVAL_MS + "}") String intervalMs) {
        // ⚠️ 与中继共用同一份租户清单（P4-D05）：
        //    两套来源迟早不一致，而「清理漏掉某个租户」的表现是
        //    「数据超期留存」——违反 PIPL 且不报任何错。
        int maxTenants = PlatformConfig.Resolver.requirePositiveInt(
                pilotMaxTenants, PlatformConfig.KEY_PILOT_MAX_TENANTS);
        List<String> slugs = WorkerPolicy.requireTenantSlugs(tenantSlugs, maxTenants);
        List<String> tenantIds = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            tenantIds.add(TenantSlugResolver.resolve(dataSource, slug));
        }
        log.info("保留期清理租户清单: slugs={}, 租户数={}", slugs, tenantIds.size());
        return new RetentionJob(purgeService, List.copyOf(tenantIds),
                Duration.ofMillis(RetentionPolicy.requireIntervalMs(intervalMs)));
    }
}

package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.agentchataudit.ChatAuditRetentionService;
import cn.studykid.multiagent.application.config.PlatformConfig;
import cn.studykid.multiagent.application.config.RetentionPolicy;
import cn.studykid.multiagent.application.config.WorkerPolicy;
import cn.studykid.multiagent.infrastructure.agentchataudit.DirectoryChatAuditRetention;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditRetentionRepository;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditDirectoryRetentionPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.worker.dispatch.TenantSlugResolver;
import cn.studykid.multiagent.worker.retention.ChatAuditRetentionJob;

/**
 * 对话审计保留期清理的组合根（PIPL §7）。
 *
 * <p><b>⚠️ 为什么单独一个配置类，而不是塞进 {@link RetentionConfig}</b>：
 * <ol>
 *   <li><b>保留天数来源不同</b>：本插件用前端可改的 {@code retention.days}
 *       （DB 单例配置），{@link RetentionConfig} 用 yml 的 {@code RetentionSettings}。
 *       两条窗口若共用一个 Bean，会出现「页面写 7 天、实际按 yml 的 90 天删」
 *       这类无法从任何单一位置解释的偏差。</li>
 *   <li><b>目录清理是全局的</b>：{@code {dir}/{date}/audit-*.json} 没有租户维度，
 *       塞进「按租户循环」的 {@code RetentionJob} 会被重复执行 N 次。</li>
 * </ol>
 * 公共部分仍然共用：<b>总开关同源</b>（{@code agent.retention.enabled}）、
 * <b>租户来源同源</b>（{@code agent.worker.tenant-slugs} → {@link TenantSlugResolver}）、
 * <b>轮询间隔与批量同源</b>（{@code agent.retention.interval-ms} / {@code batch-size}）。
 * 只要总开关关掉，两类数据一起不清理——不会出现「run_message 清了、
 * 对话审计原文没清」这种半履约状态。
 *
 * <p><b>⚠️ 清理凭据必须独立</b>：{@code 03_platform_roles.sql} 已
 * {@code REVOKE DELETE} 应用角色对 {@code chat_audit_record} 的删除权，
 * 因此库内清理只能走 {@code agent_retention} 数据源。
 * 未接入该数据源时本类<b>不伪造能力</b>：DB 端口传 null，
 * 由 {@link ChatAuditRetentionService} 打一条 ERROR 说明「库内原文不会被删除」，
 * 同时文件侧照常清理（另一条独立通道）。
 *
 * <p><b>⚠️ initMethod / destroyMethod 必须显式写</b>：Spring 不会猜你想调用
 * {@code start()}。漏掉的表现是「Bean 存在但不工作」——本项目已三次遇到。
 */
@Configuration
@ConditionalOnProperty(name = "agent.retention.enabled", havingValue = "true")
public class ChatAuditRetentionConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ChatAuditRetentionConfiguration.class);

    /**
     * 库内原文清理端口。
     *
     * <p>⚠️ 只在 {@code agent.retention.datasource.enabled=true} 时装配：
     * 没有清理凭据就用应用数据源去删，只会在到期那天得到 {@code permission denied}。
     * 宁可显式「未接入」，也不要一条注定失败的通道。
     */
    @Bean
    public ChatAuditRetentionPort chatAuditRetentionPort(
            @Autowired(required = false)
            @Qualifier(RetentionDataSourceConfiguration.BEAN_NAME)
            javax.sql.DataSource retentionDataSource) {
        if (retentionDataSource == null) {
            return null;
        }
        return new JdbcChatAuditRetentionRepository(retentionDataSource);
    }

    /** 目录清理端口（文件系统操作，实现在 infrastructure）。 */
    @Bean
    public ChatAuditDirectoryRetentionPort chatAuditDirectoryRetentionPort() {
        return new DirectoryChatAuditRetention();
    }

    @Bean
    public ChatAuditRetentionService chatAuditRetentionService(
            ObjectProvider<ChatAuditRetentionPort> retentionPort,
            ChatAuditDirectoryRetentionPort directoryPort,
            ChatAuditConfigPort configPort,
            ObjectProvider<ErasurePort> erasurePort,
            @Value("${agent.retention.actor}") String actor,
            @Value("${" + RetentionPolicy.KEY_BATCH_SIZE + "}") String batchSize) {
        ChatAuditRetentionPort db = retentionPort.getIfAvailable();
        if (db == null) {
            log.error("⚠️ 对话审计库内原文保留期清理未接入清理凭据"
                    + "（agent.retention.datasource.enabled=false）："
                    + "chat_audit_record 的原文不会被删除，文件侧仍会清理。"
                    + "该状态不是「配置项缺失」而是「合规履约缺口」，生产环境必须接入。");
        }
        return new ChatAuditRetentionService(
                db,
                directoryPort,
                () -> configPort.get().config(),
                erasurePort.getIfAvailable(),
                actor,
                RetentionPolicy.requireBatchSize(batchSize),
                Clock.systemUTC());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public ChatAuditRetentionJob chatAuditRetentionJob(
            ChatAuditRetentionService service,
            DataSource dataSource,
            @Value("${" + WorkerPolicy.KEY_TENANT_SLUGS + "}") String tenantSlugs,
            @Value("${" + PlatformConfig.KEY_PILOT_MAX_TENANTS + "}") String pilotMaxTenants,
            @Value("${" + RetentionPolicy.KEY_INTERVAL_MS + "}") String intervalMs) {
        // ⚠️ 与中继 / 保留期清理共用同一份租户清单：两套来源迟早不一致，
        //    而「清理漏掉某个租户」的表现是「原文超期留存」——违反 PIPL 且不报错。
        int maxTenants = PlatformConfig.Resolver.requirePositiveInt(
                pilotMaxTenants, PlatformConfig.KEY_PILOT_MAX_TENANTS);
        List<String> slugs = WorkerPolicy.requireTenantSlugs(tenantSlugs, maxTenants);
        List<String> tenantIds = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            tenantIds.add(TenantSlugResolver.resolve(dataSource, slug));
        }
        log.info("对话审计保留期清理租户清单: slugs={}, 租户数={}", slugs, tenantIds.size());
        return new ChatAuditRetentionJob(service, List.copyOf(tenantIds),
                Duration.ofMillis(RetentionPolicy.requireIntervalMs(intervalMs)));
    }
}

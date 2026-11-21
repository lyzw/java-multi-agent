package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import com.alibaba.druid.pool.DruidDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 应用主数据源（{@code app_agent} 身份）—— <b>显式注册，不交给自动配置猜</b>。
 *
 * <p>⚠️⚠️ <b>本类修的是一个 Sev-0 级既存缺陷（2026-11-08 RDS 实跑发现）</b>：
 * {@link RetentionDataSourceConfiguration} 注册了一个 {@code DataSource} 类型的
 * {@code retentionDataSource}（{@code agent_retention} 身份）。
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 带
 * {@code @ConditionalOnMissingBean(DataSource.class)} —— 配置类的 Bean 定义先于
 * 自动配置被处理，条件看到「已经有一个 DataSource」就<b>整段跳过</b>，
 * 于是容器里<b>只剩清理角色那一个池</b>。所有裸 {@code DataSource} 注入
 * （包括 {@code ApplicationConfiguration} 里几十个仓储）按类型解析
 * 全都静默拿到它 —— <b>全平台业务 SQL 一直在用 {@code agent_retention} 执行</b>。
 *
 * <p>该角色按 {@code deploy/sql/platform/04_retention_role.sql} 持有
 * {@code audit_record} / {@code erasure_certificate} 的 DELETE，
 * 也就是说「应用角色不能删审计」的设计被架空了：任何业务 SQL 都能删。
 * 因为 {@code agent_retention} 恰好也被授予了主要业务表的读写，
 * 功能长期「看起来正常」，<b>不报错、只是身份悄悄错</b>——
 * 正是本项目反复出现的失真形态。
 * 第一个暴露点是 P2 的 {@code platform.review_task}：该角色没被授这张新表，
 * 采集链路报 {@code permission denied for table review_task}，
 * 审查队列恒空而日志只有一条 WARN。
 *
 * <p><b>为什么 {@code @Primary}</b>：容器里会有两个 {@code DataSource}
 * （主池 + {@code retentionDataSource}）。保留期清理的注入点
 * 已用 {@code @Qualifier(RetentionDataSourceConfiguration.BEAN_NAME)} 按名取，
 * 不受影响；其余注入点（含 Spring Boot 内部的 JdbcTemplate /
 * DataSourceTransactionManager / Flyway）应一律落到主池。
 * 若不加 {@code @Primary}，那些按类型注入的自动配置类会
 * 抛 {@code NoUniqueBeanDefinitionException}。
 *
 * <p><b>fail-closed</b>：主 URL 缺失时拒绝启动。否则会退化成
 * 「又只剩 retention 一个池」的旧形态，且症状依然不报错。
 */
@Configuration
public class PrimaryDataSourceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PrimaryDataSourceConfiguration.class);

    /** Bean 名必须叫 {@code dataSource}：大量注入点靠参数名按名匹配。 */
    public static final String BEAN_NAME = "dataSource";

    @Bean(name = BEAN_NAME)
    @Primary
    public DataSource applicationDataSource(Environment environment) {
        String url = trimmed(environment.getProperty("spring.datasource.url"));
        String username = trimmed(environment.getProperty("spring.datasource.username"));
        String password = environment.getProperty("spring.datasource.password");

        if (url == null) {
            throw new IllegalStateException("应用主数据源未配置 spring.datasource.url。"
                    + "拒绝启动：没有主池时容器里只剩 agent_retention 连接池，"
                    + "会让全部业务 SQL 以清理角色执行（该角色可删审计记录）。");
        }

        String driver = trimmed(environment.getProperty("spring.datasource.driver-class-name"));
        // ⚠️ 与 application.yml 的 spring.datasource.druid.connection-init-sql 对齐：
        //    不设置 search_path 会让默认 schema 漂移，迁移脚本可能把表建到错误的域。
        String initSql = trimmed(environment.getProperty(
                "spring.datasource.druid.connection-init-sql"));
        int maxActive = environment.getProperty(
                "spring.datasource.druid.max-active", Integer.class, 10);
        int minIdle = environment.getProperty(
                "spring.datasource.druid.min-idle", Integer.class, 1);
        long keepAliveMs = environment.getProperty(
                "spring.datasource.druid.keep-alive-between-time-millis", Long.class,
                DruidPoolFactory.KEEP_ALIVE_MS);
        long minEvictableIdleMs = environment.getProperty(
                "spring.datasource.druid.min-evictable-idle-time-millis", Long.class,
                DruidPoolFactory.MIN_EVICTABLE_IDLE_MS);
        long maxEvictableIdleMs = environment.getProperty(
                "spring.datasource.druid.max-evictable-idle-time-millis", Long.class,
                DruidPoolFactory.MAX_EVICTABLE_IDLE_MS);

        DruidDataSource pool = DruidPoolFactory.create(
                "platform-primary", url, username, password, driver,
                new DruidPoolFactory.Tuning(maxActive, minIdle, keepAliveMs,
                        minEvictableIdleMs, maxEvictableIdleMs));
        DruidPoolFactory.applyConnectionInitSql(pool, initSql);

        log.info("应用主数据源已接入: user={}, pool={}", username, pool.getName());
        return pool;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}

package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

import cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider;
import cn.studykid.multiagent.infrastructure.persistence.mapper.OpsMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.RlsTenantBindingInterceptor;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantBoundTransactionFactory;

/**
 * MyBatis-Plus + RLS 双通道装配（W0/W1/W2，对应主文档 §3 / §10 / R7）。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时生效。
 *
 * <p><b>两条 SqlSessionFactory 隔离</b>（架构关键）：
 * <ul>
 *   <li><b>租户通道</b> {@code tenantSqlSessionFactory}：主数据源 +
 *       {@link TenantBoundTransactionFactory}（借外层连接）+
 *       {@link RlsTenantBindingInterceptor}（Executor 前置 set_config）+
 *       mapperLocations=classpath*:mapper/tenant/**。</li>
 *   <li><b>运维通道</b> {@code opsSqlSessionFactory}：BYPASSRLS 运维数据源 +
 *       JdbcTransactionFactory（自管连接，不走外层事务）+
 *       <b>不装 RLS 拦截器</b>（运维角色天然绕过 RLS，且平台域表无 tenant_id 列）+
 *       mapperLocations=classpath*:mapper/ops/**。</li>
 * </ul>
 *
 * <p>⚠️ <b>刻意不装分页插件</b>：游标分页（limit+1 + 全序）是自研实现；
 * 不启用 TenantLineInnerInterceptor（与 RLS 会话变量语义冲突）。
 */
@Configuration
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
@MapperScan(
        basePackages = {
                "cn.studykid.multiagent.infrastructure.persistence",
                "cn.studykid.multiagent.memory.persistence",
                "cn.studykid.multiagent.infrastructure.statistics"
        },
        // ⚠️ 平台域 ops Mapper 的 statement 只在 mapper/ops/**，不能绑到租户工厂；
        //    漏排除会在首次调用时抛 Invalid bound statement（编译期完全看不出来）。
        excludeFilters = @Filter(type = FilterType.ANNOTATION, classes = OpsMapper.class),
        sqlSessionFactoryRef = "tenantSqlSessionFactory")
public class MybatisRlsConfiguration {

    /** 租户通道 SqlSessionFactory：借外层事务连接 + RLS 绑定。 */
    @Bean(name = "tenantSqlSessionFactory")
    public SqlSessionFactory tenantSqlSessionFactory(
            @Qualifier("dataSource") DataSource dataSource) throws Exception {

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        // ★ 借外层 TransactionalConnectionHolder 的连接
        bean.setTransactionFactory(new TenantBoundTransactionFactory());
        // ★ 代码保证 RLS：每条 SQL 前绑定/校验 app.tenant_id
        bean.setPlugins(new Interceptor[]{ new RlsTenantBindingInterceptor() });
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/tenant/**/*.xml"));
        bean.setTypeAliasesPackage("cn.studykid.multiagent.infrastructure.persistence.cap.model");
        return bean.getObject();
    }

    /**
     * 运维通道 SqlSessionFactory：BYPASSRLS 运维数据源 + 不装 RLS 拦截器。
     *
     * <p>⚠️ 仅在 PlatformOpsDataSourceProvider 已接入运维角色时生效；
     * 未接入时 Bean 不创建（旧 Jdbc 平台域仓储继续工作）。
     */
    @Bean(name = "opsSqlSessionFactory")
    @ConditionalOnProperty(name = "agent.platform-ops.datasource.enabled", havingValue = "true")
    public SqlSessionFactory opsSqlSessionFactory(
            ObjectProvider<PlatformOpsDataSourceProvider> platformOpsProvider) throws Exception {

        PlatformOpsDataSourceProvider provider = platformOpsProvider.getIfAvailable();
        if (provider == null || !provider.isEnabled()) {
            // 运维角色未接入 → 不创建 opsSqlSessionFactory
            return null;
        }
        DataSource opsDs = provider.platformIdentityDataSource();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(opsDs);
        bean.setConfiguration(configuration);
        // ★ 运维连接自管事务（不借外层应用事务，避免跨数据源事务边界）
        bean.setTransactionFactory(new JdbcTransactionFactory());
        // ★ 刻意不装 RLS 拦截器（运维角色 BYPASSRLS，且平台域表无 tenant_id 列）
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/ops/**/*.xml"));
        return bean.getObject();
    }

    /**
     * 运维通道 Mapper 绑定：只扫描标注 {@link OpsMapper} 的接口。
     *
     * <p>⚠️ <b>必须双开关同时打开才扫描</b>（{@code persistence.mybatis.enabled=true} 且
     * {@code agent.platform-ops.datasource.enabled=true}），与 {@code opsSqlSessionFactory}
     * 的可用性严格同生共死。
     *
     * <p><b>为什么不能只靠外层类的 mybatis 条件</b>（一次真实启动失败）：
     * 本类是静态嵌套 {@code @Configuration}，{@code @MapperScan} 通过
     * {@code @Import(MapperScannerRegistrar)} 在配置类解析早期就注册 Mapper bean definition；
     * 外层类条件的否决发生在更晚的阶段，来不及阻止 registrar。于是当「运维开关开、MyBatis 开关关」
     * 时（例如仅配置了 AGENT_PLATFORM_OPS_ENABLED=true），外层工厂 {@code opsSqlSessionFactory}
     * 因 mybatis 关闭而不存在，ops Mapper 却被 registrar 注册并引用它，最终启动直接失败：
     * <blockquote>
     * Error creating bean with name 'dictionaryMapper': Cannot resolve reference to bean
     * 'opsSqlSessionFactory' while setting bean property 'sqlSessionFactory'
     * </blockquote>
     * 因此把 mybatis 条件显式重复在本类上，让 registrar 在解析阶段就被否决。
     * 对应适配层由 {@code ApplicationConfiguration} 退回 Jdbc 实现。
     */
    @Configuration
    @ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
            + "&& '${agent.platform-ops.datasource.enabled:false}'.equals('true')")
    @MapperScan(
            basePackages = "cn.studykid.multiagent.infrastructure.persistence.mapper",
            annotationClass = OpsMapper.class,
            sqlSessionFactoryRef = "opsSqlSessionFactory")
    static class OpsMapperScanConfiguration {
    }
}

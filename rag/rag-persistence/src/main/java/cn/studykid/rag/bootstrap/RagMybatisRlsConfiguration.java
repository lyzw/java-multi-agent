package cn.studykid.rag.bootstrap;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

import cn.studykid.rag.infrastructure.rls.RagRlsBindingInterceptor;

/**
 * RAG MyBatis-Plus + RLS 装配（W1 RAG 试点）。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 生效；
 * 与 platform 共用同一个开关，一起灰度/回滚。
 *
 * <p><b>为什么这里不需要自定义 TransactionFactory</b>（与 platform 不同）：
 * RAG 的管理面走 Spring {@code TransactionTemplate} + {@code PlatformTransactionManager}，
 * MyBatis 默认 {@code SpringManagedTransaction} 经 DataSourceUtils 拿到的就是事务内同一条连接，
 * RLS 会话绑定自然生效。platform 走显式 ThreadLocal 连接，才需要 TenantBoundTransactionFactory。
 *
 * <p>⚠️ RAG 当前处于 M3（RLS→应用层谓词）迁移中，行隔离权威在应用层显式谓词
 * （{@code rag_tenant} UUID 字面量 / RagScopeFilter），set_config 是纵深防御兼容层。
 * 本拦截器保持这一口径，不改变隔离语义。
 */
@Configuration
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
// ⚠️ 必须只扫描真正的 Mapper 接口：若扫整个 cn.studykid.rag，所有 *Port 接口
// 都会被注册成 Mapper 代理（如 knowledgeAliasPort），与显式 @Bean 的同名实现
// 竞争注入点，导致业务链路拿到无 statement 的代理。
@MapperScan(basePackages = "cn.studykid.rag.management.infrastructure.mapper",
        sqlSessionFactoryRef = "ragTenantSqlSessionFactory")
public class RagMybatisRlsConfiguration {

    @Bean(name = "ragTenantSqlSessionFactory")
    public SqlSessionFactory ragTenantSqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        // 默认 SpringManagedTransaction：与 TransactionTemplate 共享连接（RAG 场景正确）
        bean.setPlugins(new RagRlsBindingInterceptor());
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/tenant/rag/**/*.xml"));
        bean.setTypeAliasesPackage("cn.studykid.rag.management.infrastructure.model");
        return bean.getObject();
    }
}

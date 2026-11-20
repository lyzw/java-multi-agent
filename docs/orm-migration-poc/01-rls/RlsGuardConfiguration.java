package cn.studykid.multiagent.bootstrap.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import cn.studykid.multiagent.infrastructure.persistence.rls.RlsTenantBindingInterceptor;

/**
 * MyBatis-Plus + RLS 装配（POC，对应主文档 §3 / §10）。
 *
 * <p><b>双 SqlSessionFactory 隔离</b>（运维通道豁免的关键，见主文档 R7 / §10）：
 * <ul>
 *   <li><b>租户通道</b>（{@code tenantSqlSessionFactory}，@Primary）：装 {@link RlsTenantBindingInterceptor}，
 *       所有租户域 Mapper 走这里；</li>
 *   <li><b>运维通道</b>（{@code opsSqlSessionFactory}）：<b>不装 RLS 拦截器</b>，绑定
 *       BYPASSRLS 数据源（{@code platformOpsDataSource}），供跨租户运维查询。两者物理隔离，
 *       绝不共用 {@code SqlSessionFactory} / Mapper 包扫描。</li>
 * </ul>
 *
 * <p>⚠️ 本项目<b>不启用</b> {@code TenantLineInnerInterceptor}（它与 RLS 会话变量语义冲突，见主文档 2.2）；
 * 只启用分页（PG 方言，供新增后台列表用）与防全表更新插件。游标分页仍走自定义 XML。
 *
 * <p>⚠️ 版本：mybatis-plus-spring-boot4-starter 3.5.15+ / Spring Boot 4.1.1 / Jackson 3。
 */
@Configuration
public class RlsGuardConfiguration {

    /** 租户通道：主数据源 + RLS 拦截器（@Primary，普通注入默认拿到它）。 */
    @Bean(name = "tenantSqlSessionFactory")
    @Primary
    public SqlSessionFactory tenantSqlSessionFactory(
            @Qualifier("dataSource") DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor mp = new MybatisPlusInterceptor();
        mp.addInnerInterceptor(new PaginationInnerInterceptor(
                com.baomidou.mybatisplus.annotation.DbType.POSTGRESQL));
        mp.addInnerInterceptor(new BlockAttackInnerInterceptor());
        configuration.addInterceptor(mp);

        // ★ 代码保证 RLS 的核心拦截器
        configuration.addInterceptor(new RlsTenantBindingInterceptor());

        return buildFactory(dataSource, configuration, "classpath*:mapper/tenant/**/*.xml");
    }

    /**
     * 运维通道：BYPASSRLS 数据源，<b>无 RLS 拦截器</b>。
     *
     * <p>⚠️ 该工厂只被明确标注 {@code @Qualifier("opsSqlSessionFactory")} 的运维 Mapper 使用；
     * 运维 Mapper 放在独立包（如 {@code ...persistence.ops.mapper}），不与租户 Mapper 混扫。
     */
    @Bean(name = "opsSqlSessionFactory")
    public SqlSessionFactory opsSqlSessionFactory(
            @Qualifier("platformOpsDataSource") DataSource opsDataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 刻意不注册 RlsTenantBindingInterceptor —— 跨租户运维查询
        return buildFactory(opsDataSource, configuration, "classpath*:mapper/ops/**/*.xml");
    }

    private static SqlSessionFactory buildFactory(DataSource ds,
                                                   MybatisConfiguration configuration,
                                                   String mapperLocations) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(configuration);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources(mapperLocations));
        // 关闭 MP 自动填充审计（本项目用 DB now() 与现成审计钩子；如需 MetaObjectHandler 再启用）
        return bean.getObject();
    }
}

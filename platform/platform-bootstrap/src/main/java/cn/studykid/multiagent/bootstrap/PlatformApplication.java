package cn.studykid.multiagent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口（P1 工程基线）。
 *
 * <p>ADR-011：Spring Boot 4.1.1 + Java 21。
 */
// ⚠️ MP 自动配置在 application.yml 的 spring.autoconfigure.exclude 中排除：
// 它会无视 persistence.mybatis.enabled 自建 sqlSessionFactory 并扫描全部 mapper XML。
// 本项目的工厂只由 MybatisRlsConfiguration 按需装配（此处不 import ORM 具体类型，
// 以满足 DatabaseBoundaryTest 的业务模块不得依赖具体 ORM 的边界）。
@SpringBootApplication(scanBasePackages = "cn.studykid.multiagent")
@ConfigurationPropertiesScan("cn.studykid.multiagent")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

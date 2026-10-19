package cn.studykid.multiagent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口（P1 工程基线）。
 *
 * <p>ADR-011：Spring Boot 4.1.1 + Java 21。
 */
@SpringBootApplication(scanBasePackages = "cn.studykid.multiagent")
@ConfigurationPropertiesScan("cn.studykid.multiagent")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

package cn.studykid.rag.bootstrap.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * RAG 鉴权装配（评审 P0-2）。
 *
 * <p>把 {@link RagJwtAuthFilter} 注册为最高优先级过滤器，覆盖全部业务端点
 * （{@code /api/*}）。配置全部来自环境变量，密钥不入代码库：
 * <ul>
 *   <li>{@code RAG_TENANT_JWT_PUBLIC_KEY} / {@code RAG_PLATFORM_JWT_PUBLIC_KEY}：
 *       平台两个令牌域的 RS256 公钥（X.509 SPKI 的 Base64）；</li>
 *   <li>issuer/audience 缺省与平台 application.yml 的默认值对齐
 *       （tenant: agent-platform；platform: admin-console-api）；</li>
 *   <li>{@code RAG_SECURITY_ENABLED=false} 仅限本地开发的显式逃生门
 *       —— 启动即打 ERROR 横幅，默认 {@code true}（fail-closed）。</li>
 * </ul>
 */
@Configuration
public class RagSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(RagSecurityConfig.class);

    @Bean
    public FilterRegistrationBean<RagJwtAuthFilter> ragJwtAuthFilter(
            @Value("${rag.security.enabled:true}") boolean enabled,
            @Value("${rag.security.tenant.public-key:}") String tenantKey,
            @Value("${rag.security.tenant.issuer:https://auth.studykid.cn}") String tenantIssuer,
            @Value("${rag.security.tenant.audience:agent-platform}") String tenantAudience,
            @Value("${rag.security.platform.public-key:}") String platformKey,
            @Value("${rag.security.platform.issuer:https://auth.studykid.cn}") String platformIssuer,
            @Value("${rag.security.platform.audience:admin-console-api}") String platformAudience) {

        if (!enabled) {
            log.error("==================================================================");
            log.error("⛔⛔ RAG 鉴权已显式关闭（rag.security.enabled=false）。");
            log.error("⛔⛔ 8081 上的全部业务端点对任何能触达者裸奔——仅允许本地开发使用。");
            log.error("⛔⛔ 生产环境以该配置启动等同于把语料库挂到公网。");
            log.error("==================================================================");
        }

        List<RagJwtAuthFilter.TokenDomain> domains = new ArrayList<>();
        if (!tenantKey.isBlank()) {
            domains.add(RagJwtAuthFilter.parseKey("tenant", tenantKey,
                    tenantIssuer, tenantAudience));
        }
        if (!platformKey.isBlank()) {
            domains.add(RagJwtAuthFilter.parseKey("platform", platformKey,
                    platformIssuer, platformAudience));
        }
        RagJwtAuthFilter filter = new RagJwtAuthFilter(enabled, domains);

        FilterRegistrationBean<RagJwtAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("ragJwtAuthFilter");
        return reg;
    }
}

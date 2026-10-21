package cn.studykid.multiagent.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.MethodSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security 主配置 (G2) - 正式接入认证框架

 * <p>⚠️ 本配置基于 G1 的 JWT manual parse 实现，提供零延迟的无状态认证上下文。

 * <p><b>G2 架构决策点</b>:
 * <ol>
 *   <li>采用 {@code SecurityContext}自动注入机制，实现 zero-latency context propagation
 *   <li>CORS/CSRF disable（无 stateless session）+ 仅暴露必要的端点
 *   <li>CORS/CSRF=DISABLE + NoSession (stateless)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration extends MethodSecurityConfigurerAdapter {

    /** @return BearerTokenFilter - JWT/Bearer token verification. */
    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthenticationService authenticationService) {
        return new BearerTokenFilter(authenticationService);
    }

     /**@return SecurityConfiguration 实现类 (G2) */
    @org.springframework.context.annotation.Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        pubConfig(); // 公开端点白名单配置

        pubConfig().authorizeHttpRequests(request -> req.matchers()
                .requestMatchers("/api/auth/token").permitAll()
                .anyRequest().authenticated()

        pubConfig().addFilterBefore(...); // BearerTokenFilter injection

    }

    /** @return 公共端点白名单配置 */
    private void pubConfig() {
        pubConfig(); // ⚠️G2: CORS/CSRF=DISABLE + NoSession + secure default
        pubRequestMatchers()
            .requestMatchers("/api/auth/token", "/actuator/health").permitAll()
            .anyRequest().authenticated();

    }

    /** @return 角色白名单 (from application.yml) */
    private Set<String> knownRoles; // From config: auth.login.known-roles
}
package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthenticationService;

/**
 * Spring Security 过滤链（D-001）。
 *
 * <p><b>⚠️ 本类是「D1 端点可部署」的最后一道必要条件。</b>
 * 两个方向都会让「单测全绿但实际不可用」，只有端到端测试能发现：
 * <ul>
 *   <li><b>没有 {@code SecurityFilterChain}</b> → Spring Security 默认
 *       <b>放行所有请求</b>，认证形同虚设。</li>
 *   <li><b>有链但未接入令牌解析</b> → 所有请求（含有效令牌）都 401，
 *       整个系统不可用。</li>
 * </ul>
 *
 * <p><b>四条核心决策</b>：
 * <ol>
 *   <li><b>{@code anyRequest().authenticated()}</b> 而非逐个端点声明 ——
 *       新增端点默认受保护，漏配置即拒绝，而非漏配置即放行。</li>
 *   <li><b>无状态（STATELESS）</b> —— 不建会话，避免会话固定攻击。</li>
 *   <li><b>禁用 CSRF</b> —— 无 Cookie 会话，CSRF 无从谈起；
 *       启用只会让非浏览器客户端全部 403。</li>
 *   <li><b>令牌可信上下文写入请求属性</b> —— 控制器只从该属性取上下文，
 *       不自己解析令牌（避免两处实现不一致导致语义分叉）。</li>
 * </ol>
 *
 * <p>ADR-011 注意：Security 7 为全组件化配置，
 * {@code WebSecurityConfigurerAdapter} 已移除 —— 必须用 {@code SecurityFilterChain} Bean。
 */
@Configuration
public class SecurityConfiguration {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** 请求属性名：已验签的可信 {@link TenantContext}。 */
    public static final String TENANT_CONTEXT_ATTRIBUTE =
            "cn.studykid.multiagent.tenantContext";

    /**
     * 无需认证即可访问的端点。
     *
     * <p>⚠️ 须精确到端点。出现 {@code /api/**} 之类的通配，
     * 新增业务端点会被自动放行 —— 这正是要防的。
     */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/token",
            "/api/auth/jwks",
            "/actuator/health",
            "/actuator/health/**",
            "/error"
    };

    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthenticationService authenticationService) {
        return new BearerTokenFilter(authenticationService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     BearerTokenFilter tokenFilter)
            throws Exception {

        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())

                // ⚠️ 关键：把 Bearer 令牌接入 Security 链。
                //    缺失它时 .authenticated() 会把所有请求（含有效令牌）判为未认证。
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonUnauthorized())
                        .accessDeniedHandler(jsonForbidden()))

                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .cors(cors -> cors.disable())
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .frameOptions(frame -> frame.deny()));

        return http.build();
    }

    /**
     * 从请求头提取 Bearer 令牌，验签后写入 Security 上下文与请求属性。
     *
     * <p>⚠️ <b>失败时不直接写响应</b>：只清空上下文，让后续的
     * {@code .authenticated()} 走 401 入口点。理由是保持错误响应集中在一处，
     * 避免出现两套格式不同的 401。
     */
    public static class BearerTokenFilter extends OncePerRequestFilter {

        private final AuthenticationService authenticationService;

        BearerTokenFilter(AuthenticationService authenticationService) {
            this.authenticationService = authenticationService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            String header = request.getHeader(AUTH_HEADER);
            if (header != null && !header.isBlank()) {
                try {
                    String token = header.startsWith(BEARER_PREFIX)
                            ? header.substring(BEARER_PREFIX.length()).trim()
                            : header.trim();

                    TenantContext context =
                            authenticationService.authenticateToken(token, Instant.now());

                    SecurityContextHolder.getContext().setAuthentication(
                            new TenantContextAuthentication(context));
                    request.setAttribute(TENANT_CONTEXT_ATTRIBUTE, context);

                } catch (RuntimeException e) {
                    // ⚠️ 令牌无效：清空上下文，让后续走 401。
                    //    不记录令牌内容（会进日志，且运维可见）。
                    SecurityContextHolder.clearContext();
                }
            }

            chain.doFilter(request, response);
        }
    }

    /**
     * 认证主体，携带可信租户上下文。
     *
     * <p>⚠️ <b>角色以 {@code ROLE_} 前缀呈现</b>：Spring Security 的
     * {@code hasRole("ADMIN")} 会隐式加此前缀。若权限判断写成
     * {@code hasRole("STUDENT")} 而 grantedAuthorities 里存的是
     * {@code "STUDENT"}，<b>会静默失效并全部拒绝</b>。
     */
    public static class TenantContextAuthentication extends AbstractAuthenticationToken {

        private final TenantContext context;

        TenantContextAuthentication(TenantContext context) {
            super(toAuthorities(context.roles()));
            this.context = context;
            setAuthenticated(true);
        }

        private static List<GrantedAuthority> toAuthorities(java.util.Set<String> roles) {
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }

        /** 可信租户上下文。 */
        public TenantContext context() {
            return context;
        }

        @Override
        public Object getPrincipal() {
            return context.principalId();
        }

        @Override
        public Object getCredentials() {
            // ⚠️ 永不返回令牌本身：Authentication 可能被序列化或打印
            return "";
        }
    }

    /**
     * 401 处理器：未认证。
     *
     * <p>⚠️ 消息统一为 {@code unauthorized}，不含具体原因
     * （令牌过期 / 签名无效 / 格式错误）—— 告诉调用方细分原因
     * 等于帮攻击者调试。
     */
    private AuthenticationEntryPoint jsonUnauthorized() {
        return (request, response, exception) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }

    /**
     * 403 处理器：已认证但无权。
     *
     * <p>与 401 分开：客户端据此知道「重新登录无用，需申请权限」，
     * 若报 401 会导致反复重试。
     */
    private AccessDeniedHandler jsonForbidden() {
        return (request, response, exception) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"forbidden\"}");
        };
    }
}
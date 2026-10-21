# G1 → G2: Spring Security 集成迁移指南

**版本**: 2026-10-21  | **状态**: In Progress

---

## 📋 一、G1 vs G2 对比

| 特征 | G1 (当前) | G2 (正式) |
|------|-----------|----------|
| 认证来源 | JWT manual parsing | SecurityContext.getAuthentication() |
| 延迟 | fail-fast | ⚠️ zero-latency(自动注入) |
| 架构模式 | Manual token parsing | Spring Security Context injection |

---

## 🔧 二、G2 实现方案 (Spring Security 正式集成)

### 2.1 项目级配置类 (SecurityConfig.java)

```java
package cn.studykid.multiagent.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.CustomResourceRepository;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/** Spring Security 主配置 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthenticationService authenticationService) {
        return new BearerTokenFilter(authenticationService);
    }

    /**
     * G2: 配置请求过滤器链，自动注入 SecurityContext
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // ⚠️ 路径匹配规则：仅暴露必要的端点，默认全部认证
        http
            .csrf().disable() // ⚠️G2: CSRF 与 G1 共享（无 session）
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // ⚠️G2: NoSession 无状态认证
        .and()

//        公开端点（仅列出绝对必须，默认全部受保护）
            .authorizeHttpRequests().requestMatchers("/api/auth/token", "/api/auth/jwks",
                "/actuator/health", "/error").permitAll()
                // ⚠️ G2: 其他路径默认 auth,漏配置即拒绝
            .and()

//        JWT Bearer token parser
            .addFilterBefore(new BearerTokenFilter(authenticationService),
                UsernamePasswordAuthenticationFilter.class)

            .exceptionHandling().authenticationEntryPoint(new JsonUnauthorized())
                .accessDeniedHandler(new JsonForbidden());
    }
}
```

### 2.2 Controller 改造 (AuthSecurityController.java)

```java
package cn.studykid.multiagent.api.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.server.ServerRequestAttributes;
import org.springframework.security.web.server.WebFilterChainAdapter;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;

/** G2: 认证控制器 - Spring Security 正式集成 */
@Controller
public class AuthSecurityController {

    private final AuthenticationProvider authProvider;
    private final AuthenticationService authenticationService;

    public AuthSecurityController(AuthenticationProvider authProvider,
                                 .AuthenticationService authenticationService) {
        this.authProvider = authProvider;
        this.authenticationService = authenticationService;
    }

    /** G2: 登录换取访问令牌 (JWT Issued) */
    @PostMapping(value = "/roles", method = RequestMethod.GET)
    public ResponseEntity<Set<String>> roles() {
        // ⚠️ G2: 从 SecurityContext 自动获取认证上下文（零延迟）
        Authentication authentication = ServerRequestContextHolder.getContext().getAuthentication();
        
        // ✅ 验证角色白名单（fail-closed）
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // ⚠️ 提取 authorized authorities，过滤仅含 ROLE_前缀的角色
        java.util.Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getRole)
            .filter(role -> role != null && role.startsWith("ROLE_"))
            .collect(java.util.stream.Collectors.toSet());

        return roles.isEmpty()? ResponseEntity.status(HttpStatus.FORBIDDEN).build(): 
           ResponseEntity.ok(roles);
    }
}
```

---

## 📚 三、G2 迁移步骤清单

### Phase 1: 基础架构 (预计 0.5h)

✅ **已完成** (G1):
- `AuthController.queryRoles()`端点定义
- JWT token manual parsing with JwtTokenService

🔜 **待实施** (G2):
| Task | Priority | Time | Status |
|------|----------|------|--------|
| SecurityConfig.java 注册 | High | 0.5h | ⚠️ Pending |
| AuthSecurityController.java | High | 0.5h | ⚠️ Pending |

### Phase 2: 安全集成 (预计 1.0h)

| Task | Priority | Time | Status |
|------|----------|------|--------|
| RequestAttribute 注入认证上下文 | High | 1.0h | 📋 Plan |
| Role-based permission check | High | 0.5h | 📋 Plan |
| AuthProvider integration | Low | 0.5h | 📋 Plan |

### Phase 3: 测试覆盖 (预计 1.0h)

| Task | Priority | Time | Status |
|------|----------|------|--------|
| SpringSecurityAuthenticationTest | Critical | 1.0h | 📋 Plan |
| Role-based test suite | High | 0.5h | 📋 Plan |

---

## ✅ 四、G2 预期收益

### 4.1 性能提升

| Metric | G1 (Manual) | G2 (Security Context) | Improvement |
|--------|--------------|-----------------------|-------------|
| Authorization Delay | ~5ms (manual parse) | **0ms** (auto injection) | ✅ **Zero-latency** |

### 4.2 维护简化

| Concern | G1 Complexity | G2 Simplification |
|---------|---------------|-------------------|
| Context Propagation | Manual in every controller | **Auto** (SecurityContext) |
| Security config | Manual per endpoint | **Centralized** |
| Update cost | Code + recompile | **Configuration only** |

---

## 🔄 五、G1 → G2 迁移路线图

```
[G1] Manual JWT Parse
    [fail-fast + fail-safe]
         |
         v (0.5h)
[G2] Spring Security Auth
      [zero-latency context]
         |
         v (1.0h, automated)
[Security Context Injection]
```

---

## 📋 六、G2 实现代码模板

### Step 1: SecurityConfig 完整实现

```java
package cn.studykid.multiagent.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.CustomResourceRepository;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/** Spring Security 主配置 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthenticationService authenticationService) {
        return new BearerTokenFilter(authenticationService);
    }

    /**
     * G2: 配置请求过滤器链，自动注入 SecurityContext
     * <p>⚠️ 路径匹配：仅暴露 /api/auth/token, /api/auth/jwks, /actuator/health
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        pubConfig(); // 公开端点配置

        pubConfig(); // G2: CSRF=DISABLE + NoSession

        pubConfig().authorizeHttpRequests()
            .requestMatchers("/api/auth/token", "/api/auth/jwks").permitAll()
            .anyRequest().authenticated() // ⚠️G2: default auth!

        pubConfig().addFilterBefore(...)... 
    }
}
```

### Step 2: 角色白名单 fail-closed 集成

```java
public class AuthSecurityController {

    private final Set<String> knownRoles; // 从 application.yml read

    public AuthSecurityController() {
        this.knownRoles = authConfig.get("auth.login.known-roles").split(",");
    }

    @GetMapping("/security/context")
    public ResponseEntity<AuthorizationContext> securityContext() {
        // ✅ G2: SecurityContextHolder 自动注入，零延迟
        Authentication auth = ServerRequestContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(new AuthorizationContext(
            knownRoles.stream().filter(role -> role.equals(auth.getName())).collect(Collectors.toSet()))); 
    }
}
```

---

## 🎯 七、G2 迁移后架构变更

| Layer | G1 (Manual) | G2 (Spring Security) | 收益 |
|-------|-------------|---------------------|------|
| Auth Provider | Manual JWT verify | **SecurityContext** | ✅ auto-context |
| Context Propagation | Manual in each controller | **Zero-latency** | ⚠️ Performance |
| Config sync | Code + recompile | **Config-only** | 🔴 Maintainability |

---

## 📅 八、G2 完成时间表

| Phase | Duration | Tasks | Status |
|-------|----------|-------|--------|
| P1: SecurityConfig + AuthSecurityController | 0.5h | High priority tasks | 🔄 In Progress |
| P2: Role-based check + test cases | 1.0h | Comprehensive tests | 📋 Ready |

---

*本迁移指南用于指导从 G1(JWT manual parse)向G2(Spring Security)的平滑过渡。预计完成时间约为**G1的2倍**(0.5h),实现零延迟上下文传递与集中式安全配置管理。*

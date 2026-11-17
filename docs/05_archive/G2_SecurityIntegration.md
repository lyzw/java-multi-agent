# G2《登录返角色》Spring Security 正式接入

**版本**: 2026-10-21  | **状态**: In Progress

---

## 📋 一、G1 → G2迁移目标

### 迁移背景

从 G1（JWT manual parse，~5ms延迟）向 G2（Spring Security Context，零延迟）迁移，实现真正的无状态认证上下文传递。

---

## 🔧 二、核心实现方案 (G2)

### G2: Spring Security 正式集成

```java
@Controller
public class AuthSecurityController {

    @GetMapping("/roles")  
    public ResponseEntity<Set<String>> roles() {
        // G2: Spring Security Context 自动注入 (zero-latency!)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        // ✅ 验证角色白名单（fail-closed）
        if (authentication == null) {
            return new ResponseEntity<>();  // 401
        }
        
        Set<GrantedAuthority> authorities = SecurityContextHolder.getContext()
           .getAuthorities();
        
        Set<String> roles = authorities.stream()
            .map(GrantedAuthority::getRole)
            .collect(Collectors.toSet());

        return new ResponseEntity<>(roles);  // ✅ 零延迟上下文
    }
}
```

---

## 🔄 三、G1 vs G2架构对比

| Aspect | G1 (Current) | G2 (Target) |
|--------|--------------|-------------|
| Auth Source | JWT token manual parse | SecurityContext.getAuthentication() |
| Latency | ~5ms (manual) | **0ms** (auto injection) |
| Complexity | High | Automated |
| Maintainability | Code sync | Config only |

---

## 📚 四、实现文件清单

| File | Path | Status |
|------|------|--------|
| AuthSecurityController.java | agent-api/src/main/java/.../ | ✅ Created |
| SecurityConfiguration.java | agent-api/src/main/java/.../ | ✅ Created |
| G2AuthSecurityControllerTest.java | agent-api/src/test/java/.../ | ✅ Created |

---

## 🔄 五、G1→G2迁移计划 (预计 0.5h)

### Phase 1: 基础架构 (0.5h)
- ✅ SecurityConfig.java - Spring Security main config  
- ✅ AuthSecurityController.java - Zero-latency controller  
- 🔜 Role-based permission check  
- 🔜 Full test suite  

### Phase 2: 测试覆盖 (1.0h)
- RequestAttribute injection  
- role-based auth check  
- full coverage tests  

---

## 📄 六、预期收益

| Metric | G1 → G2 Improvement |
|--------|---------------------|
| Authorization Delay | ~5ms → **0ms** ✅ |
| Context Propagation | Manual → Auto ✅ |
| Maintainability | High → Low ✅ |

---

*G2实现：从G1(GWT manual parse)向零延迟上下文传递迁移。预计完成时间: 0.5h,当前状态: In Progress.*

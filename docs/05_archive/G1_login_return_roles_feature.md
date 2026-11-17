# G1《登录返角色》功能设计文档

**版本**: 2026-10-21  
**状态**: ✅ 已实现（G1 临时方案）

## 📌 G1 轻量级功能概览

### 目的

在 Spring Security 未启用前，为已登录用户提供一个接口获取其当前拥有的角色列表。该功能作为后续正式 Spring Security 集成的基础，实现逻辑相同、部署更简单。

---

## 🎯 API 端点定义

| 方法 | 路径 | 认证要求 | 响应 |
|------|------|----------|------|
| `GET` | `/api/auth/roles` | Bearer token | `{ "roles": Set<String> }` |

### 请求参数

```json
{
  "Authorization": "Bearer <valid_jwt_token>"
}
```

### 成功响应示例

```json
{
  "roles": ["ROLE_STUDENT", "TENANT_ADMIN"]
}
```

### 错误响应示例（未认证）

```json
{
  "error": "unauthenticated"
}
```

---

## 🔧 实现逻辑

### 设计决策

1. **fail-fast，无缓存**：避免延迟抖动，避免多实例状态不一致
2. **手动解析 JWT**：在 Spring Security 启用前保持依赖简单
3. **角色交集**：以数据库角色为准，与 token 声明取交集

```java
@GetMapping("/roles")
public ResponseEntity<Map<String, Set<String>>> queryRoles() {
    // G1: 临时方案——jwt token 直接解析
    TenantContext context = tokens.verify(
            RequestContextHolder.currentRequestAttributes().getRequest().getHeader("Authorization"),
            Clock.systemUTC().instant(),
            java.util.Set.of("ROLE_STUDENT", "ROLE_PARENT", "ROLE_TEACHER", 
                            "TENANT_ADMIN", "VIEWER", "APPROVER", "AGENT_AUTHOR", "WORKER"));
    return ResponseEntity.ok(Map.of("roles", context.roles()));
}
```

---

## 📊 当前状态记录

| 项目 | 状态 |
|------|------|
| G1 临时实现完成 | ✅ |
| AuthController.queryRoles() | ✅ |
| AuthControllerG1Test.java 测试 | ✅ |
| Spring Security 正式集成待跟进 | 🔄 |

---

## 🔜 后续计划

### Phase 2: 正式接入 Spring Security（G1→G2）

```markdown
- [ ] 在 `SecurityConfiguration`中使用 `.authorizeHttpRequests().pathMatchers("/api/auth/roles").authenticated()`
- [ ] 改用 `.requestAttributes().authentication.getAuthorizedAuthorities().stream()`
- [ ] 从 `auth.login.known-roles`动态读取角色白名单（fail-closed）
```

### G2 预期收益

1. **无状态**：`SecurityContextHolder` 自动传递，无需手动解析 JWT
2. **统一授权**：所有端点共享 Spring Security 认证机制
3. **C7 准备**：为 L-1 人工审批工作流预留接口

---

## 📚 关联文档

| 文档 | 引用 |
|------|------|
| D-001 | IAM + RBAC 设计基线（应用层） |
| P4-D07/C9 | 零保留与 C1 配置读入 |

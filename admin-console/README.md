# 平台管理控制台（admin-console）

**纯平台控制台**：平台用户与角色管理、租户生命周期、平台级能力对象（**Agent / Tool / Skill / MCP**）的
登记-审批-发布管控、平台审计。登录走平台身份域（`aud=admin-console-api`）、令牌层与租户身份互拒：
本工程**只持平台令牌**，一切端点走 `/api/platform/v1/*` 平台链。

> 设计依据：《重规划/平台身份与租户身份分离改造方案.md》（§10-§14 · 平台身份域）、
> `docs/管理后台系统设计方案.html`、《管理后台开发计划》（`docs/管理后台开发计划/`）。

---

## 1. 部署形态（用户决策）

- **路径部署**：控制台挂载在 **`/admin`** 路径下。
  - Vite `base: '/admin/'`，Router `basename: '/admin'`。
  - 构建产物 `dist/` 由 Nginx 的 `/admin/` location 提供。
- **同源反向代理**：生产由 Nginx 将 `/api/*` 转发到共享后端 `platform/agent-api`（与现有前端一致）。
  - 后端 `SecurityConfiguration` 已 `cors.disable()`，故**不走 CORS**，统一走同源代理。
  - 本地开发用 Vite devServer proxy 做同源代理（见 §4）。

### Nginx 示例（生产）

```nginx
server {
    listen 80;
    server_name your-domain;

    # 控制台静态资源（路径部署）
    location /admin/ {
        alias /path/to/admin-console/dist/;
        try_files $uri $uri/ /admin/index.html;
    }

    # 共享后端（同源反向代理）
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:8080;
    }
}
```

---

## 2. 技术栈

- React 18 + TypeScript 5 + Vite 6
- 路由：`react-router-dom` v7（数据路由 `createBrowserRouter`，`basename: '/admin'`）
- UI 框架：**Ant Design v5**（管理后台 UI 框架）+ `@ant-design/icons`
- 状态：React Context（SessionProvider）；后续按需引入 Zustand / React Query
- 测试：Vitest + Testing Library（jsdom，单进程 `singleFork` 防 OOM）

> 与现有 `frontend/`（学生端）**解耦**：本工程是独立 `package.json` / 独立构建 / 独立部署，仅**搬运**其设计令牌与鉴权契约，不跨工程 import 源码。

---

## 3. 导航与路由（纯平台身份域 + 平台能力目录）

| 菜单 | 路由 | 数据源端点 | 说明 |
|---|---|---|---|
| 平台用户 | `/users` | `GET/POST /api/platform/v1/users`、`PUT …/{id}`、`PUT …/{id}/password`、`POST/DELETE …/{id}/roles[/{role}]` | **可写**：建号 / 启停 / 重置密码 / 赋撤角色 |
| 角色权限矩阵 | `/roles` | `GET /api/platform/v1/roles` | **只读冻结矩阵**（后端 `PlatformRolePermissionMatrix`，6 角色 23 权限），按命名空间 Tabs（user/role/tenant/capability/resource/policy/audit/support） |
| Agent 注册表 | `/agents` | `GET /api/platform/v1/capabilities/agents` | **只读盘点**：跨租户元数据（BYPASSRLS 数据源 + 平台运营会话窗），按租户 Tabs；未接入会话时 503 fail-closed |
| Tool | `/tools` | `GET /api/platform/v1/capabilities/tools` + 三步写端点 | 生命周期 Tabs（全部/已发布/草稿/已停用）+ 申请队列 + 登记 + 停用申请 |
| Skill | `/skills` | `…/capabilities/skills` | 同上（含 Tool/MCP 闭包声明） |
| MCP Server | `/mcps` | `…/capabilities/mcps` | 同上（transport 列；凭据定位符**永不回显**） |
| 租户管理 | `/tenants` | `/api/platform/v1/tenants*` | 租户生命周期 / 配额 / 能力授权 |
| 平台审计 | `/audit` | `GET /api/platform/v1/audit-events` | 仅 SUPER_ADMIN / AUDITOR 可见 |

后台的旧租户链端点（`/api/cap/*`、`/api/admin/*`）对平台令牌不可达；原租户域页面文件暂留仓库但已不在路由中。

---

## 4. 本地开发

```bash
# 安装（已用受管 Node 完成；用户侧用本机 node 即可）
npm install

# 启动开发服务器（端口 5180，/api 同源代理到 127.0.0.1:8080）
npm run dev
# 浏览器打开 http://localhost:5180/admin

# 覆盖后端地址（可选）
VITE_DEV_API_TARGET=http://127.0.0.1:8080 npm run dev

# 类型检查 + 生产构建
npm run build

# 预览构建产物（验证路径部署）
npm run preview

# 单元测试
npm test
```

> 注意：类型检查/构建/测试在受限内存的沙箱中需用后台方式运行（前台可能被资源策略 SIGTERM）。
> 本机正常环境直接 `npm run build` 即可。

---

## 5. 鉴权约定（平台身份域）

- 登录：`POST /api/platform/v1/auth/login`（平台用户名/密码），返回 `accessToken / userId / roles`；
  ⛔ 无 tenantId —— 平台主体不属于任何租户；失败统一 401（防账号枚举）。
- 令牌：存 `sessionStorage['admin-console.platform.session']`（独立键）；**无 refresh token**，到期硬跳登录。
- 平台 JWT 验签**无状态**（不查库）⇒ 角色变更至多在一个令牌周期（900s）内生效；
  上下文里的 `authz_version` 失效可提前拒绝（fail-closed）。
- 6 个最小特权角色（`PLATFORM_SUPER_ADMIN` 等，与后端 `PlatformRoles` / known-roles 一致）。
- 角色用途：**仅前端导航显隐**。真实权限由后端 `PlatformSecurity.require(...)
  _（默认拒绝、矩阵驱动）`在每次写请求强制；前端不做安全闸门。
- ❗ **SoD / 自身保护**（后端强制，前端如实呈现）：
  - 授予/撤销 `PLATFORM_SUPER_ADMIN` 仅超管本人可操作；
  - 不可对自己执行 停用/重置密码/角色增删；
  - 撤销最后一个超管被拒（409）。用户页对上述场景禁用对应按钮并注明原因。

---

## 6. 平台能力目录（/api/platform/v1/capabilities/*）

复用 C34-2 **三步工作流**（`PlatformCapabilityPublishService`）：**申请 → 审批 → 执行**，
执行那一步才真正写配置表。Tool / Skill / MCP 三类对象同一模板：

1. **登记**：目录页「登记」→ `PlatformRegistrationModal`（构造 `PlatformPublishRequest`，提交即产生 PENDING 申请；
   注意闭包自洽：Tool kind=MCP 或 Skill 的声明会**连带重发被引用对象**；
   凭据定位符目录接口不回显 ⇒ 重发必须显式重填，前端 fail-closed 拦截「静默丢凭据」）。
2. **审批/执行**：页头「申请队列」抽屉列出变更申请（PENDING 可「通过 / 执行」）。
3. **停用**：已发布行的「停用」→ `PlatformDeprecateModal`，构造 `PlatformLifecycleRequest`
   （`expectedFrom:'PUBLISHED'`、`to:'DEPRECATED'`，单向终态，不做物理删除）。

**Agent 注册表是只读红线**：跨租户盘点只回管理元数据（租户/标识/名称/版本/角色/技能/能力标签等），
定义正文与运行输出**不出平台边界**，平台不提供任何 Agent 写端点。

**目录数据为空 / 503 / 403** 的呈现：页头 Alert 区分三种失败
（权限不足 403；平台运营会话窗未接入 503 —— 平台治理会话以 `platform-ops` 显式开启，不回落，绝不静默）。

---

## 7. 已知事项

- 打包体积：antd 全量引入导致主包 ~1.3MB（gzip ~409KB），后续可按路由 `import()` 拆包（属优化，非阻塞）。
- **导航相对路径红线**：`navigate` / `<Navigate to>` 的目标是 basename **相对**路径
  （Router 的 basename 已是 `/admin`）⇒ 写 `${APP_BASE_PATH}/xxx` 会拼出 `/admin/admin/xxx` ⇒ 404。
  回归测试见 `tests/loginRedirect.test.tsx`。
- 「状态」列合并 `lifecycleState` 与 `published`（`lifecycleStateCell`）——
  ⚠️ 停用是**单向终态**，只看 `published` 会把已停用对象显示成「已发布」。
- MCP 的「凭据引用」只给布尔 `hasCredentialRef`，定位符与凭据材料**永不入 DOM**。
- 平台令牌读写端点的前端错误兜底：后端 `ApiError(status, message)` 已归一，页面按 403/400/503 分文案。

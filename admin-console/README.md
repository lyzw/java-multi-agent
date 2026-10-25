# Agent 管理控制台（admin-console）

统一管理整个工程中 **Agent / Tool / Skill / MCP / Tenant** 配置与运行状态的**独立前端工程**。
后端复用既有共享项目 `platform/agent-api`（不新建后端工程），仅在其内按需扩展只读聚合/查询端点，
以及**平台级能力对象的写端点**（第三十四批 C34-2 起：`/api/cap/platform/*`）。

> 设计依据：`docs/管理后台系统设计方案.html`、《管理后台开发计划》（`docs/管理后台开发计划/`）。

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

## 3. 目录结构

```
admin-console/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts            # base=/admin/ + 同源 /api 代理
├── src/
│   ├── config/constants.ts    # 基路径、API 前缀、角色常量、作用域角色组
│   ├── auth/
│   │   ├── tokenStorage.ts    # sessionStorage 令牌存取（复用既有契约）
│   │   ├── session.tsx        # SessionProvider（signIn/signOut/refreshRoles）
│   │   ├── RequireAuth.tsx    # 未登录 → 跳登录
│   │   └── RequireRole.tsx    # 角色守卫（仅导航显隐，安全以后端重授权为准）
│   ├── api/
│   │   ├── types.ts           # 后端契约类型
│   │   ├── client.ts          # fetch 封装：令牌注入 + 401 兜底 + 错误归一
│   │   └── adminApi.ts        # login/getRoles/getDashboard（真实）+ 占位
│   ├── layout/
│   │   ├── navConfig.tsx      # 导航树（与设计方案 §4 对齐）
│   │   └── AdminLayout.tsx    # antd 侧栏 + 顶栏 + 路由出口
│   ├── pages/
│   │   ├── LoginPage.tsx
│   │   ├── DashboardPage.tsx  # 对接 /api/auth/roles 展示角色 + 模块卡片
│   │   └── PlaceholderPage.tsx# 后续阶段模块占位（"规划中"）
│   ├── App.tsx                # ConfigProvider(zhCN) + AntApp + Session + Router
│   ├── main.tsx
│   └── styles/global.css
└── tests/                     # Vitest
```

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

## 5. 鉴权约定（复用共享后端）

- 登录：`POST /api/auth/token`，请求体 `{ tenantId, loginName, password }`，返回 `accessToken / expiresIn / tenantId / principalId / roles?`。
- 角色回补：`GET /api/auth/roles`（登录响应缺 `roles` 时前端自动回补，幂等）。
- 令牌：存 `sessionStorage['admin.session']`（独立键，与 `frontend/` 隔离）；**无 refresh token**，到期硬跳登录。
- 角色用途：**仅前端导航显隐**。真实权限由后端在每次请求时重新授权（红线 9），前端不做安全闸门。
  ⚠️ 第三十四批起前端**多了一条「平台级写入口」**（Tool / Skill / MCP 的登记 / 改版 / 停用），
  但**前端仍然不做安全闸门** —— `PLATFORM_ADMIN` 闸门与平台运维通道由后端强制
  （403 默认拒绝 / 运维通道未接入即 503，绝不回落应用角色）。前端只负责**呈现**闸门结论。
- 作用域：平台级（租户管理、配置中心、**平台级能力对象的写入口**）需 `SUPER_ADMIN/PLATFORM_ADMIN`；租户级（Agent/Tool/Skill/MCP、发布、监控、审计）需含 `TENANT_ADMIN`。

---

## 6. 与《管理后台开发计划》的映射

| 计划阶段 | 本工程落地情况 |
|---------|--------------|
| P0 脚手架 | ✅ 本工程即 P0 产出：`/admin` 路由骨架、AdminLayout、RequireAuth/RequireRole、adminApi、Dashboard |
| P0.5 租户管理 | ⏳ 路由占位 `/tenants`（RequireRole 平台作用域），后端端点后续接入 |
| P1 实体管理 | ⏳ `/agents` `/tools` `/skills` `/mcp` 占位（RequireRole 租户作用域） |
| P2 发布审批 | ⏳ `/publish` 占位 |
| P3 关系视图 | ⏳ `/dependencies` 占位 |
| P4 运行监控 | ⏳ `/monitoring` 占位 |
| P5 审计配置 | ⏳ `/audit` `/settings` 占位 |

后端聚合端点（如 `GET /api/admin/dashboard`）由后续阶段在 `platform/agent-api` 内实现；当前 Dashboard 在其缺失时优雅降级为空态。

---

## 7. 已知事项

- 打包体积：antd 全量引入导致主包 ~892KB（gzip 287KB），后续可按路由 `import()` 拆包或配置 `manualChunks`（属优化，非阻塞）。
- `getDashboard` 端点尚未在后端实现，Dashboard 统计卡在后端就绪前不展示数字（已做空态降级）。
- 角色常量（`SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN`）以字符串与后端登记值对齐；若后端实际角色名不同，仅需调整 `src/config/constants.ts`。

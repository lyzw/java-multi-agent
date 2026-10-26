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

## 6. Agent 管理（新增 / 修改 / 详情 · 全字段）

Agent 是**租户级**对象，走应用层 `/api/agents` + `/api/cap/config/publishes`（三步工作流）。

| 入口 | 路由 | 说明 |
|---|---|---|
| 列表 | `/admin/agents` | 名称 / Agent 标识 / 版本 / 最近发布；行内「编辑」「详情」 |
| **新增** | `/admin/agents/new` | `new` 是**保留路由段**（同平台级 `NEW_OBJECT_SEGMENT`） |
| **详情** | `/admin/agents/{id}` | 页签：概览 / 编辑 / 版本历史 / 依赖 / 委派拓扑 / 发布与生命周期 |
| **修改** | `/admin/agents/{id}?tab=edit` | 与新增**共用同一张草稿表单** |

**「所有 Agent 信息」= 两个模型的全集**（概览页**并列**呈现，不合并成一份）：

- **租户实体行**（`agent` 表 / `GET /api/agents/{id}`）：标识 / 名称 / 描述 / 版本数 / 当前版本号 / 最近发布 / 版本快照；
- **多级定义**（`cap_agent_def` 表 / `GET /api/cap/agents/{id}/definition`）：定义版本 / 发布状态 / 名称 / 描述 /
  **角色 / 启用技能 / 技能包 / 能力标签 / 收紧覆盖（overrides）/ 编排声明（orchestration）**。

⚠️ **三处必须守住的约定**（改动本页时勿破坏）：

1. **新增与修改共用一张草稿表单**（`src/pages/agents/AgentDraftPanel.tsx`）——
   分两张表单迟早漂移；`new` 必须是 `:id` 参数里的**保留段**（静态路由 `agents/new`
   取不到 `:id` ⇒ `useParams()` 拿不到 `'new'` ⇒ 登记分支永不触发）。
2. **回填只认权威定义**（`cap_agent_def`）——`overrides` 取自它的 `overrides` 字段，
   **不是**旧体系 `latestDefinition` 全文（语义完全错位）。
   ⚠️ **取不到权威定义时绝不静默清空关系**：显式告警 + 用户显式确认才放行。
3. **登记的顺序不可颠倒**：`cap_agent_def` 对 `agent` 表有复合外键 ⇒ 必须先有 Agent 行；
   但**校验必须在建行之前**完成，否则会留下一个「没有多级定义的孤儿 Agent 行」。

---

## 7. 与《管理后台开发计划》的映射

| 计划阶段 | 本工程落地情况 |
|---------|--------------|
| P0 脚手架 | ✅ 本工程即 P0 产出：`/admin` 路由骨架、AdminLayout、RequireAuth、RequireRole、adminApi、Dashboard |
| P0.5 租户管理 | ⏳ 路由占位 `/tenants`（RequireRole 平台作用域），后端端点后续接入 |
| P1 实体管理 | ✅ `/agents` **全字段**（新增 / 修改 / 详情，见 §6）；`/tools` `/skills` `/mcp` 目录页已接入 |
| P2 发布审批 | ⏳ `/publish` 占位 |
| P3 关系视图 | ⏳ `/dependencies` 占位 |
| P4 运行监控 | ⏳ `/monitoring` 占位 |
| P5 审计配置 | ⏳ `/audit` `/settings` 占位 |

后端聚合端点（如 `GET /api/admin/dashboard`）由后续阶段在 `platform/agent-api` 内实现；当前 Dashboard 在其缺失时优雅降级为空态。

---

## 8. 已知事项

- 打包体积：antd 全量引入导致主包 ~892KB（gzip 287KB），后续可按路由 `import()` 拆包或配置 `manualChunks`（属优化，非阻塞）。
- `getDashboard` 端点尚未在后端实现，Dashboard 统计卡在后端就绪前不展示数字（已做空态降级）。
- 角色常量（`SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN`）以字符串与后端登记值对齐；若后端实际角色名不同，仅需调整 `src/config/constants.ts`。
- ⚠️ **`navigate` / `<Navigate to>` 的目标是 basename **相对**路径**（Router 的 basename 已是 `/admin`）：
  写成 `${APP_BASE_PATH}/xxx` 会被**再拼一次**前缀 ⇒ `/admin/admin/xxx` ⇒ 404。
  涉及 `RequireAuth`（跳登录页）与 `LoginPage`（登录后回跳）；回归测试见 `tests/loginRedirect.test.tsx`。
- **Tool 管理（第三十六批 · C36-1）**：登记 / 修改 / 详情三处**共用** `src/pages/catalog/ToolDefinitionView.tsx`
  呈现**全部** Tool 字段（定义字段 + 版本与生命周期元信息）。其中 `configFingerprint` / `createdAt`
  依赖后端 `ToolResponse` 回显（后端已补齐）；**老后端不回显时前端按「未提供」呈现，不得臆造**。
- **三个目录列表（第三十六批 · C36-1）**：Tool / Skill / MCP Server 目录同步补列，
  且「状态」列**合并** `lifecycleState` 与 `published`（`lifecycleStateCell`）——
  ⚠️ 停用是**单向终态**，只看 `published` 会把**已停用**的对象显示成「已发布」。
  MCP 目录的「凭据引用」列只给**布尔**，定位符与凭据材料**永不入响应**。

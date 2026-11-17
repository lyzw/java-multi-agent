# FE-D1 — F1–F3 实现设计（登录 / 发起 / 进度）

| 字段 | 内容 |
|---|---|
| 文档编号 | FE-D1 |
| 版本/状态 | v1.0 / **待评审**（编码前） |
| 依据 | [FE-00 阶段总纲](../FE-00_阶段总纲.md) §3、[FE-01 页面规划](../FE-01_页面规划.md) P1/P2/P3/P4 + §4、[FE-02](../FE-02_配置管理功能规划.md)（配置页不在本片） |
| 范围 | **F1 工程框架 + F2 发起辅导 + F3 进度追踪** |
| 技术栈 | React 18 + TypeScript 5 + Vite + Vitest + Testing Library |
| ⛔ 铁律 | 文档先于代码（`P0-D06` DoD §8.0）；实现偏离本文档须先改文档 |

---

## 1. 范围与不做什么

### 1.1 做什么

| 批次 | 页面 | 交付物 |
|---|---|---|
| F1 | P1 登录、P0 全局错误 | 工程脚手架、API 适配层、令牌管理、路由守卫 |
| F2 | P2 工作台、P3 发起辅导 | 幂等键策略、敏感信息预检 |
| F3 | P4 进度追踪 | SSE 轮询续传、六态映射、降级占位 |

### 1.2 ❌ 不做（防越权边界）

| 不做 | 理由 |
|---|---|
| 配置管理页（P14–P19） | 属 FE-02，本片不碰 |
| 结果页 / 画像页 | 后端无 `result:read` / 画像端点，🔴 阻塞 |
| 多租户切换 | 架构上不存在（`principal.tenant_id` 单列） |
| 注册 / 找回密码 | PIPL 不采集邮箱手机号；本期受控发号 |
| WebSocket / 长轮询替代 SSE | FR-014 明确要求 SSE，引入第二套推送语义即两套语义 |
| 「未来某接口」的 TS 定义 | 后端无 OpenAPI，凭空造类型会固化错误契约 |

> **本文档不写任何 OpenAPI 类型**。后端无契约源（`FE-01 §0.1`），
> 类型只覆盖**实测存在的 4 个端点**，字段与代码逐一对照（§3.1）。

---

## 2. 工程结构

```
frontend/
├── src/
│   ├── main.tsx                  # 挂载
│   ├── App.tsx                   # 路由表
│   ├── api/
│   │   ├── types.ts              # 实测响应类型
│   │   ├── errors.ts             # 错误归一化（双形状）
│   │   └── client.ts             # fetch 封装 + 令牌注入 + 401 处理
│   ├── auth/
│   │   ├── tokenStorage.ts       # sessionStorage 存取 + 过期计算
│   │   ├── session.tsx           # SessionProvider + useSession
│   │   └── RequireAuth.tsx       # 路由守卫
│   ├── features/
│   │   ├── compliance/scan.ts    # 敏感信息预检
│   │   └── run/
│   │       ├── idempotency.ts    # 幂等键策略
│   │       ├── runState.ts       # 六态映射 + 终态判定
│   │       └── eventStream.ts    # SSE 轮询客户端
│   ├── pages/
│   │   ├── LoginPage.tsx
│   │   ├── DashboardPage.tsx
│   │   ├── NewRunPage.tsx
│   │   └── RunProgressPage.tsx
│   └── shared/
│       ├── ErrorView.tsx         # 统一错误展示
│       └── Field.tsx             # 表单字段（含违规高亮）
├── tests/                        # Vitest + Testing Library
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

---

## 3. API 适配层设计

### 3.1 类型（严格对齐实测）

```ts
// 登录
export interface LoginRequest { tenantId: string; loginName: string; password: string }
export interface LoginResponse { accessToken: string; tokenType: 'Bearer'; expiresIn: number }
// ⚠️ 不含 roles/用户信息 → 前端无法显示当前身份（缺口 G1）

// 创建 Run
export interface CreateRunRequest {
  idempotencyKey: string;          // ⚠️ 在请求体，非 Idempotency-Key 头
  agentId: string;
  input: Record<string, unknown>;
  requestedBy?: string;
}
export interface CreateRunResponse {
  runId: string; replayed: boolean; state: string; correlationId: string;
}
```

### 3.2 ⚠️ 错误归一化（必须兼容两套形状）

后端存在**两套不兼容**的错误体：

| 来源 | 形状 |
|---|---|
| `ApiExceptionHandler` / `RunController` | `{code, message}` |
| Security 401/403、`AuthController` 401 | `{error: "unauthorized"}` |

归一化后统一为：

```ts
export interface NormalizedError {
  status: number;        // HTTP 状态码
  code: string;          // 统一错误码，未知时降级为 HTTP_<status>
  message: string;       // 面向用户的中文文案
  correlationId?: string; // 500 时用于排障
  fieldErrors: Record<string, string>; // 400 时字段级提示
}
```

**映射表**（严格按 FE-01 §4.1）

| HTTP | 归一 code | message |
|---|---|---|
| 400 | `INVALID_REQUEST` | 取后端 `message`，否则「请求有误」 |
| 401 | `UNAUTHENTICATED` | 「登录已过期，请重新登录」 |
| 403 | `ACCESS_DENIED` | 「无权执行该操作」 |
| 404 | `NOT_FOUND` | 「资源不存在」 |
| 409 | `IDEMPOTENCY_CONFLICT` | 「该提交与之前的请求不一致，请刷新后重试」 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 「请求格式不受支持」 |
| 422 | `COMPLIANCE_BLOCKED` | 「当前数据不符合合规要求」 |
| 500 | `INTERNAL_ERROR` | 「服务暂时不可用」，带 `correlationId` |

> ⚠️ **`IllegalArgumentException` 的 `message` 是后端原样透出的中文校验文案**
> （如 `"idempotencyKey 不能为空"`）。前端**可展示**（不含资源标识），但须兜底长度上限。

### 3.3 401 的全局处理

401 由 Security 过滤器返回（`{error:"unauthorized"}`，非 JSON 标准错误体）。
处理：**清空会话 + 跳登录 + 带 `reason=expired`**，由登录页显示「登录已过期」。

### 3.4 SSE 端点的特殊约定

| 事实 | 前端应对 |
|---|---|
| 每次响应都会结束（短连接轮询） | 不能用 `EventSource`（它只做长连接且不能设头），须用 `fetch` + `ReadableStream` 解析 |
| 需自定义 `Last-Event-ID` 头 | `fetch` 可设；`EventSource` 不可 |
| 终态追加注释行 `: run-terminal` | 解析时须识别注释行并据此停止 |
| 单次上限 200 条 | 超出则下一轮继续 |

> **因此本片不用 `EventSource`，改用 `fetch` + 手动 SSE 帧解析。**
> 这是被后端实现方式倒逼的，不是偏好。

---

## 4. 令牌管理设计

### 4.1 存储

| 项 | 决策 | 理由 |
|---|---|---|
| 位置 | **`sessionStorage`** | 不用 localStorage：降低 XSS 场景下令牌存活时间 |
| 内容 | `{accessToken, expiresAt}` | |
| `expiresAt` | `Date.now() + expiresIn*1000` | 后端 `expiresIn` **硬编码 900**（不读配置），故按响应值计算 |

### 4.2 过期处理

```
到期前 60s  → 提示「即将过期」
到期        → 清会话 + 跳登录
```

用 `setTimeout` 定时器，在 Provider 挂载时按剩余时间设置。

> ⚠️ **无 refresh token**（缺口 G13）。硬跳登录，不做静默刷新。

### 4.3 ⛔ 不做的事

| 不做 | 理由 |
|---|---|
| localStorage 持久化 | 令牌存活时间过长 |
| 「记住我」 | 无 refresh token，记住的是 15 分钟会话，无意义 |
| 前端自行延长过期 | 服务端才是权威 |

---

## 5. F2：幂等键策略（⚠️ Sev-0 相关）

### 5.1 问题

后端 `idempotencyKey` 在**请求体**内。若每次重试都换新 key，
会**重复创建 Run**，而 `record_weakness` 幂等键是 `student_id + run_id + knowledge_point`
——不同 runId 即视为不同操作 → **重复写入薄弱点 = Sev-0**。

### 5.2 策略

| 场景 | key 行为 |
|---|---|
| 首次提交 | 生成新 UUID v4，存 `sessionStorage` |
| 网络失败 / 5xx 后重试 | **复用同一 key** |
| 表单内容变更 | **换新 key** |
| 创建成功 | 清除 key |

### 5.3 实现

```ts
// key 与表单指纹绑定
const fingerprint = JSON.stringify({agentId, input});
// 指纹不同 → key 作废，重新生成
```

| 项 | 决策 |
|---|---|
| 存储位置 | `sessionStorage`，键 `run.idempotencyKey` |
| 与指纹绑定 | 是。避免用户改了内容仍复用旧 key 导致 `409` |
| 何时清除 | 收到 `runId` 后立即清除 |

> ⚠️ 收到 `409 IDEMPOTENCY_CONFLICT` 时：**清除 key 并提示刷新重试**，
> **不得**自动换 key 重试（可能造成重复写入）。

---

## 6. F2：敏感信息预检（合规闸门）

### 6.1 ⛔ 原则

| 层 | 作用 |
|---|---|
| **前端预检** | **体验优化** —— 提前阻断，避免一次无谓往返 |
| **后端扫描** | **安全边界** —— `DataClassification.IdentifyingPattern`，不可绕过 |

**两者都要有。** 前端预检被绕过时后端仍会拒绝（400）。

### 6.2 规则（与后端 `IdentifyingPattern` 对齐）

> ⚠️ **实施后修正**：初稿此处的正则由本文档凭经验撰写，
> 与后端 `DataClassification.IdentifyingPattern` 核对后发现**两处偏差**，
> 已按后端实现重写。详见 §6.4。

| 类型 | 判据 | 后端正则 |
|---|---|---|
| 身份证 | 18 位（末位可为 X）或 15 位，**前后不接字母数字** | `(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])` 等 |
| 手机号 | 11 位、以 **1 开头且第二位 3-9**，前后不接数字 | `(?<![0-9])1[3-9]\d{9}(?![0-9])` |
| 学号 | **8–12 位数字，前后不接数字** | `(?<![0-9])[0-9]{8,12}(?![0-9])` |
| 邮箱 | 前后不接邮箱字符 | `(?<![A-Za-z0-9._%+-])...(?![A-Za-z0-9.-])` |

⚠️ **必须用前后查找而非 `^$` 锚定**。后端注释明确记载：
> 「必须用词边界而非 `^$` 锚定：实际 Prompt 是「学生 110101… 的错题」这样的句子，
> 整串匹配会完全失效 —— 这是一个被契约测试证伪出来的真实缺陷。」

「正则锚定致闸门失效」在 P1 已出现过一次（`ZeroRetentionGate`），**前端不得重犯**。

### 6.3 ⛔ 表单设计约束

| # | 约束 |
|---|---|
| S-1 | **禁用字段根本不存在输入框**：姓名、学校、班级、身份证、手机号 |
| S-2 | 学生标识输入框固定提示「请填写平台生成的匿名编号，不要填姓名」 |
| S-3 | 预检命中 → **阻断提交 + 高亮字段**，不发请求 |
| S-4 | 题目原文等受控数据**不落 localStorage** |

> 依据：FE-01 §3 P3 的 C1–C4。**「不存在」优于「校验后拒绝」**——
> 前者不可能被误填，后者可能因绕过校验而泄漏。

### 6.4 ⚠️ 实施中发现的两个偏差（已修正）

> 本节记录「按本文档实现 → 与后端核对 → 发现偏差 → 修正」的完整过程。
> **保留此记录的价值**：证明对齐后端是必要的，不是形式动作。

**偏差 1：正则缺少前后边界（已修正）**

| | 初稿（错） | 修正后 |
|---|---|---|
| 学号 | `\d{8,12}` | `(?<![0-9])\d{8,12}(?![0-9])` |
| 手机号 | `1\d{10}` | `(?<![0-9])1[3-9]\d{9}(?![0-9])` |

**后果（实测）**：13 位数字 `1234567890123` 会被裸 `\d{8,12}` 匹配其前 12 位
→ **误判为学号**。测试 `13 位数字不应算学号` **精确失败**，暴露此缺陷。

**偏差 2：凭据错误两套形状未收敛（已修正）**

后端 `{error:"invalid_credentials"}` 与 `{code:"INVALID_CREDENTIALS"}`
在归一化后给出**不同文案**（前者「租户、账号或密码不正确」，后者落入默认映射
→ 「登录已过期，请重新登录」）。

**后果**：`{code:...}` 形状会让用户误以为会话失效而反复重新登录。
已抽取 `CREDENTIAL_FAILURE_MESSAGE` 常量，两条分支共用。

### 6.5 ⚠️ 空格绕过：前端比后端更严（有意为之）

后端正则在**原始文本**上匹配，故 `138 1234 5678`（带空格）
**前后端均漏检** —— 这是一个真实的 PIPL 合规缺口。

前端处理为**分模式收紧**，而非全局去空格：

| 规则 | 是否去空格匹配 | 理由 |
|---|---|---|
| 身份证 / 手机号 / 邮箱 | ✅ 是 | 结构固定，带空格的书写几乎不可能是正常数学内容 |
| **学号** | ❌ **否** | 学号即纯数字，而数学题里「求 1 2 3 4 5 6 7 8」极常见；全局去空格会把正常题目误杀 |

→ **学号的空格绕过需后端修复**，已登记为 §12 待办。
前端不做猜测性收紧（宁可漏检不可误杀正常题目）。

---

## 7. F3：SSE 轮询客户端

### 7.1 轮询状态机

```
IDLE ──(进入页面)──► POLLING
POLLING ──(拉到事件)──► POLLING（更新 lastEventId）
POLLING ──(无新事件)──► 等待 3s ──► POLLING
POLLING ──(run-terminal 注释)──► DONE
POLLING ──(401)──► 跳登录
POLLING ──(其他错误)──► ERROR（可重试）
```

### 7.2 参数

| 项 | 值 |
|---|---|
| 轮询间隔 | 3s（进行中） |
| 终态后 | 停止 |
| 续传 | `Last-Event-ID: <lastEventId>` |
| 退避 | 错误时退避至 10s（避免打爆后端） |

### 7.3 帧解析

```
id: 3
event: RUN_STATE_CHANGED
data: {...}
```

| 步骤 | 处理 |
|---|---|
| 1 | 按空行切分帧 |
| 2 | 遇 `:` 开头行 → 注释。若为 `: run-terminal` → 终态，停止 |
| 3 | 累积 `id` / `event` / `data` |
| 4 | 触发回调 |

### 7.4 ⚠️ 事件载荷白名单

后端**白名单透传**（P2-D05 修正：原黑名单方案被证伪，
`studentName`/`idCard` 都是字符串标量会原样输出）。

前端**只消费**这些字段：

| 字段 | 用途 |
|---|---|
| `sequenceNo` / `at` | 时间线 |
| `state` / `attempt` / `stepType` / `toolName` / `operationState` / `errorCode` / `phase` | 状态展示 |

> ⚠️ **不得**期望出现 Prompt 原文、学生数据、工具参数。
> 若渲染中出现这些，说明安全缺陷，**测试须能捕捉**。

### 7.5 ⚠️ 降级占位（重要）

**实测事实**：`run_event` 表**只有 `RUN_CREATED` 由生产代码写入**，
Worker 不写该表。→ **进度页拿不到任何执行中事件**。

因此页面**必须**能优雅降级：

| 条件 | 展示 |
|---|---|
| 无新事件 | 「正在处理，请稍候」+ 说明"详细步骤将在功能完善后展示" |
| 有事件 | 时间线渲染 |

> **⛔ 绝不编造进度百分比**（FE-01 §4.3 S1）。
> 无依据的进度条 = 假信息。也不用假动画暗示"正在执行到第 N 步"。

---

## 8. Run 状态映射（六态）

取自 `RunState` 枚举，`legalTargets()` 为空集者为终态。

| 状态 | 终态 | UI 呈现 |
|---|---|---|
| `QUEUED` | ❌ | 排队中 |
| `RUNNING` | ❌ | 执行中 |
| `SUCCEEDED` | ✅ | 已完成 |
| `FAILED` | ✅ | 失败（显示 `errorCode`）|
| `CANCELLED` | ✅ | 已取消 |
| `TIMED_OUT` | ✅ | 已超时 |

> ⚠️ **`TIMED_OUT` 是真实终态**。`P2-D05` §4 记载：初稿漏判致 SSE 连接永不关闭。
> **UI 映射与轮询终止条件均不得遗漏。**

---

## 9. 测试设计（可证伪）

> 沿用项目铁律：**断言须主动破坏防护以证明其有效**（`R-016`）。

| # | 测试 | 对应验收 |
|---|---|---|
| T1 | 登录失败提示**不区分**账号/租户/密码原因 | FE-AT-01 |
| T2 | 表单**不存在**禁用字段输入框（DOM 断言）| FE-AT-02 |
| T3 | 身份证/手机号/邮箱/8–12 位数字**被阻断**且高亮 | FE-AT-03 |
| **T4** | **移除预检 → T3 精确失败**（反证）| FE-AT-03 |
| T5 | 网络失败重试**复用同一 key**；表单变更才换新 | FE-AT-04 |
| T6 | 收到 `runId` 后清除 key | FE-AT-04 |
| T7 | 六态全映射，**含 `TIMED_OUT`** | FE-AT-05 |
| **T8** | **删除 `TIMED_OUT` 分支 → T7 精确失败**（反证）| FE-AT-05 |
| T9 | 错误同时兼容 `{code,message}` 与 `{error}` | FE-AT-06 |
| T10 | 401 自动清会话并跳登录 | FE-AT-07 |
| T11 | 未开放能力显示「未开放」而非「暂无数据」 | FE-AT-09 |
| T12 | 事件解析：注释行、`run-terminal`、多帧 | — |
| T13 | 轮询携带 `Last-Event-ID` | — |

---

## 10. 已知限制

| # | 限制 |
|---|---|
| FL-1 | 登录响应无 `roles` → **无法显示当前身份**（缺口 G1） |
| FL-2 | `agentId` 由前端配置注入 → 无法多 Agent 切换（G2） |
| FL-3 | 进度页长期停为首条事件（G4，依赖 P3-D10） |
| FL-4 | 无 refresh token → 15 分钟硬跳登录（G13） |
| FL-5 | 工作台列表仅本设备（无后端列表接口 G7），已显式标注避免误导 |
| FL-6 | SSE 非实时，延迟 = 轮询间隔 |

---

## 11. DoD

### 11.1 ✅ 已达成（2026-10-19 实测）

- [x] **脚手架可构建**：`vite build` 成功，产物 203.70 kB（gzip 67.55 kB）
- [x] **TS 严格模式零错误**：`tsc --noEmit` 通过（strict + noUncheckedIndexedAccess + noUnusedLocals）
- [x] **112 项测试全绿**：
      `pages` 15 / `scan` 23 / `runState` 23 / `errors` 22 / `sse` 19 / `idempotency` 10
- [x] **反证已验证**：`runState.test.ts` T8 组、`scan.test.ts` T4 组
- [x] **CI 扫描通过**：构建产物无硬编码密钥/口令/私钥/测试令牌
- [x] `.gitignore` 已含 `node_modules/`、`dist/`、`.env.*`
- [x] 依赖已升级：react-router-dom 7.18.4 / vite 6.4.4 / vitest 3.2.7

### 11.2 ⚠️ 本次验证抓到的 P0 缺陷（已修复）

**变量遮蔽导致所有 HTTP 调用失效**

```ts
// ❌ 缺陷代码
export function login(request: LoginRequest) {
  return request<LoginResponse>(...)   // ← 此处解析为形参，不是模块级函数
}
```

**症状**：运行时 `TypeError: request2 is not a function`，
所有 HTTP 请求静默失败，页面统一显示「登录失败，请稍后重试」。

**为什么此前没发现**：5 个纯逻辑测试文件都不经过 `client.ts`，
**只有组件测试会真正发起请求**。若沙箱持续不可用、未跑组件测试，
该缺陷会直接进入提交。

**修复**：模块级函数改名 `_request`，对外形参改名 `payload`，
形成"内外不同名"的命名约束并写入代码注释。

> **教训**：分层测试有盲区——**不经过某模块的测试不会发现该模块的缺陷**。
> HTTP 调用层必须有至少一条端到端用例，否则整层无覆盖。

### 11.3 已修正的测试自身缺陷（2 处）

| # | 问题 | 修正 |
|---|---|---|
| 1 | T2 断言「整页 HTML 不含学校/班级」 | 合规提示文案**本就该**出现这些词（"不会收集姓名、学校、班级"）。断言对象改为**输入控件**的 name/id/type |
| 2 | SSE 纯注释行断言 `terminal === false` | 纯注释不含事件字段，按 SSE 规范应整体忽略返回 `null`，否则调用方会误判收到一条事件 |

> **测试写错比代码写错更隐蔽**：前者靠"恰好通过"蒙混，后者会被立即暴露。

## 12. 待办与已知问题

| # | 项 | 级别 | 说明 |
|---|---|---|---|
| 1 | **后端 `IdentifyingPattern` 存在空格绕过缺口** | 🟡 中 | `138 1234 5678` 前后端均漏检（PIPL 禁采手机号）。前端已对身份证/手机/邮箱收紧，但**学号因误杀风险未收紧** → 需后端修复 |
| 2 | vitest 传递依赖残留 3 漏洞 | 🟢 低 | tinypool RCE / @vitest/mocker 路径穿越，**均为测试工具链、不进生产构建**。修复需 vitest 5（breaking change）|
| 3 | `agentId` 为前端环境变量注入 | 🔴 高 | 缺口 G2，无 Agent 管理接口 |
| 4 | 进度页无执行事件 | 🔴 高 | 缺口 G4，依赖 P3-D10 |
| 5 | 配置管理页未做 | ⏸️ 中 | 属 F4–F6，需先定「配置生效机制」 |
| 6 | 无 E2E（Playwright） | 🟡 中 | 组件测试已覆盖 HTTP 链路；端到端待 P4 |

---

**下一步**：
① 向后端提出待办 1（`IdentifyingPattern` 空格绕过）；
② 进入 F4（配置管理只读视图），须先定 FE-02 §9 第 1 项；
③ 缺口 G2/G4 推动 P3 补齐后接入真实数据。

# FE-D7 — F4 配置只读视图接线（C1 / C9 → P14·P15·P16·P17）

| 字段 | 内容 |
|---|---|
| 文档编号 | FE-D7 |
| 版本/状态 | v1.0 / **已实施** |
| 依据 | [P4-D07](../../P4/P4-D07_配置读取与零保留接线.md)（C1 端点 + C9 零保留接线）、[FE-02 §0.3 §2](FE-02_配置管理功能规划.md)（声明值 vs 运行态诚实性）、[FE-D3](FE-D3_视觉纪律.md)（禁用圆角/阴影/shimmer）、[FE-D5](FE-D5_样式收敛与CI不变式.md)（CSS 类 ⊆ 已定义不变式） |
| 目标 | 把前端 F4 中**确有数据支撑**的页面从诚实占位接上真实 `GET /api/config`，诚实呈现「声明值 vs 是否真正接入运行时」 |
| 不做什么 | 不接 P18（Agent 配置，G2 无接口）、P19（配置变更审计，C6 无接口）；不暴露任何密钥；不把 `effective=false` 渲染成「已生效」 |

---

## 0. 背景与范围决策

P4-D07 已落地 **C1**（`GET /api/config`）与 **C9**（零保留注册表接线）。台账据此标注
「**F4 配置只读已解锁（后端 C9/C1 落地·P4-D07）**」。

实测后端 `ConfigReadService` 返回的 `groups` 含四个业务域：
`运行与容量` / `数据保留与删除` / `工具与审批` / `合规与零保留`，
外加 `zeroRetention` 运行态视图。即**声明值 + 生效标志**已对前端可用。

故本片把 F4 中真正有数据支撑的页面接线，分两类：

| 页面 | 路由 | 数据来源 | 处置 |
|---|---|---|---|
| **P14 配置总览** | `/config` | C1 全部 groups | ✅ 接线 |
| **P15 运行与容量** | `/config/runtime` | C1 group `运行与容量` | ✅ 接线（声明值；实时指标待 C3）|
| **P16 工具与审批** | `/config/tools` | C1 group `工具与审批` | ✅ 接线（声明值；审批流待 C4）|
| **P17 合规与零保留** | `/config/compliance` | C1 group `合规与零保留` + `zeroRetention` | ✅ 接线 |
| P18 Agent 配置 | `/agents` | **G2 无接口** | 🔴 仍 `Unavailable`，更新说明 |
| P19 配置变更审计 | `/config/audit` | **C6 无接口** | 🔴 仍 `Unavailable`，更新说明 |

> ⚠️ **诚实边界（P4-D07 B-3 / B-4）**：
> - `effective=false` 的键**必须**标「仅声明未接入」，**绝不**渲染成「已生效」。
> - 零保留仅声明未确认（registry 状态 `PENDING`）→ 闸门仍 `failClosed` 拒绝真实数据，
>   页面**不得**显示「已确认放行」。
> - 本片**不**改变任何运行时行为，仅诚实呈现。

---

## 1. 后端契约（实测，非凭印象）

`GET /api/config`（默认 `authenticated`，不暴露密钥）响应：

```jsonc
{
  "groups": [
    { "name": "运行与容量", "entries": [
      { "key": "agent.backpressure.enabled", "declaredValue": "true",
        "effective": true, "source": "yml", "note": "P4-D05 已实现并接入运行时" },
      { "key": "agent.run.max-cost-per-run", "declaredValue": "2.00",
        "effective": false, "source": "yml", "note": "FE-02 §2：生产无读取点，改后不影响行为" }
    ]},
    { "name": "数据保留与删除", "entries": [ /* … */ ] },
    { "name": "工具与审批", "entries": [ /* … */ ] },
    { "name": "合规与零保留", "entries": [ /* … */ ] }
  ],
  "zeroRetention": {
    "declaredProviders": ["deepseek","minimax","workbuddy"],
    "registryStatus": { "deepseek": "PENDING", "minimax": "PENDING", "workbuddy": "PENDING" },
    "anyConfirmed": false,
    "failClosed": true
  },
  "effectiveFlags": { "agent.backpressure.enabled": true, /* … */ }
}
```

类型映射（前端 `api/types.ts` 新增）：

| 后端 record | 前端 interface |
|---|---|
| `ConfigResponse` | `ConfigResponse { groups; zeroRetention; effectiveFlags }` |
| `ConfigGroup` | `ConfigGroup { name; entries }` |
| `ConfigEntry` | `ConfigEntry { key; declaredValue; effective; source; note }` |
| `ZeroRetentionView` | `ZeroRetentionView { declaredProviders; registryStatus; anyConfirmed; failClosed }` |

---

## 2. 实现

### 2.1 类型与客户端

- `api/types.ts`：新增 `ConfigResponse` / `ConfigGroup` / `ConfigEntry` / `ZeroRetentionView`。
- `api/client.ts`：新增 `getConfig(): Promise<ConfigResponse>`，走既有 `_request<T>('/api/config', { method:'GET' }, false)`。

### 2.2 取数 hook（可测性优先）

新增 `features/config/useConfig.ts`：返回 `{ config, loading, error, reload }`。
- `useEffect` 内 `let cancelled` 守卫，避免卸载后 setState（React 18 + StrictMode 双挂载）。
- 失败区分 `ApiError` 与未知错误，归一化文案。
- `reload` 通过 `nonce` 触发重新拉取（供错误页「重试」）。

### 2.3 页面重写（`pages/config/ConfigPages.tsx`）

- **P14 总览**：`useConfig()` → 加载态 `Loading` / 错误态 `ErrorState(可重试)` / 数据态按 `groups` 逐组渲染 `Sheet`+表。
  表列：`配置键(mono)` · `声明值` · `生效`(chip：`已接入`/`仅声明未接入`) · `来源` · `说明`。
  顶部 `Marked` 说明「本页展示声明值，灰色 chip 表示改了不影响行为」。
- **P15 运行与容量**：取 `groups` 中 `name==='运行与容量'`，同表渲染；`Marked` 注明实时指标（当前并发/积压）待 C3。
- **P16 工具与审批**：取 `name==='工具与审批'`；`Marked` 注明审批流待 C4、保护项只能调严。
- **P17 合规与零保留**：取 `name==='合规与零保留'` + `zeroRetention`；
  供应商列表按 `registryStatus` 渲染（`PENDING`→`chip--mark 待确认`；
  若为未来 `CONFIRMED`→`chip--settled 已确认`）；`failClosed` 为真显示「零保留闸门生效中（fail-closed）：未确认证据的供应商调用真实数据会被拒绝」。
  **⚠️ 始终渲染**「判断按供应商分别判定」说明（保 `pages.skeleton.test.tsx` P17 契约），**永不**出现编辑控件。
- **P18 / P19**：保留 `Unavailable`，`blockedBy` 改为 `G2` / `C6`，说明改为「声明值在总览页可见；真实接口待 G2 / C6」。

### 2.4 视觉纪律（FE-D3 / FE-D5）

仅复用已定义类：`.sheet` / `.sheet__title` / `.marked` / `.chip` / `.chip--settled` / `.chip--mark` /
`.status`（如需）/ `.muted` / `.mono` / `code`。**不引入任何新类、不写圆角卡片/阴影/shimmer**
（CSS 完整性不变式 `tests/css.integrity.test.ts` 会拦截未定义类）。

---

## 3. 测试（行为 + 连通性 + 反证，沿用项目 R-016 教训）

`tests/config.page.test.tsx`，`vi.mock('@/api/client', async (importActual) => ({ ...actual, getConfig }))`
保底其他导出（如 `SessionProvider` 依赖的 `setUnauthorizedHandler`）。

- **T1 连通性 + 行为（总览）**：渲染 → 断言 `getConfig` 被调用；数据到达后渲染分组名、effective 条目分别含「已接入」「仅声明未接入」、声明值可见。
- **T2 反证（数据来自 mock）**：把某条 `declaredValue` 改成哨兵串，断言 UI 随之出现该串；删掉数据绑定则红。
- **T3 诚实边界**：全页不得出现「已生效」误标（`effective=false` 行列显式验证文案非「已生效」）。
- **T4 错误 + 重试（连通性）**：`getConfig` reject → `ErrorState` 出现；点击「重试」→ 调用次数 +1。
- **T5 合规**：渲染 zerРetention——`PENDING` 状态、`failClosed` 文案；`anyConfirmed=false` → **不**出现「已确认放行」。
- **T6 合规反证**：mock `failClosed=false` / `anyConfirmed=true` / 状态 `CONFIRMED` → 文案随之变化（证明非硬编码）。
- **T7 / T8（P15 / P16 行为）**：分别渲染对应 group，effective 标对。
- **保 P17 骨架契约**：`pages.skeleton.test.tsx` 的「无编辑控件」+「含『分别』」不因本次改动变红。

---

## 4. DoD

- [x] `api/types.ts` 新增 4 个配置类型，与后端 record 字段一一对应
- [x] `api/client.ts` 新增 `getConfig()`，走 `_request` 封装（含 401 全局清理）
- [x] `features/config/useConfig.ts`：loading/error/config/reload + 防竞态
- [x] P14/P15/P16/P17 接真实数据，`effective=false` 显式标「仅声明未接入」
- [x] P17 零保留诚实渲染 pending/fail-closed，无编辑控件、始终含「分别」
- [x] P18/P19 仍 Unavailable，说明更新
- [x] 仅复用已定义 CSS 类，零新增禁用样式
- [x] `tests/config.page.test.tsx`：T1–T8 + 反证；`pages.skeleton.test.tsx` P17 不回归
- [x] `vitest run` 全绿、`tsc --noEmit` 零错误、`vite build` 零体积回归

---

## 5. 已知限制（诚实边界）

1. **P18 / P19 仍阻塞**：Agent 配置（G2）、配置变更审计（C6）无后端接口，维持 `Unavailable`。
2. **零保留仅声明未确认**：registry 状态恒 `PENDING`，真确认（CM-1）需证据承载配置，另行排期。
3. **C8 未做**：`effective=false` 的限键（如 `max-cost-per-run`）仍「声明未执行」，本片仅诚实呈现，不掩盖。
4. **实时指标/工具注册表读取未接**：P15 展示声明值而非实时并发/积压；P16 审批流未接线（待 C3/C4）。

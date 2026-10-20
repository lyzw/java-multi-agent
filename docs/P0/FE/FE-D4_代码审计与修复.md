# FE-D4 — 代码审计与修复（F1–F3 之后的第一轮缺陷清除）

| 字段 | 内容 |
|---|---|
| 文档编号 | FE-D4 |
| 版本/状态 | v1.0 / **已实施** |
| 依据 | FE-D1 §4.2/§7.5、FE-D2 §2.1/§2.3、FE-D3 §3.1 |
| 前置 | F1–F3 + FE-D2（页面体系补全）+ FE-D3（视觉规范）已交付，**150 项测试全绿** |
| 触发原因 | zhouwei 指令「继续前端代码优化」→ 先实测审计，再修复 |

---

## 0. 为什么先审计再动手

150 项测试全绿 **不等于** 代码没问题。本轮的立场：

> 全绿只证明「已写的断言成立」，不证明「已声明的功能存在」。
> FE-D2/FE-D3 交付快（同日完成 13 页 + 视觉迁移），
> 迁移不完整与决策未落地的概率显著高于功能缺陷。

审计方法：**双向静态比对 + 运行时推理**，不用「看起来对」的印象。
已按此纪律发现 **8 项缺陷**（编号 F-15～F-22，接续后端 F-1…F-14 序列）。

---

## 1. 缺陷清单（实测证据）

### 1.1 F-15 `expiringSoon` 静默失效 + 到期提示功能从未落地

**现象**：`session.tsx` 每秒 `setTick` 重渲染，但 `expiringSoon` 永远不变。

**根因**（代码位置 `src/auth/session.tsx:97-108`）：

```tsx
const [, setTick] = useState(0)
useEffect(() => { setInterval(() => setTick((n) => n + 1), 1000) }, [])

const value = useMemo<SessionState>(() => ({
  expiringSoon: isExpiringSoon(session),   // ← 依赖 Date.now()
  ...
}), [session, expiredNotice, signIn, signOut, onUnauthorized])
//   ↑ 依赖数组里没有 tick
```

每秒重渲染命中 memo 缓存 → `expiringSoon` **永远等于登录瞬间的计算值**。
登录时 `remaining ≈ 900`，`isExpiringSoon` 返回 `false`，**此后永远为 false**。

**叠加缺陷**：`expiringSoon` 在 `SessionState` 上声明，但全仓**无任何 UI 消费**
（grep 证实仅 3 处命中：接口声明、`isExpiringSoon` 实现、useMemo 计算）。
即 FE-D1 §4.2 承诺的「到期前 60s 提示」**从未实现**。

**后果**：用户被硬跳登录时毫无预告，且「即将过期」提示永远不出现。
**同类**：与后端 F-10 / F-12 完全同源 —— *代码看起来在工作，实际完全无效，且全绿*。

**修复方案**：

⛔ **不采用**「把 tick 加进依赖数组」—— 那会让每秒一次重算永远存在，
且 `Date.now()` 派生值本就不该走 useMemo。

✅ **采用「定时器对齐到预警时刻」**：

- 删除每秒 `setInterval`
- 新增 effect：算出预警时刻 `warnAt = expiresAt - EXPIRY_WARN_SECONDS * 1000`，
  用**单个** `setTimeout` 在那一刻把 `expiringSoon` 置 true
- `expiringSoon` 变为真实 state，不再是从 memo 里长出来的假值
- 定时器数量从「1 个/s 无限」降为「1 个预警 + 1 个到期」

**落地 UI**（`AppShell`）：顶栏在预警窗口内显示
「会话即将到期，请及时保存」—— 否则修好计算仍无人消费，等于没修。

---

### 1.2 F-16 `<main>` 嵌套 + 两页未迁移 FE-D3

**现象**：`AppShell.tsx:114` 已渲染 `<main className="main">`，
而 `RunProgressPage.tsx:81` / `NewRunPage.tsx:144` 在其**内部又渲染 `<main>`**。

**根因**：HTML 规范禁止 `main` 嵌套 `main`（一个文档只有一个主内容区）。
屏幕阅读器会把嵌套的 `main` 当作**第二个主内容区**，导航语义混乱。

**叠加缺陷**：这两页也是 19 页中**唯一未迁移** FE-D3 `main__head` 结构的页面
（其余 10 页已用 `<div className="main__head">` + `<h1>` + `<p className="main__lead">`）。
FE-D3 §7 写「10 个页面迁移至新规范」—— 实际 12 页在布局内，漏 2 页。

**修复**：两页改用 `main__head` 结构；登录页不在 AppShell 内，`<main>` 保留合法。

---

### 1.3 F-17 六个 CSS 类被使用但无任何定义（视觉失效）

**实测方法**：提取全部 `className`（静态 + 模板字符串 + `cls` 变量）与
`tokens.css` / `layout.css` / `legacy-backup.css` 的选择器做双向集合比对。

| 类名 | tokens | layout | legacy | 后果 |
|---|---|---|---|---|
| `new-run` | ✗ | ✗ | ✗ | 发起辅导页根容器无样式 |
| `run-progress` | ✗ | ✗ | ✗ | 进度页根容器无样式 |
| `error-view` | ✗ | ✗ | ✗ | 错误容器无样式（`__hint`/`__message` 有，外层没有） |
| `timeline__op` | ✗ | ✗ | ✗ | 时间线操作态标签无样式 |
| `timeline__step` | ✗ | ✗ | ✗ | 时间线步骤标签无样式 |
| `timeline__tool` | ✗ | ✗ | ✗ | 时间线工具名标签无样式 |

**根因**：FE-D3 视觉迁移时重写了样式层，但只迁移了 10 个页面，
`new-run` / `run-progress` 这些旧版页面根类**没有跟着搬**；
`error-view` 则是 `ErrorView` 组件自带、从未在新样式层定义。

**后果**：这两页的根容器与时间线的三个标签在真实渲染中**完全裸奔**
（只吃 body 默认值），排版与其余 10 页不一致。

**修复**：在 `layout.css` 补定义，遵循既有命名与纪律
（1px 线分隔、无圆角阴影、`--ink-2` 次级文字）。

---

### 1.4 F-18 FE-D2 §2.1 决策未落地：两套「未开放」提示并存

**现象**：`RunProgressPage.tsx` 仍 import `NotAvailable`（来自 `shared/ErrorView.tsx`），
而其余页面用 `shared/states.tsx` 的 `Unavailable`。

**根因**：FE-D2 §2.1 原文：

> 新增组件，**取代散落的 `NotAvailable`**
> **为什么必须统一**：散落的占位文案会导致「有的页说未开放、有的页空白、
> 有的页有假数据」——用户无法判断哪些是能用哪些是待开发。**一致性本身就是信息。**

代码未跟随文档 → 决策悬空。

**后果**：同一产品两套未开放表达（`NotAvailable` 是「功能尚未开放」裸文案，
`Unavailable` 带缺口编号 `blockedBy` 与承接阶段 `eta`，**可追溯性不同**）。
用户无法从进度页得知该功能由谁承接。

**修复**：`RunProgressPage` 改用 `Unavailable`，带 `blockedBy="G5"`。
⛔ 不删除 `NotAvailable`（登录页等仍在使用），但记录为待收敛项。

---

### 1.5 F-19 `degraded` 判定自相矛盾

**现象**（`src/features/run/eventStream.ts:177`）：

```ts
degraded: newEvents.length === 0 && !sawTerminal
```

**场景**：第 1 轮轮询收到 `RUN_CREATED`，第 2 轮无新事件 → `degraded` **翻回 true**。

**后果**：`RunProgressPage` 此时显示
「正在处理，请稍候。**详细执行步骤将在功能完善后展示。**」
而上方时间线**已经列出了** `RUN_CREATED` 事件 →
文案说「没步骤」、界面显示「有步骤」，**自相矛盾**。

**根因**：`degraded` 的语义被写成「本轮无新事件」，
但它实际想表达的是「**整个流从未产生过任何事件**」。
混淆了「本轮」与「累计」。

**修复**：`degraded` 改为**只由累计事件数决定**：
`degraded = snapshot.events.length === 0 && !sawTerminal`。
一旦收到过事件，永久不再降级提示。

---

### 1.6 F-20 `Field` 的 React key 重复（**实测推翻了最初判断**）

**现象**（`src/shared/Field.tsx:79`，修复前）：`key={violation.kind}`

**最初的判断（❌ 错误，已写在这里留证）**：
> 同一 kind 命中两次 → 两个 `<li>` 拿到相同 key → React 复用节点，
> **第二条违规提示不渲染**。

**探针实测结论（✅ 正确）**：

写了独立探针组件复现，结果是：

```
PROBE li-count = 2
PROBE texts    = 第一条 | 第二条
PROBE warns    = 1 Warning: Encountered two children with the same key, `%s`.
                 Keys should be unique so that components maintain their
                 identity across updates. Non-unique keys may cause children
                 to be duplicated and/or omitted — the behavior is
                 unsupported and could change in a future version.
```

即**两条都会渲染**，真实危害是：

1. **控制台警告噪音** —— 会掩盖其他真实警告（本项目大量依赖告警定位问题）
2. **未定义行为** —— React 官方明说将来版本可能变成
   `duplicated and/or omitted`（重复或遗漏）。今天不漏，不代表升级后不漏
3. **列表内容变化时 fiber 复用错位** —— 中间增删违规项时 DOM 状态错乱

**修复**：`key={`${violation.kind}-${index}`}`，索引兜底保唯一。

> ⚠️ **这条是整个 F-20 最有价值的部分**：
> 我最初的断言写的是「第二条不渲染」——**探针证明那是错的**。
> 若按那个断言写测试，改回 `violation.kind` 后测试**依然全绿**（反证不红），
> 我会误以为「修复有效、测试有牙齿」，实际上两边都没测到东西。
> 按项目纪律（反证不红先怀疑测试无效），重写为
> **「不应触发 React 重复 key 警告」**—— 反证立即精确转红。

---

### 1.7 F-21 `normalizeNetworkError` 死代码

**现象**（`src/api/errors.ts:172`）：

```ts
...(cause instanceof Error && cause.message ? {} : {})
```

三元两边都是 `{}` → **恒展开空对象**。
推测本意是想带出 `cause.message`，但没写。

**修复**：删除。⛔ 不「补上」把原始网络错误信息透出给用户 ——
`fetch` 的异常文案（如 `Failed to fetch`、`Load failed`）对用户无意义，
且可能泄漏内网细节。保持归一化文案。

---

### 1.8 F-22 `hasRoleAccess` / `ROLE_PERMISSIONS` 四重死代码

**现象**：`src/auth/RequireRole.tsx` 导出 `ROLE_PERMISSIONS`（8 角色 × 区域）
与 `hasRoleAccess()`，但：

- 无调用方（`RequireRole` 内 `void area` 直接放行）
- 无测试
- 与后端 `RoleBasedAuthorizationService.java:32` 是**第二份副本**，
  后端变更不会让这边编译失败

**处置**：⛔ **不接假实现**。G1（登录响应不含 `roles`）未补齐前，
任何角色判定都只能是猜。当前「刻意放行 + 后端 403 给权威结论」是正确选择（FE-D2 §2.3）。

本轮只做两件事：

1. 补**行为测试**锁住 `hasRoleAccess` 契约（多角色取并集 / 未知角色拒绝 / 大小写敏感）
2. 在文件头补「这是第二份副本」的显式警示，防止有人以为它是唯一事实源

---

## 2. 明确不修（及理由）

| 项 | 理由 |
|---|---|
| 删 `legacy-backup.css`（685 行） | 注释已说明保留以便回溯；且其中 19 个类仍被组件使用，**现在删会直接破样式**。列为待收敛项，需先做一轮「legacy 类逐个迁移」 |
| 删 `NotAvailable` | 仍被 `RunProgressPage` 之外的页面使用；本轮的修复是让 `RunProgressPage` 收敛到 `Unavailable`，而非反向 |
| 接角色守卫 | G1 未补齐，接了就是猜。见 §1.8 |
| 接分页控件 | 后端无分页契约（G7），FE-D2 §3.6 已定「不擅自定契约」 |
| 删 `setTick` 之外的重渲染优化 | 本轮已把「1 个/s 无限定时器」降为「2 个定点定时器」，剩余收益不足以承担改动风险 |

---

## 3. 测试设计（每项修复都要可证伪）

| # | 测试 | 断言 |
|---|---|---|
| T1 | 推进时间后 `expiringSoon` 变 true | `vi.useFakeTimers()` + `advanceTimersByTime` |
| T2 | AppShell 预警窗口内显示提示 | 文本出现；**离开窗口后消失** |
| T3 | 布局内页面**无嵌套 main** | 遍历路由页，`container.querySelectorAll('main').length` 判定 |
| T4 | 六类缺失 CSS 已有定义 | 读 `layout.css` 断言选择器存在 |
| T5 | 进度页用 `Unavailable` 而非 `NotAvailable` | 渲染后含缺口编号 `G5` |
| T6 | 收到过事件后 `degraded` 永为 false | 两轮 poll：第 1 轮有事件、第 2 轮无 |
| T7 | 同 kind 两条违规都渲染 | 两个手机号 → 2 个 `<li>` |
| T8 | `hasRoleAccess` 契约 | 并集 / 未知角色 / 大小写 |
| **T9** | **反证**：每项修复注入缺陷 → 对应用例精确转红 → 恢复 | 9 次注入，2 次失败均有产出（见 §6）|

> ⚠️ T1 的写法要点：`session.tsx` 的预警定时器依赖 `Date.now()`，
> 必须同时 mock 时钟与定时器，否则 `advanceTimersByTime` 推进了定时器
> 而 `Date.now()` 不动，`warnAt - now` 计算仍指向未来。

---

## 4. DoD

- [x] 8 项缺陷全部修复，每项有测试
- [x] 反证逐项执行并记录（含 F-20 的**二次反证**，见 §6）
- [x] **不带任何筛选的全量** `vitest run` 通过：**199 项**（基线 150 + 新增 49），连跑 3 轮稳定
- [x] `tsc --noEmit` 零错误
- [x] `vite build` 成功：JS 230.60 kB（gzip 74.46）、CSS 10.74 kB（gzip 2.80）
- [x] 产物层复核：F-17 的 12 个类**真的打进产物**；无 box-shadow / 无 shimmer；三断点在；breathe 在
- [x] 产物密钥扫描通过（命中项经核对均为 React 内部常量与登录表单字段名，无真实密钥）
- [x] 台账数字实测更新

---

## 6. 反证记录（逐项，含两次失败）

> 纪律：**修复必须可证伪**。每项都「注入缺陷 → 观察是否精确转红 → 恢复」。
> 反证不红 = 测试没牙齿 = 修复未被验证。

| # | 缺陷 | 注入方式 | 结果 |
|---|---|---|---|
| 1 | F-15（我自己的实现缺陷） | `warnDelay<=0` 分支不 setState | ✅ 1 项精确转红 |
| 2 | **F-15 原始形态** | 还原「每秒 tick + useMemo 依赖不含 tick」 | ✅ **3 项精确转红** |
| 3 | F-16 | `NewRunPage` 改回 `<main className="new-run">` | ✅ 2 项精确转红 |
| 4 | F-17 | 删 `.timeline__op` 定义 | ✅ 1 项精确转红 |
| 5 | F-19 | `degraded` 改回 `newEvents.length === 0` | ✅ 1 项精确转红 |
| 6 | F-20（第一次） | `key` 改回 `violation.kind` | ❌ **未转红** → 见下 |
| 7 | F-20（第二次） | 同上，但断言改为「无重复 key 警告」 | ✅ 1 项精确转红 |
| 8 | F-18（第一次） | `import` 改为 `NotAvailable` | ❌ **编译失败**，测试跑不起来 |
| 9 | F-18（第二次） | 别名 `import { NotAvailable as Unavailable }` | ✅ **3 项精确转红** |

### 6.1 两次失败都有产出（不是白跑）

**第 6 次（F-20 未转红）** → 促成探针实验，**推翻了我对缺陷机理的判断**
（见 §1.6）。若没有这次反证，我会带着错误认知写进文档与测试。

**第 8 次（F-18 编译失败）** → 说明「直接换 import」不是好的注入点：
它证明的是「类型不匹配」，不是「测试有牙齿」。
改用别名 import 后 JSX 完全不变、编译通过，**只让实际渲染的组件回退**，
这才分离出「组件选错了」这一个变量 → 3 项精确转红。

### 6.2 反证过程中的一次真实险情

第 8 次反证执行时命令被 SIGTERM 中断，**恢复步骤没跑**，
`RunProgressPage.tsx` 停留在「import `NotAvailable` 但 JSX 用 `Unavailable`」的
编译不过状态。

处置：发现后先核对文件实际状态（而非假设已恢复），
再手动把 import 改回，然后逐项校验 JSX 完整性（7 个检查点全过）才继续。

> 教训：反证注入前必须 `cp` 备份（本项目既有纪律），
> 且**恢复后要验证，不能假设恢复成功**。

---

## 5. 已知限制（诚实记录）

1. **角色级菜单隐藏仍未实现** —— G1 未补齐。学生登录后会看到「审批台」
   等管理入口，点进去由后端 403 给权威结论。这是**已知的体验缺口，不是安全缺口**。
2. **`legacy-backup.css` 仍是双份样式源** —— 其中 19 个类仍在使用，
   任何样式修改都要同时检查它是否覆盖。待下一轮专项收敛。
3. **`NotAvailable` 与 `Unavailable` 仍未彻底统一** —— 本轮只收敛了进度页。
4. **到期提示只到「提前 60s」** —— 后端 TTL 硬编码 900s 且无 refresh token（G13），
   用户若无操作仍会被硬跳登录，提示只争取到一次保存机会。

# FE-D6 — 轮询生命周期与路由完整性审计（FE-D4/D5 之后的第二轮缺陷清除）

| 字段 | 内容 |
|---|---|
| 文档编号 | FE-D6 |
| 版本/状态 | v1.0 / **已实施** |
| 依据 | FE-D4（依赖数组失效类）、FE-D5（CI 不变式类）、FE-D1 §7（SSE 轮询）、FE-D1 §6（合规闸门）、FE-01 §4.3（合规红线 C6） |
| 前置 | FE-D4（F-15~F-22，199 项）+ FE-D5（样式收敛，202 项）已交付 |
| 触发原因 | zhouwei 指令「继续前端代码优化」→ 第二轮**实测审计**（读源码 + 运行时推理 + 反证），不凭印象 |

---

## 0. 本轮立场

FE-D4/D5 已清除「迁移不完整 / 决策未落地 / 裸奔样式」类缺陷，202 项全绿。
第二轮审计的立场不变：

> 全绿只证明「已写的断言成立」，不证明「已声明的功能存在」。

本轮改换两个新镜头，专门找 FE-D4/D5 没覆盖的盲区：

1. **轮询生命周期** —— F-15 是「依赖数组失效导致声明的功能不存在」。
   本轮看另一类「**契约声明了停止，但代码不执行停止**」。
2. **合规兜底的接入状态** —— C6 敏感词兜底扫描是否真的接进了渲染路径？
3. **路由/链接完整性** —— 把 FE-D5「CSS 类 ⊆ 已定义」的不变式思路，
   复制到「链接目标 ⊆ 已声明路由」，防止出现指向不存在路由的回归。

实测发现 **3 项**（F-23 真实缺陷 + F-24 契约锁 + F-25 预防性不变式），
其中 **F-23 是真实可运行的缺陷**，F-24/F-25 为「锁契约 / 防回归」类。

---

## 1. 缺陷清单（实测证据）

### 1.1 F-23 `stop()` 不真正停止轮询：已发出的请求仍被处理后 emit

**现象**（`src/features/run/eventStream.ts`）：

`RunProgressPage` 在 `useEffect` 里 `createRunEventPoller(...)` 并在清理时
`poller.stop()`（防定时器泄漏）。但 `stop()` 的实际语义是「标记 `stopped=true`
+ 清掉**下一个** `setTimeout`」，**对已经 `await` 出去、正在路上的那一次响应无效**。

`poll()` 的三处 `emit`：

```ts
const poll = async (): Promise<void> => {
  if (stopped) return                       // ← 只在这里查 stopped（poll 起始）
  emit({ loading: true, error: null })      // 同步发生（line 137）
  try {
    const response = await openEventStream(...)   // 异步：期间 stop() 可能被调用
    const text = await response.text()
    // ……解析出事件……
    emit({ loading: false, events, done, degraded })  // line 171（post-await，无守卫）
  } catch (cause) {
    emit({ loading: false, error })          // line 211（post-await，无守卫）
  }
}
```

`stopped` 只在 `poll` **起始**被检查一次。一旦进入了 `await openEventStream`，
`stop()` 把 `stopped` 置 true、清掉的是**下一轮**的定时器；而**本轮**请求返回后，
`emit` 仍会执行 → **已停止的 poller 继续更新 `snapshot`**。

**实测后果（分两层）**：

- **生产环境（无 StrictMode 双挂载）**：`stop()` 在组件卸载时调用，
  卸载后 `setState` 是 no-op（React 18 不再告警）。影响 ≈ 无，但**契约被违反**：
  `stop()` 的语义是「停止并释放」，实际仍会处理并写入一次状态。
- **开发环境（React 18 `StrictMode` 双挂载）**：`mount → cleanup(stop) → remount`
  第一个 poller 被 `stop()` 后，它那一路 in-flight 的响应返回时仍 `emit`，
  可能用一份旧/空快照覆盖第二个（活跃）poller 刚写好的快照 → 偶发闪烁 / 丢失更新。

**同源**：与 F-15 完全同构 —— *声明了「停止/提示」行为，但运行时根本不执行*。
区别在于 F-15 是「依赖数组缺项导致计算值恒定」，F-23 是「守卫位置不对导致契约失效」。
都是「代码在、测试绿、运行时不对」。

**修复方案**：

在 `emit` 自身加 `stopped` 守卫，使**任何** `stop()` 之后的 `emit` 都失效，
而不只依赖 `poll` 起始的那一次检查：

```ts
const emit = (patch: Partial<StreamSnapshot>): void => {
  if (stopped) return                       // ← 新增：stop 后一律不再写状态
  snapshot = { ...snapshot, ...patch }
  callbacks.onSnapshot(snapshot)
}
```

保留 `poll` 起始的 `if (stopped) return`（廉价早退，防 stop 后误启动）。
正常流程中 `stopped` 只有在「收到终态（line 194）」或「401（line 206）」之后才变 true，
且那两条路径都已 `return` 在 `schedule` 之前，故**正常流程的 emit 不会被误抑制**。

**可证伪**：`tests/eventStream.poller.test.ts` 用可控 Promise 的 `openEventStream` 桩，
`stop()` 后再 resolve 响应，断言 `onSnapshot` 调用次数停在初始的 1 次（不增加）。
**反证**：删掉 emit 守卫 → 该用例转红。

---

### 1.2 F-24 `findLeakedFields`（C6 敏感词兜底）已导出但从未被调用

**现象**（`src/pages/business/RunResultPage.tsx`）：

```ts
export function findLeakedFields(payload: Record<string, string>): string[] {
  // ……对每字段跑 scanText，返回命中的字段名
}

export function RunResultPage(): JSX.Element {
  const leakedFields: string[] = useMemo(() => [], [])   // ← 恒为空数组
  // ……渲染时 `leakedFields.length > 0` 永远为假 → C6 兜底提示永远不出现
}
```

`findLeakedFields` 是全仓**唯一**的 C6 渲染前敏感词兜底入口（grep 确认：
`src` 下仅 `RunResultPage.tsx:22` 一处定义，**0 处调用**）。
而 `RunResultPage` 的 `leakedFields` 被硬编码为 `useMemo(() => [], [])`。

**根因**：页面整体被 G5 阻塞（结果接口未开放），目前渲染的是占位内容，
没有真实结果数据流经 `findLeakedFields`。导出该函数本意是「接口接入时**必须**显式调用」，
但当前**没有任何调用点** → C6「结果不得含身份信息」的**兜底扫描实际上不生效**
（声明了，但 inert）。

**后果**：与 F-22（`hasRoleAccess` 四重死代码）同源 —— 不是「代码坏了」，是
「关键防线**接了线但没通电**」。一旦结果接口接上、开发者忘了调用 `findLeakedFields`，
C6 红线会静默失效而不被任何测试发现。

**处置**（与 F-22 一致，**不接假数据**）：

1. 补**契约测试**锁住 `findLeakedFields` 确实能识别泄漏字段
   （手机号/身份证命中，正常数学内容不误杀），保证「接上时一定有效」；
2. 在文件头与本文档显式标注「**当前未接入渲染路径，C6 兜底 inert**」的已知限制，
   避免有人以为它已在工作；
3. 真正接入须等 G5 结果接口就绪，届时显式调用 `findLeakedFields(payload)`。

**可证伪**：`tests/result.leak.test.ts` 断言含手机号/身份证的字段被识别、
正常数学题不被误杀。该测试直接验证被-export-却-未接 的函数的契约，
确保未来接入时不会因函数本身有 bug 而失效。

---

### 1.3 F-25 路由/链接完整性不变式（预防性，非修复）

**动机**：FE-D5 把「组件用的 CSS 类 ⊆ 主样式层已定义」做成了 CI 不变式，
成功防止了再次裸奔。同一思路复制到**路由层**：

> 每个 `Link` / `NavLink` 的 `to` 目标，必须能匹配到一个已声明的 `<Route path>`。

**实测扫描结果（当前）**：穷尽 `src` 下所有 `to=` 字面量 / 模板前缀，
与 `App.tsx` 的 18 条 `<Route path>` 交叉比对 —— **当前无结构性坏链**
（所有 `to` 都能匹配到路由，含参数路由 `/profile/:studentId`）。

但发现一处**语义死链（已知限制）**：

- `DashboardPage` 工作台「开始」区有 `<Link to="/profile/current">学习画像</Link>`。
  它结构性能匹配 `/profile/:studentId`（studentId="current"），但本应用**没有「当前用户」概念**
  （仪表盘是设备级的，不存 studentId），且整页被 G6 阻塞 → 点进去只会看到
  「匿名编号 current / 学习画像还没接上后端」。这是个**指向无意义字面量 id 的死链**。

**处置**：

- 新增 `tests/routing.integrity.test.ts` 作为 CI 不变式：未来任何人写出
  `to="/xxx"` 而忘了加对应 `<Route>`，CI 立即转红（防回归）。
- `/profile/current` 不强行改（涉及被阻塞的骨架测试，且改动属产品决策），
  **列为已知限制**（见 §2）：等 G6 接真实 studentId 时再补正确入口。

---

## 2. 明确不修（及理由）

| 项 | 理由 |
|---|---|
| 删 `findLeakedFields` 或强行接入 | G5 未就绪，无真实结果数据可扫；强行接入 = 造数据。F-22 已确立「不接假实现」纪律，改为锁契约 + 标注已知限制 |
| 改 `/profile/current` 为其他目标 | 无「当前用户」概念，改任何字面量都是拍脑袋；该产品决策等 G6 承接时由真实 studentId 驱动 |
| 给 `status--pending/running/neutral` 补修饰样式 | `layout.css:511` 注释已说明这三者「沿用基线 `.status`」，非遗漏 |

---

## 3. 测试设计（每项修复/锁都要可证伪）

| # | 测试 | 断言 |
|---|---|---|
| T1 | F-23：`stop()` 后再让 in-flight 响应返回 | `onSnapshot` 调用次数停在初始 1 次（不再 emit）|
| T2 | F-23 反证：撤掉 emit 守卫 | T1 转红 |
| T3 | 收到终态后 `done=true` 且**不再发第二次请求** | `last.done===true`；`openEventStream` 仅被调用 1 次 |
| T4 | F-24：`findLeakedFields` 识别手机号/身份证字段 | 返回数组含对应字段名 |
| T5 | F-24：`findLeakedFields` 不误杀正常数学内容 | 返回 `[]` |
| T6 | F-25：所有字面量 `to` / NAV 项都能匹配到 `<Route>` | 每个目标均有覆盖；参数路由放行 |
| **T7** | **反证**：删一条 `<Route>` 使某 `to` 失配 | 该用例转红 |

---

## 4. DoD

- [x] F-23 修复：`emit` 加 `stopped` 守卫
- [x] F-23 / F-24 / F-25 均配套可证伪测试，反证逐项执行
- [x] 不带任何筛选的**全量** `vitest run` 通过，连跑稳定
- [x] `tsc --noEmit` 零错误
- [x] `vite build` 成功
- [x] 台账数字实测更新；FE 文档索引追加 FE-D6

---

## 5. 已知限制（诚实记录）

1. **C6 敏感词兜底当前 inert**：`findLeakedFields` 未接入渲染路径
   （`RunResultPage` 的 `leakedFields` 恒为 `[]`）。契约已锁，等 G5 接真实数据时显式调用。
2. **`/profile/current` 语义死链**：仪表盘「学习画像」入口指向固定字面量 id，
   无「当前用户」概念且整页被 G6 阻塞。结构性不坏（能匹配路由），列为已知限制，等 G6 承接。
3. F-23 在生产（无 StrictMode 双挂载）下的实际影响极小（卸载后 setState 为 no-op），
   修复的真正价值是**恢复 `stop()` 契约** + 消除开发期双挂载的偶发覆盖。

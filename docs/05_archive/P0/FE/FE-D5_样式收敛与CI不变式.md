# FE-D5 样式收敛与 CSS 完整性不变式

> 阶段：FE（前端）｜日期：2026-10-20｜依赖：FE-D3（视觉规范）、FE-D4（F-17）
>
> ⚠️ 本阶段**修正了 FE-D4 的一个错误假设**（见 §6）。

---

## 0. 为什么有这一轮

FE-D4 §2「明确不修」第 1 行、§5「已知限制」第 2 条都记录：

> `legacy-backup.css`（685 行）仍是双份样式源，**其中 19 个类仍被组件使用，现在删会直接破样式**，列为待收敛项。

用户指令「继续前端代码优化」，我重新对齐状态时发现这条记录**与事实不符**。

---

## 1. 实测发现：legacy-backup.css 从未被加载

`index.css` 只 `@import` 了 `tokens.css` 与 `layout.css`：

```css
@import './styles/tokens.css';
@import './styles/layout.css';
```

全仓库搜索 `legacy-backup` 的真实加载（`@import` / JS `import` / `<link>`）——
**零命中**。它只有注释里提到，从 FE-D3 起就**没有被并入 bundle**。

**推论**：legacy 里定义的类，在生产环境**一个样式都没提供过**。
FE-D4 的「19 个类仍在使用、删了会破样式」是**反向结论**——
真实情况是那些类长期裸奔，删了反而是清理。

---

## 2. 精确比对：哪些类被组件使用、却只在 legacy 定义

方法（与 FE-D4 §1.3 同口径，但这次穷尽而非采样）：

1. 收集组件里**写死在引号里**的全部 `className`（静态 + 模板串字面量；变量引用 `{cls}` 跳过）
2. 收集 `tokens.css` ∪ `layout.css` 定义的全部选择器类
3. 取「组件使用 ∩ 未在主样式层定义」

结果：**8 个类**（被组件使用、且只存在于 legacy）：

| 类名 | 使用方 | 后果 |
|---|---|---|
| `error` | 进度页错误提示（`role=alert`）| 裸奔 |
| `field__errors` | `Field` 校验错误列表 `<ul>` | 裸奔 |
| `not-available` | `NotAvailable` 组件 | 裸奔 |
| `status` (+ `--success`/`--failure`) | 进度页状态区 | 裸奔 |
| `status__label` | 进度页状态区 | 裸奔 |
| `status__hint` | 进度页状态区 | 裸奔 |
| `notice` | 进度页降级提示（`role=status`）| 裸奔 |
| `unavailable` | `Unavailable` 组件外壳 | 裸奔（内层 `__box/__title/__meta` 已在 layout 定义）|

> 注：`new-run` / `run-progress` 仅出现在注释（历史迁移说明），真实代码无引用；
> `status--pending/running/neutral` 经 `status--${tone}` 生成，基线 `.status` 已定义即覆盖，不单独列。

**这比 FE-D4 的 F-17 更严重**：F-17 漏的 6 个类（页面根容器、时间线标签）影响排版一致；
这 8 个直接影响**错误提示、表单校验错误、状态区、未开放占位**——都是用户可见的关键反馈。

---

## 3. 为什么不能「直接加载 legacy」

legacy 用的设计 token **已经被 FE-D3 淘汰**：

- 引用 `--color-danger` / `--color-border` / `--radius` 等 —— 当前 `tokens.css` 里**不存在**
- 写法用 `border-radius` 圆角卡片 —— 违反 FE-D3「禁圆角禁阴影、区块靠线与留白」

直接 `@import` 会拉回一套已废弃的设计系统，与 FE-D3 纪律冲突。

---

## 4. 修复方案（FE-D3 纪律内收敛）

### 4.1 迁移 8 个类进 `layout.css`

遵循 FE-D3：区块靠 **1px 实线 + 留白** 分隔，禁圆角禁阴影；
**批改红（`--mark`）只给错误**，状态色用低饱和 `--settled-wash` / `--mark-wash`。

落点：`layout.css` 末尾响应式段之前（line 466-553）。

关键定义（节选）：

```css
.error        { color: var(--mark); font-size: var(--t-note); }      /* 错误：批改红 */
.field__errors{ color: var(--mark); list-style: disc; }              /* 校验错误：批改红 */
.not-available{ border-top:1px solid var(--rule); border-bottom:1px solid var(--rule); text-align:center; }  /* 未开放：中性，不染红 */
.status       { border-left:3px solid var(--rule-strong); background:var(--paper-sunk); }
.status--success { border-left-color:var(--settled); background:var(--settled-wash); }
.status--failure { border-left-color:var(--mark);   background:var(--mark-wash); }
.notice       { border:1px solid var(--rule); background:var(--paper-sunk); }
.unavailable  { margin-top: var(--s-4); }                            /* 外壳，内层 __box 已定义 */
```

引用的 14 个 token（`--mark`/`--rule`/`--rule-strong`/`--settled`/`--paper-sunk`/
`--settled-wash`/`--mark-wash`/`--ink-2`/`--t-body`/`--t-note`/`--s-1`/`--s-3`/`--s-4`/
`--font-mono`）**逐个校验均已定义**，无静默失效。

### 4.2 删除 `legacy-backup.css`

迁移完成后它 100% 是死文件（未被加载、其类已迁移或本就无人用）。已删除，
`index.css` 注释同步更新为「已于 FE-D5 收敛并删除」。

---

## 5. 新增 CI 不变式（防止再次裸奔）

**根因复盘**：FE-D4 的 F-17 只靠人工比对 + 单测断言 6 个具体类，
没有把「组件用的类 ⊆ 主样式层已定义」变成**可执行的不变式**，所以漏了这 8 个。

新增 `tests/css.integrity.test.ts`，强制该不变式：

| 测试 | 断言 |
|---|---|
| 遗留清理 | `legacy-backup.css` 已删除 |
| 迁移落点 | 8 个（含 2 个 status 修饰符）已在主样式层定义 |
| **不变式** | 组件写死用到的每个类名，主样式层都有定义（豁免 `is-`/`has-` 前缀、`status--*` 动态变体、TS 关键字噪声）|

豁免项说明：`is-*`/`has-*` 是状态/特性类（运行时切换，不要求样式）；
`status--${tone}` 动态生成但基线 `.status` 已定义即覆盖；
TS 关键字（`string`/`object`/...）会被模板串误抓，显式豁免。

---

## 6. ⚠️ 对 FE-D4 的更正

FE-D4 §2 与 §5 关于 legacy 的记录**有误**，更正如下：

| FE-D4 原记录 | 实测事实 |
|---|---|
| legacy 是「双份样式源，19 个类仍被使用」 | legacy **从未被 index.css 加载**，不是样式源；其类在生产环境**零样式** |
| 「现在删 legacy 会直接破样式」 | 删了只清理死文件；真正破样式的是**没迁移的那 8 个类**，已在本轮迁移 |
| 列为「待收敛项」 | 本轮已收敛并删除 |

> 教训（与后端 F-10/F-12 同源）：**「看起来存在的防线，实际不存在」**。
> 判定「legacy 是否加载」靠查 `@import` 链，不是看文件在不在仓库里——
> 文件在仓库 ≠ 被打进 bundle。

---

## 7. 验证（全部实测）

| 项 | 结果 |
|---|---|
| 单元/组件测试 | **202 项全绿**（12 文件），连跑稳定（基线 199 + 新增 3）|
| TypeScript 严格模式 | `tsc --noEmit` **零错误** |
| 反证 | 删 `.notice` → 不变式测试 **2 项精确转红**；恢复 → 3 项全绿（测试有牙齿）|
| 生产构建 | `vite build` 成功：JS 230.60 kB（gzip 74.46）、CSS 11.73 kB（gzip 2.95）|
| 产物层复核 | 10 个迁移类（8 + 2 status 修饰符）**真的打进 dist CSS** |
| 版式纪律 | 禁 box-shadow ✅、禁 shimmer ✅、三断点（1023/767/479）✅ |

---

## 8. DoD

- [x] 8 个裸奔类迁移进 `layout.css`，遵循 FE-D3 纪律
- [x] `legacy-backup.css` 已删除，全仓库无真实加载引用
- [x] 新增 `css.integrity.test.ts` 不变式，反证有效
- [x] FE-D4 错误记录已更正（§6）
- [x] 全量 `vitest run` 通过；`tsc` 零错误；`vite build` 成功；台账数字实测更新

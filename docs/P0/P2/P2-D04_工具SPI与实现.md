# P2-D04 工具 SPI 与两个工具实现设计

> **阶段**：P2 核心闭环 MVP · 第 4 日
> **状态**：📝 设计稿（编码前）
> **对应验收**：FR-008 Tool SPI、FR-003 幂等
> **依据**：[D-007 业务契约](../P2/D-007_业务契约_工具接口.md)、P0-D03 §4 红线 4/5/7

---

## 1. 职责边界

| 属于 D4 | 不属于 D4 |
|---|---|
| 两个工具的 `ToolHandler` 实现 | 工具**注册与装配**（Spring 配置） |
| 输入 Schema 校验 | 真实业务系统调用（依赖 D-007） |
| 画像读取 / 薄弱点写入 | 审批流（P3） |
| 幂等与 UNKNOWN 语义 | SSE（D5） |

> **刻意做 Mock**：P0-D05 允许「只读 Mock Tool 用于 PoC」。
> Mock 与真实实现的**契约完全一致**，D-007 就绪后只换实现不改调用方。

---

## 2. 组件划分

```
infrastructure/tool/
├── GetStudentProfileTool   只读，读 student_profile + weakness_record
├── RecordWeaknessTool      有副作用，写 weakness_record
├── ProfileRepository       画像数据访问（走 tenantConnection）
└── WeaknessRepository      薄弱点数据访问
```

> ⚠️ **数据访问必须复用 `tenantConnection(tenantId)`**
> （见 P1 教训：`set_config(is_local)` 必须配合事务，否则 RLS 绑定失效）。

---

## 3. `get_student_profile`（只读）

### 3.1 关键行为

| 情形 | 行为 | 理由 |
|---|---|---|
| 学生不存在 | 返回**空画像**（`weaknesses: []`） | 避免通过响应差异枚举有效 studentId |
| studentId 格式非法 | **拒绝**（Schema 校验） | 不做"尽力解析" |
| 跨租户 | **拒绝**（授权 + RLS 双重） | — |

> ⚠️ 「不存在返回空」而非报错，是为了**不泄漏哪些 studentId 有效**。
> 若不存在报错、存在返回数据，攻击者可枚举。

### 3.2 Schema 严格性

```json
{
  "type": "object",
  "required": ["studentId"],
  "properties": { "studentId": { "type": "string", "pattern": "^stu_[a-z0-9]{16}$" } },
  "additionalProperties": false
}
```

⚠️ `additionalProperties: false` —— 多传字段直接拒绝。
否则客户端传姓名、学校等被忽略的字段，看似无害实则**掩盖越界输入**。

### 3.3 输出白名单

仅四个字段：`studentId` / `grade` / `subject` / `weaknesses[]`。
表结构层面已无其他列（V2 迁移），**结构上无法输出可识别信息**。

---

## 4. `record_weakness`（有副作用）

### 4.1 幂等语义（红线 4）

```
幂等键 = student_id + run_id + knowledge_point
```

| 情形 | 行为 |
|---|---|
| 同键 + 同摘要 | 返回首次结果（`replayed: true`），**不重复写入** |
| 同键 + **异摘要** | **冲突**，抛异常 |
| 首次 | 插入，`attempt` 记录 |

> ⚠️ DB 唯一约束 `(tenant_id, student_id, run_id, knowledge_point)`
> 是**最后防线**。应用层并发检查阻止不了竞态，只有 DB 能兜住。

### 4.2 UNKNOWN 语义（红线 7）

**这是本工具最关键的路径。**

| 场景 | 行为 |
|---|---|
| 明确失败（Schema 错、约束冲突） | `definitelyFailed()` → 可重试 |
| 插入后响应丢失 | `unknownResult()` → **禁止重试** |
| `reconcile()` 查到记录 | → `RECONCILED_SUCCEEDED` |
| `reconcile()` 确认不存在 | → `RECONCILED_ABSENT` |
| `reconcile()` 返回 null | → `MANUAL_DISPOSED`（**转人工，绝不重试**） |

> ⚠️ **「下游不可查」时绝不能重试** —— 盲重试即重复写入 = Sev-0。

### 4.3 fencing（红线 5）

写入携带 `fencingToken`。旧世代写入被
`enforce_fencing_on_write` 触发器**数据库层拒绝**。

> 工具本身不判断世代 —— 判断在执行循环，兜底在数据库。
> 两层各自独立，缺一不可。

---

## 5. 摘要一致性（易错点）

幂等判定依赖请求摘要。⚠️ **摘要是对参数计算的，不是对整个上下文**：

```
正确：RequestDigest.of(arguments)      ← 仅工具参数
错误：RequestDigest.of(整个 Map 含 runId、tenantId）
```

若把 `runId`、`tenantId` 算进去，则同一逻辑操作在不同 Run 中
摘要不同 → 幂等失效 → 重复写入。

---

## 6. 失败与边界

| 情形 | 行为 | 理由 |
|---|---|---|
| 学生不存在时记录薄弱点 | 拒绝（外键约束） | 画像与薄弱点须一致 |
| 知识点超长 | 拒绝（DB 约束） | 防超长输入注入 |
| 置信度越界 | 拒绝（DB 约束） | — |
| errorType 非法枚举 | 拒绝 | 自由文本会使知识点发散 |
| 跨租户写入 | 拒绝（RLS） | — |

---

## 7. 测试覆盖计划

| 分组 | 命题 |
|---|---|
| Schema 校验 | 缺字段、格式错、**多传字段**均被拒 |
| 画像读取 | 存在/不存在/跨租户三种情形 |
| 幂等 | 同键同摘要 → 不重复写；同键异摘要 → 冲突 |
| **UNKNOWN** | 响应丢失 → `unknownResult()`，**不重试** |
| reconcile | 三种返回各有正确处置 |
| 摘要 | **不含 runId/tenantId**（否则幂等失效） |

> ⚠️ **摘要范围**是最容易被忽略的测试点 ——
> 它决定幂等是否成立，却是「看起来对」的代码。

---

## 8. 已知限制（编码前预判）

| # | 限制 | 影响 | 处置 |
|---|---|---|---|
| 1 | Mock 实现，无真实业务调用 | 业务验收不可用 | D-007 就绪后替换 |
| 2 | 未接审批流 | `REQUIRED` 时被 fail-closed 拒绝 | P3 |
| 3 | 预算预留为接口占位 | 只验证顺序 | P3 |
| 4 | 工具未接入 Spring 装配 | 需手工构造闸门 | P2 末 |
| 5 | 无并发测试 | 幂等竞态未实测 | P3 故障注入 |

> **限制 5 需注意**：DB 唯一约束是幂等的最后防线，
> 但「并发下确实只有一条」尚未实测。P3 须补并发验证。

---

## 9. 与其他阶段的关系

| 依赖 | 方向 |
|---|---|
| D-007 契约 | 本实现遵循其 Schema |
| V2 迁移 | 表结构（19 表 / 83 约束） |
| D3 `ToolInvocationGate` | 本类作为 `ToolHandler` 被调用 |
| **P1 `tenantConnection`** | 数据访问必须复用 |

---

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-19 | 初稿（编码前） |
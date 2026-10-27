# P6 · AI-Agent 效果测试与执行效果度量（规划）

> 定位：为「这个 Agent 到底跑得好不好」提供**可观测、可回归、可比对**的答案。
> 本文是**规划**，配套已交付的前端页面见 `admin-console/src/pages/agentlab/AgentLabPage.tsx`（P6-1）。

---

## 1. 目标与非目标

### 目标
| 编号 | 目标 | 判据 |
|------|------|------|
| G-1 | 单人单次能发起一次真实执行并看到结果 | 选定 Agent → 输入 → 执行 → 终态可见 |
| G-2 | 执行效果的四类维度可量化 | 质量 / 成本 / 时延 / 合规各自有数 |
| G-3 | 同一 Agent 的多次执行可横向比对 | 历史列表 + 关键指标列 |
| G-4 | 改动 Agent 配置后能回归验证 | 评测集 + 基线 + 差异报告 |
| G-5 | 效果恶化可被**发现**而不只是被**看到** | 指标越界触发告警 |

### 非目标（刻意不做）
- ❌ **不在平台控制台展示执行输出正文**。Agent 定义正文与运行输出不出现在平台控制台是禁区红线，
  这一点不会因为「方便调试」而放宽。效果判定只能基于**结构化指标**。
- ❌ 不做在线 A/B 流量分配。那是租户域运行时的事，不是平台控制台的事。
- ❌ 不把效果度量写成「模型打分」。自评分数不可复现、不可审计，只作为**参考信号**，不作结论。

---

## 2. 现状盘点（诚实）

| 能力 | 现状 | 缺口 |
|------|------|------|
| 发起执行 | ✅ `POST /api/runs`（合规 → Prompt 防护 → 执行时授权 → 限流 → 创建） | 平台令牌对**租户域**端点可达性存疑（401/403） |
| 查状态 | ✅ `GET /api/runs/{id}`（state / attempt / errorCode / 时间戳） | **无**耗时、无 token 消耗、无工具调用明细 |
| 事件流 | ✅ `GET /api/runs/{id}/events`（SSE） | `EventSource` 无法携带 `Authorization` 头 → 浏览器侧不可用 |
| 输出正文 | ⛔ 刻意不暴露 | 质量类指标必须改用「受控评测」间接度量 |
| 成本 | ⚠️ `budgetAmount` 只作上界传入 | **没有**实际消耗回写，无法回答「花了多少」 |
| 评测集 | ❌ 不存在 | 无基线即无回归 |

**结论**：今天能回答「跑没跑完」，**回答不了**「跑得好不好、花了多少、比上次差多少」。
P6 就是补这三问。

---

## 3. 执行效果的四类观测维度

```
                    ┌──────────── 质量 Quality ────────────┐
                    │ 任务成功与否 / 错误码分布 / 重试率     │
                    │ 引用命中率（RAG）/ 工具调用成功率      │
                    └──────────────────────────────────────┘
   ┌──────────── 成本 Cost ────────────┐   ┌──────────── 时延 Latency ───────────┐
   │ token 消耗 / 工具调用次数 / 预算占用 │   │ 端到端耗时 / 首字延迟 / 队列等待      │
   └───────────────────────────────────┘   └─────────────────────────────────────┘
                    ┌────────── 合规与安全 Compliance ──────────┐
                    │ 合规拦截次数 / Prompt 防护拒绝 / 授权拒绝   │
                    │ 越权尝试（deny）/ 数据分类越界             │
                    └──────────────────────────────────────────┘
```

| 维度 | 指标 | 数据来源（现状 → 需要补的） |
|------|------|--------------------------|
| 质量 | 终态分布（SUCCEEDED / FAILED / TIMED_OUT / CANCELLED） | ✅ `run.state` |
| 质量 | 失败原因码 TOP N | ✅ `run.error_code` |
| 质量 | 重试率（`attempt > 1` 占比） | ✅ `run.attempt` |
| 质量 | **工具调用成功率** | ❌ 需 `tool_operation` 聚合 |
| 质量 | **引用命中率 / 命中片段数** | ❌ 需 RAG 检索侧埋点（可与 `rag` 模块检索探针联动） |
| 成本 | **token 消耗** | ❌ 需回写 `run.usage` |
| 成本 | 预算占用率 | ❌ 需 `budget_reserved` vs `budget_consumed` |
| 成本 | 工具调用次数 | ❌ 需事件计数 |
| 时延 | 端到端耗时 | ✅ `completed_at - created_at` |
| 时延 | **队列等待**（created → 首次 RUNNING） | ❌ 需状态迁移时间线 |
| 时延 | **首字延迟** | ❌ 需事件时间戳 |
| 合规 | 合规拦截次数 | ❌ 需拦截计数（不记录被拦内容原文） |
| 合规 | Prompt 防护拒绝 | ❌ 需拒绝计数 |
| 合规 | **授权拒绝（deny）** | ✅ 已有 `AdminMonitoringController.denies` |

⚠️ **合规类指标只记次数与原因码，绝不记被拦内容的原文**——
否则「防泄漏」的防线会被自己的观测系统击穿。

---

## 4. 数据模型（需要补的埋点）

### 4.1 扩展 `run`（最小增量，不改语义）
```sql
ALTER TABLE public.run ADD COLUMN IF NOT EXISTS
    first_started_at TIMESTAMPTZ,      -- 队列等待 = first_started_at - created_at
    token_input      INTEGER,          -- 实际消耗（非上界）
    token_output     INTEGER,
    tool_call_count  INTEGER DEFAULT 0,
    tool_fail_count  INTEGER DEFAULT 0,
    retrieval_hits   INTEGER DEFAULT 0,
    compliance_block_count INTEGER DEFAULT 0,
    guard_block_count      INTEGER DEFAULT 0;
```

⚠️ **为什么是「加列」而不是「新建宽表」**：这些字段与 Run 一对一、
生命周期一致，拆表只会引入一次 join 与一处可能不同步。

### 4.2 新增评测集（P6-3）
```sql
CREATE TABLE platform.agent_eval_set (
    id          UUID PRIMARY KEY,
    agent_id    UUID NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE platform.agent_eval_case (
    id            UUID PRIMARY KEY,
    eval_set_id   UUID NOT NULL REFERENCES platform.agent_eval_set(id) ON DELETE CASCADE,
    input_text    TEXT NOT NULL,
    -- ⚠️ 期望只用**结构化判据**，不存期望输出全文（禁区红线同样适用于评测）
    expect_state  VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    expect_tags   TEXT[],
    sort_order    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE platform.agent_eval_run (
    id           UUID PRIMARY KEY,
    eval_set_id  UUID NOT NULL,
    baseline     BOOLEAN NOT NULL DEFAULT FALSE,  -- 基线运行，供后续比对
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    passed_count INTEGER NOT NULL DEFAULT 0,
    total_count  INTEGER NOT NULL DEFAULT 0,
    summary_json JSONB                             -- 各维度聚合值
);
```

---

## 5. 事件模型与链路

```
POST /api/runs ──► run(QUEUED) ──► Worker 领取 ──► run(RUNNING, attempt+1)
                                        │
                                        ├─ tool_operation（成功/失败计数）
                                        ├─ retrieval（命中片段计数）
                                        ├─ compliance / guard（拦截计数，不记原文）
                                        └─ usage（token 消耗回写）
                                        │
                                        ▼
                             run(SUCCEEDED | FAILED | TIMED_OUT | CANCELLED)
                                        │
                                        ▼
                             效果聚合 → 评测比对 → 越界告警
```

⚠️ **SSE 的现实约束**：浏览器 `EventSource` 无法设置 `Authorization` 头，
把令牌塞进 query 会落进访问日志与 `Referer`。在提供**一次性票证**
（短时效、单次使用、绑定 runId）之前，**轮询是唯一不牺牲凭据安全的做法**。
这一点已写进 `agentLabApi.ts` 的注释，不在文档里含糊过去。

---

## 6. 前端能力矩阵

| 能力 | 本次已交付（P6-1） | P6-2 | P6-3 | P6-4 |
|------|:---:|:---:|:---:|:---:|
| 租户 / Agent 选择器 | ✅ | | | |
| 用例输入 + 超时 / 预算 | ✅ | | | |
| 发起执行 + 幂等键 | ✅ | | | |
| 状态轮询 + 轨迹时间线 | ✅ | | | |
| 终态 / 耗时 / 重试 / 失败码 | ✅ | | | |
| 历史执行列表（会话内） | ✅ | | | |
| 成本指标（token / 预算占用） | | ✅ | | |
| 时延细分（队列等待 / 首字） | | ✅ | | |
| 工具调用与检索命中明细 | | ✅ | | |
| 合规拦截计数面板 | | ✅ | | |
| 评测集 CRUD | | | ✅ | |
| 基线比对与差异报告 | | | ✅ | |
| 指标越界告警（Webhook） | | | | ✅ |
| 跨版本趋势图 | | | | ✅ |

---

## 7. 分期路线

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **P6-1**（已完成） | 效果测试页面：发起执行 + 轮询 + 轨迹 + 历史 | 无 |
| **P6-2** | 指标补齐：run 扩展列 + Worker 回写 + 前端指标卡 | 后端埋点 |
| **P6-3** | 评测集与回归：eval_set / case / run + 基线比对 | P6-2 |
| **P6-4** | 越界告警 + 趋势：阈值配置 + Webhook + 趋势视图 | P6-3 + 告警通道（已有 `AlertWebhookPort` 可复用） |

---

## 8. 风险与诚实边界

| 风险 | 说明 | 处置 |
|------|------|------|
| **R-1 通道** | 平台令牌对租户域 `/api/runs` 可能 401/403 | 页面明示「需租户级令牌通道」，**不**伪装成功；长期方案是平台侧代理执行端点或发放短时效租户票证 |
| **R-2 看不到输出** | 执行输出不出现在平台控制台（禁区红线） | 质量判定改用结构化判据（终态 / 错误码 / 命中率），**不**为调试便利破例 |
| **R-3 指标污染** | 重试会放大 token / 耗时 | 所有成本与时延指标按 `attempt` 分组统计，不混在平均值里 |
| **R-4 观测击穿防线** | 合规指标若记录被拦原文，等于自己泄漏 | 只记 **次数 + 原因码** |
| **R-5 基线漂移** | Agent 配置变更后基线失效 | 基线运行显式打标；配置版本变化时在报告上标注「基线版本 ≠ 当前版本」 |

---

## 9. 验收口径

P6 各阶段完成时必须能回答：

1. 这个 Agent 最近 N 次执行的**成功率、P50/P95 耗时、平均成本**是多少？
2. 哪一类**失败原因码**占比最高？
3. 改了配置之后，这些数字**变好了还是变差了**，差多少？
4. 数字越界时，**谁**会**什么时候**知道？

答不上其中任何一问，该阶段就不能算完成。

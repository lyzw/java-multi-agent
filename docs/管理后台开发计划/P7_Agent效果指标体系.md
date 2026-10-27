# P7 · Agent 效果指标体系设计

> 面向 **线上 Agent 的工业界通用指标**，六大类、可直接上 Grafana 看板。
> 约定：**一条 Trace = 一次用户 Agent 会话任务，`trace_id` 唯一标识**。

---

## 0 · 结论先行（读完这段就能用）

| 结论 | 说明 |
|---|---|
| **① 最高可信的不是「成功率」，是「终止原因分布」** | 「链路成功」只说明框架没崩，**不等于任务干成了**。真正能定位问题的是终止原因（谁把任务掐断的）。 |
| **② 六大类里只有两类今天能全量算出** | **SLA/可用性**与**成本**可 100% 从现有事实表（`run` / `tool_operation` / `usage_record` / `budget_reservation`）算出，无需新增埋点。 |
| **③ 另外三类今天算不出来，但表必须先建** | **业务效果**（自动校验 / 抽样标注）、**用户反馈**、**风险安全**目前**没有任何采集点**。本次先把表结构和录入端点建好，指标在未接入前一律返回 **`null`（空）**，**绝不返回 0**——`0% 幻觉率` 和「没测过幻觉」是两件完全不同的事。 |
| **④ Agent 过程行为指标缺一个前置埋点** | `run_step` 表已建但**执行链路从未写入**（全库 0 处代码引用）。平均工具轮次、单轮思考耗时这类指标必须先把 step 埋点补上，否则只能退化为按 `tool_operation` 近似。本次实施按「近似口径 + 显式标注退化」处理。 |
| **⑤ 用户反馈是预警信号，不是真值** | 差评率上涨 → **触发抽样复核**，不直接判定 Agent 劣化（下游工具挂了也会导致差评）。这条纪律写进代码注释与 UI 文案。 |

### 可信度分级（本文所有指标都挂这个标签）

| 层级 | 名称 | 含义 | 使用纪律 |
|---|---|---|---|
| **L0** | 系统事实 | 执行链路直接落库，无解释空间 | **高可信 · 优先看**。可直接下结论 |
| **L1** | 自动终态校验 | 外部系统回执 / 终态存在性校验 | **高可信**，但**必须同时显示覆盖率**——只校验了 5% 的任务，成功率再漂亮也是 5% 的成功率 |
| **L2** | 抽样标注 | 人工 / 标注平台打标 | **黄金基线**，但**必须带样本量 n**；`n < 30` 一律显示「样本不足，不足以判断」 |
| **L3** | 有噪信号 | 用户显式反馈、行为信号 | **辅助 · 只看趋势**。不能单独当真值，只用于触发复核 |

> ⚠️ **派生规则**：**「假成功占比」= L1 ∩ L2 的交集指标**。任一为空则整体为 `null`，不允许用单边数据推算。

---

## 1 · 概念模型

### 1.1 术语

| 术语 | 定义 |
|---|---|
| **Trace** | 一次用户发起的 Agent 会话任务，端到端唯一 `trace_id`。本文所有指标的**统计单元**。 |
| **Run** | 执行侧载体（`public.run`）。一条 Trace **通常**对应一个 Run；重试/续跑可能产生多个 Run —— 此时以**首个** Run 为准，`run_count` 单独计数。 |
| **Step** | Trace 内的一次「思考 / 工具调用 / 追问」原子动作（`public.run_step`）。 |
| **Round（轮次）** | 一次「模型推理 → （可选）工具调用」循环。**平均轮次 = 平均步数**。 |
| **Trace 收敛** | Trace 对应的 Run 到达终态（`SUCCEEDED` / `FAILED` / `CANCELLED` / `TIMED_OUT`）。**只有收敛后的 Trace 才计入指标**，在途不计入——否则「还没跑完」会被算成「未成功」，让成功率失真（与 `StatisticsPort` 既有铁律同源）。 |

### 1.2 `trace_id` 现状与迁移口径（诚实边界）

| 项 | 现状 | 处理 |
|---|---|---|
| `run.trace_id` | **不存在**（`run` 只有 `correlation_id`） | 本次 DDL 新增列，**可空** |
| `agent_invocation.trace_id` | **存在** | 委派链路可用，但**仅覆盖有委派的 Trace** |
| 存量 Run | 无 `trace_id` | 降级取 `correlation_id`，并在 metric 行写 `trace_id_source='CORRELATION_FALLBACK'` |

> ⚠️ **降级来源必须落库可见**。用 `correlation_id` 冒充 `trace_id` 而不留标记，等于用猜测冒充事实——下游会误以为两个 ID 体系同源。

---

## 2 · 数据源盘点（现状可算 / 需新增埋点）

| 事实表 | 是否被代码写入 | 本次可算出的指标 |
|---|---|---|
| `public.run` | ✅（`JdbcRunCreationRepository` / `JdbcRunQueryRepository`） | QPS、链路成功率、终止原因、端到端耗时 |
| `public.tool_operation` | ✅（`JdbcToolOperationLedger`） | 工具调用次数、工具维度分布、工具失败率 |
| `public.tool_operation_attempt` | ✅ | 单次工具耗时、超时/5xx 判定、重试次数 |
| `public.usage_record` | ✅（`JdbcBudgetRepository`） | 输入/输出 token、费用 |
| `public.budget_reservation` | ✅ | 预算熔断（token 上限）判定辅助 |
| `public.agent_invocation` | ✅ | 多 Agent 委派深度、`trace_id`（委派链路） |
| `public.run_event` | ✅（`JdbcRunEventAppendRepository`） | 事件序列（追问、拦截等可做事件类型约定） |
| `public.run_step` | ❌ **全库 0 处引用** | ⚠️ 单轮思考耗时、平均步数 —— **需先补埋点** |
| 用户反馈 | ❌ 无 | ⚠️ 需新增 `agent_trace_feedback` |
| 抽样标注 | ❌ 无 | ⚠️ 需新增 `agent_eval_sample` |
| 安全/越权/敏感输出 | ❌ 无 | ⚠️ 需新增 `agent_risk_event` |
| 自动终态校验 | ❌ 无 | ⚠️ 需新增 `agent_task_outcome` |

---

## 3 · 六大类指标定义

> 图例：优先级 `✅必配` / `✅高价值` / `✅告警` / `⭕辅助`；可信度 `L0`~`L3`。

### 一、SLA & 可用性指标（技术稳定性）

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| SLA-1 | 请求 QPS / 并发数 | 单位时间新发起 Trace 数；并发 = 处于 `QUEUED`+`RUNNING` 的 Trace 数 | `count(*)/interval`；`count(state in ('QUEUED','RUNNING'))` | `run` | L0 | ✅必配 | Time series（双轴） | ✅ 可算 |
| SLA-2 | **链路成功率** | 终态为 `SUCCEEDED` / 终态总数。**≠任务成功** | `sum(state='SUCCEEDED')/sum(state in 4 终态)` | `run` | L0 | ✅必配 | Stat + 趋势 | ✅ 可算 |
| SLA-3 | **终止原因分布** | 归一化 9 值：`COMPLETED` / `USER_CANCELLED` / `MODEL_ERROR` / `TOOL_TIMEOUT` / `TOOL_5XX` / `MAX_ROUNDS` / `SAFETY_BLOCK` / `TOKEN_BUDGET` / `OTHER` | 见 §3.1 映射规则 | `run`+`tool_operation_attempt` | L0 | ✅必配，根因核心 | Pie / Bar gauge | ✅ 可算（`SAFETY_BLOCK` 需 P7-4） |
| SLA-4 | 端到端耗时 P50/P95/P99 | `completed_at - created_at` | `percentile_cont(0.5/0.95/0.99)` | `run` | L0 | ✅必配 | Time series（三条线） | ✅ 可算 |
| SLA-5 | 单轮思考耗时 P50/P95 | 每次模型推理（规划）耗时 | `percentile_cont` over step(`MODEL`) | `run_step` | L0 | ✅必配 | Time series | ⚠️ **需 step 埋点**（P7-2） |
| SLA-6 | 工具调用耗时 P50/P95 | 按 `tool_name` 分组的单次调用耗时 | `percentile_cont` over attempt | `tool_operation_attempt` | L0 | ✅必配 | Time series + 按 tool 拆分 | ✅ 可算 |
| SLA-7 | 工具调用失败率 | 该工具 `FAILED`/`UNKNOWN`/超时 次数 / 该工具总调用次数 | `sum(failed)/sum(total)` group by tool | `tool_operation` | L0 | ✅必配 | Bar gauge + 告警 | ✅ 可算 |

**告警建议**：SLA-2 跌破基线 3σ；SLA-3 中 `MODEL_ERROR`+`TOOL_5XX` 合计占比 > 10%；SLA-7 单工具 > 20%。

#### §3.1 终止原因映射规则（唯一权威）

```
state = SUCCEEDED                                   → COMPLETED
state = CANCELLED                                   → USER_CANCELLED
state = TIMED_OUT + deadline_at 触发（整体超时）      → TOOL_TIMEOUT（若为工具超时） / OTHER
state = FAILED:
    error_code 属安全拦截集合（SAFETY_*）             → SAFETY_BLOCK
    error_code 属预算集合（BUDGET_* / TOKEN_*）       → TOKEN_BUDGET
    error_code 属轮次上限（MAX_STEPS / MAX_ROUNDS）   → MAX_ROUNDS
    error_code 属模型侧（MODEL_* / LLM_*）            → MODEL_ERROR
    attempt.error_class = TIMEOUT                    → TOOL_TIMEOUT
    attempt.error_class ∈ (5XX, UPSTREAM_ERROR)       → TOOL_5XX
    其余                                             → OTHER
```

> ⚠️ **兜底必须是 `OTHER` 而不是丢弃**。丢弃未识别原因会让「终止原因分布」的合计小于总数，看板上出现「不知道哪去了」的黑洞。

---

### 二、成本指标（预算管控）

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| COST-1 | 总输入 / 输出 token | 按模型、Agent 版本、业务场景分组聚合 | `sum(input_tokens)` / `sum(output_tokens)` | `usage_record` | L0 | ✅必配 | Bar gauge（按模型堆叠） | ✅ 可算 |
| COST-2 | 单任务平均 token | 每 Trace 平均消耗（输入 / 输出 / 合计） | `sum(tokens)/count(distinct trace)` | `usage_record` | L0 | ✅必配 | Stat + 趋势 | ✅ 可算 |
| COST-3 | 单任务平均费用 | 折算金额，做预算告警 | `sum(cost_amount)/count(distinct trace)` | `usage_record` | L0 | ✅必配 | Stat + 阈值线 | ✅ 可算（依赖单价表） |
| COST-4 | **超大 token 异常占比** | 总 token > 阈值（默认 20k）的 Trace 占比，通常指向死循环 | `count(total>20000)/count(*)` | `usage_record` | L0 | ✅必配告警 | Stat + Alert | ✅ 可算 |

**告警建议**：COST-4 > 1% 立即告警（死循环特征）；COST-3 环比上涨 > 30% 提示。

> ⚠️ **成本是「已计量」口径，不是「已结算」口径**。`usage_record` 记的是计量账，与 `budget_reservation` 的结算账存在差异（预留未结算、UNKNOWN 未对账）。看板上这两个数字**分开呈现**，不合并。

---

### 三、Agent 过程行为指标（判断规划好不好 —— Agent 特有）

> 这一类是与普通 Chat 最大的区别，用来发现「乱调工具、绕路、死循环」。

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| BEH-1 | **平均工具轮次（平均步数）** | 一条 Trace 内调用工具的轮数；**同样任务步数上涨 = 规划变差** | `count(step where type='TOOL')/count(trace)` | `run_step` → 退化 `tool_operation` | L0 | ✅高价值 | Time series（按 Agent 版本分线） | ⚠️ 需 step 埋点；**本期用 `tool_operation` 近似并标注退化** |
| BEH-2 | **最大轮次触达率** | 走到配置上限（如 10 轮）才停止的 Trace 占比 | `count(rounds >= limit)/count(*)` | `run_step` / `run.error_code` | L0 | ✅高价值 | Gauge + 告警 | ⚠️ **本期用 `MAX_ROUNDS` 终止原因近似** |
| BEH-3 | **无效调用率** | 「重复调用（同工具+同参数）」+「调用不存在工具」+「参数非法」/ 总调用次数 | 见 §3.2 | `tool_operation` | L0 | ✅高价值 | Stacked bar | ✅ 可算 |
| BEH-4 | **循环调用次数** | 同一 `(tool_name, request_digest)` 在**单条 Trace 内出现 ≥3 次** | `count(group by trace,tool,digest having count>=3)` | `tool_operation` | L0 | ✅告警项 | Stat + Alert（单独告警） | ✅ 可算 |
| BEH-5 | 主动追问占比 / 平均追问次数 | Agent 向用户索要信息；过高 = 缺上下文或规划预判差 | `count(clarify>0)/count(*)`、`avg(clarification_count)` | `run_step`(ASK_USER) / `run_event` | L0 | ⭕辅助 | Time series | ⚠️ 需事件约定（P7-2） |
| BEH-6 | 工具选择分布 | 各工具调用频次，看是否过度依赖某工具 | `count group by tool_name` | `tool_operation` | L0 | ⭕辅助 | Pie / Top-N bar | ✅ 可算 |

#### §3.2 无效调用的三类判据（互斥、有序）

| 类型 | 判据 | 说明 |
|---|---|---|
| `UNKNOWN_TOOL` | `tool_name` 不在该 `snapshot_id` 生效的 `cap_tool_def` 闭包内 | 模型幻觉出工具 |
| `INVALID_ARGUMENT` | `attempt.error_class = 'INVALID_ARGUMENT'`（或参数校验失败标记） | 参数非法 |
| `DUPLICATE` | 同 Trace 内 `(tool_name, request_digest)` 出现 ≥2 次 | 重复调用（幂等键已存在即算重复） |

> ⚠️ **同一条调用只归入一类**，优先级 `UNKNOWN_TOOL > INVALID_ARGUMENT > DUPLICATE`，避免分子重复计数导致「无效调用率」超过 100%。

---

### 四、业务效果指标（核心：衡量到底能不能干活）

> 分两层：**自动校验（可信、覆盖有限）** 与 **抽样标注（黄金基线、需人工成本）**。

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| BIZ-1 | **自动校验任务成功率** | 可自动校验子集：外部终态校验（工单创建、数据写入、文件生成、查询结果合规）通过率 | `count(verified=true)/count(checked)`，**附覆盖率** | `agent_task_outcome`（新增） | **L1** | ✅最高可信 | Stat + **覆盖率副标签** | ❌ 需接入 checker |
| BIZ-2 | **抽样标注任务成功率** | 人工/标注平台抽检后判定「是否达成目标」 | `count(verdict='SUCCESS')/count(sampled)`，**附样本量 n** | `agent_eval_sample`（新增） | **L2** | ✅黄金指标 | Stat + 样本量 n | ❌ 需标注流程 |
| BIZ-3 | **假成功占比** | 自动校验通过、但人工复核判定结果错误的占比（幻觉 / 表面正确） | `count(verified=true AND verdict<>'SUCCESS')/count(verified=true AND sampled)` | L1 ∩ L2 | **L1∩L2** | ✅非常关键 | Stat + Alert | ❌ 双源均缺 |
| BIZ-4 | **幻觉率** | 结果事实错误、篡改工具返回信息的样本占比 | `count(hallucination=true)/count(sampled)` | `agent_eval_sample` | **L2** | ✅重要质量指标 | Stat + 趋势 | ❌ 需标注 |
| BIZ-5 | 指令遵从率 | 是否遵守约束、格式、权限要求 | `count(instruction_followed=true)/count(sampled)` | `agent_eval_sample` | **L2** | ⭕辅助 | Stat | ❌ 需标注 |

> ⚠️ **BIZ-1 的覆盖率是同一个指标的一部分，不是可选的补充信息**。只校验了 3% 任务时，「自动校验成功率 99%」这句话本身就会误导——UI 上强制并排显示 `99%（覆盖 3%）`。
>
> ⚠️ **BIZ-2/BIZ-4/BIZ-5 在 `n < 30` 时显示为「样本不足」而不是显示百分比**。用 2 条样本算出的 50% 幻觉率是噪声，不是指标。
>
> ⚠️ **BIZ-3 为 `null` 当且仅当 L1 或 L2 任一为空**。禁止单边推算。

---

### 五、用户反馈指标（全量低成本信号 —— **有噪声，不能单独当真值**）

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| FB-1 | 好评率 / 差评率 | 用户显式 👍/👎 占**完成会话**比例（分母排除未完成） | `count(score=UP)/count(feedback on completed)` | `agent_trace_feedback`（新增） | **L3** | ✅预警信号 | Time series（双线） | ❌ 需前端埋点 |
| FB-2 | 会话重试率 | 同 session 内用户重复提交**相似请求** | `count(retry)/count(session)` | `agent_trace_feedback` | **L3** | ✅预警信号 | Stat | ❌ 需 session 关联 |
| FB-3 | 中途放弃率 | Agent 仍在执行，用户关闭/退出会话 | `count(abandoned)/count(*)` | `agent_trace_feedback` | **L3** | ✅预警信号 | Stat + 趋势 | ❌ 需前端埋点 |

> ⚠️ **纪律（写进代码与 UI）**：差评率上涨 → **触发抽样复核**，**不**直接判定 Agent 劣化。下游工具故障、网络抖动、UI 卡顿都会导致差评。这三条只能用于「该去看一眼了」，不能用于「Agent 变差了」。
>
> ⚠️ **分母纪律**：FB-1 的分母是**已完成会话**。把「还在跑」的算进分母会让差评率被稀释（在途的既没好评也没差评）。

---

### 六、安全 & 风险指标

| # | 指标 | 口径定义 | 计算 | 数据源 | 可信度 | 优先级 | Grafana 面板 | 采集现状 |
|---|---|---|---|---|---|---|---|---|
| SEC-1 | **安全拦截率** | 高危请求被安全层拒绝、禁止执行 | `count(blocked)/count(*)` | `agent_risk_event` + `termination_reason=SAFETY_BLOCK` | L0 | ✅必配 | Stat + Alert | ⚠️ 部分（终止原因可算，明细事件待埋点） |
| SEC-2 | **越权调用尝试次数** | Agent 尝试调用无权限工具 / 访问越权数据 | `count(type='UNAUTHORIZED_CALL')` | `agent_risk_event`（新增） | L0 | ✅告警项 | Counter + Alert | ❌ 需埋点 |
| SEC-3 | **敏感信息输出命中次数** | 输出命中手机号 / 身份证等隐私模式 | `count(type='SENSITIVE_OUTPUT')` | `agent_risk_event`（新增） | L0 | ✅合规 | Counter + Alert | ❌ 需埋点 |

> ⚠️ **合规红线**：`agent_risk_event` **只存 `detail_digest`（摘要）与类型计数，绝不落原文**。命中敏感信息的原文一旦入库就构成二次泄露，等于把「拦截住了」变成「存下来了」。

---

## 4 · 数据模型

### 4.1 表清单

| 表 | 作用 | 粒度 | 写入方 |
|---|---|---|---|
| `public.run` **（加列）** | `trace_id`（可空，存量可空） | 1 行 / Run | 执行链路 |
| `public.agent_trace_metric` | **指标宽表（核心）**，每 Trace 一行 | 1 行 / Trace | 采集器（幂等 upsert） |
| `public.agent_task_outcome` | 自动终态校验结果（L1） | 1 行 / (Trace, checker) | 外部 checker 回执 |
| `public.agent_eval_sample` | 抽样标注（L2） | 1 行 / (Trace, 标注轮次) | 标注平台 / 管理台录入 |
| `public.agent_trace_feedback` | 用户反馈事件流（L3） | 多行 / Trace | 前端埋点 |
| `public.agent_risk_event` | 安全事件明细（脱敏） | 多行 / Trace | 安全层 |
| `mv_agent_metric_hourly` | **物化视图**：小时桶聚合，供 Grafana 时序直读 | 1 行 / (小时, Agent, 场景) | 定时刷新 |

### 4.2 为什么不建「桶表」

分位数（P95/P99）**无法从计数与求和还原**。所以：

- **计数/求和类**（QPS、成功率、费用、token、轮次均值）→ 走物化视图 `mv_agent_metric_hourly`，Grafana 读它，成本低、响应快；
- **分位数类**（P50/P95/P99）→ **直接查明细表** `agent_trace_metric`（带 `(tenant_id, started_at)` 索引，24h 窗口内毫秒级）。

这是工业界标准做法：物化视图扛时序，明细扛分位。

### 4.2.1 为什么指标表放 `platform` schema

事实源（`public.run` / `tool_operation` / `usage_record`）带 **FORCE RLS**，采集器必须在**租户上下文**里读它们（这是对的，租户侧隔离不能破）；而指标是**平台观测域**数据，需要跨租户聚合。`platform` schema 无 RLS（与 `sys_dictionary` / `platform_audit_event` 一致）。

> **读在租户域、写在平台域** —— 两侧边界都干净，不必为跨租户聚合去削弱 RLS。

### 4.3 `agent_trace_metric` 关键列（完整定义见 DDL）

> ⚠️ **终止原因单一权威在 metric，不在 `run`**。`run` 只补 `trace_id`，不再加 `termination_reason` —— 同一字段两处落库必然漂移。

分组列：`trace_id` / `tenant_id` / `run_id` / `agent_id` / `agent_version` / `scenario` / `model_id` / `started_at`
SLA：`duration_ms` / `terminal_state` / `termination_reason`
成本：`input_tokens` / `output_tokens` / `cost_amount` / `currency`
行为：`tool_rounds` / `tool_call_total` / `tool_call_failed` / `tool_call_invalid` / `tool_call_duplicate` / `loop_suspected_count` / `max_rounds_reached` / `clarification_count` / `think_ms_total` / `tool_ms_total`
效果：`auto_checked` / `auto_verified` / `sampled` / `human_verdict` / `hallucination` / `instruction_followed`
反馈：`feedback_up` / `feedback_down` / `retry_count` / `abandoned`
风险：`safety_blocked_count` / `unauthorized_attempt_count` / `sensitive_output_hits`
元信息：`trace_id_source` / `metric_version` / `computed_at`

> ⚠️ **所有「暂无采集」的列一律 `NULL`，默认值不是 0**。以 `DEFAULT 0` 会让「没测过」在 SQL 里变成「测了是 0」，这是本项目反复出现的失真来源。

---

## 5 · 采集与聚合架构

```
执行链路 (run / tool_operation / usage_record / budget_reservation)
        │  Run 到达终态
        ▼
AgentMetricCollector.collect(traceId)        ← 幂等：以 trace_id 主键 upsert
        │  ① 读事实表  ② 派生指标  ③ upsert agent_trace_metric
        ▼
agent_trace_metric  ─────────────►  mv_agent_metric_hourly（定时 REFRESH CONCURRENTLY）
        ▲                                    │
        │ 回填                                ▼
agent_eval_sample / agent_trace_feedback / agent_risk_event / agent_task_outcome
                                        Grafana (PostgreSQL datasource)
                                        admin-console 指标看板（走 API）
```

**三条铁律**：

1. **幂等**：指标是「事实表的确定函数」，重复采集必须得到同一结果 —— 用 `INSERT ... ON CONFLICT (trace_id) DO UPDATE`。
2. **只算收敛 Trace**：在途不计入，避免把「没跑完」算成「没成功」。
3. **后端回填，前端不推算**：`auto_verified` / `sampled` / `human_verdict` 由后端在录入时回写 metric 行，前端只做展示。前端自行计算 `假成功率` 会与后端口径漂移。

---

## 6 · API 设计

平台域（`/api/platform/v1/metrics/**`，`platform.resource.read` 闸门）：

| 端点 | 说明 |
|---|---|
| `GET /metrics/agent/overview` | 六类核心指标总览（含可信度标签、覆盖率、样本量） |
| `GET /metrics/agent/timeseries` | 时序（QPS / 成功率 / 费用 / 轮次），按小时桶 |
| `GET /metrics/agent/latency` | 分位数（端到端 / 思考 / 工具，P50/P95/P99） |
| `GET /metrics/agent/terminations` | 终止原因分布 |
| `GET /metrics/agent/tools` | 工具维度：失败率、耗时、调用分布 |
| `GET /metrics/agent/anomalies` | 超大 token / 循环调用 / 最大轮次触达的 Top-N 明细 |
| `POST /metrics/agent/eval-samples` | 录入抽样标注（回写 metric） |
| `POST /metrics/agent/feedback` | 录入用户反馈事件 |
| `POST /metrics/agent/risk-events` | 录入风险事件（仅摘要） |

查询参数统一：`from` / `to` / `agentId` / `agentVersion` / `scenario` / `tenantId`（平台域可跨租户，租户域强制令牌租户）。

---

## 7 · 前端看板设计（admin-console）

- 新页 **「Agent 效果指标」**，位于新分组 **「观测度量」**。
- 六大分区，每区一张卡：指标数值 + **可信度徽标**（L0 事实 / L1 自动校验 / L2 抽样 / L3 有噪）+ **副信息**（覆盖率 / 样本量 n / 告警阈值）。
- **空值呈现纪律**：无数据显示 `—（未采集）`，**不显示 `0`**；`n < 30` 显示 `样本不足`；L1 指标强制并排显示覆盖率。
- **不引入图表库**（延续 P4/P5 零新依赖决策）：趋势用 antd `Progress` 条形 + 表格，分布用条形占比。Grafana 才是时序图的主场。

---

## 8 · 分期实施

| 阶段 | 内容 | 交付标志 |
|---|---|---|
| **P7-1（已完成 · 2026-10-27）** | DDL + 采集器 + 查询 API + 看板页。**SLA / 成本 / 工具行为**真实可算；效果/反馈/风险**建表且返回 null** | 看板上 SLA 与成本有真数，其余显示「未采集」 |
| **P7-2（已完成 · 2026-10-27）** | 补 `run_step` 埋点（思考/工具/追问三类 step）→ BEH-1/BEH-2/SLA-5/BEH-5 转为真实口径；`run.error_code` 真实写入 | 平均步数不再标注「退化近似」；终止原因归因真实（不再兜底 OTHER） |
| **P7-3（已交付 · 2026-10-27）** | DDL + 录入端点（`/task-outcomes` `/eval-samples`）+ 回写 `auto_checked/verified/sampled/human_verdict` 已就绪；**admin-console 标注录入 UI 已建**（`MetricEntryPage`，四表单 + 路由 + 导航） | 端点已可录，BIZ-1~BIZ-5 不再因「无录入入口」而恒为空 |
| **P7-4（已交付 · 2026-10-27）** | Worker 侧 SAFETY_BLOCK / UNAUTHORIZED_CALL / SENSITIVE_OUTPUT 真实发射 + 录入端点（`/feedback` `/risk-events`）；**tenant 前端反馈按钮已建**（`RunFeedback`，四个显式动作）+ **新增租户侧端点** `POST /api/metrics/feedback` | SEC-2/SEC-3 明细真实可采；FB-1~FB-3 有前端数据流 |

---

## 8.1 · P7-1 落地清单（已交付）

| 层 | 产物 |
|---|---|
| DDL | `deploy/sql/platform/08_platform_agent_metrics_ddl.sql`（run 补 trace_id；5 张指标表 + 工具维度表 + 小时桶物化视图） |
| 端口 | `ports/metrics/AgentMetricPort`（写入/聚合/录入）、`ports/metrics/TraceFactPort`（只读事实） |
| 基础设施 | `JdbcAgentMetricRepository`（写平台域 + 聚合查询）、`JdbcTraceFactRepository`（租户上下文读事实表） |
| 应用 | `AgentMetricCollector`（终止原因派生 + 行为指标计算 + 幂等采集） |
| 接口 | `PlatformAgentMetricsController`（`/api/platform/v1/metrics/agent/**`，读 `resource.read` / 写 `resource.manage`） |
| 前端 | `admin-console/src/pages/metrics/AgentMetricsPage` + `api/metricsApi`，导航新增「观测度量」分组 |
| 测试 | 采集器 11 项（终止原因 9 值 + 未采集=null + 重复/循环口径）、看板 8 项（未采集≠0 + 样本量纪律 + 口径警告） |

**实施中被纠正的两个设计错误**：

1. **平台侧不能跨租户直读 `public` 表**。`run` / `tool_operation` 带 FORCE RLS，系统连接下 `current_tenant_id()` 为空 → 静默返回空集。工具维度指标因此新增 `platform.agent_trace_tool_call`，由采集器在租户上下文读完后落到平台域。
2. **`agent-application` 不得依赖 `agent-infrastructure`**。初版采集器直接用了 `TenantSessionBinder`，违反分层；改为抽象 `TraceFactPort`，租户绑定下沉到基础设施实现。

---

## 8.2 · P7-2 落地清单（已交付 · 2026-10-27）

| 层 | 产物 |
|---|---|
| 端口 | `ports/persistence/RunStepPort`（步骤写入，`Types.THINK/TOOL/ASK_USER/TERMINAL`）+ `TraceFactPort.StepFacts`（只读聚合：think 耗时 / 工具步数 / 追问数 / 最大步号）；`RunClaimPort.commitResult` 新增 6 参（带 `errorCode`） |
| 基础设施 | `JdbcRunStepRepository`（租户上下文内写 `public.run_step`，幂等覆盖，**失败吞异常**）+ `JdbcTraceFactRepository.readSteps`（聚合 step 事实）+ `JdbcRunClaimRepository`（6 参 `commitResult` 真实写 `run.error_code`）+ `V30__run_step_fencing_guard.sql`（闭合 P3-D02 L-1 缺口） |
| 执行 | `RunExecutionLoop`：循环内 `appendStep(THINK)`（模型调用前后）+ `appendStep(TOOL)`（工具闸门前后）；`commitFailure` 全调用点带真实 `errorCode`（`SAFETY_FABRICATED_SOURCE` / `TOOL_REJECTED` / `MAX_STEPS` / `MODEL_*` / `TOOL_INTERNAL` / `INTERNAL_ERROR`） |
| 装配 | `ApplicationConfiguration.runStepPort` 显式 `@Bean`（沿用 P4-D07 惯例，repository 不挂 `@Repository`，否则组件扫描与显式装配可能重复）；`WorkerExecutionConfig` 注入 `RunStepPort` |
| 应用 | `AgentMetricCollector`：`tool_rounds` / `think_ms_total` / `clarification_count` 由退化近似切换为 `StepFacts` 真实口径；未埋点旧 Run 仍返回 `null`（不混同趋势线） |
| 前端 | `AgentMetricsPage` 平均工具轮次标注由「⚠️ 退化近似」改为「来自 run_step 真实埋点；旧 Run 未采集显示『未采集』」 |
| 测试 | `RunExecutionLoopTest` 新增 3 项（纯文本 1 条 THINK / 含工具 THINK+TOOL+THINK / 步骤写入失败不冒泡）；`AgentMetricCollectorTest` 新增 2 项（真实 step 驱动 tool_rounds+think_ms；未埋点 → null） |

**关键纪律（全链路贯穿）**：
- **未采集 = `NULL`，绝不等于 0**：旧 Run（P7-2 前）无 `run_step`，`tool_rounds/think_ms_total/clarification_count` 一律 `null`，看板显示「未采集」，不并入真实口径趋势线。
- **步骤写入失败绝不冒泡到主流程**：`appendStep` / `JdbcRunStepRepository.append` 均吞异常记 WARN——一次本可成功的 Run 不能因「步骤没记下」而变失败。
- **`error_code` 真实归因**：`run.error_code` 此前从不写入，`FAILED` 终止原因永远兜底 `OTHER`；现由 `commitFailure` 各分支真实落码，终止原因分布（SLA-3）不再失真。

---

## 8.3 · P7-3 / P7-4 后端落地清单（已交付 · 2026-10-27）

### P7-3 · 业务效果数据流（L1/L2）

| 层 | 产物 |
|---|---|
| DDL | `platform.agent_task_outcome`（L1 回执，`trace_id+checker_code` 唯一）+ `platform.agent_eval_sample`（L2 标注，verdict 三值 CHECK）——随 08 脚本已建 |
| 端点 | `POST /api/platform/v1/metrics/agent/task-outcomes`（外部 checker 回执）· `/eval-samples`（标注录入），均 `platform.resource.manage` 闸门 |
| 回写 | `JdbcAgentMetricRepository.recordTaskOutcome / recordEvalSample` 落明细表并回写宽表 `auto_checked/auto_verified/sampled/human_verdict/hallucination/instruction_followed` |
| 待建 → **已交付** | **admin-console 标注录入 UI**：`MetricEntryPage`（四张表单卡：L1 校验回执 / L2 抽样标注 / 风险事件 / L3 用户反馈），共享 traceId + tenantId 字段，成功/失败以 `Alert` 呈现（不依赖 antd App 上下文，便于测试） |

### P7-4 · 反馈 / 风险安全数据流（L3/L0）

| 层 | 产物 |
|---|---|
| 端口 | `ports/persistence/RiskEventPort`（三值类型常量 + 合规红线契约：只收 `detailDigest`，无原文通道） |
| 基础设施 | `JdbcRiskEventRepository`：写 `platform.agent_risk_event`（平台域无 RLS）；**trace_id 三级解析与采集器逐字同口径**（run.trace_id → invocation(depth=1) → correlation_id → 解析不出则放弃，宁缺毋假）；失败吞异常记 WARN |
| 执行 | `RunExecutionLoop` 三个 null-safe 发射点：① 编造出处拦截 → `SAFETY_BLOCK`（摘要=拦截规则，不含原文）；② 工具授权拒绝 → `UNAUTHORIZED_CALL`（摘要=工具名+原因；**刻意与 BEH-3 无效调用区分**——未登记/Schema 拒绝不落 SEC-2，避免安全告警被噪声淹没）；③ 终态输出命中隐私模式 → `SENSITIVE_OUTPUT`（复用 `DataClassification.IdentifyingPattern` 合规正则，**只观测不阻断**，摘要=模式代号） |
| 端点 | `POST /api/platform/v1/metrics/agent/feedback`（THUMB_UP/DOWN/RETRY/ABANDON）· `/risk-events`（仅收 detailDigest，**请求体刻意无原文字段**） |
| 装配 | `ApplicationConfiguration.riskEventPort` 显式 `@Bean` + `WorkerExecutionConfig` 注入（`required=false`，缺失时发射点静默跳过） |
| 待建 → **已交付** | **tenant 前端反馈按钮**：`RunFeedback`（四个**显式**动作：有帮助 / 没帮助 / 再讲一次 / 先不看了）→ `POST /api/metrics/feedback`；配套新增**租户侧端点**（见 §8.4） |
| 测试 | `RunExecutionLoopTest` 新增 4 项：SAFETY_BLOCK（摘要不含原文）/ UNAUTHORIZED_CALL 与 BEH-3 区分（负例：未登记工具 0 条）/ SENSITIVE_OUTPUT（模式代号）/ 写入失败不冒泡 |

---

## 8.4 · P7-3 / P7-4 前端落地清单（已交付 · 2026-10-27）

### 为什么必须新增「租户侧反馈端点」

平台侧 `POST /api/platform/v1/metrics/agent/feedback` 要求 `platform.resource.manage`（**平台令牌**），
而 tenant 前台持有的是**租户令牌**，直调必然 403。
只留平台端点 → 前端的反馈按钮要么不存在、要么点了必然失败——**按钮是死的**。
故新增 `POST /api/metrics/feedback`（租户侧），并把权限门从「平台权限」降为「任意已认证租户用户」。

### 8.4.1 租户侧端点（后端 · 缺了它按钮就点不动）

| 层 | 产物 |
|---|---|
| 端点 | `TenantFeedbackController`：`POST /api/metrics/feedback`，请求体**只有** `runId` 与 `kind` |
| 租户归属 | `tenantId` 由 `RunController.CurrentPrincipal.context()`（**已验签令牌**）派生，**不接受请求体里的 tenantId**；无令牌 → 401（`ApiExceptionHandler` 统一映射） |
| traceId 解析 | **前端只给 runId**，traceId 由服务端解析（`TraceFactPort.resolveTraceId`：run.trace_id → 委派链路 invocation(depth=1) → correlation_id 降级，三者皆无则 404）——浏览器无权断言内部会话标识 |
| 租户谓词 | `resolveTraceId` 的 SQL **显式带** `AND tenant_id = ?::uuid`（纵深防御，不只依赖 RLS 会话变量）；`JdbcAgentMetricRepository.recordFeedback` 的回填 UPDATE 补上 `AND tenant_id = ?::uuid`（此前只按 trace_id 更新，租户 A 可改写租户 B 的指标行） |
| 端口 | `TraceFactPort.resolveTraceId(tenantId, runId)`（**不要求 Run 已收敛**——在途 Run 也可反馈，如「放弃」） |
| 测试 | `TenantFeedbackControllerTest` 9 项：落库租户取自已验签主体 / traceId 取自服务端解析 / 解析用已验签租户（绝不用他租户解析）/ 大小写不敏感 / 解析不到 → 404 不落库 / 缺字段·非法 kind·非法 runId → 400 不落库 / 无令牌 → 401 不落库 |

### 8.4.2 前端（admin-console + tenant 前台）

| 层 | 产物 |
|---|---|
| admin-console | `api/metricsApi` 增 4 个录入函数 + 请求类型 + 枚举中文标签；`pages/metrics/MetricEntryPage`（四表单）；`router.tsx` 与 `navConfig.tsx` 接线（观测度量分组下新增「效果数据录入」） |
| tenant 前台 | `api/types.ts` 增 `RunFeedbackKind`；`api/client.ts` 增 `recordRunFeedback(runId, kind)`；`features/run/RunFeedback.tsx`（四按钮 + 已记录状态 + 失败提示）；`RunResultPage` 在「讲解」区之后接入 |
| 合规 | 反馈只落**枚举** kind（无自由文本输入框）；状态**只在内存**（不落 localStorage，符合 C7）；**不传 tenantId、不传 traceId** |
| 反推断 | **不做自动埋点**：不靠「离开页面」推断 ABANDON、不靠「点了几次」推断 RETRY——推断信号会污染指标口径（「再辅导一道」是换题，不是重试） |
| 测试 | admin-console `metricEntry.test.tsx` 6 项；tenant 前台 `runFeedback.test.tsx` 11 项（连通性 + 反证：上报参数只有 runId 与 kind，JSON 里不得出现 tenant/trace）+ `runFeedback.client.test.ts` 4 项（线路契约：URL / method / Content-Type / 请求体形状，**不 mock client 层**，测真实拼装） |

**测试基线（2026-10-27 · 补完 P7-3/P7-4 前端后重测）**：platform 8 模块 `mvn -o clean test -DskipITs` 全绿——agent-core 178 / ports 14 / testsupport 11 / application 614 / infrastructure 217 / worker 245 / api **181**（+9 = `TenantFeedbackControllerTest`）/ bootstrap 82（26 跳过 = 需 broker 的连通性 IT）。admin-console `vitest` **317/317**（27 文件）；tenant 前台 `vitest` **536/536**（33 文件）+ `tsc --noEmit` 0 错。

## 9 · 风险与红线

| # | 风险 | 处置 |
|---|---|---|
| R-1 | `链路成功率` 被误读为「任务干成了」 | UI 上强制副标题「框架未崩溃 ≠ 任务成功」，与 BIZ-2 分区隔离 |
| R-2 | 未采集指标显示 0 → 假装测过 | 全链路 `NULL + 显式「未采集」文案`，DDL 不设 `DEFAULT 0` |
| R-3 | 低样本量下 L2 指标被当真 | `n < 30` 强制显示「样本不足」 |
| R-4 | `run_step` 无埋点导致步数类指标虚高/虚低 | 本期退化口径**显式标注**，P7-2 补埋点后切换 |
| R-5 | 敏感信息原文入库造成二次泄露 | `agent_risk_event` 只存 `detail_digest`，**禁止存原文**（DDL 层 COMMENT + 代码层只接 digest） |
| R-6 | 用户差评被直接判定为 Agent 劣化 | 反馈类指标只触发复核，UI 文案明确「下游故障也会导致差评」 |
| R-7 | 物化视图刷新期间读到旧数据 | `REFRESH CONCURRENTLY` + 响应体带 `refreshedAt`，前端展示数据时效 |

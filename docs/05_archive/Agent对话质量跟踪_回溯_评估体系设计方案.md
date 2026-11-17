# Agent 对话质量跟踪 / 回溯 / 评估体系设计方案

> 适用范围：初中生错题归因辅导平台（`cn.studykid.multiagent`）。
> 技术栈：Spring Boot 4.1.1 + Java 21 + PostgreSQL 17 + RabbitMQ 4.3.6 + Micrometer。
> 写作前提：**先查证现状，再谈设计**。本方案不与已交付的 P7 指标体系重复，而是补其缺口。

---

## 0 · 现状基线（先说清楚什么已经有了）

项目已有一个相当完整的 **P7 Agent 效果指标体系**（`docs/管理后台开发计划/P7_Agent效果指标体系.md`，2026-10-27 交付），"质量评估"的骨架已立：

| 能力 | 现状 | 落地位置 |
|---|---|---|
| 指标宽表（六类、L0–L3 分层） | ✅ 表与列已建 | `platform.agent_trace_metric`、`agent_trace_tool_call` |
| 小时桶物化视图（Grafana 直读） | ✅ DDL 已建 | `platform.mv_agent_metric_hourly` |
| 采集器（终止原因派生 + 行为指标） | ✅ 类已写，但**只暴露按需 HTTP，无自动触发** | `AgentMetricCollector` + `PlatformAgentMetricsController.collect()` |
| 效果/反馈/风险数据通道 | ✅ 表 + 录入端点 + 回写已就绪 | `agent_eval_sample` / `agent_trace_feedback` / `agent_risk_event` / `agent_task_outcome` |
| 前端看板 | ✅ 六类指标卡 + 异常 Top-N + trace 下钻 | `admin-console/src/pages/metrics/AgentMetricsPage.tsx` |
| 配置快照（不可变） | ✅ Run 创建时固化 | `public.execution_snapshot`（agent/prompt/tool/run 配置 + version_id） |

**三处尚未闭环的真缺口（本方案聚焦）：**

1. **对话内容未持久化 → 无法回放**。
   - `run_step` 表虽有 `input_payload`/`output_payload` 列，但 worker 的 `appendStep()`（`RunExecutionLoop.java:1076`）只落 `step_number/step_type/started_at/completed_at` **计时字段，不存内容**。
   - 对话历史 `List<ModelMessage>` 仅为 `RunExecutionLoop` 内存变量，仅 `run.input_payload`（用户原始输入）与 `run.result_payload`（最终产物）两段 jsonb 落库。
   - 即：除首尾两帧，**中间每一轮 Agent 说了什么、工具返回了什么，数据库里没有**——对"错题归因辅导"这种内容即产品的场景，等于没有复盘素材。

2. **指标采集器无自动触发 → 宽表空转**。
   - `AgentMetricCollector.collect()` 仅由 `PlatformAgentMetricsController`（平台侧按需 HTTP）和 admin-console "补算指标"按钮调用，**生产中没有 Run 终态钩子**调用它。
   - Run 终态已发事件：`RUN_SUCCEEDED`/`RUN_FAILED`/`RUN_CANCELLED`/`RUN_PARKED`（`RunEventPort.Types`，由 `commitSuccess/commitFailure` 经 `eventPort.append` 落 `run_event`）。这是天然的采集触发点，目前没接。

3. **内容级质量评估缺自动化**。
   - L2（抽样标注）依赖人工在 `MetricEntryPage` 手动录入；L1（`agent_task_outcome`）依赖外部 checker 回执，目前无内置"对错题归因正确性"的自动判分器。
   - 纯 L0 指标（成功率/耗时/工具失败率）回答不了"归因对不对、讲解是否对准学生"。

---

## 1 · 是否需要（必要性分析）

### 1.1 结论：需要，但范围是"补 P7 缺口"，不是从零搭建

| 维度 | 是否已覆盖 | 缺口 |
|---|---|---|
| 指标定义、可信度分层、宽表 schema | 已覆盖（P7） | 仅"自动采集"未接 |
| 内容级对话跟踪与回放 | **未覆盖** | 无 `run_message`，`run_step` 不存内容 |
| 内容级质量评估（归因正确性） | 部分（人工 L2 + 外部 L1 占位） | 无内置自动判分、无回访式复盘 |
| 监控看板 | 部分（admin-console + Grafana 物化视图） | Grafana 无看板文件；无回放视图 |

**确有需要**，理由：
- 本产品是**教育内容产品**，对话内容本身就是交付物，也是合规审查对象。没有内容留存，既无法做质量复盘，也无法满足教育/未成年人相关数据可审计要求。
- 当前指标系统"建好但空转"——看板空白会被误读为"健康"。没有自动采集，质量恶化（如某次 prompt 改动导致归因正确率下降）无法被及时发现。
- 错题归因的高度领域性决定了 **L0 行为指标≠内容质量**。要回答"这题讲对了吗"，必须回到对话内容本身，做 rubric 化评估。
- 缺少"异常 trace → 回放 → 定位到具体某一轮"的链路，根因分析（模型？prompt？工具？数据？）无从下手。

### 1.2 当前缺体系的具体风险

1. **质量事故不可复盘**：某租户反馈"Agent 把知识点讲错了"，但库里只有输入和最终答案，中间推理/工具调用缺失，无法还原当时发生了什么，也无法判定责任在模型、prompt 还是知识库。
2. **指标失真被当成"无问题"**：`agent_trace_metric` 无自动采集 → 看板长期空白或仅零星手动补算数据 → 真实的质量回归被掩盖。
3. **无法做内容合规审计**：教育内容合规（错题讲解是否含不当内容、是否泄露未成年人信息）需要可回放的原文与审查留痕，目前无原文、无审查流。
4. **根因定位链路断裂**：`tool_operation` 有工具 I/O、`run_step` 有步序、`usage_record` 有 token，但三者之间没有"这一轮 Agent 到底说了什么"的桥，问题定位只能靠猜。
5. **L1/L2 覆盖率低且无抓手**：人工标注靠自觉，没有"异常优先抽样 + 审查队列 + 覆盖率看板"的闭环，黄金基线永远长不大。

---

## 2 · 体系设计

> 总原则：**复用 P7 已有的指标宽表、采集器、录入端点、配置快照**；新增大头只有"对话内容持久化（回放底座）"和"自动采集接线"，以及把人工 L2 升级为"人工 + 自动判分"双轨。

### 2.1 质量跟踪：记录什么、怎么埋点

#### 2.1.1 新增数据：`run_message`（租户域，带 RLS）

建议**新增专用对话消息表**（而非往 `run_step` 的 payload 列塞内容），理由见 §5 取舍 A。

```sql
-- 租户域，与 run/run_step 同纪律：FORCE RLS + tenant_id 策略
CREATE TABLE public.run_message (
    message_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL,
    run_id          uuid NOT NULL,
    trace_id        text,                 -- 与 agent_trace_metric 同口径（run.trace_id→invocation→correlation 降级）
    step_number     integer,              -- 关联 run_step.step_number，定位到具体一轮
    parent_message_id uuid,               -- 树形：assistant → tool_call → tool_result → assistant
    role            text NOT NULL CHECK (role IN ('SYSTEM','USER','ASSISTANT','TOOL')),
    content         jsonb NOT NULL,       -- 文本 / tool_call 描述 / tool 结果摘要；敏感字段走 digest
    tool_operation_id uuid,               -- role=TOOL 时关联 tool_operation.operation_id
    model_id        text,                 -- role=ASSISTANT 时记录的模型
    input_tokens    bigint,
    output_tokens   bigint,
    latency_ms      bigint,
    created_at      timestamptz NOT NULL DEFAULT now()
);
-- 索引：(tenant_id, run_id, created_at) 供回放；显式带 tenant_id 谓词（纵深防御）
```

**记录内容（role 取值）：**
- `USER`：学生本轮输入（来自 `renderInput(input)`，即 `run.input_payload` 已含，此处按轮补全多轮会话）。
- `SYSTEM`：本轮注入的 system prompt **摘要 + 跨 Run 记忆召回 digest**（不存完整知识库原文，存"召回了哪些知识点/记忆条 id 的摘要"），用于回放"当时 Agent 看到了什么上下文"。
- `ASSISTANT`：模型 `ModelResponse.content`（讲解/归因文本）+ `toolCalls` 描述 + `ModelUsage`（token）+ 闸门耗时。
- `TOOL`：工具名 + 入参摘要 + 返回结果（关联 `tool_operation` 的 jsonb；命中敏感信息的，内容以 `detail_digest` 替代，复用 `agent_risk_event` 的合规红线纪律）。

#### 2.1.2 埋点位置（沿用现有纪律）

在 `RunExecutionLoop` 主循环内，**复用 `appendStep` 的 fail-safe 风格**（端口未注入则跳过、写入失败只记 WARN 不冒泡）：
- 循环开始：`USER` 消息（多轮场景）。
- 模型调用前后（`appendStep(THINK)` 同位置）：写 `ASSISTANT` 消息 = `ModelResponse`（内容 + toolCalls + usage + latency）。
- 工具调用前后（`appendStep(TOOL)` 同位置）：写 `TOOL` 消息，关联 `tool_operation.operation_id`。
- `commitSuccess` 前：写最终 `ASSISTANT` 消息（与 `run.result_payload` 同源）。
- 记忆召回处（`memoryRecallService.recall` 之后）：写 `SYSTEM` 消息（召回摘要）。

**写前纪律**：沿用 `TenantSessionBinder.bind(conn, tenantId)` + 显式 `WHERE tenant_id=?`，否则 FORCE RLS 下静默零行（与 `TraceFactPort` 注释同铁律）。`run_message` 属租户域 → 必须挂 RLS；清理走 `agent_retention` 角色（`REQUIRE_ISOLATED_CREDENTIAL`），**不提供物理删除端点**。

#### 2.1.3 已可复用、无需新建的跟踪源

| 要跟踪的量 | 已有来源 |
|---|---|
| 工具调用（参数/结果/幂等/失败） | `tool_operation` + `tool_operation_attempt`（jsonb 已全） |
| token / 费用 | `usage_record`（input/output tokens + cost） |
| 单轮思考/工具耗时 | `run_step.started_at/completed_at` + `ModelCallObserver` latency |
| 配置/版本快照 | `execution_snapshot`（不可变，含 version_id） |
| 安全/越权/敏感事件 | `agent_risk_event`（仅 digest） |

### 2.2 回溯能力：数据模型、链路关联、版本/配置快照

**关联主键链**（全部已存在或可加）：
`trace_id`（run → invocation → correlation 三级降级，与采集器逐字同口径）
→ `run_id` → `run_message.step_number` → `run_step`（步序/计时）
→ `run_message.tool_operation_id` → `tool_operation`（工具 I/O）
→ `run_message.model_id` + `usage_record` → 模型与 token
→ `execution_snapshot`（该 Run 固化时的 agent/prompt/tool/run 配置 + version_id）。

**版本/配置快照已经具备**：`execution_snapshot` 不可变，回放时直接 `JOIN` 即可还原"这次辅导用的是哪版 Agent、哪版 prompt、哪些工具契约、轮次/预算上限"——无需新设计。

**回放完整性清单（完整复现一次对话所需）**：
1. 用户原始输入（`run.input_payload` + 各轮 `USER` 消息）
2. 注入上下文摘要（SYSTEM 消息：system prompt 版本 + 记忆召回 digest）
3. 每轮 `ASSISTANT` 文本 + toolCalls + 模型 + token + 耗时
4. 每轮 `TOOL` 入参/结果（关联 `tool_operation`）
5. 最终答案（`run.result_payload` + 末条 ASSISTANT）
6. 配置快照（`execution_snapshot`）

→ 这六类拼起来即可确定性重演；**顺序由 `created_at + step_number` 保证**。

### 2.3 质量评估：维度、指标、方式

#### 2.3.1 评估维度与指标映射（复用 P7 宽表列）

| 维度 | 指标（已落 `agent_trace_metric` 的列） | 可信度 |
|---|---|---|
| 准确性（归因对不对） | `human_verdict` / `hallucination` / `instruction_followed`（L2 抽样） | L2 |
| 相关性（是否对准学生问题） | `clarification_count`（追问是否过多）、人工标注维度 | L2/L0 |
| 工具调用正确性 | `tool_call_failed` / `tool_call_invalid` / `tool_call_duplicate` / `loop_suspected_count` | L0 |
| 用户反馈 | `feedback_up/down` / `retry_count` / `abandoned` | L3（有噪） |
| 安全合规 | `safety_blocked_count` / `unauthorized_attempt_count` / `sensitive_output_hits` | L0 |
| 效率成本 | `duration_ms` / `input/output_tokens` / `cost_amount` / `tool_rounds` / `max_rounds_reached` | L0 |
| **错题归因领域正确性** | （新增）`attribution_correct` 落在 `agent_eval_sample` 扩展字段 | L1/L2 |

> 错题归因特有维度（知识点命中、归因类型）可映射到宽表已有的 `scenario` 列（目前硬编码 null，P7 待补），建议把"归因类型/学科/知识点"作为 `scenario` 的枚举值来源。

#### 2.3.2 评估方式（双轨：自动 + 人工）

- **自动评估（L0/L1）**：
  - L0（系统事实）：终止原因、工具失败、耗时、token、安全拦截——已由 `AgentMetricCollector` 从事实表算出，只需接自动采集（见 §2.4）。
  - L1（自动判分/校验）：新增 **"归因正确性自动判分器"**——读 `run_message` 的 ASSISTANT 内容 + `tool_operation` 结果 + 参考答案（如有），产出 `attribution_correct`/`verified`，回写 `agent_task_outcome`（已是 `(trace_id, checker_code)` 唯一）。**必须同时回写覆盖率**（P7 铁律：只校验 3% 时"99% 正确"是误导）。
  - **LLM-as-judge**（可选增强）：新增轻量评估服务，对抽样/异常 trace 用模型打分，只存 `verdict` + `digest` 到 `agent_eval_sample`，不存判分原文（防 PII 泄露）。

- **人工评估（L2 黄金基线）**：
  - 复用已建的 `MetricEntryPage`（四表单）与 `agent_eval_sample`。
  - **抽样策略升级**：从"人工自觉录入"改为**异常优先分层抽样**——自动把 `loop_suspected / max_rounds_reached / safety_blocked / L1 未覆盖 / 随机基线` 的 trace 推入"审查队列"， reviewer 在队列里回放→打标→回填宽表。
  - 严守 P7 纪律：`n < 30` 显示"样本不足"不显示百分比；L1/L2 任一为空则"假成功占比"为 null。

### 2.4 自动采集接线（填最大坑）

在 Run 终态事件上挂消费者，**复用现有 Outbox/事件消费架构**（与 `OutboxRelay → RunDispatchConsumer` 同模式）：
- 监听 `run_event` 的 `RUN_SUCCEEDED`/`RUN_FAILED`/`RUN_CANCELLED`；
- 异步调用 `AgentMetricCollector.collect(tenantId, runId)`（幂等 `ON CONFLICT (trace_id)`）；
- 采集失败不阻塞 Run（与 step/risk 同 fail-safe 纪律），由 admin-console "补算"按钮兜底。
- 取舍：放在事件消费者（异步解耦）而非 `commitSuccess` 内联，避免把指标耦合进执行主链路（见 §5 取舍 C）。

---

## 3 · 对话回放与审查

### 3.1 回放实现

- **新增 `TraceReplayService`**（application 层）：入参 `tenantId + runId`，按 §2.2 六类来源 `JOIN`，输出确定性时间线 transcript DTO。
- **新增端点**：
  - 租户侧：`GET /api/runs/{runId}/transcript`（学生端"我的辅导记录"可选；tenant 令牌派生 tenantId，不收请求体 tenantId，复用 `TenantFeedbackController` 的防越权模式）。
  - 平台侧：`GET /api/platform/v1/runs/{runId}/transcript`（管理员跨租户回放，受 `platform.resource.read` 闸门）。
- **内容合规**：回放接口遵循 RLS + 敏感字段 digest 化；记忆召回只回显"知识点摘要"不回显知识库原文。

### 3.2 审查流程设计

```
告警/异常/周期抽样
      │
      ▼
审查队列（新增轻量表 review_task：trace_id, status[PENDING/IN_REVIEW/RESOLVED],
         priority, assigned_to, created_at）
      │  reviewer 打开
      ▼
TraceReplayService 回放 transcript（含 SYSTEM/ASSISTANT/TOOL 全内容）
      │  reviewer 定位到问题轮（按 step_number）
      ▼
打标 → agent_eval_sample（verdict/hallucination/instruction_followed/attribution_correct）
      │
      ▼
回写 agent_trace_metric（human_verdict 等）→ 覆盖率/样本量自动更新
      │
      ▼
根因归类（模型 / prompt / 工具 / 数据 / 配置）→ 反馈到 Agent/prompt 版本迭代
```

- **抽样**：异常优先（loop/safety/max_rounds/低 L1 覆盖）+ 随机基线，保证 `scenario × agent_version` 覆盖；`n<30` 不判。
- **问题定位**：从异常 Top-N（已有 `fetchAnomalies`）点进 trace → 现有 `AgentMetricsPage` 的 Drawer 目前只显指标，**扩展为同时渲染 transcript 回放组件**（复用 §3.1 端点）。
- **标注与现有录入 UI 打通**：`MetricEntryPage` 已能录 L1/L2/风险/反馈，审查页直接内嵌回放 + 该表单，一步完成"看→判→填"。

---

## 4 · 可视化监控

### 4.1 需监控的指标与看板

**复用 admin-console `AgentMetricsPage` 已有的六类 + 异常 Top-N + trace 下钻**，新增/强化：

| 看板分区 | 内容 | 来源 |
|---|---|---|
| 现状已覆盖 | SLA/成本/过程行为/业务效果/反馈/安全 六类卡 | `agent_trace_metric` + `mv_agent_metric_hourly` |
| 新增·内容质量 | 归因正确率（按 `scenario`/知识点/学科拆分）、幻觉率、指令遵从率 | `agent_eval_sample` |
| 新增·审查运营 | 审查队列积压、覆盖率、样本量 n、L1 覆盖率趋势 | `review_task` + `agent_eval_sample` |
| 新增·回放 | 单 trace transcript 回放（嵌入 Drawer） | `run_message` + `tool_operation` |

### 4.2 可落地监控方案（指标 / 告警 / 下钻）

1. **自动采集（先决）**：接 §2.4 事件消费者，让宽表从"空转"变"实时"——这是所有看板有意义的前提。
2. **Grafana 落地**（目前只有物化视图，无看板文件）：
   - 新增 dashboard JSON（provisioning），datasource = PostgreSQL（`mv_agent_metric_hourly` 扛时序、`agent_trace_metric` 扛分位）。
   - 面板：链路成功率（Stat）、终止原因分布（Pie）、端到端/P95/P99 耗时（Time series）、成本（Bar）、工具失败率（Bar gauge）、内容质量（按 scenario 的正确率/幻觉率）。
   - 响应体带 `refreshedAt`（P7 已约定，避免把物化视图旧数据当实时）。
3. **告警规则**（直接复用 P7 阈值纪律）：
   - SLA-2 链路成功率跌破基线 3σ；
   - SLA-3 中 `MODEL_ERROR + TOOL_5XX` 合计 > 10%；
   - SLA-7 单工具失败率 > 20%；
   - COST-4 超大 token 占比 > 1%（死循环特征）；
   - BEH-4 疑似循环 trace > 0；
   - 内容质量：幻觉率（`n≥30` 时）> 5%。
4. **下钻分析**：异常 Top-N → trace 明细（指标）→ **transcript 回放** → 按 `step_number` 定位问题轮 → 关联 `tool_operation`（工具 I/O）/ `usage_record`（token）/ `execution_snapshot`（配置版本）。
5. **告警→审查闭环**：告警触发时自动建 `review_task`（高优先级），reviewer 在队列里完成回放+打标，形成"发现→复盘→归因→改进"飞轮。

---

## 5 · 关键设计的取舍说明

- **A. 新增 `run_message` 表，而非往 `run_step.payload` 塞内容**。
  取舍：`run_step` 语义是"步序+计时+行为指标"，`run_message` 语义是"对话时间线"。混在一起会让回放查询复杂、且把内容存储与行为指标耦合。代价是多一张表，但回放与指标各司其职、查询简单。若团队认为"不想加表"，退路是补全 `appendStep` 的 `input/output_payload` 两列并在其上建视图——但会牺牲对话树（`parent_message_id`）表达能力。

- **B. 存完整对话内容，而非只存摘要**。
  取舍：错题归因产品"内容即交付物"，摘要会丢失判断归因质量所需的上下文。代价是存储量（`run_message` 可能很大）→ 用 JSONB + 保留策略（沿用 `agent_retention` 角色，按租户保留期清理，不物理删除、不另造删除端点）+ 对超长 run 设单条上限来缓解。敏感字段仍走 digest（合规红线）。

- **C. 自动采集放"事件消费者"而非 `commitSuccess` 内联**。
  取舍：与项目既有的 Outbox→Relay→Consumer 架构一致；采集失败不影响 Run 终态（fail-safe）；幂等 `ON CONFLICT` 保证可重算。内联会把指标耦合进执行主链路、且 Run 提交事务内多一次跨域写。

- **D. LLM-as-judge 自动判分只做抽样，不全量**。
  取舍：自动判分有成本与延迟。仅对"异常优先 + 随机基线"抽样跑 judge，结果只存 `verdict`+`digest`，不存判分原文（防 PII 泄露），与 L2 人工标注共用 `agent_eval_sample`。

- **E. 严守合规红线**：`agent_risk_event` 与回放中的敏感字段只存摘要/不存原文；`run_message` 属租户域挂 RLS，清理走隔离凭据通道。这是项目反复强调的纪律，本方案不破例。

---

## 6 · 需要补充的信息（信息不足处）

为把方案落到可排期的实现，请补充：

1. **Grafana/Prometheus 是否已部署**？目前仓库无 dashboard 配置文件（只有物化视图与 schema）。需确认是要"接已有 Grafana"还是"从零 provisioning"。
2. **对话内容的保留期与合规口径**：教育/未成年人数据是否要求特定保留年限，或反之要求最小化存储？决定 `run_message` 的 retention 策略与是否需脱敏落库。
3. **是否有"参考答案/自动判分源"** 用于 L1 归因正确性自动校验？`agent_task_outcome` 已建但需知道外部 checker（如题库标准解析、自动解题比对）是否可用，否则 L1 自动判分只能先靠 LLM-as-judge 抽样。
4. **预期量级**：每租户每日 Run 数 / 平均对话轮数，用于给 `run_message` 存储与 `mv` 刷新频率定容量。
5. **回放受众**：回放是仅平台/管理员用于审查，还是学生端也要"查看我的辅导记录"？决定 `transcript` 端点的权限与脱敏粒度。

---

## 7 · 落地优先级（建议）

| 阶段 | 内容 | 价值 |
|---|---|---|
| **P0（最高）** | 接自动采集（§2.4 事件消费者）→ 让现有 P7 宽表从空转变实时 | 立刻让已建看板有数，暴露真实质量 |
| **P1** | 新增 `run_message` + `RunExecutionLoop` 埋点 + `TraceReplayService`/端点 | 补齐回放底座，支撑所有复盘 |
| **P2** | admin-console Drawer 嵌入 transcript 回放 + 审查队列页 | 把"看指标→回放→打标"闭环 |
| **P3** | 异常优先抽样 + L1 自动判分（或 LLM-as-judge 抽样） | 黄金基线长大，内容质量可量化 |
| **P4** | Grafana dashboard provisioning + 告警→审查任务联动 | 从"看板"升级为"可行动的监控" |

# Agent 对话质量跟踪 / 回溯 / 评估体系 —— 详细设计

> 配套文档：先读 `docs/Agent对话质量跟踪_回溯_评估体系设计方案.md`（必要性 + 缺口分析 + 取舍）。
> 本文在其结论上给出**可实现的代码级规格**：DDL、端口/仓储、埋点行号、自动采集接线、回放端点、评估双轨、审查流、Grafana 看板、测试纪律。
>
> 技术栈：Spring Boot 4.1.1 + Java 21 + PostgreSQL 17 + RabbitMQ 4.3.6 + Micrometer。包名 `cn.studykid.multiagent`。

---

## 0.0 · 实施状态（2026-11-08，动手前先读）

> ⚠️ 本文是**设计规格**而非进度台账。下表与「落地偏差」以实际代码为准，后续做 P2/P3 前必须按此校正，否则会照着本文走错路。

| 阶段 | 状态 | 落地说明 |
|---|---|---|
| **P0**（G2 自动采集） | ✅ 代码层闭环 | 必须**双通道**：Rabbit `TraceMetricCollectorListener` + DB `EventRoutedMessageHandler → TraceCollectMessageHandler`。`agent.messaging.mode` 默认 **database** 且 `platform.env` 未覆盖，只做 Rabbit 等于「验收通过但生产不生效」。另修了 `cap_tool_def` 列名错误（无 `tool_name`，逻辑标识是 `id`）。 |
| **P1**（G1 回放底座） | ✅ 代码层闭环 | 见下方「P1 落地偏差」。 |
| **P2**（审查队列） | ✅ 已完成 | `platform.review_task` DDL + 端口/仓储/端点 + `ReviewAlertService` 告警联动（去重、状态机、fail-soft）+ 闭环 IT；已在 RDS 实跑验证。 |
| **P3**（评估双轨） | ✅ 代码层闭环（L1 规则半边） | `L1AttributionEvaluator` + `TraceEvaluationService` 接进 `AgentMetricCollector.collect()`；L1 回执 → `agent_task_outcome` → 宽表 `auto_checked/auto_verified`；L1_GAP / SAMPLE_RANDOM 入人审。**`scenario` 显式标签维度已接通**（见下方「场景维度落地」）。⚠️ **LLM-as-judge 兜底未接线**（§13 第 3 项未答）。 |
| **P4**（监控） | ✅ 已完成 | Grafana PG 数据源 + Prometheus scrape + **13 面板** provisioning（含 §7.3 覆盖率并排 / 样本不足 / 审查队列 / 按场景拆分），全部 SQL 已在 RDS 与 Grafana 数据源 API 实跑；admin-console 另接「按场景筛选」下拉，五个读端点整页同口径（见下方专节）。 |

> ⚠️ **本文按「保守默认口径」实施**：保留期与 Run 对齐默认 30 天、不额外存敏感原文；量级按 `<1k Run/日`；回放仅平台审计 / ADMIN 可读。以上均为**可配置项并标注「待产品确认」**，对应 §13 第 2/4/5 项尚未由产品正式确认，不得表述为已定稿。

**P3 落地偏差与铁律（与本文 §7 规格不同，以代码为准）**：

1. **L1 判的是「链路完整性」不是「答案正确性」**：checkerCode = `TRACE_LINK_L1`，PASS 仅表示「有 USER、ASSISTANT 回答非空且未 `finishReason=LENGTH`、无工具失败」。没有参考答案 / 题库标准解析之前，任何「归因正确率」都是编造；字段刻意命名为 `linkComplete` 而非 `correct`。
2. **不碰 `agent_eval_sample`**：那是人工/标注表，机器结论混进去会让「人工抽样覆盖率」变成假指标。L1 结论只写 `agent_task_outcome`；`sampled` 覆盖率留给人审轨。
3. **事实不足不伪造覆盖率**：回放无消息或无 USER 时 `judge()` 返回 empty → 建 `source='L1_GAP'` HIGH 人审任务，**不回写 TaskOutcome**。
4. ⚠️⚠️ **授权陷阱（实测 Sev-1，2026-11-08）**：`recordTaskOutcome` 用 `INSERT … ON CONFLICT DO UPDATE` 做幂等，需要 `app_agent` 对 `platform.agent_task_outcome` 有 **UPDATE**。此前 `03_platform_roles.sql` 只给 `SELECT, INSERT` → PostgreSQL 拒写，而采集器对评估异常是 fail-soft（只打 WARN 继续）→ **指标表有数、看板正常、回执恒 0 行、`auto_checked` 永远 false**，无任何报错。修复：03 补 `SELECT, INSERT, UPDATE` + `REVOKE DELETE` 与自检断言；`SchemaDriftIT` 新增「应用角色真跑一次幂等 upsert」门禁；本地 IT 库与 RDS 均重放 03→04。
5. **验收**：`L1AttributionEvaluatorTest`(8) + `TraceEvaluationServiceTest`(8) + `EvaluationWiringTest`(2) + `EvaluationClosedLoopIT`(4)；**双重反向验证**：① 库中撤掉 UPDATE → 权限门禁红；② 摘掉 `raiseEvaluation(metric)` → 闭环 4 例全红。恢复后全量 `mvn -o clean verify` = **3003 testcase / 0 失败 0 错误 / 5 跳过**（场景维度接入后 **3024**）。
6. **RDS 实跑**：对真实 MiniMax Run 重投 `agent.trace.collect` → 采集 → L1 判 `PARTIAL/TRUNCATED_OUTPUT` → `agent_task_outcome.verified=false` → 宽表 `auto_checked=true / auto_verified=false`，无 WARN。真实产出的截断回答**没有**被误算成「自动校验通过」。

**场景维度落地（2026-11-08，与本文 §2.3 规格不同，以代码为准）**：

1. ⚠️ **未新增 `public.run.scenario` 列**：不改 `CreateRunRequest` / `RunCreationService` / `RunCreationPort` 的签名，也不触发 01 终态快照与活库的结构漂移。标签作为 **`run.input_payload.scenario` 显式键**由调用方传入（`CreateRunRequest.input` 本就是开放 Map），`JdbcTraceFactRepository` 读取后写入宽表 `agent_trace_metric.scenario`。存量 NULL = 当时未标注。
2. ⚠️ **绝不从对话正文派生标签**：真实 Run 的 `input_payload` 只有 `userMessage` 自由文本，没有可靠的学科/知识点来源。自动派生需要题库标准解析与产品口径（§13 第 3 项），在那之前只接受调用方明确给出的分类码，否则保持 NULL。
3. **双重准入（合规红线）**：SQL 侧 `jsonb_typeof(input_payload -> 'scenario') = 'string'` 挡住 object/array（否则 `->>` 会把整段 JSON 结构转成文本）；Java 侧 `ScenarioTag` 白名单（Unicode 字母/数字 + `_ . : / + -`，≤64 字符，**不含空白与句读**）挡住整段学生提问被复制进指标宽表。非法值一律降级为「未标注」，不截断、不猜默认分组。
4. **看板口径**：Grafana 面板 13 按场景并排显示「自动校验覆盖率 / 人工抽样覆盖率」，NULL 独立成「未标注」组，`n<30` 百分比置 NULL 且标注「样本不足」。后端 `GET /api/platform/v1/metrics/agent/*?scenario=...` 筛选对**五个读端点同时生效**，并新增 `/scenarios` 枚举端点（见下方「按场景筛选 · 整页同口径落地」）。
5. **验收**：`ScenarioTagTest`(11) + `AgentMetricCollectorTest` 新增 2 例 + `EvaluationClosedLoopIT` 新增 4 例（显式标签 / 未标注保持 NULL / 自由文本被拒 / 非字符串被拒）。**双重反向验证**：① 把采集器 `run.scenario()` 改回 `null` → 单测 1 例 + 闭环 1 例精准变红；② 拆掉 `ScenarioTag.normalize` → 自由文本守卫变红。恢复后全量 **3024 testcase / 0 失败**。**RDS 实跑**：给真实 MiniMax Run 补标签后重投采集事件 → 宽表 `scenario='数学:一元二次方程'`，另一条未标注 Run 保持 NULL，Grafana 面板 13 出现两组，API 按场景筛选 2/1/0 条。
6. ⚠️ **仍未做**：「归因正确率按场景拆分」仍阻塞于 `agent_eval_sample.attribution_correct`（§13 第 3 项），面板 13 目前拆的是**链路指标与覆盖率**，不得表述为内容正确率。前端「按场景筛选」控件已落地，见下一节。

**前端落地（2026-11-08）**：admin-console 新增 `TraceTranscriptDrawer`（对话时间线，TOOL 消息仅 ADMIN 可见）、`ReviewQueuePage`（审查队列 + 认领/驳回/归因关闭），接入路由 `/review-queue` 与导航，并在 `AgentMetricsPage` 的 Trace 下钻挂「查看对话回放」。⚠️ 实跑联调抓到并修复 **Sev-1**：平台回放端点旧实现用 `app_agent`（NOBYPASSRLS）未绑租户直查 FORCE RLS 的 `public.run` → 恒 0 行 → 对所有 Run 一律 404；现改为「显式 `tenantId` 优先 + 平台域无 RLS 的 `agent_trace_metric` 反查兜底」，并加真实库闭环守卫（`platformReplayResolvesTenantForRealRun`）+ 反向验证。前端全量 56 文件 607 测试 / typecheck / 生产构建通过。

**按场景筛选 · 整页同口径落地（2026-11-08 收尾）**：

1. ⚠️ **核心纪律：整页同口径，不允许「总览已筛选、其它面板仍是全量」**。原先只有 `/overview` 接受 `scenario`，`/terminations`、`/latency`、`/tools`、`/anomalies` 都把该维度硬编码为 `null`；若只给前端总览挂筛选，合计会对不上却零报错 —— 又是一次「不报错、只是数字悄悄错」。现四个端点全部补 `@RequestParam("scenario")`，控制器逐一守卫参数下传。
2. ⚠️ **工具表没有 `scenario` 列**：`platform.agent_trace_tool_call` 只按 `trace_id` 关联宽表。若沿用 `toolTable=true` 直接跳过场景条件，「工具维度」会保持全量。现用 `trace_id IN (SELECT trace_id FROM platform.agent_trace_metric WHERE …)` 子查询跟随过滤；`applyFilters` 的条件追加顺序与 `bindDims` 的占位符绑定顺序必须逐字对应，错位只在运行期报「参数下标越界」，mock 单测照不出。
3. **「未标注」哨兵 `(unlabeled)`**：故意含圆括号 —— `ScenarioTag` 白名单不含括号，真实标签不可能撞上；仓储层译成 `scenario IS NULL`。`/scenarios` 返回 `{value, label, traceCount}`，未标注组由后端给出可读 `label`，前端无需复制哨兵语义。
4. ⚠️ **筛选器不能自锁**：`/scenarios` 是下拉自身的数据源，**签名与实现都不接受当前 scenario**（实现层显式清空 agent / scenario 维度），否则选中 A 后下拉里只剩 A，用户切不回 B。前端该 effect 的依赖刻意只有 `hours`。
5. **失真防护**：场景枚举响应非数组直接抛错，不退化成「暂无场景」空下拉；快速切换用请求序号丢弃过期响应；筛选生效时页面显著标注统计范围，切时间窗后当前场景无数据时提示「区间内无数据」，避免 0 条被读成「这个场景表现很差」。
6. ⚠️ **SQL 拼接陷阱（IT 实测抓到）**：Java text block 会吃掉开分隔符后的第一个换行，`AND scenario = ?` 直接接上 `ORDER BY` 会拼成 `?ORDER BY` / `NULLORDER BY`。`applyFilters` 末尾统一留一个空格，这类缺陷只在真跑 SQL 时暴露。
7. **验收**：`PlatformAgentMetricsControllerScenarioTest`(5) + `JdbcAgentMetricScenarioIT`(5，真库验证五端点同口径 / 工具表子查询 / 未标注 / 枚举不自锁 / 租户边界) + `metricScenarioApi.test.ts`(7) + `AgentMetricsPage` 新增 4 例。**三组反向验证**：① 抽掉工具表场景子查询 → IT 3 例精准红（5/3/2 变 10）；② 控制器漏传 + 枚举不清空维度 → 控制器 1 例 + IT 1 例精准红；③ 前端漏传一个端点 → 页面 1 例精准红。恢复后全量 **3034 testcase / 0 失败 0 错误 / 5 跳过**，前端 **56 文件 607 测试**。
8. **真实应用实跑**：重启最新 jar（`app_agent` 主池 + `agent_retention` 清理池）后，平台域令牌调 `/scenarios` 与五个端点全部 200：`scenarios=[数学:一元二次方程(1), 未标注(1)]`，`overview` 全部 2 = 数学 1 + 未标注 1，数学场景的 `terminations` 合计 = 1 与总览闭合，`latency.p50=13816.0`。⚠️ 实跑踩坑：`jwt.env` 有**两套独立密钥**，平台令牌必须用 `PLATFORM_JWT_PRIVATE_KEY` 签（用租户域 `JWT_PRIVATE_KEY` 会得到一排 401，日志只有 access 401，极易误判成端点问题）。

**P1 落地偏差（与本文规格不同，以代码为准）**：

1. **DDL 不走 `10_quality_trace.sql` 增量脚本**：按仓库「结构变更改 01 终态快照 + 活库 ALTER + 种子，不再增量 patch」纪律，直接写进 `deploy/sql/platform/01_platform_ddl.sql`；`04_retention_role.sql` 显式给 `agent_retention` 加 `run_message` DELETE；`init-db.sh` 期望值同步为 **70 表 / 44 RLS 表 / 52 策略**，`it-env.sh --probe` 用同一份**精确相等**常量。
2. **端点路径**：租户侧 `GET /api/runs/{runId}/transcript`（STUDENT 受众，隐藏 TOOL 消息）；平台侧 `GET /api/platform/v1/runs/{runId}/transcript`（先过 `platform.resource.read`，再按 runId 反查租户，ADMIN 完整时间线）。两端点共用同一份 `TranscriptResponse.of` 组装逻辑。
3. **埋点 M1–M4** 在 `RunExecutionLoop`：USER 存渲染后输入；SYSTEM 只存记忆**标签**（知识点 / 错误类型），绝不存知识库原文；ASSISTANT 存模型输出 + 工具调用摘要 + token/latency；TOOL 存工具名 / 状态 / 结果摘要。⚠️ **M4 暂不写 `tool_operation_id`**（`ToolInvocationResult` 未暴露「工具名 → operationId」映射），可按 `run_id + tool_name` 回查。
4. **幂等键** `(tenant_id, run_id, idem_key)`；TOOL 消息的键额外含工具标识，防同一轮多工具互相覆盖。
5. **验收**：`TraceReplayClosedLoopIT`（生产 Bean 真跑一次 Run，3 例）、`RunMessageWiringTest`（装配接线，6 例）、两端点单测（9 例）。**已做反向验证**：把写端口从执行循环抽掉 → 装配守卫 2 例 + 闭环 IT 3 例全部变红。
6. ⚠️ **读侧 RLS 陷阱**：`run_message` 是 FORCE RLS 表，任何用应用连接查它都必须**在事务内绑定租户**（`setAutoCommit(false)` + `TenantSessionBinder.bind`）；否则 `count(*)` 恒为 0，会把「消息已落库」误判成「埋点没执行」。闭环 IT 里同时保留「管理视角直查」做交叉验证。

> ✅ **已补实跑验收**：RDS `agent_dev` 上已用 MiniMax-M2.5 跑过真实 `SUCCEEDED` Run，`run_message` 2 条、`/transcript` 200、`agent_trace_metric` 1 行、Prometheus `ai_model_calls_total` 可见；P2 审查队列、P3 L1 回执与覆盖率回填也各在 RDS 重投采集事件实跑验证。本地 `agent_it` 全量回归不替代这些实跑，两者都做了。
>
> ⚠️ **仍未做**：LLM-as-judge 兜底 / `ATTRIBUTION` 判分（阻塞于 §13 第 3 项「参考答案 / 题库标准解析」口径），以及依赖它的「归因正确率按场景拆分」。`scenario` 显式标签维度、admin-console 的 `TraceTranscriptDrawer` / `ReviewQueuePage`、以及**按场景筛选控件（整页五端点同口径）**均已落地，见「场景维度落地」「前端落地」「按场景筛选 · 整页同口径落地」。

---

## 0 · 范围与已查证事实（避免空谈）

详细设计只补方案 §0 列出的**三处真缺口**，不复刻 P7 已交付资产：

| 缺口 | 现状（已查证） | 本文交付 |
|---|---|---|
| **G1 对话内容未持久化** | `run_step.appendStep`（`RunExecutionLoop.java:1069`）只落计时字段；`public.run_message` 不存在；对话历史是 `RunExecutionLoop` 内存 `messages` 列表；仅 `run.input_payload` / `run.result_payload` 落库 | §2 `public.run_message` DDL + §4 worker 埋点 + §6 `TraceReplayService` |
| **G2 采集器无自动触发** | `AgentMetricCollector.collect()`（`agent-application/.../metrics/AgentMetricCollector.java:55`）仅由 `PlatformAgentMetricsController.collect`（按需 HTTP）调用；Run 终态已发 `RUN_SUCCEEDED/FAILED/CANCELLED`（`RunEventPort.Types`）但无人消费 | §5 事件消费者接线（事件驱动，fail-safe） |
| **G3 内容级评估缺自动** | L2 人工经 `MetricEntryPage` 录入 `platform.agent_eval_sample`；L1 `agent_task_outcome` 占位；`agent_trace_metric.scenario` 恒 null | §7 `AttributionEvaluator` + `ReviewSampler` + `scenario` 来源接入 |

**已复用、不再重造的现有资产**（直接引用，不另写）：
- 指标宽表 `platform.agent_trace_metric`（44 列，幂等 `ON CONFLICT (trace_id)`）、`platform.agent_trace_tool_call`、`platform.mv_agent_metric_hourly` 物化视图、`platform.agent_eval_sample` / `agent_trace_feedback` / `agent_risk_event` / `agent_task_outcome`。
- 采集器 `AgentMetricCollector`（确定性派生，含循环/超大 token 判定）。
- 配置快照 `public.execution_snapshot`（不可变，含 `version_id` / `agent_config` / `prompt_config` / `tool_contracts` / `run_config`）。
- 工具/用量事实 `public.tool_operation`(+`attempt`)、`public.usage_record`、`public.run_step`、`public.run_event`。
- 仓储纪律：`JdbcRunStepRepository.openTenant(tenantId)` = `TenantSessionBinder.bind(conn, new TenantContext(tenantId, "...", Set.of(), 0L))`；`JdbcAgentMetricRepository.openSystem()` = `TenantSessionBinder.clear(conn)`（平台域无 RLS）。
- 消息：`TenantRoutingMessageSender` + `@RabbitListener`（`RunDispatchConsumer` 范式）；`OutboxRelay` 中继 `outbox_event`→RabbitMQ。

---

## 1 · 模块与包归位（新增文件清单）

新增代码全部落在**已存在模块**，不新建模块（与 memory 纪律「数据库两 reactor 两模块」「ADR-011 禁跨库塞代码」一致）：

| 层 | 模块 | 包 | 新增文件 |
|---|---|---|---|
| 端口 | `agent-ports` | `cn.studykid.multiagent.ports.persistence` | `RunMessagePort.java`、`TraceReplayPort.java`、`ReviewTaskPort.java` |
| 领域值对象 | `agent-ports` | `cn.studykid.multiagent.ports.model` | `RunMessage.java`（可选，亦可复用 `Map` 入参，见 §3.1） |
| 仓储 | `agent-persistence` | `cn.studykid.multiagent.infrastructure.persistence` | `JdbcRunMessageRepository.java`、`JdbcTraceReplayRepository.java`、`JdbcReviewTaskRepository.java` |
| 应用 | `agent-application` | `cn.studykid.multiagent.application.quality` | `TraceReplayService.java`、`TraceMetricCollectConsumer.java`、`AttributionEvaluator.java`、`ReviewSampler.java`、`ReviewTaskService.java` |
| 执行埋点 | `agent-worker` | `cn.studykid.multiagent.worker.execute` | 改 `RunExecutionLoop.java`（加 `messagePort` 字段 + `appendMessage` 方法 + 4 处调用） |
| 接线 | `platform-bootstrap` | `cn.studykid.multiagent.bootstrap.config` | 改 `WorkerExecutionConfig.java:653`（注入 `RunMessagePort`）、`WorkerLoopConfig.java`（注册 consumer + queue + `MessageSender` 路由键） |
| API | `agent-api` | `cn.studykid.multiagent.api.run` / `api.platform` | `TranscriptController.java`（租户侧）、`PlatformTranscriptController.java`、改 `PlatformAgentMetricsController`（补 `reviewTask` 录入/告警联动） |
| 前端 | `admin-console` | `src/pages/metrics` + `src/components/trace` | `TraceTranscriptDrawer.tsx`、`ReviewQueuePage.tsx`、扩展 `AgentMetricsPage.tsx` |
| DDL | `deploy/sql/platform` | — | 新文件 `10_quality_trace.sql`（含 `run_message` / `review_task` / `run.scenario` 增量 + 授权 + 索引） |

> 依赖方向：上述模块都已在 platform reactor 内，遵循既有 `application → ports ← persistence` 与 `worker → ports` 单向依赖，无新环。

---

## 2 · 数据模型详细设计（DDL）

### 2.1 `public.run_message`（G1 回放底座，租户域，FORCE RLS）

```sql
-- 10_quality_trace.sql
CREATE TABLE public.run_message (
    message_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq               bigint      GENERATED ALWAYS AS IDENTITY,   -- 插入序，回放确定性排序
    tenant_id         uuid        NOT NULL,
    run_id            uuid        NOT NULL,
    trace_id          text,                          -- 冗余；来源 run.trace_id，便于按 trace 聚合
    step_number       integer,                       -- 关联 run_step.step_number（定位到具体一轮）
    parent_message_id uuid,                          -- 对话树：assistant → tool_call → tool_result
    role              text        NOT NULL
                                  CHECK (role IN ('SYSTEM','USER','ASSISTANT','TOOL')),
    content           jsonb       NOT NULL,          -- 见 §2.4 内容约定
    tool_operation_id uuid,                          -- role=TOOL 时关联 tool_operation.operation_id
    model_id          text,                          -- role=ASSISTANT 时记录模型
    input_tokens      bigint,
    output_tokens     bigint,
    latency_ms        bigint,
    created_at        timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ONLY public.run_message FORCE ROW LEVEL SECURITY;

-- 回放主路径：按 run 拉全量时间线
CREATE INDEX idx_run_message_run_seq
    ON public.run_message (tenant_id, run_id, seq);
-- 下钻：按轮次定位问题 step
CREATE INDEX idx_run_message_step
    ON public.run_message (tenant_id, run_id, step_number);
-- 跨 trace 聚合（异常抽样/内容质量统计）；显式带 tenant_id 谓词（纵深防御）
CREATE INDEX idx_run_message_trace
    ON public.run_message (tenant_id, trace_id, created_at);

-- RLS：复用 public 既有 tenant_id 隔离策略（与 run/run_step/tool_operation 同策略名）。
--   USING (tenant_id = current_setting('app.tenant_id')::uuid)，由 TenantSessionBinder 在事务内绑定。
--   ⚠️ 新建表必须显式挂该策略，否则 FORCE RLS 下无策略=拒绝全部写入（静默零行）。
```

**取舍**：`seq` 用 `GENERATED ALWAYS AS IDENTITY` 而非依赖 `created_at`——`gen_random_uuid()` 是 v4 无序，`created_at` 同毫秒会有 tie；`seq` 保证回放顺序与插入序严格一致。`parent_message_id` 支持「assistant→tool_call→tool_result」对话树，保留多工具并行编排的表达力（比扁平追加更利于定位）。

**保留与清理**：属租户域 → 必须走 `agent_retention` 角色（`REQUIRE_ISOLATED_CREDENTIAL`），**不提供物理删除端点**（与 `ErasurePort` 纪律一致）。保留期待 §6 待补信息确认；默认先与 `run` 同策略（按租户保留期）。

### 2.2 `platform.review_task`（审查队列，平台域，无 RLS）

与 `agent_eval_sample` 同 schema（平台域、无 RLS、系统连接读写）：

```sql
CREATE TABLE platform.review_task (
    task_id      uuid   PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id     text   NOT NULL,
    tenant_id    uuid   NOT NULL,                     -- 冗余，便于平台侧按租户分桶
    run_id       uuid   NOT NULL,
    source       text   NOT NULL
                        CHECK (source IN ('ALERT','ANOMALY','SAMPLE_RANDOM','L1_GAP','MANUAL')),
    priority     text   NOT NULL DEFAULT 'NORMAL'
                        CHECK (priority IN ('HIGH','NORMAL','LOW')),
    status       text   NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','IN_REVIEW','RESOLVED','DISMISSED')),
    assigned_to  text,
    root_cause   text   CHECK (root_cause IN ('MODEL','PROMPT','TOOL','DATA','CONFIG','UNKNOWN')),
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    resolved_at  timestamptz
);
CREATE INDEX idx_review_task_status  ON platform.review_task (status, created_at DESC);
CREATE INDEX idx_review_task_tenant ON platform.review_task (tenant_id, status);
```

**取舍**：审查队列放 `platform` 而非 `public`——reviewer 是平台/租户管理员，跨租户可见，无租户隔离需求；与 `agent_eval_sample`（人工标注也是平台域）保持一致，避免两套连接纪律。

### 2.3 `run.scenario` 增量（打通"按学科/知识点"拆分）

> ⚠️ **落地偏差（2026-11-08，以代码为准，详见 §0.0「场景维度落地」）**：本节的
> `ALTER TABLE public.run ADD COLUMN scenario text` **未实施**。实际采用
> `run.input_payload.scenario` 显式键 → `ScenarioTag` 白名单规范化 → 宽表
> `agent_trace_metric.scenario`，零 DDL 变更、零创建链路签名改动。
> 原因：真实输入只有 `userMessage` 自由文本，没有可靠的「从输入/学生画像派生」规则；
> 在产品给出题库标准解析口径前，任何自动派生都是编造指标。下方 SQL 保留作原始设计意图。

```sql
-- 原始设计（未落地，保留作背景）
ALTER TABLE public.run ADD COLUMN scenario text;
COMMENT ON COLUMN public.run.scenario IS
  '错题归因维度标签（学科/知识点/归因类型），由创建 Run 时从输入/学生画像派生。
   采集器据此填充 agent_trace_metric.scenario，打通内容质量「按 scenario 拆分」看板。
   存量 NULL = 当时未记录，采集器退化为空 scenario 聚合。';
```

**取舍**：不在宽表加 `attribution_correct` 列，而是把它落在 `agent_eval_sample.attribution_correct`（人工/L1 黄金基线本源），内容质量看板由 `eval_sample` 聚合得出——避免宽表 schema 膨胀与"未采集≠0"口径被冲淡（P7 铁律）。仅在需要高频时序时才考虑宽表冗余列，本文默认聚合查询。

### 2.4 `content` jsonb 内容约定（合规红线）

| role | content 结构 | 合规注意 |
|---|---|---|
| `USER` | `{"text": "...", "turn": 1}` | 学生输入原文可存（属产品交付物），但回放给学生端时按 §6.3 脱敏 |
| `SYSTEM` | `{"kind":"MEMORY_RECALL","items":[{"id":"...","label":"..."}],"tokens":n}` | **绝不存知识库原文**，只存召回条 id+摘要（与 `PromptGuard.MEMORY_*` 围栏一致） |
| `ASSISTANT` | `{"text":"...","tool_calls":[{"id","name","args_summary"}],"finish_reason":"..."}` | 模型输出原文可存；`args_summary` 不得含完整敏感参数 |
| `TOOL` | `{"tool_name":"...","call_id":"...","args_summary":{...},"result_summary":{...},"state":"SUCCEEDED","error":null}` | 命中敏感信息以 `detail_digest` 替代；完整结果经 `tool_operation_id` 关联回看 |

> 所有 `args_summary` / `result_summary` 走与 `EventPortModelCallObserver` 同款长度封顶 + 不可信标记；命中 `agent_risk_event` 模式的，内容以 digest 替代，**绝不落原文**（与 P7 合规红线一致）。

---

## 3 · 端口与仓储（接口级规格）

### 3.1 `RunMessagePort`（写入，worker 调用）

```java
package cn.studykid.multiagent.ports.persistence;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface RunMessagePort {
    /**
     * 追加一条对话消息（G1 回放底座）。
     * @param fencingToken 写守卫：旧世代 token 回退由 run_message 上的 fencing 触发器拒绝（红线 5 同源）
     * @param content      jsonb 内容，结构见 §2.4；实现侧用 Jackson 序列化
     */
    void append(@NonNull String tenantId, @NonNull String runId, long fencingToken,
                int stepNumber, @NonNull String role, @NonNull Map<String, Object> content,
                @Nullable String toolOperationId, @Nullable String modelId,
                @Nullable Long inputTokens, @Nullable Long outputTokens,
                @Nullable Long latencyMs);
}
```

### 3.2 `TraceReplayPort`（读取，回放服务调用）

```java
public interface TraceReplayPort {
    /** 拉取某 run 的完整消息时间线（租户绑定，按 seq 升序）。 */
    List<Map<String,Object>> messages(@NonNull String tenantId, @NonNull String runId);
    /** 拉取某 tool_operation 完整 jsonb（回放 TOOL 消息时按需展开）。 */
    Optional<Map<String,Object>> toolOperation(@NonNull String tenantId, @NonNull String operationId);
}
```

### 3.3 `ReviewTaskPort`（审查队列读写）

```java
public interface ReviewTaskPort {
    String create(@NonNull ReviewTaskSpec spec);                 // 返回 task_id
    void assign(@NonNull String taskId, @NonNull String assignee);
    void resolve(@NonNull String taskId, @Nullable String rootCause, @Nullable String note);
    void dismiss(@NonNull String taskId, @Nullable String note);
    List<ReviewTaskView> pending(int limit, @Nullable String tenantId);
}
```

### 3.4 仓储实现要点

- **`JdbcRunMessageRepository`**：**逐字复制 `JdbcRunStepRepository.openTenant(tenantId)` 的 RLS 绑定纪律**（`TenantSessionBinder.bind(conn, new TenantContext(tenantId, "agent-run-message-writer", Set.of(), 0L))`）。`append` 用 `INSERT ... ON CONFLICT (tenant_id, run_id, seq) DO NOTHING`？不用——`seq` 是 IDENTITY 不冲突；用普通 `INSERT`，失败（如 fencing 回退）catch 后 WARN 不冒泡（与 `appendStep` 同纪律）。
- **`JdbcTraceReplayRepository`**：读 `run_message` 用 `openTenant(tenantId)`（带 RLS）；读 `tool_operation` 同；**不读指标宽表**（那是平台域，回放不需要）。
- **`JdbcReviewTaskRepository`**：`openSystem()`（平台域无 RLS），但 `create` 写入**必须带 `tenant_id`**（队列分桶用）。

---

## 4 · 执行埋点（worker 侧，G1 落地）

### 4.1 `RunExecutionLoop` 改造清单

1. **新增字段**（与 `stepPort`/`riskEventPort` 同位置，约 `:146` / `:164` 附近）：
   ```java
   private final RunMessagePort messagePort;   // @Nullable，未注入则跳过（与 stepPort 同）
   ```
2. **构造器**：在 `WorkerExecutionConfig.java:653` 的 `@Bean runExecutionLoop(...)` 增加形参
   `@Autowired(required = false) RunMessagePort messagePort`，并传入构造（沿用既有「可选构造参数 + 默认 null」纪律，避免破坏既有装配与单测）。
3. **新增方法**（紧邻 `appendStep`，约 `:1082` 后）：
   ```java
   private void appendMessage(String tenantId, RunClaimPort.LeasedTask task, int stepNumber,
                              @NonNull String role, @NonNull Map<String,Object> content,
                              @Nullable String toolOperationId, @Nullable String modelId,
                              @Nullable Long inputTokens, @Nullable Long outputTokens,
                              @Nullable Long latencyMs) {
       if (messagePort == null) return;
       try {
           messagePort.append(tenantId, task.runId(), task.fencingToken(), stepNumber, role,
                             content, toolOperationId, modelId, inputTokens, outputTokens, latencyMs);
       } catch (RuntimeException e) {
           log.warn("⚠️ 写入对话消息失败（不影响本次执行结果）: runId={}, cause={}",
                   task.runId(), e.toString());
       }
   }
   ```

### 4.2 四处精确埋点位置（行号基于当前 `RunExecutionLoop.java`）

| # | 位置 | 行号锚点 | role | content 来源 |
|---|---|---|---|---|
| M1 | 循环开始前（仅首轮） | `:516-519` `messages.add(user(renderInput(input)))` 之后 | `USER` | `renderInput(input)` 原文本 |
| M2 | 记忆召回成功处 | `:535` `if (recalled.hasMemory())` 块内 | `SYSTEM` | `recalled.items()` 的 id/label 摘要 + `recalled.tokens()` |
| M3 | 模型返回后（无工具调用分支前） | `:768` `appendStep(THINK)` 之后 / `:791` `if (response.toolCalls().isEmpty())` 之前 | `ASSISTANT` | `response.content()` + `response.toolCalls()` 摘要 + `response.usage()`（input/output tokens）+ `ModelCallObserver` 的 `latency_ms`；`model_id=agentConfig.modelId()` |
| M4 | 工具调用完成后 | `:839` `appendStep(TOOL)` 之后 / `:854-876` `appendEvent(TOOL_INVOKED)` 附近 | `TOOL` | `toolGate.invokeAll` 返回的 operation 列表：`tool_operation_id` + 工具名 + 入参摘要 + 结果摘要 + state；`step_number` 同本轮 |
| M5（可选） | `commitSuccess` 终态 | `:1427` | — | **不必重复写**：末条 `ASSISTANT`（M3）即最终答案；若需强化，可在此补一条 `ASSISTANT` 终态标记（幂等覆盖按 seq 不行，故用 `step_number=MAX+1` 区分）。默认不写，由回放取最后一条 ASSISTANT |

> **纪律重申**：所有 `appendMessage` 调用处于 `try` 主流程内但**自身吞异常**（方法内已 catch），绝不因写消息失败而让 Run 失败——与 `appendStep`/`appendEvent`/`emitRiskEvent` 同纪律。

### 4.3 内容构造示例（M3 ASSISTANT）

```java
Map<String,Object> content = new LinkedHashMap<>();
content.put("text", response.content());
content.put("tool_calls", response.toolCalls().stream()
        .map(tc -> Map.of("id", tc.id(), "name", tc.name(),
                          "args_summary", summarize(tc.arguments())))
        .toList());
content.put("finish_reason", response.finishReason());
appendMessage(tenantId, task, steps, "ASSISTANT", content,
              null, agentConfig.modelId(),
              response.usage()==null?null:response.usage().inputTokens(),
              response.usage()==null?null:response.usage().outputTokens(),
              modelLatencyMs);
```

---

## 5 · 自动采集接线（G2，P0 最高优先）

**目标**：让 `AgentMetricCollector.collect(tenantId, runId)` 在 Run 终态被异步触发，使宽表从"空转"变"实时"。

### 5.1 触发机制（事件驱动，复用既有消息架构）

在 Run 终态三处（`commitSuccess` `:1434 RUN_SUCCEEDED`、`commitFailure` `:1453 RUN_FAILED`、`:584/:908/:1686 RUN_CANCELLED/PARKED` 视需要）**追加一行 fail-safe 发布**：

```java
// 在 commitSuccess/commitFailure 的 appendEvent(RUN_SUCCEEDED/FAILED) 之后
publishTraceCollect(tenantId, task.runId());   // 新增，见下
```

`publishTraceCollect` 实现（fail-safe，不阻塞 Run）：
```java
private void publishTraceCollect(String tenantId, String runId) {
    try {
        // 复用既有 MessageSender 抽象（RunDispatchConsumer 同款）；路由键 agent.trace.collect
        messageSender.send(EXCHANGE, "agent.trace.collect",
                           Map.of("tenantId", tenantId, "runId", runId));
    } catch (RuntimeException e) {
        // ⚠️ 无 broker / 发送失败 → 跳过，由 admin-console「补算」按钮兜底（P7 既有路径）
        log.warn("⚠️ 发布 trace 采集事件失败（不影响终态）: runId={}, cause={}", runId, e.toString());
    }
}
```
- `messageSender` 注入：`WorkerExecutionConfig` 已有 `TenantRoutingMessageSender`（见 `WorkerLoopConfig` 导入），直接复用；或在构造器新增可选形参。
- **不写入 `outbox_event`**：采集是"尽力而为的后续动作"，非唤醒 Worker 的强一致需求；直接发 MQ 更简单，且失败已被 catch。若要求 at-least-once 持久化，则改用 `outbox_event(event_type='TRACE_METRIC_COLLECT')` 由 `OutboxRelay` 中继——**实现时按当时 `OutboxRelay` 的 event_type→queue 路由能力二选一**（若 Relay 仅支持单一目标队列，则走直接 `MessageSender` 路线，本文默认此路）。

### 5.2 消费者 `TraceMetricCollectorConsumer`（新，`agent-application` 或 `agent-worker` 消费侧）

```java
@Component
public class TraceMetricCollectorConsumer {
    private final AgentMetricCollector collector;     // 既有 @Service
    @RabbitListener(queues = "agent.trace.collect")   // 队列在 WorkerLoopConfig 注册（仿 runExecuteQueue）
    public void onCollect(@Payload Map<String,String> cmd) {
        String tenantId = cmd.get("tenantId");
        String runId = cmd.get("runId");
        try {
            collector.collect(tenantId, UUID.fromString(runId));   // 幂等 ON CONFLICT(trace_id)
        } catch (RuntimeException e) {
            // ⚠️ 消费失败：MQ delivery-limit + 死信兜底；不阻塞 Run（Run 已终态）
            log.error("trace 指标采集失败（将重试/死信）: runId={}", runId, e);
            throw e;   // 交 RabbitMQ 重试
        }
    }
}
```

### 5.3 接线点（bootstrap）

在 `WorkerLoopConfig.java` 注册：
- `Queue traceCollectQueue(@Value("${agent.mq.queues.trace-collect.name}") ...)`（仿 `:124 runExecuteQueue`，带死信交换机）。
- `Binding`：`topicExchange ↔ traceCollectQueue`，routing key `agent.trace.collect`（仿既有 binding）。
- 注册 `TraceMetricCollectorConsumer` 为 `@RabbitListener` 宿主 Bean（Spring 自动发现，无需手动注册，但确保其在 worker 启用时加载）。

**验收口径**：不带 `agent.worker.enabled=true` 时跳过（既有纪律）；生产开启后，Run 终态 → 数秒内 `agent_trace_metric` 出现该行 → admin-console 看板有数。

---

## 6 · 回放服务与端点（G1 闭环）

### 6.1 `TraceReplayService.buildTranscript(tenantId, runId)`

```java
@Service
public class TraceReplayService {
    private final TraceReplayPort replay;        // 租户域读取（run_message + tool_operation）
    private final RunQueryPort runQuery;         // 既有，取 run 元信息 + snapshot_id
    private final ExecutionSnapshotPort snapshot;// 既有，取不可变配置快照
    private final RunStepPort stepPort;          // 既有，取每轮计时
    // usage_record 聚合（token/cost）经 RunQueryPort 或新增轻量查询

    public Transcript buildTranscript(String tenantId, String runId) {
        RunMeta run = runQuery.require(tenantId, runId);            // 跨租户 runId → 404（不泄露归属）
        SnapshotMeta snap = snapshot.byId(run.snapshotId());        // 配置版本
        List<TranscriptMessage> msgs = replay.messages(tenantId, runId).stream()
                .map(this::toMessage).toList();                    // 按 seq 升序
        // TOOL 消息按需展开 tool_operation（仅回放时取，不在列表页取全量）
        return new Transcript(run, snap, msgs, steps(runId));
    }
}
```

**Transcript DTO**（返回前端）：
```json
{
  "runId": "uuid", "traceId": "text", "state": "SUCCEEDED",
  "startedAt": "ISO", "endedAt": "ISO", "durationMs": 1234,
  "snapshot": {"agentVersion": 7, "promptVersion": "...", "modelId": "...",
               "runConfig": {"maxSteps":20,"maxLlmCalls":12,"maxCostPerRun":2.00},
               "toolContracts": ["calc","kb_query"]},
  "messages": [
    {"seq":1,"role":"USER","stepNumber":0,"content":{"text":"这道题为什么选 C？"}},
    {"seq":2,"role":"SYSTEM","stepNumber":1,"content":{"kind":"MEMORY_RECALL","items":[...]}},
    {"seq":3,"role":"ASSISTANT","stepNumber":1,"modelId":"gpt-4o",
     "content":{"text":"...归因...","tool_calls":[{"name":"kb_query",...}]},
     "inputTokens":820,"outputTokens":140,"latencyMs":1200},
    {"seq":4,"role":"TOOL","stepNumber":1,"toolOperationId":"op-uuid",
     "content":{"tool_name":"kb_query","state":"SUCCEEDED","result_summary":{...}}},
    {"seq":5,"role":"ASSISTANT","stepNumber":2,"content":{"text":"最终讲解..."}}
  ],
  "tokenTotal": {"input":1200,"output":300,"cost":0.012},
  "steps": [{"stepNumber":1,"type":"THINK","ms":1300},{"stepNumber":1,"type":"TOOL","ms":400}]
}
```

### 6.2 租户侧端点（学生/租户管理员"我的辅导记录"）

仿 `TenantFeedbackController` 防越权范式（**不收请求体 tenantId，由令牌派生；runId 解析跨租户即 404**）：

```java
@RestController @RequestMapping("/api/runs")
public class TranscriptController {
    private final TraceReplayService replay;
    private final RunController.CurrentPrincipal currentPrincipal; // 既有可信主体
    @GetMapping("/{runId}/transcript")
    public Transcript get(@PathVariable UUID runId) {
        TenantContext ctx = currentPrincipal.context();
        // runId 经 RunQueryPort.require(tenantId, runId) 校验归属 → 跨租户 404
        return replay.buildTranscript(ctx.tenantId(), runId.toString());
    }
}
```
> 向学生端暴露时，`SYSTEM` 记忆召回只回显知识点摘要、`TOOL` 仅回显结果摘要（content 已是摘要态，无需额外脱敏）；若学生端不应看工具内部，可在 service 层按 `audience=STUDENT` 过滤 TOOL 消息。

### 6.3 平台侧端点（管理员跨租户回放）

```java
@RestController @RequestMapping("/api/platform/v1/runs")
public class PlatformTranscriptController {
    private final TraceReplayService replay;
    private final PlatformAuthorization authz; // 既有 platform.resource.read 闸门
    @GetMapping("/{runId}/transcript")
    public Transcript get(@PathVariable UUID runId) {
        authz.require(AuthorizationService.Actions.PLATFORM_RESOURCE_READ); // 平台令牌闸门
        // 平台侧不绑租户：先按 runId 取 tenantId（RunQueryPort 内部按 RLS 不可用，故用系统连接查 tenant_id）
        String tenantId = runQuery.resolveTenantId(runId);   // 新增轻量方法
        return replay.buildTranscript(tenantId, runId.toString());
    }
}
```

---

## 7 · 质量评估双轨（G3）

### 7.1 `AttributionEvaluator`（L1 自动判分 + LLM-as-judge 抽样）

```java
public interface AttributionEvaluator {
    /** L1：基于参考答案/题库标准解析的确定性判分（有源时）。 */
    Optional<EvalResult> autoAttribution(String tenantId, String runId, Transcript t);
    /** LLM-as-judge：仅抽样调用，返回 verdict + digest（不存判分原文）。 */
    EvalResult llmJudge(String tenantId, String runId, Transcript t);
}
```
- **回写路径**（复用 `JdbcAgentMetricRepository` 既有方法，不新写 JDBC）：
  - L1 有源 → `metrics.recordTaskOutcome(new TaskOutcome(traceId, tenantId, "ATTRIBUTION", extRef, verified, digest))` → 宽表 `auto_checked/auto_verified` 自动更新。
  - 人工/L1 结论 → `metrics.recordEvalSample(new EvalSample(traceId, tenantId, verdict, hallucination, followed, note, sampler))` + 宽表 `sampled/human_verdict/...` 更新。
  - **新增** `agent_eval_sample.attribution_correct`（§2.3 决策：加在 eval_sample 而非宽表）→ `recordEvalSample` 扩参或新增 `recordAttribution(traceId, tenantId, correct, scenario)`。

### 7.2 `ReviewSampler`（异常优先分层抽样，喂审查队列）

```java
public interface ReviewSampler {
    /** 周期性（如每小时）扫描 agent_trace_metric，按优先级推 review_task。 */
    void schedule();
}
```
**分层规则**（保证 `scenario × agent_version` 覆盖，`n<30` 不判）：
1. **异常优先**（`platform.review_task.source='ANOMALY'`）：`loop_suspected_count>0` / `max_rounds_reached` / `safety_blocked_count>0` / `coalesce(in,out)>20000`（超大 token）/ `auto_verified=FALSE AND sampled=TRUE`（L1 判失败）。
2. **L1 覆盖缺口**（`source='L1_GAP'`）：`auto_checked IS NULL` 的 trace 随机抽，逼近目标覆盖率（如 30%）。
3. **随机基线**（`source='SAMPLE_RANDOM'`）：纯随机抽，维持黄金基线规模。
4. 已存在 PENDING/IN_REVIEW 同 trace → 跳过（去重）。

### 7.3 覆盖率纪律（P7 铁律）

- 看板展示"自动校验覆盖率"`count(auto_checked)/count(*)` 与"人工抽样覆盖率"`count(sampled)/count(*)`，**并排显示**；只校验 3% 时"成功率 99%"在 UI 明确标注"覆盖不足"。
- `n<30` 的维度（如某 scenario 样本不足）一律显示"样本不足"，不显示百分比（与 `agent_eval_sample` 注释纪律一致）。

---

## 8 · 审查流程（P2 闭环）

### 8.1 `review_task` 状态机

```
PENDING ──assign──▶ IN_REVIEW ──resolve(rootCause)──▶ RESOLVED
   │                        │
   └────────── dismiss ─────┴──────────────────────▶ DISMISSED
```
- `resolve` 时若 `root_cause` 指向 `PROMPT`/`CONFIG`/`MODEL`，回写 `agent_eval_sample`（归因结论）并触发"提示词/模型版本迭代"工单（外部，本文不展开）。
- `RESOLVED`/`DISMISSED` 后从待办看板消失。

### 8.2 告警 → 审查联动

在 `PlatformAgentMetricsController` 或独立 `AlertingService` 中：当 §9.2 任一告警规则触发，自动建 `review_task(source='ALERT', priority='HIGH', trace_id=命中 trace)`；reviewer 在队列里一步完成"回放 → 打标 → 归因"。

### 8.3 前端改造（admin-console）

- **`TraceTranscriptDrawer.tsx`**：在 `AgentMetricsPage` 现有 trace 下钻 Drawer（目前只显指标）内嵌 transcript 回放组件，调用 §6.2/6.3 端点，渲染六类消息（USER/SYSTEM/ASSISTANT/TOOL 分色），点击 TOOL 消息展开 `tool_operation` 详情。
- **`ReviewQueuePage.tsx`**：审查队列（PENDING 列表 + 优先级 + 来源），点开即加载 `TraceTranscriptDrawer` + 复用 `MetricEntryPage` 的四表单（L1/L2/风险/反馈），一步"看→判→填"。
- 复用既有 `MetricEntryPage` 录入端点，不新写录入 UI。

---

## 9 · 可视化监控（P4）

### 9.1 Grafana dashboard（新增 provisioning JSON）

**前提**：§5 自动采集已接通（看板有数）。数据源 = PostgreSQL（`mv_agent_metric_hourly` 扛时序、`agent_trace_metric` 扛分位/明细、`agent_eval_sample` 扛内容质量）。

**面板清单**：

| 面板 | 类型 | 查询（PostgreSQL 数据源） |
|---|---|---|
| 链路成功率 | Stat | `SELECT count(*) FILTER (WHERE terminal_state='SUCCEEDED')/count(*) FROM agent_trace_metric WHERE started_at>=$__from` |
| 终止原因分布 | Pie | `SELECT termination_reason, count(*) FROM agent_trace_metric WHERE ... GROUP BY 1` |
| P50/P95/P99 耗时 | Time series | `SELECT bucket_start, percentile_cont(0.95) ... FROM mv_agent_metric_hourly GROUP BY bucket_start`（或直查宽表） |
| 成本趋势 | Bar | `SELECT bucket_start, sum(cost_amount) FROM mv_agent_metric_hourly GROUP BY 1` |
| 工具失败率 Top | Bar gauge | `SELECT tool_name, sum(failed)/sum(call) FROM agent_trace_tool_call WHERE ... GROUP BY 1` |
| **内容质量·归因正确率** | Time series | `SELECT started_at, count(*) FILTER(WHERE attribution_correct) / count(*) FROM agent_eval_sample WHERE ... GROUP BY 1`（n<30 置空） |
| **内容质量·幻觉率** | Time series | `SELECT started_at, count(*) FILTER(WHERE hallucination) / count(*) FROM agent_eval_sample ...` |
| **按 scenario 拆分（链路指标 / 覆盖率）** | Table | ✅ **已落地（面板 13）**：`coalesce(nullif(scenario,''), '未标注')` 分组，自动/人工覆盖率并排，`n<30` 百分比置 NULL 并标「样本不足」。⚠️ 此处拆的是**链路指标与覆盖率**，不是归因正确率 —— 后者仍阻塞于 `agent_eval_sample.attribution_correct`（§13 第 3 项）。 |
| **审查运营·积压/覆盖** | Stat + Bar | `SELECT status, count(*) FROM review_task GROUP BY 1`；覆盖率=`count(sampled)/count(*)` from metric |

> 响应体带 `refreshed_at`（`mv` 刷新期间读旧数据，前端标注时效，与 P7 约定一致）。

### 9.2 告警规则（复用 P7 阈值纪律，告警→建 review_task）

| 规则 | 条件 | 动作 |
|---|---|---|
| SLA-2 成功率 | 链路成功率 < 基线-3σ | 建 `review_task(HIGH, ALERT)` |
| SLA-3 错误率 | `MODEL_ERROR+TOOL_5XX` 占比 > 10% | 建 `review_task(HIGH, ALERT)` |
| SLA-7 工具失败 | 单工具失败率 > 20% | 建 `review_task(NORMAL, ALERT)` |
| COST-4 超大 token | `coalesce(in,out)>20000` 占比 > 1% | 建 `review_task(NORMAL, ANOMALY)` |
| BEH-4 循环 | `loop_suspected_count>0` 的 trace | 建 `review_task(HIGH, ANOMALY)` |
| 内容质量 | `n≥30` 时幻觉率 > 5% | 建 `review_task(HIGH, ALERT)` |

### 9.3 下钻链路（看板→复盘）

```
看板指标 → 异常 Top-N（fetchAnomalies 既有） → trace 明细（指标）
  → TraceTranscriptDrawer 回放 → 按 step_number 定位问题轮
  → 关联 tool_operation（工具 I/O）/ usage_record（token）/ execution_snapshot（配置版本）
  → 打标回写 eval_sample → 根因归类 → 提示词/模型迭代
```

---

## 10 · 迁移与兼容

- **DDL 迁移脚本** `deploy/sql/platform/10_quality_trace.sql`：含 `run_message` / `review_task` 建表 + 索引 + RLS 策略挂载 + `run.scenario` ALTER + 授权（复用 `03_platform_roles.sql` 的角色：`app_agent` 写 `run_message`；`platform_ops` 读；`agent_retention` 清理）。**授权唯一源仍是 03/04 角色脚本，不要自写 GRANT 循环**（memory 铁律）。
- **兼容旧 Run**：`run_message` 仅对新 Run 写入；回放旧 Run 时 `messages` 为空 → Transcript 退化为仅 `run.input_payload` / `result_payload` + 配置快照（明确标注"此 Run 无详细回放"），不报错。
- **`run.scenario` 存量 NULL**：采集器 `scenario` 填 NULL → 宽表 scenario 聚合退化为"未分类"桶，不影响既有指标。

---

## 11 · 测试与守卫（IT 隔离纪律）

- **RLS 验证用 `app_agent`（NOBYPASSRLS）**，禁用 `ai_agent`（BYPASSRLS 会假绿）——沿用 memory 铁律。
- **IT 造数用 `ItTenantProvisioner.provision(adminPool, "it-quality")`**（`it-` 前缀），在 `@BeforeAll` 回收（`purgeItTenantRuns`），连续两轮全量后各 it- 租户 Run 数应一致（回收纪律）。
- **新增守卫建议**：
  1. `RunMessagePersistenceIT`：worker 跑一个 Run → 断言 `run_message` 行数 = 预期（USER+SYSTEM+ASSISTANT×N+TOOL×M），且 `app_agent` 能读、`ai_agent` 在 FORCE RLS 下若未绑租户读不到（防假绿）。
  2. `TraceMetricAutoCollectIT`：Run 终态后消费 `agent.trace.collect` → 断言 `agent_trace_metric` 出现该行（验证 G2 不再空转）。
  3. `TraceReplayRlsIT`：跨租户 runId 经租户侧端点返回 404（防越权）。
  4. `ReviewSamplingCoverageTest`：模拟 `n<30` → 断言抽样返回"样本不足"而非百分比。
- 共享库断言必带租户过滤；`*IT` 中"种子租户字面量 + INSERT INTO run_message"同时出现即判红（沿用 `SeedTenantWriteArchitectureTest` 纪律）。

---

## 12 · 落地里程碑（优先级）

| 阶段 | 内容 | 价值 | 关键交付 |
|---|---|---|---|
| **P0** ✅ 已完成 | 自动采集接线（§5） | 现有看板立刻有数，暴露真实质量 | Rabbit + DB **双通道** `agent.trace.collect` 专用链路（默认 database） |
| **P1** ✅ 已完成 | 回放底座（§2.1+§3+§4+§6） | 补齐对话内容持久化与回放 | `run_message` DDL + 仓储 + worker 埋点 + 两端点（**落地偏差见 §0.0**） |
| **P2** ✅ 已完成 | 审查闭环（§8） | 看→判→填闭环 | `review_task` DDL + `ReviewTaskPort` + 端点 + 告警联动 + 闭环 IT + RDS 实跑；admin-console `ReviewQueuePage` 前端已接 |
| **P3** ✅ 已完成（L1 半边 + 场景维度） | 评估双轨（§7） | 覆盖率是真数字 | `L1AttributionEvaluator` + `TraceEvaluationService` + 回执/覆盖率回填 + 闭环 IT + RDS 实跑；`scenario` 显式标签维度已接通（落地偏差见 §0.0）；**LLM-as-judge 未做** |
| **P4** ✅ 已完成 | 监控升级（§9） | 可行动的监控 | Grafana PG 数据源 + Prometheus scrape + **13 面板**（覆盖率并排 / 样本不足 / 队列 / 按场景拆分），全部 SQL 经 RDS 与 Grafana 数据源 API 实跑；admin-console 按场景筛选（五端点同口径 + `/scenarios` 枚举）已在真实应用实跑 200 |

---

## 13 · 仍待补充信息（方案 §6 未决项，实现前需确认）

1. **Grafana/Prometheus 是否部署**——✅ **已答并完成接入（2026-11-08）**：Prometheus `java-multi-agent` 作业已启用，Grafana PG 数据源 `platform-pg` 已 provisioning（RDS 主机名 + `sslmode=disable`），看板 13 面板全部 SQL 实跑通过、Grafana API 确认热加载。
2. **`run_message` 保留期与合规口径**——🟡 **按保守默认实施，待产品确认**：保留期与 Run 对齐默认 30 天、不额外存敏感原文（M2 SYSTEM 只存记忆标签）。教育/未成年人数据的特定期限与脱敏要求仍无正式口径。
3. **L1 自动判分源**——⛔ **唯一实质阻塞项**：参考答案/题库标准解析（`ATTRIBUTION` checker）是否存在仍未知。当前 L1 只判链路完整性（`TRACE_LINK_L1`），**LLM-as-judge 兜底未接线**：没有判分源就让大模型猜一个「归因正确率」是编造指标。拿到判分源与成本口径后才接模型判分。
4. **预期量级**——🟡 **按 `<1k Run/日` 设计，待产品确认**；决定 `run_message` 存储与 `mv` 刷新频率，当前为可配置项。
5. **回放受众**——🟡 **按「仅平台审计 / ADMIN 可读」实施，待产品确认**：租户侧端点对 STUDENT 隐藏 TOOL 消息。学生端是否开放「查看我的辅导记录」未定，会改变 §6.2 脱敏粒度。

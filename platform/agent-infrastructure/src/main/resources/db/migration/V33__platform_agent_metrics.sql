-- =====================================================================
-- V33 · Agent 效果指标体系 DDL（P0-1 收编：deploy/sql/platform/08_platform_agent_metrics_ddl.sql）
-- 评审 §4.1：P7 六类指标表 + run.trace_id 补列 + 小时桶物化视图。
-- 头号纪律：未采集 = NULL，绝不等于 0（所有缺省列不设 DEFAULT 0）。
-- =====================================================================
-- =====================================================================
-- Agent 效果指标体系 DDL（P7）
-- =====================================================================
--
-- 【要解决的问题】线上 Agent 目前只有「跑没跑完」，没有「跑得好不好、
--    花了多少、比上次差多少」。本脚本建立六大类指标的数据底座：
--      SLA/可用性 · 成本 · Agent 过程行为 · 业务效果 · 用户反馈 · 风险安全
--
-- 【统计单元】一条 Trace = 一次用户 Agent 会话任务，trace_id 唯一标识。
--    只有 Run 到达终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT）的 Trace
--    才被采集 —— 在途 Trace 计入会把「还没跑完」算成「没成功」。
--
-- 【为何放在 platform schema】
--    · 事实源（public.run / tool_operation / usage_record）带 FORCE RLS，
--      采集器必须在**租户上下文**里读它们（这是正确的，租户侧隔离不能破）；
--    · 指标是**平台观测域**的数据，需要按平台视角跨租户聚合；
--    · platform schema 无 RLS（与 sys_dictionary / platform_audit_event 一致），
--      采集器读租户数据后以系统身份写入，查询端天然跨租户。
--    → 读在租户域、写在平台域，两侧边界都干净。
--
-- 【⚠️ 头号纪律：未采集 = NULL，绝不等于 0】
--    本脚本中所有「当前尚无采集点」的列一律 **不设 DEFAULT 0**。
--    给它们 DEFAULT 0 会让「没测过幻觉」在 SQL 里变成「幻觉率 0%」——
--    这是本项目反复出现的失真来源。前端读到 NULL 显示「未采集」。
--
-- 【⚠️ 合规红线】agent_risk_event 只存 detail_digest（摘要）与类型计数，
--    **绝不落原文**。命中敏感信息的原文一旦入库就是二次泄露——
--    「拦截住了」会变成「存下来了」。
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS platform;

-- ── 0 · 事实源补列 ──────────────────────────────────────────────────
-- run 上原本只有 correlation_id，没有 trace_id。新增列刻意**可空**：
-- 存量 Run 根本没有 trace_id，用 NOT NULL + 假值填充＝用猜测冒充事实。
ALTER TABLE public.run ADD COLUMN IF NOT EXISTS trace_id text;

CREATE INDEX IF NOT EXISTS idx_run_tenant_trace
    ON public.run (tenant_id, trace_id)
    WHERE trace_id IS NOT NULL;

COMMENT ON COLUMN public.run.trace_id IS
    '一次用户 Agent 会话任务的唯一标识（P7）。⚠️ 存量行为 NULL —— 当时的会话标识只落在 '
    'agent_invocation.trace_id（仅覆盖有委派的链路）或以 correlation_id 代用。'
    '采集器对存量 Run 降级取 correlation_id，并在指标行标记 trace_id_source，'
    'NULL 的语义是「当时未记录」，不是「不存在这条 Trace」。';


-- ── 1 · 指标宽表（核心）────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.agent_trace_metric (
    trace_id                  text        NOT NULL,
    tenant_id                 uuid        NOT NULL,
    run_id                    uuid        NOT NULL,
    agent_id                  uuid,
    agent_version             integer,
    scenario                  text,
    model_id                  text,

    -- ── SLA / 可用性 ──
    started_at                timestamptz NOT NULL,
    ended_at                  timestamptz,
    duration_ms               bigint,
    terminal_state            text        NOT NULL,
    termination_reason        text        NOT NULL,

    -- ── 成本 ──
    input_tokens              bigint,
    output_tokens             bigint,
    cost_amount               numeric(18,6),
    currency                  text,

    -- ── Agent 过程行为 ──
    tool_rounds               integer,
    tool_call_total           integer,
    tool_call_failed          integer,
    tool_call_invalid         integer,
    tool_call_duplicate       integer,
    loop_suspected_count      integer,
    max_rounds_reached        boolean,
    clarification_count       integer,
    think_ms_total            bigint,
    tool_ms_total             bigint,

    -- ── 业务效果（L1 自动校验 / L2 抽样标注；未接入时为 NULL）──
    auto_checked              boolean,
    auto_verified             boolean,
    sampled                   boolean,
    human_verdict             text,
    hallucination             boolean,
    instruction_followed      boolean,

    -- ── 用户反馈（L3 有噪信号）──
    feedback_up               integer,
    feedback_down             integer,
    retry_count               integer,
    abandoned                 boolean,

    -- ── 风险安全 ──
    safety_blocked_count      integer,
    unauthorized_attempt_count integer,
    sensitive_output_hits     integer,

    -- ── 元信息 ──
    trace_id_source           text        NOT NULL,
    metric_version            integer     NOT NULL DEFAULT 1,
    computed_at               timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT agent_trace_metric_pk PRIMARY KEY (trace_id),
    CONSTRAINT agent_trace_metric_state_ck CHECK (
        terminal_state IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT agent_trace_metric_reason_ck CHECK (
        termination_reason IN ('COMPLETED','USER_CANCELLED','MODEL_ERROR','TOOL_TIMEOUT',
                               'TOOL_5XX','MAX_ROUNDS','SAFETY_BLOCK','TOKEN_BUDGET','OTHER')),
    CONSTRAINT agent_trace_metric_source_ck CHECK (
        trace_id_source IN ('RUN_TRACE_ID','INVOCATION_TRACE_ID','CORRELATION_FALLBACK')),
    CONSTRAINT agent_trace_metric_verdict_ck CHECK (
        human_verdict IS NULL OR human_verdict IN ('SUCCESS','PARTIAL','FAIL'))
);

COMMENT ON TABLE platform.agent_trace_metric IS
    'P7 指标宽表：一条 Trace 一行。指标是事实表的**确定函数** —— 重复采集必须得到同一结果，'
    '故写入一律 INSERT ... ON CONFLICT (trace_id) DO UPDATE（幂等）。';

COMMENT ON COLUMN platform.agent_trace_metric.termination_reason IS
    '归一化终止原因（9 值，P7 §3.1 映射规则为唯一权威）。⚠️ 兜底必须是 OTHER 而不是丢弃 —— '
    '丢弃未识别原因会让「终止原因分布」的合计小于总数，看板上出现「不知道哪去了」的黑洞。';

COMMENT ON COLUMN platform.agent_trace_metric.terminal_state IS
    '⚠️ 链路终态 **≠ 任务成功**。SUCCEEDED 只说明框架没崩、走完了终止逻辑，'
    '是否真的达成了用户目标由 human_verdict / auto_verified 回答。两者绝不可混用。';

COMMENT ON COLUMN platform.agent_trace_metric.tool_rounds IS
    'P7-2 起为**真实口径**：取自 run_step 的 TOOL 步骤计数（JdbcRunStepRepository 已补全写入方）。'
    '历史未埋点的旧 Run 查不到步骤行 → 如实为 NULL（未采集），绝不退回退化近似。'
    '注意：run_step 仅覆盖 P7-2 之后执行的 Run；此前数据的步数类指标显示「未采集」。';

CREATE INDEX IF NOT EXISTS idx_agent_metric_tenant_time
    ON platform.agent_trace_metric (tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_metric_agent_time
    ON platform.agent_trace_metric (agent_id, agent_version, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_metric_reason
    ON platform.agent_trace_metric (started_at DESC, termination_reason);


-- ── 1.1 · 工具维度（按 Trace × 工具名聚合）─────────────────────────
--
-- 【为何需要这张表】工具明细事实在 public.tool_operation，而该表带 FORCE RLS
--    （策略形如 tenant_id = current_tenant_id()）。平台侧以系统身份连接时
--    current_tenant_id() 为空 → **一行都读不到**。
--    因此工具维度指标必须由采集器在**租户上下文**读完后，
--    以平台身份落到这里——延续「读在租户域、写在平台域」。
--    试图用系统连接跨租户直读 public 表是本脚本最容易踩的坑。
--
-- 【为何不存 P95】分位数无法从 count/sum 还原。本表只存 total / max 耗时，
--    工具耗时看板呈现**均值与最大值**；需要真 P95 时按 P7-2 打通
--    run_step 后从 attempt 明细算。
CREATE TABLE IF NOT EXISTS platform.agent_trace_tool_call (
    trace_id          text    NOT NULL,
    tenant_id         uuid    NOT NULL,
    tool_name         text    NOT NULL,
    call_count        integer NOT NULL,
    failed_count      integer NOT NULL DEFAULT 0,
    invalid_count     integer NOT NULL DEFAULT 0,
    duplicate_count   integer NOT NULL DEFAULT 0,
    total_duration_ms bigint,
    max_duration_ms   bigint,
    started_at        timestamptz NOT NULL,
    CONSTRAINT agent_trace_tool_call_pk PRIMARY KEY (trace_id, tool_name)
);

COMMENT ON TABLE platform.agent_trace_tool_call IS
    '工具维度指标（按 Trace × 工具名）。⚠️ 不存 P95 —— 分位数无法从计数与求和还原，'
    '强行用 max 冒充 P95 会让尾部延迟被高估。看板呈现均值 + 最大值。';

CREATE INDEX IF NOT EXISTS idx_agent_trace_tool_time
    ON platform.agent_trace_tool_call (started_at DESC, tool_name);


-- ── 2 · 自动终态校验结果（L1 · 高可信但有覆盖率）─────────────────
CREATE TABLE IF NOT EXISTS platform.agent_task_outcome (
    outcome_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        text        NOT NULL,
    tenant_id       uuid        NOT NULL,
    checker_code    text        NOT NULL,
    external_ref    text,
    verified        boolean     NOT NULL,
    evidence_digest text,
    checked_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_task_outcome_trace_checker_uq UNIQUE (trace_id, checker_code)
);

COMMENT ON TABLE platform.agent_task_outcome IS
    '外部终态校验回执（L1）：工单是否真创建、数据是否真写入、文件是否真生成、'
    '查询结果是否合规。⚠️ 自动校验成功率必须**并排显示覆盖率** —— '
    '只校验了 3% 的任务时，「成功率 99%」这句话本身就是误导。';


-- ── 3 · 抽样标注（L2 · 黄金基线，必须带样本量）────────────────────
CREATE TABLE IF NOT EXISTS platform.agent_eval_sample (
    sample_id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id             text        NOT NULL,
    tenant_id            uuid        NOT NULL,
    verdict              text        NOT NULL,
    hallucination        boolean,
    instruction_followed boolean,
    note                 text,
    sampler              text        NOT NULL,
    sampled_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_eval_sample_verdict_ck CHECK (verdict IN ('SUCCESS','PARTIAL','FAIL'))
);

COMMENT ON TABLE platform.agent_eval_sample IS
    '人工/标注平台抽检打标（L2 黄金基线）。⚠️ 样本量 n < 30 时不得显示为百分比 —— '
    '用 2 条样本算出的 50% 幻觉率是噪声，不是指标。此时一律展示「样本不足」。';


-- ── 4 · 用户反馈事件流（L3 · 有噪，只看趋势）──────────────────────
CREATE TABLE IF NOT EXISTS platform.agent_trace_feedback (
    feedback_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id     text        NOT NULL,
    tenant_id    uuid        NOT NULL,
    kind         text        NOT NULL,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_trace_feedback_kind_ck CHECK (
        kind IN ('THUMB_UP','THUMB_DOWN','RETRY','ABANDON'))
);

COMMENT ON TABLE platform.agent_trace_feedback IS
    '用户显式反馈与行为信号（L3）。⚠️ **不能单独当真值**：下游工具故障、网络抖动、'
    'UI 卡顿都会导致差评。差评率上涨的处置是**触发抽样复核**，'
    '而不是直接判定 Agent 劣化。';


-- ── 5 · 风险安全事件（脱敏）────────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.agent_risk_event (
    risk_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id     text        NOT NULL,
    tenant_id    uuid        NOT NULL,
    event_type   text        NOT NULL,
    detail_digest text,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_risk_event_type_ck CHECK (
        event_type IN ('SAFETY_BLOCK','UNAUTHORIZED_CALL','SENSITIVE_OUTPUT'))
);

COMMENT ON TABLE platform.agent_risk_event IS
    '安全事件明细。⚠️ **合规红线：只存摘要，绝不落原文**。'
    '命中敏感信息的原文一旦入库就构成二次泄露，等于把「拦截住了」变成「存下来了」。';

COMMENT ON COLUMN platform.agent_risk_event.detail_digest IS
    '事件摘要（如命中模式的类型代号 + 哈希前 8 位），不含任何原文片段。';


-- ── 6 · 小时桶物化视图（Grafana 时序直读）──────────────────────────
--
-- 【为何不建「桶表」】分位数（P95/P99）无法从计数与求和还原。因此：
--   · 计数/求和类（QPS、成功率、费用、token、轮次）→ 走本视图，成本低、响应快；
--   · 分位数类（P50/P95/P99）→ 直接查明细表 agent_trace_metric。
-- 这是工业界标准做法：物化视图扛时序，明细扛分位。
CREATE MATERIALIZED VIEW IF NOT EXISTS platform.mv_agent_metric_hourly AS
SELECT
    date_trunc('hour', started_at)          AS bucket_start,
    tenant_id,
    agent_id,
    agent_version,
    scenario,
    count(*)                                                        AS trace_count,
    count(*) FILTER (WHERE terminal_state = 'SUCCEEDED')            AS succeeded_count,
    count(*) FILTER (WHERE terminal_state = 'FAILED')               AS failed_count,
    count(*) FILTER (WHERE terminal_state = 'CANCELLED')            AS cancelled_count,
    count(*) FILTER (WHERE terminal_state = 'TIMED_OUT')            AS timed_out_count,
    count(*) FILTER (WHERE termination_reason = 'COMPLETED')        AS reason_completed,
    count(*) FILTER (WHERE termination_reason = 'USER_CANCELLED')   AS reason_user_cancelled,
    count(*) FILTER (WHERE termination_reason = 'MODEL_ERROR')      AS reason_model_error,
    count(*) FILTER (WHERE termination_reason = 'TOOL_TIMEOUT')     AS reason_tool_timeout,
    count(*) FILTER (WHERE termination_reason = 'TOOL_5XX')         AS reason_tool_5xx,
    count(*) FILTER (WHERE termination_reason = 'MAX_ROUNDS')       AS reason_max_rounds,
    count(*) FILTER (WHERE termination_reason = 'SAFETY_BLOCK')     AS reason_safety_block,
    count(*) FILTER (WHERE termination_reason = 'TOKEN_BUDGET')     AS reason_token_budget,
    count(*) FILTER (WHERE termination_reason = 'OTHER')            AS reason_other,
    coalesce(sum(input_tokens), 0)                                  AS input_tokens,
    coalesce(sum(output_tokens), 0)                                 AS output_tokens,
    coalesce(sum(cost_amount), 0)                                   AS cost_amount,
    count(*) FILTER (WHERE (coalesce(input_tokens,0) + coalesce(output_tokens,0)) > 20000)
                                                                    AS oversized_token_traces,
    coalesce(sum(tool_call_total), 0)                               AS tool_call_total,
    coalesce(sum(tool_call_failed), 0)                              AS tool_call_failed,
    coalesce(sum(tool_call_invalid), 0)                             AS tool_call_invalid,
    coalesce(sum(tool_call_duplicate), 0)                           AS tool_call_duplicate,
    coalesce(sum(loop_suspected_count), 0)                          AS loop_suspected_total,
    count(*) FILTER (WHERE max_rounds_reached)                      AS max_rounds_traces,
    coalesce(sum(duration_ms), 0)                                   AS duration_ms_sum,
    count(*) FILTER (WHERE auto_checked)                            AS auto_checked_count,
    count(*) FILTER (WHERE auto_verified)                           AS auto_verified_count,
    count(*) FILTER (WHERE sampled)                                 AS sampled_count,
    count(*) FILTER (WHERE human_verdict = 'SUCCESS')               AS human_success_count,
    count(*) FILTER (WHERE hallucination)                           AS hallucination_count,
    count(*) FILTER (WHERE safety_blocked_count > 0)                AS safety_blocked_traces,
    count(*) FILTER (WHERE unauthorized_attempt_count > 0)          AS unauthorized_traces,
    count(*) FILTER (WHERE sensitive_output_hits > 0)               AS sensitive_output_traces
FROM platform.agent_trace_metric
GROUP BY 1, 2, 3, 4, 5;

-- REFRESH CONCURRENTLY 要求唯一索引；刷新的同时读不阻塞。
CREATE UNIQUE INDEX IF NOT EXISTS mv_agent_metric_hourly_uq
    ON platform.mv_agent_metric_hourly
    (bucket_start, tenant_id, coalesce(agent_id, '00000000-0000-0000-0000-000000000000'::uuid),
     coalesce(agent_version, 0), coalesce(scenario, ''));

COMMENT ON MATERIALIZED VIEW platform.mv_agent_metric_hourly IS
    '小时桶聚合，供 Grafana 时序面板直读。⚠️ 刷新期间会读到上一版数据 —— '
    '查询响应体必须带上 refreshed_at，前端展示数据时效，不能假装是实时的。'
    '刷新：REFRESH MATERIALIZED VIEW CONCURRENTLY platform.mv_agent_metric_hourly;';


-- ── 7 · 供 Grafana 直接用的分位数查询（示例，不建对象）────────────
--
-- 端到端耗时 P50/P95/P99（查明细，不要查物化视图）：
--
--   SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms) AS p50,
--          percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95,
--          percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99
--   FROM platform.agent_trace_metric
--   WHERE started_at BETWEEN $__timeFrom() AND $__timeTo()
--     AND duration_ms IS NOT NULL;
--
-- 工具维度失败率：
--
--   SELECT tool_name,
--          count(*) FILTER (WHERE state IN ('FAILED','UNKNOWN')) * 1.0 / count(*) AS fail_rate
--   FROM public.tool_operation
--   WHERE created_at BETWEEN $__timeFrom() AND $__timeTo()
--   GROUP BY tool_name ORDER BY count(*) DESC LIMIT 20;

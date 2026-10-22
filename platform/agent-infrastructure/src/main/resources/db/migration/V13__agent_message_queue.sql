-- V13：PostgreSQL 异步消息队列（P4-D17 / 2026-10-22 · 消息通道可切换）
--
-- ⚠️ 为什么要有这张表：
--   消息通道原先**写死**为 RabbitMQ（交换机/quorum 队列/容器工厂/@RabbitListener
--   全部无条件装配）。代价有三：
--     ① 无 broker 的环境（CI）要么启动失败，要么被迫把整套 worker 关掉——
--        而「关掉 worker」等于回到「消息永不消费」的断链形态；
--     ② 换一种异步载体要改代码 + 重新发版 + 重验全链路；
--     ③ 试运营阶段无法「先小范围用新通道、稳了再全量」。
--   故新增一张**以数据库为载体的异步队列表**：它把「broker 依赖」变成
--   「一个可切换的配置项」，而**不改变任何业务语义**。
--
-- ⚠️ 为什么是「新增表」而不是复用 outbox_event：
--   outbox_event 是**发布侧**的事务性日志（Run 创建与消息同事务落库），
--   其状态机是 PENDING → PUBLISHED/FAILED，语义是「是否已交给通道」。
--   而本表是**通道侧**的队列，语义是「是否已被消费」。
--   两者混用会导致：切换模式时旧状态机残留、新旧消费端争抢同一批行，
--   且「已发布但未消费」这一最危险的状态无法表达。
--   故**独立成表**，两种模式各自独立、可同时存在、可安全切换。
--
-- ⚠️ 语义对齐 RabbitMQ（刻意保持一致，避免切换改变行为）：
--   · at-least-once：领取后若进程崩溃，行停在 INFLIGHT，
--     由 claimed_at 超过可见性超时后被重新领取（等价于 broker 的 unacked 重投）；
--   · 有限重试：attempts 达上限即进死信表（等价于 x-delivery-limit → DLX）；
--   · 死信**不自动删除**：留待人工介入（等价于 Rabbit 的 DLQ 语义）。
--
-- ⚠️ 载荷最小化：与 outbox_event / MQ 消息体一致，
--    只承载唤醒信号（runId/tenantId/attempt/fencingToken/enqueuedAt），
--    不含 Prompt / 工具参数 / 学生数据（P0-D00 §4 白名单）。
--    应用层仍会用 OutboxMessage.assertPayloadSafe 复核，此处只做**结构性**约束。

CREATE TYPE agent_message_state AS ENUM (
    'PENDING',    -- 待领取
    'INFLIGHT'    -- 已领取未确认（进程崩溃后由可见性超时回收）
);

CREATE TABLE IF NOT EXISTS agent_message_queue (
    queue_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id),
    run_id         UUID NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    state          agent_message_state NOT NULL DEFAULT 'PENDING',
    -- 重试次数：达到上限即进死信（对齐 x-delivery-limit=3 的意图）
    attempts       INTEGER NOT NULL DEFAULT 0,
    -- 下次可领取时刻：退避重试用（等价于 broker 的延迟重投）
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 领取者与领取时刻：进程崩溃后据此回收 INFLIGHT 行
    claimed_by     TEXT,
    claimed_at     TIMESTAMPTZ,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_message_attempts_non_negative CHECK (attempts >= 0),
    -- 与 outbox_event 一致：消息随 Run 一起消失（Run 已删则执行无意义）
    CONSTRAINT agent_message_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE,
    -- 领取态必须同时给出领取者与时刻：否则无法判断是否超时可回收
    CONSTRAINT agent_message_claim_complete CHECK (
        (state = 'INFLIGHT' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL)
        OR (state = 'PENDING' AND claimed_by IS NULL AND claimed_at IS NULL)
    )
);

COMMENT ON TABLE agent_message_queue IS
    'P4-D17 PostgreSQL 异步消息队列：RabbitMQ 的可切换替代载体。'
    'at-least-once + 可见性超时回收 + 有限重试，与 Rabbit 通道语义对齐';

-- ── 索引 ──
-- ⚠️ 领取路径的索引：只覆盖 PENDING（INFLIGHT 的回收走另一条索引）
CREATE INDEX IF NOT EXISTS idx_agent_message_pending
    ON agent_message_queue (next_attempt_at, created_at)
    WHERE state = 'PENDING';

-- ⚠️ INFLIGHT 回收路径：按领取时刻找「超时未确认」的行
CREATE INDEX IF NOT EXISTS idx_agent_message_inflight
    ON agent_message_queue (claimed_at)
    WHERE state = 'INFLIGHT';

-- ⚠️ 深度探测路径（背压）：按租户数未确认行
CREATE INDEX IF NOT EXISTS idx_agent_message_tenant_state
    ON agent_message_queue (tenant_id, state);

-- ── 死信表 ──
-- ⚠️ 死信**只进不出**（无自动清理）：对应 Rabbit 的 DLQ 语义，
--    需人工介入处置。行级保留期由 P4-D04 的受控清理通道承担。
CREATE TABLE IF NOT EXISTS agent_message_dead_letter (
    dead_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id),
    queue_id       UUID NOT NULL,
    run_id         UUID NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    attempts       INTEGER NOT NULL,
    error          TEXT NOT NULL,
    failed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ⚠️ 无 FK 指向 run：Run 被删除后**死信证据仍须留存**（对账与追责用）
    CONSTRAINT agent_message_dead_attempts_non_negative CHECK (attempts >= 0)
);

COMMENT ON TABLE agent_message_dead_letter IS
    'P4-D17 消息死信：重试耗尽或被拒的消息。只进不出，须人工介入';

CREATE INDEX IF NOT EXISTS idx_agent_message_dead_tenant
    ON agent_message_dead_letter (tenant_id, failed_at);

-- ── RLS：与其他表一致 ──
-- ⚠️ FORCE ROW LEVEL SECURITY 不可省略：表 owner 不受普通 RLS 约束，
--    不 FORCE 则 owner 连接可绕过隔离（V8 已踩过，见该迁移注释）。
ALTER TABLE agent_message_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_message_queue FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS agent_message_queue_tenant_isolation ON agent_message_queue;
CREATE POLICY agent_message_queue_tenant_isolation ON agent_message_queue
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE agent_message_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_message_dead_letter FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS agent_message_dead_letter_tenant_isolation ON agent_message_dead_letter;
CREATE POLICY agent_message_dead_letter_tenant_isolation ON agent_message_dead_letter
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ── GRANT ──
-- ⚠️ **逐条执行而非放进同一事务**：任一条失败会连带回滚全部 GRANT。
-- ⚠️ 新表漏 GRANT 的后果不是「功能不可用」而是 `permission denied`，
--    配置写入整体 fail-closed（P3-D08 踩过同形态：限流自身故障反成 DoS 入口）。
DO $$
BEGIN
    EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON agent_message_queue TO app_agent';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON agent_message_dead_letter TO app_agent';
END $$;

-- ── updated_at 自动维护 ──
CREATE OR REPLACE FUNCTION touch_agent_message_queue_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_agent_message_queue_touch ON agent_message_queue;
CREATE TRIGGER trg_agent_message_queue_touch
    BEFORE UPDATE ON agent_message_queue
    FOR EACH ROW EXECUTE FUNCTION touch_agent_message_queue_updated_at();

-- ════════════════════════════════════════════════════════════════════
-- V21 · Run 业务截止时间（多级 Agent v1.1 · FR-024 · MA-T09）
-- ════════════════════════════════════════════════════════════════════
--
-- ⚠️ 为什么要加这个字段：
--   多级 Agent 的不可妥协约束之一是「全调用树共享超时上限」——
--   根 Run 的剩余时间是整棵调用树的**共同上界**，任何一跳都不得重置它。
--   但此前 `run` 表**没有任何截止时间字段**，闸门只能把「根 Run 剩余时间」
--   传成上界 Long.MAX_VALUE → 该约束在根层从未真正生效：
--   一个父 Agent 可以无限次委派，每一跳的子 Agent 都能跑满自己的 timeoutMs。
--
-- ⚠️ 为什么是 NULL 而不是 NOT NULL：
--   存量 Run 没有截止时间。若强加 NOT NULL 并回填一个占位值，
--   就等于把「占位超时」变成生产默认行为 —— 那比"不限时"更危险
--   （看起来有上限，实际是随意的数）。NULL = 不限时，语义诚实且向后兼容。
--
-- ⚠️ deadline_at 与 lease_expires_at 是**两件事**：
--   lease_expires_at = 租约到期（worker 换手，另一个 worker 接手继续跑）；
--   deadline_at      = 业务截止（这个 Run 该结束了）。
--   混用会把「换手」误判成「超时」，或让已超时的 Run 因续租而无限跑下去。

ALTER TABLE run ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ;

COMMENT ON COLUMN run.deadline_at IS
    'Run 业务截止时刻（NULL = 不限时）。多级 Agent 的「全调用树共享超时上限」以此为根上界；与 lease_expires_at（租约到期）语义不同，不得混用。';

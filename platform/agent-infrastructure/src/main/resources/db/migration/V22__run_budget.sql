-- ════════════════════════════════════════════════════════════════════
-- V22 · Run 级预算额度（多级 Agent v1.1 · FR-024 · MA-T17）
-- ════════════════════════════════════════════════════════════════════
--
-- ⚠️ 为什么要加这个字段：
--   不可妥协约束之一是「全调用树共享预算上限，不能通过嵌套重置额度」。
--   此前系统只有 `budget_account`（租户 + 币种维度）——
--   也就是说：一个 Run 可以把整个租户的余额烧光，
--   而「这一棵调用树最多花多少」**没有任何独立口径**。
--
-- ⚠️ 为什么不需要新增「已用额」列：
--   `budget_reservation.run_id` 已存在（外键指向 run，且有索引
--   idx_reservation_run (tenant_id, run_id)）。所有工具调用 ——
--   无论多深、属于哪个 Agent —— 都记在**同一个 run_id** 下，
--   因此「按 run_id 聚合的预留 + 结算」天然就是「这一整棵树的消耗」，
--   嵌套 Agent 无法通过重置额度绕过上限。
--
-- ⚠️ 为什么是 NULL 而不是 NOT NULL：
--   NULL = 未声明 Run 级额度 = **不额外限制**（不是「额度为 0」）。
--   存量 Run 没有这个约束；强加 NOT NULL 并回填 0 会让所有存量 Run
--   立刻「一分不许花」，回填一个占位额度则比「不限」更危险
--   （看起来有上限，实际是随意的数）。

ALTER TABLE run ADD COLUMN IF NOT EXISTS budget_amount NUMERIC(18, 6);

COMMENT ON COLUMN run.budget_amount IS
    'Run 级预算额度（NULL = 不额外限制；非空时按 budget_reservation.run_id 聚合判定）。与租户级 budget_account 是两个独立口径：前者约束单棵调用树，后者约束租户总量。';

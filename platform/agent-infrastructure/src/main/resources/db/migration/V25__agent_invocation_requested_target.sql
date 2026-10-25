-- =====================================================================
-- V25：委派记录补「模型原始候选」（多级 Agent v1.1 · 第二十三批 · A3）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--     §变更补充：多级 Agent 数据模型与发布闭包（v1.1）· 关系数据
--   docs/多级 Agent优化/08_安全测试与验收矩阵.md
--     · MA-T05：路由模型输出未声明目标 Agent → 拒绝委派，不触发子执行
--     · MA-T15：编排声明固化进发布闭包
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- ⚠️ 为什么补这一列（这是一个**审计盲区**，不是功能缺失）：
--   agent_invocation 记录的是**实际生效**的目标（target_agent_id）——
--   这是对的：委派记录的职责是「谁真的被调用了」，写模型候选会让审计
--   看到一份「并非事实」的委派。
--   但 ORDERED_FALLBACK 降级时，**模型给出的原始候选**就此丢失：
--   事后无法从数据上直接回答
--   「这次是不是发生了路由降级、模型原本想选谁」——只剩日志留痕。
--   本列把「模型原始候选」与「实际生效目标」**并存**：
--     · target_agent_id            → 谁真的被调用了（权威事实）
--     · requested_target_agent_id  → 模型本来想要谁（审计线索）
--   两者**不互相替代**：只记生效目标就看不出降级，只记候选就看不出事实。
--
-- ⚠️ **回填口径：不回填**（这是本脚本最需要被审的一点）：
--   存量行的「模型原始候选」在任何**持久化**位置都不存在 ——
--   当时只有应用日志留痕，而日志可能已轮转、已归档、已按租户擦除，
--   也可能根本未落在同一台机器上。据此回填 = 用**猜测**冒充事实：
--   猜错的行会把「没降级」记成「降级过」，比空值更有害。
--   故存量行一律留 NULL，语义是「**当时未记录**」（≠「没有降级」）。
--   ⚠️ 读侧必须区分这两种含义：`AgentInvocation.routedFallback()` 在
--   requested_target_agent_id 为 NULL 时返回 false，那只意味着「无从判断」。
--
-- ⚠️ 本脚本必须**幂等**：可重复执行而不报错。
--   所有结构变更用 DO $$ ... IF NOT EXISTS 包裹。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 补列
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'agent_invocation'
          AND column_name = 'requested_target_agent_id'
    ) THEN
        ALTER TABLE agent_invocation
            ADD COLUMN requested_target_agent_id UUID;
    END IF;
END;
$$;

COMMENT ON COLUMN agent_invocation.requested_target_agent_id IS
    '模型给出的原始候选目标（路由降级**前**）。'
    '与 target_agent_id（实际生效的目标）并存：'
    '两者不同即说明发生了 ORDERED_FALLBACK 路由降级。'
    '⚠️ 存量行为 NULL：当时模型原始候选只在日志里留痕、没有任何持久化副本，'
    '无法可靠回填（据日志回填 = 用猜测冒充事实，比空值更有害）——'
    'NULL 的语义是「**当时未记录**」，**不是**「没有降级」。';

-- ---------------------------------------------------------------------
-- 2. 降级记录的索引
-- ---------------------------------------------------------------------
-- ⚠️ 索引只覆盖「**确实发生过降级**」的行：
--   谓词必须显式带上 `requested_target_agent_id IS NOT NULL` ——
--   否则存量行（NULL vs target_agent_id）在 `IS DISTINCT FROM` 下会被判为「不同」，
--   把**没有发生降级**的历史行也算进降级集合 —— 那正是本列要消除的审计噪音。
--
-- ⚠️ 部分索引的谓词必须是**不可变**表达式：
--   `IS DISTINCT FROM` 作用在两个普通列上是不可变的（仅在含序列/时区函数的
--   表达式上不可用），PostgreSQL 接受该谓词。
--
-- 服务的查询：「这次委派是不是降级了、模型原本想选谁」——
--   WHERE tenant_id = ? AND requested_target_agent_id IS DISTINCT FROM target_agent_id

CREATE INDEX IF NOT EXISTS idx_agent_invocation_routed_fallback
    ON agent_invocation (tenant_id, started_at DESC)
    WHERE requested_target_agent_id IS NOT NULL
      AND requested_target_agent_id IS DISTINCT FROM target_agent_id;

COMMENT ON INDEX idx_agent_invocation_routed_fallback IS
    '只索引「确实发生过路由降级」的委派记录：'
    'requested_target_agent_id 非空且与 target_agent_id 不同。'
    '降级记录是全部委派里的一小撮，这个索引让「哪些委派发生了降级」'
    '成为一次廉价的范围扫描，而不是全表过滤。';

-- ---------------------------------------------------------------------
-- 3. 审计线索的反查索引
-- ---------------------------------------------------------------------
-- 服务的查询：「模型原本想选 Agent X 的委派有哪些」（评估某个 Agent 被
-- 降级掉的频次，是策略调优的直接输入）。
--
-- ⚠️ 只索引非空值：存量行（NULL）本来就没有候选，任何反查都不该命中它们。

CREATE INDEX IF NOT EXISTS idx_agent_invocation_requested_target
    ON agent_invocation (tenant_id, requested_target_agent_id)
    WHERE requested_target_agent_id IS NOT NULL;

COMMENT ON INDEX idx_agent_invocation_requested_target IS
    '按「模型原始候选」反查委派记录 —— 用于统计某个 Agent 被路由降级掉的频次。';

-- P3-D08 / D-2：固定窗口限流桶
--
-- ⚠️ 为什么预算约束不够（D-2 的核心论点）：
--    `budget_no_overspend` 管的是「总量」，限流管的是「速率」。
--    攻击者以 1000 QPS 调用、每笔远低于单次预留额时，
--    预算约束**一次都不会触发**，但预算会在几分钟内被打穿。
--    二者不可互相替代，必须同时存在。
--
-- ⚠️ 为什么用固定窗口而非滑动窗口（§2.2 D-2）：
--    固定窗口的边界突发最大 2× limit，是**有界**的；
--    滑动窗口需行锁，会让限流本身成为锁竞争点（新 DoS 入口）。
--    用「有界的 2×」换「无界的现状」，是净收益。
--
-- ⚠️ 为什么唯一索引是原子性的基础（D-1）：
--    check-then-act（先 SELECT 再 INSERT）在并发下必然超发。
--    `INSERT ... ON CONFLICT (cols) DO UPDATE` 依赖的正是这个唯一索引；
--    去掉它，冲突目标不存在，原子 upsert 退化为「插入重复行」。

CREATE TABLE IF NOT EXISTS rate_limit_bucket (
    tenant_id      UUID NOT NULL,
    bucket_key     TEXT NOT NULL,
    -- 窗口起点：floor(epoch / window_seconds) * window_seconds，按窗口对齐
    window_start   TIMESTAMPTZ NOT NULL,
    count          BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 原子 upsert 的冲突目标：无此唯一索引则并发下会产生重复桶行
    CONSTRAINT rate_limit_bucket_unique UNIQUE (tenant_id, bucket_key, window_start),
    CONSTRAINT rate_limit_bucket_count_nonneg CHECK (count >= 0),
    CONSTRAINT rate_limit_bucket_key_valid CHECK (bucket_key <> '')
);

-- 过期窗口清理入口（无自动清理调度器，见 P3-D08 §4 L-2）
CREATE INDEX IF NOT EXISTS idx_rate_limit_cleanup ON rate_limit_bucket (window_start);

-- ⚠️ 幂等：先删策略再建（DROP POLICY IF EXISTS）
DROP POLICY IF EXISTS rate_limit_bucket_tenant_isolation ON rate_limit_bucket;
CREATE POLICY rate_limit_bucket_tenant_isolation ON rate_limit_bucket
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);

ALTER TABLE rate_limit_bucket ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_bucket FORCE ROW LEVEL SECURITY;

COMMENT ON TABLE rate_limit_bucket IS
    '固定窗口限流计数（P3-D08 / D-2 预算击穿）。UNIQUE(tenant_id,bucket_key,'
    'window_start) 是原子 upsert 的基础，不可移除；index idx_rate_limit_cleanup '
    '为过期窗口清理入口，本层刻意不提供自动清理调度器。';

-- ---------------------------------------------------------------------
-- 授权给非特权角色
-- ⚠️ 必须逐条 GRANT（整段事务任一失败会连带回滚全 GRANT）
-- ⚠️ 缺 GRANT 会导致 app_agent 报 "permission denied"，
--    而限流实现把该异常当 fail-closed → 全量请求被拒（限流反而成 DoS）
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON rate_limit_bucket TO app_agent;
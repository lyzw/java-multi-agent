-- =====================================================================
-- V5 迁移：检索审计可空主体（报表中心 · 每日搜索问题数）
--
-- 背景：报表中心需要「每日搜索的问题数」，数据来源是 rag_access_audit 的
--       RETRIEVE 动作计数。但检索由系统（platform 工具调用）发起，
--       没有人类 principal——而 V1 把 principal_id 定义为 NOT NULL，
--       导致系统检索无法落审计。
--
-- 处理：把 principal_id 改为可空。INGEST/ADMIT/REJECT 等人类动作仍会传入
--       主体（NOT NULL 约束的语义由调用方保证），RETRIEVE 等系统动作传 NULL。
--       这是诚实表达：系统发起的检索本就没有人类责任人。
--
-- 另：补 (tenant_id, occurred_at) 索引，支撑「按天分组计数」的报表查询。
-- =====================================================================

SET search_path TO rag, public;

-- 检索审计：主体可空（系统发起的检索无人类 principal）
ALTER TABLE rag_access_audit ALTER COLUMN principal_id DROP NOT NULL;

-- 报表查询加速：按天聚合检索次数
CREATE INDEX IF NOT EXISTS rag_audit_tenant_occurred_idx
    ON rag_access_audit (tenant_id, occurred_at DESC);

-- =====================================================================
-- 10_publish_request_before_snapshot.sql
--
-- 为变更记录补充「变更前」配置快照。
--
-- 背景：变更记录的 before/after 对比此前靠「查询时按 target_version 反查
-- cap_agent_def 的旧版本」得出，存在两个致命缺陷：
--   1. 申请单从未写入 target_version（insert 重载未带该参数）→ 基线恒为 NULL，
--      对比永远拿不到「修改前」；
--   2. 即使有版本号，事后查询时 cap_agent_def 可能已被清理或再次变更，
--      算出来的「修改前」并不是当次变更的真实基线。
--
-- 正确做法：在**提交申请时**把当时的权威配置快照随申请单一并落库。
-- 快照由服务端计算，不采信客户端 —— 否则「修改前」这份证据就是调用方自己写的。
--
-- 列语义：
--   before_payload jsonb  —— 变更前的配置快照（按资源类型分键，如 {"agents":[...]}）
--                            NULL = 无基线（新建对象 / 非配置类操作 / 升级前的历史行）
--   payload        jsonb  —— 已有列，语义不变：变更后的配置（本次提交的目标配置）
-- =====================================================================

SET search_path TO public;

ALTER TABLE public.cap_config_publish_request
    ADD COLUMN IF NOT EXISTS before_payload jsonb;

COMMENT ON COLUMN public.cap_config_publish_request.before_payload IS
    '变更前配置快照（服务端在提交申请时计算并写入）；NULL = 无基线（新建对象 / 非配置类操作 / 升级前历史行）';

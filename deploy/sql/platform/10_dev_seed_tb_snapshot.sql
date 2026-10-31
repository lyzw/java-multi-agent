-- ============================================================================
-- 10_dev_seed_tb_snapshot.sql
-- 关联：dev 环境种子补齐（IT 前置数据，非业务变更）
--
-- 目的：给 dev 库的 t-b 租户补齐「至少一条 execution_snapshot」。
--
-- ⚠️ 为什么需要（真实故障，不是预防性改动）：
--    `FaultInjectionSupport.snapshotIdBySlug('t-b')` 断言「该租户至少有一条
--    execution_snapshot」，而 dev 库里 t-b 的快照数为 0 → 3 个 IT 直接在
--    setUp 阶段失败（BudgetReservationIT / FaultInjectionIT / RetentionErasureIT），
--    报错点离真正的被测行为（预算隔离、故障注入、保留期清理）很远。
--    ⚠️ `ensureTenantSnapshot` 帮不上忙：它只为**名为 'agent1'** 的 agent
--       建版本与快照，而 t-b 只有 cap-xt-agent-* 三条 agent，没有 agent1。
--       （t-a 能过是因为它有 240 条历史快照，**不是因为**它有 agent1。）
--
-- ⚠️ 形状与 t-a 的既有快照保持一致（run_config 留空对象），
--    避免夹具形态在两个租户间不一致导致用例行为漂移。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--    * 幂等：ON CONFLICT DO NOTHING，重复执行不产生重复行。
--    * 只写 dev 种子数据，不触碰任何业务表。
-- ============================================================================

-- 1) t-b 的 agent1（供 ensureTenantSnapshot 复用同一形态）
INSERT INTO public.agent (agent_id, tenant_id, name, description, created_at, updated_at)
SELECT 'a3f1c2d4-5e6b-4a7c-8d9e-0f1a2b3c4d5e',
       t.tenant_id, 'agent1', 'dev 种子 Agent（供跨租户夹具使用）', now(), now()
FROM public.tenant t
WHERE t.slug = 't-b'
ON CONFLICT (agent_id) DO NOTHING;

-- 2) 该 agent 的 v1 版本
INSERT INTO public.agent_version (version_id, tenant_id, agent_id, version_number,
                                 definition, published_at, created_at)
SELECT 'b4e2d3c5-6f7c-4b8d-9e0f-1a2b3c4d5e6f',
       t.tenant_id, a.agent_id, 1, '{"kind":"tutor"}'::jsonb, now(), now()
FROM public.tenant t
JOIN public.agent a ON a.tenant_id = t.tenant_id AND a.name = 'agent1'
WHERE t.slug = 't-b'
ON CONFLICT (version_id) DO NOTHING;

-- 3) 该版本的执行快照（run_snapshot_fk 的目标，跨租户用例要建 Run 必须有它）
INSERT INTO public.execution_snapshot
    (snapshot_id, tenant_id, version_id, agent_config, prompt_config,
     tool_contracts, run_config, created_at)
SELECT 'c5f3e4d6-7a8d-4c9e-0f1a-2b3c4d5e6f7a',
       t.tenant_id, v.version_id, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
       '{}'::jsonb, now()
FROM public.tenant t
JOIN public.agent_version v ON v.tenant_id = t.tenant_id
WHERE t.slug = 't-b' AND v.version_number = 1
ON CONFLICT (snapshot_id) DO NOTHING;

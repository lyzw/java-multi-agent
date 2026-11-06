-- =====================================================================
-- V1 验证用种子数据
--
-- 仅供 DDL 约束验证与集成测试使用，<b>不进入生产迁移</b>。
--
-- <b>可重复执行</b>：全部使用 ON CONFLICT DO NOTHING，重跑不会因唯一约束失败
-- 而中断（中断会导致后续语句静默跳过，产生「表在但数据不全」的假象——这类假象
-- 曾导致本项目误判「约束全部失效」）。
--
-- 租户 ID 由默认值生成；集成测试通过 slug（t-a / t-b）查询，不硬编码 UUID。
-- =====================================================================

INSERT INTO tenant (slug, name) VALUES ('t-a', '租户A'), ('t-b', '租户B')
ON CONFLICT (slug) DO NOTHING;

-- 每个租户两条 agent：
--   agent1            —— 两租户同名，用于验证隔离靠 tenant_id 而非 name
--   agent-of-*-only   —— 各自独有，用于验证「未看到对方租户数据」
INSERT INTO agent (tenant_id, name)
SELECT t.tenant_id, 'agent1' FROM tenant t
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO agent (tenant_id, name)
SELECT t.tenant_id, 'agent-of-' || lower(t.slug) || '-only' FROM tenant t
ON CONFLICT (tenant_id, name) DO NOTHING;

-- 租户 A 的完整链路：版本 → 快照 → Run → UNKNOWN 工具操作
INSERT INTO agent_version (tenant_id, agent_id, version_number, definition)
SELECT a.tenant_id, a.agent_id, 1, '{"kind":"tutor"}'::jsonb
FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug = 't-a' AND a.name = 'agent1'
ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING;

INSERT INTO execution_snapshot (tenant_id, version_id, agent_config,
                                prompt_config, tool_contracts, run_config)
SELECT v.tenant_id, v.version_id, '{}', '{}', '{}',
       '{"maxCostPerRun":"2.00","maxLlmCalls":12,"maxSteps":20,"approvalRequired":"OPTIONAL"}'
FROM agent_version v JOIN agent a ON a.agent_id = v.agent_id
JOIN tenant t ON t.tenant_id = v.tenant_id
WHERE t.slug = 't-a' AND a.name = 'agent1' AND v.version_number = 1
ON CONFLICT DO NOTHING;

INSERT INTO run (tenant_id, snapshot_id, idempotency_key, request_digest,
                 input_payload, correlation_id, state, completed_at, result_payload)
SELECT s.tenant_id, s.snapshot_id, 'k1', 'digest-1', '{}'::jsonb, 'corr-1',
       'SUCCEEDED', now(), '{"ok":true}'::jsonb
FROM execution_snapshot s JOIN tenant t ON t.tenant_id = s.tenant_id
WHERE t.slug = 't-a'
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;

-- UNKNOWN 状态的工具逻辑操作：用于验证「禁止盲目重试」（Sev-0）
INSERT INTO tool_operation (tenant_id, run_id, tool_name, risk_level, state,
                            idempotency_key, request_digest, tool_arguments, fencing_token)
SELECT r.tenant_id, r.run_id, 'record_weakness', 'WRITE_PROFILE', 'UNKNOWN',
       'op1', 'digest-op1', '{"knowledgePoint":"一元二次方程"}'::jsonb, 1
FROM run r JOIN tenant t ON t.tenant_id = r.tenant_id
WHERE t.slug = 't-a'
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;
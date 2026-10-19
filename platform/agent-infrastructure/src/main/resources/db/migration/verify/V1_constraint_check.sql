-- =====================================================================
-- V1 基线 DDL 约束验证脚本（P1-02 / ADR-004 / ADR-006）
--
-- 目的：验证 DDL 声明的安全约束在数据库层<b>真实生效</b>，而非仅语法正确。
--   语法正确 ≠ 约束有效——本脚本用于捕获后者。
--
-- 关键前提：必须以<b>非特权角色</b>执行。
--   PostgreSQL 超级用户与 BYPASSRLS 角色会绕过 RLS 策略，
--   FORCE ROW LEVEL SECURITY 也拦不住——用特权角色验证 RLS 会得到
--   虚假的「隔离生效」结论。故先建 app_agent（NOSUPERUSER NOBYPASSRLS）。
--
-- 用法：
--   psql -U agent -d <db> -v ON_ERROR_STOP=1 -f V1__baseline_schema.sql
--   psql -U agent -d <db> -v ON_ERROR_STOP=1 -f verify/V1_constraint_check.sql
--
-- 期望：每项 ✗ 检查均输出 ERROR 行。若某项无 ERROR，即表示该约束失效（严重）。
-- =====================================================================

\set ON_ERROR_STOP off

-- ---------------------------------------------------------------------
-- 前置：幂等创建非特权应用角色
-- 注意：CREATE ROLE 是<b>集群级</b>对象，DROP DATABASE 不会删除，
--       故用 IF NOT EXISTS 语义避免重复创建失败导致整段回滚。
-- ---------------------------------------------------------------------
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_agent') THEN
        CREATE ROLE app_agent NOSUPERUSER NOBYPASSRLS LOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO app_agent;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_agent;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_agent;

\echo ''
\echo '################ 阶段一：RLS 租户隔离（ADR-006）################'

\echo '--- R1 未设置 app.tenant_id 时可见行数（期望 0 —— 默认拒绝，fail-closed）'
SELECT count(*) AS r1_should_be_zero FROM agent;

\echo '--- R2 设置为租户B 后可见行数（期望 1，且不含租户A）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-b'), true);
SELECT count(*) AS r2_should_be_1 FROM agent;
SELECT count(*) AS r2_cross_tenant_leak_should_be_0
FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id WHERE t.slug <> 't-b';
ROLLBACK;

\echo '--- R3 写入非当前租户数据（期望拒绝：new row violates row-level security）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO agent (tenant_id, name)
SELECT (SELECT tenant_id FROM tenant WHERE slug = 't-b'), 'sneaky';
ROLLBACK;

\echo '--- R4 复合外键阻止跨租户引用 agent（期望拒绝：violates foreign key）'
INSERT INTO agent_version (tenant_id, agent_id, version_number, definition)
SELECT (SELECT tenant_id FROM tenant WHERE slug = 't-b'),
       (SELECT agent_id FROM agent WHERE name = 'agent1' LIMIT 1), 99, '{}'::jsonb;

\echo ''
\echo '############ 阶段二：一致性守卫（ADR-004 / P0-D03 红线）############'

\echo '--- C1 终态 Run 不可迁出（期望拒绝：终态不可迁移）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE run SET state = 'FAILED' WHERE idempotency_key = 'k1';
ROLLBACK;

\echo '--- C2 执行快照不可变（期望拒绝：禁止修改）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE execution_snapshot SET agent_config = '{"tampered":true}'::jsonb;
ROLLBACK;

\echo '--- C3 旧 fencing 世代不得提交（期望拒绝：fencing token 回退）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- 先模拟 Worker 接管提升世代，再让旧世代尝试提交
UPDATE run SET fencing_token = 5 WHERE idempotency_key = 'k1';
SAVEPOINT s1;
UPDATE run SET fencing_token = 3 WHERE fencing_token = 5;
ROLLBACK TO s1;
ROLLBACK;

\echo '--- C4 同一租户重复幂等键（期望拒绝：duplicate key）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO run (tenant_id, snapshot_id, idempotency_key, request_digest,
                 input_payload, correlation_id)
SELECT tenant_id, snapshot_id, 'k1', 'different-digest', '{}'::jsonb, 'c-dup'
FROM execution_snapshot;
ROLLBACK;

\echo ''
\echo '###### 阶段三：UNKNOWN 与副作用治理（P0-D03 红线 7 / Sev-0）######'

\echo '--- U1 ⚠️UNKNOWN 逻辑操作禁止新增 attempt（等同盲目重试 → Sev-0）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- 显式指定 operation_id，避免依赖 RLS 过滤后的结果集为空
INSERT INTO tool_operation_attempt (tenant_id, operation_id, attempt_number,
                                    state, fencing_token, result_unknown)
SELECT o.tenant_id, o.operation_id, 99, 'UNKNOWN', 1, true
FROM tool_operation o WHERE o.tool_name = 'record_weakness';
ROLLBACK;

\echo '--- U1b 正常 IN_FLIGHT 操作可新增 attempt（期望成功，防止约束过严）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'IN_FLIGHT' WHERE state = 'UNKNOWN';
INSERT INTO tool_operation_attempt (tenant_id, operation_id, attempt_number,
                                    state, fencing_token, result_unknown)
SELECT o.tenant_id, o.operation_id, 1, 'IN_FLIGHT', 1, false
FROM tool_operation o WHERE o.tool_name = 'record_weakness';
ROLLBACK;

\echo '--- U2 UNKNOWN attempt 必须标记 result_unknown（期望拒绝：check 约束）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- 先把逻辑操作移出 UNKNOWN 才能触发该约束
UPDATE tool_operation SET state = 'IN_FLIGHT' WHERE state = 'UNKNOWN';
INSERT INTO tool_operation_attempt (tenant_id, operation_id, attempt_number,
                                    state, fencing_token, result_unknown)
SELECT tenant_id, operation_id, 1, 'UNKNOWN', 1, false
FROM tool_operation;
ROLLBACK;

\echo '--- U3 RECONCILED_SUCCEEDED 必须有 reconciled_at（期望拒绝：check 约束）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'RECONCILED_SUCCEEDED' WHERE state = 'UNKNOWN';
ROLLBACK;

\echo '--- U4 MANUAL_DISPOSED 必须记录处置人与时间（期望拒绝：check 约束）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'MANUAL_DISPOSED' WHERE state = 'UNKNOWN';
ROLLBACK;

\echo ''
\echo '########## 阶段四：预算并发不超发（R-007 / FR-012）##########'

\echo '--- B1 预留+结算不得超过总额度（期望拒绝：budget_no_overspend）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO budget_account (tenant_id, total_limit, reserved_amount, settled_amount)
SELECT tenant_id, 100.00, 60.00, 50.00 FROM tenant WHERE slug = 't-a';
ROLLBACK;

\echo '--- B2 正常预留应成功（期望 INSERT 1）'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO budget_account (tenant_id, total_limit, reserved_amount, settled_amount)
SELECT tenant_id, 100.00, 40.00, 30.00 FROM tenant WHERE slug = 't-a';
ROLLBACK;

\echo ''
\echo '################################################################'
\echo '# 判定规则：上述标注「期望拒绝」的检查若<b>无 ERROR 输出</b>，        #'
\echo '#          即表示该安全约束<b>已失效</b>，属 Sev-0 级缺陷。           #'
\echo '# 标注「期望成功/为1」的检查若出现 ERROR，即表示约束过严或数据异常。#'
\echo '################################################################'
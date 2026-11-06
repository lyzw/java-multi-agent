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

\echo '--- R1 未设置 app.tenant_id 时可见行数（期望 0 —— 默认拒绝，fail-closed） [expect:count0]'
-- ⚠️ 计数用 'KEY=' || count(*) 直接输出，而不是靠 psql 表格里的数字：
--   表格数字在 stdout，ERROR 在 stderr，两者重定向到同一文件时
--   **交错顺序会被 stdout 的块缓冲打乱** → 数字会被归到别的检查项名下。
--   （也踩过 \gset + \echo 的坑：psql 的 \echo **不做变量插值**，
--     输出的是字面量 ':r1_should_be_zero'。）
SELECT 'R1_COUNT=' || count(*) AS r1_count FROM agent;

\echo '--- R2 换到租户B 后：可见本租户行、跨租户泄漏为 0（期望 scope） [expect:scope]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-b'), true);
SELECT 'R2_COUNT=' || count(*) AS r2_count FROM agent;
SELECT 'R2_LEAK=' || count(*) AS r2_leak
FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id WHERE t.slug <> 't-b';
ROLLBACK;

\echo '--- R3 写入非当前租户数据（期望拒绝：new row violates row-level security） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO agent (tenant_id, name)
SELECT (SELECT tenant_id FROM tenant WHERE slug = 't-b'), 'sneaky';
ROLLBACK;

\echo '--- R4 复合外键阻止跨租户引用 agent（期望拒绝：violates foreign key） [expect:reject]'
INSERT INTO agent_version (tenant_id, agent_id, version_number, definition)
SELECT (SELECT tenant_id FROM tenant WHERE slug = 't-b'),
       (SELECT agent_id FROM agent WHERE name = 'agent1' LIMIT 1), 99, '{}'::jsonb;

\echo ''
\echo '############ 阶段二：一致性守卫（ADR-004 / P0-D03 红线）############'

\echo '--- C1 终态 Run 不可迁出（期望拒绝：终态不可迁移） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- ⚠️⚠️ 动态挑一条**真实存在**的终态 Run：
--   此前写死 idempotency_key='k1'，而库里从来没有 k1 这条（实测 count=0）
--   → UPDATE 命中 0 行 → 守卫**根本没被触发** → 检查项永远「无 ERROR」，
--   看起来像「约束失效（Sev-0）」，实际是**夹具空转**（假不变式）。
--   C1_FIXTURE 输出候选行数：判定器据此识别「夹具空转」，
--   避免把空转误报成约束失效。
SELECT 'C1_FIXTURE=' || count(*) AS c1_fixture FROM run
 WHERE tenant_id = (SELECT tenant_id FROM tenant WHERE slug = 't-a')
   AND state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT');
UPDATE run SET state = 'FAILED'
 WHERE run_id = (SELECT run_id FROM run
                  WHERE tenant_id = (SELECT tenant_id FROM tenant WHERE slug = 't-a')
                    AND state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                  ORDER BY run_id LIMIT 1);
ROLLBACK;

\echo '--- C2 执行快照不可变（期望拒绝：禁止修改） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE execution_snapshot SET agent_config = '{"tampered":true}'::jsonb;
ROLLBACK;

\echo '--- C3 旧 fencing 世代不得提交（期望拒绝：fencing token 回退） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- 先模拟 Worker 接管提升世代，再让旧世代尝试提交
UPDATE run SET fencing_token = 5 WHERE idempotency_key = 'k1';
SAVEPOINT s1;
UPDATE run SET fencing_token = 3 WHERE fencing_token = 5;
ROLLBACK TO s1;
ROLLBACK;

\echo '--- C4 同一租户重复幂等键（期望拒绝：duplicate key） [expect:reject]'
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

\echo '--- U1 ⚠️UNKNOWN 逻辑操作禁止新增 attempt（等同盲目重试 → Sev-0） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- 显式指定 operation_id，避免依赖 RLS 过滤后的结果集为空
INSERT INTO tool_operation_attempt (tenant_id, operation_id, attempt_number,
                                    state, fencing_token, result_unknown)
SELECT o.tenant_id, o.operation_id, 99, 'UNKNOWN', 1, true
FROM tool_operation o WHERE o.tool_name = 'record_weakness';
ROLLBACK;

\echo '--- U1b 正常 IN_FLIGHT 操作可新增 attempt（期望成功，防止约束过严） [expect:ok]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'IN_FLIGHT' WHERE state = 'UNKNOWN';
-- ⚠️⚠️ 只取**一条 IN_FLIGHT 操作**，且 attempt_number 取该操作当前最大值 +1：
--   此前这里取的是全部 tool_name='record_weakness' 的操作 —— 其中混着 SUCCEEDED，
--   触发器 forbid_attempt_after_terminal 会**正确**拒绝（终态禁止新增 attempt），
--   于是整条 INSERT 失败 → 本项（防「约束过严」的正向检查）长期报 ERROR，
--   看起来像「约束过严」，实际是**夹具自身前提不成立**。
--   噪声的代价：真正过严的约束与这条噪声混在一起，无法判读。
INSERT INTO tool_operation_attempt (tenant_id, operation_id, attempt_number,
                                    state, fencing_token, result_unknown)
SELECT o.tenant_id, o.operation_id,
       (SELECT COALESCE(MAX(a.attempt_number), 0) + 1
          FROM tool_operation_attempt a
         WHERE a.tenant_id = o.tenant_id AND a.operation_id = o.operation_id),
       'IN_FLIGHT', 1, false
FROM tool_operation o
WHERE o.tool_name = 'record_weakness' AND o.state = 'IN_FLIGHT'
ORDER BY o.operation_id
LIMIT 1;
ROLLBACK;

\echo '--- U2 UNKNOWN attempt 必须标记 result_unknown（期望拒绝：check 约束） [expect:reject]'
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

\echo '--- U3 RECONCILED_SUCCEEDED 必须有 reconciled_at（期望拒绝：check 约束） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'RECONCILED_SUCCEEDED' WHERE state = 'UNKNOWN';
ROLLBACK;

\echo '--- U4 MANUAL_DISPOSED 必须记录处置人与时间（期望拒绝：check 约束） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
UPDATE tool_operation SET state = 'MANUAL_DISPOSED' WHERE state = 'UNKNOWN';
ROLLBACK;

\echo ''
\echo '########## 阶段四：预算并发不超发（R-007 / FR-012）##########'

\echo '--- B1 预留+结算不得超过总额度（期望拒绝：budget_no_overspend） [expect:reject]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
INSERT INTO budget_account (tenant_id, total_limit, reserved_amount, settled_amount)
SELECT tenant_id, 100.00, 60.00, 50.00 FROM tenant WHERE slug = 't-a';
ROLLBACK;

\echo '--- B2 正常预留应成功（期望 INSERT 1） [expect:ok]'
BEGIN;
SELECT set_config('app.tenant_id',
    (SELECT tenant_id::text FROM tenant WHERE slug = 't-a'), true);
-- ⚠️ 用**独立币种**建账户：budget_account 的唯一约束是 (tenant_id, currency)，
--   而 t-a 的 'CNY' 账户已存在（种子数据）→ 此前这条 INSERT 撞
--   budget_account_unique，报 duplicate key → 本项（防「约束过严」的正向检查）
--   长期报 ERROR。与 U1b 同形态：**夹具前提不成立**，不是约束过严。
--   （B1 撞的是 CHECK，CHECK 先于 UNIQUE 触发，所以 B1 一直是准的。）
DELETE FROM budget_account WHERE currency = 'CNY-TEST'
  AND tenant_id = (SELECT tenant_id FROM tenant WHERE slug = 't-a');
INSERT INTO budget_account (tenant_id, total_limit, reserved_amount, settled_amount, currency)
SELECT tenant_id, 100.00, 40.00, 30.00, 'CNY-TEST' FROM tenant WHERE slug = 't-a';
ROLLBACK;

\echo ''
\echo '################################################################'
\echo '# 判定规则：上述标注「期望拒绝」的检查若<b>无 ERROR 输出</b>，        #'
\echo '#          即表示该安全约束<b>已失效</b>，属 Sev-0 级缺陷。           #'
\echo '# 标注「期望成功/为1」的检查若出现 ERROR，即表示约束过严或数据异常。#'
\echo '################################################################'
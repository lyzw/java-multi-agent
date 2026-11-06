-- =====================================================================
-- V2 约束验证：学生画像与薄弱点
--
-- ⚠️ 必须以**非特权角色**执行（如 app_agent）：
--    超级用户与 BYPASSRLS 角色会绕过所有 RLS 策略，
--    FORCE ROW LEVEL SECURITY 也拦不住 → 得到虚假结论。
--
-- 判读规则：标注「期望拒绝」的检查若<b>无 ERROR 行</b>，
--            即表示该约束已失效（Sev-0）。
-- =====================================================================

\echo '--- V2-P1 合法画像写入应成功'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_a1b2c3d4e5f6g7h8', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
SELECT count(*) FROM student_profile WHERE student_id = 'stu_a1b2c3d4e5f6g7h8';
ROLLBACK;

\echo '--- V2-P2 ⚠️ 姓名作为 student_id 应被拒（DEC-6 禁止可识别信息）'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
SAVEPOINT s1;
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('张三', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
ROLLBACK TO s1;
ROLLBACK;

\echo '--- V2-P3 ⚠️ 学号格式（8 位纯数字）应被拒（不符合 stu_ 前缀）'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
SAVEPOINT s2;
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('20260101', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
ROLLBACK TO s2;
ROLLBACK;

\echo '--- V2-P4 ⚠️ 白名单外的年级应被拒'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
SAVEPOINT s3;
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_1122334455667788', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '高三', '数学');
ROLLBACK TO s3;
ROLLBACK;

\echo '--- V2-P5 ⚠️ 自由文本 errorType 应被拒（会导致知识点无限发散）'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_2233445566778899', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
SAVEPOINT s4;
INSERT INTO weakness_record (tenant_id, student_id, run_id, knowledge_point, error_type)
VALUES ((SELECT tenant_id FROM tenant WHERE slug='t-a'), 'stu_2233445566778899',
        (SELECT run_id FROM run LIMIT 1), '任意知识点', '随便写的错误类型');
ROLLBACK TO s4;
ROLLBACK;

\echo '--- V2-P6 ⚠️ 置信度越界应被拒'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_3344556677889900', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
SAVEPOINT s5;
INSERT INTO weakness_record (tenant_id, student_id, run_id, knowledge_point, error_type, confidence)
VALUES ((SELECT tenant_id FROM tenant WHERE slug='t-a'), 'stu_3344556677889900',
        (SELECT run_id FROM run LIMIT 1), '一元二次方程', '概念不清', 1.5);
ROLLBACK TO s5;
ROLLBACK;

\echo '--- V2-P7 ⚠️ 幂等：同 (租户,学生,Run,知识点) 重复写入应被拒'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_4455667788990011', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
INSERT INTO weakness_record (tenant_id, student_id, run_id, knowledge_point, error_type, confidence)
VALUES ((SELECT tenant_id FROM tenant WHERE slug='t-a'), 'stu_4455667788990011',
        (SELECT run_id FROM run LIMIT 1), '一元二次方程', '概念不清', 0.80);
SAVEPOINT s6;
INSERT INTO weakness_record (tenant_id, student_id, run_id, knowledge_point, error_type, confidence)
VALUES ((SELECT tenant_id FROM tenant WHERE slug='t-a'), 'stu_4455667788990011',
        (SELECT run_id FROM run LIMIT 1), '一元二次方程', '计算失误', 0.30);
ROLLBACK TO s6;
ROLLBACK;

\echo '--- V2-P8 ⚠️ 知识点长度越界应被拒（防超长输入注入）'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-a'), true);
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_5566778899001122', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
SAVEPOINT s7;
INSERT INTO weakness_record (tenant_id, student_id, run_id, knowledge_point, error_type)
VALUES ((SELECT tenant_id FROM tenant WHERE slug='t-a'), 'stu_5566778899001122',
        (SELECT run_id FROM run LIMIT 1), repeat('超长知识点', 30), '概念不清');
ROLLBACK TO s7;
ROLLBACK;

\echo '--- V2-P9 ⚠️ 无 tenant 上下文时跨租户写入应被拒（RLS fail-closed）'
BEGIN;
SELECT set_config('app.tenant_id', '', true);
SAVEPOINT s8;
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_6677889900112233', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
ROLLBACK TO s8;
ROLLBACK;

\echo '--- V2-P10 ⚠️ 租户B上下文写入租户A数据应被拒'
BEGIN;
SELECT set_config('app.tenant_id', (SELECT tenant_id::text FROM tenant WHERE slug='t-b'), true);
SAVEPOINT s9;
INSERT INTO student_profile (student_id, tenant_id, grade, subject)
VALUES ('stu_7788990011223344', (SELECT tenant_id FROM tenant WHERE slug='t-a'), '初二', '数学');
ROLLBACK TO s9;
ROLLBACK;
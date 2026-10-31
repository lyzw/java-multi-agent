-- =====================================================================
-- RLS 策略对齐：租户判据统一走 current_tenant_id()（P3-1 ④）
-- =====================================================================
--
-- 【⚠️ 本次修的是什么】
--   全部 50 条 RLS 策略里，有 49 条用 public.current_tenant_id() 取租户，
--   唯独 rate_limit_bucket 手写重新实现了一遍判据：
--       tenant_id = (current_setting('app.tenant_id'::text, true))::uuid
--   表面上等价，差异在于函数里有 NULLIF(..., '')：
--       current_tenant_id()  = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid
--
--   ⚠️ 两种写法在**空字符串**这一输入上行为不同：
--     · 手写版：''::uuid → ERROR: invalid input syntax for type uuid → 500
--     · 函数版：NULL        → 策略不匹配任何行 → 0 行（fail-closed）
--   也就是说「租户上下文被置成空串」这一条错误输入，
--   在其余 49 张表上是「拒绝访问」，在这一张表上是「数据库报错」。
--   这既是一条不一致的错误面（可被构造），也是一处可维护性隐患：
--   将来 current_tenant_id() 一旦演进（比如引入平台作用域），
--   这张表会**静默地**不跟着变。
--
-- 【为什么要有一条常被忽视的理由】
--   单点真相。**同一个判据散落两处，其中一处必然过期**。
--   这不是「多写一遍也能跑」的问题，是「改了一处忘了另一处，
--   而系统不会报错」的问题 —— 本项目已多次栽在这一类故障形态上。
--
-- 【幂等】先 DROP POLICY IF EXISTS 再重建，可反复执行。
-- =====================================================================

\set ON_ERROR_STOP on

DROP POLICY IF EXISTS rate_limit_bucket_tenant_isolation ON public.rate_limit_bucket;

CREATE POLICY rate_limit_bucket_tenant_isolation ON public.rate_limit_bucket
    USING ((tenant_id = public.current_tenant_id()))
    WITH CHECK ((tenant_id = public.current_tenant_id()));

\echo ''
\echo '=== 校验：仍直接照抄 current_setting 的租户隔离策略（应为空）==='
SELECT schemaname, tablename, policyname
FROM pg_policies
WHERE schemaname = 'public'
  AND qual LIKE '%current_setting(%'
  AND qual NOT LIKE '%current_tenant_id(%';

\echo ''
\echo '✅ rate_limit_bucket 的租户判据已统一为 public.current_tenant_id()。'
\echo '   ⚠️ 并同步 01_platform_ddl.sql 快照中的同条策略，否则线上与快照即刻漂移'
\echo '      （结构门禁 RlsPolicyAlignmentTest 会拦住该漂移）。'

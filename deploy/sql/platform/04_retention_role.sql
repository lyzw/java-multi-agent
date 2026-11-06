-- =====================================================================
-- 受控清理主体：凭据隔离（P2-2 · 审计「不可篡改」加固）
-- =====================================================================
--
-- 【⚠️ 本次修的是什么】
--   加固前，审计表 audit_record 的「受控删除通道」判据是**会话自己声明的一个 GUC**：
--       NULLIF(current_setting('agent.retention.actor', TRUE), '') IS NOT NULL
--   任何持有数据库凭据的会话都可以执行
--       SELECT set_config('agent.retention.actor', 'anyone', true);
--   然后 DELETE FROM audit_record —— 触发器会放行。
--   这意味着「审计不可篡改」是**条件性**的：它的强度等价于「拿到应用凭据」，
--   而不是「拿到保留期清理权限」。DDL 注释里也早已自承这一点
--   （"真正的防线在 DB 凭据分离"），但分离此前并没有真正落地。
--
--   ⚠️ 威胁模型变更后仍未覆盖的部分（必须写清楚，不许含糊）：
--     · 仍然防不住超级用户 / 表 owner（ai_agent）：owner 恒有全部特权，
--       PostgreSQL 不允许撤销 owner 自身的权限。这是数据库的基本事实，
--       不是本脚本能消除的 —— 只能靠「owner 凭据不进应用」来规避。
--     · 仍然防不住持有 ai_agent 凭据者。
--     · 已经能防住：仅持有应用凭据（app_agent）的会话，
--       含潜在的 SQL 注入上下文 —— 这是本次真正闭合的面。
--
-- 【本脚本做什么】
--   1. 建专用角色 agent_retention（NOSUPERUSER + NOBYPASSRLS），
--      只有它具有 audit_record / erasure_certificate 的 DELETE 权；
--   2. 从应用角色**撤销** audit_record / erasure_certificate 的 DELETE；
--   3. 把触发器判据从「自声明 GUC」改为「连接凭据具备 agent_retention 成员身份」
--      （GUC 保留，但降级为「留痕谁在做」，不再作为放行依据）；
--   4. 给 erasure_certificate 补同样的追加不可改触发器 —— 它此前
--      可被同一条通道删除，等于「销毁自己的证据链」无人拦截。
--
-- 【为什么必须配一条独立的保留期数据源】
--   撤销应用角色 DELETE 后，保留期清理任务若仍用应用数据源就会
--   **运行时报 permission denied**。故应用侧必须按 RetentionDataSourceConfiguration
--   配 agent.retention.datasource.*（同 platform-ops 的双通道写法）。
--   ⚠️ 这是**有意的破坏性变更**：宁可让清理任务明确失败，
--   也不要保留一条「任何会话都能删审计」的通道。
--
-- 【幂等】可反复执行。口令不传则不改既有凭据。
--
-- 【用法】
--   psql -h <host> -U <超管> -d <db> -v ON_ERROR_STOP=1 \
--        -v retention_password='<口令>' -f 04_retention_role.sql
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?retention_role}
\else
  \set retention_role 'agent_retention'
\endif

\if :{?app_role}
\else
  \set app_role 'app_agent'
\endif

\if :{?retention_password}
\else
  \set retention_password ''
\endif

SELECT set_config('my.retention_role',     :'retention_role',     false),
       set_config('my.retention_password', :'retention_password', false),
       set_config('my.app_role',           :'app_role',           false);

\echo ''
\echo '── 参数 ──'
\echo '  受控清理角色 :' :retention_role
\echo '  应用角色     :' :app_role


-- =====================================================================
-- 1. 角色：存在则重置属性，不存在则创建
-- =====================================================================
DO $$
DECLARE
    role_name text := current_setting('my.retention_role');
    pwd       text := current_setting('my.retention_password');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已创建（NOSUPERUSER + NOBYPASSRLS）', role_name;
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已存在，属性已重置', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 口令已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 retention_password，跳过口令设置';
    END IF;
END
$$;


-- =====================================================================
-- 2. 授权：只给保留期清理真正需要的表
-- =====================================================================
-- ⚠️ 本角色刻意**不是**全库 GRANT：
--    它就是「能删审计的角色」，权限面越窄，凭据泄露的爆炸半径越小。
--    （对照 app_agent 的全表 GRANT：那条凭据丢了大不了读到租户数据，
--     这条凭据丢了可以直接抹掉「谁在什么时候做了什么」。）
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'retention_role') \gexec

SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON %s TO %I', t, :'retention_role')
FROM unnest(ARRAY[
        'student_profile', 'run_event', 'audit_record',
        'consent_withdrawal', 'erasure_certificate']) AS t \gexec

-- ⚠️ P1 跨 Run 记忆（student_memory）必须**显式**列在这里：
--    记忆清理按 created_at 切，与画像清理（按 student_profile.created_at）
--    是**两条独立路径**。记忆的 created_at 可能远晚于画像
--    （新知识点是后来才出现的），故「画像删了、记忆还在」这个窗口
--    只能靠本表被本角色显式删除来关闭。
--    ⚠️ 不能依赖 student_profile 的 ON DELETE CASCADE 作为唯一路径：
--    那要求画像先被删，而记忆清理的触发条件是它自己的 created_at。
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON %s TO %I', t, :'retention_role')
FROM unnest(ARRAY['student_memory']) AS t \gexec

SELECT format('GRANT SELECT, UPDATE ON %s TO %I', t, :'retention_role')
FROM unnest(ARRAY['run', 'run_step', 'tool_operation']) AS t \gexec

SELECT format('GRANT SELECT ON tenant TO %I', :'retention_role') \gexec


-- =====================================================================
-- 3. 从应用角色撤销 DELETE —— 本次加固的核心一步
-- =====================================================================
-- ⚠️ 这一步之前，「delete + set_config」就能抹审计；这一步之后，
--    应用凭据**结构上不可能**删掉 audit_record —— 触发器与表级权限双重拦住。
-- ⚠️ ⚠️ 迁移回滚风险：若将来有人重跑 03_platform_roles.sql 的
--    「GRANT ALL TABLES」，本撤销会被覆盖且**无任何报错**。
--    故本脚本必须排在授权脚本**之后**执行，重跑时一并重跑。
SELECT format('REVOKE DELETE ON %s FROM %I', t, :'app_role')
FROM unnest(ARRAY['audit_record', 'erasure_certificate']) AS t \gexec


-- =====================================================================
-- 4. 触发器判据：凭据替代自声明
-- =====================================================================
CREATE OR REPLACE FUNCTION public.forbid_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    detail text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- ⚠️⚠️ 判据一：**连接凭据**必须具备受控清理角色成员身份。
        --    pg_has_role 看的是数据库认证出来的角色，会话无法自己给自己加成员身份
        --    （不像 set_config 那样一句话就能声明）。这是本次加固的实质。
        IF pg_has_role(current_user, 'agent_retention', 'MEMBER') THEN
            -- 判据二：仍要求声明 actor —— 但它现在只是**留痕**（谁在执行清理），
            --         不再是放行依据（缺 GUC 会走到下面的异常分支）。
            IF NULLIF(current_setting('agent.retention.actor', TRUE), '') IS NOT NULL THEN
                RETURN OLD;
            END IF;
        END IF;
    END IF;

    -- ⚠️ 报错必须说清「拦在哪一条判据上」：含糊的权限拒绝会让运维花几小时
    --    怀疑口令配错，而真因是「连接用的角色没有清理身份」。
    IF TG_OP = 'DELETE'
       AND NOT pg_has_role(current_user, 'agent_retention', 'MEMBER') THEN
        detail := ' 当前连接角色 ' || current_user
                  || ' 不具备受控清理身份（需 agent_retention 成员身份）；'
                  || '应用凭据在结构上不得删除审计行 —— 这正是凭据隔离要达到的效果。';
    ELSE
        detail := ' 受控清理通道必须同时具备清理角色身份与 actor 声明（后者用于留痕）。';
    END IF;

    RAISE EXCEPTION '审计记录为追加不可改（R-3）：禁止 % 操作 audit_record（audit_id=%）。%',
        TG_OP, OLD.audit_id, detail
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION public.forbid_audit_mutation() IS 'V6 审计防篡改，P4-D04 受控清理通道，P2-2 改为凭据隔离：DELETE 仅放行具备 agent_retention 成员身份的连接，且仍须声明 agent.retention.actor 以留痕；UPDATE 永远拒绝。此前判据只看该 GUC，而任何持有 DB 凭据的会话都可自行 set_config，属可构造绕过。';

DROP TRIGGER IF EXISTS trg_audit_record_append_only ON public.audit_record;
CREATE TRIGGER trg_audit_record_append_only
    BEFORE DELETE OR UPDATE ON public.audit_record
    FOR EACH ROW EXECUTE FUNCTION public.forbid_audit_mutation();


-- =====================================================================
-- 5. erasure_certificate 同样不可改（此前完全没有触发器）
-- =====================================================================
-- ⚠️ 删除凭证本身可被删，等于「按规定删了数据」的举证可以被事后抹掉，
--    而这恰恰是 erasure_certificate 存在的唯一理由（P4-D04 边界 L-4）。
CREATE OR REPLACE FUNCTION public.forbid_certificate_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '删除凭证为追加不可改（P4-D04 L-4）：禁止 % 操作 erasure_certificate（certificate_id=%）。'
        , TG_OP, OLD.certificate_id
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION public.forbid_certificate_mutation() IS 'P2-2：删除凭证自清理会销毁自己的证据链，故 UPDATE/DELETE 一律拒绝（无例外通道 —— 连受控清理角色也不放行）。';

DROP TRIGGER IF EXISTS trg_erasure_certificate_append_only ON public.erasure_certificate;
CREATE TRIGGER trg_erasure_certificate_append_only
    BEFORE DELETE OR UPDATE ON public.erasure_certificate
    FOR EACH ROW EXECUTE FUNCTION public.forbid_certificate_mutation();


-- =====================================================================
-- 6. 校验
-- =====================================================================
\echo ''
\echo '=== 角色属性 ==='
SELECT rolname AS 角色, rolsuper AS 超级用户, rolbypassrls AS 绕过rls, rolcanlogin AS 可登录
FROM pg_roles WHERE rolname IN (:'retention_role', :'app_role') ORDER BY rolname;

\echo ''
\echo '=== audit_record 的 DELETE 权归属（应用角色必须「无」）==='
SELECT grantee AS 角色, privilege_type AS 权限
FROM information_schema.role_table_grants
WHERE table_schema='public' AND table_name='audit_record'
  AND privilege_type='DELETE' AND grantee IN (:'retention_role', :'app_role')
ORDER BY grantee;

\echo ''
\echo '=== student_memory 的 DELETE 权归属（清理角色必须「有」）==='
-- ⚠️ 这一项缺失时的症状是「记忆从不被清理」，而清理报告里
--    memoriesDeleted 恒为 0、failedCategories 也恒为 0 —— 静默合规违约。
SELECT grantee AS 角色, privilege_type AS 权限
FROM information_schema.role_table_grants
WHERE table_schema='public' AND table_name='student_memory'
  AND privilege_type='DELETE' AND grantee IN (:'retention_role', :'app_role')
ORDER BY grantee;

\echo ''
\echo '✅ 审计凭据隔离完成。'
\echo '   ⚠️ 下一步必须做：应用侧配 agent.retention.datasource.*，'
\echo '      让保留期清理任务用 agent_retention 连接，否则清理会在运行期 permission denied。'
\echo '   ⚠️ 尚未覆盖（威胁模型显式声明）：超级用户与表 owner（ai_agent）仍可删除，'
\echo '      owner 特权在 PostgreSQL 中不可撤销 —— 依赖「owner 凭据不进应用」这一运维约束。'

-- =====================================================================
-- V8 迁移：语料侧「平台全局 / 租户私有」双作用域（第三刀）
--
-- 【本迁移把 V7 的表示法整体换成「哨兵租户 ID」，并且是有意为之】
--   V7 在 rag_dictionary 上用「可空 tenant_id + scope 判别」，
--   因为字典表是孤立表：没有外键指向它，也没有复合唯一键依赖 NULL 语义。
--   语料侧**不满足这两个前提**。以下三条都是在临时库
--   （rag_null_probe，V1→V7 实跑）以 SQL 实测得到的事实，不是推断：
--
--   ① **复合外键会被静默跳过**（PG 的 MATCH SIMPLE 默认：FK 任一列为 NULL
--      即不校验）。实测：把 rag_chunk.tenant_id 置 NULL 后插入一条
--      document_id 指向**不存在载体**的片段 —— **插入成功**。
--      语料链 source→edition→document→chunk→embedding 全部靠
--      (tenant_id, <父 id>) 复合外键维系，放开 NULL = 全局行**没有引用完整性**，
--      孤儿片段/孤儿载体可以长期存在且无人报错。
--
--   ② **等值 JOIN 直接查不到全局行**。实测：全局 chunk 与全局 edition 的
--      tenant_id 同为 NULL 时，`c.tenant_id = ed.tenant_id` 返回 **0 行**
--      （NULL = NULL 是 NULL），而 `IS NOT DISTINCT FROM` 才返回 1 行。
--      现有检索/管理 SQL 里这样的 JOIN 有十几处，逐处改写既易漏又难审。
--
--   ③ **rag_knowledge_prerequisite 的 tenant_id 在主键里**
--      （PRIMARY KEY (tenant_id, kp_id, prereq_kp_id)），
--      PostgreSQL 直接拒绝放开它：`ERROR: column "tenant_id" is in a primary key`。
--      即「全局知识点的先决关系边」在 NULL 表示法下**根本无法存在**，
--      除非重设计主键——那已超出本刀范围。
--
--   哨兵表示法（全局行的 tenant_id = rag_global_tenant_id()，列继续 NOT NULL）
--   把这三条一次性消掉：复合外键照常校验（实测孤儿全局片段被 FK 拒）、
--   等值 JOIN 照常命中、主键表照常插入、复合唯一键照常去重
--   （不再需要 NULLS NOT DISTINCT，实测 ON CONFLICT 幂等）。
--   代价只有一个：**必须防止任何会话把这个哨兵当成自己的租户身份**，
--   由 §1 的 rag_current_tenant() 断言 + 应用层边界校验共同封死（实测见反证脚本）。
--
-- 【-1 的原始诉求在这里如何落地】
--   用户最初问「全局的租户 id 为空或者 -1」。tenant_id 是 UUID，-1 无法表示；
--   但在哨兵表示法下，「-1 的 UUID 版」正是本方案：
--   一个保留的、不可能属于任何真实租户的常量。
--   选 nil UUID `00000000-0000-0000-0000-000000000000`：
--   真实租户 ID 来自 gen_random_uuid()/platform 下发，与之碰撞的概率为 0，
--   且它一眼可辨，不会被误当成正常租户。
--
-- 【为什么写策略仍然严格相等】
--   读策略放开到「本租户 ∪ 全局」，写/改/删策略保持
--   `tenant_id = rag_current_tenant()`（不含哨兵分支）。
--   于是应用角色（NOBYPASSRLS）**永远写不出全局行**——
--   全局语料的发布通道只有超级用户/运维角色：
--     · 可运行的样例见 scripts/rag-corpus-scope-counterproof.sh 的 seed 段
--       （本迁移**不提供** 05_... 发布脚本：全局语料的运营通道连同其
--        准入校验属第四刀，届时随 PlatformOpsDataSourceProvider 一并落地，
--        不要在文档里预先给一个还不存在的文件名）；
--     · Flyway 与应用共用同一个非特权数据源，故本迁移不播种任何全局行。
--
-- 【fail-closed 语义的重述】
--   原底线：「未建租户上下文 = 全库不可见」。
--   新底线：「未建租户上下文 = 只见 scope='PLATFORM' 的行」。
--   这不是放宽，而是全局共享的定义本身：可见集合是平台主动发布的那几行，
--   任何租户私有行仍然一行都看不到。启动自检与 worker 预检同步改述
--   （RagStartupSelfCheck / ReindexRunner），否则升级后必然误报启动失败。
-- =====================================================================

SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- 1. 全局哨兵 + 租户上下文函数加固
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION rag.rag_global_tenant_id() RETURNS UUID
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT '00000000-0000-0000-0000-000000000000'::UUID $$;

COMMENT ON FUNCTION rag.rag_global_tenant_id() IS
    '全局作用域的保留租户 ID（哨兵）。真实租户永远取不到这个值。'
    '全局行 = tenant_id 等于本值 且 scope = ''PLATFORM''。';

-- ⚠️ 把「会话身份 = 哨兵」当成越权来拒绝，而不是当成「无租户」。
--   原实现只做 `NULLIF(current_setting(...),'')::UUID`，
--   调用方只要 set_config('app.tenant_id', 哨兵)，
--   读写策略里的 `tenant_id = rag_current_tenant()` 就会**如常成立**，
--   于是任何租户都能直接改写全局语料——这是本方案唯一的新增攻击面，
--   必须在最底层的取数函数上封死（应用层 RagTenantContext 再挡一次，属纵深）。
CREATE OR REPLACE FUNCTION rag.rag_current_tenant() RETURNS UUID
LANGUAGE plpgsql STABLE
AS $fn$
DECLARE
    v text := current_setting('app.tenant_id', true);
BEGIN
    IF v IS NULL OR btrim(v) = '' THEN
        RETURN NULL;
    END IF;
    -- ⚠️ 先转 UUID 再比哨兵：非法值沿用 V1 的 22P02 抛错行为，
    --    不额外发明一套宽松的正则前置校验（会把合法但带大括号的写法误杀）。
    IF v::uuid = rag.rag_global_tenant_id() THEN
        RAISE EXCEPTION '禁止把全局哨兵租户 % 作为会话身份（越权企图）', v
            USING ERRCODE = '42501';
    END IF;
    RETURN v::uuid;
END
$fn$;

COMMENT ON FUNCTION rag.rag_current_tenant() IS
    '会话租户上下文。未设置 → NULL（只见全局行）。'
    '显式把全局哨兵当租户身份 → 直接报错（越权企图，fail-loud 而非静默放行）。';

-- ---------------------------------------------------------------------
-- 2. 语料链 7 张表：加 scope 判别列 + 不变式 + 读写分策略
-- ---------------------------------------------------------------------
-- ⚠️ tenant_id 列**保持 NOT NULL**（见文件头 ①②③），全局行用哨兵值表达。
-- 覆盖范围：检索与治理要读到的全局内容必须整条链齐备
--   source → edition → document → chunk → chunk_embedding
--   knowledge_point → knowledge_prerequisite
-- 不覆盖（本刀刻意不动）：
--   rag_access_audit / rag_answer_citation —— 行为留痕与租户答案的引用，
--     天然只属于发起方租户，保持严格相等；
--   rag_ingest_job —— 索引作业是租户侧动作；全局语料的索引通道属第四刀。
DO $$
DECLARE
    t        text;
    p        record;
    tables   text[] := ARRAY[
        'rag_source', 'rag_edition', 'rag_document', 'rag_chunk',
        'rag_chunk_embedding', 'rag_knowledge_point', 'rag_knowledge_prerequisite'];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        EXECUTE format(
            'ALTER TABLE %I ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT %L',
            t, 'TENANT');

        -- 语义不变式：scope 必须与 tenant_id 同向。
        -- 少了它，「tenant_id 是租户的、scope 写成 PLATFORM」的行会被读策略
        -- 当全局行放行（读策略认 tenant_id 的值），等于把某租户私有语料泄露给全平台。
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', t, t || '_scope_ck');
        EXECUTE format($f$
            ALTER TABLE %I ADD CONSTRAINT %I CHECK (
                (scope = 'PLATFORM' AND tenant_id = rag_global_tenant_id())
                OR (scope = 'TENANT' AND tenant_id <> rag_global_tenant_id())
            )$f$, t, t || '_scope_ck');

        EXECUTE format('COMMENT ON COLUMN %I.scope IS %L', t,
            '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，'
            || '全租户只读可见）；TENANT=租户私有（严格隔离）。''');

        -- 单策略 → 读写分策略。
        -- ⚠️ 按名字 drop 老的隔离策略会漏：V1 里有三张表的隔离策略名不带表名前缀
        --    （rag_embedding_tenant_isolation / rag_kp_tenant_isolation /
        --     rag_kp_prereq_tenant_isolation），实测漏掉后那些表会剩 5 条策略。
        --    漏掉的旧策略虽是「严格相等」不会造成越权，但它与新读策略是
        --    **permissive OR** 关系，等于埋着一颗「以后改哪条才生效」的雷。
        --    故按「非四件套即清除」处理，而不是猜名字。
        FOR p IN
            SELECT policyname FROM pg_policies
             WHERE schemaname = 'rag' AND tablename = t
               AND policyname <> ALL (ARRAY[t || '_read', t || '_write',
                                             t || '_update', t || '_delete'])
        LOOP
            EXECUTE format('DROP POLICY %I ON %I', p.policyname, t);
        END LOOP;

        -- 读：本租户行 ∪ 全局行。
        -- 用 IN 而不是 `= 租户 OR IS NULL`：值比较可被优化器折叠成索引可用形式，
        -- 且 JOIN 侧无需任何改动（等值 JOIN 对哨兵天然成立）。
        EXECUTE format($f$
            CREATE POLICY %I ON %I FOR SELECT
            USING (tenant_id IN (rag_current_tenant(), rag_global_tenant_id()))$f$,
            t || '_read', t);

        -- 写：只允许本租户行，且不得自封全局。
        -- ⚠️ 故意不含哨兵分支——放行即任一租户可发布全局语料（直接越权）。
        EXECUTE format($f$
            CREATE POLICY %I ON %I FOR INSERT
            WITH CHECK (tenant_id = rag_current_tenant() AND scope = 'TENANT')$f$,
            t || '_write', t);

        -- 改：可见即可改的集合必须窄于读集合——全局行只读。
        EXECUTE format($f$
            CREATE POLICY %I ON %I FOR UPDATE
            USING (tenant_id = rag_current_tenant())
            WITH CHECK (tenant_id = rag_current_tenant() AND scope = 'TENANT')$f$,
            t || '_update', t);

        -- 删：同上，全局行不可被租户删除。
        EXECUTE format($f$
            CREATE POLICY %I ON %I FOR DELETE
            USING (tenant_id = rag_current_tenant())$f$,
            t || '_delete', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 3. 引用全局片段：把 citations 的复合外键降为单列外键
-- ---------------------------------------------------------------------
-- rag_answer_citation 的 FK 原本是 (tenant_id, chunk_id) → rag_chunk(tenant_id, chunk_id)。
-- 租户答案引用一条**全局**片段时，citation 行的 tenant_id 是租户的、
-- 而被引片段的 tenant_id 是哨兵 —— 复合外键必然不匹配，
-- 「引用平台公共语料」这条最常见路径会直接写不进去（实测为 FK 报错）。
-- chunk_id 本身就是 rag_chunk 的**单列主键**，全局唯一，
-- 因此单列外键的引用完整性不降反稳（不需要靠 tenant_id 再收一遍）。
-- ⚠️ 这不是放松隔离：citation 行仍由自己的严格相等 RLS 策略隔离，
--    只是「可引用哪些片段」改由检索侧的策略负责。
ALTER TABLE rag_answer_citation
    DROP CONSTRAINT IF EXISTS rag_citation_chunk_fk;
ALTER TABLE rag_answer_citation
    ADD CONSTRAINT rag_citation_chunk_fk
    FOREIGN KEY (chunk_id) REFERENCES rag_chunk (chunk_id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- 4. 字典表向哨兵表示法收敛（与语料侧共用同一条不变式）
-- ---------------------------------------------------------------------
-- 留两套「什么叫全局」的规则，未来一定会写错谓词——故整体收敛为一套。
--
-- ⚠️ 顺序是** upgrade 路径的正确性要求**，不是风格问题：
--   V7 的 rag_dictionary_scope_ck 写的是
--       (scope='PLATFORM' AND tenant_id IS NULL) OR (scope='TENANT' AND tenant_id IS NOT NULL)
--   若先执行下面的回填 UPDATE，就是把 PLATFORM 行的 tenant_id 从 NULL 改成哨兵
--   ——**正好违反当时还生效的那条 CHECK**，迁移直接失败。
--   全新库（表里还没有全局行）跑不出来：UPDATE 影响 0 行，CHECK 无从触发。
--   也就是说这个坑只在「已用 V7 + 04 种子发布过全局字典」的库上炸，
--   恰是升级时最需要它成功的场景。故先摘掉旧不变式，再回填，最后装新的。
ALTER TABLE rag_dictionary
    DROP CONSTRAINT IF EXISTS rag_dictionary_scope_ck;

UPDATE rag_dictionary
   SET tenant_id = rag.rag_global_tenant_id()
 WHERE tenant_id IS NULL;

ALTER TABLE rag_dictionary
    ALTER COLUMN tenant_id SET NOT NULL;

-- NULLS NOT DISTINCT 的唯一索引不再需要：哨兵值非 NULL，普通唯一键照常去重。
DROP INDEX IF EXISTS rag_dictionary_scope_type_code_uidx;
ALTER TABLE rag_dictionary
    DROP CONSTRAINT IF EXISTS rag_dictionary_tenant_id_dict_type_code_key;
ALTER TABLE rag_dictionary
    ADD CONSTRAINT rag_dictionary_tenant_id_dict_type_code_key
    UNIQUE (tenant_id, dict_type, code);

ALTER TABLE rag_dictionary
    ADD CONSTRAINT rag_dictionary_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id = rag_global_tenant_id())
        OR (scope = 'TENANT' AND tenant_id <> rag_global_tenant_id())
    );

DROP POLICY IF EXISTS rag_dictionary_read   ON rag_dictionary;
DROP POLICY IF EXISTS rag_dictionary_write  ON rag_dictionary;
DROP POLICY IF EXISTS rag_dictionary_update ON rag_dictionary;
DROP POLICY IF EXISTS rag_dictionary_delete ON rag_dictionary;

CREATE POLICY rag_dictionary_read ON rag_dictionary
    FOR SELECT
    USING (tenant_id IN (rag_current_tenant(), rag_global_tenant_id()));
CREATE POLICY rag_dictionary_write ON rag_dictionary
    FOR INSERT
    WITH CHECK (tenant_id = rag_current_tenant() AND scope = 'TENANT');
CREATE POLICY rag_dictionary_update ON rag_dictionary
    FOR UPDATE
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant() AND scope = 'TENANT');
CREATE POLICY rag_dictionary_delete ON rag_dictionary
    FOR DELETE
    USING (tenant_id = rag_current_tenant());

-- ---------------------------------------------------------------------
-- 5. 自证：装配是否完整（迁移即验证，失败早爆）
-- ---------------------------------------------------------------------
-- 少建一条策略 / 名字拼错的表象是「租户能改全局语料」这类静默安全退化，
-- 离真因极远，故用系统表断言，不靠事后人工核对。
DO $$
DECLARE
    t         text;
    n_read    integer;
    n_write   integer;
    n_update  integer;
    n_delete  integer;
    n_total   integer;
    tables    text[] := ARRAY[
        'rag_source', 'rag_edition', 'rag_document', 'rag_chunk',
        'rag_chunk_embedding', 'rag_knowledge_point', 'rag_knowledge_prerequisite',
        'rag_dictionary'];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        SELECT count(*) INTO n_read   FROM pg_policies WHERE schemaname = 'rag'
            AND tablename = t AND policyname = t || '_read'   AND cmd = 'SELECT';
        SELECT count(*) INTO n_write  FROM pg_policies WHERE schemaname = 'rag'
            AND tablename = t AND policyname = t || '_write'  AND cmd = 'INSERT';
        SELECT count(*) INTO n_update FROM pg_policies WHERE schemaname = 'rag'
            AND tablename = t AND policyname = t || '_update' AND cmd = 'UPDATE';
        SELECT count(*) INTO n_delete FROM pg_policies WHERE schemaname = 'rag'
            AND tablename = t AND policyname = t || '_delete' AND cmd = 'DELETE';

        IF n_read <> 1 OR n_write <> 1 OR n_update <> 1 OR n_delete <> 1 THEN
            RAISE EXCEPTION '% 读写分策略装配不完整（read=%, insert=%, update=%, delete=%）'
                ' —— 全局/租户隔离不可信，中止迁移', t, n_read, n_write, n_update, n_delete;
        END IF;

        -- 按「每条命令恰好一条」而非「四条里有没有我期望的四条」来断言：
        -- 实测漏掉旧隔离策略时（三张表策略名不带表名前缀）逐名检查全是 1，
        -- 总数却是 5 —— 只数总数才能把这类残留抓出来。
        SELECT count(*) INTO n_total FROM pg_policies
            WHERE schemaname = 'rag' AND tablename = t;
        IF n_total <> 4 THEN
            RAISE EXCEPTION '% 上共有 % 条策略（应为 4 条读写分策略）'
                ' —— 存在未收口的历史策略，隔离语义不可推断，中止迁移', t, n_total;
        END IF;

        -- 写/删策略一旦引用哨兵，等于任一租户可发布/删除全局内容。
        -- ⚠️ 只扫 INSERT/DELETE：_read 策略含哨兵是**应该**的，
        --    把它一起扫会得到恒假的自检（曾经就是这么写错的）。
        IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'rag'
                   AND tablename = t AND cmd IN ('INSERT', 'DELETE')
                   AND (qual || coalesce(with_check, '') LIKE '%rag_global_tenant_id%')) THEN
            RAISE EXCEPTION '% 的写/删策略引用了全局哨兵，任一租户将可发布/删除全局内容'
                            ' —— 越权，中止迁移', t;
        END IF;

        -- 表 owner（含 Flyway 所用角色）不 FORCE 就能绕过 RLS。
        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = t
                       AND relrowsecurity AND relforcerowsecurity) THEN
            RAISE EXCEPTION '% 未启用 FORCE ROW LEVEL SECURITY，中止迁移', t;
        END IF;

        -- tenant_id 必须是 NOT NULL：一旦有人改回可空，哨兵约定即失效。
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'rag' AND table_name = t
                     AND column_name = 'tenant_id' AND is_nullable <> 'NO') THEN
            RAISE EXCEPTION '%.tenant_id 不是 NOT NULL，哨兵表示法失效，中止迁移', t;
        END IF;
    END LOOP;

    -- citations 的外键必须已降为单列，否则「引用全局片段」写不进去。
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'rag.rag_answer_citation'::regclass
                 AND conname = 'rag_citation_chunk_fk'
                 AND array_length(conkey, 1) <> 1) THEN
        RAISE EXCEPTION 'rag_citation_chunk_fk 仍是复合外键，'
                        '租户答案无法引用全局片段，中止迁移';
    END IF;
END
$$;

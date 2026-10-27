-- =====================================================================
-- V7 迁移：字典表支持「平台全局」与「租户私有」双作用域
--
-- 背景（全局语料验证切片 · 第一张试点表）：
--   V6 把 rag_dictionary 做成纯租户隔离——每个租户都要重复录入同一份
--   SOURCE_TYPE 下拉项。现需区分「全局共用的字典项」与「租户自有项」，
--   全局项对所有租户只读可见，租户项仍严格隔离。
--
-- ⚠️ **为什么不是「tenant_id 填 -1」**：
--   tenant_id 是 UUID，-1 根本无法表示；且全仓无 -1 哨兵先例。
--   本仓已有的对口约定是 platform 侧 cap_config_release（V15）：
--   **可空 tenant_id + scope 判别列 + 读写分策略**，此处照抄，不另立规矩。
--
-- ⚠️ **为什么必须拆读写策略，而不是只放开 NOT NULL**：
--   原单策略 USING (tenant_id = rag_current_tenant()) 是严格相等，
--   NULL = 任何值 在 SQL 里是 NULL 而非 true → 全局行对**所有租户隐形**，
--   且 INSERT 的 WITH CHECK 会直接拒绝写全局行。
--   放开列约束而不改策略 = 功能静默不生效（表现为「字典没变化」而非报错）。
--
-- ⚠️ **写策略绝不放行 tenant_id IS NULL**（沿用 V15:474-480 的结论）：
--   若放行，任一租户都能发布**全局**字典项，影响所有租户——那是直接越权。
--   全局行的写入只能由超级用户/迁移角色执行（见 02_rag_seed_global.sql）。
--   故本迁移**不播种任何全局行**：Flyway 与应用共用同一个非特权数据源
--   （见 rag-bootstrap/application.yml），在这里插 NULL 租户行必然失败。
--
-- ⚠️ **本表是有意的 fail-closed 例外**：
--   「未建租户上下文 = 全库不可见」这条底线（RagStartupSelfCheck 第 5 项探针）
--   对**语料表**继续成立且不受本迁移影响（探针只查 rag_chunk）。
--   字典表放开后，无租户上下文能看到 scope='PLATFORM' 的行——
--   这是全局共享的定义本身，不是泄漏：可见集合只有「平台主动发布的那几行」，
--   任何租户私有行仍然一行都看不到（由 _write/_update/_delete 策略与
--   RLS 严格相等共同保证，脚本 scripts/rag-dict-scope-counterproof.sh 实测）。
-- =====================================================================

SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- 1. 作用域判别列
-- ---------------------------------------------------------------------
-- DEFAULT 'TENANT' 让既有行自动落为租户级，无需回填 UPDATE。
ALTER TABLE rag_dictionary
    ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'TENANT';

COMMENT ON COLUMN rag_dictionary.scope IS
    '''作用域：PLATFORM=平台全局（tenant_id 恒为 NULL，全租户只读可见）；'
    'TENANT=租户私有（tenant_id 必为非空，严格隔离）。''';

-- ---------------------------------------------------------------------
-- 2. 语义不变式：scope 与 tenant_id 必须同向
-- ---------------------------------------------------------------------
-- 没有这条 CHECK，「scope=PLATFORM 但带了 tenant_id」这种半全局行
-- 会被读策略当成全局行放行，等于把某租户的私有项泄露给全平台。
ALTER TABLE rag_dictionary
    DROP CONSTRAINT IF EXISTS rag_dictionary_scope_ck;
ALTER TABLE rag_dictionary
    ADD CONSTRAINT rag_dictionary_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL)
        OR (scope = 'TENANT' AND tenant_id IS NOT NULL)
    );

-- ---------------------------------------------------------------------
-- 3. 放开 NOT NULL —— 全局行的载体
-- ---------------------------------------------------------------------
ALTER TABLE rag_dictionary
    ALTER COLUMN tenant_id DROP NOT NULL;

-- ---------------------------------------------------------------------
-- 4. 唯一约束改为「NULL 也互斥」
-- ---------------------------------------------------------------------
-- ⚠️ 这一步不是可选的整洁化，是**正确性**要求：
--   普通 UNIQUE / 唯一索引里 NULL 彼此不相等，
--   于是 (NULL, SOURCE_TYPE, TEXTBOOK) 可以插入任意多行；
--   更糟的是仓库的 upsert 走 ON CONFLICT (tenant_id, dict_type, code)，
--   对全局行**探测不到冲突** → 每次保存都新增一行重复的全局字典项。
--   NULLS NOT DISTINCT（PG15+，本项目 DEC-13 固定 PG17）让 NULL 参与唯一性比较，
--   全局行的 upsert 才有幂等语义。
ALTER TABLE rag_dictionary
    DROP CONSTRAINT IF EXISTS rag_dictionary_tenant_id_dict_type_code_key;

CREATE UNIQUE INDEX IF NOT EXISTS rag_dictionary_scope_type_code_uidx
    ON rag_dictionary (tenant_id, dict_type, code) NULLS NOT DISTINCT;

COMMENT ON INDEX rag_dictionary_scope_type_code_uidx IS
    '''NULLS NOT DISTINCT：使全局行（tenant_id IS NULL）同样受'
    '(类型, code) 幂等约束，否则 ON CONFLICT 探测不到冲突。''';

-- ---------------------------------------------------------------------
-- 5. 单策略 → 读写分策略
-- ---------------------------------------------------------------------
DROP POLICY IF EXISTS rag_dictionary_tenant_isolation ON rag_dictionary;

-- 读：本租户行 + 平台全局行。
CREATE POLICY rag_dictionary_read ON rag_dictionary
    FOR SELECT
    USING (tenant_id IS NULL OR tenant_id = rag_current_tenant());

-- 写：只允许写本租户行。
-- ⚠️ 故意不含 `tenant_id IS NULL` 分支——放行即越权（见文件头）。
CREATE POLICY rag_dictionary_write ON rag_dictionary
    FOR INSERT
    WITH CHECK (tenant_id = rag_current_tenant());

-- 改：只能改本租户行，且改完仍是本租户行。
-- 不放行全局行，否则租户可把平台字典项改成任意文案影响全平台。
CREATE POLICY rag_dictionary_update ON rag_dictionary
    FOR UPDATE
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

-- 删：只能删本租户行。
-- 注意 DELETE 的可见性会同时受 _read 策略影响（读得到才谈得上删），
-- 因此这里必须以本条严格相等的 USING 收口——
-- 实际交互语义由反证脚本 P6 实测，不靠推断。
CREATE POLICY rag_dictionary_delete ON rag_dictionary
    FOR DELETE
    USING (tenant_id = rag_current_tenant());

-- ---------------------------------------------------------------------
-- 6. 自证：策略装配是否符合预期（迁移即验证，失败早爆）
-- ---------------------------------------------------------------------
-- ⚠️ 策略名拼错 / 少建一条，表现是「租户能删全局行」这类静默的安全退化，
--    离真因极远。故在迁移末尾用系统表断言，不依赖事后人工核对。
DO $$
DECLARE
    n_read   integer;
    n_write  integer;
    n_update integer;
    n_delete integer;
BEGIN
    SELECT count(*) INTO n_read   FROM pg_policies WHERE schemaname = 'rag'
        AND tablename = 'rag_dictionary' AND policyname = 'rag_dictionary_read'   AND cmd = 'SELECT';
    SELECT count(*) INTO n_write  FROM pg_policies WHERE schemaname = 'rag'
        AND tablename = 'rag_dictionary' AND policyname = 'rag_dictionary_write'  AND cmd = 'INSERT';
    SELECT count(*) INTO n_update FROM pg_policies WHERE schemaname = 'rag'
        AND tablename = 'rag_dictionary' AND policyname = 'rag_dictionary_update' AND cmd = 'UPDATE';
    SELECT count(*) INTO n_delete FROM pg_policies WHERE schemaname = 'rag'
        AND tablename = 'rag_dictionary' AND policyname = 'rag_dictionary_delete' AND cmd = 'DELETE';

    IF n_read <> 1 OR n_write <> 1 OR n_update <> 1 OR n_delete <> 1 THEN
        RAISE EXCEPTION
            'rag_dictionary 读写分策略装配不完整（read=%, insert=%, update=%, delete=%）'
            ' —— 全局/租户隔离不可信，中止迁移',
            n_read, n_write, n_update, n_delete;
    END IF;

    -- 写策略若含 IS NULL 分支 = 越权放行，直接拒绝迁移。
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'rag'
               AND tablename = 'rag_dictionary' AND cmd IN ('INSERT', 'DELETE')
               AND (qual LIKE '%IS NULL%' OR with_check LIKE '%IS NULL%')) THEN
        RAISE EXCEPTION 'rag_dictionary 的写/删策略放行了 tenant_id IS NULL，'
                        '任一租户将可发布/删除全局字典项 —— 越权，中止迁移';
    END IF;

    -- FORCE 必须在：表 owner（含 Flyway 所用角色）否则可绕过 RLS。
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'rag_dictionary'
                   AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'rag_dictionary 未启用 FORCE ROW LEVEL SECURITY，中止迁移';
    END IF;
END
$$;

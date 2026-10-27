-- =====================================================================
-- rag 全局字典种子 —— 发布「平台级共享字典项」（双作用域）
-- =====================================================================
--
-- 【这是什么】
--   把 SOURCE_TYPE 的常用取值一次性发布为**平台全局项**
--   （scope='PLATFORM'、tenant_id = 全局哨兵），使所有租户开箱即有下拉，
--   不必逐个租户重复录入。租户可写入**同 code 的私有项覆盖**其标签与排序，
--   覆盖只作用于本租户，其余租户仍看到平台原文。
--
-- 【⚠️ 全局行的载体：哨兵租户，不是 NULL】
--   V7 曾用「tenant_id IS NULL + NULLS NOT DISTINCT 唯一索引」表示全局行，
--   V8 把字典表收敛到与语料表同一表示：
--       全局行 = tenant_id = rag_global_tenant_id() AND scope='PLATFORM'
--   本脚本因此**一律用函数取全局租户 ID，不写死字面量**：
--   写死一份常量，改 DB 侧函数时这里会静默种成「某个不存在的租户的私有项」，
--   表现是「下拉没变化」而不是报错。
--   （Java 侧 RagScope.GLOBAL_TENANT_ID 与函数的一致性由
--    JdbcDictionaryRepositoryIT#sentinelConstantMatchesDatabase 钉住。）
--
-- 【⚠️ 为什么单独一个脚本，而不并进迁移】
--   迁移与应用**共用同一个非特权数据源**（rag-bootstrap/application.yml 的
--   spring.datasource，角色 rag_app：NOSUPERUSER + NOBYPASSRLS）。
--   rag_dictionary_write 策略的 WITH CHECK 是
--   `tenant_id = rag_current_tenant() AND scope='TENANT'`，不放行全局桶 ——
--   所以**应用角色插不进全局行**，写进迁移必然失败。
--   这个「插不进去」正是越权闸门本身，已由
--   scripts/rag-dict-scope-counterproof.sh 的 P8-1/P8-3 实测钉住：
--   全局项只能经**平台侧通道**（超级用户/运维角色）发布。
--   故本脚本必须由迁移角色执行，用法见下。
--
-- 【⚠️ 不要用 02_rag_seed.sql 代替】
--   02 灌的是**租户私有**的演示语料（要传 tenant_id，落进某个租户命名空间）。
--   本脚本灌的是**跨租户共享**的字典项，两者作用域不同，不可混用。
--
-- 【幂等】
--   走 ON CONFLICT (tenant_id, dict_type, code)。哨兵是普通非空值，
--   天然参与唯一性比较，因此 V8 之后用的是**普通唯一约束**，
--   全局行重复执行不会长成多行。
--   已存在的全局项会被更新标签/排序（发布语义 = 以本脚本为准）。
--
-- 【用法】
--   psql -h <host> -p <port> -U <迁移角色> -d study_rag -v ON_ERROR_STOP=1 \
--        -f 04_rag_seed_global_dictionary.sql
--
--   本地（agent-pg17 容器）：
--     docker exec -i agent-pg17 psql -U agent -d rag_it -v ON_ERROR_STOP=1 \
--         -f /dev/stdin < deploy/sql/rag/04_rag_seed_global_dictionary.sql
-- =====================================================================

SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- SOURCE_TYPE：来源类型（与前端 useDictionaries 的 rag.source_type 兜底同值）
-- ⚠️ 兜底值与本表不一致时**以本表为准**；改这里请同步改前端兜底。
-- ---------------------------------------------------------------------
INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
VALUES (rag_global_tenant_id(), 'SOURCE_TYPE', 'TEXTBOOK', '教材', 10, 'PLATFORM'),
       (rag_global_tenant_id(), 'SOURCE_TYPE', 'WORKBOOK', '教辅', 20, 'PLATFORM'),
       (rag_global_tenant_id(), 'SOURCE_TYPE', 'PAPER',    '论文', 30, 'PLATFORM'),
       (rag_global_tenant_id(), 'SOURCE_TYPE', 'OTHER',    '其他', 90, 'PLATFORM')
ON CONFLICT (tenant_id, dict_type, code)
DO UPDATE SET label = EXCLUDED.label,
              sort_order = EXCLUDED.sort_order;

-- ---------------------------------------------------------------------
-- 发布后自证：全局项确实落在全局桶，且没有长出重复行
-- ---------------------------------------------------------------------
-- ⚠️ 断言全部走 `tenant_id = rag_global_tenant_id()`，**不写 IS NULL**：
--    V8 起 tenant_id 是 NOT NULL，IS NULL 谓词恒假，
--    「0 行」会被读成「没重复」——那是假绿。
DO $$
DECLARE
    n_global   integer;
    n_distinct integer;
BEGIN
    SELECT count(*)                          INTO n_global
        FROM rag_dictionary
        WHERE tenant_id = rag_global_tenant_id() AND dict_type = 'SOURCE_TYPE';
    SELECT count(DISTINCT code)              INTO n_distinct
        FROM rag_dictionary
        WHERE tenant_id = rag_global_tenant_id() AND dict_type = 'SOURCE_TYPE';

    IF n_global <> n_distinct THEN
        RAISE EXCEPTION '全局 SOURCE_TYPE 出现重复行：共 % 行但只有 % 个 code —— '
                        '唯一约束 rag_dictionary_tenant_id_dict_type_code_key 未生效',
                        n_global, n_distinct;
    END IF;

    -- V7 的 NULL 表示若在同库里残留，读策略看不见它们，
    -- 会成为永不生效却占着 code 的僵尸项；这里一并挡掉。
    IF EXISTS (SELECT 1 FROM rag_dictionary WHERE tenant_id IS NULL) THEN
        RAISE EXCEPTION 'rag_dictionary 仍有 tenant_id IS NULL 的历史行 —— '
                        'V8 收敛未完成，这些行对所有租户隐形（迁移没跑干净？）';
    END IF;

    RAISE NOTICE '全局字典发布完成：% 条 SOURCE_TYPE 全局项，无重复', n_global;
END
$$;

-- ---------------------------------------------------------------------
-- 校验：不得有任何行「带租户却标 PLATFORM」或「全局桶却标 TENANT」
-- ---------------------------------------------------------------------
-- 有 rag_dictionary_scope_ck 兜着，这里属于事后确认（防手工改库绕过约束）。
DO $$
DECLARE
    n_bad integer;
BEGIN
    SELECT count(*) INTO n_bad FROM rag_dictionary
        WHERE (scope = 'PLATFORM' AND tenant_id <> rag_global_tenant_id())
           OR (scope = 'TENANT'   AND tenant_id =  rag_global_tenant_id());
    IF n_bad > 0 THEN
        RAISE EXCEPTION '发现 % 行 scope 与 tenant_id 不同向，字典作用域已不可信', n_bad;
    END IF;
END
$$;

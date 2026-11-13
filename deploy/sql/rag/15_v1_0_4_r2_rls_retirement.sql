-- =============================================================================
-- M4-R2 管理面 RLS 退役 · 活库等价 ALTER（DRAFT — 禁止应用）
-- =============================================================================
-- ⛔⛔⛔ DRAFT / 未放行 ⛔⛔⛔
--   本文件**禁止在任何库执行**，直到满足 M2 影子信号硬前置（承接 R1 之后）：
--     rlsDependent 随 R1 退役已降至 24，本批再降至 18；且
--     AUTHZ_MISMATCH=0 连续 14 天（≥2 周真实流量沉淀） + TL/QA 签核。
--   见 docs/权限管控体系/RLS依赖清理改造计划_20261112.md §3 M2 出口门禁 / §5.2 / §6。
--   策略清单源自 authz/policy-baseline-rag-20261112.yaml（policy_count=43，
--     本批覆盖其中 20 条，见下方「退役范围」）。
--
-- 纪律（沿用 01_rag_ddl.sql 头部「存量库改动纪律」+ R1 草案）：
--   ① 本文件是 01 终态快照的**等价增量 ALTER**，切勿重放 01。
--   ② 上线顺序：先跑本文件 → 再部署移除 set_config 的应用（见下方「R2 代码改动清单」）。
--      ⚠️ 反向会 Sev-0：应用移除 set_config 后 RLS 仍在场 ⇒ rag_current_tenant() 返回
--         NULL ⇒ FORCE RLS 静默过滤 ⇒ 租户行 0 可见。必须先退役 RLS（本文件），后改代码。
--   ③ 跨桶 UPDATE/DELETE 被 RLS 挡住表现=影响 0 行不报错 ⇒ 断言核对真实状态，勿信文案。
--   ④ 无租户上下文时 rag_current_tenant() 返回 NULL 且不报错 ⇒ 任何反证/回填必须在
--      同一事务内先 set_config('app.tenant_id', …, true)。
--
-- 退役范围（R2 管理面，6 表 × 20 策略，源自基线 yaml）：
--   rag_dictionary        : rag_dictionary_write / _update / _delete / _read            (4)
--   rag_edition           : rag_edition_write / _update / _delete / _read              (4)
--   rag_knowledge_point   : rag_knowledge_point_write / _update / _delete / _read      (4)
--   rag_knowledge_prerequisite : rag_knowledge_prerequisite_write / _update / _delete / _read (4)
--   rag_kp_alias          : rag_kp_alias_tenant_isolation（单策略，含 USING+WITH CHECK）(1)
--   rag_reviewer_roster   : rag_reviewer_roster_write / _read / _update（无 _delete）   (3)
--   （rag_access_audit 不在任何 R 批 —— 白名单高敏表，RLS 保留，见 §6 边界）
--
-- 阶段策略（与 R1 一致）：先 DROP 写侧(INSERT/UPDATE/DELETE) → 观察 1 周
--   → 再 DROP 读侧(SELECT) + 单策略表的整策略 → 最后 NO FORCE + DISABLE RLS。
--   CENTRAL（RagSessionScope 2 处 set_config）本批**不动**（留待 M4-CENTRAL）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 阶段 1（写侧）：DROP INSERT/UPDATE/DELETE 策略（12 条，4 张四策略表）
--   写路径隔离已由 M3 显式谓词（{rag_tenant}/{rag_sentinel} 字面量）承担。
-- -----------------------------------------------------------------------------
BEGIN;

DROP POLICY IF EXISTS rag_dictionary_write        ON rag.rag_dictionary;
DROP POLICY IF EXISTS rag_dictionary_update       ON rag.rag_dictionary;
DROP POLICY IF EXISTS rag_dictionary_delete       ON rag.rag_dictionary;

DROP POLICY IF EXISTS rag_edition_write           ON rag.rag_edition;
DROP POLICY IF EXISTS rag_edition_update          ON rag.rag_edition;
DROP POLICY IF EXISTS rag_edition_delete          ON rag.rag_edition;

DROP POLICY IF EXISTS rag_knowledge_point_write   ON rag.rag_knowledge_point;
DROP POLICY IF EXISTS rag_knowledge_point_update  ON rag.rag_knowledge_point;
DROP POLICY IF EXISTS rag_knowledge_point_delete  ON rag.rag_knowledge_point;

DROP POLICY IF EXISTS rag_knowledge_prerequisite_write    ON rag.rag_knowledge_prerequisite;
DROP POLICY IF EXISTS rag_knowledge_prerequisite_update   ON rag.rag_knowledge_prerequisite;
DROP POLICY IF EXISTS rag_knowledge_prerequisite_delete   ON rag.rag_knowledge_prerequisite;

-- ⚠️ 阶段 1 上线后观察 1 周：写路径应仅由显式谓词隔离（与 M3 反证一致）。
--   确认无回归后，再执行阶段 2。

-- 预检 dry-run：阶段 1 先 ROLLBACK 复核影响（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 1 真实放行时启用（观察 1 周前不要 COMMIT）


-- -----------------------------------------------------------------------------
-- 阶段 2（读侧 + 单策略表）：DROP SELECT 策略（4 条） + 单策略整策略（kp_alias 1 + reviewer_roster 3）
--   + NO FORCE + DISABLE RLS（6 表）
-- -----------------------------------------------------------------------------
BEGIN;

-- 4 张四策略表：读侧（4 条）
DROP POLICY IF EXISTS rag_dictionary_read         ON rag.rag_dictionary;
DROP POLICY IF EXISTS rag_edition_read            ON rag.rag_edition;
DROP POLICY IF EXISTS rag_knowledge_point_read    ON rag.rag_knowledge_point;
DROP POLICY IF EXISTS rag_knowledge_prerequisite_read ON rag.rag_knowledge_prerequisite;

-- 单策略表（整策略一次摘掉）
DROP POLICY IF EXISTS rag_kp_alias_tenant_isolation   ON rag.rag_kp_alias;
DROP POLICY IF EXISTS rag_reviewer_roster_write       ON rag.rag_reviewer_roster;
DROP POLICY IF EXISTS rag_reviewer_roster_read        ON rag.rag_reviewer_roster;
DROP POLICY IF EXISTS rag_reviewer_roster_update      ON rag.rag_reviewer_roster;

-- 策略全清后，撤销 FORCE 并关闭 RLS（无策略时 ENABLE RLS 仍拒绝非超级用户）
ALTER TABLE rag.rag_dictionary            NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_dictionary            DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_edition               NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_edition               DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_knowledge_point       NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_knowledge_point       DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_knowledge_prerequisite NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_knowledge_prerequisite DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_kp_alias              NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_kp_alias              DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_reviewer_roster       NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_reviewer_roster       DISABLE ROW LEVEL SECURITY;

-- ⚠️ 阶段 2 上线后：同步部署移除 6 处 set_config 的应用代码（见下方 R2 代码改动清单），
--   并把 RlsDependencyProbe.EXPECTED R2 6→0（总 30→24→18）、RlsDependencyMetricTest 映射同步递减；
--   重跑 JdbcCorpusManagementRepositoryIT + JdbcDictionaryRepositoryIT + JdbcKnowledgeAliasRepositoryIT 验收。

-- 预检 dry-run：阶段 2 先 ROLLBACK 复核（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 2 真实放行时启用（CENTRAL 仍保留，rag_section 等读仍走 GUC）

-- =============================================================================
-- M4-R3 摄取/索引/巡检 RLS 退役 · 活库等价 ALTER（DRAFT — 禁止应用）
-- =============================================================================
-- ⛔⛔⛔ DRAFT / 未放行 ⛔⛔⛔
--   本文件**禁止在任何库执行**，直到满足 M2 影子信号硬前置（承接 R2 之后）：
--     rlsDependent 随 R1/R2 退役已降至 18，本批再降至 2；且
--     AUTHZ_MISMATCH=0 连续 14 天（≥2 周真实流量沉淀） + TL/QA 签核。
--   见 docs/权限管控体系/RLS依赖清理改造计划_20261112.md §3 M2 出口门禁 / §5.2 / §6。
--   策略清单源自 authz/policy-baseline-rag-20261112.yaml（policy_count=43，
--     本批覆盖其中 6 条，见下方「退役范围」）。
--
-- 纪律（沿用 01_rag_ddl.sql 头部「存量库改动纪律」+ R1/R2 草案）：
--   ① 本文件是 01 终态快照的**等价增量 ALTER**，切勿重放 01。
--   ② 上线顺序：先跑本文件 → 再部署移除 set_config 的应用（见下方「R3 代码改动清单」）。
--      ⚠️ 反向会 Sev-0：应用移除 set_config 后 RLS 仍在场 ⇒ rag_current_tenant() 返回
--         NULL ⇒ FORCE RLS 静默过滤 ⇒ 租户行 0 可见。必须先退役 RLS（本文件），后改代码。
--   ③ 跨桶 UPDATE/DELETE 被 RLS 挡住表现=影响 0 行不报错 ⇒ 断言核对真实状态，勿信文案。
--   ④ 无租户上下文时 rag_current_tenant() 返回 NULL 且不报错 ⇒ 任何反证/回填必须在
--      同一事务内先 set_config('app.tenant_id', …, true)。
--
-- 退役范围（R3 摄取/索引/巡检面，4 表 × 6 策略，源自基线 yaml）：
--   rag_answer_citation   : rag_citation_tenant_isolation（单策略，含 USING+WITH CHECK）(1)
--   rag_index_generation  : rag_index_generation_write / _read / _update（无 _delete）      (3)
--   rag_ingest_job        : rag_ingest_tenant_isolation（单策略）                         (1)
--   rag_inspection_record : rag_inspection_tenant_isolation（单策略）                     (1)
--
-- ⛔⛔⛔ 白名单高敏表 rag_access_audit **不在本批**：保留其 RLS（rag_audit_tenant_isolation +
--   FORCE RLS + trg_rag_audit_append_only 禁止 UPDATE/DELETE）。审计留痕表的隔离纵深不撤。
--
-- 阶段策略（与 R1/R2 一致）：先 DROP 写侧 → 观察 1 周 → 再 DROP 读侧 + 单策略整策略
--   → NO FORCE + DISABLE RLS。CENTRAL（RagSessionScope 2 处 set_config）本批**不动**。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 阶段 1（写侧）：DROP INSERT/UPDATE 策略（2 条，仅 rag_index_generation 有写侧拆分）
--   其余 3 张表为单策略（USING+WITH CHECK 覆盖全命令），整策略在阶段 2 一次性摘除。
-- -----------------------------------------------------------------------------
BEGIN;

DROP POLICY IF EXISTS rag_index_generation_write   ON rag.rag_index_generation;
DROP POLICY IF EXISTS rag_index_generation_update  ON rag.rag_index_generation;

-- ⚠️ 阶段 1 上线后观察 1 周：写路径应仅由显式谓词隔离（与 M3 反证一致）。
--   确认无回归后，再执行阶段 2。

-- 预检 dry-run：阶段 1 先 ROLLBACK 复核影响（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 1 真实放行时启用（观察 1 周前不要 COMMIT）


-- -----------------------------------------------------------------------------
-- 阶段 2（读侧 + 单策略表）：DROP SELECT 策略（1 条） + 单策略整策略（3 条）
--   + NO FORCE + DISABLE RLS（4 表）
-- -----------------------------------------------------------------------------
BEGIN;

-- rag_index_generation 读侧（1 条）
DROP POLICY IF EXISTS rag_index_generation_read    ON rag.rag_index_generation;

-- 单策略表（整策略一次摘掉）
DROP POLICY IF EXISTS rag_citation_tenant_isolation  ON rag.rag_answer_citation;
DROP POLICY IF EXISTS rag_ingest_tenant_isolation   ON rag.rag_ingest_job;
DROP POLICY IF EXISTS rag_inspection_tenant_isolation ON rag.rag_inspection_record;

-- 策略全清后，撤销 FORCE 并关闭 RLS（无策略时 ENABLE RLS 仍拒绝非超级用户）
ALTER TABLE rag.rag_answer_citation     NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_answer_citation     DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_index_generation    NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_index_generation    DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_ingest_job          NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_ingest_job          DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_inspection_record   NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_inspection_record   DISABLE ROW LEVEL SECURITY;

-- ⚠️ 阶段 2 上线后：同步部署移除 16 处 set_config 的应用代码（见下方 R3 代码改动清单），
--   并把 RlsDependencyProbe.EXPECTED R3 16→0（总 30→24→18→2）、RlsDependencyMetricTest 映射同步递减；
--   重跑摄取/索引/巡检面 IT 验收（JdbcIngestJobRepositoryIT / IndexGenerationBuilder* / ChunkIndexer* 等）。
-- ⚠️ rag_access_audit 的 RLS 与 set_config 兼容层**保留**（白名单高敏）；其 AuditRepository 不动。

-- 预检 dry-run：阶段 2 先 ROLLBACK 复核（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 2 真实放行时启用（CENTRAL 仍保留，rag_section 等读仍走 GUC）

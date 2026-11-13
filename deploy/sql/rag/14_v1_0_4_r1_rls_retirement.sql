-- =============================================================================
-- M4-R1 检索读面 RLS 退役 · 活库等价 ALTER（DRAFT — 禁止应用）
-- =============================================================================
-- ⛔⛔⛔ DRAFT / 未放行 ⛔⛔⛔
--   本文件**禁止在任何库执行**，直到满足 M2 影子信号硬前置：
--     rlsDependent 随本批退役递减（基线 30 → R1 后 24）且
--     AUTHZ_MISMATCH=0 连续 14 天（≥2 周真实流量沉淀） + TL/QA 签核。
--   见 docs/权限管控体系/RLS依赖清理改造计划_20261112.md §3 M2 出口门禁 / §5.2 / §6。
--
-- 纪律（沿用 01_rag_ddl.sql 头部「存量库改动纪律」）：
--   ① 本文件是 01 终态快照的**等价增量 ALTER**，切勿重放 01（CREATE TABLE 非幂等）。
--   ② 上线顺序：先跑本文件 → 再部署移除 set_config 的应用（见 §6 R1 代码改动清单）。
--      ⚠️ 反向会 Sev-0：应用移除 set_config 后 RLS 仍在场 ⇒ rag_current_tenant() 返回
--         NULL ⇒ FORCE RLS 静默过滤 ⇒ 租户行 0 可见。必须先退役 RLS（本文件），后改代码。
--   ③ 跨桶 UPDATE/DELETE 被 RLS 挡住表现=影响 0 行不报错 ⇒ 断言核对真实状态，勿信文案。
--   ④ 无租户上下文时 rag_current_tenant() 返回 NULL 且不报错 ⇒ 任何反证/回填必须在
--      同一事务内先 set_config('app.tenant_id', …, true)。
--
-- 退役范围（R1 检索读面，4 表 × 16 策略）：
--   rag_chunk, rag_chunk_embedding, rag_document, rag_source
--   （rag_section 不在 R1 —— 仍依赖 CENTRAL/RagSessionScope 的 GUC，留待 M4-CENTRAL）
--
-- 阶段策略（与计划 §5.2 一致）：先 DROP 写侧(INSERT/UPDATE/DELETE) → 观察 1 周
--   → 再 DROP 读侧(SELECT) → 最后 NO FORCE + DISABLE RLS。
--   CENTRAL（RagSessionScope 2 处 set_config）本批**不动**。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 阶段 1（写侧）：DROP INSERT/UPDATE/DELETE 策略（12 条）
--   写路径隔离已由 M3 显式谓词（{rag_tenant}/{rag_sentinel} 字面量）承担。
-- -----------------------------------------------------------------------------
BEGIN;

DROP POLICY IF EXISTS rag_chunk_write          ON rag.rag_chunk;
DROP POLICY IF EXISTS rag_chunk_update         ON rag.rag_chunk;
DROP POLICY IF EXISTS rag_chunk_delete         ON rag.rag_chunk;

DROP POLICY IF EXISTS rag_chunk_embedding_write    ON rag.rag_chunk_embedding;
DROP POLICY IF EXISTS rag_chunk_embedding_update   ON rag.rag_chunk_embedding;
DROP POLICY IF EXISTS rag_chunk_embedding_delete   ON rag.rag_chunk_embedding;

DROP POLICY IF EXISTS rag_document_write       ON rag.rag_document;
DROP POLICY IF EXISTS rag_document_update      ON rag.rag_document;
DROP POLICY IF EXISTS rag_document_delete      ON rag.rag_document;

DROP POLICY IF EXISTS rag_source_write         ON rag.rag_source;
DROP POLICY IF EXISTS rag_source_update        ON rag.rag_source;
DROP POLICY IF EXISTS rag_source_delete        ON rag.rag_source;

-- ⚠️ 阶段 1 上线后观察 1 周：写路径应仅由显式谓词隔离（与 M3 反证一致）。
--   确认无回归后，再执行阶段 2。

-- 预检 dry-run：阶段 1 先 ROLLBACK 复核影响（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 1 真实放行时启用（观察 1 周前不要 COMMIT）


-- -----------------------------------------------------------------------------
-- 阶段 2（读侧）：DROP SELECT 策略（4 条）+ NO FORCE + DISABLE RLS（4 表）
--   读路径隔离已由 M3 显式谓词（含哨兵桶 OR tenant_id = 哨兵）承担。
-- -----------------------------------------------------------------------------
BEGIN;

DROP POLICY IF EXISTS rag_chunk_read              ON rag.rag_chunk;
DROP POLICY IF EXISTS rag_chunk_embedding_read    ON rag.rag_chunk_embedding;
DROP POLICY IF EXISTS rag_document_read           ON rag.rag_document;
DROP POLICY IF EXISTS rag_source_read             ON rag.rag_source;

-- 策略全清后，撤销 FORCE 并关闭 RLS（无策略时 ENABLE RLS 仍拒绝非超级用户）
ALTER TABLE rag.rag_chunk             NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_chunk             DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_chunk_embedding   NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_chunk_embedding   DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_document          NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_document          DISABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_source            NO FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_source            DISABLE ROW LEVEL SECURITY;

-- ⚠️ 阶段 2 上线后：同步部署移除 6 处 set_config 的应用代码（见 §6 R1 代码改动清单），
--   并把 RlsDependencyProbe.EXPECTED R1 6→0、RlsDependencyMetricTest 映射同步递减；
--   重跑 RetrievalRepositoryIT + JdbcCorpusManagementRepositoryIT + RagShadowLiveIT 验收。

-- 预检 dry-run：阶段 2 先 ROLLBACK 复核（真实应用时改为 COMMIT）
ROLLBACK;
-- COMMIT;  -- ← 阶段 2 真实放行时启用（CENTRAL 仍保留，rag_section 等读仍走 GUC）

-- =============================================================================
-- 索引代际（蓝绿切换）· 活库等价 ALTER
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--
-- 【解决什么】RAG-D13 §3.5.2 / RAG-D15 §六 待办 #5：
--   「换嵌入模型」必须能**零停机**——旧代际继续服务，新代际在后台建好再切。
--   而改造前 `index_generation` 列虽然存在，却**写死为 1**、
--   向量表主键是 `(chunk_id)`，一个片段只能存一套向量 → 物理上无法双代际共存。
--
-- 【本迁移做两件事】
--   1. 新建 `rag_index_generation`：代际元数据 + 状态机（BUILDING/ACTIVE/RETIRED）；
--   2. 把 `rag_chunk_embedding` 的主键与唯一约束改为
--      `(tenant_id, chunk_id, index_generation)`，使**同一片段的多个代际向量可以并存**。
--
-- ⚠️ **为什么必须改主键而不是加张新表**：
--   检索时稠密通道要 JOIN 向量表取当前代际的向量。若拆表，
--   每次换模型都要新建一张「向量表」并改检索 SQL —— 那才是真正的灾难。
--   改主键后，检索只需多一个 `AND e.index_generation = <当前代际>` 谓词。
--
-- ⚠️ **存量数据兼容**：迁移把已有向量行的 generation 保持为 1，
--   并自动建一条 `generation=1` 的 ACTIVE 代际记录，
--   使迁移后检索立即可用（无需重建索引）。
--
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用。
--    （应用新增 `AND index_generation = ...` 谓词；若表未迁移，该谓词会查不到列。）
-- =============================================================================

BEGIN;

-- ── 1. 代际元数据表 ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rag.rag_index_generation (
    tenant_id       uuid     NOT NULL,
    generation      integer  NOT NULL,
    embed_model     text     NOT NULL,
    /** 向量维度：换模型若变维度，此列让「列维度 vs 期望维度」的不符在切换前就被发现 */
    dimension       integer  NOT NULL,
    /**
     * 代际状态：
     *  BUILDING —— 正在构建向量，**不参与检索**；
     *  ACTIVE   —— 当前服务代际，检索只读它；
     *  RETIRED  —— 已退役（可保留用于回滚，也可清理）。
     */
    status          text     NOT NULL DEFAULT 'BUILDING',
    /** 已写入向量行数（构建进度；由构建任务更新） */
    indexed_chunks  bigint   NOT NULL DEFAULT 0,
    /** 总片段数（构建开始时定格，用于算进度百分比） */
    total_chunks    bigint   NOT NULL DEFAULT 0,
    note            text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    retired_at      timestamptz,
    scope           text     NOT NULL DEFAULT 'TENANT',
    CONSTRAINT rag_index_generation_pk PRIMARY KEY (tenant_id, generation),
    CONSTRAINT rag_index_generation_status_chk
        CHECK (status = ANY (ARRAY['BUILDING'::text, 'ACTIVE'::text, 'RETIRED'::text])),
    CONSTRAINT rag_index_generation_gen_chk CHECK (generation >= 1),
    CONSTRAINT rag_index_generation_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id = rag.rag_global_tenant_id())
        OR (scope = 'TENANT' AND tenant_id <> rag.rag_global_tenant_id()))
);

CREATE INDEX IF NOT EXISTS rag_index_generation_active_idx
    ON rag.rag_index_generation (tenant_id, status);

ALTER TABLE rag.rag_index_generation ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_index_generation FORCE ROW LEVEL SECURITY;

-- ⚠️ 策略按**操作分开**，与 rag_reviewer_roster（08 迁移）同款：
--    读要能读到全局语料（tenant_id = 全局哨兵），写只能写本租户。
--    合成一条 FOR ALL 会让全局语料读不到（与既有 6 处踩坑同型）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_index_generation'
          AND policyname = 'rag_index_generation_read'
    ) THEN
        CREATE POLICY rag_index_generation_read
            ON rag.rag_index_generation
            FOR SELECT
            TO PUBLIC
            USING (tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()]));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_index_generation'
          AND policyname = 'rag_index_generation_write'
    ) THEN
        CREATE POLICY rag_index_generation_write
            ON rag.rag_index_generation
            FOR INSERT
            TO PUBLIC
            WITH CHECK (tenant_id = rag.rag_current_tenant());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_index_generation'
          AND policyname = 'rag_index_generation_update'
    ) THEN
        CREATE POLICY rag_index_generation_update
            ON rag.rag_index_generation
            FOR UPDATE
            TO PUBLIC
            USING (tenant_id = rag.rag_current_tenant())
            WITH CHECK (tenant_id = rag.rag_current_tenant());
    END IF;
END
$$;

-- ── 2. 向量表主键改造：允许同一片段多代际并存 ────────────────────────────────
--
-- ⚠️⚠️ 这是不可逆的结构变更，但**不丢数据**：
--   旧约束 (chunk_id) / (tenant_id, chunk_id) 换成
--   (tenant_id, chunk_id, index_generation)。存量行 generation 均为 1，
--   新约束下仍然唯一，故不会冲突。
--
-- ⚠️ 用 DO 块做幂等：重复执行不会因「约束已存在」而报错。

ALTER TABLE rag.rag_chunk_embedding
    DROP CONSTRAINT IF EXISTS rag_chunk_embedding_pkey;

ALTER TABLE rag.rag_chunk_embedding
    DROP CONSTRAINT IF EXISTS rag_embedding_tenant_chunk_unique;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'rag_chunk_embedding_gen_pkey') THEN
        ALTER TABLE rag.rag_chunk_embedding
            ADD CONSTRAINT rag_chunk_embedding_gen_pkey
            PRIMARY KEY (tenant_id, chunk_id, index_generation);
    END IF;
END
$$;

-- 按代际查向量的覆盖索引（稠密检索按 (tenant, generation) 收敛）
CREATE INDEX IF NOT EXISTS rag_chunk_embedding_gen_idx
    ON rag.rag_chunk_embedding (tenant_id, index_generation);

-- ── 3. 存量数据的代际记录（让迁移后立即可检索）──────────────────────────────
--
-- ⚠️ 只为「已有向量行」的租户建 ACTIVE 代际=1，
--   避免给空库凭空造一条状态不明的记录。
-- ⚠️ embed_model 取自存量行（取任意一条）：若历史上有混模型的行，
--   这里会挑中其中一种——但那本身就是脏数据，迁移不掩盖它，
--   而是让人在管理面看到「当前代际 model 与实际行不一致」从而触发重建。
INSERT INTO rag.rag_index_generation
    (tenant_id, generation, embed_model, dimension, status, indexed_chunks, total_chunks, scope, note)
SELECT e.tenant_id,
       1,
       COALESCE(MIN(e.embed_model), 'unknown'),
       -- ⚠️ 不在 SQL 里探测维度：halfvec 无通用 vector_dims()；
       --    维度由应用侧启动自检按 embeddingPort.dimension() 校验，
       --    此处留 0 表示「未知」，不影响检索（检索不读该列）。
       0,
       'ACTIVE',
       COUNT(*),
       COUNT(*),
       CASE WHEN e.tenant_id = rag.rag_global_tenant_id() THEN 'PLATFORM' ELSE 'TENANT' END,
       '迁移自动创建（存量向量代际=1）'
  FROM rag.rag_chunk_embedding e
 GROUP BY e.tenant_id
ON CONFLICT (tenant_id, generation) DO NOTHING;

COMMIT;

-- =============================================================================
-- 回滚（⚠️ 仅在确认只有一个代际时可用；多代际共存时回滚会丢向量行）
-- =============================================================================
-- BEGIN;
-- DELETE FROM rag.rag_chunk_embedding WHERE index_generation <> 1;
-- ALTER TABLE rag.rag_chunk_embedding DROP CONSTRAINT IF EXISTS rag_chunk_embedding_gen_pkey;
-- ALTER TABLE rag.rag_chunk_embedding ADD CONSTRAINT rag_chunk_embedding_pkey PRIMARY KEY (chunk_id);
-- ALTER TABLE rag.rag_chunk_embedding ADD CONSTRAINT rag_embedding_tenant_chunk_unique UNIQUE (tenant_id, chunk_id);
-- DROP TABLE IF EXISTS rag.rag_index_generation;
-- COMMIT;

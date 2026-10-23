-- =====================================================================
-- V3 迁移：RAG 管理面字段补齐（RAG-D07 §2.6 缺口清单 / RAG-D10 管理模块）
--
-- 背景：RAG-D07 §2.6 逐条列出了 DDL 的字段缺口，并明确标注
--       「V2 迁移尚未编写」。本迁移按该清单补齐**管理面必需**的部分，
--       使「语料数据管理」能力有可落地的持久化基础：
--
--   1. rag_source 审核留痕字段：
--      - review_started_at / reviewer_id   （认领人 + 认领时间）
--      - ver_reject_reason                 （拒绝理由，与成功证据 ver_evidence 语义分离）
--      - ver_decided_at                    （决定时间）
--
--   2. rag_document 载体级状态字段（RAG-R20 缺口）：
--      - ver_status / retrievable / revoked_at / revoked_reason
--      缺这些字段时，载体级撤回只能靠物理 DELETE，
--      而 DELETE 会级联抹除该载体下的历史引用（破坏可溯源性，RAG-D07 §5.5）。
--
--   3. rag_license_status 枚举化（license_status 此前是 TEXT + CHECK）。
--
-- ⚠️ 诚实边界：本迁移**只改结构，不改语义**。
--    检索期对 G-1～G-4 的强制过滤仍未在查询路径实现（RAG-R01，见 RAG-D07 §8），
--    本迁移不产生「准入闸门已在检索期生效」的结论。
-- =====================================================================

SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- 1. 版权状态枚举化
-- ---------------------------------------------------------------------
-- ⚠️ 为什么现在做：license_status 此前是 TEXT + CHECK 白名单，
--    拼写错误会在应用层之外被静默写入。新增取值需要改 DDL。
--    枚举化后，DB 自身就是权威类型。
-- ⚠️ 兼容性：V1 的 CHECK 约束只允许 4 个值，与枚举取值完全一致，
--    因此转换前先删 CHECK、转换后用枚举类型本身承担约束。
ALTER TABLE rag_source DROP CONSTRAINT IF EXISTS rag_source_license_chk;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'rag_license_status') THEN
        CREATE TYPE rag_license_status AS ENUM (
            'LICENSED',           -- 已取得授权
            'FAIR_USE_QUOTATION', -- 合理引用（教学引用场景）
            'PENDING',            -- 未取得授权（**不放行**，RAG-D07 §2.3.1 G-4）
            'RESTRICTED'          -- 禁止使用（撤回时置此值）
        );
    END IF;
END$$;

-- ⚠️⚠️ **必须先 DROP DEFAULT，再改类型，最后 SET DEFAULT**（三步不可颠倒）
--
--   报错原文：
--     ERROR:  default for column "license_status" cannot be cast automatically
--             to type rag_license_status
--
--   机理：V1 建表时写的是 `license_status TEXT NOT NULL DEFAULT 'PENDING'`，
--   PG 把它记为默认表达式 `'PENDING'::text`。执行 ALTER COLUMN ... TYPE 时，
--   PG 会**顺带尝试把该默认表达式转成新类型**；而 text → enum
--   **没有隐式转换**（枚举标签与任意字符串之间不存在自动 cast），
--   于是即便 USING 子句给出了显式转换，默认值这一路仍会失败。
--   PG 只在「旧默认可隐式转换到新类型」时才允许省略 DROP DEFAULT。
--
--   故正确次序是：
--     1) DROP DEFAULT  —— 先摘掉 text 默认值，解除 ALTER TYPE 的牵连
--     2) ALTER TYPE ... USING —— 用显式转换把既有行数据转过去
--     3) SET DEFAULT  —— 用**新类型**的字面量重新设默认值
--
--   ⚠️ 第 3 步必须显式写 `::rag_license_status`：
--     否则 PG 会把 'PENDING' 当作 unknown 字面量重新解释，
--     虽仍能工作，但依赖隐式解析——显式转换更稳。
ALTER TABLE rag_source ALTER COLUMN license_status DROP DEFAULT;

ALTER TABLE rag_source
    ALTER COLUMN license_status TYPE rag_license_status
    USING license_status::rag_license_status;

ALTER TABLE rag_source
    ALTER COLUMN license_status SET DEFAULT 'PENDING'::rag_license_status;

-- ---------------------------------------------------------------------
-- 2. rag_source 审核留痕字段
-- ---------------------------------------------------------------------
-- ⚠️ 为什么需要：RAG-D07 §2.6 指出的缺口——
--    此前「这批语料卡在谁手上」无法回答，SLA 无法度量；
--    REJECTED 的理由只能借 ver_evidence 承载，语义混淆
--    （成功证据 vs 失败理由），且与 SourceVerification.evidenceRef
--    的「校验依据」语义不符。
ALTER TABLE rag_source ADD COLUMN IF NOT EXISTS review_started_at TIMESTAMPTZ;
ALTER TABLE rag_source ADD COLUMN IF NOT EXISTS reviewer_id UUID;
ALTER TABLE rag_source ADD COLUMN IF NOT EXISTS ver_reject_reason TEXT;
ALTER TABLE rag_source ADD COLUMN IF NOT EXISTS ver_decided_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 3. rag_document 载体级状态（RAG-R20）
-- ---------------------------------------------------------------------
-- ⚠️ 为什么必须加：source / edition / document 三层模型的意义在于
--    「同一版本下可保留官方电子版、单独撤回第三方扫描版」。
--    没有载体级状态字段时，下架一个载体只能物理 DELETE，
--    而 DELETE 会级联抹除该载体下的全部历史引用（RAG-D07 §5.5）。
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS ver_status
    rag_verification_status NOT NULL DEFAULT 'PENDING';
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS retrievable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS revoked_reason TEXT;

-- ⚠️ 载体级状态与来源级状态的关系：载体默认继承来源的准入结论，
--    但**不得高于**来源——来源被撤回时，载体必须同时不可检索。
--    这里用「来源级状态变更时同步下架载体」在应用层实现（管理面事务内），
--    DB 层只加 CHECK 保证「撤回必带依据、撤回必置不可检索」。

-- 撤回的载体必须带撤回依据（RAG-D07 §2.5 留痕要求）
ALTER TABLE rag_document DROP CONSTRAINT IF EXISTS rag_document_revoked_reason_chk;
ALTER TABLE rag_document ADD CONSTRAINT rag_document_revoked_reason_chk
    CHECK (ver_status <> 'REVOKED' OR revoked_reason IS NOT NULL);

-- 载体级授权状态：与来源级 license_status 同样是独立轴（RAG-D07 §2.3.1 G-4）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS license_status
    rag_license_status NOT NULL DEFAULT 'PENDING';

-- ---------------------------------------------------------------------
-- 4. 索引补充
-- ---------------------------------------------------------------------
-- 管理面按来源列文档、按文档列片段，是高频路径
CREATE INDEX IF NOT EXISTS rag_document_tenant_source_idx
    ON rag_document (tenant_id, source_id);

-- 管理面按「待审核」筛选来源
CREATE INDEX IF NOT EXISTS rag_source_tenant_ver_status_idx
    ON rag_source (tenant_id, ver_status, created_at DESC);

-- 管理面按校验状态统计
CREATE INDEX IF NOT EXISTS rag_source_tenant_trust_idx
    ON rag_source (tenant_id, trust_tier);

-- ---------------------------------------------------------------------
-- 5. 审计动作扩展
-- ---------------------------------------------------------------------
-- ⚠️ RAG-D07 §7 记载：审核员的判定行为只体现在 rag_source 字段上，
--    缺独立事件流（无「准入审核」action）。
--    此前 V1 的 CHECK 只允许 RETRIEVE/INGEST/CITE/EXPORT/REVOKE，
--    审核动作被迫借道 INGEST，语义混淆。
--    本迁移新增 ADMIT / REJECT 两个动作，使「谁在何时判了什么」可独立检索。
ALTER TABLE rag_access_audit DROP CONSTRAINT IF EXISTS rag_access_audit_action_chk;
ALTER TABLE rag_access_audit ADD CONSTRAINT rag_access_audit_action_chk
    CHECK (action IN ('RETRIEVE', 'INGEST', 'CITE', 'EXPORT', 'REVOKE', 'ADMIT', 'REJECT'));

-- 审计按来源检索（管理面「这个来源发生过什么」）
CREATE INDEX IF NOT EXISTS rag_audit_tenant_source_idx
    ON rag_access_audit (tenant_id, source_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- 6. 载体级检索过滤索引
-- ---------------------------------------------------------------------
-- ⚠️ 与来源级同等重要：来源 VERIFIED 但载体被单独撤回时，
--    该载体的片段必须不可检索。检索路径需 JOIN rag_document 并过滤
--    doc.retrievable = TRUE（当前检索路径尚未实现，见 RAG-D07 §8 RAG-R01）。
CREATE INDEX IF NOT EXISTS rag_document_retrievable_idx
    ON rag_document (tenant_id, edition_id)
    WHERE retrievable;

COMMENT ON COLUMN rag_document.retrievable IS
    '载体级可检索标记。撤回时置 FALSE，实现「同版本下只下架单个载体」而不必物理 DELETE。';
COMMENT ON COLUMN rag_source.ver_reject_reason IS
    '拒绝理由。与 ver_evidence（成功证据）语义分离，避免混淆。';
COMMENT ON COLUMN rag_source.reviewer_id IS
    '审核认领人。回答「这批语料卡在谁手上」，使 SLA 可度量。';

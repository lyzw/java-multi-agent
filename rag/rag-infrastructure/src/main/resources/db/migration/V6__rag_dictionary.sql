-- =====================================================================
-- V6 迁移：语料管理字典表（Point 2：枚举下拉的数据源，可由前端维护）
--
-- 背景：录入 / 导入的「来源类型」等枚举此前是前端硬编码或自由文本，
--       「校验手段 / 权威等级 / 版权状态」本就是 Java 枚举。
--       新增一张租户隔离的字典表，承载可由前端维护的<b>动态</b>枚举
--       （如 SOURCE_TYPE），使「下拉选项」成为后台可读、可维护的数据，
--       而不是散落在前端常量里、改一个选项就要发版。
--
-- ⚠️ 派生枚举（TRUST_TIER / VERIFICATION_METHOD / LICENSE_STATUS）<b>不走本表</b>：
--       它们由后端按 Java 枚举在查询时派生（只读），保证逻辑与持久化单一权威。
--       本表只存「需要业务侧增删改」的动态字典项。
-- =====================================================================

SET search_path TO rag, public;

CREATE TABLE IF NOT EXISTS rag_dictionary (
    dict_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    dict_type   TEXT NOT NULL,
    code        TEXT NOT NULL,
    label       TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, dict_type, code)
);

CREATE INDEX IF NOT EXISTS rag_dictionary_tenant_type_idx
    ON rag_dictionary (tenant_id, dict_type, sort_order, code);

-- 租户隔离：与 V1 其它表同构，复用 rag_current_tenant() 与会话变量 app.tenant_id
-- （set_config('app.tenant_id', ?, true) 在事务内设置，见 V1 注释）。
ALTER TABLE rag_dictionary ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_dictionary FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rag_dictionary_tenant_isolation ON rag_dictionary;
CREATE POLICY rag_dictionary_tenant_isolation ON rag_dictionary
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

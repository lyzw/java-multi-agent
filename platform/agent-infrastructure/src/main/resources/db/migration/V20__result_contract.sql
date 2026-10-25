-- =====================================================================
-- V20：输出契约（委派结果契约 · 多级 Agent v1.1 补充）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--     §变更补充：AgentDefinition 增补 orchestration
--       · resultContractRef：用于约束委派结果的结构化契约
--   docs/多级 Agent优化/08_安全测试与验收矩阵.md
--     · MA-T16：子 Agent 输出不符合声明 Schema → 不作为成功结果交给父 Agent
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 不可妥协的设计约束：
--   1. 契约**必须固定版本**：Agent 的 resultContractRef 形如 id@version，
--      禁止 latest 漂移别名（与 childAgentRefs 同口径）。
--   2. 契约**未发布即不可用**：发布期校验会拒绝引用未发布契约的 Agent；
--      运行期取不到契约 → fail-closed（CHILD_RESULT_INVALID）。
--   3. 契约是**租户级**对象 → 必须 ENABLE + FORCE ROW LEVEL SECURITY，
--      沿用既有 current_tenant_id() 策略（V1 机制）。
--   4. schema_json 保存**契约定义本身**（JSON Schema 文本），
--      不是契约的实例 —— 它是发布期就固化的事实。
-- =====================================================================

CREATE TABLE IF NOT EXISTS cap_result_contract (
    tenant_id          UUID NOT NULL,
    contract_id        UUID NOT NULL,
    version            INTEGER NOT NULL,
    -- 契约定义（JSON Schema 文本）
    schema_json        JSONB NOT NULL,
    -- 内容指纹：用于「契约变了没有」的判定（06 §4 指纹口径）
    config_fingerprint TEXT NOT NULL,
    schema_version     INTEGER NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 发布时间；NULL = 草稿（不可被引用）
    published_at       TIMESTAMPTZ,
    CONSTRAINT cap_result_contract_pk PRIMARY KEY (tenant_id, contract_id, version),
    CONSTRAINT cap_result_contract_version_ck CHECK (version > 0),
    CONSTRAINT cap_result_contract_fingerprint_ck CHECK (
        config_fingerprint IS NOT NULL AND length(config_fingerprint) > 0)
);

COMMENT ON TABLE cap_result_contract IS
    '委派输出契约（06 变更补充 v1.1 · MA-T16）。按 (tenant_id, contract_id, version) '
    '寻址；Agent 的 resultContractRef 必须指向已发布的固定版本。';

COMMENT ON COLUMN cap_result_contract.schema_json IS
    '契约定义本身（JSON Schema），不是契约实例。发布期固化，运行期只读。';

-- ⚠️ 租户隔离：与 cap_agent_def / cap_agent_child_ref 同一口径（fail-closed）
DO $$
DECLARE
    t TEXT := 'cap_result_contract';
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = current_schema() AND tablename = t
    ) THEN
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            t || '_tenant_isolation', t);
    END IF;
END;
$$;

-- ============================================================================
-- 13_cap_agent_def_backfill.sql
-- 关联缺口：cap_agent_def 只有 1 行（测试夹具 it-agent），而 1130 个已发布 Agent
--   均无 cap_agent_def 行 → resolver.resolve() 抛 RESOURCE_UNKNOWN →
--   快照写入被 fail-soft 跳过 → execution_capability_snapshot 全库 0 行
-- 关联阶段：P0 地基交付后遗留（2026-10-31 实测发现）
--
-- 目的：从 agent_version 表回填 cap_agent_def 行，值取自 agent_version.definition JSONB。
--
-- ⚠️ 为什么不「新建行、值=臆造」而是「从 definition JSONB 提取」：
--    cap_agent_def 的 enabled_skills / roles / capability_tags / skillBundles /
--    orchestration / overrides 是闭包解析的输入，必须与 Run 实际绑定的快照一致。
--    臆造值会让「闭包指纹」与「实际能力」分叉，执行准入误判。
--
-- ⚠️ effective_closure 与 config_fingerprint 留空（NULL）：
--    这两列由 resolver.resolve() 在运行时计算（需 cap_skill_def / cap_tool_def 行），
--    而当前 SQL 无法调用 Java 闭包解析器。留空后首次解析会计算并填充。
--    这是「数据缺失」而非「设计缺失」—— 运行时能补算。
--
-- ⚠️ authz_version = 1（初始值）：
--    与 principal.authz_version 的初始值对齐。后续撤权递增由应用层触发。
--
-- ⚠️ schema_version = 1：
--    与 cap_agent_def 的 CHECK (schema_version >= 1) 对齐。
--
-- ⚠️ lifecycle_state = 'PUBLISHED'：
--    与 agent_version.published_at IS NOT NULL 的条件对齐。
--
-- ⚠️ 幂等：ON CONFLICT (tenant_id, agent_id, version) DO NOTHING，
--    重复执行不报错、不覆盖既有行（包括测试夹具 it-agent）。
-- ============================================================================

INSERT INTO cap_agent_def (
    tenant_id, agent_id, version, name, description,
    roles, enabled_skills, skill_bundles, capability_tags,
    inherits, overrides, config_fingerprint, schema_version,
    created_at, updated_at, published_at, lifecycle_state, orchestration
)
SELECT
    av.tenant_id,
    av.agent_id::uuid,
    av.version_number,
    COALESCE(av.definition->>'name', a.name)::text,
    COALESCE(av.definition->>'description', a.description)::text,
    COALESCE(av.definition->'roles', '[]'::jsonb)::jsonb,
    COALESCE(av.definition->'enabledSkills', '[]'::jsonb)::jsonb,
    COALESCE(av.definition->'skillBundles', '[]'::jsonb)::jsonb,
    COALESCE(av.definition->'capabilityTags', '[]'::jsonb)::jsonb,
    NULL,   -- inherits: 旧模型无继承，恒 NULL（CHECK 约束要求）
    COALESCE(av.definition->'overrides', '{}'::jsonb)::jsonb,
    md5(coalesce(av.definition::text, '')),  -- config_fingerprint: 从 definition JSONB 计算
    1,      -- schema_version: 与 CHECK 对齐
    av.created_at,
    av.created_at,      -- updated_at 不存在于 agent_version，用 created_at 替代
    av.published_at,
    'PUBLISHED'::cap_lifecycle_state,
    COALESCE(av.definition->'orchestration', '{}'::jsonb)::jsonb
FROM agent_version av
JOIN agent a ON a.agent_id = av.agent_id AND a.tenant_id = av.tenant_id
WHERE av.published_at IS NOT NULL
  -- ⚠️ **跳过 IT 夹具**（name 以 'it-' 开头）：
  --    共享 dev 库里有 870 个 IT 造的 it-* Agent，它们是**测试残留**，
  --    随时会被下一轮 IT 的清理语句删掉（CapITBase.cleanCapTables）。
  --    给它们建 cap_agent_def 行 = 给「即将被删的行」建依赖 ——
  --    下次 IT 跑完，这些 cap_agent_def 行就成了孤儿（agent 行没了，版本行还在）。
  --    故回填只覆盖真实业务 Agent。
  AND a.name NOT LIKE 'it-%'
  AND NOT EXISTS (
    SELECT 1 FROM cap_agent_def cd
    WHERE cd.tenant_id = av.tenant_id
      AND cd.agent_id::text = av.agent_id::text
      AND cd.version = av.version_number
  );

-- 自检：所有已发布的 agent_version 都有 cap_agent_def 行
DO $$
DECLARE
    published_count bigint;
    cap_def_count   bigint;
BEGIN
    -- ⚠️ 口径与回填一致：**排除 IT 夹具**（name 以 'it-' 开头）——
    --    它们随时会被 IT 清理语句删掉，不该计入「应被覆盖」的范围。
    SELECT count(*) INTO published_count
    FROM agent_version av
    JOIN agent a ON a.agent_id = av.agent_id AND a.tenant_id = av.tenant_id
    WHERE av.published_at IS NOT NULL
      AND a.name NOT LIKE 'it-%';
    SELECT count(*) INTO cap_def_count
    FROM cap_agent_def cd
    JOIN agent a ON a.agent_id = cd.agent_id AND a.tenant_id = cd.tenant_id
    WHERE a.name NOT LIKE 'it-%';
    IF cap_def_count < published_count THEN
        RAISE EXCEPTION 'cap_agent_def 行数 (% ) 少于已发布 agent_version 数 (% )，回填未完全生效',
            cap_def_count, published_count;
    END IF;
    RAISE NOTICE 'cap_agent_def 回填完成：已发布 agent_version（不含 IT 夹具）=%, cap_agent_def=%',
        published_count, cap_def_count;
END;
$$;

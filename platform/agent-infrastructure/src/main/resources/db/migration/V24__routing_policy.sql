-- =====================================================================
-- V24：路由策略（多级 Agent v1.1 · 第十四批 · ROUTER 名副其实）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--     §变更补充：AgentDefinition 增补 orchestration
--       · routingPolicyRef：ROUTER 模式引用固定版本路由策略
--   docs/多级 Agent优化/08_安全测试与验收矩阵.md
--     · MA-T05：路由模型输出未声明目标 Agent → 拒绝委派，不触发子执行
--     · MA-T15：编排声明固化进发布闭包
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- ⚠️ 为什么必须有这张表（此前 routingPolicyRef 是**悬空引用**）：
--   ROUTER 模式在 AgentOrchestration 构造期就**强制**要求 routingPolicyRef，
--   解析期也强制要求它是 id@version 固定版本引用 ——
--   但全仓**没有任何地方真正持有这个被引用的对象**：
--   发布期不校验它是否存在，运行期不读它，闭包指纹里只有一个字符串。
--   结果是「配置看起来声明了路由策略，实际路由行为与普通多级委派毫无差别」。
--   本表把路由策略变成**独立、可发布、可版本化、可复核**的治理对象。
--
-- 不可妥协的设计约束：
--   1. 策略**必须固定版本**：Agent 的 routingPolicyRef 形如 id@version，
--      禁止 latest 漂移别名（与 childAgentRefs / resultContractRef 同口径）。
--   2. 策略**未发布即不可用**：发布期校验拒绝引用未发布策略的 Agent；
--      运行期取不到策略 → fail-closed（ROUTING_POLICY_UNAVAILABLE）。
--   3. 策略是**租户级**对象 → 必须 ENABLE + FORCE ROW LEVEL SECURITY，
--      沿用既有 current_tenant_id() 策略（V1 机制）。
--   4. targets 存**有序目标列表**（JSONB 字符串数组）：
--      顺序在 ORDERED_FALLBACK 下降级语义下是有意义的（见 RoutingStrategy）。
--
-- ⚠️ policy_id 必须是 **TEXT**，不是 UUID：
--   对外 schema 契约（common.schema.json#/$defs/id）把 fixedVersionRef.id
--   定义为**可读字符串**（pattern ^[A-Za-z0-9][A-Za-z0-9._:-]*$），
--   治理通道也按字符串接收。V23 已经因为「输出契约 id 声明成 UUID」
--   付出过整笔发布事务回滚的代价 —— 新概念不再重复这个错误。
-- =====================================================================

CREATE TABLE IF NOT EXISTS cap_routing_policy (
    tenant_id          UUID NOT NULL,
    -- ⚠️ 可读字符串标识（与 cap_result_contract.contract_id 同构，见 V23 的教训）
    policy_id          TEXT NOT NULL,
    version            INTEGER NOT NULL,
    -- 选择策略：EXPLICIT_TARGETS（显式集合，不降级）/ ORDERED_FALLBACK（有序降级）
    strategy           TEXT NOT NULL,
    -- 允许的目标 Agent 标识（有序 · 非空 · 不重复）
    targets            JSONB NOT NULL,
    -- 内容指纹：用于「策略变了没有」的判定（06 §4 指纹口径）
    config_fingerprint TEXT NOT NULL,
    schema_version     INTEGER NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 发布时间；NULL = 草稿（不可被引用）
    published_at       TIMESTAMPTZ,
    CONSTRAINT cap_routing_policy_pk PRIMARY KEY (tenant_id, policy_id, version),
    CONSTRAINT cap_routing_policy_version_ck CHECK (version > 0),
    CONSTRAINT cap_routing_policy_strategy_ck CHECK (
        strategy IN ('EXPLICIT_TARGETS', 'ORDERED_FALLBACK')),
    CONSTRAINT cap_routing_policy_targets_ck CHECK (
        -- ⚠️ 用 CASE 而不是 "jsonb_typeof(...) = 'array' AND jsonb_array_length(...) > 0"：
        --    PostgreSQL **不保证** AND 两侧的求值顺序，targets 若不是数组，
        --    jsonb_array_length 会直接抛错（而不是让 CHECK 判为 false）——
        --    那会让「约束报错」与「数据类型报错」两种现场混在一起，排查方向被带偏。
        CASE WHEN jsonb_typeof(targets) = 'array'
             THEN jsonb_array_length(targets) > 0
             ELSE false END),
    CONSTRAINT cap_routing_policy_fingerprint_ck CHECK (
        config_fingerprint IS NOT NULL AND length(config_fingerprint) > 0)
);

COMMENT ON TABLE cap_routing_policy IS
    '路由策略（多级 Agent v1.1 第十四批 · MA-T05/MA-T15）。'
    '按 (tenant_id, policy_id, version) 寻址；Agent 的 routingPolicyRef '
    '必须指向已发布的固定版本。';

COMMENT ON COLUMN cap_routing_policy.targets IS
    '允许的目标 Agent 标识（有序）。顺序在 ORDERED_FALLBACK 下降级语义下有意义。'
    '⚠️ 不含版本号：目标的固定版本由父 Agent 的 childAgentRefs 提供 —— 版本真相只有一份。';

COMMENT ON COLUMN cap_routing_policy.strategy IS
    '选择策略。EXPLICIT_TARGETS = 候选必须命中集合，否则拒绝（不降级）；'
    'ORDERED_FALLBACK = 候选未命中时按 targets 顺序取第一个。';

-- ⚠️ 租户隔离：与 cap_agent_def / cap_agent_child_ref / cap_result_contract 同一口径
--    （fail-closed：跨租户连「存在性」都不泄漏）
DO $$
DECLARE
    t TEXT := 'cap_routing_policy';
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

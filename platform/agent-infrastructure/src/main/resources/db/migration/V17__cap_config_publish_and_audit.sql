-- =====================================================================
-- V17：统一配置与权限管控 · 原子发布与审计（W-PERM-P5 · 发布审计与生命周期）
--
-- 对标设计文档：
--   docs/Agent - Skill- Tool -MCP 统一配置与权限管控体系.md
--     §5.7 稳定原因码表（权威） / §6.1 核心表 / §6.2 引用与快照职责
--     §6.3 原子发布与并发控制（CAS / 回滚） / §6.6 生命周期 / §7.4 双维度结果
--     §9.2 PUB-1 / PUB-2 不变量
--   docs/权限管控体系/06_配置数据模型与生命周期设计.md
--     §1.7 cap_config_release 字段表 / §3 配置生命周期（状态机）/ §3.2 多对象原子发布
--   docs/权限管控体系/08_安全测试与验收矩阵.md
--     §7 性能与审计测试（四字段完整率 100% / 非笼统 DENY 占比 100%）
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 本迁移补齐 P0 留下的三处缺口：
--   1. **当前指针实体缺失** —— P0 只有「发布头 + object_versions」，
--      没有「当前发布指向哪个 release」这一实体，§6.3 第 3/5 条要求的
--      CAS 与「只读指针指向的 PUBLISHED release」无处落地。
--      → 新增 cap_config_pointer。
--   2. **生命周期状态缺失** —— §6.6 要求 DRAFT→VALIDATED→PUBLISHED→DEPRECATED，
--      而 V15 的 cap_*_def 只有 published_at（布尔式），无法表达 VALIDATED / DEPRECATED。
--      → 新增 cap_lifecycle_state 枚举 + lifecycle_state 列。
--   3. **四字段决策日志缺失** —— 08 §7 要求四字段完整率 100%，
--      V1 的 audit_record 是通用审计（action/resource/detail），
--      缺四字段专用列，也无法按 runId+invocationId 追踪每次调用。
--      → 新增 cap_decision_log。
--
-- ⚠️ **幂等**：本迁移必须可重复执行（scripts/dev-it-db.sh 会逐个重放）。
--    · CREATE TYPE 用 DO 块判存在性（PG 无 CREATE TYPE IF NOT EXISTS）；
--    · CREATE TABLE / INDEX / POLICY 用 IF NOT EXISTS 或 pg_policies 判存在性；
--    · 加列用 information_schema 判存在性。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 生命周期状态枚举（§6.6）
--
-- ⚠️ **为什么需要独立的 lifecycle_state 而不只看 published_at**：
--    published_at 是**布尔式**的「这一行已发布」，无法表达：
--      · VALIDATED —— 校验通过但尚未发布（06 §3 状态机的 validate 步骤）；
--      · DEPRECATED —— 已发布但被停用，**新 Run 不可再引用**（06 §3.4）。
--    缺了这两态，「校验失败保持可修订」与「被引用对象禁止物理删除 → 改 DEPRECATED」
--    都无法在数据层表达，只能停留在流程约定上。
--
-- ⚠️ 与 published_at 的关系：**published_at 是不可变性触发器的依据**
--    （V15 forbid_published_def_mutation：已发布行不可改写），
--    lifecycle_state 是**流程状态**。两者并存，语义不同，不可互相替代。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'cap_lifecycle_state') THEN
        CREATE TYPE cap_lifecycle_state AS ENUM (
            'DRAFT',       -- 草稿：可编辑；校验失败保持可修订（§6.6）
            'VALIDATED',   -- 已校验：待发布
            'PUBLISHED',   -- 已发布：当前发布可引用
            'DEPRECATED'   -- 已停用：新发布不可再引用（06 §3.4）
        );
    END IF;
END;
$$;

COMMENT ON TYPE cap_lifecycle_state IS
    '配置对象生命周期状态（§6.6 / 06 §3）：DRAFT→VALIDATED→PUBLISHED→DEPRECATED。'
    '与 published_at 并存：前者是流程状态，后者是不可变触发器的依据。';

-- ---------------------------------------------------------------------
-- 2. lifecycle_state 列（四张对象定义表）
--
-- ⚠️ **回填必须临时关闭已发布不可变触发器**：
--    V15 的 forbid_published_def_mutation 在 OLD.published_at IS NOT NULL 时
--    拒绝一切 UPDATE —— 而回填恰恰要 UPDATE 已发布行（改为 PUBLISHED）。
--    故本段：DISABLE 触发器 → 回填 → ENABLE 触发器。
--
-- ⚠️ **cap_agent_def 额外需要临时 NO FORCE ROW LEVEL SECURITY**：
--    该表 FORCE RLS，迁移角色若是 owner 也受策略约束；
--    未设租户上下文时 UPDATE 影响 0 行 → 回填静默不生效。
--    NO FORCE 后 owner 不再受策略约束，回填必然生效；随后恢复 FORCE。
--
-- ⚠️ **异常路径必须恢复现场**：任一步失败都要把触发器与 FORCE 状态复原，
--    否则迁移中断会留下「已发布行可被改写」或「租户表不再受 RLS 保护」的残局 ——
--    这比迁移失败本身严重得多。
--
-- ⚠️ **§2.3 的对齐触发器（实测补正）**：
--    首版实现里 lifecycle_state 的列 DEFAULT 是 'DRAFT'，
--    于是「INSERT 一行 published_at=now() 的已发布行」会得到
--    lifecycle_state='DRAFT' —— 一个**自相矛盾**的状态：
--    内容说「已发布」，状态说「草稿」。
--    后果实测可见：PUBLISHED→DEPRECATED 的停用迁移被不可变触发器拒绝
--    （old_state='DRAFT'，不满足放行条件），即「停用一个已发布对象」
--    在数据层<b>无法表达</b>（06 §3.4 被架空）。
--    对齐触发器把两者绑定：**published_at 非空 ⇒ 状态为 PUBLISHED**
--    （DEPRECATED 除外，它是显式的停用终态）。
--    这样"已发布"只有一个来源，冲突不可能出现。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    restore_failed BOOLEAN := FALSE;
BEGIN
    -- 2.1 临时关闭四张表的不可变触发器
    FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                            'cap_mcp_server_def', 'cap_agent_def'] LOOP
        EXECUTE format('ALTER TABLE %I DISABLE TRIGGER trg_%I_immutable', t, t);
    END LOOP;

    -- 2.2 加列（幂等）
    FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                            'cap_mcp_server_def', 'cap_agent_def'] LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_name = t AND column_name = 'lifecycle_state') THEN
            EXECUTE format(
                'ALTER TABLE %I ADD COLUMN lifecycle_state cap_lifecycle_state NOT NULL DEFAULT %L',
                t, 'DRAFT');
        END IF;
    END LOOP;

    -- 2.3 生命周期状态与 published_at 的对齐触发器（见 §2.3 说明）
    EXECUTE $align$
        CREATE OR REPLACE FUNCTION align_cap_lifecycle_state() RETURNS TRIGGER AS $fn$
        BEGIN
            IF NEW.published_at IS NOT NULL AND NEW.lifecycle_state <> 'DEPRECATED' THEN
                NEW.lifecycle_state := 'PUBLISHED';
            ELSIF NEW.published_at IS NULL AND NEW.lifecycle_state = 'PUBLISHED' THEN
                -- ⚠️ **显式报错而非静默降级**：声称「已发布」却没有 published_at
                --    是一个真实的配置错误。若这里悄悄改回 DRAFT，
                --    「发布」会看起来成功而对象其实仍是草稿 ——
                --    失败现场离真因极远，且未发布对象会被当成已发布使用。
                RAISE EXCEPTION 'lifecycle_state=PUBLISHED 必须同时设置 published_at（表 %）',
                    TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
            END IF;
            RETURN NEW;
        END;
        $fn$ LANGUAGE plpgsql;
    $align$;

    FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                            'cap_mcp_server_def', 'cap_agent_def'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%I_align_state ON %I', t, t);
        -- ⚠️ 触发器名必须**字典序小于** trg_<表>_immutable：
        --    PostgreSQL 对同一事件（此处 BEFORE UPDATE）的多个触发器按**名称字典序**执行。
        --    'align_state' < 'immutable' → 对齐先于不可变判定，
        --    于是「PUBLISHED→DEPRECATED 的纯元数据迁移」能通过不可变判定。
        EXECUTE format('CREATE TRIGGER trg_%I_align_state BEFORE INSERT OR UPDATE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION align_cap_lifecycle_state()', t, t);
    END LOOP;

    -- 2.4 cap_agent_def 临时解除 FORCE RLS（owner 才不被策略约束）
    EXECUTE 'ALTER TABLE cap_agent_def NO FORCE ROW LEVEL SECURITY';

    -- 2.5 回填：已发布行 → PUBLISHED，其余 → DRAFT
    --     ⚠️ DEFAULT 已是 'DRAFT'，此处只需把已发布行抬到 PUBLISHED。
    FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                            'cap_mcp_server_def', 'cap_agent_def'] LOOP
        EXECUTE format(
            'UPDATE %I SET lifecycle_state = %L WHERE published_at IS NOT NULL '
            'AND lifecycle_state <> %L',
            t, 'PUBLISHED', 'PUBLISHED');
    END LOOP;

    -- 2.6 恢复 FORCE RLS
    EXECUTE 'ALTER TABLE cap_agent_def FORCE ROW LEVEL SECURITY';

    -- 2.7 恢复不可变触发器
    FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                            'cap_mcp_server_def', 'cap_agent_def'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE TRIGGER trg_%I_immutable', t, t);
    END LOOP;
EXCEPTION WHEN OTHERS THEN
    -- ⚠️ 失败也要复原现场：先恢复触发器与 FORCE，再把原始异常抛出去。
    BEGIN
        FOREACH t IN ARRAY ARRAY['cap_tool_def', 'cap_skill_def',
                                'cap_mcp_server_def', 'cap_agent_def'] LOOP
            EXECUTE format('ALTER TABLE %I ENABLE TRIGGER trg_%I_immutable', t, t);
        END LOOP;
        EXECUTE 'ALTER TABLE cap_agent_def FORCE ROW LEVEL SECURITY';
    EXCEPTION WHEN OTHERS THEN
        restore_failed := TRUE;
    END;
    IF restore_failed THEN
        RAISE EXCEPTION 'V17 回填失败且现场恢复失败（需人工修复触发器/RLS 状态）: %', SQLERRM;
    END IF;
    RAISE;
END;
$$;

COMMENT ON COLUMN cap_tool_def.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';
COMMENT ON COLUMN cap_skill_def.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';
COMMENT ON COLUMN cap_mcp_server_def.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';
COMMENT ON COLUMN cap_agent_def.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';

-- ---------------------------------------------------------------------
-- 3. 不可变触发器扩展：允许 PUBLISHED → DEPRECATED 的**纯元数据**迁移
--
-- ⚠️ **为什么必须扩展**：
--    06 §3.4 要求「被引用对象禁止物理删除 → 改 DEPRECATED」。
--    而 V15 的触发器对已发布行**一律拒绝 UPDATE**，
--    于是「停用一个已发布的对象」在数据层**无法表达** ——
--    只能物理删除（违反引用完整性）或留着继续可被新发布引用（违反 §3.4）。
--
-- ⚠️ **放行范围被严格限定**：
--    仅当「除 lifecycle_state 与 updated_at 外的所有列完全不变」时才放行。
--    用 jsonb 差分实现，保证四张结构不同的表共用同一函数时依然精确 ——
--    直接比较业务列会导致 cap_agent_def（无 id 列）报错（V15 注释已记录该坑）。
--
-- ⚠️ **DEPRECATED 之后不可再改**：
--    旧状态为 PUBLISHED 时才允许迁移；DEPRECATED → 任何状态都被拒，
--    即「停用是单向的」。
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_published_def_mutation() RETURNS TRIGGER AS $$
DECLARE
    v_version TEXT;
    v_old_state TEXT;
    v_new_state TEXT;
BEGIN
    IF OLD.published_at IS NULL THEN
        -- 草稿行可编辑（06 §3「草稿编辑不修改已发布版本行」）
        RETURN NEW;
    END IF;

    v_old_state := to_jsonb(OLD) ->> 'lifecycle_state';
    v_new_state := to_jsonb(NEW) ->> 'lifecycle_state';

    IF v_old_state = 'PUBLISHED' AND v_new_state = 'DEPRECATED'
       AND (to_jsonb(OLD) - 'lifecycle_state' - 'updated_at')
           IS NOT DISTINCT FROM
           (to_jsonb(NEW) - 'lifecycle_state' - 'updated_at') THEN
        -- 纯停用：内容一字未改，只改生命周期状态 → 放行
        RETURN NEW;
    END IF;

    v_version := to_jsonb(OLD) ->> 'version';
    RAISE EXCEPTION '% 为已发布不可变版本行，禁止修改（version=%）',
        TG_TABLE_NAME, v_version USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION forbid_published_def_mutation() IS
    '已发布版本行内容不可改写（§6.1 / 06 §1.1）。草稿行可编辑；'
    '已发布行仅允许 PUBLISHED→DEPRECATED 的纯元数据停用（06 §3.4），其余一律拒绝。'
    'DELETE 不受此约束：删除/保留期链路必须能物理删除（V8 erasure）。';

-- ---------------------------------------------------------------------
-- 4. cap_config_pointer（当前发布指针 · CAS 目标）
--
-- ⚠️ **为什么必须有这张表**（P0 的最大缺口）：
--    §6.3 第 5 条要求「读路径：只读已提交指针指向的 PUBLISHED release」，
--    第 3 条要求「CAS：基于 expected 基线更新指针」。
--    P0 只有 cap_config_release（发布头 + object_versions），
--    没有「当前发布是哪一个」这一实体 —— 于是：
--      · 「当前版本」只能退化为「取最新已发布版本行」，
--        这在回滚后会解析到**已回滚的版本**（P1 过渡实现被迫整体 fail-closed）；
--      · CAS 无处落地，并发发布无法线性化。
--
-- ⚠️ **指针与发布头分离是刻意的**：
--    指针是**可变**的（每次发布改一行），发布头是**不可变**的历史。
--    把「当前」编码成发布头上的一列，就退化成可变标志（06 §3.2 明令禁止）。
--
-- ⚠️ **CAS 基线是 current_publish_seq**：
--    指针记下它指向的发布的序号，CAS 即「WHERE current_publish_seq = expected」。
--    基线为 NULL 表示「尚未发布过」—— 这是首次发布的合法期望值，
--    故用 IS NOT DISTINCT FROM 比较（NULL = NULL 为真）。
--
-- ⚠️ **pointer_key 的构造**：
--    'PLATFORM' 表示平台级全局指针；'TENANT:<uuid>' 表示租户级指针。
--    用单列主键而不是复合主键，是为了让 CAS 的 WHERE 子句是**单行定位**，
--    避免「按 (scope,tenant_id) 定位」在 tenant_id 为 NULL 时语义含糊
--    （SQL 里 NULL 比较恒为 UNKNOWN，这是最常见的「CAS 静默不生效」来源）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cap_config_pointer (
    -- 'PLATFORM' | 'TENANT:<uuid>'
    pointer_key         TEXT PRIMARY KEY,
    scope               TEXT NOT NULL,
    tenant_id           UUID,
    -- 指针指向的发布头。⚠️ 外键保证指针不会指向不存在的 release。
    current_release_id  BIGINT REFERENCES cap_config_release (release_id),
    -- CAS 基线（§6.3 第 3 条）。NULL = 尚未发布过。
    current_publish_seq BIGINT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cap_config_pointer_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL AND pointer_key = 'PLATFORM')
        OR (scope = 'TENANT' AND tenant_id IS NOT NULL
            AND pointer_key = 'TENANT:' || tenant_id::text)),
    CONSTRAINT cap_config_pointer_seq_ck CHECK (
        current_publish_seq IS NULL OR current_publish_seq >= 0),
    -- 指针必须「要么完全为空（尚未发布），要么完全有值（已指向某个发布）」：
    -- 半填状态会让读路径无法区分「没发布过」与「发布记录丢了」。
    CONSTRAINT cap_config_pointer_complete_ck CHECK (
        (current_release_id IS NULL AND current_publish_seq IS NULL)
        OR (current_release_id IS NOT NULL AND current_publish_seq IS NOT NULL))
);

COMMENT ON TABLE cap_config_pointer IS
    '当前发布指针（§6.3）。当前版本**只**由指针指向的 PUBLISHED release 决定，'
    '绝不以 max(seq) 选择、绝不扫描历史行。CAS 基线为 current_publish_seq。';

CREATE INDEX IF NOT EXISTS idx_cap_config_pointer_scope
    ON cap_config_pointer (scope, tenant_id);

-- ---------------------------------------------------------------------
-- 5. cap_decision_log（四字段决策日志 + ledger 维度）
--
-- ⚠️ **为什么不用 V1 的 audit_record**：
--    audit_record 的列是 (action, resource_type, resource_id, detail JSONB)，
--    是**通用行为审计**；08 §7 要求的是「runId/invocationId/authzDecision/
--    execDecision/execStatus/reasonCode/fingerprint」**四字段 + ledger**，
--    且要求「四字段完整率 100%」「非笼统 DENY 占比 100%」。
--    用通用审计表承载四字段，只能靠 detail 里的约定 key 表达 ——
--    「完整率 100%」就变成了一条无法用 SQL 断言的约定。
--    故另立专表，用 NOT NULL 从存储层强制完整率。
--
-- ⚠️ **不存参数/载荷 = 天然脱敏**（NFR-06 / 08 §7 第 2 条）：
--    本表**只有** id、决策字段与上下文 id，**没有**任何承载业务参数的列。
--    「日志不含明文凭据与不必要的敏感参数」因此不需要额外的过滤逻辑 ——
--    没有可泄漏的字段。凭据材料更是在任何阶段都不入库（06 §5）。
--
-- ⚠️ **decision_kind 区分事件种类**：
--    'INVOCATION' = 工具调用的四字段决策（08 §7 的统计口径）；
--    'PUBLISH' / 'ROLLBACK' / 'LIFECYCLE' = 配置发布链路的审计旁路。
--    分开是为了让「四字段完整率」这一指标有**明确的统计边界**。
--
-- ⚠️ **不设 UNIQUE(tenant_id, invocation_id)**：
--    同一 invocation 可能先被准入拒绝、后被重试成功（§7.4 对账语义），
--    两条决策都是真实事实，强行去重会丢掉中间态。
--    「每次调用可经 runId+invocationId 追踪」由索引保证。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cap_decision_log (
    decision_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID,
    -- ⚠️ INVOCATION 必填；发布类事件为 NULL。
    run_id            UUID,
    invocation_id     TEXT NOT NULL,
    -- 四字段（§5.1 / 08 §7）
    authz_decision    TEXT NOT NULL,
    exec_decision     TEXT NOT NULL,
    exec_status       TEXT NOT NULL,
    -- ledger 维度（§7.4 双维度结果）
    ledger_status     TEXT NOT NULL,
    -- 原因码（§5.7 权威表）
    reason_code       TEXT NOT NULL,
    -- 上下文 id
    authz_version     BIGINT,
    closure_fingerprint TEXT,
    config_publish_seq BIGINT,
    release_id        BIGINT,
    principal_id      TEXT,
    tool_id           TEXT,
    -- 事件种类
    decision_kind     TEXT NOT NULL DEFAULT 'INVOCATION',
    -- 阶段（§5.7 表格第二列语义：A / B / 准入 / 执行后 / 发布）
    phase             TEXT NOT NULL DEFAULT 'A',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ⚠️ 取值域用 CHECK 固定：枚举值漂移会让「非笼统 DENY 占比」无法统计。
    CONSTRAINT cap_decision_log_authz_ck CHECK (authz_decision IN ('ALLOW', 'DENY')),
    CONSTRAINT cap_decision_log_exec_ck CHECK (
        exec_decision IN ('ALLOW', 'DENY', 'NOT_EVALUATED')),
    CONSTRAINT cap_decision_log_status_ck CHECK (
        exec_status IN ('NOT_STARTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT cap_decision_log_ledger_ck CHECK (
        ledger_status IN ('PENDING', 'RECORDED', 'RETRYING', 'NEEDS_MANUAL')),
    CONSTRAINT cap_decision_log_kind_ck CHECK (
        decision_kind IN ('INVOCATION', 'PUBLISH', 'ROLLBACK', 'LIFECYCLE')),
    -- ⚠️ INVOCATION 事件必须带 runId：08 §7 要求「每次调用可经 runId+invocationId 追踪」。
    CONSTRAINT cap_decision_log_invocation_ck CHECK (
        decision_kind <> 'INVOCATION' OR run_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_cap_decision_log_invocation
    ON cap_decision_log (tenant_id, run_id, invocation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cap_decision_log_release
    ON cap_decision_log (tenant_id, release_id, created_at);

COMMENT ON TABLE cap_decision_log IS
    '四字段决策日志 + ledger 维度（§5.1 / §7.4 / 08 §7）。'
    '四字段 NOT NULL 从存储层强制「完整率 100%」；本表不存任何业务参数，天然脱敏（NFR-06）。';

-- ---------------------------------------------------------------------
-- 6. RLS 租户隔离（ADR-006 / §6.1）
--
-- ⚠️ **cap_config_pointer 与 cap_config_release 采用同一套分读写策略**：
--    · SELECT：PLATFORM 行只读共享（平台对象版本需共享）；TENANT 行仅本租户可见。
--    · INSERT/UPDATE：**只允许写本租户的 TENANT 行**。
--      若写策略放行 tenant_id IS NULL，则任一租户都能改写**全局**当前指针 ——
--      那等于让任一租户单方面替换全平台生效的配置，是直接的越权。
--      故 PLATFORM 指针的写入只能由平台运维角色（BYPASSRLS）执行，
--      与 V15 对 cap_config_release 的处置**完全一致**（不是新引入的限制）。
--
-- ⚠️ **cap_decision_log 采用同样的分读写策略**：
--    发布类事件（PLATFORM 作用域）需要以平台运维身份写入；
--    调用类事件（租户作用域）由租户自身写入。
--    读策略放行 PLATFORM（tenant_id IS NULL）行，使租户可以审计平台级发布事件。
--
-- ⚠️ **本段必须幂等**：PostgreSQL 的 CREATE POLICY **不支持 IF NOT EXISTS**
--    （PG 17 实测：语法错误），故用 pg_policies 存在性判断来跳过（同 V16）。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    -- 6.1 cap_config_pointer
    ALTER TABLE cap_config_pointer ENABLE ROW LEVEL SECURITY;
    ALTER TABLE cap_config_pointer FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_pointer'
                   AND policyname = 'cap_config_pointer_read') THEN
        CREATE POLICY cap_config_pointer_read ON cap_config_pointer
            FOR SELECT USING (tenant_id IS NULL OR tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_pointer'
                   AND policyname = 'cap_config_pointer_write') THEN
        CREATE POLICY cap_config_pointer_write ON cap_config_pointer
            FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_pointer'
                   AND policyname = 'cap_config_pointer_update') THEN
        CREATE POLICY cap_config_pointer_update ON cap_config_pointer
            FOR UPDATE USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;

    -- 6.2 cap_decision_log
    ALTER TABLE cap_decision_log ENABLE ROW LEVEL SECURITY;
    ALTER TABLE cap_decision_log FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_decision_log'
                   AND policyname = 'cap_decision_log_read') THEN
        CREATE POLICY cap_decision_log_read ON cap_decision_log
            FOR SELECT USING (tenant_id IS NULL OR tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_decision_log'
                   AND policyname = 'cap_decision_log_write') THEN
        CREATE POLICY cap_decision_log_write ON cap_decision_log
            FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_decision_log'
                   AND policyname = 'cap_decision_log_update') THEN
        CREATE POLICY cap_decision_log_update ON cap_decision_log
            FOR UPDATE USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END;
$$;

COMMENT ON POLICY cap_config_pointer_read ON cap_config_pointer IS
    'PLATFORM 指针只读共享；TENANT 指针仅本租户可见。';
COMMENT ON POLICY cap_config_pointer_write ON cap_config_pointer IS
    '租户只能写自己的 TENANT 指针。PLATFORM 指针须由平台运维角色执行。';
COMMENT ON POLICY cap_decision_log_read ON cap_decision_log IS
    'PLATFORM 发布类审计只读共享；租户调用类审计仅本租户可见。';
COMMENT ON POLICY cap_decision_log_write ON cap_decision_log IS
    '租户只能写自己租户作用域的审计行。PLATFORM 发布审计须由平台运维角色写入。';

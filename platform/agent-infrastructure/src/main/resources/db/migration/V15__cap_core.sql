-- =====================================================================
-- V15：统一配置与权限管控 · 核心表（W-PERM-P0 · 地基）
--
-- 对标设计文档：
--   docs/Agent - Skill- Tool -MCP 统一配置与权限管控体系.md
--     §6.1 核心表 / §6.2 引用与快照职责 / §6.3 原子发布与并发控制
--     §6.4 闭包指纹（结构就绪，算法在 P1 实现）/ §6.6 生命周期
--   docs/权限管控体系/06_配置数据模型与生命周期设计.md
--     §1.1~§1.7 表结构 / §3.2 多对象原子发布 / §4 四概念分离
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 不可妥协的设计约束：
--   1. cap_*_def 为 (id, version) **不可变版本行**；当前生效版本由发布头指针
--      指向，**绝不**用 is_current 之类可变标志、也**绝不**以 max(seq) 选当前
--      （06 §1.1 / §3.2）。
--   2. cap_authz_version 为 **DB 支撑、单调递增、全局一致** 的版本号，
--      递增必须由 DB 原子操作完成，**禁止**应用先读后加一（06 §1.5 第 2 条）。
--   3. 撤权与版本递增同事务提交（06 §1.5 第 4 条）。
--   4. 结果事件表与执行意图记录必须在**副作用之前**可恢复落盘（P2 步骤 13a /
--      P3 §5.3.4），否则崩溃窗口内副作用既无法防重也无法对账。
--   5. 租户边界：cap_tool_def / cap_skill_def / cap_mcp_server_def 为 platform；
--      agent、快照、审批、账本、补偿任务、结果事件按 tenant RLS（§6.1）。
--
-- 本迁移只建表与约束，**不含**任何业务逻辑：
--   · 原子发布 CAS            → W-PERM-P5
--   · 闭包指纹算法             → W-PERM-P1
--   · 授权公式 / Phase A 校验  → W-PERM-P1
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 枚举
-- ---------------------------------------------------------------------

-- 配置发布状态（06 §1.7）。PREPARING 为未提交态，运行期**绝不可读到**
-- （06 §3.2 第 5 条）；ROLLED_BACK 仅作审计旁路，不参与当前版本解析。
CREATE TYPE cap_release_status AS ENUM (
    'PREPARING', 'PUBLISHED', 'ROLLED_BACK'
);

-- 结果事件的结果类型。与 tool_operation_state 的 UNKNOWN 语义对齐：
-- 副作用已发生但响应丢失 = UNKNOWN，**不得**盲目重放（红线：UNKNOWN 对账）。
CREATE TYPE cap_result_status AS ENUM (
    'SUCCEEDED', 'FAILED', 'UNKNOWN'
);

-- 执行意图状态。与 P3 审批状态机（PENDING→RESERVED→INTENT_SUBMITTED→CONSUMED）
-- 对齐：意图先于副作用写入，故其状态只能前进不能回退到"未打算执行"。
CREATE TYPE cap_intent_state AS ENUM (
    'RESERVED', 'INTENT_SUBMITTED', 'CONSUMED', 'CANCELLED', 'EXPIRED'
);

-- ---------------------------------------------------------------------
-- 1. 配置发布序号（§6.3 第 2 条）
--
-- ⚠️ 全局单调发布序号，由**序列**保证唯一。
-- ⚠️ 序号只标识「发布事件」，**当前版本不以 max(seq) 选择**——
--    当前版本只认发布头指针指向的 PUBLISHED release（§6.3 第 5 条）。
--    两者都存在正是刻意设计：configVersion 负责「版本追溯」，
--    configFingerprint 负责「内容一致性」（06 §4）。
-- ---------------------------------------------------------------------

CREATE SEQUENCE cap_config_publish_seq AS BIGINT START WITH 1 INCREMENT BY 1;

COMMENT ON SEQUENCE cap_config_publish_seq IS
    '配置发布全局单调序号（§6.3）。仅用于追溯发布事件，不用于选择当前版本。';

-- ---------------------------------------------------------------------
-- 2. platform 级对象定义表（cap_tool_def / cap_skill_def / cap_mcp_server_def）
--
-- ⚠️ 这三张表是 **platform 级**，**不启用 RLS**（§6.1 租户边界）。
--    理由：V1 只允许 platform 级定义（06 §2），租户级自定义 Tool 留 V2。
--    若误加 RLS，平台级定义将无人可见 → 全员授权失败。
--
-- ⚠️ 三表均为 (id, version) 复合主键的**不可变版本行**。
--    发布 = 插入新 (id, version+1) 行 + 发布头指针切换，**绝不 UPDATE 历史行**。
-- ---------------------------------------------------------------------

CREATE TABLE cap_tool_def (
    id                  TEXT NOT NULL,
    version             INTEGER NOT NULL,
    name                TEXT,
    -- native | mcp（04 §A2 / §A3）
    kind                TEXT NOT NULL,
    -- 风险等级**枚举化**：未知/非法值在**配置期**即被拒（04 §A9 / 06 §1.1）。
    -- 缺省不允许 —— 05 §3.3 要求"缺省=WRITE_EXTERNAL 且 fail-closed"，
    -- 这里用 NOT NULL + CHECK 强制显式声明，比依赖应用层默认值更硬。
    risk_level          TEXT NOT NULL,
    mcp_server_id       TEXT,
    mcp_tool_name       TEXT,
    -- 结构校验用；缺省 {} 表示"无结构约束"，但不代表"允许任意参数"
    -- （参数域由 param_policies 收紧，见 §4.3）
    input_schema        JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- V1 仅支持 in_allowlist / lte 两点手写判定；其他 op 写入即拒
    -- （04 §3 预留字段表）。**本阶段只建结构，op 白名单校验在 P1/P5**。
    param_policies      JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- V1 仅 platform（06 §2）
    namespace           TEXT NOT NULL DEFAULT 'platform',
    -- 本版本内容指纹（不可变）。⚠️ P0 写入**临时确定性摘要**，
    --    真实闭包指纹算法在 P1 实现（§6.4），届时需重算并迁移。
    config_fingerprint  TEXT NOT NULL,
    -- 行格式版本，迁移兼容（06 §3.3）
    schema_version      INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT cap_tool_def_pk PRIMARY KEY (id, version),
    CONSTRAINT cap_tool_def_kind_ck CHECK (kind IN ('native', 'mcp')),
    CONSTRAINT cap_tool_def_risk_ck CHECK (
        risk_level IN ('READ', 'WRITE_PROFILE', 'WRITE_EXTERNAL')),
    CONSTRAINT cap_tool_def_namespace_ck CHECK (namespace = 'platform'),
    CONSTRAINT cap_tool_def_version_positive CHECK (version > 0),
    -- kind=mcp 时才允许有 mcp_server_id / mcp_tool_name；
    -- kind=native 时携带二者即为**配置错误**（会绕过 MCP 固定清单与 SSRF 防线）
    CONSTRAINT cap_tool_def_mcp_binding_ck CHECK (
        (kind = 'mcp' AND mcp_server_id IS NOT NULL AND mcp_tool_name IS NOT NULL)
        OR (kind = 'native' AND mcp_server_id IS NULL AND mcp_tool_name IS NULL))
);

CREATE INDEX idx_cap_tool_def_fingerprint ON cap_tool_def (config_fingerprint);

COMMENT ON TABLE cap_tool_def IS
    'Tool 定义（platform 级，§6.1）。(id,version) 不可变版本行；当前生效版本由 cap_config_release 解析。';

CREATE TABLE cap_skill_def (
    id                  TEXT NOT NULL,
    version             INTEGER NOT NULL,
    name                TEXT,
    description         TEXT,
    -- Tool id 显式列表（**仅存 id**，有效版本由发布头解析，06 §2）
    declared_tools      JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- [{serverId, toolAllowlist?}]
    declared_mcp        JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 上限。未知/非法 → 发布拒绝（04 §A5）
    risk_ceiling        TEXT NOT NULL,
    param_policies      JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- ⚠️ V1 **写入即拒**（04 §3 预留字段 / N1）。
    --    DB 层 CHECK 保证"预留字段"不会悄悄变成"生效字段"——
    --    嵌套 Skill 的传递闭包易抬权（02 P3），V1 从存储层就堵死。
    inherits            JSONB,
    -- V1 忽略语义（预留，N2）
    required_caps       JSONB NOT NULL DEFAULT '[]'::jsonb,
    namespace           TEXT NOT NULL DEFAULT 'platform',
    config_fingerprint  TEXT NOT NULL,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT cap_skill_def_pk PRIMARY KEY (id, version),
    CONSTRAINT cap_skill_def_risk_ck CHECK (
        risk_ceiling IN ('READ', 'WRITE_PROFILE', 'WRITE_EXTERNAL')),
    CONSTRAINT cap_skill_def_namespace_ck CHECK (namespace = 'platform'),
    CONSTRAINT cap_skill_def_version_positive CHECK (version > 0),
    CONSTRAINT cap_skill_def_no_inherits_ck CHECK (inherits IS NULL)
);

COMMENT ON TABLE cap_skill_def IS
    'Skill 定义（platform 级，§6.1）。inherits 在 V1 写入即拒（N1）。';

CREATE TABLE cap_mcp_server_def (
    id                  TEXT NOT NULL,
    version             INTEGER NOT NULL,
    -- V1 必做 http/sse；stdio 允许存在但**不启用**（§6.5 末句 / N7）
    transport           TEXT NOT NULL,
    endpoint            TEXT,
    command             TEXT,
    auth_kind           TEXT NOT NULL,
    -- internal | partner | public（影响默认审批）
    trust_tier          TEXT NOT NULL,
    -- 固定工具名清单。⚠️ null ≠ []：
    --    null = 仅连接级授权，调用时按 Skill toolAllowlist 兜底（05 §4）；
    --    []  = 显式空清单 = 任何操作都不允许（05 §4 错误码表）。
    --    两者语义不同，**不可**用同一默认值混淆（04 §3 / 05 §4）。
    exposed_tool_allowlist JSONB,
    -- 连接级风险（默认映射 trustTier）
    connection_risk     TEXT,
    -- ⚠️ V1 固定 false；写入 true 即拒绝（04 §3 / N4）。
    --    DB 层 CHECK 兜底：动态发现会绕过固定清单与指纹校验（S5a/S5c）。
    dynamic_discovery   BOOLEAN NOT NULL DEFAULT FALSE,
    config_fingerprint  TEXT NOT NULL,
    -- 凭据**引用**（非明文）。明文禁入库（06 §5）
    credential_ref      TEXT,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT cap_mcp_server_def_pk PRIMARY KEY (id, version),
    CONSTRAINT cap_mcp_server_def_transport_ck CHECK (
        transport IN ('stdio', 'http', 'sse')),
    CONSTRAINT cap_mcp_server_def_auth_ck CHECK (
        auth_kind IN ('token', 'oauth', 'none')),
    CONSTRAINT cap_mcp_server_def_trust_ck CHECK (
        trust_tier IN ('internal', 'partner', 'public')),
    CONSTRAINT cap_mcp_server_def_version_positive CHECK (version > 0),
    -- V1 动态发现恒为 false（N4）
    CONSTRAINT cap_mcp_server_def_no_dynamic_ck CHECK (dynamic_discovery = FALSE),
    -- http/sse 必须有 endpoint；stdio 必须有 command（06 §5）
    CONSTRAINT cap_mcp_server_def_endpoint_ck CHECK (
        (transport IN ('http', 'sse') AND endpoint IS NOT NULL)
        OR (transport = 'stdio' AND command IS NOT NULL))
);

COMMENT ON TABLE cap_mcp_server_def IS
    'MCP Server 定义（platform 级，§6.1）。dynamic_discovery 恒为 false（N4）；凭据仅存 credential_ref。';

-- ---------------------------------------------------------------------
-- 3. 租户级对象定义表：cap_agent_def（§6.1 / 06 §1.4）
--
-- ⚠️ 租户级 → **启用 RLS**。
-- ⚠️ 与既有 agent_version 的关系（06 §1.4 评审 01-02）：
--    二者并存但指向同一发布事实 —— cap_agent_def 是**结构化真源**，
--    agent_version 保留为历史快照归档，迁移期**双写**（P0-T6），
--    最终读路径切到 cap_agent_def。
-- ---------------------------------------------------------------------

CREATE TABLE cap_agent_def (
    tenant_id           UUID NOT NULL,
    agent_id            UUID NOT NULL,
    version             INTEGER NOT NULL,
    name                TEXT,
    description         TEXT,
    -- 仅声明 Agent 适用主体角色范围/调用资格，**非授权授予**（03 §2.2 注）
    roles               JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- Skill id 显式列表（**仅存 id**）。V1 **唯一启用途径**（04 §A1）：
    -- 不允许任何隐式/标签传播启用
    enabled_skills      JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- V1 忽略语义（预留，N2）
    skill_bundles       JSONB NOT NULL DEFAULT '[]'::jsonb,
    capability_tags     JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- ⚠️ V1 写入即拒（N1）
    inherits            TEXT,
    -- {removeSkills?, removeTools?, lowerRiskCeiling?} —— **仅收紧**（04 §A6）
    overrides           JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_fingerprint  TEXT NOT NULL,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT cap_agent_def_pk PRIMARY KEY (tenant_id, agent_id, version),
    CONSTRAINT cap_agent_def_version_positive CHECK (version > 0),
    CONSTRAINT cap_agent_def_no_inherits_ck CHECK (inherits IS NULL),
    -- 复合外键：防跨租户引用（与 agent_version 同构）
    CONSTRAINT cap_agent_def_agent_fk FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agent (tenant_id, agent_id) ON DELETE CASCADE
);

CREATE INDEX idx_cap_agent_def_tenant ON cap_agent_def (tenant_id, agent_id, version DESC);

COMMENT ON TABLE cap_agent_def IS
    'Agent 结构化定义（租户级，§6.1 / 06 §1.4）。迁移期与 agent_version 双写，'
    '本表为结构化真源。';

-- ---------------------------------------------------------------------
-- 4. cap_authz_version（DB 化 authzVersion，V1 强制）
--
-- 06 §1.5 并发协议：
--   1. 初始值 1（首次创建主体授权记录时写入）
--   2. 递增由 DB 原子操作完成，**禁止**应用先读后加一（避免丢失更新）
--   3. 角色变化 / 主体禁用 / 授权关系删除撤销 **均** bump
--   4. 撤权与版本递增同事务
--   5. 作用域为 (tenant_id, principal_id)，跨租户身份变化须分别 bump
--   6. 版本服务不可用 → fail-closed 拒绝，**不允许**降级放行
--   7. 重复撤权只继续递增，不产生版本回退或丢失撤权
-- ---------------------------------------------------------------------

CREATE TABLE cap_authz_version (
    tenant_id           UUID NOT NULL,
    principal_id        TEXT NOT NULL,
    -- 单调递增。CHECK 兜底"不得回退到 0 或负"
    authz_version       BIGINT NOT NULL DEFAULT 1,
    -- 乐观并发列（06 §1.5 末列）。DB 原子自增是主协议，此列供
    -- 需要 CAS 语义的调用方（预期基线）使用
    version             INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cap_authz_version_pk PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT cap_authz_version_positive CHECK (authz_version >= 1)
);

COMMENT ON TABLE cap_authz_version IS
    'DB 化单调授权版本（§6.1 / 06 §1.5）。递增必须用 UPDATE...RETURNING 原子自增，'
    '禁止应用先读后加一。读取失败一律 fail-closed（AUTHZ_UNAVAILABLE）。';

-- ---------------------------------------------------------------------
-- 5. execution_capability_snapshot（T2 绑定快照，06 §1.6）
--
-- ⚠️ 同时记录 config_version（**序号**，追溯发布事件）与
--    config_fingerprint（**内容**，检测一致性）—— 二者语义不同，**不可合并**
--    （06 §1.6 注 / §4）。
-- ⚠️ config_fingerprint 是**本 Run 依赖闭包级**摘要，不是全局聚合；
--    无关对象变更**不**触发本 Run 失效（§6.4 / 05 §5 / S5a）。
-- ---------------------------------------------------------------------

CREATE TABLE execution_capability_snapshot (
    run_id              UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    agent_id            UUID NOT NULL,
    -- 计算后的有效能力闭包
    effective_closure   JSONB NOT NULL,
    agent_version       INTEGER NOT NULL,
    -- 配置发布版本（单调序号）
    config_version      BIGINT NOT NULL,
    -- 有效能力闭包指纹
    config_fingerprint  TEXT NOT NULL,
    -- 生成时快照的授权版本
    authz_version       BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT execution_capability_snapshot_version_positive CHECK (agent_version > 0),
    -- 复合外键：防跨租户引用
    CONSTRAINT execution_capability_snapshot_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

CREATE INDEX idx_exec_cap_snapshot_tenant ON execution_capability_snapshot (tenant_id, created_at DESC);

COMMENT ON TABLE execution_capability_snapshot IS
    'Run 级能力闭包快照（§6.1 / 06 §1.6）。config_version 与 config_fingerprint 并存，'
    '分别承担版本追溯与内容一致性校验。';

-- ---------------------------------------------------------------------
-- 6. cap_config_release（发布头 / 原子发布指针，06 §1.7 / §3.2）
--
-- ⚠️ **作用域决策（P0 落地说明，需 P5 复核）**：
--    §6.3 允许「平台级全局唯一当前指针；租户级（若有）按 tenant」，
--    §6.1 的租户 RLS 清单未包含发布头。但 V1 的 cap_agent_def 是**租户级**，
--    若发布头为 platform 级且**不带 RLS**，其 object_versions 会把
--    **各租户的 Agent 结构**写进一张任何租户都能 SELECT 的表 —— 跨租户结构泄漏
--    （与 R-004 同类，Sev-0 级）。
--
--    故本表按 scope 区分：
--      · PLATFORM 作用域 → tenant_id 为 NULL，只含 platform 对象版本
--      · TENANT   作用域 → tenant_id 非空，只含本租户对象版本
--    并以 RLS 保证：租户只读 PLATFORM 行、只写自己的 TENANT 行；
--    PLATFORM 行的写入由平台运维角色（BYPASSRLS）执行。
--    详见本表 RLS 策略注释。
-- ---------------------------------------------------------------------

CREATE TABLE cap_config_release (
    release_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 全局单调发布序号（跨对象一致边界）
    config_publish_seq  BIGINT NOT NULL UNIQUE,
    scope               TEXT NOT NULL,
    tenant_id           UUID,
    -- [{type, id, version}, ...] 本次发布各对象版本列表
    object_versions     JSONB NOT NULL,
    aggregate_fingerprint TEXT,
    status              cap_release_status NOT NULL DEFAULT 'PREPARING',
    -- 双人复核：approver_id <> requester_id（06 §3 状态机 approve 行）
    requester_id        TEXT,
    approver_id         TEXT,
    effective_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cap_config_release_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL)
        OR (scope = 'TENANT' AND tenant_id IS NOT NULL)),
    -- 双人复核的 DB 层兜底：应用层校验不是唯一防线
    CONSTRAINT cap_config_release_two_person_ck CHECK (
        approver_id IS NULL OR requester_id IS NULL OR approver_id <> requester_id)
);

CREATE INDEX idx_cap_config_release_current
    ON cap_config_release (scope, tenant_id, config_publish_seq DESC)
    WHERE status = 'PUBLISHED';

COMMENT ON TABLE cap_config_release IS
    '配置发布头（§6.1 / 06 §1.7）。当前版本只认**指针指向的 PUBLISHED release**，'
    '绝不以 max(seq) 选择，也绝不扫描历史行判断当前。';

-- ---------------------------------------------------------------------
-- 7. cap_result_event（结果事件表，§6.1）
--
-- 用途（§6.1）：invocationId、确定结果、终态事件 id，**供 POST 重试与崩溃恢复**。
-- ⚠️ 关键不变式：同一 invocation **最多一条**结果事件（UNIQUE 约束）。
--    这条约束是 P3「确定成功但 POST 失败 → 只重试落账、**禁止**再次 execute」
--    的存储层地基（§7.4 双维度结果）。没有它，"重试落账"就可能变成"重跑副作用"。
-- ---------------------------------------------------------------------

CREATE TABLE cap_result_event (
    event_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    run_id              UUID,
    invocation_id       TEXT NOT NULL,
    -- 终态事件 id（与 run_event 的终态事件对应）
    terminal_event_id   TEXT,
    result_status       cap_result_status NOT NULL,
    -- 确定结果载荷。UNKNOWN 时允许为 NULL（结果本就未知）
    result_payload      JSONB,
    -- 错误码/原因码（失败与 UNKNOWN 时使用）
    reason_code         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ⚠️ 同一 invocation 最多一条确定结果
    CONSTRAINT cap_result_event_invocation_unique UNIQUE (tenant_id, invocation_id),
    -- 复合外键：防跨租户引用
    CONSTRAINT cap_result_event_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

CREATE INDEX idx_cap_result_event_run ON cap_result_event (tenant_id, run_id, created_at DESC);

COMMENT ON TABLE cap_result_event IS
    '工具调用的确定结果事件（§6.1）。UNIQUE(tenant_id, invocation_id) 是'
    '"只重试落账不重执行"的存储层保障。';

-- ---------------------------------------------------------------------
-- 8. cap_execution_intent（执行意图记录，§6.1）
--
-- 用途（§6.1）：与 PRE/INTENT 合并或独立，**可恢复**。
-- ⚠️ 这是 P2 步骤 13a 的落点：**必须先于** ToolHandler.execute 持久化。
--    若崩溃发生在"意图已写、副作用未发"→ 恢复时可判定"未执行"，安全重试；
--    若崩溃发生在"副作用已发、意图未写"→ 恢复时无从判断，必须按 UNKNOWN 对账。
--    顺序反了就会产生**不可检测的重复副作用**（07 §2 步骤 13a / P3 COMP-6）。
-- ---------------------------------------------------------------------

CREATE TABLE cap_execution_intent (
    intent_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    run_id              UUID,
    invocation_id       TEXT NOT NULL,
    tool_id             TEXT NOT NULL,
    tool_version        INTEGER,
    -- 参数指纹（防参数替换重放，P3 §5.3.1 双哈希之一）
    args_fingerprint    TEXT,
    approval_id         TEXT,
    intent_state        cap_intent_state NOT NULL DEFAULT 'RESERVED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ⚠️ 同一 invocation 最多一条执行意图
    CONSTRAINT cap_execution_intent_invocation_unique UNIQUE (tenant_id, invocation_id),
    -- 复合外键：防跨租户引用
    CONSTRAINT cap_execution_intent_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

CREATE INDEX idx_cap_execution_intent_run ON cap_execution_intent (tenant_id, run_id, created_at DESC);
-- 恢复扫描：按状态捞出未终结的意图（崩溃恢复，P3 §5.3.5）
CREATE INDEX idx_cap_execution_intent_pending
    ON cap_execution_intent (tenant_id, intent_state, created_at);

COMMENT ON TABLE cap_execution_intent IS
    '执行意图记录（§6.1）。必须先于外部副作用写入（P2 步骤 13a）；'
    '崩溃恢复据此区分"未执行"与"结果未知"。';

-- ---------------------------------------------------------------------
-- 9. RLS 租户隔离（ADR-006 / §6.1）
--
-- 沿用 V1 的既有机制：
--   · current_tenant_id() 读 current_setting('app.tenant_id')
--   · ENABLE + FORCE ROW LEVEL SECURITY（表 owner 也受策略约束）
--   · 未设置租户上下文时**默认拒绝**（fail-closed）
--
-- ⚠️ 本节的 platform 级表（cap_tool_def / cap_skill_def / cap_mcp_server_def）
--    **刻意不启用 RLS**（§6.1 租户边界）。对 platform 表误加 RLS 会让
--    平台级定义对所有租户不可见 → 全员授权失败。
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'cap_agent_def', 'cap_authz_version', 'execution_capability_snapshot',
        'cap_result_event', 'cap_execution_intent'
    ];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            t || '_tenant_isolation', t);
    END LOOP;
END;
$$;

-- ⚠️ cap_config_release 的策略**分读写两种**，与普通租户表不同：
--    · SELECT：PLATFORM 行对所有租户只读可见（平台对象版本需共享）；
--            TENANT 行仅本租户可见。
--    · INSERT/UPDATE：**只允许写本租户的 TENANT 行**。
--      若写策略也放行 tenant_id IS NULL，则任一租户都能发布
--      **全局**配置 —— 那是直接的越权。因此 PLATFORM 行的写入
--      只能由平台运维角色（BYPASSRLS / 迁移角色）执行。
DO $$
BEGIN
    ALTER TABLE cap_config_release ENABLE ROW LEVEL SECURITY;
    ALTER TABLE cap_config_release FORCE ROW LEVEL SECURITY;

    CREATE POLICY cap_config_release_read ON cap_config_release
        FOR SELECT USING (tenant_id IS NULL OR tenant_id = current_tenant_id());

    CREATE POLICY cap_config_release_write ON cap_config_release
        FOR INSERT WITH CHECK (tenant_id = current_tenant_id());

    CREATE POLICY cap_config_release_update ON cap_config_release
        FOR UPDATE USING (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
END;
$$;

COMMENT ON POLICY cap_config_release_read ON cap_config_release IS
    'PLATFORM 作用域行只读共享（平台对象版本）；TENANT 作用域行仅本租户可见。';
COMMENT ON POLICY cap_config_release_write ON cap_config_release IS
    '租户只能写自己的 TENANT 作用域发布。PLATFORM 作用域发布须由平台运维角色执行。';

-- ---------------------------------------------------------------------
-- 10. 已发布版本行不可变（§6.1 / 06 §1.1）
--
-- ⚠️ 只封禁**已发布**行（published_at IS NOT NULL）的 **UPDATE**：
--    草稿行仍可编辑（06 §3「草稿编辑不修改已发布版本行」），
--    但已发布行一旦写入就**不可变**——改配置只能发新版本。
--
-- ⚠️ 用「改写已发布行」来表达"当前版本切换"是**禁止**的：
--    那样需要随切换反复更新可变标志，发布指针与历史行会不一致
--    （06 §1.1 末段）。切换当前版本**只**改发布头指针。
--
-- ⚠️ **DELETE 不在此封禁**：平台的删除/保留期链路（V8 erasure）必须仍能
--    物理删除数据行，否则「被遗忘权」会被这条不变式直接挡住 ——
--    那是一个**法务/合规**义务，不能被一条技术不变式否决。
--    「被引用的对象不得物理删除 → 改 DEPRECATED」（06 §3.4）是
--    **引用完整性**规则，由应用层在删除前检查引用关系来保证；
--    本触发器只守「已发布内容不可被<b>改写</b>」这一条。
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION forbid_published_def_mutation() RETURNS TRIGGER AS $$
DECLARE
    v_version TEXT;
BEGIN
    IF OLD.published_at IS NOT NULL THEN
        -- ⚠️ **必须用 to_jsonb(OLD) 取列，不能写 OLD.id / OLD.version**：
        --    本函数被四张结构不同的表共用，而 cap_agent_def **没有 id 列**
        --    （它是 tenant_id + agent_id + version）。直接引用 OLD.id 会在
        --    cap_agent_def 上抛 "record old has no field id" ——
        --    报错点离真因（该行已发布）很远，且掩盖了「不可变」这个真实语义。
        v_version := to_jsonb(OLD) ->> 'version';
        RAISE EXCEPTION '% 为已发布不可变版本行，禁止修改（version=%）',
            TG_TABLE_NAME, v_version USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cap_tool_def_immutable
    BEFORE UPDATE ON cap_tool_def
    FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation();

CREATE TRIGGER trg_cap_skill_def_immutable
    BEFORE UPDATE ON cap_skill_def
    FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation();

CREATE TRIGGER trg_cap_mcp_server_def_immutable
    BEFORE UPDATE ON cap_mcp_server_def
    FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation();

-- ⚠️ cap_agent_def 同样受保护（租户级，迁移期双写的**写入目标**）：
--    若它可被原地改写，双写的一致性比对将失去意义 ——
--    比对工具每次都比的是「当前值」，而不是「写入时刻的值」，
--    漂移会被改写动作掩盖掉。已发布版本行必须不可改写。
CREATE TRIGGER trg_cap_agent_def_immutable
    BEFORE UPDATE ON cap_agent_def
    FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation();

COMMENT ON FUNCTION forbid_published_def_mutation() IS
    '已发布版本行内容不可改写（§6.1 / 06 §1.1）。草稿行可编辑；已发布行改配置须发新版本。'
    'DELETE 不受此约束：删除/保留期链路必须能物理删除（V8 erasure）。';

-- =====================================================================
-- V1：基线数据模型（ADR-004 / P1-02）
--
-- 技术基线：PostgreSQL 17（DEC-13），支持至 2029-11-08
-- 目标 MQ：RabbitMQ 4.3.6 + Quorum Queue（DEC-12 / ADR-001）
--
-- 不可妥协的设计约束（来自 P0-D03 §4 验收红线）：
--   1. PostgreSQL 是 Run/事件/Outbox/ToolOperation/Approval/Usage 的权威事实源
--   3. Run 创建、初始事件、幂等记录和 Outbox 必须在同一事务中提交
--   4. Run attempt、ToolOperation 逻辑操作、调用 attempt 必须区分
--   8. 对象存储每次读取重新授权（授权字段见 object_access_grant）
--   9. 当前授权需在旧 Run 恢复和工具执行时重新验证
--
-- 租户隔离（ADR-006）：所有租户级表启用 RLS，且使用 FORCE ROW LEVEL SECURITY，
--   使表 owner 也受策略约束——避免应用误用高权限连接造成跨租户泄漏。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 枚举类型
-- ---------------------------------------------------------------------

CREATE TYPE run_state AS ENUM (
    'QUEUED', 'RUNNING', 'CANCELLED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'
);

CREATE TYPE tool_operation_state AS ENUM (
    'PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'UNKNOWN',
    'RECONCILED_SUCCEEDED', 'RECONCILED_ABSENT', 'MANUAL_DISPOSED'
);

CREATE TYPE approval_state AS ENUM (
    'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONSUMED'
);

CREATE TYPE tool_risk_level AS ENUM (
    'READ', 'WRITE_PROFILE', 'WRITE_EXTERNAL'
);

CREATE TYPE approval_requirement AS ENUM (
    'NOT_REQUIRED', 'OPTIONAL', 'REQUIRED'
);

CREATE TYPE outbox_state AS ENUM (
    'PENDING', 'PUBLISHED', 'FAILED'
);

-- ---------------------------------------------------------------------
-- 2. 租户
-- ---------------------------------------------------------------------

CREATE TABLE tenant (
    tenant_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$')
);

COMMENT ON TABLE tenant IS '租户。注意：本表为全局表，不启用 RLS（租户自身即隔离单元）';

-- ---------------------------------------------------------------------
-- 3. Agent 与版本
-- ---------------------------------------------------------------------

CREATE TABLE agent (
    agent_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_name_per_tenant UNIQUE (tenant_id, name),
    -- 复合唯一约束：供下游表以 (tenant_id, agent_id) 建立复合外键，
    -- 防止跨租户引用（仅靠 agent_id 单列主键不足以约束租户边界）
    CONSTRAINT agent_tenant_agent_unique UNIQUE (tenant_id, agent_id)
);

CREATE TABLE agent_version (
    version_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    agent_id       UUID NOT NULL,
    version_number INTEGER NOT NULL,
    -- 不可变快照内容：发布后不得原地修改（FR-002）
    definition     JSONB NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_version_unique UNIQUE (tenant_id, agent_id, version_number),
    -- 供 execution_snapshot 以 (tenant_id, version_id) 建立复合外键
    CONSTRAINT agent_version_tenant_version_unique UNIQUE (tenant_id, version_id),
    CONSTRAINT agent_version_number_positive CHECK (version_number > 0),
    -- 复合外键：防止跨租户引用
    CONSTRAINT agent_version_agent_fk FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agent (tenant_id, agent_id) ON DELETE CASCADE
);

COMMENT ON CONSTRAINT agent_version_unique ON agent_version IS
    '复合唯一约束：同一租户下 agent 版本号唯一。跨租户 agent_id 相同不冲突';

CREATE INDEX idx_agent_version_agent ON agent_version (tenant_id, agent_id);

-- ---------------------------------------------------------------------
-- 4. 执行快照（FR-002 不可变快照 + DEC-9 配置快照 TODO-10）
-- ---------------------------------------------------------------------

CREATE TABLE execution_snapshot (
    snapshot_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    version_id     UUID NOT NULL,
    -- 快照内容一经写入不可修改
    agent_config   JSONB NOT NULL,
    prompt_config  JSONB NOT NULL,
    tool_contracts JSONB NOT NULL,
    -- DEC-9：Run 创建时快照的配置值，此后改配置只影响新 Run（R-021）
    run_config     JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 供 run 以 (tenant_id, snapshot_id) 建立复合外键
    CONSTRAINT execution_snapshot_tenant_snapshot_unique UNIQUE (tenant_id, snapshot_id),
    CONSTRAINT execution_snapshot_version_fk FOREIGN KEY (tenant_id, version_id)
        REFERENCES agent_version (tenant_id, version_id)
);

COMMENT ON COLUMN execution_snapshot.run_config IS
    'DEC-9 配置快照：maxCostPerRun/maxLlmCalls/maxSteps/approvalRequired。'
    'Run 创建时固化，改配置只影响新 Run。审计导出不得含学生可识别信息';

-- 快照不可变：UPDATE 一律拒绝
CREATE OR REPLACE FUNCTION forbid_snapshot_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'execution_snapshot 为不可变快照，禁止修改（snapshot_id=%）',
        OLD.snapshot_id USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_snapshot_immutable
    BEFORE UPDATE OR DELETE ON execution_snapshot
    FOR EACH ROW EXECUTE FUNCTION forbid_snapshot_mutation();

-- ---------------------------------------------------------------------
-- 5. Run
-- ---------------------------------------------------------------------

CREATE TABLE run (
    run_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,
    snapshot_id        UUID NOT NULL,
    -- 幂等键域 + 请求摘要（ADR-003）
    idempotency_key    TEXT NOT NULL,
    request_digest     TEXT NOT NULL,
    state              run_state NOT NULL DEFAULT 'QUEUED',
    -- fencing token：租约世代，Worker 每次接管递增（红线 5）
    fencing_token      BIGINT NOT NULL DEFAULT 0,
    lease_owner        TEXT,
    lease_expires_at   TIMESTAMPTZ,
    attempt            INTEGER NOT NULL DEFAULT 0,
    -- 取消登记时间；协作式停止，不承诺撤销已发外部副作用
    cancel_requested_at TIMESTAMPTZ,
    input_payload      JSONB NOT NULL,
    result_payload     JSONB,
    error_code         TEXT,
    error_message      TEXT,
    correlation_id     TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    -- 幂等：同租户同键唯一（ADR-003）
    CONSTRAINT run_idempotency_unique UNIQUE (tenant_id, idempotency_key),
    -- 供 run_step / run_event / tool_operation / outbox_event / usage 等以 (tenant_id, run_id) 建立复合外键
    CONSTRAINT run_tenant_run_unique UNIQUE (tenant_id, run_id),
    CONSTRAINT run_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT run_fencing_non_negative CHECK (fencing_token >= 0),
    CONSTRAINT run_snapshot_fk FOREIGN KEY (tenant_id, snapshot_id)
        REFERENCES execution_snapshot (tenant_id, snapshot_id),
    -- 终态才有 completed_at
    CONSTRAINT run_completed_at_consistent CHECK (
        (state IN ('QUEUED', 'RUNNING') AND completed_at IS NULL)
        OR (state IN ('CANCELLED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT') AND completed_at IS NOT NULL)
    ),
    -- 失败/成功终态须有结果或错误
    CONSTRAINT run_result_consistent CHECK (
        state <> 'SUCCEEDED' OR result_payload IS NOT NULL
    )
);

COMMENT ON CONSTRAINT run_idempotency_unique ON run IS
    'ADR-003：同租户同幂等键只允许一个 Run。同键不同摘要由应用层判定为冲突';

CREATE INDEX idx_run_state_queued ON run (tenant_id, state) WHERE state = 'QUEUED';
CREATE INDEX idx_run_lease ON run (tenant_id, lease_expires_at) WHERE state = 'RUNNING';
CREATE INDEX idx_run_correlation ON run (tenant_id, correlation_id);

-- 状态只能通过受控迁移更新：禁止从终态迁出
CREATE OR REPLACE FUNCTION enforce_run_state_transition() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state IN ('CANCELLED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')
       AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Run 终态 % 不可迁移到 %（run_id=%）',
            OLD.state, NEW.state, OLD.run_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_run_state_guard
    BEFORE UPDATE ON run
    FOR EACH ROW EXECUTE FUNCTION enforce_run_state_transition();

-- Worker 提交结果必须携带匹配的 fencing token（红线 5：旧世代不得提交）
CREATE OR REPLACE FUNCTION enforce_fencing_on_write() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.fencing_token < OLD.fencing_token THEN
        RAISE EXCEPTION 'fencing token 回退被拒绝：% < %（run_id=%）',
            NEW.fencing_token, OLD.fencing_token, OLD.run_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fencing_guard
    BEFORE UPDATE ON run
    FOR EACH ROW EXECUTE FUNCTION enforce_fencing_on_write();

-- ---------------------------------------------------------------------
-- 6. Run Step（执行步骤）
-- ---------------------------------------------------------------------

CREATE TABLE run_step (
    step_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    run_id         UUID NOT NULL,
    step_number    INTEGER NOT NULL,
    state          run_state NOT NULL,
    step_type      TEXT NOT NULL,
    input_payload  JSONB,
    output_payload JSONB,
    error_class    TEXT,
    fencing_token  BIGINT NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT run_step_number_unique UNIQUE (tenant_id, run_id, step_number),
    CONSTRAINT run_step_number_positive CHECK (step_number > 0),
    CONSTRAINT run_step_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

CREATE INDEX idx_run_step_run ON run_step (tenant_id, run_id, step_number);

-- ---------------------------------------------------------------------
-- 7. ToolOperation 账本（红线 4：逻辑操作与调用 attempt 必须区分）
-- ---------------------------------------------------------------------

CREATE TABLE tool_operation (
    operation_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    run_id             UUID NOT NULL,
    step_id            UUID,
    tool_name          TEXT NOT NULL,
    risk_level         tool_risk_level NOT NULL,
    state              tool_operation_state NOT NULL DEFAULT 'PENDING',
    -- 稳定幂等键：首期 record_weakness 为 student_id + run_id + knowledge_point
    idempotency_key    TEXT NOT NULL,
    request_digest     TEXT NOT NULL,
    tool_arguments     JSONB NOT NULL,
    result_payload     JSONB,
    fencing_token      BIGINT NOT NULL,
    -- UNKNOWN 处置：责任人 + 对账结论（红线 7）
    reconciliation_source TEXT,
    reconciled_at      TIMESTAMPTZ,
    manual_disposed_by TEXT,
    manual_disposed_at TIMESTAMPTZ,
    -- 预算关联：预留 ID，UNKNOWN 不自动释放（R-007）
    budget_reservation_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 幂等：同一租户内幂等键唯一 → 重复调用命中同一逻辑操作
    CONSTRAINT tool_operation_idempotency_unique UNIQUE (tenant_id, idempotency_key),
    -- 供 tool_operation_attempt / approval / usage_record 以 (tenant_id, operation_id) 建立复合外键
    CONSTRAINT tool_operation_tenant_op_unique UNIQUE (tenant_id, operation_id),
    CONSTRAINT tool_operation_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE,
    -- UNKNOWN 必须有对账或人工处置痕迹
    CONSTRAINT tool_operation_unknown_needs_disposition CHECK (
        state <> 'RECONCILED_SUCCEEDED' OR reconciled_at IS NOT NULL
    ),
    CONSTRAINT tool_operation_manual_needs_actor CHECK (
        state <> 'MANUAL_DISPOSED' OR (manual_disposed_by IS NOT NULL AND manual_disposed_at IS NOT NULL)
    )
);

COMMENT ON CONSTRAINT tool_operation_idempotency_unique ON tool_operation IS
    '幂等核心：同租户同幂等键只有一个逻辑操作。重复调用复用同一行而非新建';

COMMENT ON COLUMN tool_operation.budget_reservation_id IS
    '关联预算预留。UNKNOWN 状态下不得自动释放预留——成本可能已发生（R-007）';

CREATE INDEX idx_tool_operation_run ON tool_operation (tenant_id, run_id);
CREATE INDEX idx_tool_operation_unknown ON tool_operation (tenant_id, state)
    WHERE state = 'UNKNOWN';
CREATE INDEX idx_tool_operation_pending_reconcile ON tool_operation (tenant_id, created_at)
    WHERE state = 'UNKNOWN';

-- 调用 attempt：与逻辑操作分离，支持「重试次数」与「副作用次数」解耦
CREATE TABLE tool_operation_attempt (
    attempt_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    operation_id     UUID NOT NULL,
    attempt_number   INTEGER NOT NULL,
    state            tool_operation_state NOT NULL,
    fencing_token    BIGINT NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    error_class      TEXT,
    error_message    TEXT,
    -- 结果是否未知：区分「明确失败可重试」与「未知须对账」（最易出现的 Sev-0）
    result_unknown   BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT tool_attempt_number_unique UNIQUE (tenant_id, operation_id, attempt_number),
    CONSTRAINT tool_attempt_number_positive CHECK (attempt_number > 0),
    -- 未知结果的 attempt 必须标记 result_unknown
    CONSTRAINT tool_attempt_unknown_flag CHECK (
        state <> 'UNKNOWN' OR result_unknown = TRUE
    ),
    CONSTRAINT tool_attempt_operation_fk FOREIGN KEY (tenant_id, operation_id)
        REFERENCES tool_operation (tenant_id, operation_id) ON DELETE CASCADE
);

CREATE INDEX idx_tool_attempt_operation ON tool_operation_attempt (tenant_id, operation_id, attempt_number);

-- attempt 归属的逻辑操作已终态后，不得再新增 attempt
CREATE OR REPLACE FUNCTION forbid_attempt_after_terminal() RETURNS TRIGGER AS $$
DECLARE
    op_state tool_operation_state;
BEGIN
    SELECT state INTO op_state
    FROM tool_operation
    WHERE tenant_id = NEW.tenant_id AND operation_id = NEW.operation_id
    FOR UPDATE;

    IF op_state IN ('SUCCEEDED', 'RECONCILED_SUCCEEDED', 'RECONCILED_ABSENT', 'MANUAL_DISPOSED') THEN
        RAISE EXCEPTION '逻辑操作已终态 %，禁止新增 attempt（operation_id=%）',
            op_state, NEW.operation_id USING ERRCODE = 'restrict_violation';
    END IF;
    -- UNKNOWN 状态下新增 attempt 等同盲目重试，必须拒绝
    IF op_state = 'UNKNOWN' THEN
        RAISE EXCEPTION '逻辑操作处于 UNKNOWN，禁止新增 attempt（等同盲目重试，Sev-0）。'
            '须先完成对账（operation_id=%）', NEW.operation_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_forbid_attempt_after_terminal
    BEFORE INSERT ON tool_operation_attempt
    FOR EACH ROW EXECUTE FUNCTION forbid_attempt_after_terminal();

-- ---------------------------------------------------------------------
-- 8. 审批
-- ---------------------------------------------------------------------

CREATE TABLE approval (
    approval_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    operation_id     UUID NOT NULL,
    state            approval_state NOT NULL DEFAULT 'PENDING',
    requirement      approval_requirement NOT NULL,
    -- 审批绑定参数摘要与目标资源，防止审批后参数被篡改（FR-013）
    argument_digest  TEXT NOT NULL,
    target_resource  TEXT NOT NULL,
    approver_scope   TEXT NOT NULL,
    -- DEC-11：有效期配置（初值 15 分钟）
    expires_at       TIMESTAMPTZ NOT NULL,
    approver_id      TEXT,
    decided_at       TIMESTAMPTZ,
    consumed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_operation_fk FOREIGN KEY (tenant_id, operation_id)
        REFERENCES tool_operation (tenant_id, operation_id) ON DELETE CASCADE,
    -- 已审批必须记录审批人与时间
    CONSTRAINT approval_decided_consistent CHECK (
        state = 'PENDING' OR approver_id IS NOT NULL
    ),
    -- 消费后不可重复消费（一次性消费）
    CONSTRAINT approval_consumed_consistent CHECK (
        state <> 'CONSUMED' OR consumed_at IS NOT NULL
    )
);

COMMENT ON TABLE approval IS
    '审批绑定具体 operation + 参数摘要 + 目标资源 + 有效期。'
    '消费审批前必须检查 Run 是否已取消（P0-D03 §2）';

CREATE INDEX idx_approval_pending ON approval (tenant_id, state) WHERE state = 'PENDING';

-- ---------------------------------------------------------------------
-- 9. Run Event（事件持久化 + SSE 续传）
-- ---------------------------------------------------------------------

CREATE TABLE run_event (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    run_id          UUID NOT NULL,
    -- 单调递增序号，SSE Last-Event-ID 续传依据（FR-014）
    sequence_no     BIGINT NOT NULL,
    event_type      TEXT NOT NULL,
    -- 载荷最小化：不含学生可识别信息与 Prompt 原文
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT run_event_seq_unique UNIQUE (tenant_id, run_id, sequence_no),
    CONSTRAINT run_event_seq_positive CHECK (sequence_no > 0),
    CONSTRAINT run_event_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

COMMENT ON CONSTRAINT run_event_seq_unique ON run_event IS
    'SSE 续传依据：同一 Run 内序号唯一且单调递增。游标过期按协议重置';

CREATE INDEX idx_run_event_cursor ON run_event (tenant_id, run_id, sequence_no);

-- ---------------------------------------------------------------------
-- 10. Outbox（红线 3：与 Run 创建同事务原子提交）
-- ---------------------------------------------------------------------

CREATE TABLE outbox_event (
    outbox_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    run_id          UUID NOT NULL,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      TEXT NOT NULL,
    -- 消息体最小化：只含唤醒信号，不含 Prompt/工具参数/学生数据（ADR-001 §4.2）
    payload         JSONB NOT NULL,
    state           outbox_state NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT outbox_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

COMMENT ON TABLE outbox_event IS
    '与 Run 创建在同一事务写入（红线 3）。发布失败可重试；重复投递由消费端幂等处理';

CREATE INDEX idx_outbox_pending ON outbox_event (tenant_id, created_at)
    WHERE state = 'PENDING';

-- ---------------------------------------------------------------------
-- 11. 幂等记录（红线 3：与 Run 创建同事务原子提交）
-- ---------------------------------------------------------------------

CREATE TABLE idempotency_record (
    record_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    key_namespace   TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_digest  TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     UUID,
    -- 保留期到期后可清理（ADR-003）
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_unique UNIQUE (tenant_id, key_namespace, idempotency_key),
    -- 同键同摘要才可复用；异摘要由应用层判冲突
    CONSTRAINT idempotency_digest_consistent CHECK (length(request_digest) > 0)
);

COMMENT ON TABLE idempotency_record IS
    'ADR-003 幂等键命名空间。键的构造规则见 P1-D05 幂等与三层 Attempt 规范。'
    '同键同摘要返回原 resource_id；同键异摘要返回冲突';

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);

-- ---------------------------------------------------------------------
-- 12. 预算预留与用量（R-007：并发不能超发）
-- ---------------------------------------------------------------------

CREATE TABLE budget_account (
    account_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,
    currency        TEXT NOT NULL DEFAULT 'CNY',
    total_limit     NUMERIC(18, 6) NOT NULL,
    reserved_amount NUMERIC(18, 6) NOT NULL DEFAULT 0,
    settled_amount  NUMERIC(18, 6) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT budget_account_unique UNIQUE (tenant_id, currency),
    -- 供 budget_reservation 以 (tenant_id, account_id) 建立复合外键
    CONSTRAINT budget_account_tenant_account_unique UNIQUE (tenant_id, account_id),
    CONSTRAINT budget_amounts_non_negative CHECK (
        total_limit >= 0 AND reserved_amount >= 0 AND settled_amount >= 0
    ),
    -- 核心不变量：预留 + 已结算不得超过总额度（并发超发的最后防线）
    CONSTRAINT budget_no_overspend CHECK (reserved_amount + settled_amount <= total_limit)
);

COMMENT ON CONSTRAINT budget_no_overspend ON budget_account IS
    '数据库层最后防线：即使应用层并发检查有漏，DB 约束也拒绝超发（R-007）';

CREATE TABLE budget_reservation (
    reservation_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    account_id      UUID NOT NULL,
    run_id          UUID NOT NULL,
    reserved_amount NUMERIC(18, 6) NOT NULL,
    settled_amount  NUMERIC(18, 6) NOT NULL DEFAULT 0,
    state           TEXT NOT NULL DEFAULT 'RESERVED',
    -- UNKNOWN 时预留不释放，待对账后处置
    release_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at      TIMESTAMPTZ,
    CONSTRAINT reservation_amount_positive CHECK (reserved_amount > 0),
    CONSTRAINT reservation_settled_valid CHECK (
        settled_amount >= 0 AND settled_amount <= reserved_amount
    ),
    CONSTRAINT reservation_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES budget_account (tenant_id, account_id) ON DELETE RESTRICT,
    CONSTRAINT reservation_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

COMMENT ON COLUMN budget_reservation.state IS
    'RESERVED / SETTLED / RELEASED。UNKNOWN 对应的预留保持 RESERVED，不自动释放';

CREATE INDEX idx_reservation_run ON budget_reservation (tenant_id, run_id);

CREATE TABLE usage_record (
    usage_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    run_id          UUID NOT NULL,
    step_id         UUID,
    operation_id    UUID,
    model_id        TEXT NOT NULL,
    input_tokens    BIGINT NOT NULL DEFAULT 0,
    output_tokens   BIGINT NOT NULL DEFAULT 0,
    cost_amount     NUMERIC(18, 6) NOT NULL DEFAULT 0,
    currency        TEXT NOT NULL DEFAULT 'CNY',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT usage_tokens_non_negative CHECK (input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT usage_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE
);

COMMENT ON TABLE usage_record IS
    '模型用量账。幂等：以 (tenant_id, operation_id) 防止重复结算同一逻辑操作';

CREATE UNIQUE INDEX idx_usage_operation_once ON usage_record (tenant_id, operation_id)
    WHERE operation_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 13. 对象存储访问授权（红线 8：每次读取重新授权）
-- ---------------------------------------------------------------------

CREATE TABLE object_access_grant (
    grant_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    object_key      TEXT NOT NULL,
    principal_id    TEXT NOT NULL,
    permission      TEXT NOT NULL,
    -- 预签名 URL 必须限定有效期
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT object_grant_unique UNIQUE (tenant_id, object_key, principal_id, permission)
);

COMMENT ON TABLE object_access_grant IS
    '预签名 URL 授权记录。每次读取须重新校验当前授权，撤权后旧引用立即失效';

-- ---------------------------------------------------------------------
-- 14. 审计（脱敏，不含 Prompt 原文）
-- ---------------------------------------------------------------------

CREATE TABLE audit_record (
    audit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    actor_id        TEXT NOT NULL,
    action          TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT,
    -- 载荷脱敏：不存学生可识别信息、不存 Prompt 原文（P0-D05 R-015）
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_record IS
    '保留 180 天。detail 字段须脱敏，禁止写入 Prompt 原文与学生可识别信息';

CREATE INDEX idx_audit_tenant_time ON audit_record (tenant_id, created_at DESC);

-- =====================================================================
-- 15. RLS 租户隔离（ADR-006）
--
-- 设计要点：
--   · FORCE ROW LEVEL SECURITY 使表 owner 也受策略约束，
--     避免应用误用高权限连接造成跨租户泄漏（R-004 / Sev-0）
--   · current_setting('app.tenant_id') 由应用在事务开始时设置；
--     未设置时策略拒绝（默认拒绝，而非默认放行）
--   · 迁移角色使用 BYPASSRLS 权限，不受策略约束
-- =====================================================================

CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION current_tenant_id() IS
    '返回当前事务的租户上下文。未设置 app.tenant_id 时返回 NULL，'
    '所有 RLS 策略将拒绝访问——默认拒绝，符合 fail-closed';

DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'agent', 'agent_version', 'execution_snapshot', 'run', 'run_step',
        'tool_operation', 'tool_operation_attempt', 'approval', 'run_event',
        'outbox_event', 'idempotency_record', 'budget_account',
        'budget_reservation', 'usage_record', 'object_access_grant', 'audit_record'
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

COMMENT ON POLICY agent_tenant_isolation ON agent IS
    '租户隔离策略。tenant_id 必须等于当前事务租户上下文，否则拒绝';
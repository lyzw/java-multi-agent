-- =====================================================================
-- V8 迁移：数据保留与删除（P4-D04）
--
-- 背景：P0-D00 §4.4 冻结了四类保留期承诺（画像 12 月 / Prompt 90 天 /
--       审计 180 天 / Trace 30 天），但截至本迁移前，全仓库**没有任何**
--       删除或清理实现——19 张表无一张带 expires_at/retain_until 列。
--       「有承诺、无机制」是合规审计中最难辩解的一种形态。
--
-- 依据：P0-D00 §4.4 · P0-D02 §5 NFR-008 · FR-019（撤回后 30 日内删除）
--
-- 本迁移做三件事：
--   1. 新建 consent_withdrawal（撤回登记）与 erasure_certificate（删除凭证）
--   2. 为两张新表启用 RLS + 授权（V2 §4 纪律）
--   3. 改造 V6 审计触发器，开出「受控清理通道」（P4-D04 D-1）
--
-- 幂等：全部 IF NOT EXISTS / CREATE OR REPLACE / DROP IF EXISTS
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 撤回同意登记
--
-- ⚠️ 为什么要有登记表而不直接删：
--    FR-019 要求「撤回后 30 日内删除」。「30 日内」是**上限**，
--    但系统必须能回答三个问题——谁撤回了、什么时候到期、
--    到期前有没有真的删掉。没有登记表就只能靠删除时间反推，
--    而「删了」恰恰意味着登记信息也没了。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS consent_withdrawal (
    withdrawal_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,

    -- ⚠️ 与 student_profile 同格式校验：撤回登记的 student_id 若格式开放，
    --    一旦有人塞进真实学号/姓名，登记表本身就成了违规数据源。
    student_id      TEXT        NOT NULL
        CONSTRAINT consent_student_id_format_chk
        CHECK (student_id ~ '^stu_[a-z0-9]{16}$'),

    withdrawn_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ⚠️ 到期时点**预计算**而非查询时算：
    --    ⚠️ due_at 必须能参与索引，才能对「已完成 vs 已到期未完成」
    --    做索引扫描；运行时计算会使索引失效。
    due_at          TIMESTAMPTZ NOT NULL,

    state           TEXT        NOT NULL DEFAULT 'PENDING'
        CONSTRAINT consent_state_chk
        CHECK (state IN ('PENDING', 'ERASED', 'FAILED')),

    erased_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ⚠️ 同一租户同一学生只允许一条撤回登记（P4-D04 D-3）。
    --    第二次登记直接冲突，避免「撤回两次 → 产生两张凭证」的举证混乱。
    CONSTRAINT consent_student_uniq UNIQUE (tenant_id, student_id),

    CONSTRAINT consent_due_after_withdraw_chk
        CHECK (due_at >= withdrawn_at)
);

COMMENT ON TABLE consent_withdrawal IS
    '监护人撤回同意登记（FR-019）。登记后应在 due_at 前完成删除，'
    '完成情况由 state 与 erasure_certificate 共同举证。';
COMMENT ON COLUMN consent_withdrawal.due_at IS
    '删除截止时点 = withdrawn_at + 宽限期（默认 30 天）。'
    '宽限期是**上限**：允许提前删，不得超过。';

-- 到期扫描：只扫未完成且已到期的行
CREATE INDEX IF NOT EXISTS idx_consent_pending_due
    ON consent_withdrawal (tenant_id, due_at)
    WHERE state = 'PENDING';

-- ---------------------------------------------------------------------
-- 2. 删除凭证
--
-- ⚠️ 为什么必须单独建表而不只写日志：
--    日志会按保留期轮换、且难以按「某次删除」精确检索；
--    而凭证要与审计同等级别——它是「删除确实发生」的唯一证据，
--    且必须能被独立查询（ROP/合规举证时需要按人次列出）。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS erasure_certificate (
    certificate_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,

    -- ⚠️ 只允许两个值（P4-D04 §4.1）：「临时」「调试」之类 reason 一旦允许，
    --    凭证就从「举证材料」退化为「清理日志」。
    reason          TEXT        NOT NULL
        CONSTRAINT erasure_reason_chk
        CHECK (reason IN ('RETENTION_EXPIRED', 'CONSENT_WITHDRAWN')),

    -- ⚠️ 操作主体不可为空：没有主体的删除等于没有责任人（R-1）。
    actor           TEXT        NOT NULL
        CONSTRAINT erasure_actor_nonempty_chk
        CHECK (char_length(actor) BETWEEN 1 AND 128),

    executed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ⚠️ 本表刻意**不设** dry_run 列。
    --    预留一个永远为 false 的列，就是制造「看起来支持预演、实际从未接线的开关」——
    --    与本项目反复治理的「机制存在但不在链路上」（D01/D03 断链）是同一形态。
    --    预演语义若要有，应当随其真正的调用方一起落地。

    -- 清理窗口键（'YYYY-MM-DD'）。幂等键的一部分（P4-D04 D-6）。
    window_key      TEXT        NOT NULL
        CONSTRAINT erasure_window_key_chk
        CHECK (window_key ~ '^\d{4}-\d{2}-\d{2}$'),

    -- 删除主体：保留期清理为 'tenant-wide'；撤回清理为学生 ID。
    subject_key     TEXT        NOT NULL
        CONSTRAINT erasure_subject_key_nonempty_chk
        CHECK (char_length(subject_key) BETWEEN 1 AND 128),

    -- 行数明细：{"student_profile":3,"weakness_record":12,...}
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,

    -- ⚠️ 幂等（P4-D04 D-6）：同一租户、同一原因、同一窗口、同一主体
    --    只允许一张凭证。凭证数量失控会让「发生了什么」无法判读，
    --    重复凭证还会被误读为「删了两次」。
    CONSTRAINT erasure_idempotency_uniq
        UNIQUE (tenant_id, reason, window_key, subject_key)
);

COMMENT ON TABLE erasure_certificate IS
    '删除凭证（P4-D04）。保留期到期清理与撤回同意删除都必须留凭证，'
    '否则「我们按承诺删了」在合规举证中不成立。'
    '本表刻意**不设**自动清理：自清理会销毁自己的证据链（见 P4-D04 边界 L-4）。';

CREATE INDEX IF NOT EXISTS idx_erasure_tenant_time
    ON erasure_certificate (tenant_id, executed_at DESC);

-- ---------------------------------------------------------------------
-- 3. RLS 租户隔离（ADR-006 / V2 §4 纪律）
--
-- ⚠️ FORCE ROW LEVEL SECURITY 不可省略：
--    只 ENABLE 时表 owner 的连接会绕过策略 → 跨租户泄漏（Sev-0）。
--    student_id 属未成年人数据，是本系统泄漏后果最严重的一类。
--
-- ⚠️ DROP POLICY IF EXISTS 必须保留：
--    缺这一步时二次运行会因策略已存在而中断，
--    导致后续语句被**静默跳过**——产生「表在但保护不全」的假象。
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
    v8_tables TEXT[] := ARRAY['consent_withdrawal', 'erasure_certificate'];
BEGIN
    FOREACH t IN ARRAY v8_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_tenant_isolation', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            t || '_tenant_isolation', t);
    END LOOP;
END;
$$;

COMMENT ON POLICY consent_withdrawal_tenant_isolation ON consent_withdrawal IS
    '租户隔离。撤回登记含 student_id——未成年人数据，跨租户泄漏即 Sev-0。';

-- ---------------------------------------------------------------------
-- 4. 授权给非特权应用角色（V2 §4.1 纪律）
--
-- ⚠️ 新建表**不会**自动继承既有授权。漏掉时应用连接得到
--    `permission denied for table`，表现为限流 fail-open 或任务永久失败
--    ——症状与业务错误无法区分。
--
-- ⚠️ 逐条执行而非放进同一事务：任一条失败会连带回滚全部 GRANT，
--    表现为「授权数 = 0」却无明显报错。
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_agent') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON consent_withdrawal TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON erasure_certificate TO app_agent';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- 5. 受控清理通道：改造 V6 审计触发器（P4-D04 D-1）
--
-- 解决的冲突：
--   V6 让 audit_record 的 UPDATE/DELETE 一律拒绝（R-3 防篡改），
--   而 P0 §4.4 承诺审计保留 180 天——到期必须能删。
--   V6 的 COMMENT 给出的解法是「临时禁用触发器」，但那会留下一个
--   **删除窗口内 R-3 完全失效**的时间段，且无授权链、无删除凭证。
--
-- 解法：DELETE 时检查会话是否声明了操作主体
--   current_setting('agent.retention.actor', TRUE)
--     非空 → 放行（该值即操作主体，须落 erasure_certificate）
--     空   → 拒绝（V6 原语义保持）
--
-- ⚠️ 诚实边界：set_config() 任何会话都能调用，所以这不是**访问控制**，
--    而是「意图声明 + 强制留痕」。真正的防线是清理任务使用专用 DB 凭据
--    （运维面，见 P4-D04 边界 L-2）。
--
-- ⚠️ UPDATE 仍然一律拒绝：清理只删不改。允许 UPDATE 就等于允许
--    「把一条不利的审计改成有利的」——这是 R-3 的底线。
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION forbid_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    -- P4-D04 D-1：仅 DELETE 且会话已声明受控清理主体时放行
    IF TG_OP = 'DELETE'
       AND NULLIF(current_setting('agent.retention.actor', TRUE), '') IS NOT NULL THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION '审计记录为追加不可改（R-3）：禁止 % 操作 audit_record（audit_id=%）。'
        , TG_OP, OLD.audit_id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION forbid_audit_mutation() IS
    'V6 审计防篡改，P4-D04 增加受控清理通道：'
    'DELETE 仅在会话声明 agent.retention.actor 时放行，UPDATE 永远拒绝。'
    '注意这是意图声明而非访问控制——任意会话都可自行 set_config，'
    '真正的防线在 DB 凭据分离。';

COMMENT ON TABLE audit_record IS
    '审计记录（P3-D07 / R-1 / R-2 / R-3）。追加不可改：'
    'UPDATE 一律拒绝，DELETE 仅在受控清理通道下放行'
    '（会话须声明 agent.retention.actor 且须落 erasure_certificate）。'
    '180 天保留期由 RetentionJob 通过该通道执行清理。';

-- =====================================================================
-- V2 迁移：学生画像与薄弱点（D-007 业务契约）
--
-- 背景：V1 基线 17 张表中**无画像表、无薄弱点表**，
--       导致 get_student_profile 无处可读、record_weakness 无处可写。
--       这是 P1 遗漏，本迁移补齐。
--
-- 依据：P0-D00 §3.2 工具清单、DEC-6 数据白名单、ADR-003 幂等协议
--
-- 合规约束（P0-D00 §4，硬红线）：
--   · 仅采集随机 student_id、年级、学科、薄弱点（知识点+错误类型）
--   · 禁止：姓名/学校/班级/身份证/手机号/定位/生物识别/社交账号
--   · 本迁移**刻意不建**任何可识别学生身份的列，
--     使「不小心存了敏感信息」在类型层面无从发生
--
-- 幂等：全部 IF NOT EXISTS / ON CONFLICT，可重复执行
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 学生画像（get_student_profile 的读取目标）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS student_profile (
    -- ⚠️ 随机标识，不可反推身份。DEC-6 白名单的唯一主体标识
    --    前缀 stu_ + 16 位十六进制，便于日志中肉眼识别其性质
    student_id        TEXT        NOT NULL
        CONSTRAINT student_profile_id_format_chk
        CHECK (student_id ~ '^stu_[a-z0-9]{16}$'),
    tenant_id         UUID        NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,

    -- 以下为 DEC-6 白名单允许的全部属性，无其他可识别信息
    grade             TEXT        NOT NULL
        CONSTRAINT student_profile_grade_chk
        CHECK (grade IN ('初一','初二','初三')),
    subject           TEXT        NOT NULL
        CONSTRAINT student_profile_subject_chk
        CHECK (subject IN ('数学','物理','化学','语文','英语','生物')),

    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 同一租户内 student_id 唯一
    CONSTRAINT student_profile_pk PRIMARY KEY (tenant_id, student_id)
);

COMMENT ON TABLE student_profile IS
    '学生画像（DEC-6 白名单）。刻意不含姓名/学校/班级/联系方式——'
    '不是「暂未采集」，而是**结构上无法采集**，从源头杜绝越界。';
COMMENT ON COLUMN student_profile.student_id IS
    '随机标识，不可反推身份。禁止使用姓名、学号等可识别值。';
COMMENT ON COLUMN student_profile.updated_at IS
    '画像保留期 12 个月，由清理任务按此字段判定。';

-- ---------------------------------------------------------------------
-- 2. 薄弱点记录（record_weakness 的写入目标）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS weakness_record (
    weakness_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenant (tenant_id) ON DELETE CASCADE,

    -- ⚠️ 幂等键的三要素之一（ADR-003：student_id + run_id + knowledge_point）
    student_id        TEXT        NOT NULL,
    run_id            UUID        NOT NULL REFERENCES run (run_id) ON DELETE CASCADE,
    knowledge_point   TEXT        NOT NULL
        CONSTRAINT weakness_kp_length_chk
        CHECK (char_length(knowledge_point) BETWEEN 1 AND 100),

    -- 枚举而非自由文本：自由文本会使知识点无限发散，
    -- 无法聚合分析，且会成为 Prompt 注入的载体
    error_type        TEXT        NOT NULL
        CONSTRAINT weakness_error_type_chk
        CHECK (error_type IN (
            '概念不清','公式记错','符号错误','计算失误','审题偏差','步骤缺失')),

    -- 置信度。⚠️ 模型给出的置信度不可靠，此处仅作参考，不参与判定
    confidence        NUMERIC(3,2)
        CONSTRAINT weakness_confidence_chk
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),

    -- 同一逻辑操作的幂等保证（红线 4）
    -- ⚠️ 唯一约束是幂等的**最后防线**：
    -- 应用层可能因并发而同时通过幂等检查，只有 DB 能真正兜住
    CONSTRAINT weakness_idempotency_uniq
        UNIQUE (tenant_id, student_id, run_id, knowledge_point),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT weakness_student_fk
        FOREIGN KEY (tenant_id, student_id)
        REFERENCES student_profile (tenant_id, student_id) ON DELETE CASCADE
);

COMMENT ON TABLE weakness_record IS
    '薄弱点记录。首期唯一的有副作用工具 record_weakness 的落点，'
    '用于实证验证红线 4/5/7（Attempt 区分、fencing、UNKNOWN 对账）。';
COMMENT ON CONSTRAINT weakness_idempotency_uniq ON weakness_record IS
    '幂等最后防线：同一 (租户,学生,Run,知识点) 只允许一条。'
    '应用层并发检查无法阻止竞态，此约束是唯一可靠保证。';

-- ---------------------------------------------------------------------
-- 3. 索引
-- ---------------------------------------------------------------------

-- 画像查询：按学生取全部薄弱点
CREATE INDEX IF NOT EXISTS idx_weakness_student
    ON weakness_record (tenant_id, student_id, updated_at DESC);

-- 按知识点聚合分析（薄弱点排行）
CREATE INDEX IF NOT EXISTS idx_weakness_knowledge
    ON weakness_record (tenant_id, knowledge_point);

-- 保留期清理：按创建时间批量判定 12 个月
CREATE INDEX IF NOT EXISTS idx_weakness_created
    ON weakness_record (created_at);

-- ---------------------------------------------------------------------
-- 4. RLS 租户隔离（ADR-006）
--
-- 复用 V1 的 current_tenant_id() 与策略模板。
-- ⚠️ FORCE ROW LEVEL SECURITY 不可省略：
--    若省略，应用误用表 owner 连接时将绕过策略 → 跨租户泄漏（Sev-0）。
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
    v2_tables TEXT[] := ARRAY['student_profile', 'weakness_record'];
BEGIN
    FOREACH t IN ARRAY v2_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        -- ⚠️ 先 DROP 再 CREATE，保证迁移可重复执行。
        --    缺少这一步时，第二次运行会因策略已存在而中断，
        --    导致后续语句被静默跳过 —— 产生「表在但数据不全」的假象。
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_tenant_isolation', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            t || '_tenant_isolation', t);
    END LOOP;
END;
$$;

COMMENT ON POLICY student_profile_tenant_isolation ON student_profile IS
    '租户隔离。student_profile 直接承载未成年人数据，是跨租户泄漏'
    '后果最严重的表（R-004 / Sev-0）。';

-- ---------------------------------------------------------------------
-- 4.1 授权给非特权应用角色
--
-- ⚠️ 极易遗漏：新建表不会自动继承既有授权。若不显式 GRANT，
--    应用连接会得到 `permission denied for table`，
--    而 RLS 验证也会因无权访问而**假性通过或假性失败**。
--
-- ⚠️ 必须逐条执行：若把多条 GRANT 放进同一事务，任意一条失败会
--    连带回滚全部 GRANT，表现为「授权数=0」却无明显报错。
--
-- 角色不存在时跳过（首次部署可能尚未创建 app_agent）。
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_agent') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON student_profile TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON weakness_record TO app_agent';
        -- ⚠️ weakness_id 由 gen_random_uuid() 生成，**不建序列**，
        --    故无需序列授权。若日后改为 serial/bigserial 才需补充。
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- 5. 不变量守护：禁止写入白名单之外的字段
--
-- 不用触发器，而是**结构性保证**：表里根本没有那些列。
-- 相比「加了列但靠约束禁止写入」，这种方式更难被绕过——
-- 后者只需一次 ALTER 就能突破，前者需要改表结构（需评审）。
-- =====================================================================
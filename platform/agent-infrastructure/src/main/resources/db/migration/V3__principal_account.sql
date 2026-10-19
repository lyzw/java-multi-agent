-- =====================================================================
-- V3 · principal 账号主体表（D-001 平台自建认证）
-- =====================================================================
-- 背景：D-001 决策为「平台自建认证」，需账号存储。
-- 本表承载登录凭据与授权状态，是认证链的**唯一权威来源**。
--
-- ⚠️ 本表刻意**不提供**以下列，即使它们能带来便利：
--   邮箱 / 手机号 / 身份证号 / 真实姓名 / 班级 / 学校
-- 理由有二：
--   ① PIPL 未成年人合规：平台账号对应初中生及其监护人，
--      多采集一份可识别信息就多一份泄露面与告知同意成本。
--   ② 自建认证最大的诱惑是「顺手加个邮箱方便找回密码」——
--      结构上不提供，需要时须走 CHG 评审，而非一次 ALTER 顺手加。
--
-- ⚠️ login_name 设计为**全局唯一**而非租户内唯一：
--   租户内唯一会让 `alice` 存在于多个租户 → 用户混淆 + 枚举面扩大 +
--   运维易误判。撞名应在**发号阶段**即暴露，而非运行时。
-- =====================================================================

CREATE TABLE IF NOT EXISTS principal (
    principal_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant (tenant_id),
    login_name      TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    display_name    TEXT,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    -- 授权版本：撤销授权时递增，执行时重新授权据此识别陈旧上下文
    -- （P0-D03 §4 红线 9：学生退课、教师离职后，在途 Run 须重新校验）
    authz_version   BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ⚠️ login_name 全局唯一：这是防「跨租户撞名」的关键约束
CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_login_name
    ON principal (login_name);

-- 登录查询路径：按 tenant + login_name 定位（RLS 下 tenant 先行）
CREATE INDEX IF NOT EXISTS idx_principal_tenant_login
    ON principal (tenant_id, login_name);

-- ---------------------------------------------------------------
-- 约束
-- ---------------------------------------------------------------
ALTER TABLE principal DROP CONSTRAINT IF EXISTS principal_login_name_format;
ALTER TABLE principal ADD CONSTRAINT principal_login_name_format
    -- 限 ASCII 小写：避免大小写/Unicode 归一化差异导致的
    -- 「A 能否登录 a」类歧义（曾多次成为真实漏洞来源）
    CHECK (login_name ~ '^[a-z0-9][a-z0-9._-]{2,63}$');

ALTER TABLE principal DROP CONSTRAINT IF EXISTS principal_status_enum;
ALTER TABLE principal ADD CONSTRAINT principal_status_enum
    CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'));

ALTER TABLE principal DROP CONSTRAINT IF EXISTS principal_failed_attempts_nonneg;
ALTER TABLE principal ADD CONSTRAINT principal_failed_attempts_nonneg
    CHECK (failed_attempts >= 0);

ALTER TABLE principal DROP CONSTRAINT IF EXISTS principal_authz_version_positive;
ALTER TABLE principal ADD CONSTRAINT principal_authz_version_positive
    CHECK (authz_version >= 1);

ALTER TABLE principal DROP CONSTRAINT IF EXISTS principal_password_hash_present;
ALTER TABLE principal ADD CONSTRAINT principal_password_hash_present
    -- ⚠️ 格式为 $pbkdf2-sha256$i=<iterations>$<salt>$<hash>
    --    非 Argon2id：实测 JDK 21.0.12 不提供 Argon2id 的 SecretKeyFactory
    --    （该支持要到 JDK 25+），设计假设与运行环境不符，以实测为准。
    --    此约束同时防止「明文或弱哈希入库」—— 空串、短串、纯明文均不匹配。
    CHECK (password_hash LIKE '$pbkdf2-sha256$i=%$%');

CREATE TABLE IF NOT EXISTS principal_role (
    tenant_id    UUID NOT NULL,
    principal_id UUID NOT NULL REFERENCES principal (principal_id) ON DELETE CASCADE,
    role         TEXT NOT NULL,
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 一个账号在同一租户内同一角色只登记一次
CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_role
    ON principal_role (tenant_id, principal_id, role);

ALTER TABLE principal_role DROP CONSTRAINT IF EXISTS principal_role_not_blank;
ALTER TABLE principal_role ADD CONSTRAINT principal_role_not_blank
    CHECK (role ~ '^[A-Z][A-Z_]{1,31}$');

-- ---------------------------------------------------------------
-- 租户隔离（与其他租户表一致）
-- ---------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    v3_tables TEXT[] := ARRAY['principal', 'principal_role'];
BEGIN
    FOREACH t IN ARRAY v3_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        -- ⚠️ 先 DROP 再 CREATE，保证迁移可重复执行（V2 踩过的坑）
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_tenant_isolation', t);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            t || '_tenant_isolation', t);
    END LOOP;
END;
$$;

COMMENT ON TABLE principal IS
    'D-001 平台自建认证的账号主体表。刻意不采集邮箱/手机号/真实姓名（PIPL + DEC-6）。'
    'login_name 全局唯一以防跨租户撞名。';

COMMENT ON COLUMN principal.authz_version IS
    '授权版本，撤销授权时递增。执行时重新授权据此拒绝陈旧上下文（红线 9）。';

COMMENT ON COLUMN principal.password_hash IS
    'PBKDF2-HMAC-SHA256 哈希。任何查询路径都不得返回此列到 API 响应或日志。';

COMMENT ON TABLE principal_role IS
    'D-001 账号角色。这是「防止令牌自封管理员」的权威来源：'
    'JWT 中的 roles 仅为声明，实际生效角色以此表为准。';

-- ---------------------------------------------------------------
-- 授权给非特权角色
-- ---------------------------------------------------------------
-- ⚠️ GRANT 必须逐条执行：整段放一个事务里，任一条失败会连带
--    回滚全部 GRANT，表现为「授权数=0」却无明显报错（P1 踩过）。
-- ⚠️ principal_id 用 gen_random_uuid() 生成，不建序列，无需序列授权。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_agent') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON principal TO app_agent';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON principal_role TO app_agent';
    END IF;
END;
$$;
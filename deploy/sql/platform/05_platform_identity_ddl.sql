-- =====================================================================
-- 平台身份域 DDL（重规划 §3.1 / §8）
-- =====================================================================
--
-- 【定位】与租户身份域（public  schema 的 tenant_user / tenant_membership /
--        agent / run … 带 RLS）完全独立的 platform schema。本 schema 不含
--        任何租户业务数据，不挂 RLS，由 platform_ops 数据库角色（或超管）
--        在带外运维通道管理。平台主体（platform_user）不持有 tenant_id，
--        不属于任何租户。
--
-- 【适用】生产环境初始化 / 与线上库做结构比对。本文件与仓库迁移脚本同源，
--        迁移脚本变更时需重新导出核对。
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS platform;

-- ── 枚举类型 ───────────────────────────────────────────────────────
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'platform_user_status') THEN
    CREATE TYPE platform.user_status AS ENUM ('ACTIVE','DISABLED','LOCKED');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'platform_auth_source') THEN
    CREATE TYPE platform.auth_source AS ENUM ('LOCAL','OIDC','SSO');
  END IF;
END$$;

-- ── 平台用户主体（无 tenant_id）─────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.platform_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(128) NOT NULL UNIQUE,
    email         VARCHAR(256) UNIQUE,
    display_name  VARCHAR(128),
    status        platform.user_status NOT NULL DEFAULT 'ACTIVE',
    auth_source   platform.auth_source  NOT NULL,
    -- ⚠️ PBKDF2-HMAC-SHA256（$pbkdf2-sha256$i=..$salt$hash），与租户侧同算法。
    --    绝不可出现在任何 API 响应/日志中。
    password_hash VARCHAR(256),
    mfa_required  BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    authz_version BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

-- ── 平台角色 / 权限 / 绑定（与租户角色表物理隔离）────────────────────
CREATE TABLE IF NOT EXISTS platform.platform_role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(256),
    built_in    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS platform.platform_permission (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(128) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS platform.platform_role_permission (
    role_id       UUID NOT NULL REFERENCES platform.platform_role(id),
    permission_id UUID NOT NULL REFERENCES platform.platform_permission(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS platform.platform_user_role (
    platform_user_id UUID NOT NULL REFERENCES platform.platform_user(id),
    role_id          UUID NOT NULL REFERENCES platform.platform_role(id),
    granted_by       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (platform_user_id, role_id)
);

-- ── 平台会话（独立撤销状态）─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.platform_session (
    id            UUID PRIMARY KEY,
    platform_user_id UUID NOT NULL REFERENCES platform.platform_user(id),
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 平台 MFA / 外部身份提供方绑定 ──────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.platform_mfa_credential (
    id              UUID PRIMARY KEY,
    platform_user_id UUID NOT NULL REFERENCES platform.platform_user(id),
    type            VARCHAR(32) NOT NULL,
    ext_subject     VARCHAR(256),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 租户生命周期 / 配额 / 能力授权（平台对租户的管理关系）────────────
-- ⚠️ platform.tenant 是平台侧生命周期登记；业务表仍在 public schema（带 tenant_id + RLS）。
--    两者通过 tenant_id 逻辑关联，不在本文件建跨 schema 外键（避免耦合业务表结构）。
CREATE TABLE IF NOT EXISTS platform.tenant (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(128) NOT NULL,
    slug                   VARCHAR(64)  NOT NULL UNIQUE,
    status                 VARCHAR(32)  NOT NULL,
    plan                   VARCHAR(32),
    lifecycle_state        VARCHAR(32)  NOT NULL,
    effective_policy_version BIGINT NOT NULL DEFAULT 1,
    tenant_authz_version   BIGINT NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.tenant_quota (
    tenant_id             UUID NOT NULL REFERENCES platform.tenant(id),
    agent_limit           INT,
    run_concurrency_limit INT,
    budget_limit          NUMERIC(14,2),
    PRIMARY KEY (tenant_id)
);

CREATE TABLE IF NOT EXISTS platform.tenant_capability_grant (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES platform.tenant(id),
    resource_code VARCHAR(128) NOT NULL,
    scope       VARCHAR(32) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, resource_code)
);

-- ── 平台审计（无 tenant_id；与租户审计分域）─────────────────────────
CREATE TABLE IF NOT EXISTS platform.platform_audit_event (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id      UUID NOT NULL,
    actor_type    VARCHAR(32) NOT NULL DEFAULT 'PLATFORM_USER',
    action        VARCHAR(128) NOT NULL,
    target_type   VARCHAR(64),
    target_id     UUID,
    decision      VARCHAR(16) NOT NULL,
    reason_code   VARCHAR(64),
    correlation_id UUID,
    ip            INET,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 索引 ───────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_platform_user_status ON platform.platform_user (status);
CREATE INDEX IF NOT EXISTS idx_platform_user_role_user ON platform.platform_user_role (platform_user_id);
CREATE INDEX IF NOT EXISTS idx_platform_user_role_role ON platform.platform_user_role (role_id);
CREATE INDEX IF NOT EXISTS idx_platform_session_user ON platform.platform_session (platform_user_id);
CREATE INDEX IF NOT EXISTS idx_platform_tenant_status ON platform.tenant (status);
CREATE INDEX IF NOT EXISTS idx_platform_cap_grant_tenant ON platform.tenant_capability_grant (tenant_id);
CREATE INDEX IF NOT EXISTS idx_platform_audit_actor ON platform.platform_audit_event (actor_id, occurred_at);

-- ⚠️ 内置平台角色初始化（应用层不提供自授端点；此处仅为角色目录种子，
--    实际赋权仍经 out-of-band 运维通道写入 platform_user_role）。
INSERT INTO platform.platform_role (name, description, built_in) VALUES
  ('PLATFORM_SUPER_ADMIN',   '平台超级管理员（最高权限，敏感操作强 MFA/重认证）', TRUE),
  ('TENANT_LIFECYCLE_ADMIN', '租户生命周期管理员（创建/启停/套餐/配额/能力授权）', TRUE),
  ('PLATFORM_RESOURCE_ADMIN','平台资源管理员（全局 Tool/MCP/模型目录/资源可用性）', TRUE),
  ('PLATFORM_SECURITY_ADMIN','平台安全管理员（认证/访问/授权策略与风险处置）', TRUE),
  ('PLATFORM_AUDITOR',       '平台审计员（只读平台审计与脱敏租户生命周期）', TRUE),
  ('PLATFORM_SUPPORT',       '平台支持人员（只读脱敏诊断与运维信息）', TRUE)
ON CONFLICT (name) DO NOTHING;

-- ── 平台权限全集（与代码 PlatformRolePermissionMatrix.allPlatformPermissions() 严格一致）──
INSERT INTO platform.platform_permission (code) VALUES
  ('platform.user.read'), ('platform.user.manage'),
  ('platform.role.read'), ('platform.role.assign'), ('platform.role.revoke'),
  ('platform.tenant.create'), ('platform.tenant.read'), ('platform.tenant.update'),
  ('platform.tenant.suspend'), ('platform.tenant.resume'), ('platform.tenant.decommission'),
  ('platform.tenant.quota.read'), ('platform.tenant.quota.update'),
  ('platform.tenant.capability.grant'), ('platform.tenant.capability.revoke'),
  ('platform.tenant.capability.read'),
  ('platform.resource.read'), ('platform.resource.manage'),
  ('platform.policy.read'), ('platform.policy.publish'),
  ('platform.audit.read'), ('platform.audit.export'),
  ('platform.support.diagnostics.read')
ON CONFLICT (code) DO NOTHING;

-- ⚠️ 角色→权限映射（与 PlatformRolePermissionMatrix.ROLE_PERMISSIONS 严格一致）。
--    以下 INSERT 为权限矩阵的 DB 落地（审计/自检用）；运行时授权以代码矩阵为准。
INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'PLATFORM_SUPER_ADMIN'
  AND p.code IN (
    'platform.user.read','platform.user.manage','platform.role.read','platform.role.assign',
    'platform.role.revoke','platform.tenant.create','platform.tenant.read','platform.tenant.update',
    'platform.tenant.suspend','platform.tenant.resume','platform.tenant.decommission',
    'platform.tenant.quota.read','platform.tenant.quota.update','platform.tenant.capability.grant',
    'platform.tenant.capability.revoke','platform.tenant.capability.read','platform.resource.read',
    'platform.resource.manage','platform.policy.read','platform.policy.publish','platform.audit.read',
    'platform.audit.export','platform.support.diagnostics.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'TENANT_LIFECYCLE_ADMIN'
  AND p.code IN (
    'platform.tenant.create','platform.tenant.read','platform.tenant.update','platform.tenant.suspend',
    'platform.tenant.resume','platform.tenant.decommission','platform.tenant.quota.read',
    'platform.tenant.quota.update','platform.tenant.capability.grant','platform.tenant.capability.revoke',
    'platform.tenant.capability.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'PLATFORM_RESOURCE_ADMIN'
  AND p.code IN ('platform.resource.read','platform.resource.manage','platform.tenant.capability.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'PLATFORM_SECURITY_ADMIN'
  AND p.code IN ('platform.policy.read','platform.policy.publish',
    'platform.audit.read','platform.support.diagnostics.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'PLATFORM_AUDITOR'
  AND p.code IN ('platform.audit.read','platform.audit.export','platform.tenant.read',
    'platform.tenant.quota.read','platform.tenant.capability.read','platform.resource.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r, platform.platform_permission p
WHERE r.name = 'PLATFORM_SUPPORT'
  AND p.code IN ('platform.support.diagnostics.read','platform.tenant.read',
    'platform.tenant.quota.read','platform.tenant.capability.read','platform.resource.read')
ON CONFLICT (role_id, permission_id) DO NOTHING;

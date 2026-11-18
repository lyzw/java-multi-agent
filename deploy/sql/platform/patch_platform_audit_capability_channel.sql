-- 平台审计扩列：统一能力调用的可追溯字段（双入口方案第 3 刀 · A7）
--
-- 背景：admin-console 同一能力有平台直录 / Agent 编排两种入口，二者归一为
-- InvocationContext 后经 PolicyGate 裁决。审计需要回答「谁、从哪个入口、以哪档、
-- 跑了哪个能力」，ticket_scope 已有，本补丁补 capability + channel 两列。
--
-- 幂等：重复执行安全（DO $$ 检查列存在）。对 NULL 的兼容：Java 侧 PlatformAuditEvent
-- 提供旧 12 参构造器，旧调用点这两列为 NULL，属常态而非缺失。
-- 仅追加审计表，无 RLS（platform schema），由 platform_ops 角色持有。

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform'
          AND table_name = 'platform_audit_event'
          AND column_name = 'capability'
    ) THEN
        ALTER TABLE platform.platform_audit_event
            ADD COLUMN capability character varying(128);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'platform'
          AND table_name = 'platform_audit_event'
          AND column_name = 'channel'
    ) THEN
        ALTER TABLE platform.platform_audit_event
            ADD COLUMN channel character varying(32);
    END IF;
END
$$;

COMMENT ON COLUMN platform.platform_audit_event.capability IS '统一能力标识（双入口方案第 3 刀，如 rag.corpus.ingest）。NULL = 非能力调用的审计。';
COMMENT ON COLUMN platform.platform_audit_event.channel IS '触发入口（双入口方案第 3 刀）：PLATFORM_DIRECT / AGENT_ORCH / TENANT_SESSION。NULL = 旧调用点。';

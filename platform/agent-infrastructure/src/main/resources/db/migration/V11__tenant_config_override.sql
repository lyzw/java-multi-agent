-- V11：租户配置覆盖层（P4-D15 / F6 · C2 配置存储）
--
-- ⚠️ 为什么是「覆盖层」而不是把配置搬进 DB：
--   平台已有 56 个 yml 叶子键，其中 46 个**已接入运行时**并带启动期 fail-closed
--   守卫（P4-D11）。把默认值搬进 DB 会一次性砸掉这些守卫与
--   ApplicationConfigTest 的 yml 文本契约，收益远小于风险。
--   故 DB 只存**覆盖值**：无覆盖 = 用 yml 默认；删除覆盖 = 立刻回默认。
--
-- ⚠️ 为什么这样就能「不重启生效」：
--   配置快照在**每次 Run 创建时**构造（RunConfigSnapshotFactory.snapshotFor），
--   不是启动时固化一次。所以覆盖值天然只影响新 Run，
--   且已被 R-021「快照隔离」保护——在途 Run 不受影响。
--   这不是本迁移带来的能力，而是复用了既有架构（R-021）。

CREATE TABLE IF NOT EXISTS tenant_config_override (
    tenant_id   UUID NOT NULL REFERENCES tenant(tenant_id),
    config_key  TEXT NOT NULL,
    value_text  TEXT NOT NULL,
    -- 显式存类型：解析失败时报「类型不匹配」而非神秘的值解析异常（fail-closed 可诊断性）
    value_type  TEXT NOT NULL CHECK (value_type IN ('INTEGER', 'DECIMAL', 'ENUM', 'DURATION')),
    -- ⛔ C7 的技术落点：理由 NOT NULL——缺失时**数据库层**就写不进去，
    --    不依赖应用层自觉。长度下限由应用层校验（便于返回 400 而非 500）。
    reason      TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    changed_by  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 每租户每键至多一条覆盖：删除覆盖即回默认，无需区分历史
    PRIMARY KEY (tenant_id, config_key)
);

COMMENT ON TABLE tenant_config_override IS
    'P4-D15 租户配置覆盖层：仅存覆盖值，缺省回落 yml 默认值；生效机制为 Run 快照隔离（R-021）';

-- ── RLS：与其他表一致 ──
-- ⚠️ FORCE ROW LEVEL SECURITY 不可省略：表 owner 不受普通 RLS 约束，
--    不 FORCE 则 owner 连接可绕过隔离（V8 已踩过，见该迁移注释）。
ALTER TABLE tenant_config_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_config_override FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_config_override_tenant_isolation ON tenant_config_override;
CREATE POLICY tenant_config_override_tenant_isolation ON tenant_config_override
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ── GRANT ──
-- ⚠️ **逐条执行而非放进同一事务**：任一条失败会连带回滚全部 GRANT。
-- ⚠️ 新表漏 GRANT 的后果不是「功能不可用」而是 `permission denied`，
--    配置写入整体 fail-closed（P3-D08 踩过同形态：限流自身故障反成 DoS 入口）。
DO $$
BEGIN
    EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_config_override TO app_agent';
END $$;

-- ── updated_at 自动维护 ──
-- ⚠️ 覆盖值的 updated_at 是「本条覆盖最后一次被改」的时间，
--    不等于配置历史；历史在 audit_record 里（见 P4-D15 §6 L-5）。
CREATE OR REPLACE FUNCTION touch_tenant_config_override_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_config_override_touch ON tenant_config_override;
CREATE TRIGGER trg_config_override_touch
    BEFORE UPDATE ON tenant_config_override
    FOR EACH ROW EXECUTE FUNCTION touch_tenant_config_override_updated_at();
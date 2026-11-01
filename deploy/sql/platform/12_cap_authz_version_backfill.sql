-- ============================================================================
-- 12_cap_authz_version_backfill.sql
-- 关联缺口：cap_authz_version 无任何真实用户行（只有 principal_id='worker'，
--   而 worker 在 principal 表里 0 行，不是真实主体）
-- 关联阶段：P0 地基交付后遗留（2026-10-31 实测发现）
--
-- 目的：为所有真实用户初始化 cap_authz_version 行，值取自 principal.authz_version。
--
-- ⚠️ 为什么不是「新建行、值=1」而是「取自 principal.authz_version」：
--    principal.authz_version 是实际生效的授权版本（JWT 的 authzVersion 就来自它），
--    而 cap_authz_version 是 CAP 模型的 DB 强一致版本源。
--    两者本应同步，但 cap_authz_version 从未被初始化。
--    直接写 1 会让「principal 已撤权但 cap_authz_version 仍是 1」的窗口出现，
--    而取自 principal.authz_version 则保证两者一致。
--
-- ⚠️ 为什么用 INSERT ... ON CONFLICT DO NOTHING 而非 UPDATE：
--    已有 worker 行（测试夹具）保留不动；真实用户行若已存在也不覆盖。
--    幂等：重复执行不报错、不改既有数据。
-- ============================================================================

INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version, version, updated_at)
SELECT p.tenant_id, p.principal_id::text, p.authz_version, 0, now()
FROM principal p
WHERE NOT EXISTS (
    SELECT 1 FROM cap_authz_version a
    WHERE a.tenant_id = p.tenant_id AND a.principal_id::text = p.principal_id::text
);

-- 自检：所有真实用户都有 cap_authz_version 行
DO $$
DECLARE
    principal_count bigint;
    authz_count     bigint;
BEGIN
    SELECT count(*) INTO principal_count FROM principal;
    SELECT count(*) INTO authz_count FROM cap_authz_version;
    IF authz_count < principal_count THEN
        RAISE EXCEPTION 'cap_authz_version 行数 (% ) 少于 principal 行数 (% )，初始化未完全生效',
            authz_count, principal_count;
    END IF;
    RAISE NOTICE 'cap_authz_version 初始化完成：principal=%, cap_authz_version=%',
        principal_count, authz_count;
END;
$$;

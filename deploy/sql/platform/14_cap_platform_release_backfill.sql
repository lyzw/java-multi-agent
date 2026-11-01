-- ============================================================================
-- 14_cap_platform_release_backfill.sql
-- 关联缺口：种子写入的 cap_tool_def / cap_skill_def 未登记进 PLATFORM 发布头
--   → PublishedVersionPort 按「指针 → 发布头 → object_versions」解析
--   → 解析不出平台对象版本 → resolver.resolve() 抛
--   「对象当前无已发布版本」→ 快照写入被 fail-soft 跳过
-- 关联阶段：P0 地基交付后遗留（2026-10-31 实测发现）
--
-- 目的：为种子写入的平台级 Tool / Skill 补一个 PLATFORM 发布头，
--   并把 PLATFORM 指针移到该发布头上，使「当前生效版本」可被解析。
--
-- ⚠️ 为什么必须走发布头（而不是只依赖 cap_*_def 的 published_at）：
--    §6.3 第 5 条规定「当前生效版本**只由发布指针决定**」——
--    PublishedVersionPort 的解析路径是
--    「指针 → 发布头 → object_versions → id→精确版本」，
--    它**不读** cap_*_def.published_at。
--    种子脚本直接插 cap_*_def 并置 published_at（行级语义正确），
--    但**没有发布头** —— 于是「行级已发布」与「发布指针可见」两件事不一致，
--    解析侧一律失败。
--    这不是「种子数据错了」，而是**种子绕过了发布流程**：
--    发布流程要写版本行 + 发布头 + CAS 移指针三件事，种子只做了第一件。
--
-- ⚠️ 为什么这个脚本是「补齐」而不是「伪造发布」：
--    它登记的每一行 cap_*_def 都已具备 published_at（非 NULL）且
--    lifecycle_state = 'PUBLISHED' —— 即它们**本来就该是已发布的**，
--    只是发布头的登记步骤从未执行。
--    本脚本补的正是那一步，不是给未发布的东西「贴发布标签」。
--
-- ⚠️ requester_id 与 approver_id **必须不同**（cap_config_release_two_person_ck）：
--    「申请人与审批人不得是同一人」是双人复核的硬约束，
--    即便这是补登记也不能借「同一个人自批自过」绕过它。
--    这里用两个不同的固定标识（不是真实主体 —— 它们不对应任何 principal）。
--
-- ⚠️ 幂等：
--    · 每次执行都新建一个发布头（release_id 自增），但指针只移动一次；
--    · 已存在「登记了同样对象集合」的发布头时直接复用它，不新建。
-- ============================================================================

-- ── 1. 计算待登记的平台对象集合（已发布 + 平台命名空间）───────────────
--    ⚠️ 只收 namespace = 'platform' 的：租户私有对象走 TENANT 指针，
--       不该出现在平台发布头里。
--    ⚠️ 用内联子查询而非临时表：DO 块与外层语句不在同一事务上下文，
--       临时表在块内不可见（实测 relation does not exist）。
DO $$
DECLARE
    existing_release bigint;
    payload          jsonb;
BEGIN
    SELECT jsonb_agg(jsonb_build_object('id', object_id, 'type', object_type,
                                        'version', version)
                     ORDER BY object_type, object_id)
    INTO payload
    FROM (
        SELECT id AS object_id, 'tool' AS object_type, version
        FROM cap_tool_def
        WHERE published_at IS NOT NULL AND namespace = 'platform'
        UNION ALL
        SELECT id, 'skill', version
        FROM cap_skill_def
        WHERE published_at IS NOT NULL AND namespace = 'platform'
    ) o;

    -- ── 2. 若已有「对象集合完全相同」的发布头，直接复用（不新建）──────
    SELECT release_id INTO existing_release
    FROM cap_config_release
    WHERE scope = 'PLATFORM' AND tenant_id IS NULL
      AND object_versions = payload;

    IF existing_release IS NOT NULL THEN
        RAISE NOTICE '复用既有 PLATFORM 发布头 release_id=%', existing_release;
        UPDATE cap_config_pointer
        SET current_release_id = existing_release,
            current_publish_seq = (SELECT config_publish_seq FROM cap_config_release
                                   WHERE release_id = existing_release),
            updated_at = now()
        WHERE pointer_key = 'PLATFORM';
        RETURN;
    END IF;

    -- ── 3. 新建发布头（序号由 DB 序列分配，§6.3 第 2 条）────────────────
    INSERT INTO cap_config_release
        (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
         status, requester_id, approver_id, effective_at, created_at)
    VALUES
        (nextval(pg_get_serial_sequence('cap_config_release', 'release_id')),
         'PLATFORM', NULL, payload, md5(payload::text),
         'PUBLISHED'::cap_release_status, 'seed-backfill-requester', 'seed-backfill-approver',
         now(), now())
    RETURNING release_id INTO existing_release;

    RAISE NOTICE '新建 PLATFORM 发布头 release_id=%', existing_release;

    UPDATE cap_config_pointer
    SET current_release_id = existing_release,
        current_publish_seq = (SELECT config_publish_seq FROM cap_config_release
                               WHERE release_id = existing_release),
        updated_at = now()
    WHERE pointer_key = 'PLATFORM';
END;
$$;

-- ── 4. 自检：PLATFORM 指针下的每个对象都能解析出版本 ──────────────────
DO $$
DECLARE
    unresolved text;
BEGIN
    SELECT string_agg(object_type || ':' || object_id, ', ')
    INTO unresolved
    FROM (
        SELECT id AS object_id, 'tool' AS object_type, version
        FROM cap_tool_def
        WHERE published_at IS NOT NULL AND namespace = 'platform'
        UNION ALL
        SELECT id, 'skill', version
        FROM cap_skill_def
        WHERE published_at IS NOT NULL AND namespace = 'platform'
    ) o
    WHERE NOT EXISTS (
        SELECT 1 FROM cap_config_pointer p
        JOIN cap_config_release r ON r.release_id = p.current_release_id
        JOIN LATERAL jsonb_array_elements(r.object_versions) e ON true
        WHERE p.pointer_key = 'PLATFORM'
          AND e->>'id' = o.object_id
          AND e->>'type' = o.object_type
          AND (e->>'version')::int = o.version
    );
    IF unresolved IS NOT NULL THEN
        RAISE EXCEPTION 'PLATFORM 发布头未覆盖这些对象（仍解析不出版本）: %', unresolved;
    END IF;
    RAISE NOTICE '自检通过：PLATFORM 指针已覆盖全部已发布的平台对象';
END;
$$;

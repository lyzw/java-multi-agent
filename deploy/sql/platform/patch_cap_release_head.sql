-- =====================================================================
-- 补丁：把「能力目录已发布」这件事补完 —— 生成发布头并挂上当前指针
--
-- 【为什么需要本文件】cap_* 三张定义表（cap_skill_def / cap_tool_def /
--   cap_mcp_server_def）与 cap_agent_def 里的行都标着 lifecycle_state='PUBLISHED'，
--   但**运行期从不读 lifecycle_state**：精确版本唯一的解析口径是
--   指针 → 发布头 → object_versions → id@version（§6.3 第 2 条，
--   JdbcPublishedVersionPort.resolveFromPointer）。
--   于是「定义表里 PUBLISHED」与「运行期可用」是两件事。
--   2026-10-29 现场：PLATFORM 指针指向的发布头只有 2 个对象
--   （release_id=4：skill-e2e-profile + get_student_profile，requester='e2e-seed'，
--   一条为跑通 E2E 临时手写的行），而目录里已发布对象有 14 个
--   （5 skill + 7 tool + 2 mcp_server）。后果：除 e2e-minimax 之外的每个 Agent
--   在双写打开后都会撞上「对象当前无已发布版本」（RESOURCE_UNKNOWN），
--   闭包算不出来 → 该 Run 没有能力快照 → 执行准入判 CONFIG_UNAVAILABLE。
--   TENANT 侧更彻底：cap_config_pointer 里**一条 TENANT 行都没有**，
--   所以 resolveCurrent / CurrentAgentVersion 恒失败（当前只在
--   config.flag.cap-authorization-enforced=false 下不被调用，属潜伏 fail-closed）。
--
-- 【语义】本补丁**不新增任何定义行、不改任何已发布内容**，只做治理动作：
--   按「目录里当前全部 PUBLISHED 对象」新建一份 PLATFORM 发布头（status=PUBLISHED，
--   序号由 DB 序列分配），把 PLATFORM 指针 CAS 过去；再为每个有已发布 Agent 的租户
--   同样产出一份 TENANT 发布头 + 指针。旧发布头**一字不改**，作为历史保留
--   （§6.1 不可变 + §6.3 回滚只移指针）。
--
-- 【幂等】按 aggregate_fingerprint 判等：当前指针已指向内容相同的发布头时，
--   发布头写入 0 行、指针更新 0 行。可重复执行。
--
-- 【指纹为什么在 SQL 里逐字拼】aggregate_fingerprint =
--   'cfg_agg_v1:' + SHA-256(ConfigObjectVersionCodec.toJson(refs))，
--   而 toJson 的形态是**紧凑、键序固定 type,id,version、按 (type,id,version) 排序**。
--   jsonb 会把键重排、json 会加空格，两者算出的摘要都不是产品 codec 的那个
--   （已实测：与 codec 输出逐字节相同才作数）。
--   ⚠️ 因此 id 只能是不需要 JSON 转义的简单串；下面用正则把越界 id 挡在聚合之外，
--   宁可少一个对象，也不会拼出一份「看起来对、摘要错」的发布头。
--
-- 【范围】开发 / 复放环境的初始化补齐。生产上发布是治理动作，
--   须走平台的「申请 → 审批 → 执行」三步（/api/cap/platform/*，双人复核），
--   不应由脚本代替发布决策。
-- 【执行】⚠️ 须由**库 owner（ai_agent）**执行，不能用 platform_ops —— 实测：
--   platform_ops 对 cap_config_publish_seq 只有 USAGE+SELECT，**没有 UPDATE**，
--   而 nextval() 要推进序列 → 需要 UPDATE 权限 →
--   第 0 步与第 1/3 步直接报 permission denied for sequence。
--   （授权侧的修法是把序列 UPDATE 给 platform_ops，属另一件事，本补丁不做。）
--   两张表 FORCE ROW LEVEL SECURITY，写策略只放行本租户的 TENANT 行，
--   PLATFORM 行只有 BYPASSRLS 通道能写（这是预期的，见 03_platform_roles.sql）。
--   ⚠️ 先 dry-run（PgRun f … rollback + check）核对对象数与指纹，再 COMMIT。
--   本文件**不含事务控制**（没有 BEGIN/COMMIT），由执行方包：pgjdbc 通道天然在事务里，
--   psql 用 `-1`（单事务）或自己套 BEGIN/COMMIT。这样它既能 dry-run，
--   也能被 02_platform_seed.sql 直接内联复放（见该文件末尾的同一份附录）。
--
-- 【requester/approver 为什么留 NULL】cap_config_release_two_person_ck 是
--   「approver_id IS NULL OR requester_id IS NULL OR approver_id <> requester_id」——
--   两个 NULL 都放行（已登记为缺陷，见 docs 问题记录 §11.5）。
--   本补丁填 requester='seed-replay'、approver=NULL 只是**如实标明这是复放产物**，
--   不代表走了双人复核；真发布仍须走审批三步。
-- =====================================================================

-- ── 0. 校正发布序号序列（本补丁的前置，实测必需）───────────────────
-- ⚠️ 现场：cap_config_release 已有 config_publish_seq=1 的行（release_id=4），
--    而序列 cap_config_publish_seq 的 last_value 也还是 1 ——
--    说明那条手写发布头**没有从序列取号**（§6.3 第 2 条要求序号由序列分配）。
--    不校正的话本补丁第一次 nextval 就撞 unique 约束（dry-run 实测报错）。
--    这里只把序列**追到已有行的最大值**，不改任何历史行、不回拨、不跳号复用。
SELECT setval('public.cap_config_publish_seq',
              (SELECT coalesce(max(config_publish_seq), 0) FROM public.cap_config_release),
              true);

-- ── 1. PLATFORM 发布头：覆盖目录里全部已发布平台对象 ──────────────────
INSERT INTO public.cap_config_release
    (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
     status, requester_id, approver_id, effective_at, created_at)
SELECT nextval('public.cap_config_publish_seq'), 'PLATFORM', NULL,
       d.json_text::jsonb,
       'cfg_agg_v1:' || encode(sha256(d.json_text::bytea), 'hex'),
       'PUBLISHED', 'seed-replay', NULL, now(), now()
FROM (
    SELECT '[' || string_agg(
               '{"type":"' || o.object_type || '","id":"' || o.id
                   || '","version":' || o.version || '}',
               ',' ORDER BY o.object_type, o.id, o.version) || ']' AS json_text
    FROM (
        SELECT 'mcp_server' AS object_type, id, max(version) AS version
          FROM public.cap_mcp_server_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
        UNION ALL
        SELECT 'skill', id, max(version)
          FROM public.cap_skill_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
        UNION ALL
        SELECT 'tool', id, max(version)
          FROM public.cap_tool_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
    ) o
    WHERE o.id ~ '^[A-Za-z0-9_.:@-]+$'
) d
WHERE d.json_text IS NOT NULL
  AND NOT EXISTS (
        SELECT 1
        FROM public.cap_config_pointer p
        JOIN public.cap_config_release r ON r.release_id = p.current_release_id
        WHERE p.pointer_key = 'PLATFORM'
          AND r.aggregate_fingerprint =
              'cfg_agg_v1:' || encode(sha256(d.json_text::bytea), 'hex'));

-- ── 2. PLATFORM 指针：缺失则创建 ──────────────────────────────────────
-- ⚠️ 空库复放时这一步是**必需**的：deploy/sql 下没有任何脚本写过 cap_config_pointer，
--    开发库那条 PLATFORM 行是应用首次发布时 JdbcConfigPointerRepository
--    的 INSERT ... ON CONFLICT DO NOTHING 惰性建出来的。
--    只靠下一步的 UPDATE，在干净库里会「发布头写成功、指针 0 行」→
--    运行期照旧 RESOURCE_UNKNOWN。
INSERT INTO public.cap_config_pointer
    (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
SELECT 'PLATFORM', 'PLATFORM', NULL, h.release_id, h.config_publish_seq, now()
FROM (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'PLATFORM' AND r.status = 'PUBLISHED'
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
) h
WHERE NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p
                   WHERE p.pointer_key = 'PLATFORM');

-- ── 3. PLATFORM 指针：CAS 到内容等于当前目录的那份发布头 ─────────────
UPDATE public.cap_config_pointer p
   SET current_release_id    = h.release_id,
       current_publish_seq   = h.config_publish_seq,
       updated_at            = now()
  FROM (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'PLATFORM'
          AND r.status = 'PUBLISHED'
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"' || o.object_type || '","id":"' || o.id
                                || '","version":' || o.version || '}',
                            ',' ORDER BY o.object_type, o.id, o.version) || ']')::bytea),
                       'hex')
              FROM (
                  SELECT 'mcp_server' AS object_type, id, max(version) AS version
                    FROM public.cap_mcp_server_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
                  UNION ALL
                  SELECT 'skill', id, max(version)
                    FROM public.cap_skill_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
                  UNION ALL
                  SELECT 'tool', id, max(version)
                    FROM public.cap_tool_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
              ) o
              WHERE o.id ~ '^[A-Za-z0-9_.:@-]+$')
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
  ) h
 WHERE p.pointer_key = 'PLATFORM'
   AND p.current_release_id IS DISTINCT FROM h.release_id;

-- ── 4. TENANT 发布头：每个有已发布 Agent 的租户一份（按该租户全部已发布 Agent）──
INSERT INTO public.cap_config_release
    (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
     status, requester_id, approver_id, effective_at, created_at)
SELECT nextval('public.cap_config_publish_seq'), 'TENANT', a.tenant_id,
       a.json_text::jsonb,
       'cfg_agg_v1:' || encode(sha256(a.json_text::bytea), 'hex'),
       'PUBLISHED', 'seed-replay', NULL, now(), now()
FROM (
    SELECT g.tenant_id,
           '[' || string_agg(
                      '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                      ',' ORDER BY g.id, g.version) || ']' AS json_text
    FROM (
        SELECT tenant_id, agent_id::text AS id, max(version) AS version
          FROM public.cap_agent_def
         WHERE lifecycle_state = 'PUBLISHED'
         GROUP BY tenant_id, agent_id
    ) g
    WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$'
    GROUP BY g.tenant_id
) a
WHERE NOT EXISTS (
        SELECT 1
        FROM public.cap_config_pointer p
        JOIN public.cap_config_release r ON r.release_id = p.current_release_id
        WHERE p.pointer_key = 'TENANT:' || a.tenant_id::text
          AND r.aggregate_fingerprint =
              'cfg_agg_v1:' || encode(sha256(a.json_text::bytea), 'hex'));

-- ── 5. 每个租户一行 TENANT 指针（缺失则插入，已存在则 CAS）──────────────
INSERT INTO public.cap_config_pointer
    (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
SELECT 'TENANT:' || t.tenant_id::text, 'TENANT', t.tenant_id,
       h.release_id, h.config_publish_seq, now()
FROM (SELECT DISTINCT tenant_id FROM public.cap_agent_def
       WHERE lifecycle_state = 'PUBLISHED') t
JOIN LATERAL (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'TENANT' AND r.status = 'PUBLISHED' AND r.tenant_id = t.tenant_id
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                            ',' ORDER BY g.id, g.version) || ']')::bytea), 'hex')
              FROM (SELECT agent_id::text AS id, max(version) AS version
                      FROM public.cap_agent_def
                     WHERE lifecycle_state = 'PUBLISHED' AND tenant_id = t.tenant_id
                     GROUP BY agent_id) g
              WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$')
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
) h ON true
WHERE NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p
                   WHERE p.pointer_key = 'TENANT:' || t.tenant_id::text);

UPDATE public.cap_config_pointer p
   SET current_release_id  = h.release_id,
       current_publish_seq = h.config_publish_seq,
       updated_at          = now()
  FROM (
        SELECT r.tenant_id, r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'TENANT' AND r.status = 'PUBLISHED'
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                            ',' ORDER BY g.id, g.version) || ']')::bytea), 'hex')
              FROM (SELECT agent_id::text AS id, max(version) AS version
                      FROM public.cap_agent_def
                     WHERE lifecycle_state = 'PUBLISHED' AND tenant_id = r.tenant_id
                     GROUP BY agent_id) g
              WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$')
          AND r.config_publish_seq = (
              SELECT max(r2.config_publish_seq) FROM public.cap_config_release r2
               WHERE r2.scope = 'TENANT' AND r2.status = 'PUBLISHED'
                 AND r2.tenant_id = r.tenant_id
                 AND r2.aggregate_fingerprint = r.aggregate_fingerprint)
  ) h
 WHERE p.pointer_key = 'TENANT:' || h.tenant_id::text
   AND p.current_release_id IS DISTINCT FROM h.release_id;

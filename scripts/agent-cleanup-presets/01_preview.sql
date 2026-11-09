-- =====================================================================
-- 任务一 · 脏 Agent 盘点（纯只读，绝不写库）
-- ---------------------------------------------------------------------
-- 目标库：RDS agent_dev（platform.env / 管理后台后端实际连接的库）
-- 执行身份：platform_ops（BYPASSRLS 运维通道）。用 app_agent 会因 RLS
--           看不到跨租户数据，得到「0 行」的假绿。
-- 用法：见同目录 run.sh --preview
--
-- 本脚本回答三件事：
--   1) 脏数据有哪些、各多少、判断依据是什么；
--   2) 哪些必须保留（发布过的 / 种子夹具 / IT 专用租户）；
--   3) 哪些表会受清理影响（决定「连坐删除」范围）。
-- =====================================================================
\set ON_ERROR_STOP on

-- 安全闸：绝不误连 agent_it / study_rag / 其它库
DO $$
BEGIN
  IF current_database() <> 'agent_dev' THEN
    RAISE EXCEPTION '拒绝执行：当前库是 %，本脚本只作用于 agent_dev', current_database();
  END IF;
END $$;

SELECT current_database() AS db, current_user AS usr, now() AS checked_at;

-- ---------------------------------------------------------------------
-- 1. 总量与分布：按 public.tenant.slug 分组，识别租户性质
--    t-a      = 真实业务租户（管理后台可见）
--    t-b      = dev 种子里的跨租户夹具（02_platform_seed.sql 附录 B 写入）
--    it-*     = IT 专用租户（历史 IT 曾共用 agent_dev 时留下的残留）
-- ---------------------------------------------------------------------
\echo '===== 1. Agent 总量 / 按租户分布 ====='
SELECT count(*) AS total_agents FROM public.agent;

SELECT coalesce(t.slug, '<无租户登记>') AS tenant_slug,
       count(*) AS agents,
       min(a.created_at)::date AS first_day,
       max(a.created_at)::date AS last_day
FROM public.agent a
LEFT JOIN public.tenant t ON t.tenant_id = a.tenant_id
GROUP BY 1 ORDER BY agents DESC;

-- ---------------------------------------------------------------------
-- 2. 待清理候选（方案 A 默认范围：仅 t-a）
--    判据（三条同时成立，避免误删）：
--      (a) 名称带批量测试前缀 + UUID 后缀（agent-page-* / agent-dup-* /
--          ep-* / rc-* 等，来自 Agent 管理接口 IT）；
--      (b) 不在 3 个正式 Agent 白名单内；
--      (c) 没有 cap_agent_def 行 —— 即从未进入「发布」链路。
--    补充信号：description 为 NULL/'desc'/'d'/'seeded'，且均不在
--              TENANT 发布头 cap_config_release(object_versions) 内。
-- ---------------------------------------------------------------------
\echo '===== 2. 待清理候选（t-a 测试件）汇总 ====='
WITH targets AS (
  SELECT a.agent_id, a.tenant_id, a.name, a.description, a.created_at
  FROM public.agent a
  JOIN public.tenant t ON t.tenant_id = a.tenant_id
  WHERE t.slug = 't-a'
    AND a.name NOT IN ('assignment-copilot', 'math-tutor', 'profile-reviewer')
    AND a.name ~ '^(agent-|ep-|rc-|rc-ns-|probe-|stat-it-|test-|it-)'
    AND NOT EXISTS (SELECT 1 FROM public.cap_agent_def c WHERE c.agent_id = a.agent_id)
)
SELECT count(*) AS candidates,
       count(*) FILTER (WHERE description IS NULL OR btrim(description) = '') AS blank_description,
       min(created_at)::date AS first_day, max(created_at)::date AS last_day
FROM targets;

\echo '===== 2b. 待清理候选明细（全量可导出核对，这里按名称前缀聚合）====='
WITH targets AS (
  SELECT a.agent_id, a.name
  FROM public.agent a
  JOIN public.tenant t ON t.tenant_id = a.tenant_id
  WHERE t.slug = 't-a'
    AND a.name NOT IN ('assignment-copilot', 'math-tutor', 'profile-reviewer')
    AND a.name ~ '^(agent-|ep-|rc-|rc-ns-|probe-|stat-it-|test-|it-)'
    AND NOT EXISTS (SELECT 1 FROM public.cap_agent_def c WHERE c.agent_id = a.agent_id)
)
SELECT split_part(name, '-', 1) || '-' || split_part(name, '-', 2) AS name_prefix,
       count(*) AS rows
FROM targets GROUP BY 1 ORDER BY 2 DESC, 1;

\echo '===== 2c. 待清理候选逐行清单（前 30 行，完整清单用 run.sh 导出 CSV）====='
SELECT a.agent_id, a.name, coalesce(a.description, '<NULL>') AS description,
       a.created_at,
       (SELECT count(*) FROM public.agent_version v WHERE v.agent_id = a.agent_id) AS versions,
       (SELECT count(*) FROM public.execution_snapshot s
          JOIN public.agent_version v ON v.version_id = s.version_id
         WHERE v.agent_id = a.agent_id) AS snapshots
FROM public.agent a
JOIN public.tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug = 't-a'
  AND a.name NOT IN ('assignment-copilot', 'math-tutor', 'profile-reviewer')
  AND a.name ~ '^(agent-|ep-|rc-|rc-ns-|probe-|stat-it-|test-|it-)'
  AND NOT EXISTS (SELECT 1 FROM public.cap_agent_def c WHERE c.agent_id = a.agent_id)
ORDER BY a.created_at, a.name LIMIT 30;

-- ---------------------------------------------------------------------
-- 3. 必须保留清单
-- ---------------------------------------------------------------------
\echo '===== 3. 保留清单（3 正式 + t-b 种子夹具 + IT 租户分布）====='
SELECT t.slug, a.name, a.description,
       (SELECT count(*) FROM public.agent_version v WHERE v.agent_id = a.agent_id) AS versions,
       (SELECT count(*) FROM public.cap_agent_def c WHERE c.agent_id = a.agent_id) AS cap_defs,
       (SELECT count(*) FROM public.execution_snapshot s
          JOIN public.agent_version v ON v.version_id = s.version_id
         WHERE v.agent_id = a.agent_id) AS snapshots
FROM public.agent a JOIN public.tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug IN ('t-a', 't-b')
  AND (a.name IN ('assignment-copilot', 'math-tutor', 'profile-reviewer', 'agent1'))
ORDER BY t.slug, a.name;

\echo '===== 3b. 当前 TENANT 发布头实际引用的 Agent（运行期唯一可用集）====='
SELECT p.pointer_key, p.current_release_id, r.status, r.object_versions
FROM public.cap_config_pointer p
JOIN public.cap_config_release r ON r.release_id = p.current_release_id
WHERE p.scope = 'TENANT';

-- ---------------------------------------------------------------------
-- 4. 异常体检：重复 / 空字段 / 无版本 / 缺执行快照 / 孤儿
-- ---------------------------------------------------------------------
\echo '===== 4. 异常体检 ====='
SELECT '同名重复(受唯一约束保证为0)' AS check_item,
       (SELECT count(*) FROM (SELECT 1 FROM public.agent GROUP BY tenant_id, name HAVING count(*) > 1) d) AS cnt
UNION ALL SELECT 'name 空白',
       (SELECT count(*) FROM public.agent WHERE btrim(name) = '')
UNION ALL SELECT 'description 空/NULL',
       (SELECT count(*) FROM public.agent WHERE description IS NULL OR btrim(description) = '')
UNION ALL SELECT '无版本(不可运行)',
       (SELECT count(*) FROM public.agent a WHERE NOT EXISTS
          (SELECT 1 FROM public.agent_version v WHERE v.agent_id = a.agent_id))
UNION ALL SELECT '已发布版本缺 execution_snapshot(不可运行)',
       (SELECT count(*) FROM public.agent_version v WHERE v.published_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM public.execution_snapshot s WHERE s.version_id = v.version_id))
UNION ALL SELECT 'cap_agent_def 孤儿',
       (SELECT count(*) FROM public.cap_agent_def c WHERE NOT EXISTS
          (SELECT 1 FROM public.agent a WHERE a.agent_id = c.agent_id))
UNION ALL SELECT 'agent_version 孤儿',
       (SELECT count(*) FROM public.agent_version v WHERE NOT EXISTS
          (SELECT 1 FROM public.agent a WHERE a.agent_id = v.agent_id));

-- ---------------------------------------------------------------------
-- 4b. 可删性分组（**本盘点最重要的结论**）
--     execution_snapshot_version_fk 是 NO ACTION 且不可延迟；
--     execution_snapshot 又有 forbid_snapshot_mutation() 触发器，
--     UPDATE/DELETE 一律抛异常 ⇒ 挂过快照的 Agent 版本物理删不掉。
--     现场：104 个测试件挂 136 条快照、关联 692 个历史 Run。
-- ---------------------------------------------------------------------
\echo '===== 4b. 224 个候选：可物理删除 vs 快照阻断 ====='
WITH x AS (
  SELECT a.agent_id FROM public.agent a
  JOIN public.tenant t ON t.tenant_id = a.tenant_id
  WHERE t.slug = 't-a'
    AND a.name NOT IN ('assignment-copilot', 'math-tutor', 'profile-reviewer')
    AND a.name ~ '^(agent-|ep-|rc-|rc-ns-|probe-|stat-it-|test-|it-)'
    AND NOT EXISTS (SELECT 1 FROM public.cap_agent_def c WHERE c.agent_id = a.agent_id)
), s AS (
  SELECT x.agent_id, count(DISTINCT s.snapshot_id) AS snaps,
         count(DISTINCT r.run_id) AS runs
  FROM x
  LEFT JOIN public.agent_version v ON v.agent_id = x.agent_id
  LEFT JOIN public.execution_snapshot s ON s.version_id = v.version_id
  LEFT JOIN public.run r ON r.snapshot_id = s.snapshot_id
  GROUP BY x.agent_id
)
SELECT CASE WHEN snaps = 0 THEN 'DELETE_OK(可物理删)' ELSE 'SNAPSHOT_BLOCKED(只能归档)' END AS bucket,
       count(*) AS agents, sum(snaps) AS snapshots, sum(runs) AS linked_runs
FROM s GROUP BY 1 ORDER BY 1;

\echo '===== 4c. 候选是否被任何 PUBLISHED TENANT 发布头引用（应为 0）====='
SELECT count(*) AS referenced_by_publish_head
FROM public.agent a
JOIN public.tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug = 't-a'
  AND a.name NOT IN ('assignment-copilot', 'math-tutor', 'profile-reviewer')
  AND a.name ~ '^(agent-|ep-|rc-|rc-ns-|probe-|stat-it-|test-|it-)'
  AND EXISTS (SELECT 1 FROM public.cap_config_release r
              WHERE r.scope = 'TENANT' AND r.status = 'PUBLISHED'
                AND r.object_versions::text LIKE '%' || a.agent_id::text || '%');

-- ---------------------------------------------------------------------
-- 5. 清理的连坐范围
--    agent_version / cap_agent_def 有 ON DELETE CASCADE 指向 agent；
--    cap_agent_child_ref 有 CASCADE 指向 cap_agent_def。
--    ⚠️ execution_snapshot 有 NO ACTION 外键 + 禁止删除触发器，
--       它是历史证据，本方案**不删**（所以上面才有 SNAPSHOT_BLOCKED 分组）。
-- ---------------------------------------------------------------------
\echo '===== 5. 相关子表行数 ====='
SELECT 'agent_version' AS tbl, count(*) FROM public.agent_version
UNION ALL SELECT 'cap_agent_def', count(*) FROM public.cap_agent_def
UNION ALL SELECT 'cap_agent_child_ref', count(*) FROM public.cap_agent_child_ref
UNION ALL SELECT 'execution_snapshot(不可删,有 forbid 触发器)', count(*) FROM public.execution_snapshot
UNION ALL SELECT 'execution_capability_snapshot', count(*) FROM public.execution_capability_snapshot
UNION ALL SELECT 'agent_invocation(运行史,保留)', count(*) FROM public.agent_invocation
UNION ALL SELECT 'audit_record(审计,保留)', count(*) FROM public.audit_record;

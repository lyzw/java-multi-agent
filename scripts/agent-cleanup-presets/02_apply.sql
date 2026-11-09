-- =====================================================================
-- 任务一 + 任务二 · 彻底清理脏 Agent + 预制常用 Agent（**确认后才执行**）
-- ---------------------------------------------------------------------
-- 目标库：agent_dev（本地 5433 或 RDS，由 run.sh 选择连接）
-- 执行身份：
--   · 本地 5433：agent（超级用户）→ 可禁用不可变触发器，彻底删除
--   · RDS     ：ai_agent（表 owner，需 AI_AGENT_PASSWORD）或 platform_ops
--              （platform_ops 非表 owner，禁用触发器会被拒 → 仅能删无快照者，
--                有快照者保留并提示，需改用 ai_agent 重跑）
-- 用法：run.sh --target local --apply   或   run.sh --target rds --apply
--       mode 通过 -v mode=local|rds 传入（run.sh 负责）
--
-- 彻底删除链路（授权依据：用户确认「彻底删除 + 授权禁用不可变触发器」）：
--   run(tenant_id,snapshot_id) → execution_snapshot → agent_version → agent
--   （agent_version/cap_agent_def 对 agent 为 ON DELETE CASCADE；
--     cap_agent_child_ref 对 cap_agent_def 为 CASCADE）
--   顺序：禁用快照触发器 → 删 run → 删 execution_snapshot → 删 agent(级联) → 启触发器
--   ⚠️ run.snapshot_id 为 NOT NULL 且有终态守卫触发器，但守卫只拦 UPDATE 状态迁移，
--      DELETE run 不被拦，故可安全删除（仅删指向待清快照的 run）。
-- =====================================================================
\set ON_ERROR_STOP on
-- 安全闸 1：目标库名（两个库都叫 agent_dev，防误连 agent_it / study_rag）
DO $$
BEGIN
  IF current_database() <> 'agent_dev' THEN
    RAISE EXCEPTION '拒绝执行：当前库是 %，本脚本只作用于 agent_dev', current_database();
  END IF;
END $$;

-- 安全闸 2：执行身份必须 BYPASSRLS，否则 RLS 会让清理静默漏行（假绿）
DO $$
BEGIN
  IF NOT (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION '拒绝执行：当前角色 % 无 BYPASSRLS，清理会静默漏行', current_user;
  END IF;
END $$;

-- mode 由 run.sh 通过 -v mode=local|rds 注入；缺失会在下方 DO 块中报错。
-- 两库都叫 agent_dev，务必通过 run.sh 选择连接与目标模式，不要手动直连误执行。

-- 通过临时表把 mode 传入 DO 块（psql 的 :'name' 替换在 $$ 美元引号字符串内不生效，
-- 故在块外用 :'mode' 写入临时表，块内再读取）
CREATE TEMP TABLE ops_mode (m text);
INSERT INTO ops_mode (m) VALUES (:'mode');

DO $$
DECLARE
  v_ta_tenant   uuid;
  v_can_disable boolean := false;
  v_targets     int;
  v_blocked     int;
  v_deletable   int;
  v_json        text;
  v_mode        text;
  v_fp          text;
  v_release_id  bigint;
  v_seq         bigint;
BEGIN
  -- 从临时表读取 run.sh 传入的 mode（psql 变量替换在美元引号字符串内不生效）
  SELECT m INTO v_mode FROM ops_mode;
  -- 1) 解析 t-a 租户（两库 tenant_id 不同，运行时解析，绝不硬编码）
  SELECT tenant_id INTO v_ta_tenant FROM public.tenant WHERE slug = 't-a';
  IF v_ta_tenant IS NULL THEN
    RAISE EXCEPTION '找不到 t-a 租户，终止';
  END IF;

  -- 2) 固化待清理目标集（临时表，事务结束即消失）
  --    安全网：任何被 PUBLISHED 发布头引用的 agent 一律不删。
  CREATE TEMP TABLE ops_targets (agent_id uuid, tenant_id uuid) ON COMMIT DROP;
  IF v_mode = 'local' THEN
    -- 本地：清掉全部 agent（1806 行的开发库，无官方种子），仅保留 5 个预制名
    INSERT INTO ops_targets (agent_id, tenant_id)
    SELECT a.agent_id, a.tenant_id FROM public.agent a
    WHERE a.name NOT IN ('mistake-diagnosis','knowledge-explainer','practice-drill','weekly-review','homework-qa')
      AND NOT EXISTS (SELECT 1 FROM public.cap_config_release r
                      WHERE r.status='PUBLISHED' AND r.object_versions::text LIKE '%'||a.agent_id::text||'%');
  ELSE
    -- RDS：清 t-a 中除 3 个官方种子外的测试件 + 所有 it-* 租户测试件；保留 5 预制名
    INSERT INTO ops_targets (agent_id, tenant_id)
    SELECT a.agent_id, a.tenant_id FROM public.agent a
    JOIN public.tenant t ON t.tenant_id = a.tenant_id
    WHERE (
            (t.slug = 't-a'
             AND a.name NOT IN ('assignment-copilot','math-tutor','profile-reviewer',
                                'mistake-diagnosis','knowledge-explainer','practice-drill',
                                'weekly-review','homework-qa'))
         OR t.slug LIKE 'it-%'
          )
      AND NOT EXISTS (SELECT 1 FROM public.cap_config_release r
                      WHERE r.status='PUBLISHED' AND r.object_versions::text LIKE '%'||a.agent_id::text||'%');
  END IF;

  SELECT count(*) INTO v_targets FROM ops_targets;
  IF v_targets = 0 THEN
    RAISE NOTICE '没有需要清理的目标（可能已清过），继续预制与发布头重建';
  END IF;

  -- 3) 尝试禁用不可变触发器（彻底删除的硬前置）。无表 owner 权限则降级为部分删除。
  BEGIN
    ALTER TABLE public.execution_snapshot DISABLE TRIGGER trg_snapshot_immutable;
    v_can_disable := true;
  EXCEPTION WHEN OTHERS THEN
    v_can_disable := false;
    RAISE NOTICE '⚠ 无法禁用不可变触发器（% 不是表 owner）：快照阻断的记录将保留，需改用 ai_agent 重跑', current_user;
  END;

  -- 4) 计算目标版本与快照
  CREATE TEMP TABLE ops_ver (version_id uuid) ON COMMIT DROP;
  INSERT INTO ops_ver SELECT version_id FROM public.agent_version WHERE agent_id IN (SELECT agent_id FROM ops_targets);

  CREATE TEMP TABLE ops_snap (snapshot_id uuid) ON COMMIT DROP;
  INSERT INTO ops_snap SELECT snapshot_id FROM public.execution_snapshot WHERE version_id IN (SELECT version_id FROM ops_ver);

  CREATE TEMP TABLE ops_blocked (agent_id uuid) ON COMMIT DROP;
  INSERT INTO ops_blocked (agent_id)
  SELECT DISTINCT v.agent_id FROM public.agent_version v
  JOIN public.execution_snapshot s ON s.version_id = v.version_id
  WHERE v.agent_id IN (SELECT agent_id FROM ops_targets);
  SELECT count(*) INTO v_blocked FROM ops_blocked;
  SELECT count(*) INTO v_deletable FROM ops_targets WHERE agent_id NOT IN (SELECT agent_id FROM ops_blocked);

  -- 5) 删 run（指向待清快照的；run 守卫触发器只拦 UPDATE，DELETE 放行）
  DELETE FROM public.run r
  WHERE r.snapshot_id IN (SELECT snapshot_id FROM ops_snap);

  -- 6) 删快照 + agent（级联 agent_version / cap_agent_def / cap_agent_child_ref）
  IF v_can_disable THEN
    DELETE FROM public.execution_snapshot WHERE version_id IN (SELECT version_id FROM ops_ver);
    DELETE FROM public.agent WHERE agent_id IN (SELECT agent_id FROM ops_targets);
    RAISE NOTICE '✅ 彻底删除：% 个 agent（含 % 个快照阻断者）已物理删除', v_targets, v_blocked;
  ELSE
    -- 降级：仅删无快照者；有快照者保留，待 ai_agent 重跑
    DELETE FROM public.execution_snapshot
      WHERE version_id IN (SELECT version_id FROM ops_ver
                           WHERE version_id NOT IN (SELECT v.version_id FROM ops_blocked b JOIN public.agent_version v ON v.agent_id=b.agent_id));
    DELETE FROM public.agent
      WHERE agent_id IN (SELECT agent_id FROM ops_targets WHERE agent_id NOT IN (SELECT agent_id FROM ops_blocked));
    RAISE NOTICE '⚠ 部分删除：% 个无快照 agent 已删；% 个有快照 agent 因无禁用触发器权限而保留（请用 ai_agent 重跑彻底删除）', v_deletable, v_blocked;
  END IF;

  -- 7) 恢复触发器
  IF v_can_disable THEN
    ALTER TABLE public.execution_snapshot ENABLE TRIGGER trg_snapshot_immutable;
  END IF;

  -- ===================================================================
  -- Part 3 · 预制 5 个常用 Agent（初中生错题归因辅导）
  --   四表齐全：agent / agent_version / execution_snapshot / cap_agent_def(PUBLISHED)
  --   否则「界面有、运行期不可用」。UUID 固定 ⇒ 幂等。
  -- ===================================================================
  CREATE TEMP TABLE ops_preset (agent_id uuid, version_id uuid, name text, description text,
                                roles jsonb, skills jsonb, tags jsonb) ON COMMIT DROP;
  INSERT INTO ops_preset VALUES
   ('3a402413-0f17-4522-80a9-b4a00a0bf0ba','6799f1ce-4227-4625-bdf7-462745400b1c',
    'mistake-diagnosis','错题归因分析师：定位错题的知识薄弱点、思维误区与计算/审题失误，输出归因标签与证据链',
    '["student","teacher"]','["skill-profile-view","skill-report-brief"]','["diagnosis","wrong-answer"]'),
   ('c5530511-d851-4034-ba62-79e0fae46ba8','bab5cb31-2e86-488f-89eb-5895a8e3e46f',
    'knowledge-explainer','知识点精讲师：针对归因出的薄弱知识点做分层讲解、类比与示例，不直接给答案',
    '["student","teacher"]','["skill-profile-view"]','["explanation","knowledge"]'),
   ('7134b483-db1b-491e-9896-8bb42b5dd5c3','07dabd13-cbb1-4ded-8187-a1a7926770d5',
    'practice-drill','变式练习生成师：按归因结果生成同类变式题与阶梯练习，附难度梯度与自查提示',
    '["student","teacher"]','["skill-profile-view","skill-report-brief"]','["practice","drill"]'),
   ('5e04d727-a3b8-4f72-bd65-4acab610155b','b6fd561d-bb60-4586-830f-b20120188246',
    'weekly-review','学情周报教练：汇总阶段错题与练习表现，生成面向家长/老师的周报与下周学习建议',
    '["teacher"]','["skill-profile-view","skill-report-brief","skill-include-guardian"]','["report","review"]'),
   ('eb39e4c1-8ec9-4ae2-91f3-9e22a91a5a2b','aa1cfb8d-6531-4c01-b36a-33acfe078c6d',
    'homework-qa','作业答疑助手：单题答疑与解题步骤拆解，标注所依据的课本知识点，输出可复盘步骤',
    '["student","teacher"]','["skill-profile-view","skill-report-brief"]','["qa","homework"]');

  -- 3.1 目录行
  INSERT INTO public.agent (agent_id, tenant_id, name, description, created_at, updated_at)
  SELECT p.agent_id, v_ta_tenant, p.name, p.description, now(), now()
  FROM ops_preset p ON CONFLICT (agent_id) DO NOTHING;

  -- 3.2 版本行（不可变 definition 快照）
  INSERT INTO public.agent_version (version_id, tenant_id, agent_id, version_number, definition, published_at, created_at)
  SELECT p.version_id, v_ta_tenant, p.agent_id, 1,
         jsonb_build_object('name',p.name,'agentId',p.name,'description',p.description,
            'roles',p.roles,'enabledSkills',p.skills,'skillBundles','[]'::jsonb,
            'capabilityTags',p.tags,'overrides','{}'::jsonb,'orchestration','{}'::jsonb),
         now(), now()
  FROM ops_preset p ON CONFLICT (version_id) DO NOTHING;

  -- 3.3 执行快照（Run 创建链 INNER JOIN 必需）
  INSERT INTO public.execution_snapshot (tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config)
  SELECT v_ta_tenant, p.version_id, v.definition, '{}'::jsonb, '{}'::jsonb,
         '{"maxCostPerRun":"2.00","maxLlmCalls":12,"maxSteps":20,"approvalRequired":"OPTIONAL"}'::jsonb
  FROM ops_preset p JOIN public.agent_version v ON v.version_id = p.version_id
  WHERE NOT EXISTS (SELECT 1 FROM public.execution_snapshot s WHERE s.version_id = p.version_id);

  -- 3.4 结构化定义（PUBLISHED；published_at 非空满足触发器对齐要求）
  INSERT INTO public.cap_agent_def
      (tenant_id, agent_id, version, name, description, roles, enabled_skills,
       skill_bundles, capability_tags, inherits, overrides, config_fingerprint,
       schema_version, created_at, updated_at, published_at, lifecycle_state, orchestration)
  SELECT v_ta_tenant, p.agent_id, 1, p.name, p.description,
         p.roles, p.skills, '[]'::jsonb, p.tags, NULL, '{}'::jsonb,
         'pending_v1:' || encode(sha256(convert_to(
              coalesce((SELECT '[' || string_agg(value, ', ' ORDER BY value) || ']'
                        FROM jsonb_array_elements_text(p.roles)), '[]') || chr(31)
           || coalesce((SELECT '[' || string_agg(value, ', ' ORDER BY value) || ']'
                        FROM jsonb_array_elements_text(p.skills)), '[]') || chr(31)
           || '[]' || chr(31)
           || coalesce((SELECT '[' || string_agg(value, ', ' ORDER BY value) || ']'
                        FROM jsonb_array_elements_text(p.tags)), '[]') || chr(31)
           || '{}' || chr(31) || '{}' || chr(31)
           || p.name || chr(31) || p.description || chr(31), 'UTF8')), 'hex'),
         1, now(), now(), now(), 'PUBLISHED', '{}'::jsonb
  FROM ops_preset p ON CONFLICT DO NOTHING;

  -- ===================================================================
  -- Part 4 · 重建 t-a 的 TENANT 发布头 + 指针（运行期唯一可用入口）
  --   本地：原无指针 → INSERT；RDS：原指针指向 release 2 → UPDATE。UPSERT 兼容两种。
  -- ===================================================================
  SELECT '[' || string_agg('{"type":"agent","id":"'||agent_id::text||'","version":'||version||'}',
                            ',' ORDER BY agent_id::text, version) || ']'
    INTO v_json
  FROM (SELECT agent_id, max(version) AS version FROM public.cap_agent_def
        WHERE lifecycle_state='PUBLISHED' AND tenant_id=v_ta_tenant GROUP BY agent_id) g;

  v_fp := 'cfg_agg_v1:' || encode(sha256(v_json::bytea), 'hex');

  INSERT INTO public.cap_config_release
      (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
       status, requester_id, approver_id, effective_at, created_at)
  SELECT nextval('public.cap_config_publish_seq'), 'TENANT', v_ta_tenant,
         v_json::jsonb, v_fp, 'PUBLISHED', 'agent-preset', NULL, now(), now()
  WHERE v_json IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM public.cap_config_release r
                    WHERE r.scope='TENANT' AND r.tenant_id=v_ta_tenant AND r.aggregate_fingerprint=v_fp)
  RETURNING release_id INTO v_release_id;

  IF v_release_id IS NULL AND v_json IS NOT NULL THEN
    SELECT release_id INTO v_release_id FROM public.cap_config_release
    WHERE scope='TENANT' AND tenant_id=v_ta_tenant AND aggregate_fingerprint=v_fp
    ORDER BY config_publish_seq DESC LIMIT 1;
  END IF;

  IF v_release_id IS NOT NULL THEN
    SELECT config_publish_seq INTO v_seq FROM public.cap_config_release WHERE release_id=v_release_id;
    INSERT INTO public.cap_config_pointer
        (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
    VALUES ('TENANT:'||v_ta_tenant, 'TENANT', v_ta_tenant, v_release_id, v_seq, now())
    ON CONFLICT (pointer_key) DO UPDATE
       SET current_release_id = EXCLUDED.current_release_id,
           current_publish_seq = EXCLUDED.current_publish_seq,
           updated_at = now();
  END IF;

  RAISE NOTICE '✅ t-a 发布头已重建，引用 % 个可用 Agent', jsonb_array_length(v_json::jsonb);
END $$;

-- ---------------------------------------------------------------------
-- 验收（提交后只读）
-- ---------------------------------------------------------------------
\echo '===== 验收 1：t-a Agent 分类计数 ====='
SELECT CASE
         WHEN a.name IN ('assignment-copilot','math-tutor','profile-reviewer') THEN 'SEED'
         WHEN a.name IN ('mistake-diagnosis','knowledge-explainer','practice-drill','weekly-review','homework-qa') THEN 'PRESET'
         ELSE 'OTHER' END AS kind, count(*)
FROM public.agent a JOIN public.tenant t ON t.tenant_id=a.tenant_id WHERE t.slug='t-a'
GROUP BY 1 ORDER BY 1;

\echo '===== 验收 2：5 个预制 Agent 四表齐全（每行应全是 1）====='
SELECT a.name,
       (SELECT count(*) FROM public.agent_version v WHERE v.agent_id=a.agent_id) AS ver,
       (SELECT count(*) FROM public.execution_snapshot s JOIN public.agent_version v ON v.version_id=s.version_id WHERE v.agent_id=a.agent_id) AS snap,
       (SELECT count(*) FROM public.cap_agent_def c WHERE c.agent_id=a.agent_id) AS capdef
FROM public.agent a
WHERE a.tenant_id = (SELECT tenant_id FROM public.tenant WHERE slug='t-a')
  AND a.name IN ('mistake-diagnosis','knowledge-explainer','practice-drill','weekly-review','homework-qa')
ORDER BY a.name;

\echo '===== 验收 3：当前 t-a TENANT 发布头可用 Agent 数 ====='
SELECT p.current_release_id, r.status, jsonb_array_length(r.object_versions) AS published_agents
FROM public.cap_config_pointer p
JOIN public.cap_config_release r ON r.release_id=p.current_release_id
WHERE p.pointer_key = 'TENANT:' || (SELECT tenant_id FROM public.tenant WHERE slug='t-a');

\echo '===== 验收 4：两库总 agent 数（确认清理生效）====='
SELECT count(*) AS total_agents FROM public.agent;

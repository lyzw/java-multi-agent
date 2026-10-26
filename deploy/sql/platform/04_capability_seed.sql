-- =====================================================================
-- platform 能力目录种子数据 —— 生产级 Agent / Skill / Tool / MCP 初始化
-- =====================================================================
--
-- 【用途】
--   在 platform 库（已执行 01_platform_ddl.sql，可选 02_platform_seed.sql）
--   之上建立**生产级能力目录基线**：
--
--   · 平台对象（cap_tool_def / cap_skill_def / cap_mcp_server_def，无 RLS：
--       2 个 MCP Server（http + token / http + oauth，凭据仅存 credential_ref）
--       6 个 Tool（native ×2 / HTTP ×2 / MCP 绑定 ×2，三类绑定各覆盖）
--       4 个 Skill（declared_tools / declared_mcp 闭包自洽）
--   · 平台发布：1 个 PLATFORM release + cap_config_pointer('PLATFORM') 行
--     + cap_decision_log PUBLISH 审计旁路行
--     （无指针 = RaVn 解析 fail-closed，种子必须补上指针才算「可用基线」）
--   · 租户 Agent：目标租户（:tenant_slug）3 个 Agent：
--       math-tutor / profile-reviewer（LEAF）+ assignment-copilot（SEQUENTIAL 父级，
--       声明两个子 Agent 引用，固定 v1）
--     每个写入 agent / agent_version(v1 快照) / cap_agent_def(v1 PUBLISHED)
--     / cap_agent_child_ref（父→子投影边）。
--
-- 【⚠️ 角色 / 权限通道】
--   · cap_tool_def / cap_skill_def / cap_mcp_server_def**无 RLS**，任意授权角色可写；
--   · cap_config_release / cap_config_pointer 为租户 RLS 表，其 **PLATFORM 作用
--     域行必须由 BYPASSRLS 角色（platform_ops）写入** —— 本脚本应以下列方式执行：
--       psql -U platform_ops -d agent_dev -v tenant_slug=t-a -f 04_capability_seed.sql
--   · 租户部分（agent / agent_version / cap_agent_def / cap_agent_child_ref）
--     依赖会话 GUC `app.tenant_id`（RLS 策略），故必须 set_config(..., false)
--     设**会话级**（psql autocommit，local=true 提交即丢）。
--
-- 【幂等】
--   · def 行：ON CONFLICT (id, version) DO NOTHING —— 已发布版本行不可变（V15）；
--   · published_at / lifecycle_state 只对 inserted 且尚未发布的行抬升
--     （UPDATE 带 published_at IS NULL 守卫，重跑不触碰不可变触发器）；
--   · release / pointer：以「PLATFORM 指针尚未指向任何发布」为守卫，
--     已有指针 → 整段跳过（指针是「当前发布」的唯一事实，绝不覆盖）；
--   · agent 行按 (tenant_id, name) 冲突跳过；agent_version / cap_agent_def /
--     cap_agent_child_ref ON CONFLICT DO NOTHING。
--
-- 【校验】
--   末段会断言各计数并 RAISE EXCEPTION（fail loudly），不复位不一致现场。
--   校验块**带着**租户上下文读 RLS 表，读完后复位（与 02 同纪律）。
--
-- 【注意】psql 变量替换不发生在 $$ 体内 —— 传参一律 set_config 进会话 GUC。
-- =====================================================================

\set ON_ERROR_STOP on

-- ── 可覆盖参数（slug 需与应用侧 AGENT_TENANT_SLUGS 一致，默认 t-a）────────
\if :{?tenant_slug}
\else
  \set tenant_slug 't-a'
\endif

\echo '── 能力目录种子参数 ──'
\echo '  目标租户 slug: ' :tenant_slug

-- =====================================================================
-- 1. GUC 桥接（psql 变量 → 会话 GUC，供 $$ 体读取）
-- =====================================================================

-- 租户上下文：本脚本的后半段（租户 Agent）依赖它写 RLS 表。
-- ⚠️ set_config(..., false) = 会话级；psql 默认 autocommit，local 值改一句就丢。
SELECT set_config('seed.tenant_slug', :'tenant_slug', false);

-- 校验目标租户存在，并**立刻**把 app.tenant_id 设为该租户（会话级）。
-- 不存在 → 直接抛错（后续所有租户写都无意义）。
SELECT set_config('app.tenant_id',
                  (SELECT tenant_id::text FROM public.tenant
                    WHERE slug = current_setting('seed.tenant_slug', TRUE)),
                  false);
DO $$
BEGIN
    IF current_setting('app.tenant_id', TRUE) IS NULL
       OR current_setting('app.tenant_id', TRUE) = '' THEN
        RAISE EXCEPTION '种子失败：租户 % 不存在', current_setting('seed.tenant_slug', TRUE);
    END IF;
END;
$$;

-- =====================================================================
-- 2. 平台对象：MCP Server（2 个）
-- =====================================================================
-- ⚠️ 凭据**仅存 credential_ref**（vault 定位符），明文禁入库（06 §5）。
-- exposed_tool_allowlist 是**固定清单**：null（未定）= 连接级授权兜底，
-- [] = 显式全禁 —— 两者语义不同，种子用都是有值的清单（最小权限）。
-- =====================================================================

INSERT INTO cap_mcp_server_def
    (id, version, transport, endpoint, command,
     auth_kind, trust_tier, exposed_tool_allowlist, connection_risk,
     dynamic_discovery, credential_ref, config_fingerprint,
     schema_version, published_at, lifecycle_state)
VALUES
    -- 学生档案查询：内部系统，token 认证，固定暴露两个只读操作
    ('mcp-student-profile', 1,
     'http', 'https://mcp-profile.internal-studykid.cn/mcp', NULL,
     'token', 'internal',
     '["lookupProfile","lookupGuardian"]'::jsonb,
     'READ', FALSE,
     'vault://kv/platform/mcp-student-profile#api_token',
     encode(sha256('seed:mcp-student-profile:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),
    -- 课程日历：合作方系统，OAuth2，暴露读写各一
    ('mcp-course-calendar', 1,
     'http', 'https://mcp-calendar.partner-studykid.cn/mcp', NULL,
     'oauth', 'partner',
     '["getSchedule","getEvents"]'::jsonb,
     'READ', FALSE,
     'vault://kv/platform/mcp-course-calendar#oauth_client',
     encode(sha256('seed:mcp-course-calendar:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED')
ON CONFLICT (id, version) DO NOTHING;

-- =====================================================================
-- 3. 平台对象：Tool（6 个 · native / http / mcp 三类绑定各覆盖）
-- =====================================================================
-- ⚠️ V28 起三类绑定约束：
--   kind='mcp'   → mcp_server_id + mcp_tool_name 必填，http_* 必空
--   kind='http'  → http_method + http_url + http_timeout_ms 必填，
--                  mcp_* 必空（本 DB 的 http 绑定防线）
--   kind='native'→ mcp_* 与 http_* 都必空
-- ⚠️ input_schema 的结构约束 + param_policies 的参数域收紧是两层门禁：
--   缺省 {} 表示「无结构约束」但**不代表**允许任意参数（V15 §4.3）。
-- =====================================================================

INSERT INTO cap_tool_def
    (id, version, name, kind, risk_level,
     mcp_server_id, mcp_tool_name, http_method, http_url, http_timeout_ms,
     input_schema, param_policies, namespace, config_fingerprint,
     schema_version, published_at, lifecycle_state)
VALUES
    -- native ×2 --------------------------------------------------------
    ('tool-profile-read', 1,
     '档案读取',
     'native', 'READ', NULL, NULL, NULL, NULL, NULL,
     '{"type":"object","required":["studentId"],"properties":{"studentId":{"type":"string","description":"学生编码"}}}'::jsonb,
     '[]'::jsonb, 'platform',
     encode(sha256('seed:tool-profile-read:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),
    ('tool-profile-update', 1,
     '档案修改',
     'native', 'WRITE_PROFILE', NULL, NULL, NULL, NULL, NULL,
     '{"type":"object","required":["studentId","field","value"],"properties":{"studentId":{"type":"string"},"field":{"type":"string","description":"允许更新的档案字段"},"value":{"type":"string"}}}'::jsonb,
     -- 参数域收紧示例（04 §4.3）：field 仅允许「监护人手机号」一个受控值——
     -- 任何越出清单的字段名在准入阶段（Phase A）即被拒。
     '[{"param":"field","op":"in_allowlist","value":["guardianPhone"]}]'::jsonb, 'platform',
     encode(sha256('seed:tool-profile-update:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),

    -- http ×2 ------------------------------------------------------------
    ('tool-grade-report-fetch', 1,
     '成绩报告拉取',
     'http', 'READ', NULL, NULL, 'GET',
     'https://reports.internal-studykid.cn/api/v1/grade-reports', 3000,
     '{"type":"object","required":["studentId"],"properties":{"studentId":{"type":"string"}}}'::jsonb,
     '[]'::jsonb, 'platform',
     encode(sha256('seed:tool-grade-report-fetch:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),
    ('tool-parent-notification', 1,
     '家长通知发送',
     'http', 'WRITE_EXTERNAL', NULL, NULL, 'POST',
     'https://notify-gateway.internal-studykid.cn/api/v1/messages', 5000,
     '{"type":"object","required":["studentId","message"],"properties":{"studentId":{"type":"string"},"message":{"type":"string","maxLength":500}}}'::jsonb,
     '[]'::jsonb, 'platform',
     encode(sha256('seed:tool-parent-notification:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),

    -- mcp ×2 -------------------------------------------------------------
    ('tool-mcp-profile-lookup', 1,
     '档案查询(MCP)',
     'mcp', 'READ', 'mcp-student-profile', 'lookupProfile', NULL, NULL, NULL,
     '{"type":"object","required":["studentId"],"properties":{"studentId":{"type":"string"}}}'::jsonb,
     '[]'::jsonb, 'platform',
     encode(sha256('seed:tool-mcp-profile-lookup:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),
    ('tool-mcp-schedule', 1,
     '课程表查询(MCP)',
     'mcp', 'READ', 'mcp-course-calendar', 'getSchedule', NULL, NULL, NULL,
     '{"type":"object","required":["studentId"],"properties":{"studentId":{"type":"string"},"date":{"type":"string","description":"yyyy-MM-dd"}}}'::jsonb,
     '[]'::jsonb, 'platform',
     encode(sha256('seed:tool-mcp-schedule:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED')
ON CONFLICT (id, version) DO NOTHING;

-- =====================================================================
-- 4. 平台对象：Skill（4 个 · 闭包自洽：只引用上面已登记的 Tool / MCP）
-- =====================================================================
-- ⚠️ risk_ceiling 必须 >= 其闭包内 Tool 的最高 risk（发布校验会拒；
--    种子直接写对齐值）。inherits 列 V1 写入即拒（N1）—— 保持 NULL。
-- ⚠️ paramPolicies 形状：[{param, op, value}]，op 仅 in_allowlist / lte。
-- =====================================================================

INSERT INTO cap_skill_def
    (id, version, name, description, declared_tools, declared_mcp,
     risk_ceiling, param_policies, inherits, required_caps, namespace,
     config_fingerprint, schema_version, published_at, lifecycle_state)
VALUES
    ('skill-profile-view', 1,
     '档案查看', '查看学生档案与监护人信息（只读）',
     '["tool-profile-read"]'::jsonb,
     '[]'::jsonb,
     'READ', '[]'::jsonb, NULL, '[]'::jsonb, 'platform',
     encode(sha256('seed:skill-profile-view:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),

    ('skill-profile-edit', 1,
     '档案维护', '查看并更新学生档案（含写档案字段，需走审批链路）',
     '["tool-profile-read","tool-profile-update"]'::jsonb,
     '[]'::jsonb,
     'WRITE_PROFILE', '[]'::jsonb, NULL, '[]'::jsonb, 'platform',
     encode(sha256('seed:skill-profile-edit:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),

    ('skill-report-brief', 1,
     '成绩简报', '拉取成绩报告并经 MCP 校对档案（含 MCP 绑定 Tool）',
     '["tool-grade-report-fetch","tool-mcp-profile-lookup"]'::jsonb,
     '[]'::jsonb,
     'READ', '[]'::jsonb, NULL, '[]'::jsonb, 'platform',
     encode(sha256('seed:skill-report-brief:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED'),

    ('skill-include-guardian', 1,
     '家长协同', '查询课程安排并向家长发送提示（声明 MCP 直连 + WRITE_EXTERNAL）',
     '[]'::jsonb,
     '[{"serverId":"mcp-course-calendar"}]'::jsonb,
     'WRITE_EXTERNAL',
     '[{"param":"maxRecipients","op":"lte","value":50}]'::jsonb,
     NULL, '[]'::jsonb, 'platform',
     encode(sha256('seed:skill-include-guardian:v1'::bytea), 'hex'),
     1, now(), 'PUBLISHED')
ON CONFLICT (id, version) DO NOTHING;

-- =====================================================================
-- 5. 抬升 published_at / lifecycle_state（仅对未发布的新插入行）
-- =====================================================================
-- ⚠️ 带 published_at IS NULL 守卫：重跑时不触碰已发布行（不可变触发器会拒）。
-- =====================================================================
DO $$
BEGIN
    UPDATE cap_mcp_server_def SET published_at = now()
      WHERE published_at IS NULL AND id IN ('mcp-student-profile','mcp-course-calendar');
    UPDATE cap_tool_def SET published_at = now()
      WHERE published_at IS NULL AND id LIKE 'tool-%';
    UPDATE cap_skill_def SET published_at = now()
      WHERE published_at IS NULL AND id LIKE 'skill-%';
END;
$$;

-- =====================================================================
-- 6. 平台发布：release + 指针 + PUBLISH 决策日志
-- =====================================================================
-- ⚠️ 守卫：**PLATFORM 指针尚未指向任何发布**时才生成 —— 指针是「当前发布」
--    的唯一事实（§6.3），种子不覆盖任何已有发布。
-- ⚠️ object_versions 规范化序列（ConfigObjectVersionCodec）：按 (type,id) 排序；
--    type 取 CapObjectType code（tool / skill / mcp_server）。
-- =====================================================================
DO $$
DECLARE
    v_release_id bigint;
    v_publish_seq bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM cap_config_pointer
                WHERE pointer_key = 'PLATFORM' AND current_release_id IS NOT NULL) THEN
        RAISE NOTICE '平台指针已指向既有发布，跳过种子发布头';
        RETURN;
    END IF;

    v_publish_seq := nextval('cap_config_publish_seq');

    INSERT INTO cap_config_release
        (config_publish_seq, scope, tenant_id, object_versions,
         aggregate_fingerprint, status, requester_id, approver_id, effective_at)
    VALUES
        (v_publish_seq, 'PLATFORM', NULL,
         '[{"type":"mcp_server","id":"mcp-course-calendar","version":1},'
           '{"type":"mcp_server","id":"mcp-student-profile","version":1},'
           '{"type":"skill","id":"skill-include-guardian","version":1},'
           '{"type":"skill","id":"skill-profile-edit","version":1},'
           '{"type":"skill","id":"skill-profile-view","version":1},'
           '{"type":"skill","id":"skill-report-brief","version":1},'
           '{"type":"tool","id":"tool-grade-report-fetch","version":1},'
           '{"type":"tool","id":"tool-mcp-profile-lookup","version":1},'
           '{"type":"tool","id":"tool-mcp-schedule","version":1},'
           '{"type":"tool","id":"tool-parent-notification","version":1},'
           '{"type":"tool","id":"tool-profile-read","version":1},'
           '{"type":"tool","id":"tool-profile-update","version":1}]'::jsonb,
         encode(sha256('seed:platform-release:v1'::bytea), 'hex'),
         'PUBLISHED', 'platform_seed', 'platform_admin', now())
    RETURNING release_id INTO v_release_id;

    INSERT INTO cap_config_pointer
        (pointer_key, scope, tenant_id, current_release_id, current_publish_seq)
    VALUES ('PLATFORM', 'PLATFORM', NULL, v_release_id, v_publish_seq);

    -- PUBLISH 审计旁路（decision_kind='PUBLISH'：run_id 可空，phase='P'）
    INSERT INTO cap_decision_log
        (tenant_id, run_id, invocation_id,
         authz_decision, exec_decision, exec_status, ledger_status, reason_code,
         closure_fingerprint, config_publish_seq, release_id,
         decision_kind, phase)
    VALUES
        (NULL, NULL, 'seed-platform-publish-v1',
         'ALLOW', 'ALLOW', 'SUCCEEDED', 'RECORDED', 'PUBLISH_APPROVED',
         encode(sha256('seed:platform-release:v1'::bytea), 'hex'),
         v_publish_seq, v_release_id, 'PUBLISH', 'P');

    RAISE NOTICE '平台发布完成：release_id=%，publish_seq=%', v_release_id, v_publish_seq;
END;
$$;

-- =====================================================================
-- 7. 租户 Agent（3 个 · 2 LEAF + 1 SEQUENTIAL 父级）
-- =====================================================================
-- ⚠️ agent 行按 (tenant_id, name) 幂等（远程已有 92 个既有 Agent，绝不碰）。
--    agent_version definition 是快照归档（与 cap_agent_def 同构内容）。
-- ⚠️ 父级 orchestration 形状与 AgentOrchestrationParser.toJson() 对齐：
--      {"mode":"SEQUENTIAL","childAgentRefs":[{"id":<name>,"version":1,
--        "required":true}, ...],"maxDelegationDepth":2,
--       "maxChildInvocations":3,"timeoutMs":60000,"failurePolicy":"FAIL_FAST"}
--    子引用固定版本、绝不写 latest（MA-T02）。
-- =====================================================================
DO $outer$
DECLARE
    v_tenant    uuid := current_setting('app.tenant_id', TRUE)::uuid;
    -- LEAF：缺省 {} = 不允许委派（MA-T01）
    v_orch_leaf constant jsonb := '{}'::jsonb;
    -- SEQUENTIAL 父级：与 AgentOrchestrationParser.toJson() 字段形状对齐；
    -- 子引用固定 v1、required=true，绝不写 latest（MA-T02）
    v_orch_parent constant jsonb := jsonb_build_object(
        'mode', 'SEQUENTIAL',
        'childAgentRefs', jsonb_build_array(
            jsonb_build_object('id', 'math-tutor', 'version', 1, 'required', TRUE),
            jsonb_build_object('id', 'profile-reviewer', 'version', 1, 'required', TRUE)),
        'maxDelegationDepth', 2,
        'maxChildInvocations', 3,
        'timeoutMs', 60000,
        'failurePolicy', 'FAIL_FAST');

    -- 种子清单（与上面 v_orchs 序号一一对应）
    v_names constant text[] := ARRAY['math-tutor','profile-reviewer','assignment-copilot'];
    v_descs constant text[] := ARRAY[
        '数学辅导 Agent：读取学生档案与课程安排，给出个性化练习建议',
        '档案复核 Agent：复核并维护学生档案中的关键联系字段',
        '作业协同 Agent：串行委派数学辅导与档案复核，聚合结果向家长通报'];
    v_roles constant jsonb[] := ARRAY[
        '["student"]'::jsonb,
        '["teacher"]'::jsonb,
        '["student","teacher"]'::jsonb];
    v_skills constant jsonb[] := ARRAY[
        '["skill-profile-view","skill-report-brief"]'::jsonb,
        '["skill-profile-edit"]'::jsonb,
        '["skill-profile-view"]'::jsonb];
    v_tags constant jsonb[] := ARRAY[
        '["tutor","math"]'::jsonb,
        '["reviewer","profile"]'::jsonb,
        '["copilot","orchestrator"]'::jsonb];
    v_orchs constant jsonb[] := ARRAY[v_orch_leaf, v_orch_leaf, v_orch_parent];

    v_agent_id uuid;
    v_fp       text;
    v_def      jsonb;
    v_i        int;
BEGIN
    FOR v_i IN 1..3 LOOP
        -- agent 行：按 (tenant_id, name) 幂等 —— 既有同名 Agent 绝不碰
        SELECT agent_id INTO v_agent_id FROM agent
          WHERE tenant_id = v_tenant AND name = v_names[v_i];
        IF v_agent_id IS NULL THEN
            INSERT INTO agent (tenant_id, name, description)
            VALUES (v_tenant, v_names[v_i], v_descs[v_i])
            RETURNING agent_id INTO v_agent_id;
        END IF;

        v_fp := encode(sha256(('seed:agent:' || v_names[v_i] || ':v1')::bytea), 'hex');
        v_def := jsonb_build_object(
            'agentId', v_names[v_i], 'name', v_names[v_i], 'description', v_descs[v_i],
            'roles', v_roles[v_i], 'enabledSkills', v_skills[v_i],
            'skillBundles', '[]'::jsonb, 'capabilityTags', v_tags[v_i],
            'overrides', '{}'::jsonb, 'orchestration', v_orchs[v_i]);

        -- agent_version：v1 快照（归档，幂等）
        INSERT INTO agent_version (tenant_id, agent_id, version_number,
                                   definition, published_at)
        VALUES (v_tenant, v_agent_id, 1, v_def, now())
        ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING;

        -- cap_agent_def：结构化真源 v1（幂等；未发布才抬升 published_at）
        INSERT INTO cap_agent_def
            (tenant_id, agent_id, version, name, description, roles,
             enabled_skills, skill_bundles, capability_tags, overrides,
             orchestration, config_fingerprint, schema_version,
             published_at, lifecycle_state)
        VALUES (v_tenant, v_agent_id, 1, v_names[v_i], v_descs[v_i],
                v_roles[v_i], v_skills[v_i], '[]'::jsonb, v_tags[v_i],
                '{}'::jsonb, v_orchs[v_i], v_fp,
                1, now(), 'PUBLISHED')
        ON CONFLICT (tenant_id, agent_id, version) DO NOTHING;
        UPDATE cap_agent_def SET published_at = now()
          WHERE tenant_id = v_tenant AND agent_id = v_agent_id
            AND version = 1 AND published_at IS NULL;
    END LOOP;

    -- 父→子投影边（cap_agent_child_ref，幂等；ordinal 即 SEQUENTIAL 执行序）
    INSERT INTO cap_agent_child_ref
        (tenant_id, parent_agent_id, parent_release_version,
         child_agent_id, child_release_version, ordinal, required)
    SELECT p.tenant_id, p.agent_id, 1, c.agent_id, 1, k.ord, TRUE
      FROM agent p
      JOIN agent c ON c.tenant_id = p.tenant_id
      JOIN (VALUES ('math-tutor', 0), ('profile-reviewer', 1)) AS k(name, ord)
        ON k.name = c.name
     WHERE p.tenant_id = v_tenant AND p.name = 'assignment-copilot'
    ON CONFLICT DO NOTHING;
END;
$outer$;

-- =====================================================================
-- 8. 校验（fail loudly · 带上下文读 RLS 表）
-- =====================================================================
SELECT set_config('seed.tenant_slug', :'tenant_slug', false);

DO $$
DECLARE
    v_mcp int; v_tool int; v_skill int;
    v_pointer_ok boolean;
    v_agents int; v_child_refs int;
    v_tenant uuid;
BEGIN
    -- 平台对象计数
    SELECT count(*) INTO v_mcp FROM cap_mcp_server_def
      WHERE id IN ('mcp-student-profile','mcp-course-calendar')
        AND published_at IS NOT NULL;
    SELECT count(*) INTO v_tool FROM cap_tool_def
      WHERE id IN ('tool-profile-read','tool-profile-update',
                   'tool-grade-report-fetch','tool-parent-notification',
                   'tool-mcp-profile-lookup','tool-mcp-schedule')
        AND published_at IS NOT NULL;
    SELECT count(*) INTO v_skill FROM cap_skill_def
      WHERE id IN ('skill-profile-view','skill-profile-edit',
                   'skill-report-brief','skill-include-guardian')
        AND published_at IS NOT NULL;
    IF v_mcp <> 2 OR v_tool <> 6 OR v_skill <> 4 THEN
        RAISE EXCEPTION '种子不完整：MCP=% (期望2) Tool=% (期望6) Skill=% (期望4)',
            v_mcp, v_tool, v_skill;
    END IF;

    -- 平台发布指针
    SELECT (current_release_id IS NOT NULL AND current_publish_seq IS NOT NULL)
      INTO v_pointer_ok
      FROM cap_config_pointer WHERE pointer_key = 'PLATFORM';
    IF NOT v_pointer_ok THEN
        RAISE EXCEPTION '种子不完整：PLATFORM 指针尚未指向任何发布';
    END IF;

    -- 租户 Agent（带 RLS 上下文读）
    v_tenant := current_setting('app.tenant_id', TRUE)::uuid;
    SELECT count(*) INTO v_agents FROM cap_agent_def
      WHERE tenant_id = v_tenant
        AND version = 1 AND published_at IS NOT NULL
        AND (name = 'math-tutor' OR name = 'profile-reviewer'
             OR name = 'assignment-copilot');
    SELECT count(*) INTO v_child_refs FROM cap_agent_child_ref
      WHERE child_release_version = 1;
    IF v_agents < 3 THEN
        RAISE EXCEPTION '种子不完整：期望租户内 3 个 v1 Agent 定义，实际 %', v_agents;
    END IF;
    IF v_child_refs < 2 THEN
        RAISE EXCEPTION '种子不完整：父级 SEQUENTIAL 至少应有 2 条子引用边';
    END IF;

    RAISE NOTICE '✅ 能力目录种子校验通过：MCP=2, Tool=6, Skill=4, '
        '平台发布指针在位, 租户 Agent v1=%, 子引用边=%', v_agents, v_child_refs;
END;
$$;

-- =====================================================================
-- 9. 复位租户上下文（校验之后，防泄漏）
-- =====================================================================
SELECT set_config('app.tenant_id', '', false);

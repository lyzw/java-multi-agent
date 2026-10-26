-- =====================================================================
-- 一次性清理：platform schema 下误建的 agent / 租户业务表残留
-- =====================================================================
-- 背景：
--   Flyway 迁移原本使用未限定表名，且未配置 default-schema。若某环境连接
--   角色的默认 schema 为 platform（或手工 psql 跑迁移时 search_path 首元素
--   为 platform），未限定前缀的 agent / run / ... 会被建到 platform 下，
--   导致「platform 也有 agent 表 + public 也有」的重复。
--   现已通过 application.yml 钉死 default-schema=public + connection-init-sql
--   防止复发。本脚本仅用于清理**存量**残留。
--
-- ⚠️ 破坏性：DROP 不可恢复。执行前务必先跑下方诊断，确认：
--   1) 列出的表确实位于 platform 下、且并非你有意保留的对象；
--   2) 对应 public 下已有正确表（由 Flyway 管理）；
--   3) 所有行数为 0（非空表说明有数据，需先决定迁移还是备份，绝不直接 DROP）。
-- 建议：先在事务里核对诊断结果，确认无误后再手动执行 DROP 段（去掉注释）。
-- =====================================================================

-- ── 1. 诊断：platform 下疑似业务表 + 行数 ───────────────────────────
DO $$
DECLARE
    r RECORD;
    n BIGINT;
BEGIN
    RAISE NOTICE '=== platform 下的疑似业务表残留在下方；行数为 0 才可安全清理 ===';
    FOR r IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'platform'
          AND table_name = ANY(ARRAY[
                'tenant','agent','agent_version','agent_invocation','agent_message_queue',
                'agent_message_dead_letter','execution_snapshot','run','run_step','tool_operation',
                'tool_operation_attempt','approval','run_event','outbox_event','idempotency_record',
                'budget_account','budget_reservation','usage_record','object_access_grant','audit_record',
                'student_profile','weakness_record','principal','principal_role','rate_limit_bucket',
                'consent_withdrawal','erasure_certificate','tenant_config_override','config_change_request',
                'cap_agent_child_ref','cap_agent_def','cap_tool_def','cap_skill_def','cap_mcp_server_def',
                'cap_authz_version','execution_capability_snapshot','cap_config_release','cap_result_event',
                'cap_execution_intent','cap_approval','cap_compensation_task','cap_config_pointer',
                'cap_decision_log','cap_config_publish_request','cap_result_contract','cap_routing_policy'
            ])
        ORDER BY table_name
    LOOP
        EXECUTE format('SELECT count(*) FROM platform.%I', r.table_name) INTO n;
        RAISE NOTICE '% : % 行', r.table_name, n;
    END LOOP;
END
$$;

-- 若上述输出为空，说明 platform 下没有业务表残留，无需清理。
-- 若全部为 0 行，且你确认 public 下已有对应正确表，再取消注释执行下方 DROP：

-- DROP TABLE IF EXISTS
--     platform.agent,
--     platform.agent_version,
--     platform.agent_invocation,
--     platform.agent_message_queue,
--     platform.agent_message_dead_letter,
--     platform.run,
--     platform.run_step,
--     platform.tool_operation,
--     platform.tool_operation_attempt,
--     platform.approval,
--     platform.run_event,
--     platform.outbox_event,
--     platform.idempotency_record,
--     platform.budget_account,
--     platform.budget_reservation,
--     platform.usage_record,
--     platform.object_access_grant,
--     platform.audit_record,
--     platform.execution_snapshot,
--     platform.student_profile,
--     platform.weakness_record,
--     platform.principal,
--     platform.principal_role,
--     platform.rate_limit_bucket,
--     platform.consent_withdrawal,
--     platform.erasure_certificate,
--     platform.tenant_config_override,
--     platform.config_change_request,
--     platform.tenant,
--     platform.cap_agent_child_ref,
--     platform.cap_agent_def,
--     platform.cap_tool_def,
--     platform.cap_skill_def,
--     platform.cap_mcp_server_def,
--     platform.cap_authz_version,
--     platform.execution_capability_snapshot,
--     platform.cap_config_release,
--     platform.cap_result_event,
--     platform.cap_execution_intent,
--     platform.cap_approval,
--     platform.cap_compensation_task,
--     platform.cap_config_pointer,
--     platform.cap_decision_log,
--     platform.cap_config_publish_request,
--     platform.cap_result_contract,
--     platform.cap_routing_policy
-- CASCADE;

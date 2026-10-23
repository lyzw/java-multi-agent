-- =====================================================================
-- V18：统一配置与权限管控 · 配置变更申请（W-PERM-P6R4 · 管理端点）
--
-- 对标设计文档：
--   docs/Agent - Skill- Tool -MCP 统一配置与权限管控体系.md
--     §6.1 核心表 / §6.3 原子发布与并发控制（CAS / 回滚） / §6.6 生命周期
--   docs/权限管控体系/06_配置数据模型与生命周期设计.md
--     §3 配置生命周期（状态机）/ §3.2 多对象原子发布
--   docs/权限管控体系/08_安全测试与验收矩阵.md
--     §7 审计指标（四字段完整率 100% / 非笼统 DENY 占比 100%）
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 背景（P6 冻结报告登记的限制 7 / 限制 8）：
--   发布 / 回滚 / 生命周期迁移**没有 HTTP 管理端点**，只能由测试直接调服务；
--   `dynamicDiscovery` / `stdio` 的配置期拒虽已在**发布门禁**与**采集兜底**两层生效，
--   但没有可达的写入口，运维就无从触发「写一条非法配置看它被拒」。
--
-- ⚠️ **为什么要单独建一张「申请单」表**（而不是让端点一次调用直接发布）：
--   §6.3 的发布是「版本行 + 发布头 + 指针 CAS」三件事的**原子事务**，
--   天然不可拆成两次调用；而双人复核（06 §3 approve 行：approver_id <> requester_id）
--   又要求**申请人与审批人是两个不同的主体**。
--   若把两个 id 直接放在一次请求里由调用方填写，双人复核就退化成
--   「调用方自己声明另一个人同意了」—— 那不是复核，是自证。
--   故本表承载「待审批的变更方案」，让申请与审批成为**两次独立的、带身份的调用**。
--
-- ⚠️ **本表是租户级**：租户管理员发布的是**本租户的对象**。
--   平台级对象（Tool / Skill / MCP Server）无 tenant_id，其发布走
--   平台运维通道（BYPASSRLS 凭据 + 带外运维工具），**不经租户身份暴露**。
--
-- ⚠️ **幂等**：本迁移必须可重复执行（scripts/dev-it-db.sh 会逐个重放）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 配置变更申请表
--
-- ⚠️ **状态机的含义（与 cap_config_release 的 status 不同）**：
--   本表的 state 描述的是「**审批流程**」的进度，不是配置对象自身的生命周期
--   （对象生命周期在 cap_*_def.lifecycle_state，见 V17）。
--   两套状态刻意分开：
--     · 本表：PENDING → APPROVED → APPLIED（另有 REJECTED / FAILED）
--     · 对象：DRAFT → VALIDATED → PUBLISHED → DEPRECATED（V17 cap_lifecycle_state）
--   混用会让「审批通过了但还没生效」的对象被误当成「已发布」。
--
-- ⚠️ **FAILED 是终态、不可重试**：
--   执行失败（CAS 冲突 / 门禁拒绝）时保留申请单原文与失败原因，
--   由人判断「改参数重来」还是「重新申请」——
--   自动重试一个**审批已通过**的方案，等于让审批人批过的东西在没人的情况下变形。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cap_config_publish_request (
    request_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    -- PUBLISH | ROLLBACK | LIFECYCLE_TRANSITION
    operation      TEXT NOT NULL,
    -- 变更方案原文（JSONB）。原文入库是审计要求：
    -- 审批人批的是**这份**方案，执行时执行的也必须是**这份**方案。
    payload        JSONB NOT NULL,
    reason         TEXT NOT NULL,
    requester_id   TEXT NOT NULL,
    state          TEXT NOT NULL DEFAULT 'PENDING',
    approver_id    TEXT,
    decision_note  TEXT,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ,
    applied_at     TIMESTAMPTZ,
    failure_reason TEXT,
    CONSTRAINT cap_config_publish_request_operation_ck CHECK (
        operation IN ('PUBLISH', 'ROLLBACK', 'LIFECYCLE_TRANSITION')),
    CONSTRAINT cap_config_publish_request_state_ck CHECK (
        state IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'FAILED')),
    -- 双人复核的 DB 层兜底：应用层校验不是唯一防线（与 cap_config_release 同款）
    CONSTRAINT cap_config_publish_request_two_person_ck CHECK (
        approver_id IS NULL OR approver_id <> requester_id)
);

CREATE INDEX IF NOT EXISTS idx_cap_config_publish_request_tenant
    ON cap_config_publish_request (tenant_id, requested_at DESC);

COMMENT ON TABLE cap_config_publish_request IS
    '配置变更申请单（P6R4 管理端点）：承载**待审批**的发布 / 回滚 / 生命周期迁移方案。'
    '双人复核的载体 —— 申请与审批是两次独立的、带身份的调用。';

COMMENT ON COLUMN cap_config_publish_request.state IS
    '审批流程状态（PENDING/APPROVED/REJECTED/APPLIED/FAILED）；'
    '**不是**配置对象自身的生命周期（见 cap_*_def.lifecycle_state）。';

-- ---------------------------------------------------------------------
-- 2. RLS：租户隔离
--
-- ⚠️ 与 cap_config_release 的区别：
--   cap_config_release / cap_config_pointer 需要承载 PLATFORM 作用域行
--   （tenant_id IS NULL），故读策略是「tenant_id IS NULL OR = current_tenant_id()」，
--   写策略则**只**放行本租户行（V15 §写策略注释：若放行 tenant_id IS NULL，
--   任一租户都能发布平台级配置）。
--   本表**只**承载租户级申请（平台级发布不走本表），
--   故读写都用严格相等 —— 少一个「IS NULL」的例外，就少一个能出事的分支。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class
                   WHERE relname = 'cap_config_publish_request'
                     AND relnamespace = 'public'::regnamespace) THEN
        RAISE EXCEPTION 'cap_config_publish_request 未创建，V18 结构异常';
    END IF;

    EXECUTE 'ALTER TABLE cap_config_publish_request ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE cap_config_publish_request FORCE ROW LEVEL SECURITY';

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_publish_request'
                   AND policyname = 'cap_config_publish_request_read') THEN
        CREATE POLICY cap_config_publish_request_read ON cap_config_publish_request
            FOR SELECT USING (tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_publish_request'
                   AND policyname = 'cap_config_publish_request_insert') THEN
        CREATE POLICY cap_config_publish_request_insert ON cap_config_publish_request
            FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'cap_config_publish_request'
                   AND policyname = 'cap_config_publish_request_update') THEN
        CREATE POLICY cap_config_publish_request_update ON cap_config_publish_request
            FOR UPDATE USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END;
$$;

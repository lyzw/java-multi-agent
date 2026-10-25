-- =====================================================================
-- V26：输出契约 / 路由策略补生命周期状态（多级 Agent v1.1 · 第二十五批 · A5）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--     §3 配置生命周期（状态机）
--     §3.2 删除/停用被引用对象
--   docs/多级 Agent优化/00_修订说明与评审决议项.md
--     · 「路由策略没有独立的生命周期迁移接口」（本批消解）
--
-- ⚠️ **为什么这两张表要补 lifecycle_state**（本批要修的真实缺口）：
--   `cap_result_contract`（V20）与 `cap_routing_policy`（V24）只继承了 V15 时代的
--   「布尔式」发布标记（published_at 非空 = 已发布），
--   **没有 V17 引入的 cap_lifecycle_state 列**。
--   后果是这两类对象**只能表达「已发布」与「未发布」两态**：
--     · 表达不出「已校验待发布」（VALIDATED）；
--     · 表达不出「已停用」（DEPRECATED）——
--       06 §3.2 要求「被引用对象禁止物理删除 → 改 DEPRECATED」，
--       而这两类对象连一个「停用」的落点都没有，
--       于是「停用一份被引用的契约/策略」在数据层**无法表达**。
--
-- ⚠️ **为什么 DEPRECATED 不能靠 published_at 表达**：
--   published_at 是不可变触发器的判定依据（published_at 非空 = 已发布 = 内容冻结）。
--   要「停用」就必须让 published_at 变空 —— 那等于把已发布行**降级回草稿**：
--     · 内容会重新变得可改（不可变保证被抽掉）；
--     · 而且「已发布」这个事实会凭空消失，回滚与追溯都失去依据。
--   故停用必须是**独立于 published_at 的第三个维度**：lifecycle_state。
--
-- ⚠️ **本批顺带补上一处更硬的缺口**：
--   V20 / V24 建表时**没有**挂不可变触发器
--   （V15 只给 cap_tool_def / cap_skill_def / cap_mcp_server_def / cap_agent_def 挂了），
--   于是「已发布不可变的契约 / 策略版本行」在数据层其实是**可改写的**——
--   §6.1「已发布版本行不可变」对这两张表从未生效。
--   本批把 trg_<表>_immutable 补上，与既有四张表同款判定函数。
--
-- 技术基线：PostgreSQL 17（DEC-13）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 增补 lifecycle_state 列（幂等）
--
-- ⚠️ **DEFAULT 取 'DRAFT'**：与 V17 同口径 —— 列默认值必须是「最弱的状态」，
--    由对齐触发器把「published_at 非空」的行抬到 PUBLISHED。
--    若 DEFAULT 取 'PUBLISHED'，则每一条草稿行一插入就自称已发布。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_name = t AND column_name = 'lifecycle_state') THEN
            EXECUTE format(
                'ALTER TABLE %I ADD COLUMN lifecycle_state cap_lifecycle_state NOT NULL DEFAULT %L',
                t, 'DRAFT');
        END IF;
    END LOOP;
END;
$$;

COMMENT ON COLUMN cap_result_contract.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。'
    'DEPRECATED = 已停用：新发布不可再引用（06 §3.2），存量引用按版本过渡。';

COMMENT ON COLUMN cap_routing_policy.lifecycle_state IS
    '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。'
    'DEPRECATED = 已停用：新发布不可再引用（06 §3.2），存量引用按版本过渡。';

-- ---------------------------------------------------------------------
-- 2. 回填：已发布行 → PUBLISHED
--
-- ⚠️ **必须临时 NO FORCE ROW LEVEL SECURITY**：
--   这两张表都是 FORCE RLS 的租户表，迁移角色若是 owner 也**同样受策略约束**；
--   未设租户上下文时 UPDATE 影响 0 行 → 回填**静默不生效**
--   （表现为「迁移跑成功了，但状态全是 DRAFT」—— 最难查的一类假成功）。
--   故本段：NO FORCE → 回填 → FORCE。与 V17 对 cap_agent_def 的处理同款。
--
-- ⚠️ **异常路径必须恢复现场**：任一步失败都要把 FORCE 状态复原，
--   否则迁移中断会留下「租户表不再受 RLS 保护」的残局 —— 那比迁移失败本身严重得多。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    restore_failed BOOLEAN := FALSE;
BEGIN
    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
    END LOOP;

    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        EXECUTE format(
            'UPDATE %I SET lifecycle_state = %L WHERE published_at IS NOT NULL '
            'AND lifecycle_state <> %L',
            t, 'PUBLISHED', 'PUBLISHED');
    END LOOP;

    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
EXCEPTION WHEN OTHERS THEN
    BEGIN
        FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    EXCEPTION WHEN OTHERS THEN
        restore_failed := TRUE;
    END;
    IF restore_failed THEN
        RAISE EXCEPTION 'V26 回填失败且现场恢复失败（需人工修复 RLS 状态）: %', SQLERRM;
    END IF;
    RAISE;
END;
$$;

-- ---------------------------------------------------------------------
-- 3. 对齐触发器（published_at ⇄ lifecycle_state 绑定）
--
-- ⚠️ **直接复用 V17 的 align_cap_lifecycle_state() 函数**：
--   同一套绑定规则必须在所有对象定义表上**只有一份实现** ——
--   两份实现迟早漂移，而漂移落在「published_at 非空却自称 DRAFT」
--   这类自相矛盾的状态上，排查方向会被带偏。
--
-- ⚠️ **触发器名沿用 V17 的命名约定**（{@code align_state} 字典序在 {@code immutable} 之前）：
--   六张对象定义表必须共用**同一套触发器命名**，否则「四张表 + 两张表」
--   的触发器集合会分裂成两族，维护时极易漏改。
--   ⚠️ **本迁移的两条边上，执行顺序本身不影响结论**（停用迁移里
--   align 不会改动 {@code DEPRECATED}），故此处**不**把顺序说成「放行的必要条件」——
--   那样写会把一条巧合说成机制。保持命名一致是为了给未来新增的边留出确定性。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%I_align_state ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_%I_align_state BEFORE INSERT OR UPDATE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION align_cap_lifecycle_state()', t, t);
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------
-- 4. 不可变触发器（已发布版本行内容冻结）
--
-- ⚠️ **放行范围与既有四张表完全一致**：
--   复用 V17 版 forbid_published_def_mutation() ——
--   仅当「除 lifecycle_state 与 updated_at 外的所有列完全不变」时才放行。
--   这两张表没有 updated_at 列（jsonb 差分减去不存在的键是无害的），
--   所以「停用迁移」在这两张表上同样是**纯元数据**的。
--
-- ⚠️ **V20 / V24 建表时漏挂了这个触发器**：
--   契约与策略此前是「已发布也照样能改」的 —— §6.1「已发布版本行不可变」
--   对它们从未生效。本批补上（**收紧**方向，不放宽任何既有防线）。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['cap_result_contract', 'cap_routing_policy'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%I_immutable ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_%I_immutable BEFORE UPDATE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation()', t, t);
    END LOOP;
END;
$$;

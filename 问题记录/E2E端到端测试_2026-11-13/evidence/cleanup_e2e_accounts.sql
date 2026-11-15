-- ============================================================================
-- cleanup_e2e_accounts.sql
-- 用途：清理 E2E 测试残留平台账号
--   目标账号：e2e_probe_1110（2026-11-10）、e2e_test_1113a / e2e_test_1113b（2026-11-13）
-- 关联发现：本轮 E2E 写路径实测后，platform.platform_user 表累积 3 个 DISABLED 测试主体
--           （见《端到端测试报告_2026-11-13.md》§3.4 / §5 DEF-05 / §10.7）
-- 来源活库：agent_dev（platform schema），即后端 8080 当前连接的同一活库
-- ============================================================================
--
-- ⚠️ 前置条件（必读）
--   1. 必须以表 owner（ai_agent）身份连接活库；当前会话无 AI_AGENT_PASSWORD，无法直接执行。
--   2. 平台设计为 fail-closed、无物理删除端点、审计可追溯。物理删除会破坏审计链；
--      若非合规/容量必须，推荐保持 DISABLED 即可（当前 3 个账号已是 DISABLED，不可登录）。
--   3. 本脚本的物理删除步骤为「可选、需显式授权」；默认只提供只读核验与可逆演练。
--
-- 执行纪律：所有写操作包在事务内，先 ROLLBACK 演练确认影响行数，再改 COMMIT。
--           维护窗口 + 双人复核下执行；执行后回填本文件末尾「复核记录」。
-- ============================================================================

-- STEP 0：只读核验（先跑，确认范围，不改动任何数据）
SELECT username,
       status,
       created_at
FROM platform.platform_user
WHERE username IN ('e2e_probe_1110', 'e2e_test_1113a', 'e2e_test_1113b')
ORDER BY created_at;

-- STEP 0b：外键排查（执行任何删除前必读，确认关联清理顺序）
--   列出所有引用 platform_user 的外键，据此决定 STEP 1 的清理顺序
SELECT tc.constraint_name,
       kcu.table_schema  AS fk_schema,
       kcu.table_name    AS fk_table,
       kcu.column_name   AS fk_column,
       ccu.table_name    AS referenced_table
FROM information_schema.table_constraints  tc
JOIN information_schema.key_column_usage    kcu ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND ccu.table_schema  = 'platform'
  AND ccu.table_name    = 'platform_user'
ORDER BY kcu.table_name, kcu.column_name;

-- STEP 0c：各关联表待删除行数预估（将 <fk_table>/<fk_column> 替换为 STEP 0b 的查询结果）
-- SELECT '<fk_table>' AS tbl, count(*) AS rows_to_delete
-- FROM platform.<fk_table>
-- WHERE <fk_column> IN (
--     SELECT id FROM platform.platform_user
--     WHERE username IN ('e2e_probe_1110','e2e_test_1113a','e2e_test_1113b')
-- );

-- ----------------------------------------------------------------------------
-- STEP 1（可选·需授权）：事务内清理关联 + 主表删除
--   1a. 按 STEP 0b 外键结果，对每个 <fk_table> 逐条 DELETE（顺序须满足外键依赖）
--   1b. 删除 platform_user 主行
--   1c. 核验影响行数 → 先 ROLLBACK 演练，确认无误后改为 COMMIT
-- ----------------------------------------------------------------------------
BEGIN;

-- 1a. 关联清理（示例占位，执行前用 STEP 0b 结果替换 <fk_table>/<fk_column>）
-- DELETE FROM platform.<fk_table>
-- WHERE <fk_column> IN (
--     SELECT id FROM platform.platform_user
--     WHERE username IN ('e2e_probe_1110','e2e_test_1113a','e2e_test_1113b')
-- );

-- 1b. 主表删除
-- DELETE FROM platform.platform_user
-- WHERE username IN ('e2e_probe_1110','e2e_test_1113a','e2e_test_1113b');

-- 1c. 确认影响行数（应正好 3 行主表 + 各关联表计数）
-- SELECT count(*) FROM platform.platform_user
-- WHERE username IN ('e2e_probe_1110','e2e_test_1113a','e2e_test_1113b');  -- 期望 0

-- 演练：先回滚，确认无误后再把下行 ROLLBACK 改为 COMMIT;
ROLLBACK;
-- COMMIT;

-- ============================================================================
-- 复核记录（执行后由操作者填写）
--   执行人 / 日期：
--   活库标识（agent_dev 连接串脱敏）：
--   STEP 0 核验结果（3 行 DISABLED？）：
--   STEP 0b 外键清单（关联表与列）：
--   实际删除行数（主表 / 关联表）：
--   回滚 or 提交：ROLLBACK（保持 DISABLED）  /  COMMIT（已物理删除）
--   遗留风险：
-- ============================================================================

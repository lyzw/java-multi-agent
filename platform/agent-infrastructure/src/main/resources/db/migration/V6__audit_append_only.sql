-- P3-D07 / R-3：审计追加约束（DB 唯一可靠防线）
--
-- ⚠️ 为什么必须是触发器而不是「应用层只提供 insert」：
--    应用层约束对直连 SQL / DBA / 迁移脚本**完全无效**。
--    R-3（日志被篡改）只有 DB 层能真正防住。
--
-- ⚠️ 为什么不能用 GRANT 控制：
--    GRANT 是**按表**的权限，无法区分「本平台的正常写入」与「事后篡改」。
--    触发器作用于**所有**写入路径（含超级用户）。
--
-- 与 D4 的 enforce_reservation_state_transition / D5 的
-- enforce_approval_state_transition 同源手法：应用层 + DB 双保险。

-- 审计写入入口的白名单：只有这两条 DML 合法
--   INSERT（正常写入）—— 由 trg_audit_record_append_only 放行
--   UPDATE / DELETE    —— 一律拒绝
CREATE OR REPLACE FUNCTION forbid_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '审计记录为追加不可改（R-3）：禁止 % 操作 audit_record（audit_id=%）。'
        , TG_OP, OLD.audit_id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_record_append_only ON audit_record;
CREATE TRIGGER trg_audit_record_append_only
    BEFORE UPDATE OR DELETE ON audit_record
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- 追加约束的文档化注释（追溯用途）
COMMENT ON TABLE audit_record IS
    '审计记录（P3-D07 / R-1 / R-2 / R-3）。追加不可改：UPDATE/DELETE 由 '
    'trg_audit_record_append_only 一律拒绝。180 天保留期到期清理须走受控运维流程'
    '（临时禁用触发器 + 记录操作主体），本层刻意不提供自动清理。';
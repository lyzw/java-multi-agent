-- =============================================================================
-- M4-CENTRAL 中央绑定器（RagSessionScope）RLS 兼容层退役 · DRAFT — 禁止应用
-- =============================================================================
-- ⛔⛔⛔ DRAFT / 未放行 ⛔⛔⛔
--   本文件是 M4 的最后一批，承接 R3 之后：rlsDependent 已降至 2（仅剩 CENTRAL 2 处
--   set_config），本批归零至 0。硬前置同前：AUTHZ_MISMATCH=0 连续 14 天 + TL/QA 签核。
--   见 docs/权限管控体系/RLS依赖清理改造计划_20261112.md §5.2 / §6。
--
-- ⚠️⚠️ 本批**无任何 DB 策略可退役**：
--     表级 RLS 已由 R1（16 策略）/ R2（20 策略）/ R3（6 策略）三批全部 DROP + DISABLE，
--     rag_access_audit 白名单高敏表 RLS 保留。本文件因此**不含任何 DROP POLICY / ALTER TABLE**，
--     仅作为「CENTRAL = 纯代码改动」的代码变更清单锚点，避免在迁移链里留下空跑脚本。
--
-- CENTRAL 真实改动（代码侧，反序=Sev-0：先确保不依赖 GUC 再删 GUC）：
--   ① rag-persistence `cn.studykid.rag.management.infrastructure.RagSessionScope.apply`
--      移除 2 处 set_config（:71 `set_config('app.tenant_id', '', true)` 置空分支 /
--      :76 `set_config('app.tenant_id', ?, true)` 置租户分支）；
--      同步移除该处 `RlsDependencyProbe.record(CENTRAL)` 调用（探针计数归零）。
--   ② 确认 rag_section 等原本依赖 CENTRAL GUC 的读路径，已由 M3 显式谓词（{rag_tenant}/
--      {rag_sentinel} 字面量或 SubjectContext 注入）独立承重，再删 GUC（对偶安全证据见 M2 影子）。
--   ③ `rag-core.RlsDependencyProbe.EXPECTED`：CENTRAL 2→0 ⇒ expectedTotal() 30→0；
--      `rag-persistence.RlsDependencyMetricTest` 映射同步递减。
--   ④ 重跑全量 rag reactor `clean verify`（含 RagShadowLiveIT + 各面 IT）验收；
--      反向验证：移除显式谓词即转红（红才对）。
--
-- 回滚：本批可逆 —— 恢复 RagSessionScope 2 处 set_config 即回（RLS 已无，仅影响 GUC 兼容层，
--   不影响隔离正确性，因为显式谓词已是唯一权威）。
-- =============================================================================

-- 本文件刻意不含可执行 DDL：所有变更在应用代码侧（见上方「CENTRAL 真实改动」）。
-- 若需在本迁移链占位以便顺序对齐，可保留本空文件；它不会在任何库产生副作用。

DO $$ BEGIN
    -- 占位：CENTRAL 批次无 DB 对象变更。此处仅做存在性自检，不产生 DDL 副作用。
    RAISE NOTICE 'M4-CENTRAL: no DB policy to drop — all table RLS retired by R1/R2/R3; change is code-only (RagSessionScope set_config removal).';
END $$;

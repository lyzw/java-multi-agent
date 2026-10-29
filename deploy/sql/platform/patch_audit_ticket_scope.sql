-- =====================================================================
-- 平台审计补丁：platform_audit_event 增列 ticket_scope（U-8 / 方案 §5 A4）
-- =====================================================================
--
-- 【补的是什么】治理票据的**档位**（＝票据声明的 `rag_scope`，值域同 RAG 侧
--   RagCorpusScope 三档）。此前它没有落点：
--     · ip 是 inet、target_id / correlation_id 是 uuid，装不下档位；
--     · reason_code 已被「ALLOW 的 jti ／ DENY 的原因码」两种语义占用（varchar(64)），
--       再塞第三种读法就得靠约定推断，而**推断出来的审计不能作为追责证据**。
--   本轮之前可签档位唯一（PLATFORM），所以「哪档」恰好没有丢；
--   第三刀放开 ALL 之后，同一张表里会同时出现两种档位的签发与拒绝，
--   「谁签的、签来写哪档」必须是一条**查询**而不是一个**考古**。
--
-- 【顺序是正确性要求，不是风格问题】（2026-10-29 U-7 退役时立下的纪律，本文件是它的第一次兑现）
--   加约束型改动一律 **先加可空列 → 再回填存量 → 最后加 CHECK**：
--   反过来（先建 CHECK 再回填）时，回填的每一行都要过当时已生效的约束，
--   而约束的表达力未必覆盖回填语义 —— 全新库里回填 0 行、看不见这个坑，
--   存量库才会炸。这里三步都幂等，空库与存量库都能跑。
--
-- 【幂等】ADD COLUMN IF NOT EXISTS + 两条带 WHERE 守卫的回填 + NOT EXISTS 守卫的约束。
--   可反复执行；与 01_platform_ddl.sql 的终态**逐字等价**（由
--   scripts/platform-audit-ticket-scope-retrofit-check.sh 用 pg_dump 差分证明）。
--
-- 【范围】只动 platform.platform_audit_event 一列；不 DROP、不改既有列、不碰租户侧
--   public.audit_record（那是分域的另一张表）。
--
-- 【执行】以 platform schema 的 owner 执行（platform.env 的 SPRING_DATASOURCE_USERNAME）：
--   platform_ops 虽有 BYPASSRLS 但对 platform schema 无写权限（RLS ≠ 表权限）。
--   ⚠️ 共享业务库：先 dry-run（把末尾改成 ROLLBACK 跑一遍）再 COMMIT。
--   用法：psql <连接串> -U <owner> -d <库> -v ON_ERROR_STOP=1 -f 本文件
-- =====================================================================

SET search_path TO platform, public;
-- 审计表是热表（每次票据签发/平台写操作都追加一行），ALTER 要拿 ACCESS EXCLUSIVE 锁；
-- 拿不到就快速失败，不要排在长查询后面把整条平台链路一起卡住。
SET lock_timeout = '20s';
SET statement_timeout = '120s';

BEGIN;

-- ── 1. 先加**可空、无约束**的列 ────────────────────────────────
-- 非空默认值会让 PG 在 ALTER 内重写全表并当场校验，存量库上等于把一次加列
-- 变成一次锁表迁移；可空 + 无默认 = 目录级变更，瞬时完成。
ALTER TABLE platform.platform_audit_event
    ADD COLUMN IF NOT EXISTS ticket_scope character varying(32);

-- ── 2. 回填存量：只回填**能从已存数据证明**的档位 ───────────────
-- 2.1 ALLOW 行的档位：本 action 的 ALLOW 只在四道闸门全过之后写出，
--     而闸门 3 对 ALL 一律先抛 SCOPE_NOT_MINTABLE → 能签出来的历史票据**只有 PLATFORM**。
UPDATE platform.platform_audit_event
SET ticket_scope = 'PLATFORM'
WHERE action = 'platform.rag.governance_ticket'
  AND decision = 'ALLOW'
  AND ticket_scope IS NULL;

-- 2.2 拒绝行按**原因码**反推档位（这三条映射都是代码里唯一可达的，不是猜测）：
--     SCOPE_NOT_MINTABLE     ← 只有 scope==ALL 才走到（PlatformRagGovernanceTicketService 闸门 3）
--     RATE_LIMITED / _UNAVAILABLE ← 闸门 4，排在档位闸门之后 → 当时必然是 PLATFORM
--     CHANNEL_DISABLED       ← 闸门 2，排在档位校验**之前** → 请求里的档位不可知，**留 NULL**
UPDATE platform.platform_audit_event
SET ticket_scope = CASE reason_code
                       WHEN 'SCOPE_NOT_MINTABLE' THEN 'ALL'
                       WHEN 'RATE_LIMITED' THEN 'PLATFORM'
                       WHEN 'RATE_LIMIT_UNAVAILABLE' THEN 'PLATFORM'
                   END
WHERE action = 'platform.rag.governance_ticket'
  AND decision = 'DENY'
  AND ticket_scope IS NULL
  AND reason_code IN ('SCOPE_NOT_MINTABLE', 'RATE_LIMITED', 'RATE_LIMIT_UNAVAILABLE');

-- ── 3. 数据就位后再收约束 ────────────────────────────────────
-- 值域恒等于 RagCorpusScope 三档。NULL 是合法常态（非票据类审计行为 NULL，
-- CHECK 对 NULL 求值为 NULL → 通过），所以本约束不会挡住任何既有写入方。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'platform.platform_audit_event'::regclass
                     AND conname  = 'platform_audit_event_ticket_scope_ck') THEN
        ALTER TABLE platform.platform_audit_event
            ADD CONSTRAINT platform_audit_event_ticket_scope_ck
            CHECK (ticket_scope IS NULL
                   OR ticket_scope IN ('PLATFORM', 'TENANT', 'ALL'));
    END IF;
END
$$;

-- ── 4. 注释与结构同源（否则下次 pg_dump 重导会把它抹掉，列退化成一个无说明的自由文本）──
-- ⚠️ COMMENT ON ... IS 只接受**单个字符串字面量**，不接受 || 拼接表达式
--    （实测：由反证脚本在空库复放快照时撞出来的语法错误）。
COMMENT ON COLUMN platform.platform_audit_event.ticket_scope IS '治理票据声明的档位＝作用域（同一根轴的两个说法：票据只带 rag_scope 一个声明，值域即 RAG 侧 RagCorpusScope 三档）。非票据类审计行为 NULL，这是常态而不是缺失。⚠️ 写入方只准落这三个规范值：被拒请求里的档位是调用方可控文本，规范集之外一律写 NULL —— 把未校验文本塞进带 CHECK 的列，表现是「审计写入 SQLException 把一次 503 拒绝变成 500」，即闸门自己制造故障。追责口径（方案 §5 A4）= actor_id（谁）+ 本列（哪档）+ decision + occurred_at。';

-- ── 5. 自证（给人看的答案，不是装饰）─────────────────────────
-- 5.1 列与约束都在位。
SELECT '列 ' || c.column_name || ' 可空=' || c.is_nullable || ' 类型=' || c.data_type
       || coalesce('(' || c.character_maximum_length || ')', '')
FROM information_schema.columns c
WHERE c.table_schema = 'platform' AND c.table_name = 'platform_audit_event'
  AND c.column_name = 'ticket_scope';

SELECT '约束 ' || con.conname || ' ' || pg_get_constraintdef(con.oid)
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
WHERE nsp.nspname = 'platform' AND rel.relname = 'platform_audit_event'
  AND con.conname = 'platform_audit_event_ticket_scope_ck';

-- 5.2 回填后的分布：非票据类行必须**一条都没被误填**（NULL 计数 = 该 action 之外的全部）。
SELECT '档位分布 ' || coalesce(ticket_scope, '<NULL>') || ' = ' || count(*)
FROM platform.platform_audit_event
GROUP BY ticket_scope
ORDER BY ticket_scope;

-- 5.3 治理票据这条 action 的行**不该再有 NULL 档位**，除了一种：CHANNEL_DISABLED
--     （闸门 2 在档位校验之前，档位本就不可知）——这一条是用来核对「回填没有过度推断」。
SELECT '票据行剩余 NULL 档位数（应全为 CHANNEL_DISABLED）= ' || count(*)
       || '，其中非 CHANNEL_DISABLED 的 = ' ||
       count(*) FILTER (WHERE coalesce(reason_code, '') <> 'CHANNEL_DISABLED')
FROM platform.platform_audit_event
WHERE action = 'platform.rag.governance_ticket' AND ticket_scope IS NULL;

COMMIT;

-- ⚠️ 本文件 COMMIT 之后，运行期仍必须同步改两处（缺一处就是「有列、不能写」）：
--    ① PlatformAuditPort.PlatformAuditEvent 增字段 + JdbcPlatformAuditRepository 的
--       INSERT/SELECT 列清单（列清单与占位符是**按位置**对齐的，漏一个会串列）；
--    ② PlatformRagGovernanceTicketService.record(...) 把档位传进去。
--    复核：scripts/platform-audit-ticket-scope-retrofit-check.sh

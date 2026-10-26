-- =====================================================================
-- V28：Tool 定义新增「远程 HTTP 请求」种类（第三十七批 · C37-1）
--
-- 对标设计文档：
--   docs/Java SaaS Agent 平台：规划与技术架构设计方案 v3.1.md
--     §4.2（Tool 种类 native / mcp / http）
--     §13.1（安全与外部 HTTP 工具：SSRF 与 Egress）
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 背景（第三十七批 · C37-1 · 管理台改造）：
--   cap_tool_def.kind 原本只有 'native'（内置）与 'mcp'（绑定 MCP Server），
--   管理台要能登记「直接打一个远程 URL」的 Tool，就必须有第三种 kind。
--
-- ⚠️ **HTTP 与 MCP 的关键差别（决定了本迁移为什么必须加约束而不是只加列）**：
--   MCP 工具的目标由 **MCP Server 的固定清单**约束 —— 远端操作名必须在清单内，
--   出站目标由 Server 的 endpoint 决定，且 Server 侧已有一套 SSRF 防线
--   （McpEndpointGuard / McpAddressPolicy）。
--   而 HTTP 工具的目标**就是配置里的那个 URL** —— 它绕开了 MCP 的固定清单，
--   因此「出站防护」从「别人的附带能力」变成「它的固有义务」。
--   于是 kind='http' 的行如果允许携带 MCP 绑定、或允许三件套残缺，
--   就会造出「这个 Tool 到底打哪里」不唯一的行 ——
--   那类行一旦入库，运行期走哪条路就取决于实现细节，安全边界就此失守。
--
-- ⚠️ **本迁移只做存储层的形状约束，不做运行期出站防护**：
--   SSRF 判定（DNS 解析后按实际 IP 阻断 loopback / 私网 / 链路本地 / 云元数据、
--   每次重定向重新校验、防 DNS rebinding）属于**运行期**义务，
--   由 HTTP 执行器落实（设计文档 §13.1）。存储层这里只能保证
--   「配置形状自洽」，不能保证「目标安全」—— 后者需要解析 DNS，
--   不是一条 CHECK 能做的。
--
-- ⚠️ **不改触发器**：forbid_published_def_mutation() 用 to_jsonb(OLD) 取列，
--    与列清单无关，新增列自动纳入「已发布行不可改写」的封禁范围。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 结构变更（可重复执行）
--
-- ⚠️ **幂等**：`ADD COLUMN IF NOT EXISTS` 是 PG 原生语法；
--    但 `ADD CONSTRAINT` **没有** `IF NOT EXISTS`（PG 17 实测），
--    故用 `pg_constraint` 存在性判断来跳过（同 V15 / V17 / V27 的做法）。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    ALTER TABLE cap_tool_def ADD COLUMN IF NOT EXISTS http_method TEXT;
    ALTER TABLE cap_tool_def ADD COLUMN IF NOT EXISTS http_url TEXT;
    ALTER TABLE cap_tool_def ADD COLUMN IF NOT EXISTS http_timeout_ms INTEGER;
END
$$;

COMMENT ON COLUMN cap_tool_def.http_method IS
    '远程 HTTP 请求工具的方法（GET/POST/PUT/PATCH/DELETE/HEAD）。仅 kind=http 时非空。';

COMMENT ON COLUMN cap_tool_def.http_url IS
    '远程 HTTP 请求工具的目标 URL（绝对地址，http/https）。仅 kind=http 时非空。';

COMMENT ON COLUMN cap_tool_def.http_timeout_ms IS
    '远程 HTTP 请求工具的单次请求超时（毫秒，正整数，上限 300000）。仅 kind=http 时非空。';

-- ---------------------------------------------------------------------
-- 2. kind 取值域扩容：native | mcp | http
--
-- ⚠️ **必须先 DROP 再 ADD**：CHECK 约束不能直接改条件。
--    DROP 之后立刻 ADD，中间存在一个「无 kind 约束」的窗口 ——
--    故两条语句放进**同一个事务**（迁移默认单事务执行），
--    窗口不存在于其他会话能看到的中间状态。
--
-- ⚠️ **既有行不受影响**：存量行的 kind 只有 'native' / 'mcp'，
--    且 http_* 三列全部为 NULL（新增列默认值即 NULL）——
--    它们同时满足下面两条新约束。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'cap_tool_def_kind_ck') THEN
        ALTER TABLE cap_tool_def DROP CONSTRAINT cap_tool_def_kind_ck;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'cap_tool_def_kind_ck') THEN
        ALTER TABLE cap_tool_def
            ADD CONSTRAINT cap_tool_def_kind_ck CHECK (kind IN ('native', 'mcp', 'http'));
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 3. MCP 绑定约束扩容：kind=http 时同样不得携带 MCP 绑定
--
-- ⚠️ **为什么必须显式写 http 分支，而不是只写「非 mcp 即为空」**：
--    原约束是 (kind='mcp' AND 有绑定) OR (kind='native' AND 无绑定)，
--    新增第三种 kind 后，若不补分支，kind='http' 的行将**无法满足任何一支**
--    → CHECK 直接拒绝所有 HTTP 工具（连合法的也进不去）。
--    而若把 native 那支放宽成「kind<>'mcp' 即无绑定」，
--    又会让 'native' 与 'http' 共用一支 —— 以后再加第四种 kind 时，
--    那支会**默默地**把新 kind 也放进来，是一处会长出 bug 的地方。
--    故三种 kind 各写各的分支：新增 kind 时必须显式表态。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'cap_tool_def_mcp_binding_ck') THEN
        ALTER TABLE cap_tool_def DROP CONSTRAINT cap_tool_def_mcp_binding_ck;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'cap_tool_def_mcp_binding_ck') THEN
        ALTER TABLE cap_tool_def
            ADD CONSTRAINT cap_tool_def_mcp_binding_ck CHECK (
                (kind = 'mcp'
                    AND mcp_server_id IS NOT NULL AND mcp_tool_name IS NOT NULL)
                OR (kind = 'native'
                    AND mcp_server_id IS NULL AND mcp_tool_name IS NULL)
                OR (kind = 'http'
                    AND mcp_server_id IS NULL AND mcp_tool_name IS NULL));
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 4. HTTP 配置一致性约束
--
-- ⚠️ **两侧都写死**：
--    · kind='http' → 三件套**必须齐全**且取值合法
--      （残缺 = 一个能入库、能发布、但永远跑不起来的 Tool）；
--    · kind≠'http' → 三件套**必须全空**
--      （非 HTTP 工具携带 http 配置 = 同一份配置里藏着两套互相矛盾的目标）。
--
-- ⚠️ **URL 形态在存储层就收窄**：只允许 http / https 绝对地址。
--    这**不是** SSRF 防线（它挡不住 169.254.169.254 这类地址），
--    只是把「显然不是 HTTP URL 的东西」挡在门外 —— 省掉一次无谓的运行期失败。
--    真正的 SSRF 判定在运行期（§13.1），那里才知道 DNS 解析后的实际 IP。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'cap_tool_def_http_binding_ck') THEN
        ALTER TABLE cap_tool_def DROP CONSTRAINT cap_tool_def_http_binding_ck;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'cap_tool_def_http_binding_ck') THEN
        ALTER TABLE cap_tool_def
            ADD CONSTRAINT cap_tool_def_http_binding_ck CHECK (
                (kind = 'http'
                    AND http_method IS NOT NULL
                    AND http_url IS NOT NULL
                    AND http_timeout_ms IS NOT NULL
                    AND http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD')
                    AND http_timeout_ms > 0
                    AND http_timeout_ms <= 300000
                    AND http_url ~* '^https?://[^[:space:]]+$')
                OR (kind <> 'http'
                    AND http_method IS NULL
                    AND http_url IS NULL
                    AND http_timeout_ms IS NULL));
    END IF;
END
$$;

COMMENT ON CONSTRAINT cap_tool_def_kind_ck ON cap_tool_def IS
    'Tool 种类取值域：native（内置）/ mcp（绑定 MCP Server）/ http（远程 HTTP 请求）。';

COMMENT ON CONSTRAINT cap_tool_def_http_binding_ck ON cap_tool_def IS
    'HTTP 工具的形状一致性：kind=http 时方法/URL/超时必须齐全且合法；'
    'kind≠http 时三者必须全空。⚠️ 这不是 SSRF 防线 —— 运行期出站防护见设计文档 §13.1。';

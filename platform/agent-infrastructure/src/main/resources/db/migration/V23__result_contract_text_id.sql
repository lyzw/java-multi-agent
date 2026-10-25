-- =====================================================================
-- V23：输出契约标识改为可读字符串（多级 Agent v1.1 · FR-027 · MA-T16）
-- =====================================================================
--
-- ⚠️ 为什么必须改（这是一个**真实缺陷**，不是风格调整）：
--
--   ① **对外契约**：`agent-capability-contracts-v1/schemas/common.schema.json`
--      的 `$defs/id` 明确是**可读字符串**（pattern `^[A-Za-z0-9][A-Za-z0-9._:-]*$`），
--      `resultContractRef` 形如 `answer-contract@1` —— id 部分**不是 UUID**。
--   ② **领域模型**：`CapResultContractDef.contractId()` 是 String，
--      治理通道（`CapConfigPublishController.ResultContractRequest`）直接收字符串。
--   ③ **DB 却是 UUID**：`cap_result_contract.contract_id UUID NOT NULL`，
--      仓储用 `?::uuid` 写入/查询。
--
--   三者不一致的后果（**生产上必然踩到**）：
--   - 发布链路：`ConfigPublishService` 用治理通道传来的字符串 id 登记契约
--     → `?::uuid` 转换失败 → **PSQLException → 整个发布事务回滚**
--     → 「发布契约」这个功能对**除 UUID 形式以外的任何 id 都不可用**；
--   - 运行链路：`ResultContractProvider.schemaFor("answer-contract@1")`
--     → 查询时 `?::uuid` 转换失败 → 抛异常 → 委派每次都 fail-closed。
--
--   ⚠️ **为什么此前没被发现**：单元级证据（`JdbcResultContractProviderTest`）
--   用 mock 连接，**UUID 转换根本没在 PG 上执行过** →
--   「调用了正确的 SQL 文本」被当成了「这段 SQL 能跑」。
--   补 DB 层 IT（`MultiAgentPersistenceIT`）后立刻暴露。
--
-- ⚠️ 为什么 agent id **不**一起改：
--   `agent.agent_id` / `cap_agent_def.agent_id` 是 **UUID**（平台既有设计，
--   Agent 的人类可读名在 `agent.name` 上，UNIQUE(tenant_id, name)），
--   `cap_agent_child_ref` 的复合外键正是指向它 —— 那是**自洽**的，不动。
--   契约是**新概念**，没有既有表约束，标识形态由 schema 契约定为可读字符串。
--
-- ⚠️ 迁移是**就地改列类型**，不改写任何行的**内容**：
--   已存在的 UUID 形式 id 转成文本后仍是同一个字符串（`::text`），
--   引用侧（`id@version`）用同一字符串解析，前后完全一致。

ALTER TABLE cap_result_contract
    ALTER COLUMN contract_id TYPE TEXT USING contract_id::text;

COMMENT ON COLUMN cap_result_contract.contract_id IS
    '输出契约标识（可读字符串，与 cap_tool_def.id / cap_skill_def.id 同构）。'
    '⚠️ 不可改为 UUID：对外 schema 契约（common.schema.json#/$defs/id）把契约 id '
    '定义为可读字符串，治理通道也按字符串接收；曾声明为 UUID 导致发布事务在 '
    '非 UUID id 上整笔回滚（V23 修复）。';

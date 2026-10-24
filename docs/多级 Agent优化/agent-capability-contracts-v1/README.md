# Agent / Skill / Tool / MCP 契约文件包

版本：v1.0-draft  
状态：设计评审稿，尚需与现有代码、DDL、05《分层授权决策规范》逐项对齐后冻结。  
Schema 方言：JSON Schema Draft 2020-12

## 1. 文件清单

- `schemas/agent.schema.json`：Agent 定义契约
- `schemas/skill.schema.json`：Skill 定义契约
- `schemas/tool.schema.json`：Tool 定义契约
- `schemas/mcp-server.schema.json`：MCP Server 定义契约
- `examples/*.json`：各实体示例
- `CONTRACT_RULES.md`：JSON Schema 之外必须实现的跨对象/运行期规则
- `README.md`：本文件

## 2. 核心依赖关系

`Agent -> Skill -> Tool`，以及 `Skill -> MCP Server + 操作 allowlist`。V1 禁止 Agent 直接引用 Tool/MCP，禁止 Skill 继承 Skill，禁止运行期动态 MCP 工具发现。MCP 工具调用应被包装成平台 Tool，并统一经过唯一的 `ToolInvocationGate`；禁止模型框架或 MCP 客户端绕过 Gate 直接执行。

## 3. 通用约定

- `id` 是稳定标识；`version` 是定义版本；已发布版本不可原地修改。
- `tenantId` 必须来自可信服务端上下文，不能信任请求体自行指定的租户。
- Schema 负责结构校验；跨对象引用、租户一致性、权限交集、风险上限、发布竞态由服务端验证。
- `DRAFT` / `VALIDATED` 对象不能被新 Run 引用；只有完整校验的 `PUBLISHED` 版本可以被绑定。
- 配置期校验、Run 绑定期快照、每次调用运行期重验是三个不同门禁。
- `null`、`[]`、字段缺失不可混为一谈，具体规则见 `CONTRACT_RULES.md`。
- 未知字段默认拒绝（`additionalProperties: false`）；扩展必须通过提升 `schemaVersion` 或显式扩展点完成。
- Schema 中的默认值不应依赖验证器自动填充；服务端必须实现统一规范化逻辑。

## 4. 字段命名

契约 JSON 统一使用 camelCase。历史数据库字段如 `credential_ref` 可以在持久化映射层转换，但 API/契约层不混用 snake_case。

## 5. 建议落地顺序

1. 先冻结 `CONTRACT_RULES.md` 中的安全不变量与空值语义。
2. 用四份 Schema 接入创建/更新 API 的结构校验。
3. 实现跨对象引用解析、租户校验、依赖闭包与风险校验。
4. 发布时生成不可变 release snapshot 与 closure fingerprint。
5. Run 创建时固化执行快照；每次调用由 `ToolInvocationGate` 重新校验。
6. 用正向、反向、异常、并发、撤权、恢复测试覆盖契约。

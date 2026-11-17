# CONTRACT_RULES.md — 跨对象与运行期契约规则

## 1. 对象职责与允许引用

| 对象 | 可以引用 | 禁止 |
|---|---|---|
| Agent | Skill ID + 明确版本/版本约束 | V1 直接引用 Tool、MCP；隐式获得全部 Skill |
| Skill | Tool ID + 明确版本/版本约束；MCP Server ID + 操作 allowlist | V1 `inherits` 非空；隐式获得 server 全部未来新增工具 |
| Tool | MCP Server ID + MCP tool name（仅 `kind=mcp`） | 运行时未登记 Tool；跳过 Gate |
| MCP Server | endpoint/command、凭据引用、固定工具清单及连接策略 | 明文凭据；V1 动态发现；不受限的任意 endpoint/command |

Agent 的 `roles` 只表示适用主体角色/调用资格，不授予调用者权限。最终主体授权来自可信认证上下文与 AuthorizationService。

## 2. 版本与发布

1. `DRAFT -> VALIDATED -> PUBLISHED -> DEPRECATED`。状态转换由服务端控制。
2. 发布必须在同一一致性边界内校验所有引用对象均存在、租户一致、状态可引用、版本约束已解析。
3. 发布指针更新必须使用 CAS（比较并交换）或等价的乐观并发控制；禁止仅取最大 sequence/版本号决定当前版本。
4. 已发布版本不可原地修改。修改产生新 revision/release。
5. Run 绑定时固化 agent release、完整依赖闭包、Schema/Prompt/策略版本、MCP 固定工具清单指纹和聚合 `configFingerprint`。
6. `configFingerprint` 应基于规范化、稳定排序后的有效配置和依赖闭包计算；不能只 hash Agent JSON。
7. 配置回滚不恢复已撤销的主体授权，也不降低 `authzVersion`。

## 3. `null`、缺省、空集合

规范化必须在进入业务逻辑前完成，所有服务共享同一规范化函数。

| 字段 | 缺省 | `null` | `[]` |
|---|---|---|---|
| `Agent.enabledSkills` | 创建时拒绝或由 API 明确补成 `[]`；不得隐式继承 | 拒绝 | 无 Skill 可用 |
| `Skill.declaredTools` | 拒绝发布 | 拒绝 | 不声明任何 Native Tool |
| `Skill.declaredMcp` | 规范化为 `[]` | 拒绝 | 不声明 MCP |
| `Skill.paramPolicies` | 规范化为 `[]` | 表示未配置 Skill 级附加策略；仍执行 Tool 级和强制策略 | 显式无 Skill 级附加策略；仍执行 Tool 级和强制策略 |
| `McpServer.exposedToolAllowlist` | 规范化为 `null`，即未配置服务端额外 allowlist | 未配置服务端额外 allowlist；调用仍必须受 Skill 操作 allowlist 限制 | 禁止该 Server 的所有工具调用 |
| `Skill.declaredMcp[].toolAllowlist` | 规范化为 `null` | 使用 Server 固定 allowlist 的范围；若 Server 也是 `null`，发布时拒绝形成无边界操作授权 | 禁止该 Server 的所有操作 |
| `Tool.riskLevel` | 拒绝发布或 fail-closed 解析为 `WRITE_EXTERNAL`；推荐创建期必填 | 同左 | 不适用 |
| `McpServer.dynamicDiscovery` | 规范化为 `false` | 规范化为 `false` | 不适用 |

说明：`null` 不代表绕过强制安全策略。MCP 的最终操作集合必须是各层显式允许集合的交集；任一明确空集合都使操作集合为空。V1 为固定清单，Server 端和 Skill 端至少一侧必须提供有限、可验证的工具名集合，推荐两侧都显式配置。

## 4. 能力闭包与风险

1. Agent 的有效 Skill 集只来自 `enabledSkills` 的显式引用（V1 无 bundles/tags/inherits）。
2. Skill 的有效 Tool 集来自 `declaredTools`；Tool 必须已发布、同租户/平台命名空间合法且未失效。
3. MCP 操作必须同时满足：Skill 绑定了 Server、操作在 Skill `toolAllowlist` 范围内、操作在 Server `exposedToolAllowlist` 范围内、操作存在于发布时固定清单中。
4. 不在固定清单中的 MCP 操作一律拒绝；清单/schema 发生变化时指纹失配，拒绝新调用并要求重新校验/发布。
5. 风险序：`READ < WRITE_PROFILE < WRITE_EXTERNAL`。Tool 风险不得高于 Skill `riskCeiling`。缺失/未知风险按最严风险处理，不能默认低风险。
6. Agent override 只能收紧：`removeSkills`、`removeTools`、降低 `riskCeiling`。禁止新增能力、提高风险上限或放松策略；非法覆盖应显式报错，不可静默修正。
7. `paramPolicies` V1 只支持 `in_allowlist`、`lte`。未知 operator、类型不符、缺少必需参数、无法求值均拒绝发布或拒绝调用。多个策略按 AND 组合；Skill 与 Tool 级策略同时生效。

## 5. 授权与执行决策分离

一次 Tool 调用至少产出以下四个维度：
- `authorizationDecision`: `ALLOW | DENY`
- `executionDecision`: `ALLOW | DENY`
- `executionStatus`: `NOT_STARTED | RUNNING | SUCCEEDED | FAILED | UNKNOWN`
- `reasonCode`: 稳定机器可读原因码

主体授权、能力链、风险上限、MCP 绑定、参数策略、配置指纹失败属于授权阶段 DENY。审批、预算、幂等冲突或执行准入失败属于执行准入 DENY，不能误记为主体未授权。

## 6. 每跳运行期重验与撤权

1. 唯一执行入口是 `ToolInvocationGate`；Native Tool 和 MCP-backed Tool 均必须经过它。
2. 每次调用都要沿 Agent→Skill→Tool/MCP 重新验证当前能力链和主体授权；不能只信任 Run 创建时的快照。
3. `authzVersion` 必须来自数据库/一致性存储，单调递增。多实例不得使用本地缓存版本作为权威。
4. 撤权以执行准入检查为线性化边界。执行准入点需再次检查授权版本/配置指纹，处理 TOCTOU。
5. 审批必须绑定 `closureFingerprint`、`authzVersion`、`approvalScopeHash`、`effectiveArgsHash`、目标 Tool/MCP 操作、有效期及一次性消费状态。任何绑定字段不一致、过期、重放或无法确认消费结果都拒绝执行。
6. 审批消费、预算预留、幂等登记与账本登记须定义事务边界和失败补偿；不能先产生外部副作用再补写关键准入事实。

## 7. MCP 接入安全

- `endpoint` 必须通过服务端 allowlist 校验，阻止 SSRF；禁止用户输入直接决定目标地址。
- `command` 只允许使用平台登记的执行器和固定命令模板；禁止 shell 拼接用户输入。
- 凭据只存 `credentialRef`，明文 secret 不进入配置、日志、错误消息或快照。
- 连接超时、读取超时、最大响应字节、并发上限必须有平台强制上限。
- MCP 返回内容均视为不可信输入；不得作为系统指令或授权依据。
- V1 `dynamicDiscovery=false`；工具清单由受控发布流程获取、审核、固定并计算指纹。
- MCP 调用由 MCP adapter 转成统一 Tool 调用，执行、重试、对账和审计仍经统一 Gate/ledger 语义。

## 8. 执行状态与账本分离

`executionStatus` 与 `ledgerPersistenceStatus` 是两个维度，不得合并：
- 执行状态描述业务动作是否开始、成功、失败或结果不确定。
- 账本持久化状态描述账本事实是否已可靠写入。
- `UNKNOWN` 只表示外部副作用结果不确定，不能用来表示普通错误或账本写入失败。
- 对于外部写操作，超时/连接断开且无法证明对端未执行时，应记录 `UNKNOWN` 并进入 reconcile/对账流程；在对账完成前不得盲目重试。
- 账本持久化失败必须触发可靠恢复/告警路径，不能被覆盖成业务执行失败或成功。

## 9. 稳定错误码（最低集合）

`SCHEMA_INVALID`, `REFERENCE_NOT_FOUND`, `REFERENCE_NOT_PUBLISHED`, `TENANT_MISMATCH`, `UNSUPPORTED_V1_FEATURE`, `SKILL_NOT_ENABLED`, `TOOL_NOT_DECLARED`, `MCP_NOT_BOUND`, `MCP_OP_NOT_ALLOWED`, `MCP_TOOL_DRIFTED`, `RISK_EXCEEDS_CEILING`, `PARAM_SCHEMA_FAIL`, `PARAM_POLICY_FAIL`, `AUTHZ_STALE`, `CONFIG_FINGERPRINT_STALE`, `APPROVAL_REQUIRED`, `APPROVAL_SCOPE_MISMATCH`, `APPROVAL_REPLAYED`, `BUDGET_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, `EXEC_ADMISSION_STALE`, `TOOL_TIMEOUT`, `TOOL_RESULT_UNKNOWN`, `LEDGER_PERSISTENCE_FAILED`。

错误响应应包含 `code`、`message`（脱敏）、`retryable`、`correlationId`；不得将内部凭据、敏感参数或完整外部响应回显给调用者。

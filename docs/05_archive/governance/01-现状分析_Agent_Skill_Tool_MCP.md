# 现状分析：Agent / Tool / MCP / Skill 各层职责边界

> 阶段 1 结论。基于对 `platform/agent-*` 模块的静态走读（非改动）。

## 0. 总览：这是「平台侧治理已成型、能力侧分层未打通」的状态

平台整体是**六边形 / 端口-适配器**架构（`cn.studykid.multiagent`），
Maven 多模块，依赖方向严格为 `api → application → ports ← infrastructure/worker → core`。
核心安全哲学（贯穿全仓、写在大量 Javadoc 里）可以浓缩为三条：

1. **模型只建议，平台执行。** 模型输出「工具调用建议」，
   校验/授权/审批/预算/落账/执行全部由平台侧完成。
2. **执行时重新授权（re-authorize at execution）。**
   任何敏感动作（工具执行、结果读取、事件订阅）在真正动作发生前，
   都要用「当前」授权重新校验——因为创建时的主体权限可能已被撤销。
3. **fail-closed（拿不准一律拒 / 取最严）。** 未登记的工具、未配置的键、
   未知风险级、预算预留失败……一律按最差情况处理，绝不静默放行。

在这套哲学下，**Tool 层是治理最完整的一层，Agent 层只到「版本快照」，
而 Skill 与 MCP 在代码中尚不存在**（仅见于旧设计文档）。

## 1. 各层现状与职责边界

### 1.1 Tool 层 —— 已落地、已强制，但是「扁平原子」

| 关注点 | 现状 | 文件 |
|---|---|---|
| 工具 SPI | `ToolHandler`：`toolName / inputSchema / riskLevel / validate / execute / reconcile` | `agent-ports/.../tool/ToolHandler.java` |
| 风险分级 | `ToolRiskLevel`：READ / WRITE_PROFILE / WRITE_EXTERNAL，**未登记 → 当 WRITE_EXTERNAL** | `agent-core/.../config/ToolRiskLevel.java` |
| 执行闸门 | `ToolInvocationGate`：不可绕过，串行 | `agent-worker/.../execute/ToolInvocationGate.java` |
| 审批策略 | `ApprovalPolicy` + `RiskBasedApprovalPolicy`（按风险级 + 审批来源，fail-closed） | `agent-application/application/approval` |
| 具体工具 | `GetStudentProfileTool`（只读）、`RecordWeaknessTool`（写画像） | `agent-infrastructure/.../tool` |
| 授权入口 | `AuthorizationService.Actions.TOOL_INVOKE`，角色→动作映射（`RoleBasedAuthorizationService`） | `agent-ports/ports/security` |

**执行链（`ToolInvocationGate` 注释原文即安全契约）**：
```
模型请求调用 → Schema 校验 → 执行时重新授权 → 幂等键 → 操作账本 → 审批 → 预算 → 执行 → 账本更新
```
配套三层 Attempt 语义（Run attempt / ToolOperation / ToolOperationAttempt）、
UNKNOWN 对账、预算预留（预留失败即拒、UNKNOWN 绝不释放预算）均已成型。

> **现状定性**：Tool 是**安全边界最清晰、约束最完整**的一层；
> 唯一缺失是**与 Agent 的绑定**：`ToolRiskLevel` 的 key 是工具名全局字典
> （`agent.tool.risk-level-map`），`ToolInvocationGate` 里 `handlers` 是全局 List——
> 即「任一 Agent 都能调用任一已注册工具」，**没有 per-Agent 的可用工具集**。

### 1.2 Agent 层 —— 到「不可变版本快照」为止

| 关注点 | 现状 | 文件 |
|---|---|---|
| Agent 实体 | `agent` / `agent_version` 表，租户隔离（RLS） | `JdbcAgentManageRepository` |
| 定义 | `definition` = **自由 JSONB 文本透传**，前端 textarea 输入，`AgentQueryPort` 不做 schema 校验 | `AgentQueryPort` / `AgentManagePort` |
| 版本 | 发布 = maxVersion+1，`publishVersion` 不可变；并发版本号冲突 → 409 | `AgentManagePort` |
| 治理动作 | `AGENT_MANAGE` 受控动作；创建/发布走 `AuditPort.recordRequired`（审计 fail-closed） | `AgentController` |
| 运行边界 | `RunConfigSnapshotFactory` 在 **Run 创建**时把成本/步数/审批固化成不可变快照（R-021） | `agent-application/config` |

> **现状定性**：Agent 有「谁建、什么版本、何时发布、审计留痕」，
> **但没有「这个 Agent 能用什么 Skill / 调什么 Tool / 接什么 MCP」的语义**。
> definition 是自由 JSON，平台**既不 parse 也不 enforce** 任何能力声明。
> 这是本需求要补的核心缺口。

### 1.3 Skill 层 —— 不存在

代码零引用。语义上属于「比 Tool 更高一级的能力包（编排/领域知识/子任务模板）」。
旧设计文档把它和工具系统并提，但未落实现。

### 1.4 MCP 层 —— 不存在，且设计文档已明确定性

旧设计文档（`Agent 功能模块：完整开发规划… 改造版.md`§560-568）已写明：
> 「MCP 仅作为协议适配之一；**能否被模型发现 / 当前租户能否用 / 能否访问具体资源，
> 仍由平台决定**。MCP 标准化接入（可选），**不能替代平台权限与治理层**。」

这与本仓「模型只建议、平台执行」的哲学完全一致：MCP 若引入，
是 Tool 的一种**来源/传输协议**，不是一层自治授权主体。

## 2. 已经可用的「继承/覆盖」范式（本设计的直接锚点）

平台已有一套成熟且 fail-closed 的配置继承机制，可直接复用为授权体系骨架：

| 范式 | 位置 | 可复用点 |
|---|---|---|
| 默认 ⊕ 覆盖 合并 | `EffectiveConfigResolver`：`yml 默认 ⊕ DB 覆盖`，只遍历声明过的键（防目录漂移），`db_override` 优先 | 层级覆盖 + 来源标注（source: yml/db_override） |
| 单调收紧棘轮 | `ConfigTighteningPolicy` + `ApprovalRequirement.isRelaxationTo/maxWith`：比较基准是**当前生效值**而非默认值（防「先紧后松」绕过） | 子层对父层只能收紧、审批要求 `maxWith` 取最严 |
| 创建时固化 | `RunConfigSnapshotFactory`：Run 创建时把配置冻成不可变快照，之后改配置不影响在途 Run | 授权快照固化进 Run，保证同一 Run 前后一致、事后可复现 |
| 写端点白名单 | `ConfigOverridePort.WRITABLE`：可写键**白名单**（新增键默认不可写，安全方向） | 能力授予写端点用白名单，防止「加了个键就自动放行」 |
| 拒绝/故障可区分 | `AccessDeniedException`（403）vs 认证 401 vs 故障 5xx | 权限校验失败语义明确，不复用故障通道 |

## 3. 阶段 1 结论（一句话）

**Tool 层安全成熟、Agent 层到版本快照为止、Skill/MCP 尚未存在；
且平台已具备「默认⊕覆盖 + 单调收紧 + 创建时固化 + 白名单」的继承范式。
因此「Agent ⊇ Skill ⊇ Tool/MCP」的分层授权，不是从零发明，而是把已有的 Tool 授权
沿 Agent→Skill 方向**补齐绑定 + 声明**，并纳入统一授权快照与校验时机即可。**

下一份文档（阶段 2）对该分层设计做合理性/正确性评估与改进建议。

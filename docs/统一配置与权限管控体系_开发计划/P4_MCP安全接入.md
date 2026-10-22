# W-PERM-P4 · MCP 安全接入：http/sse 适配、固定清单、SSRF 防御

> **阶段目标**：实现 MCP 传输层（仅 http/sse）适配与连接级/操作级分离，落地固定清单 + 操作级 allowlist + 指纹 + §5.8 新鲜度判定顺序，并以具名安全用例覆盖 SSRF / DNS rebinding / 重定向 / 凭据泄露。  
> **设计文档落点**：§4.4 MCP 可调用操作（规范化后再交集）、§5.8 MCP 新鲜度与操作级校验顺序、§6.5 MCP 安全（http/sse）、§3.2 N4/N7（动态发现/stdio 非 V1）、§2.2 威胁缓解 P2。

---

## 1. 目标与范围

| 范围 | 内容 |
|---|---|
| 传输 | 仅 `http` / `sse`；`dynamicDiscovery=false`；`exposedToolAllowlist` 的 `null`≠`[]`（§4.4） |
| 适配 | MCP-backed Tool 经同一 `ToolInvocationGate`（ADR-011）；包装为 `cap_tool_def(kind=mcp)` |
| 规范化交集 | `AllowedOps = {op ∈ (SkillAllowedOps ∩ ClosureOps) | ForcedSafety(op)=true}`（§4.4） |
| 新鲜度顺序 | §5.8：闭包指纹 → op∈快照 → op∈当前清单 → 逻辑 id 映射；同一类变化不得两种码 |
| SSRF/安全 | endpoint 防 SSRF、DNS 解析后 IP 校验、防 rebinding、禁止重定向至内网/元数据、凭据仅 `credential_ref`（永不入指纹/日志）、超时/限流/熔断、输出不可信 |
| 非目标 | 动态 MCP 工具发现（N4，V2）、stdio 传输（N7，非 V1 必做） |

> 与 P1 关系：P1 已校验 MCP Phase A 逻辑（P1-T6 桩）；本阶段提供真实适配与传输安全，替换桩。可并行于 P2/P3 的开发，但 P6 前须汇合。

---

## 2. 前置条件

- **W-PERM-P0** 完成：`cap_mcp_server_def` 表、`ClosureFingerprint` 基础设施。
- **W-PERM-P1** 完成：`InvocationValidator` MCP 校验骨架（本阶段替换为真实适配）。
- MCP server 清单与 endpoint 配置可用（V1 固定清单）。

---

## 3. 任务拆解

| ID | 任务 | 交付 | 依赖 |
|---|---|---|---|
| P4-T1 | `McpTransportAdapter`（http/sse）：连接管理、超时/限流/熔断 | 适配器 + 单元 | P0 |
| P4-T2 | `McpServerDefRepository` + 固定清单加载（ManifestOps）；`dynamicDiscovery` 强制 false 校验 | 仓库 + 配置校验 | P0 |
| P4-T3 | 操作级规范化（§4.4）：`exposedToolAllowlist`/`toolAllowlist` 的 null/[]/L 三态规范化后再交集 | 规范化器 + 单元 | P4-T2 |
| P4-T4 | 接入 `InvocationValidator` MCP 分支（替换 P1-T6 桩）：§5.8 四步顺序，唯一原因码 | 集成 + IT | P1, P4-T3 |
| P4-T5 | SSRF 防御：DNS 解析后 IP 校验、防 rebinding、禁止重定向至内网/元数据 | 安全过滤器 + 单测 | P4-T1 |
| P4-T6 | 凭据管理：仅 `credential_ref` 引用；连接级重验（凭据轮换后）；永不入指纹/日志 | 凭据解析 + 审计检查 | P4-T1 |
| P4-T7 | 输出不可信处理：MCP 返回结果视为不可信，过 Gate 边界再落盘 | 处理 + 测试 | P4-T1 |
| P4-T8 | 具名安全用例：S5a/S5b/S5c + SSRF/DNS/重定向/凭据 + 强制 `dynamicDiscovery=false`/非 stdio | 安全测试套件 | P4-T3~T7 |

---

## 4. 交付物

- `McpTransportAdapter`（http/sse）、`McpServerDefRepository`、操作级规范化器、SSRF 安全过滤器、凭据 `credential_ref` 解析。
- 真实 MCP 适配接入 `InvocationValidator`（替换 P1 桩）。
- 具名 MCP 安全测试套件（含 §6.5 各威胁的用例）。

---

## 5. 测试与验收映射（§9）

| §9 场景 | authz | exec | status | reasonCode | 落点 |
|---|---|---|---|---|---|
| S5a 闭包指纹变化 | DENY | NOT_EVALUATED | NOT_STARTED | CONFIG_FINGERPRINT_STALE | P4-T4 |
| S5b 操作级越界 | DENY | NOT_EVALUATED | NOT_STARTED | MCP_OP_NOT_ALLOWED | P4-T3/T4 |
| S5c 清单下线/漂移 | DENY | NOT_EVALUATED | NOT_STARTED | MCP_TOOL_DRIFTED / CONFIG_FINGERPRINT_STALE | P4-T4（按 §5.8 统一） |

| 安全用例 | 断言 | 落点 |
|---|---|---|
| SSRF | 内网/元数据地址被拦截 | P4-T5 |
| DNS rebinding | 解析后 IP 变更/内网被拦截 | P4-T5 |
| 重定向 | 重定向至内网/元数据被禁 | P4-T5 |
| 凭据 | 日志/指纹中无明文凭据；仅 `credential_ref` | P4-T6 |
| 非目标 | `dynamicDiscovery=true` 配置期拒；stdio 传输不启用 | P4-T2 |

---

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 重定向绕过 SSRF | 禁止跟随重定向至非允许网段；或完全禁重定向（按安全基线） |
| DNS rebinding 时序攻击 | 连接前二次解析并校验；连接绑定解析 IP |
| 凭据泄露 | 仅 `credential_ref`；轮换后连接级重验；审计扫描明文 |

---

## 7. 退出门禁

- [ ] 仅 http/sse 传输启用；`dynamicDiscovery` 强制 false（配置期拒），stdio 未启用。
- [ ] 操作级规范化（§4.4 null/[]/L 三态）后再交集，单测覆盖全部组合。
- [ ] §5.8 四步顺序实现且同一类变化不产生两种原因码。
- [ ] S5a/S5b/S5c 具名 MCP 用例四字段 + 原因码正确。
- [ ] SSRF/DNS rebinding/重定向/凭据 具名安全用例全绿；日志/指纹无明文凭据。

**出口**：M4 MCP 可信。

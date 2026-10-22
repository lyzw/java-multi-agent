# Agent / Skill / Tool / MCP 统一配置与权限管控体系 —— 可运行 Demo

本 Demo 是 PRD（`docs/权限管控体系/03_PRD_统一配置与权限管控体系.md`）的可执行实现，
验证「Agent 限定可用 Skill，Skill 声明可用 Tool 与 MCP」分层授权设计。

> **语言取舍**：平台为 Java（Spring Boot 4.1 / Jakarta 11），但本沙箱无 `mvn`/`mvnw` 且
> `javac` 为残桩，**无法编译/运行 Java**。故引擎用 **Python 3.11+ 标准库（零依赖）** 实现并验证，
> `java-port/` 提供与 `platform/` 同构的 **Java 端口参考**（Skill 接口 + PermissionResolver），
> 可直接落地到 agent-ports / agent-core，复用 `ToolHandler`、`AuthorizationService`、
> `ToolInvocationGate`、`EffectiveConfigResolver`、`ConfigTighteningPolicy`、RLS。

> ⚠️ **Demo 定位与验收边界（评审 P0-9）**：本 Demo 是 **Python 可运行验证原型**，用于证明《统一配置与权限管控体系》设计**在逻辑上可行**。**它不是生产代码，不构成任何 V1 验收证据**。V1 冻结要求 Java 平台（`cap_*` 对象 / 闸门集成 / DB `authzVersion` / `cap_*` 上的 RLS / MCP 接入 / 审计脱敏）实现并经由单元/集成/安全测试（见 09 §5 状态总览）。Demo 全绿仅说明设计自洽，不代表平台已落地。

## 目录
```
config/        配置文件（tools / mcp_servers / skills / agents / tenant_overrides）— JSON 运行态；PRD 含 YAML 等价
src/
  model.py    领域模型（Tool/Skill/McpServer/Agent/AuthContext/RiskLevel），与 ToolHandler 同构
  store.py    配置加载（单一事实源；Java 侧经 @ConfigurationProperties）
  resolver.py  继承合并 / 收紧覆盖 / 能力闭包计算（PRD §2.4 / §3.1 T2）
  validator.py 调用期逐跳重验（PRD §3.2：Agent→Skill→Tool/MCP + authzVersion 重授权）
  runtime.py   调用链路模拟（审批/预算/账本/执行 + 决策日志）
examples/call_chain_demo.py  典型调用链路示例（9 个场景）
tests/test_permission.py     单元测试（闭包 / 各 DENY 路径 / riskCeiling / MCP / 重授权）
java-port/    Java 端口参考（Skill.java / PermissionResolver.java）
```

## 运行
```bash
# 调用链示例
python examples/call_chain_demo.py

# 单元测试
python -m tests.test_permission
# 或：python tests/test_permission.py
```

## 验证的设计要点（对应 03 PRD + 05 决策规范 + 本轮 Review 修正 v1.3）
- ✅ 分层能力禁闭：Agent→Skill→{Tool,MCP} 单向，有效权限=闭包
- ✅ **统一决策状态模型（05 §0）**：`authorizationDecision` / `executionDecision` / `executionStatus` / `reasonCode` 四字段分离——授权失败≠审批/预算失败
- ✅ 主体授权**最先**校验（05 §2 顺序铁律），再能力链；运行期逐跳重验 + authzVersion 重授权（强一致，杜绝越权）
- ✅ MCP 拆分连接级/操作级 + **V1 固定清单 + 闭包级配置指纹失效**（无动态发现，06 §4 / P0-6）
- ✅ Skill riskCeiling 上限（防高危透传）
- ✅ 租户收紧覆盖（tenant_config_override，仅收紧，不放松）
- ✅ **requiredCaps V1 忽略语义（P0-3）**：缺失/为空不丢弃 Skill、不用于启用/闭包/授权判定（04 §3）；`skillBundles`/`capabilityTags` 同属 V1 忽略字段
- ✅ **未知风险等级（P0-1 / 05 §3.3）**：配置期拒绝发布、运行期 `authorizationDecision=DENY(RISK_LEVEL_UNKNOWN)`，**不降级为 WRITE_EXTERNAL 后放行**
- ✅ **执行准入点（TOCTOU）双契约（P0-7 / 05 §5）**：S3b 撤权先提交→阻断；S3f 准入先完成→在途不承诺绝对阻断（交由对账/补偿）
- ✅ **Phase B 结构校验单次（P0-4）**：`inputSchema` 必填校验与 Phase A 参数策略（业务权限 `in_allowlist`/`lte`）分离，不重复
- ✅ 预算不足 `ALLOW/DENY(BUDGET_EXCEEDED)` / 审批缺失 `ALLOW/DENY(APPROVAL_REQUIRED)` / 配置指纹失效 `DENY(CONFIG_FINGERPRINT_STALE)` / 外部未知→UNKNOWN
- ✅ 决策日志四字段结构化（含 runId/invocationId/authzVersion/configVersion/**closureFingerprint**/reasonCode，审计字段 100%）

## V1 范围说明（与 04 冻结边界一致）
> Demo 引擎实现了**V1+V2 超集**以便研究验证；但以下能力在 **V1 冻结边界**内**不启用语义**：
> 嵌套 Skill(`inherits`)、Capability Tag/Bundle、动态 MCP 发现、通用参数策略引擎。
> 配置中这些字段仅作"预留/手写两点"处理（见 04 §3）。
> 注：`requiredCaps` / `skillBundles` / `capabilityTags` 为 **V1 忽略字段**（接受写入但不参与启用/闭包/授权判定，P0-3 / 04 §3）；Demo 亦按忽略处理（不丢弃 Skill）。

### 端到端验收场景映射（评审最终意见 + 08 §1/§2）
| 场景 | Demo 覆盖 | 预期（四字段） |
|---|---|---|
| S1 已授权工具 | call_chain 1 / `test_S1` | ALLOW/ALLOW/SUCCEEDED |
| S2 未授权工具 | call_chain 3/4 / `test_S2` | DENY/DENY/NOT_STARTED (SKILL_NOT_ENABLED) |
| S3 主体撤权 | call_chain 8 / `test_S3` | DENY/DENY/NOT_STARTED (AUTHZ_STALE) |
| S3a 跨实例失效 | `test_S3a` | DENY/DENY (AUTHZ_STALE) |
| S3b 执行准入点阻断（撤权先提交） | `test_S3b` | ALLOW/DENY/NOT_STARTED (EXEC_ADMISSION_STALE) |
| S3c 并发单调递增 | `test_S3c` | 无丢失更新 |
| S3d 版本服务不可用 | `test_S3d` | DENY/DENY (fail-closed) |
| S3e 重复撤权无回退 | `test_S3e` | 版本继续递增 |
| S3f 在途操作不承诺绝对阻断（准入先完成） | `test_S3f_inflight_not_blocked` | ALLOW/ALLOW/SUCCEEDED（在途不撤销，05 §5 契约 2） |
| S4 Skill 被收紧 | call_chain 9 / `test_S4` | DENY/DENY (SKILL_NOT_ENABLED) |
| S5a 闭包级指纹变化（相关对象变更） | `test_S5a_closure_fingerprint` | DENY/DENY (CONFIG_FINGERPRINT_STALE) |
| S5b MCP 操作级越界 | `test_S5b` | DENY/DENY (MCP_OP_NOT_ALLOWED) |
| 结构校验(Phase B 单次) | `test_schema_fail_param_required_missing` | ALLOW/DENY/NOT_STARTED (PARAM_SCHEMA_FAIL) |
| S6 审批未通过 | `test_S6` | ALLOW/DENY (APPROVAL_REQUIRED) |
| S7 预算不足 | `test_S7` | ALLOW/DENY (BUDGET_EXCEEDED) |
| S8 外部结果未知 | `test_S8` | ALLOW/ALLOW/UNKNOWN |

运行 `python -m tests.test_permission` 时 `TestE2EAcceptance`（S1–S8 + S3a–S3e + S3f + S5a/S5b + 结构校验）必须全绿，方满足 V1 冻结门槛（04 §4）。

## 与 platform 的对接点
| Demo | platform 落点 |
|---|---|
| `Tool.kind=native/mcp` + `riskLevel` | `ToolHandler` + `ToolRiskLevel`（fail-closed） |
| `validator` 逐跳重验 | `ToolInvocationGate`（重授权/审批/预算/账本） |
| `authzVersion` 重授权 | `AuthorizationService.currentAuthzVersion` |
| `tenant_overrides` 收紧 | `tenant_config_override` + `EffectiveConfigResolver` + `ConfigTighteningPolicy` |
| `Skill` 接口 | 新增 `agent-ports/.../skill/Skill.java`（本 Demo `java-port/`） |

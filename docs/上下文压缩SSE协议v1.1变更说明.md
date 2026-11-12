# SSE 协议 v1.1 变更说明（上下文压缩，M6 冻结）

> **状态**：契约冻结 / G2 已对话确认放行，S3-M8 按 ADR-013 D49 与 G1 A.5 台账激活（M6 仍为历史 contract-only 时点）
> **版本**：v1 → **v1.1**
> **前置**：G1 已对话确认放行；G2 于 2026-11-12 对话确认放行（线下补签）。旧口径「G1 TL/QA 签核前不进入 M7」保留为历史门禁，当前须按 G1 A.5 的 S3-M8 台账「先登记、后升级守卫」
> **激活边界**：M8（服务端在 `usage` 与 `done` 之间发射 `context.checkpoint` + 请求侧 `contextState` **六项**静默丢弃校验）、M13（两前端同批发协议 v1.1 并携带 committed `contextState`）
> **权威代码**：`platform/agent-ports/.../agentchat/AgentChatEvent.java`、`AgentChatEventTest`；**权威决策**：ADR-013 D49

---

## 0. 一句话结论

**v1.1 只做追加，不改任何 v1 帧的一个字节。**
新增第 14 个可选事件 `context.checkpoint` 与 `done.contextStateVersion` 可选字段；
`context.checkpoint` 在 M6 **只定义不发射**，`Done(boolean)` 兼容构造保留，
`new Done(false).data()` 永久冻结为 `{"truncated":false}`。

---

## 1. 变更清单

| 对象 | v1 | v1.1（M6） | 运行时状态 |
| --- | --- | --- | --- |
| `run.start` … `error` | 13 类事件 | **逐字不变** | 正常发射 |
| `done` | `{"truncated":false}` | 增可选 `contextStateVersion`；null/空串时**仍产出 v1 旧字节** | 正常发射，新字段缺省 |
| `context.checkpoint` | 不存在 | 新增第 14 个 permitted record，wire name `context.checkpoint` | **M6 不发射**，M8 起在 `usage` 与 `done` 之间可选发射（旧帧相对次序 `answer → usage → done` 不变） |
| `WIRE_NAMES` | 13 项 | 14 项，含 `contextCheckpoint → context.checkpoint` | 冻结 |
| `AgentChatStreamRequest` / `AgentChatRequest` | 无 `contextState` | **M6 不加** | M8 加 `@Nullable contextState`；缺失 = 无压缩态 |
| 两前端 `agentChatApi.ts` | `default: return null` | **M13 mock：只解析不上送** | 解析 `context.checkpoint` / `done.contextStateVersion`，压缩态仅内存暂存；mock 阶段请求体仍无 `contextState`，S3-M8 / M13 同批发正式收发 |
| `ExecutionObserver` | `onContextTrimmed` | 增 `onContextCompacted`（default 空实现）；旧方法保留 | M6 仅契约，M7 接线才调用 |

---

## 2. 14 事件字典（冻结）

| # | 事件名 | data 形状 | 状态 |
| --- | --- | --- | --- |
| ① | `run.start` | `{"runId":…}` | v1 冻结 |
| ② | `route` | `{"agentId":…,"agentName":…,"reason":…,"candidates":[…]}` | v1 冻结 |
| ③ | `rag.query` | `{"query":…,"topK":…}` | v1 冻结 |
| ④ | `rag.hits` | `{"hits":[…],"injected":…}` | v1 冻结 |
| ⑤ | `skill` | `{"skillId":…,"skillName":…,"phase":…,"status":…,…}` | v1 冻结 |
| ⑥ | `tool` | `{"toolCallId":…,"toolName":…,"phase":…,"status":…,…}` | v1 冻结 |
| ⑦ | `handoff` | `{"from":…,"note":…}` | v1 冻结 |
| ⑧ | `thinking` | 裸文本 | v1 冻结，仅平台链 |
| ⑨ | `delta` | 裸文本 | v1 冻结 |
| ⑩ | `answer` | `{"text":…}` | v1 冻结 |
| ⑪ | `usage` | `{"inputTokens":…,"outputTokens":…}` | v1 冻结 |
| ⑫ | `error` | `{"kind":…,"message":…}` | v1 冻结 |
| ⑬ | `done` | `{"truncated":…,"contextStateVersion"?:…}` | **追加可选字段** |
| ⑭ | `context.checkpoint` | `{"state":…,"upToTurn"?:…,"policyVersion":…,"tier":…,"fallback":…,"digest":…}` | **新增可选事件**；声明序号 ⑭，但线序在 ⑪ `usage` **之后**、⑬ `done` **之前** |

> ⚠️ 声明顺序与线序刻意解耦：Java `permits` / `WIRE_NAMES` 把新类型追加为第 14 个，
> 保持旧 13 个类型的相对次序不变；ADR-013 D49 冻结实际发射时序为
> `answer → usage → context.checkpoint? → done`，因为 `done` 仍是应用层终帧。
> ⛔ 不插到 `answer` 与 `usage` 之间，避免旧帧相邻关系漂移。

---

## 3. 新事件 `context.checkpoint`

### 3.1 载荷示例

```json
{"state":"eyJnb2FsIjoi6K+V6aKY5ZCN6Zeu6aKYIn0...","upToTurn":12,
 "policyVersion":"cp_v1_8f3…","tier":2,"fallback":false,"digest":"sha256:9f2c…"}
```

### 3.2 字段契约

| 字段 | 类型 | 必填 | 含义 / 约束 |
| --- | --- | --- | --- |
| `state` | string | 是 | **不透明 base64**。载荷 = 结构化状态 JSON（12 字段，**不含原始对话正文**）+ 元数据（tenantId digest、agentId/version、`exp`、`epoch`）；用独立密钥 `AGENT_CONTEXT_STATE_HMAC_KEY` 签名 |
| `upToTurn` | integer | 否 | 已吸收轮次；为 `null` 时**省略该字段**，不写 `null` |
| `policyVersion` | string | 是 | 策略指纹；服务端不匹配即丢弃 rebuild，绝不复用旧策略摘要 |
| `tier` | integer | 是 | 触发水位 `1/2/3`（W1/W2/W3）；W0 不发本事件 |
| `fallback` | boolean | 是 | W3 摘要失败回落 W2 时为 `true`，绝不伪造摘要 |
| `digest` | string | 是 | 状态摘要 `sha256:…`，缓存与幂等键 |

### 3.3 不可信纪律（服务端 M8 实现：六项静默丢弃）

`state` 是**客户端携带的不可信数据**，不是可信指令。下一轮进入压缩链前必须逐项校验，
任一命中即**静默丢弃 + 退回全量历史 + 服务端确定性裁剪**，不报错、不拒绝用户：

1. HMAC 验签失败；
2. `exp` 过期；
3. `policyVersion` 与当前已裁决策略不一致；
4. tenantId digest 与当前租户不一致；
5. 超过 `max-state-chars`；
6. `upToTurn > 当前 history 长度`（防止把未来状态当历史）。

> **D49 口径**：开发计划与本文早期「五项」指前五项**状态自身校验**；第 6 项是**历史游标一致性**校验，S3-M8 必须一并实现。G3 最小必测四组为验签 / 过期 / 版本不符 / 超长，六项全部实现并逐项留痕。

注入时必须经 `PromptGuard.wrapCompactedContext()` 包成
`<compacted_context trust="untrusted">` 的 **USER 消息**，
⛔ 绝不拼进 `systemPrompt`；`state` 内容不加密，HMAC 只防篡改不防泄露，
因此绝不能塞原始对话正文（PIPL / 未成年人最小保留）。

### 3.4 旧客户端兼容

两前端解码器对未知事件仍为 `default: return null`，
因此旧客户端收到 `context.checkpoint` 会直接忽略，不会报错、不会污染对话状态。
**M13 mock 已落地**：两前端新增第 14 事件解析与 `done.contextStateVersion` 可选字段，
但仍**只解析不上送**——`AgentChatRequest` / 请求体不含 `contextState`，服务端发射属 M8。
`ContextCompressionM6GuardTest#frontendProtocolV11MockIsParseOnly` 与
`ContextCompressionM13GuardTest` 双向锁死这一边界；G1 TL/QA 签核前不进入 M7 接线。

> **S3-M8 激活**：G2 已于 2026-11-12 对话确认放行（线下补签）；两前端与服务端**同批发**，上述 mock 阶段守卫按 G1 A.5 台账升级为「正式收发 + 不解析 / 不持久化 + 旧客户端兼容」，旧负面覆盖不删除。

---

## 4. `Done` 可选字段与 v1 字节冻结

```java
// v1 兼容构造：所有旧调用点零改动
new AgentChatEvent.Done(false).data()
// => {"truncated":false}          ← 永久冻结

new AgentChatEvent.Done(false, null).data()
// => {"truncated":false}          ← 缺省等价

new AgentChatEvent.Done(false, "  ").data()
// => {"truncated":false}          ← 空串视作未提供

new AgentChatEvent.Done(false, "cp_v1_8f3").data()
// => {"truncated":false,"contextStateVersion":"cp_v1_8f3"}
```

**铁律 #10 约束**：`Done` 保留 `Done(boolean)` 兼容构造，但它必须收敛到
`Done(boolean, String)` 全参构造；⛔ 绝不保留第二份独立的 `data()` 拼装逻辑。
`AgentChatEventTest` 已冻结旧 13 帧的全部 `data()` 字面量（含 `Done(false)`），
并以 `Done.class.getDeclaredConstructors()` 断言构造路径数量。

---

## 5. 请求侧边界（M6 明确不改）

- M6 **不**给 `AgentChatStreamRequest` / `AgentChatRequest` 增加 `contextState`；
- **M13 mock 仍不**改请求体、**不**引入 localStorage/sessionStorage 持久化：只解析帧、
  只在内存暂存压缩态，刷新即降级为全量历史；
- 请求字段与 carry-over **六项**丢弃校验（D49）属于 **M8**；前端正式收发 `contextState` 属于
  **M13 收口**，与 M8 同批发；
- 因此 M6 + M13 mock 的服务端行为与今天**逐位一致**：既没有新请求头/字段，也没有新 SSE 帧；
  唯一变化是两前端**能识别**服务端尚未发射的第 14 事件。

---

## 6. 兼容性矩阵

| 组合 | 表现 | 结论 |
| --- | --- | --- |
| 旧客户端 + v1.1 服务端 | 忽略 `context.checkpoint`；`done` 多出的字段被可选解析/忽略 | ✅ 可用，无压缩态复用 |
| v1.1 客户端 + 旧服务端 | 收不到 `context.checkpoint`，`done.contextStateVersion` 缺失 | ✅ 可用，按「无压缩态」降级为全量历史 |
| 滚动发布中的混合实例 | 无共享状态假设；每轮请求自带状态，实例间不依赖 | ✅ 可接受 |
| 任一实例在 M6 / M13 mock 阶段提前发射 `context.checkpoint` | 旧客户端忽略；但阶段契约禁止，须等 G2 放行后的 S3-M8 且守卫升级完成 | ⛔ M6 / M13 mock 阶段不允许；S3-M8 按 D49 激活 |

---

## 7. 配套内部端口：`ExecutionObserver.onContextCompacted`

```java
default void onContextCompacted(@NonNull String tenantId, @NonNull String runId,
                                int tier, int beforeTokens, int afterTokens,
                                int droppedTokens, boolean fallback) {
    // M6 contract-only：默认无副作用
}
```

- **default 空实现**：既有 4 个 `ExecutionObserver` 实现者零改动即可编译；
- **旧 `onContextTrimmed` 保留且仍为抽象**：CH-8 要求 `[D11]` 关闭态调用点逐位等价；
- `ExecutionObserverChain` 覆写新方法并逐观察者转发，单个观测者抛异常只记 WARN，不影响其他观测者；
- `droppedTokens = max(0, beforeTokens - afterTokens)` **由调用方算好传入**；新契约不提供
  `droppedRounds` / `droppedTools`，⛔ 观测者不得从 tier 倒推或伪造这两个维度；
- M7 接线纪律：关闭态继续走 `onContextTrimmed`；开启态由统一装配入口调用
  `onContextCompacted`；**同一轮不要两个事件都发**，否则指标双计。需要旧维度的观测者
  由 M7 适配层显式决定，不在端口层造数字。

---

## 8. 验证证据（M6-d 收口）

| 项 | 结果 |
| --- | --- |
| `ContextPortsContractTest` | **8/8**（四端口、中性签名、值对象 fail-closed、无渲染便捷方法） |
| `AgentChatEventTest` | **7/7**（14 事件映射、旧 13 帧字节冻结、`Done` 可选字段、checkpoint 载荷与 fail-closed、控制字符转义） |
| `ExecutionObserverChainTest` | **5/5**（default 空实现、链转发、异常隔离、旧 trim 不变、源码依赖中性） |
| `ContextCompressionM6GuardTest` | **8/8**（端口中性、请求体无 `contextState`、旧帧字节冻结、checkpoint 只定义不发射、三链路零接线、Observer default、前端未知事件忽略、文档门禁） |
| `platform` 全 reactor `test-compile` | **父 POM + 13 模块 BUILD SUCCESS**（`Done` 新增组件未破坏任何旧调用点） |
| 撤点反向验证 | **RV-38…RV-46（M6-a 9）+ RV-47…RV-57（M6-b 11）+ RV-58…RV-64（M6-c 7）+ RV-65…RV-73（M6-d 9）= 36 组**全部精确命中目标守卫并 md5 复原 |
| platform `clean verify` | 13 模块 **BUILD SUCCESS**，单测 **3012** / IT **638**，0 失败 0 错误、5 既有跳过（IT 库 `agent_it`，结构 71/44/52） |
| rag `clean verify` | 10 模块 **BUILD SUCCESS**，单测 **756** / IT **65**，0 失败 0 错误（IT 库 `study_rag_it`） |

> M6-d 已补齐 `platform-bootstrap` 源码守卫并跑完 platform + rag 双 reactor `clean verify`：
> 请求体不含 `contextState`、两前端未知事件仍忽略、三条链路零 M6 运行时接线、
> `context.checkpoint` 只定义不发射。本报告与 ADR-013 D33–D35 为协议 v1.1 的冻结依据；
> **G1 TL/QA 签核前不进入 M7**；M8 服务端发射与 M13 两前端解析必须同批发布。
> G1 与 G2 已分别于 2026-11-11 / 2026-11-12 对话确认放行（线下补签）；S3-M8 激活按 ADR D49 与 G1 A.5 新台账执行，旧冻结证据保留为历史基线。

---

## 9. 上线纪律（不可跳过）

1. M6 只冻结代码契约；**不得**在当前分支发射 `context.checkpoint`、调用
   `onContextCompacted`、或给请求 DTO 加 `contextState`。
2. 三套活库执行 `07_model_catalog_context_upgrade.sql` 是 S2 灰度硬前置
   （本地 `agent_it` 已补，远程 RDS 待 DBA 维护窗口）。
3. G1 报告中的 D25 指标口径与 SSE/Console 结构证据边界**已对话确认放行**（线下补签）；S3-M8 接线触发的守卫升级须先在 G1 A.5 新台账登记（#60 已完成），未登记不得改守卫。
4. M8 服务端发射 + M13 两前端同批发，必须在**同一发布窗口**完成；
   不允许只发一侧造成协议 v1.1 半升级。线序、压缩态生产者与六项丢弃校验按 ADR-013 D49 执行。

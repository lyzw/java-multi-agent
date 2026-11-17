# AI 请求完整逻辑梳理

> 生成时间：2026-10-29
> 分析范围：java-multi-agent 工程中所有与 AI 模型调用相关的代码

---

## 一、代码文件清单（按模块/层次分组）

### 1. 端口层（ports）—— 领域抽象，零框架依赖

| 文件 | 角色 |
|---|---|
| `platform/agent-ports/src/main/java/.../ports/model/ModelGateway.java` | **核心接口**：模型调用端口，定义 `complete(ModelRequest)` 契约与 `ModelErrorKind` 归一化枚举 |
| `platform/agent-ports/src/main/java/.../ports/model/ModelRequest.java` | **请求值对象**：模型调用参数（modelId / systemPrompt / userContent / temperature / maxTokens / timeout / toolSchemas / messages） |
| `platform/agent-ports/src/main/java/.../ports/model/ModelResponse.java` | **响应值对象**：模型输出（content / toolCalls / usage / finishReason） |
| `platform/agent-ports/src/main/java/.../ports/model/ModelMessage.java` | **对话消息**：多轮对话载体（USER / ASSISTANT / TOOL 角色，含 toolCallId / toolCalls 回显） |

### 2. 基础设施层（infrastructure）—— 真实实现

| 文件 | 角色 |
|---|---|
| `platform/agent-infrastructure/src/main/java/.../infrastructure/model/AbstractModelGateway.java` | **抽象基类**：归一化兜底（超时/限流/鉴权 → ModelErrorKind），`callWithTimeout()` 超时约束，异常分类 |
| `platform/agent-infrastructure/src/main/java/.../infrastructure/model/MiniMaxModelGateway.java` | **真实适配器**：MiniMax OpenAI 兼容 API 实现，HTTP 调用 + 请求体组装 + 响应解析 |
| `platform/agent-infrastructure/src/main/java/.../infrastructure/model/FakeModelGateway.java` | **测试替身**：故障注入（TIMEOUT/RATE_LIMITED/PROVIDER_ERROR 等），供契约测试使用 |
| `platform/agent-infrastructure/src/main/java/.../infrastructure/model/CredentialRedactor.java` | **凭据脱敏**：正则替换 API Key/Authorization 等敏感字段 |

### 3. Worker 执行层（agent-worker）—— 请求发起与编排

| 文件 | 角色 |
|---|---|
| `platform/agent-worker/src/main/java/.../worker/execute/RunExecutionLoop.java` | **根循环**：主执行循环，发起模型调用、处理工具回灌、状态提交 |
| `platform/agent-worker/src/main/java/.../worker/execute/WorkerChildAgentRunner.java` | **子执行器**：多级 Agent 子调用，发起模型调用、处理委派上下文 |
| `platform/agent-worker/src/main/java/.../worker/execute/ToolInvocationGate.java` | **工具闸门**：模型输出 toolCalls 后的安全校验（授权/审批/预算/幂等） |
| `platform/agent-worker/src/main/java/.../worker/execute/ToolOutcomeRenderer.java` | **回灌渲染器**：将工具执行结果渲染为多轮消息（assistant+tool 成对） |
| `platform/agent-worker/src/main/java/.../worker/execute/ContextBudgetTrimmer.java` | **上下文裁剪**：按 token 预算裁剪历史消息序列 |

### 4. 装配层（bootstrap）

| 文件 | 角色 |
|---|---|
| `platform/platform-bootstrap/src/main/java/.../bootstrap/config/WorkerExecutionConfig.java` | **Spring 装配**：根据 api-key 配置选择 MiniMaxModelGateway 或 FakeModelGateway，注入执行链 |

### 5. 入口层（dispatch）

| 文件 | 角色 |
|---|---|
| `platform/agent-worker/src/main/java/.../worker/dispatch/RunDispatchConsumer.java` | **MQ 消费者**：领取租约 → 调用 RunExecutionLoop 执行 → 根据结局映射 MQ 处置 |

---

## 二、完整调用流程（按顺序）

### 阶段 0：装配（应用启动时）

```
WorkerExecutionConfig.modelGateway()
  ├─ agent.model.minimax.api-key 已配置
  │   └─ → new MiniMaxModelGateway(baseUrl, apiKey, defaultModel)
  └─ 未配置
      └─ → new FakeModelGateway(FaultMode.NONE, cannedResponse)
        （⚠️ 日志警告：非真实模型调用）
```

`modelGateway()` Bean 被注入到：
- `RunExecutionLoop`（根循环）
- `WorkerChildAgentRunner`（子 Agent 执行器）

### 阶段 1：触发入口（MQ 消息到达）

```
RunDispatchConsumer.consume(eventType, payload, workerId)
  ├─ 校验 eventType == "RUN_DISPATCH"
  ├─ OutboxMessage.fromPayload(payload)  解析消息
  ├─ claimPort.claimSpecificRun(tenantId, runId, workerId, leaseDuration)
  │   └─ 领取租约 → 拿到 LeasedTask（含 fencingToken, deadlineAt, attempt）
  └─ executor.execute(tenantId, task)  → 调用 RunExecutionLoop.execute()
```

### 阶段 2：执行前检查

```
RunExecutionLoop.execute(tenantId, task, agentConfig, snapshot, input)
  ├─ §2.1 isCurrentGeneration()   → 世代失效则放弃（节省成本）
  ├─ §2.1.1 rejectIfTenantBlocked() → 租户 SUSPENDED/DECOMMISSIONED 则拒绝
  ├─ adopter.adopt()              → 在途委派收敛（MA-T12）
  └─ 初始化消息序列: messages = [ModelMessage.user(renderInput(input))]
```

### 阶段 3：循环体（每轮一次模型调用）

```
while (steps < snapshot.maxSteps())
  ├─ §2.1.2 isCancelled()         → 协作式取消检查
  ├─ §2.1.3 deadlineAt 检查       → 根 Run 业务超时（FR-024）
  ├─ §2.1.1 rejectIfTenantBlocked() → 每轮再验证租户状态
  │
  ├─ ContextBudgetTrimmer.trim(messages, contextTokenBudget)
  │   └─ 按 token 预算裁剪历史，保留最近若干轮
  │   └─ 生成裁剪说明 notice → 拼入 systemPrompt
  │
  ├─ 构造 ModelRequest（关键代码段 ①）
  │   ModelRequest request = new ModelRequest(
  │       agentConfig.modelId(),
  │       systemPrompt + trimmed.notice(),
  │       renderInput(input),
  │       agentConfig.temperature(),
  │       agentConfig.maxTokens(),
  │       agentConfig.timeout(),
  │       agentConfig.toolSchemas(),
  │       trimmed.messages()       // ← 带消息历史
  │   )
  │
  ├─ ModelResponse response = modelGateway.complete(request)  ← 核心调用
  │
  ├─ 无 toolCalls → 编造出处检查 → 敏感信息检测 → commitSuccess()
  │
  └─ 有 toolCalls → toolGate.invokeAll() → 回灌 → 续租 → 下一轮
```

### 阶段 4：模型网关调用（核心路径）

```
modelGateway.complete(ModelRequest)
  │
  ├─ MiniMaxModelGateway.invokeGuarded(request)
  │   ├─ buildPayload(request)          → 组装 OpenAI 兼容请求体
  │   │   ├─ system → systemPrompt
  │   │   ├─ messages → effectiveMessages()（USER/ASSISTANT/TOOL）
  │   │   ├─ tools → translateToolSchemas(toolSchemas)
  │   │   └─ temperature / max_tokens / stream=false
  │   ├─ HttpRequest.newBuilder(endpoint)
  │   │       .timeout(timeout)
  │   │       .header("Authorization", "Bearer " + apiKey)
  │   │       .POST(body)
  │   ├─ callWithTimeout(() -> sendAndParse(http), timeout)
  │   │   ├─ client.send(http, BodyHandlers.ofString())
  │   │   ├─ 非 2xx → ProviderStatusException → classify() → ModelErrorKind
  │   │   ├─ parseCompletion(responseBody)
  │   │   │   ├─ 检查 base_resp.status_code（MiniMax 业务错误）
  │   │   │   ├─ 剥离推理草稿 stripReasoning() → <think> 标签
  │   │   │   ├─ 解析 tool_calls → ToolCallRequest
  │   │   │   └─ 构建 usage + mapFinishReason()
  │   │   └─ 返回 ModelResponse
  │   └─ catch → 归一化为 ModelGatewayException
  │
  └─ AbstractModelGateway.complete() 兜底
      ├─ 已归一化异常 → 原样保留 kind + CredentialRedactor 脱敏
      └─ 未归一化异常 → 兜底 PROVIDER_ERROR + 日志告警
```

### 阶段 5：响应处理

**分支 A：无工具调用（纯文本输出）**

```
HallucinationDetector.assertNoFabricatedSource(content)
  ├─ 通过 → commitSuccess(tenantId, task, content) → 写 SUCCEEDED 终态
  └─ 失败 → commitFailure(..., "SAFETY_FABRICATED_SOURCE") → 写 FAILED 终态
```

**分支 B：有工具调用**

```
toolGate.invokeAll(tenantId, task, toolCalls, scope, delegationContext)
  ├─ Schema 校验 → 授权 → 幂等键 → 账本预留 → 审批 → 预算 → 执行 → 账本更新
  └─ 返回 ToolInvocationResult（succeeded/failed/rejected/unknown）

ToolOutcomeRenderer.renderRound(toolCalls, toolResult)
  ├─ 生成 assistant 消息（带 toolCalls 回显）
  └─ 为每个调用生成 tool 消息（带 tool_call_id 对齐）
  → 并入 messages 序列，进入下一轮

续租: claimPort.renewLease() → 失败则放弃执行
```

### 阶段 6：异常处理

```
catch ModelGateway.ModelGatewayException e
  ├─ e.kind().retryable() == true  → RETRYABLE_FAILURE（nack 重投）
  │   TIMEOUT / RATE_LIMITED / PROVIDER_ERROR
  └─ retryable() == false → commitFailure(..., "MODEL_" + kind)
      INVALID_REQUEST / UNAUTHORIZED / CONTENT_BLOCKED
```

---

## 三、关键代码片段与作用

### 3.1 端口接口（归一化契约）

```java
// ModelGateway.java — 核心端口
public interface ModelGateway {
    @NonNull ModelResponse complete(@NonNull ModelRequest request);

    enum ModelErrorKind {
        TIMEOUT(true),        // 可重试
        RATE_LIMITED(true),   // 可重试
        PROVIDER_ERROR(true), // 可重试
        INVALID_REQUEST(false), // 重试无意义
        UNAUTHORIZED(false),    // 禁止重试
        CONTENT_BLOCKED(false); // 禁止重试
    }
}
```
**作用**：定义了领域层与供应商之间的唯一契约，所有供应商差异在此收敛为 6 种归一化错误。

### 3.2 请求构造（RunExecutionLoop.java:376-385）

```java
ModelRequest request = new ModelRequest(
    agentConfig.modelId(),
    systemPrompt,                 // 可信指令载体
    renderInput(input),           // 用户输入（带注入防护）
    agentConfig.temperature(),
    agentConfig.maxTokens(),
    agentConfig.timeout(),
    agentConfig.toolSchemas(),
    trimmed.messages()            // 多轮消息历史（可裁剪）
);
```
**作用**：将 Agent 配置 + 运行约束 + 消息历史组装为中性请求对象。`systemPrompt` 与消息序列分离，防止系统指令被混入不可信数据。

### 3.3 HTTP 调用与响应解析（MiniMaxModelGateway.java:78-88, 204-227）

```java
protected @NonNull ModelResponse invokeGuarded(@NonNull ModelRequest request) {
    String body = MAPPER.writeValueAsString(buildPayload(request));
    HttpRequest http = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return callWithTimeout(() -> sendAndParse(http), timeout);
}
```
**作用**：真实 HTTP 调用，带超时约束。`callWithTimeout` 由基类提供，确保单次模型调用不超时。

### 3.4 工具回灌成对结构（ToolOutcomeRenderer.java:83-103）

```java
// 必须回灌成「assistant 带调用 + tool 带同一标识」的成对结构
List<ModelMessage> messages = new ArrayList<>();
messages.add(ModelMessage.assistantToolCalls(describeIntent(calls), echoes));
for (ModelMessage.ToolCallEcho echo : echoes) {
    messages.add(ModelMessage.toolResult(
        echo.toolName(), outcomeText(echo.toolName(), toolResult), echo.callId()));
}
```
**作用**：2026-10-28 真实 MiniMax 实测后重写。旧实现只发 tool 消息且 tool_call_id 填工具名，导致 MiniMax 返回 400 "tool result's tool id not found"。新实现保证 assistant 与 tool 消息成对出现且标识一致。

### 3.5 异常兜底（AbstractModelGateway.java:39-57）

```java
public @NonNull ModelResponse complete(@NonNull ModelRequest request) {
    try {
        return invokeGuarded(request);
    } catch (ModelGatewayException e) {
        // ⚠️ 已归一化的异常必须原样保留 kind
        throw new ModelGatewayException(e.kind(),
                CredentialRedactor.safeMessage("模型调用失败[" + e.kind() + "]", e.getCause()));
    } catch (RuntimeException e) {
        // ⚠️ 兜底：未归一化一律 PROVIDER_ERROR
        log.warn("模型调用出现未归一化异常: {}", CredentialRedactor.redactMessage(e));
        throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR,
                CredentialRedactor.safeMessage("模型调用失败[PROVIDER_ERROR]", e));
    }
}
```
**作用**：关键防御。已归一化的异常若被重新归一化，会把「不可重试」误判为「可重试」，触发无限重试。

### 3.6 凭据脱敏（CredentialRedactor.java:42-50）

```java
public static @Nullable String redact(@Nullable String text) {
    // 匹配 sk-xxx / ghp_xxx / AKIAxxx / xoxb-xxx 等
    String result = API_KEY_PATTERNS.matcher(text).replaceAll(MASK);
    // 匹配 Authorization: Bearer xxx / api-key: xxx
    result = HEADER_PATTERN.matcher(result).replaceAll(mr -> mr.group(1) + "=" + MASK);
    return result;
}
```
**作用**：任何供应商 SDK 输出的字符串（异常消息、响应体）在进入日志前必须经此脱敏。

---

## 四、潜在问题与不清晰之处

### 4.1 🔴 高风险：FakeModelGateway 默认响应可能掩盖装配问题

**位置**: `WorkerExecutionConfig.java:129-147`

当 `agent.model.minimax.api-key` 未配置时，系统自动装配 `FakeModelGateway`，返回一个硬编码的假响应。

**问题**：
- 调用链路看起来"通了"，但实际上从未调用真实模型
- `ZeroRetentionGate` 仍按供应商判定，真实学生数据在非确认清单供应商下会被拒绝，但 Fake 网关不会触发此校验
- 日志中虽然有 WARN，但如果没有人监控日志，这个"静默降级"很难被发现

**建议**：在非 Fake 网关场景下增加一个健康检查端点/指标，明确标识当前是否在使用真实模型。

### 4.2 🟡 中风险：ContextBudgetTrimmer 使用粗估 token 算法

**位置**: `ContextBudgetTrimmer.java:171-189`

```java
// 中日韩汉字: 1字1token, 其余: 3字符1token
// 偏保守（估算偏大→早裁剪），但与真实 tokenizer 有偏差
```

**问题**：
- 本仓没有供应商 tokenizer 可用（D-002 未提供），只能用粗估
- 估算偏小会导致实际 token 超出窗口（顶爆窗口），模型返回 `INVALID_REQUEST`
- 不同模型的 tokenizer 差异很大，一个通用估算无法适配所有模型

**建议**：在 Agent 配置中增加 `tokenizerType` 字段，对接真实 tokenizer。

### 4.3 🟡 中风险：子 Agent 执行器的 timeout 封顶逻辑可能过度截断

**位置**: `WorkerChildAgentRunner.java:354-358`

```java
private static @NonNull Duration capTimeout(@NonNull Duration configured, long remainingNanos) {
    Duration remaining = Duration.ofNanos(Math.max(remainingNanos, 1L));
    return configured.compareTo(remaining) <= 0 ? configured : remaining;
}
```
**问题**：当剩余预算只剩 1 纳秒时（`Math.max(remainingNanos, 1L)`），封顶为 1 纳秒，实际会立即触发 TIMEOUT。这虽然在逻辑上正确（宁可超时也不超预算），但可能导致子 Agent 在预算边缘时频繁超时。

### 4.4 🟡 中风险：ToolOutcomeRenderer 同名工具合并丢失调用粒度

**位置**: `ToolOutcomeRenderer.java:110-111`（注释中已承认）

```java
// 同名工具在一轮里被调两次时，闸门按工具名合并结果，
// 此处对两次调用给同一段文本 —— 这是既有合并语义的已知粗糙处，
// 不是本方法能修的（修它要改账本键）。
```
**问题**：如果模型在同一轮调用同一个工具两次（不同参数），回灌时两次结果会被合并为一条消息，模型无法区分两次调用的不同输出。

### 4.5 🟢 低风险：RunExecutionLoop 中 `eventPort` / `stepPort` / `riskEventPort` 可空

**位置**: `RunExecutionLoop.java:86-105`

这三个端口都允许为 null（不写），且吞掉写入异常。

**问题**：
- 装配时如果漏注入，不会报错，只是"不写"
- 生产环境如果忘记装配，进度页时间线永远只有一条（`eventPort` 缺失）、步骤记录为空（`stepPort` 缺失）、安全事件不落库（`riskEventPort` 缺失）
- 已在注释中警告，但没有任何编译期/启动期强制检查

**建议**：增加装配期连通性测试（已在 P4-D12 / G4 中规划），但代码层面缺少 `@NonNull` 注解强制。

### 4.6 🟢 低风险：`ModelRequest.effectiveMessages()` 与 `userContent` 的兼容语义

**位置**: `ModelRequest.java:78-83`

```java
public @NonNull List<ModelMessage> effectiveMessages() {
    if (!messages.isEmpty()) {
        return messages;
    }
    return List.of(ModelMessage.user(userContent));
}
```
**问题**：`messages` 非空时 `userContent` 被忽略（仅冗余保留）。如果调用方误以为 `userContent` 会被追加到消息序列末尾，可能导致首轮输入丢失。虽然文档已说明，但这种"双字段互斥"语义容易误用。

### 4.7 🟢 低风险：MiniMaxModelGateway 的 `stripReasoning` 在草稿未闭合时保留原文

**位置**: `MiniMaxModelGateway.java:286-297`

```java
int lastClose = content.lastIndexOf(THINK_CLOSE);
if (lastClose < 0) {
    log.warn("模型返回的推理草稿未闭合，正文无法可靠切分，按原文保留");
    return content;  // 宁可多发一段草稿，也不丢可能被误判为正文的内容
}
```
**评价**：这是**设计取舍而非缺陷**——宁可保留草稿也不静默丢弃内容。但如果模型频繁输出未闭合的 think 标签（如被 maxTokens 截断），会导致学生看到推理草稿。建议监控 `模型返回的推理草稿未闭合` 日志的频率。

---

## 五、整体架构总结

```
┌──────────────────────────────────────────────────────────────────┐
│                     应用启动装配层                                 │
│  WorkerExecutionConfig.modelGateway() → MiniMax / Fake           │
└──────────────────────────┬───────────────────────────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
    ┌─────────────┐ ┌────────────┐ ┌────────────────┐
    │ RunDispatch │ │ 子 Agent    │ │ 其他直接调用方  │
    │ Consumer    │ │ Runner     │ │ (如测试)       │
    └──────┬──────┘ └──────┬─────┘ └────────┬───────┘
           │               │                │
           ▼               ▼                ▼
    ┌──────────────────────────────────────────────────┐
    │              ModelGateway.complete(request)       │
    │  ┌──────────────────────────────────────────┐    │
    │  │ AbstractModelGateway (归一化/脱敏兜底)     │    │
    │  │  ┌────────────────────────────────────┐   │    │
    │  │  │ MiniMaxModelGateway (真实 HTTP)     │   │    │
    │  │  │  buildPayload → HttpRequest        │   │    │
    │  │  │  sendAndParse → parseCompletion    │   │    │
    │  │  │  stripReasoning → ModelResponse    │   │    │
    │  │  └────────────────────────────────────┘   │    │
    │  │  ┌────────────────────────────────────┐   │    │
    │  │  │ FakeModelGateway (故障注入)         │   │    │
    │  │  └────────────────────────────────────┘   │    │
    │  └──────────────────────────────────────────┘    │
    └──────────────────────────────────────────────────┘
                           │
                    ModelResponse
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         无toolCalls   有toolCalls   异常
              │       ┌───┴───┐       │
              ▼       ▼       ▼       ▼
         commit    工具闸门   重试    失败
         Success   回灌      (可重试) (不可重试)
```

---

## 六、关键设计决策（ADR 引用）

| 决策 | 说明 |
|---|---|
| **ADR-002** | 领域层只依赖 `ModelGateway` 接口，不直接依赖供应商 SDK 或 Spring AI 类型 |
| **ADR-011 §3.3** | 禁止使用 Spring AI `ToolCallingAdvisor` 自动执行路径，必须走 `ToolInvocationGate` |
| **FR-007** | 超时/限流/供应商错误归一化为 `ModelErrorKind` |
| **FR-008** | 工具调用必须经过完整闸门（授权→幂等→审批→预算→执行） |
| **FR-012** | 返回 `usage` 供预算结算 |
| **AT-MODEL-002** | 凭据不得出现在日志与异常消息中（`CredentialRedactor`） |
| **MA-T14** | 工具输出为不可信数据，须标记边界（`ToolOutcomeRenderer`） |
| **MA-T09** | 子 Agent 必须自行实施取消与超时检查 |
| **P3-D09 / FR-016** | Prompt 注入防护（`PromptGuard`） |
| **P3-D11 / FR-020** | 编造出处拦截（`HallucinationDetector`） |
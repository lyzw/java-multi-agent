# AI 请求观测者：接口定义、实现与注入位置

> 生成时间：2026-10-30
> 相关文档：[AI-REQUEST-ANALYSIS.md](./AI-REQUEST-ANALYSIS.md)、[AI-REQUEST-ANALYSIS-SUPPLEMENT.md](./AI-REQUEST-ANALYSIS-SUPPLEMENT.md)

---

## 一、观测者接口总览

| 接口 | 职责 | 注入位置 |
|---|---|---|
| `ModelCallObserver` | 观测每次模型调用的完整生命周期（before/after/exception） | `ObservableModelGateway.complete()` |
| `ExecutionObserver` | 观测执行循环的生命周期（loop/step/model/tool/trim/complete） | `RunExecutionLoop.execute()` |
| `TraceObserver` | 端到端追踪（trace start/event/end/aborted） | `RunDispatchConsumer.consume()` |

### 1.1 分层架构

```
ports 模块（领域层，无外部依赖）
  └── observer/
      ├── ModelCallObserver.java          # 接口
      ├── ExecutionObserver.java          # 接口 + ToolCallSummary + LoopOutcome
      ├── TraceObserver.java              # 接口 + TraceOutcome
      ├── ModelCallObserverChain.java     # 链式组合器
      ├── ExecutionObserverChain.java     # 链式组合器
      ├── TraceObserverChain.java         # 链式组合器
      └── ObservableModelGateway.java     # ModelGateway 装饰器

infrastructure 模块（实现层）
  └── observer/
      ├── logging/                        # Slf4j 实现（3 个）
      ├── metrics/                        # Micrometer 实现（3 个）
      ├── bridge/                         # EventPort 桥接实现（3 个）
      └── noop/                           # Noop 兜底实现（3 个）

bootstrap 模块（装配层）
  └── ObserverConfiguration.java          # Spring Bean 装配
```

### 1.2 关键设计决策

| 决策 | 说明 |
|---|---|
| **ports 层定义接口** | 观测者接口在 ports 模块定义，遵循"领域层零框架依赖"原则 |
| **中性值对象** | `ToolCallSummary`、`LoopOutcome` 在接口内部定义，避免 ports 依赖 worker 模块 |
| **链式组合器** | 每个接口有对应的 Chain 类，按 `@Order` 顺序调用，异常只记 WARN 不冒泡 |
| **ObservableModelGateway 装饰器** | 不修改现有 ModelGateway 实现，通过装饰器注入观测能力 |
| **Noop 兜底** | 每种观测者有 Noop 实现，确保观测者链永不为空 |
| **@Primary 包装** | `ObservableModelGateway` 标记为 `@Primary`，覆盖默认 ModelGateway 装配 |

---

## 二、观测者接口详细定义

### 2.1 ModelCallObserver

```java
public interface ModelCallObserver {
    void beforeCall(String tenantId, String runId, int step, ModelRequest request);
    void afterCall(String tenantId, String runId, int step, ModelRequest request,
                   ModelResponse response, Duration latency);
    void onException(String tenantId, String runId, int step, ModelRequest request,
                     ModelGatewayException exception, Duration latency);
}
```

**观测点**：
- `beforeCall`: 模型调用前（记录请求参数）
- `afterCall`: 模型调用成功（记录响应 + 延迟 + token）
- `onException`: 模型调用异常（记录异常类型 + 是否可重试）

### 2.2 ExecutionObserver

```java
public interface ExecutionObserver {
    void onLoopStart(String tenantId, String runId, int maxSteps);
    void onStepStart(String tenantId, String runId, int step);
    void onModelCallComplete(String tenantId, String runId, int step,
                             ModelResponse response, Duration latency);
    void onToolCallComplete(String tenantId, String runId, int step,
                            ToolCallSummary toolResult, Duration latency);
    void onContextTrimmed(String tenantId, String runId, int droppedRounds,
                          int droppedTokens, List<String> droppedTools);
    void onLoopComplete(String tenantId, String runId, LoopOutcome outcome, int totalSteps);

    // 内部中性值对象
    class ToolCallSummary { ... }   // succeeded/failed/rejected/unknown
    class LoopOutcome { ... }       // kind + detail + retryable
    enum LoopOutcome.Kind { ... }   // SUCCEEDED/FAILED/RETRYABLE_FAILURE/...
}
```

**观测点**：
- `onLoopStart`: 执行循环开始（记录最大步数）
- `onStepStart`: 每轮迭代开始
- `onModelCallComplete`: 模型调用完成（复用 ModelCallObserver 的数据）
- `onToolCallComplete`: 工具调用完成（成功/失败/拒绝/未知）
- `onContextTrimmed`: 上下文裁剪发生
- `onLoopComplete`: 执行循环结束（记录结局 + 总步数）

### 2.3 TraceObserver

```java
public interface TraceObserver {
    void onTraceStart(String traceId, String tenantId, String runId,
                      String eventType, Map<String, Object> payload);
    void onTraceEvent(String traceId, String event, Map<String, Object> data);
    void onTraceEnd(String traceId, TraceOutcome outcome, Duration totalLatency);
    void onTraceAborted(String traceId, String error, Duration totalLatency);

    enum TraceOutcome {
        SUCCEEDED, FAILED, CANCELLED, RETRYABLE_FAILURE, UNKNOWN_RESULT, STALE_GENERATION
    }
}
```

**观测点**：
- `onTraceStart`: MQ 消息到达（生成 Trace ID）
- `onTraceEvent`: 中间事件（可选）
- `onTraceEnd`: 执行完成（记录结局 + 端到端延迟）
- `onTraceAborted`: 执行异常中断

---

## 三、观测者实现对比

### 3.1 ModelCallObserver 实现

| 实现 | 类名 | 输出目标 | Order | 关键指标 |
|---|---|---|---|---|
| 日志 | `Slf4jModelCallObserver` | `ai.model.calls` logger | 10 | beforeCall / afterCall / onException 结构化日志 |
| 指标 | `MicrometerModelCallObserver` | MeterRegistry | 20 | `ai.model.calls.total` / `ai.model.calls.latency` / `ai.model.tokens.*` |
| 桥接 | `EventPortModelCallObserver` | RunEventPort | 30 | MODEL_CALL_BEFORE / MODEL_CALL_DONE / MODEL_CALL_ERROR 事件 |
| 兜底 | `NoopModelCallObserver` | 无 | MAX_VALUE | 空实现 |

### 3.2 ExecutionObserver 实现

| 实现 | 类名 | 输出目标 | Order | 关键指标 |
|---|---|---|---|---|
| 日志 | `Slf4jExecutionObserver` | `ai.execution.loop` logger | 10 | LOOP_START / STEP_* / CONTEXT_TRIMMED / LOOP_COMPLETE |
| 指标 | `MicrometerExecutionObserver` | MeterRegistry | 20 | `ai.execution.loops.total` / `ai.execution.steps` / `ai.execution.latency.total` / `ai.execution.trimmed` |
| 桥接 | `EventPortExecutionObserver` | RunEventPort | 30 | LOOP_START / STEP_* / CONTEXT_TRIMMED / LOOP_COMPLETE 事件 |
| 兜底 | `NoopExecutionObserver` | 无 | MAX_VALUE | 空实现 |

### 3.3 TraceObserver 实现

| 实现 | 类名 | 输出目标 | Order | 关键指标 |
|---|---|---|---|---|
| 日志 | `Slf4jTraceObserver` | `ai.trace` logger + MDC | 10 | TRACE_START / TRACE_END / TRACE_ABORTED（含 MDC traceId） |
| 指标 | `MicrometerTraceObserver` | MeterRegistry | 20 | `ai.trace.total` / `ai.trace.latency` |
| 桥接 | `EventPortTraceObserver` | RunEventPort | 30 | TRACE_START / TRACE_END / TRACE_ABORTED 事件 |
| 兜底 | `NoopTraceObserver` | 无 | MAX_VALUE | 空实现 |

---

## 四、注入位置详细说明

### 4.1 ModelCallObserver 注入点

**文件**: `platform/agent-ports/src/main/java/.../ports/observer/ObservableModelGateway.java`

**注入方式**: 装饰器模式，包装原始 `ModelGateway`

```
RunExecutionLoop.execute()
  └─ modelGateway.complete(tenantId, runId, step, request)
       └─ ObservableModelGateway.complete()
            ├─ observerChain.beforeCall(tenantId, runId, step, request)    ← 注入点 1
            ├─ delegate.complete(request)                                  ← 真实调用
            ├─ observerChain.afterCall(tenantId, runId, step, ...)         ← 注入点 2
            └─ observerChain.onException(tenantId, runId, step, ...)       ← 注入点 3
```

**装配**: `ObserverConfiguration.observableModelGateway()` → `@Primary`，Spring 自动注入所有 `ModelCallObserver` 实现

### 4.2 ExecutionObserver 注入点

**文件**: `platform/agent-worker/src/main/java/.../worker/execute/RunExecutionLoop.java`

**注入方式**: 在 `execute()` 方法的各阶段调用 `observerChain.*`

```
RunExecutionLoop.execute()
  ├─ observerChain.onLoopStart(tenantId, runId, snapshot.maxSteps())       ← 注入点 1
  │
  ├─ 每轮循环:
  │    ├─ observerChain.onStepStart(tenantId, runId, steps)                ← 注入点 2
  │    ├─ observerChain.onContextTrimmed(...)                              ← 注入点 3 (条件)
  │    ├─ modelGateway.complete() → ModelCallObserver 触发
  │    └─ observerChain.onToolCallComplete(...)                            ← 注入点 4
  │
  ├─ observerChain.onLoopComplete(tenantId, runId, outcome, steps)         ← 注入点 5 (每个 return 前)
  │
  ├─ return ExecutionOutcome.*  → 共 14 个 return 点，每个都调用 notifyLoopComplete()
```

**装配**: `WorkerExecutionConfig.runExecutionLoop(..., observerChain)` → 自动注入 `ExecutionObserverChain`

### 4.3 TraceObserver 注入点

**文件**: `platform/agent-worker/src/main/java/.../worker/dispatch/RunDispatchConsumer.java`

**注入方式**: 在 `consume()` 方法的入口和出口调用

```
RunDispatchConsumer.consume()
  ├─ traceId = generateTraceId(tenantId, runId)
  ├─ observerChain.onTraceStart(traceId, tenantId, runId, ...)             ← 注入点 1
  │
  ├─ executor.execute(tenantId, task) → RunExecutionLoop.execute()
  │
  ├─ observerChain.onTraceEnd(traceId, outcome, totalLatency)              ← 注入点 2
  └─ observerChain.onTraceAborted(traceId, error, totalLatency)            ← 注入点 3 (异常)
```

**装配**: `ObserverConfiguration.traceObserverChain()` → 自动注入所有 `TraceObserver` 实现

---

## 五、观测数据流

```
Model Call  ──────────────────────────────────────────────────────
  ObservableModelGateway.complete()
    ├─ Slf4jModelCallObserver     → 日志: ai.model.calls
    ├─ MicrometerModelCallObserver → 指标: ai.model.calls.* / ai.model.tokens.*
    └─ EventPortModelCallObserver  → 事件: MODEL_CALL_* → RunEventPort → DB

Execution Loop ───────────────────────────────────────────────────
  RunExecutionLoop.execute()
    ├─ Slf4jExecutionObserver      → 日志: ai.execution.loop
    ├─ MicrometerExecutionObserver  → 指标: ai.execution.*
    └─ EventPortExecutionObserver   → 事件: LOOP_*/STEP_* → RunEventPort → DB

Trace ─────────────────────────────────────────────────────────────
  RunDispatchConsumer.consume()
    ├─ Slf4jTraceObserver          → 日志: ai.trace + MDC traceId
    ├─ MicrometerTraceObserver      → 指标: ai.trace.*
    └─ EventPortTraceObserver       → 事件: TRACE_* → RunEventPort → DB
```

---

## 六、启用/禁用配置

默认所有观测者均启用。可通过 Spring 属性控制：

```yaml
# 全部禁用观测者（不推荐）
ai.observer:
  model-call:
    slf4j:
      enabled: false
    micrometer:
      enabled: false
  execution:
    slf4j:
      enabled: false
  trace:
    slf4j:
      enabled: false
```

**注意**: Noop 兜底观测者始终可用，确保观测者链永不为空。

---

## 七、新增观测者指南

### 7.1 添加新的 ModelCallObserver 实现

```java
@Component
@Order(40)  // 在日志/指标/桥接之后
public class MyCustomModelCallObserver implements ModelCallObserver {
    @Override
    public void beforeCall(String tenantId, String runId, int step, ModelRequest request) {
        // 自定义逻辑
    }
    // ...
}
```

Spring 自动收集到 `ModelCallObserverChain` 中，无需修改装配代码。

### 7.2 添加新的 ExecutionObserver 实现

```java
@Component
@Order(40)
public class MyCustomExecutionObserver implements ExecutionObserver {
    // 实现各方法
}
```

### 7.3 添加新的 TraceObserver 实现

```java
@Component
@Order(40)
public class MyCustomTraceObserver implements TraceObserver {
    // 实现各方法
}
```

### 7.4 添加新的观测维度

如果需要在现有观测者中增加新的观测点：
1. 在 ports 层接口中增加新的方法（如 `onBudgetConsumed()`）
2. 在 Chain 类中调用新方法
3. 在各实现中实现新方法（Noop 中为空实现）
4. 在执行循环中在对应位置触发

---

## 八、与现有观测端口的关系

| 现有端口 | 与新观测者的关系 |
|---|---|
| `RunEventPort` | 新观测者通过 `EventPort*Observer` 桥接到此端口 |
| `RunStepPort` | 继续由 `RunExecutionLoop.appendStep()` 直接写入，不受新观测者影响 |
| `RiskEventPort` | 继续由 `RunExecutionLoop.emitRiskEvent()` 直接写入，不受新观测者影响 |
| `AgentMetricPort` | 事后评估端口，与实时观测者形成"实时 + 审计"双层保障 |

**关键原则**: 新观测者**不替代**现有端口，而是**串联**它们。`EventPort*Observer` 只是把现有事件类型以更结构化的方式写入 `RunEventPort`。

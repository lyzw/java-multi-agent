# RouteAgent 规则转发设计（路由契约 + 确定性规则引擎）

**文档**：RAG/多级 Agent 设计包 · 10  
**主题**：把「路由目标只有 id」升级为「父侧路由契约 + 策略内确定性规则」，使 RouteAgent 能**按规则转发**  
**基线**：Java SaaS Agent v3.1；多级 Agent v1.1（06/07/08/09）  
**状态**：设计方案候选；**未上线，无兼容包袱，可重建模型**  
**配套**：[11_RouteAgent请求示例模拟.md](11_RouteAgent请求示例模拟.md)（端到端字段走查 + 缺口清单）

---

## 1. 问题定位

多级 Agent 的**治理骨架已完备**（固定版本、逐跳授权、深度/次数/超时/预算、fail-closed），但「按规则转发」在数据模型层面缺失：

| 缺口 | 证据 | 后果 |
|---|---|---|
| 子引用无决策字段：仅 `agentId/releaseVersion/required` | `core/orchestration/ChildAgentRef.java:21-25` | 路由无从依据语义 |
| 策略目标只有裸 id 列表 | `core/cap/CapRoutingPolicyDef.java:50` | 同上 |
| 模型侧只看到 id 的 `enum` | `core/orchestration/DelegationToolSchema.java:114-118`<br>`worker/execute/DelegationToolSchemaAssembler.java:82-84` | **模型在只见到一串 id 的情况下猜目标** |
| 服务端只做事后允许集校验 | `application/orchestration/DelegationExecutor.java:214-227` | 规则无处表达 |
| `capabilityTags`/`skillBundles` 为「V1 忽略语义」 | `core/cap/CapAgentDef.java:20-23` | 死字段 |

现状真实语义是：**模型猜目标 → 服务端判断"猜的这个允不允许"**。这既不是规则转发，也不是可靠的语义路由。

---

## 2. 设计原则：把被搁置的三个问题做实

原 `RoutingStrategy` 的 javadoc 明确写了"本批不引入条件 DSL"，理由是三件事未评审（原 `core/orchestration/RoutingStrategy.java:12-18`，该枚举已随 S4 的旧内容模型删除；纪律现落在 `routing/RoutingEngine` 与 `core.orchestration.ConflictPolicy` 的 javadoc 里）。本方案的主线是**逐个做实**，而非继续绕过：

| 未决问题 | 本方案的答案 |
|---|---|
| ① 求值必须确定性 | **封闭文法 + 纯函数 + 固定求值序 + 稳定决胜键**。路由决策 = `f(策略版本, 规范化输入, 拓扑)`，同输入必同输出 |
| ② 条件语言需发布期校验 | **有界 DNF 展开 + 样本回放**：发布期确定性地判掉矛盾条件、被遮蔽规则、死目标 |
| ③ 求值失败语义必须冻结 | `on_error` 仅两值，且 **ERROR 永不等于 MATCH**（默认 `SKIP`），决策记录留稳定码 |

> ⚠️ **本方案的价值不在"有规则"，而在"规则可判、可校验、可重放"。**
> 只做规则求值器而不做 §5 的静态守卫，等于把「写错的条件」从配置期推迟到线上 —— 那正是 06/08 反复消除的缺陷类型。

---

## 3. Agent 路由契约（RoutingContract）

### 3.1 结构

父 Agent 为**每个子**声明一份路由契约：

```jsonc
// orchestration.childAgentRefs[]
{
  "agentId": "order-query",
  "releaseVersion": 7,
  "required": false,
  "routingContract": {
    "intent": "按订单号查询订单状态、发货与物流进度",
    "useWhen":     ["用户询问单个订单的状态/物流/发货时间"],
    "avoidWhen":   ["批量统计、报表、财务对账", "订单尚未生成的售前咨询"],
    "capabilities": ["order.read", "logistics.read"],
    "inputContract": {
      "type": "object", "required": ["topic"], "additionalProperties": "false",
      "properties": { "topic": {"enum": ["order_status","refund","policy"]},
                      "orderId": {"type":"string","maxLength":64} }
    },
    "priority": 10,          // SEMANTIC 模式的稳定决胜键
    "source": "INHERITED"    // INHERITED | OVERRIDE
  }
}
```

### 3.2 为什么权威源在父侧

委派拓扑本来就是父的声明（`core/orchestration/AgentOrchestration.java:12-14` 已确立"父声明拓扑"）。三点理由：

1. **同一子在不同 Router 下职责可以不同** —— 契约是"在这个父的路由语境里我扮演什么"，不是"这个 Agent 全局是什么"。
2. **父的发布闭包必须自洽** —— 不能因为子重新发布而让父的路由行为漂移（与 `childAgentRefs` 必须固定 release 同口径，`ChildAgentRef.java:31-35`）。
3. **可复核** —— 决策快照只需引用父的版本，不必再解析子的当前版本。

子的 `CapAgentDef` 只提供**默认底稿**（admin-console 建引用时预填），父可 `OVERRIDE`。

### 3.3 防"父写歪"的静态守卫

子侧可声明 `routingContractLocked: true`；锁定时父的契约与子的底稿不一致 → **拒绝发布**。

> 这是纯字符串/结构化比对，不引入运行期不确定性，因此不破①的确定性要求。

### 3.4 约束

| 维度 | 上限 | 理由 |
|---|---|---|
| `intent` | ≤200 字符 | 进 prompt |
| `useWhen` / `avoidWhen` | 各 ≤8 条，每条 ≤200 字符 | 防 prompt 膨胀稀释注意力 |
| `capabilities` | ≤32 项，`^[a-z][a-z0-9._-]{1,63}$` | 词汇表可控 |
| `inputContract` | 复用 `ChildResultContractValidator` 的关键字白名单口径 | 不再造一套 schema 方言 |

---

## 4. 路由策略：从「枚举 + id 列表」到规则引擎

### 4.1 三种 mode 的语义边界

| mode | 谁选目标 | 模型参与度 | 适用 |
|---|---|---|---|
| `RULES_ONLY` | 规则引擎 | **零**（模型只产出 `input`） | **推荐默认**；确定性最强 |
| `RULE_FIRST` | 规则优先，无命中再兜底/语义 | 有限 | 规则覆盖不全的过渡期 |
| `SEMANTIC` | 模型（看得到 §3 契约） | 完全 | 开放问答类分诊 |

> ⚠️ 三种 mode 的差别必须**写进 javadoc 与 admin 文案**，且 `RULES_ONLY` 下 Schema **不含 `candidates`**（模型无权选目标）。
> 历史教训：ROUTER 在第十四批前"名不副实"（`V24__routing_policy.sql:14-20`），`SEQUENTIAL` 曾被承诺聚合而实现没有（`OrchestrationMode.java:48-51`）。**名字许诺了做不到的事，配置方就会按那个许诺来设计。**

旧 `EXPLICIT_TARGETS` / `ORDERED_FALLBACK` **直接删除**（未上线）：
`EXPLICIT_TARGETS → SEMANTIC + no_match_action=REJECT`；`ORDERED_FALLBACK → SEMANTIC + DEFAULT_TARGET`。

> ⚠️ **这两条映射只在「把旧允许集写成规则目标/兜底目标」时才行为等价**（C38-2 · 见 §6.2.1）：
> 旧形态自带允许集，运行期判「候选 ∈ 集」；新形态的目标集是从规则/兜底**推**出来的。
> 因此迁成**纯** `SEMANTIC`（既无规则也无兜底）等于**放弃收窄**，只剩拓扑一道关 ——
> 症状不是报错，是「策略里明明没写这个 Agent，委派却照样发出去」。

### 4.2 DDL

```sql
CREATE TABLE cap_routing_policy (
    tenant_id           UUID    NOT NULL,
    policy_id           TEXT    NOT NULL,          -- ⚠️ TEXT 不是 UUID（V23/V24 的教训）
    version             INTEGER NOT NULL,
    mode                TEXT    NOT NULL,          -- RULES_ONLY | RULE_FIRST | SEMANTIC
    input_schema        JSONB   NOT NULL,          -- 路由输入词汇表（封闭定义）
    evaluation          JSONB   NOT NULL,          -- 有序规则列表
    default_target      TEXT,
    no_match_action     TEXT    NOT NULL,          -- REJECT | DEFAULT_TARGET
    on_conflict         TEXT    NOT NULL,          -- RULE_WINS | CANDIDATE_WINS
    sample_set          JSONB   NOT NULL DEFAULT '[]',
    config_fingerprint  TEXT    NOT NULL,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    lifecycle_state     public.cap_lifecycle_state DEFAULT 'DRAFT',
    CONSTRAINT cap_routing_policy_pk PRIMARY KEY (tenant_id, policy_id, version),
    CONSTRAINT cap_routing_policy_version_ck CHECK (version > 0),
    CONSTRAINT cap_routing_policy_mode_ck CHECK (mode IN ('RULES_ONLY','RULE_FIRST','SEMANTIC')),
    CONSTRAINT cap_routing_policy_nomatch_ck CHECK (no_match_action IN ('REJECT','DEFAULT_TARGET')),
    CONSTRAINT cap_routing_policy_conflict_ck CHECK (on_conflict IN ('RULE_WINS','CANDIDATE_WINS')),
    -- ⚠️ 一律用 CASE，不用 `typeof(...)='array' AND length(...)>0`：
    --    PG 不保证 AND 的求值顺序，非数组时 jsonb_array_length 会抛错而非让 CHECK 判 false
    --    （沿用 V24:59-65 的既有做法）
    CONSTRAINT cap_routing_policy_eval_ck CHECK (
        CASE WHEN mode IN ('RULES_ONLY','RULE_FIRST')
             THEN jsonb_typeof(evaluation)='array' AND jsonb_array_length(evaluation) > 0
             ELSE jsonb_typeof(evaluation)='array' END),
    CONSTRAINT cap_routing_policy_default_target_ck CHECK (
        no_match_action <> 'DEFAULT_TARGET' OR default_target IS NOT NULL),
    CONSTRAINT cap_routing_policy_fingerprint_ck CHECK (config_fingerprint <> '')
);
-- RLS：ENABLE + FORCE + tenant_id = current_tenant_id()，与 V24:85-101 同口径
```

### 4.3 规则结构

```jsonc
{ "ordinal": 1,               // 服务端重排，不信任传入值
  "ruleId": "r-order-status", // 可读，进决策日志
  "target": "order-query",    // 必须 ∈ childAgentRefs
  "when": { /* Predicate，见 §4.4 */ },
  "onError": "SKIP",          // SKIP | REJECT
  "note": "带订单号的状态查询" }
```

---

## 4.4 Predicate 封闭文法（确定性的物理保证）

**没有脚本、没有模板、没有插值、没有外部调用。**

```
Value     := in.<dottedPath> | ctx.<name> | Literal
ctx.*     := tenantId | channel | locale | run.attempt | run.depth | now.utcDate
Literal   := JSON scalar | 标量数组（≤64 项）

Predicate := {"const": bool}
           | {"and": [P…]} | {"or": [P…]} | {"not": P}
           | {"eq": [V,V]} | {"in": [V, LiteralArray]} | {"null": [V]}
           | {"startsWith":[V,V]} | {"endsWith":[V,V]}
           | {"contains": [V,V]}                      -- 字符串子串，非集合语义
           | {"anyOf": [V, LiteralArray]}             -- 两侧皆集合，交集非空
           | {"re": [V, BoundedRegex]}
```

**硬限制**（解析期检查，**超限即拒而非降级**）：

| 维度 | 上限 | 理由 |
|---|---|---|
| AST 节点数 | 128 | 防规则膨胀不可维护 |
| 嵌套深度 | 8 | 可读性 |
| **正则** | 长度 ≤128，**RE2/J 求值** | Java 原生 `Pattern` 有回溯爆炸（ReDoS）风险 —— 这是②里最容易埋雷的一处 |
| DNF 展开积 | ≤4096 | 见 §5；超限**直接拒发布**，绝不跳过 V4/V5/V6 |
| 字面量 | 仅 JSON 标量/标量数组 | 排除对象/数组嵌套的语义歧义 |
| 规则条数 | ≤64 | 运行期最坏成本可预算 |

> ⚠️ `now.utcDate` 是唯一的时间词汇，**只允许日粒度比较**，且必须进决策快照 —— 否则重放会得出不同结论。
> 策略不引用时间就完全确定；引用了时间，则"同一自然日内可重放"。二者都必须在文档里写明，不能含糊。

---

## 5. 发布期静态守卫 V1-V10

核心技巧：**把谓词规范化为 DNF 并做有界展开**（`in` 子句按字面量积展开），在展开后的 DNF 上做**精确**判定，不是启发式。

| # | 守卫 | 判定方式 | 不通过 |
|---|---|---|---|
| V1 | 结构合法（文法、上限、未知键） | 解析期 | 拒 |
| V2 | 每个 `target` ∈ 父 `childAgentRefs` | 集合比对（既有，`AgentPublishClosureValidator.java:188-200`） | 拒 |
| V3 | 引用的 `in.*` 路径 ∈ `input_schema` 且类型相容 | schema 对齐 | 拒 |
| V4 | **矛盾谓词**（永假规则） | DNF：空子句 / 同字段不同常量 | 拒 |
| V5 | **被遮蔽规则**（前条 covers 本条，本条永不执行） | DNF：`earlier ⊇ later` | 拒 |
| V6 | **死目标**（某 target 无规则命中且非 default） | DNF：该 target 所有规则皆空 | 拒 |
| V7 | 完备性（`required` 子无规则覆盖） | DNF | 警告 |
| V8 | **样本回放**（全部样例过确定性引擎） | 求值比对 `expectTarget` | 拒 |
| V9 | 样本覆盖（每个 target 至少被一条样例命中） | 计数 | 拒 |
| V10 | DNF 积超预算 | 计数 | 拒（**不得跳过 V4-V6**） |

```jsonc
"sampleSet": [
  { "label": "带单号查状态", "input": {"topic":"order_status","orderId":"SO-1"}, "expectTarget": "order-query" },
  { "label": "无命中应兜底", "input": {"topic":"order_status"},                   "expectTarget": "faq-answer", "via": "DEFAULT_TARGET" }
]
```

**执行顺序固定 V1→V10**，V8 之前必做 V1/V3（否则求值器抛在畸形输入上，报错方向被带偏）。

> ⚠️ 诚实边界：有界 DNF 能**精确**判掉 V4/V5/V6，前提是展开积 ≤4096。超预算时**拒绝发布并提示拆分策略**，
> 而不是降级成启发式或跳过 —— 后两者都会让「策略通过校验」这句话失去含义。

---

## 6. 运行期决策流

`selectByRoutingPolicy`（现状 `DelegationExecutor.java:185-228`）重写为两阶段：

```
① 抽取 RoutingInput   in  = input 中按 input_schema 取的字段（其余不进求值视野）
                       ctx = 服务端注入（模型无法伪造）
② 规则引擎            按 ordinal 求值 → matchedRule | NO_MATCH | EVAL_ERROR@rule
③ 语义引擎            仅 RULE_FIRST 且 ②=NO_MATCH 时启用；候选来自模型
④ 冲突裁决            ②命中且与模型候选不同 → on_conflict（默认 RULE_WINS）+ ROUTING_CONFLICT 指标
⑤ 准入校验（不变）    拓扑包含 → 版本固定 → 逐跳授权 → 深度/次数/超时/预算
                       ⚠️ 兜底/降级目标同样必须走完 ⑤
                       （延续「降级只作用于选择，不作用于准入」：原 RoutingStrategy.java:42-46，现 RoutingEngine 类头 javadoc）
```

`RULES_ONLY` 下 ③ 跳过 —— 模型连目标选择权都没有。

### 6.1 决策快照

```jsonc
{ "policyRef": "orders-router@3", "policyFingerprint": "sha256:…", "mode": "RULE_FIRST",
  "canonicalInput": {…}, "canonicalInputSha256": "sha256:…",
  "evaluationTrace": [ {"ordinal":1,"ruleId":"…","outcome":"NO_MATCH","error":null}, … ],
  "resolution": "RULE_MATCH", "matchedRuleId": "…", "matchedRuleSha256": "sha256:…",
  "candidateFromModel": "faq-answer", "finalTarget": "order-query" }
```

`Resolution` 稳定码（扩 `ReasonCode`，沿用"对外只暴露稳定码"的既有纪律）：
`RULE_MATCH` / `SEMANTIC_MATCH` / `DEFAULT_TARGET` / `NO_MATCH_REJECTED` / `EVAL_ERROR_SKIPPED` / `EVAL_ERROR_REJECTED` / `POLICY_UNAVAILABLE` / `TARGET_NOT_ALLOWED` / `ROUTING_CONFLICT`。

**重放不变式**：`(policyRef, canonicalInputSha256) → finalTarget` 唯一。这才让 MA-T05/MA-T15 的"可复核"从口号变成可测性质。

#### 6.1.1 实现口径：对上面字段清单的两处收紧（S3 已落地）

上面那段 jsonc 是**设计意图**，落库的形态在两处刻意比它更严 —— 症状不是报错，是红线被悄悄换掉：

| 字段 | 设计稿 | 实际落库 | 为什么 |
|---|---|---|---|
| `canonicalInput` | 存明文 | **不存**，只存 `canonicalInputSha256` | `agent_invocation` 的既有红线是"只存摘要不存明文"（与 `input_digest` 同口径）。`in.*` 是业务字段（订单号、学段、主题），抄进 jsonb 等于为本表**新开一条明文旁路**，而这条旁路不在既有脱敏与保留策略的覆盖范围内。重放不变式只需要 SHA-256，不需要输入本身 |
| `evaluationTrace[].error` | 存自由文本 | **不存**，只存 `ordinal`/`ruleId`/`outcome` | `PredicateEvaluator.describe()` 的失败原因会内嵌**实际取到的输入值**（形如 `in.topic=三年级`）。理由再有用也不能拿它的载体去换一条红线；"哪一条规则、判成了什么"足以定位到具体规则，可读原因只进日志 |

序列化只有 `DecisionSnapshot.toJson()` 一份实现（发布期 dry-run、运行期委派、事后对账共用）—— 键名或哈希口径一旦分叉，症状是**两条本该相等的快照不相等**，于是复核结论作废。

#### 6.1.2 `Resolution` 与 `ReasonCode` 的关系（勿全量复制）

稳定码是 9 个，但 `reason_code` 是**失败契约**：只把 `Resolution.isFailure()` 的那四个映射进 `ReasonCode`
（`ROUTING_POLICY_UNAVAILABLE` / `ROUTING_TARGET_NOT_ALLOWED` / `ROUTING_NO_MATCH_REJECTED` / `ROUTING_EVAL_ERROR_REJECTED`），
映射表唯一的真相在 `Resolution.reasonCode()`。
成功出口（`RULE_MATCH`/`SEMANTIC_MATCH`/`DEFAULT_TARGET`/`EVAL_ERROR_SKIPPED`/`ROUTING_CONFLICT`）**只**存在于快照的 `resolution` 键里 ——
复制进 `ReasonCode` 会把成功的委派计成拒绝，并造出第二份真相。

#### 6.1.3 已知缺口：`ctx.channel` / `ctx.locale` 无可信来源

`ctx` 半边按设计必须由**服务端注入**（模型无法伪造）。但本运行期只有 MQ 接入通道，
`DelegationExecutor` 拿不到可信的"接入渠道"与"请求语言"，因此这两个值**按缺席传入**：

- 引用 `ctx.channel` / `ctx.locale` 的规则一律求值为 **ERROR**，交由 `onError` 处置（`SKIP` 跳过 / `REJECT` 中止整次路由）；
- 这是**响亮**的失效，不是静默放行 —— 符合 §6.2 的"ERROR 永不等于 MATCH"铁律；
- ⚠️ 但它在数据层不可自愈：发布期守卫只看规则引用了哪些词汇（V3 判类型兼容），看不出运行期永远取不到值。
  接入渠道/语言的**可信**来源（网关层注入、且不得取自请求体）是后续批次的前置条件；
  在那之前，任何依赖这两个 ctx 的策略都等于**没写**。


### 6.2 失败语义铁律

| 情形 | 结果 |
|---|---|
| 谓词求值异常 + `onError=SKIP` | 跳过本条继续（trace 记 `EVAL_ERROR_SKIPPED`） |
| 谓词求值异常 + `onError=REJECT` | 整次路由失败，不触发子执行 |
| 策略不可得 / 读失败 / 能力未装配 | `ROUTING_POLICY_UNAVAILABLE`，fail-closed（现状已实现，`DelegationExecutor.java:190-213`） |

> 🔒 **ERROR 永不等于 MATCH。** 任何实现不得把求值异常当"不匹配"静默放行 —— 那会让配置缺陷隐形。

#### 6.2.1 S3 落地后的副作用：`ROUTING_TARGET_NOT_ALLOWED` 运行期不再产出（**已闭环 · C38-2 采纳 ②**）

旧模型里策略自带一个「允许集」，运行期判的是「候选 ∈ 允许集」；S3 之后这件事被拆成了两层：
**规则的 target 只能来自父声明的拓扑**（守卫 V2 在发布期强制，`PolicySpec` 构造期再封一次），
**运行期判的是「候选 ∈ 拓扑」**（`DELEGATION_TARGET_NOT_DECLARED`）。
中间那一层 —— **「候选 ∈ 这份策略点名的目标」** —— 于是没人判了，
`Resolution.TARGET_NOT_ALLOWED` 随之失去可达路径。

> ⚠️ 当时**安全上没有任何东西被绕过**：拓扑检查在引擎**之前**（`DelegationExecutor` 的 3.1 先于 3.2），
> 越出父声明的候选照样被拒、零子执行。真正的问题更朴素也更容易漏：
> **MA-T05a 这条验收项在实现里没有产生路径** —— 那个码恒不出现，测试恒绿，
> 而「策略比拓扑窄」只在规则命中路径上成立：`RULE_FIRST`/`SEMANTIC` 无命中时引擎直接采纳候选，
> `no_match_action` 根本不参与，那些「没被这条输入命中的规则」也拦不住它。

**决议：采纳 ② —— 在采纳任何模型候选之前，先判它是否在这份策略点名的目标集内。**

判据落在**采纳的收口点**上（`RoutingEngine.adoptCandidate`），两个调用点共用同一个出口：

| 调用点 | 与收窄的关系 |
|---|---|
| 规则未命中 → 采纳候选（`settleWithoutRuleMatch` 第一分支） | 主路径 |
| `on_conflict=CANDIDATE_WINS` → 改判采纳 | 同一道判，**不是后门** |

> ⚠️ 为什么不说「后门」：S3 之后**两处都不判**，`CANDIDATE_WINS` 不是第二条更隐蔽的口子，
> 而是同一个缺口的另一半。把它叫成后门会读出一份并不存在的攻击面；
> 真正要记的是**判据写在收口点、而不是各调用点各写一遍** ——
> 后者迟早分叉，而分叉的那条在日志与指标上看起来一切正常（本仓反复出现的形态）。

三个口径上的约束：

1. **策略点名的目标集** = 规则 target ∪ `default_target`（`PolicySpec.declaredTargets()`），
   ⚠️ **不是** `allowedTargets` —— 后者是父的拓扑。运行期与 dry-run 都走 `parseStandalone`，
   而它把**点名集本身**当 `allowedTargets` 传进来，所以「用 `allowedTargets` 判候选」
   在这两个入口里<b>恒为真</b>：代码写了、收窄永不触发 —— 「看起来生效、实际不生效」的教科书形态。
2. **空集不设收窄**：`SEMANTIC` 且无规则无兜底 = 把选择完全交给模型，这是该模式的定义，不是漏洞。
   此时只剩拓扑一道关。
3. **判定顺序是「先拓扑、后策略」**，且这条顺序是第十四批定下、被用例钉死的评审决议
   （`RouterDelegationTest#routerReportsTopologyViolationBeforePolicyViolation`）：
   越出拓扑由 3.1 报 `DELEGATION_TARGET_NOT_DECLARED`，越出策略由引擎报 `TARGET_NOT_ALLOWED`。
   所以本码的准确读法是**「拓扑过了、策略没收」**，不是「策略和拓扑都没过」；
   两个码的排查方向相反（改子 Agent 声明 vs 改策略内容），顺序一旦反过来，
   「越界试探」就会被报成「策略没收」，MA-T05 的语义随之改写。
   ⚠️ 引擎被**单独**调用时（管理台 dry-run、样本回放）没有那道拓扑前置，
   不要把引擎的 `TARGET_NOT_ALLOWED` 当成「拓扑没问题」的证明。

> 为什么不是 ① 或 ③：两者都是「让名字追上实现」。而 MA-T05a 与 `ReasonCode.ROUTING_TARGET_NOT_ALLOWED`
> 的既有文案（「模型选的那个策略不许」）早已把收窄定为契约 —— 该改的是实现，不是契约。
> 另外 ① 要删一个**已发布过语义**的稳定码，历史行的指标分档会不认识它。

**§4.1 的迁移映射随之重新可辩护**：旧 `EXPLICIT_TARGETS`（「候选不在列表里就拒」）
在新模型里的等价形态是**把那个列表写成规则目标或兜底目标**（`RULES_ONLY`/`RULE_FIRST`），
而不是纯 `SEMANTIC` —— 纯 `SEMANTIC` 没有点名任何目标，只受拓扑约束。

验证：`RoutingEngineTest`（主路径 / 改判采纳同判 / 空集不收窄）
+ `RouterDelegationTest#ruleFirstRejectsCandidateOutsidePolicyWorkbook`（拓扑内、策略外 → 拒绝且**零子执行**）；
判定顺序的既有证据 `#routerReportsTopologyViolationBeforePolicyViolation` 保持不变。

---

## 7. 结构性债清理（未上线，直接重建）

| # | 债 | 证据 | 处置 |
|---|---|---|---|
| 1 | 三表双写 `agent`/`agent_version`/`cap_agent_def` | `01_platform_ddl.sql:569-576,691-700,873-894` | 单一真源 `cap_agent_def`；删 `CapDualWriteComparator`/`CapDualWriteService` |
| 2 | `inherits` 列 CHECK 强制 NULL 的死列 | `01_platform_ddl.sql:892` | 二选一：实现契约继承（`source=INHERITED` 即此意）**或删列** |
| 3 | `capabilityTags`/`skillBundles` V1 忽略语义 | `CapAgentDef.java:20-23` | `capabilities` 成为 `anyOf` 匹配词汇 → 转正；`skillBundles` 无消费方则删 |
| 4 | 前端契约薄 | `admin-console/src/api/types.ts:378-385` | 扩 `mode/inputSchema/evaluation/defaultTarget/noMatchAction/onConflict/sampleSet` |
| 5 | 策略编辑无 dry-run | — | 加后端 dry-run 接口，**前端不自建 TS 引擎镜像**（避免又造两套真相） | ✅ 已还：`POST /api/cap/config/routing-policies/dry-run` 把草稿原样交给 `RoutingDryRun`（内含 `RoutingEngine` + `PublishGuards`），探针文本不进 `definitionJson()` 与内容指纹；`admin-console` 的策略页只透传 JSON 原文与渲染结论 |

---

## 8. S3 前置任务：六个实现缺口

> 这六条来自 [11 号文档](11_RouteAgent请求示例模拟.md) §14 的端到端走查，**必须在 S3 之前关闭**。
> 理由：它们不是风格问题，而是**"可复核"的地基缺失** —— 地基不补，S3 做完决策快照也无从验证，
> 等于又造一个"看起来生效、实际无从证明"的对象（正是 V24 之前 `routingPolicyRef` 悬空引用的复发形态）。

| # | 缺口 | 证据 | 阻塞 S3 的哪一部分 | 关闭动作 | 验收 |
|---|---|---|---|---|---|
| **G-1** | `input_digest`/`output_digest` **恒为 NULL** | `DelegationExecutor.java:526-530`（构造时传 `null,null,startedAt,null,null`）；终态写只传 `reasonCode`+`instant`（:596-598） | §6.1 决策快照的 `canonicalInputSha256` 无对应物，重放不变式**无法证伪也无法证实** | 构造与终态写都填摘要（规范化 JSON → SHA-256） | IT：同一 input 两次委派 → `input_digest` 相同；不同 input → 不同 |
| **G-2** | `trace_id` 被写成 `runId` | `ToolInvocationGate.java:829` | 决策日志无法按调用粒度串联；一次多跳 Run 的 `ROUTING_CONFLICT` 归因不到具体 call | 用 `call.callId()`（幂等派生用的就是它，:754-757）填 `traceId` | IT：同一 Run 两次委派的 `trace_id` 不同 |
| **G-3** | `configVersion` 硬编码 `0L` | `CapDualWriteService.java:136-137` | 「策略变了没有」判据不成立 → `configFingerprint` 与版本无法互相佐证，快照与真实策略版本脱钩 | 从配置表取真实版本号回填 | IT：发布新策略版本后 `configVersion` 递增，且 `CapabilitySnapshot.configFingerprint` 变化 |
| **G-4** | `input` 可缺省（兜底 `Map.of()`） | `DelegationArguments.java:48-51` | `RULES_ONLY/RULE_FIRST` 下规则**永远 NO_MATCH** 且现场无人察觉 —— 静默退化成"全部走兜底"，是本项目最怕的「写了不生效」形态 | 声明了 `evaluation` 的策略强制 `input` 必填；缺失 → `PARAM_SCHEMA_FAIL` | 单测：缺 input + 有 evaluation → 拒且稳定码正确 |
| **G-5** | `additionalProperties:false` 用**字符串比较** | `ChildResultContractValidator.java:134-143`（`"false".equals(asText(null))`） | §3.4 让 `inputContract` 复用同一校验器 → 歧义直接传染到路由输入契约 | 改真正的布尔判定（兼容 JSON bool 与 `"false"` 字符串，但**拒绝** `"FALSE"`/`"0"`） | 单测覆盖 4 种写法：`false`/`"false"` 通过，`"FALSE"`/`0` 报错 |
| **G-6** | 未知字段拒收**未实现** | `CreateRunRequest.java:14-16,41`（`UNKNOWN_PROPERTIES_REJECTED` 只是常量，全仓无 `FAIL_ON_UNKNOWN_PROPERTIES`/`@JsonIgnoreProperties`） | §3 契约引入 `input` 词汇表后，"多传字段"会变成**规则可见但 Schema 未声明**的输入 → `in.*` 取值边界失去唯一真相；同时身份字段（`tenantId`）存在从请求体注入的敞口 | 加 `@JsonIgnoreProperties(ignoreUnknown=false)`，或在 `assertNoIdentifyingContent()` 里真正遍历判定（该方法已在 `RunController:137-195` 链路中被调用，是天然落点） | IT：body 含 `tenantId` / 未知字段 → 400 且稳定码；`ctx.*` 永不被请求体覆盖 |

> ⚠️ **G-6 的安全维度优先于其余五条**：它是唯一一个"身份可被注入"的敞口。`tenantId` 现状确实只取 `TenantContext`（`RunController.java:139`），但 javadoc 承诺的拒收没有实现 —— **文档与实现不一致本身就是缺陷**（同 §4.1 的"名分落差"教训）。
>
> **建议排期**：G-6 → G-1 → G-4 → G-3 → G-2 → G-5。前三条分别对应「安全敞口 / 复核地基 / 静默失效」，是本项目历史上反复出现缺陷的三个类别。

---

## 9. 实施顺序

| Stage | 交付 | 出口条件 | 状态 |
|---|---|---|---|
| **S0** | §8 六个缺口 G-1~G-6 | 对应 IT/单测全绿；**S3 的硬前置** | ✅ |
| **S1 语义基线** | `RoutingContract` 进 `ChildAgentRef`，`DelegationToolSchema` 渲染富候选 | Router 能"看见"子在做什么；`RoutingSchemaParityTest` 断言 `enum` 与 `candidates` 同源同序 | ✅ |
| **S2 引擎核心** | Predicate 文法 + 求值器 + DNF 规范化 + V1-V10 | 单测覆盖文法每条产生式；DNF 超预算被拒；RE2 防 ReDoS | ✅（独立模块 `agent-routing`） |
| **S3 运行期** | `selectByRoutingPolicy` 重写为两阶段 + 决策快照落库（需给 `agent_invocation` 加 `routing_decision jsonb`/`policy_ref`/`policy_fingerprint`） | **同输入重放必得同目标**（`RoutingReplayIT`）；`RULE_WINS` 不改判定 | ✅（见 §6.1.1 的两处收紧；`ctx.channel`/`ctx.locale` 缺口见 §6.1.3；**策略级收窄已补，见 §6.2.1 · C38-2**） |
| **S4 数据层** | DDL 重建 + 删旧枚举 + 三表收敛 | 真实 PG17 IT；RLS 跨租户 fail-closed | ✅ |
| **S5 前端** | 策略编辑器 + 样本回放面板（走后端 dry-run）+ 契约表单 | 前后端语义一致（同一实现） | ✅ 一致性成立：登记表单、试跑面板、契约页（A6）都把草稿**原文**交同一份后端实现，前端零求值器。**剩余边界不在本仓**：`/api/cap/config/**` 属租户域链（`SecurityConfiguration` 的 `@Order(0)` 只匹配 `/api/platform/**`），平台令牌对其 401/403；页面按 AI 实验室同口径**明示「需租户级令牌通道」**，不把「没跑成」显示成「没问题」 |
| **S6 文档回写** | 更新 `RoutingStrategy`/`OrchestrationMode` 的"本批取舍" javadoc + 06/08/09 | 无死字段；文档承诺与实现逐条对得上 | ✅（10/06/08/00 与 `Agent设计总览` 已对账；`RoutingStrategy` 枚举随 S4 删除，其纪律落在 `RoutingEngine`/`ConflictPolicy` javadoc） |

测试清单见 [11 号文档](11_RouteAgent请求示例模拟.md) §15。

---

## 10. 一句话总结

把「路由目标只有 id」补成「**父侧声明的路由契约** + 策略内的**确定性规则**」：用**有界 DNF + 样本回放**解决 javadoc 搁置的发布期校验，用**封闭文法 + 决策快照**解决确定性与可复核，用 **`on_error` 二值 + ERROR≠MATCH** 解决失败语义。`RULES_ONLY` 下模型甚至不参与目标选择 —— 转发真正"按规则"。

**但在动 S1 之前，先把 S0 的六个缺口关掉。** 没有摘要、没有 configVersion、input 可缺省、未知字段不拒收的系统里加规则引擎，得到的只会是一个「看起来在按规则路由、实际无从证明」的新对象。

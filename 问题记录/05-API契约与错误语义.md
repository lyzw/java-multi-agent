# 05 · API 契约与错误语义类

> **判据**：根因是**接口契约 / 状态码 / 错误体 / 数据形状**。
> 典型形态：状态码语义反转、错误体与审计码不同源、请求字段类型与线格式不一致。

---

## API-01 · 未映射路径返回 500 而非 404

**问题描述**
`GET /api/nonexistent` → **500**（应为 404）。`/api/cap/config/nope`、`/api/audit-records`、
`/actuator/nope` 全部同样。

**产生原因**
Spring 6+ 起，未映射到任何 `@RequestMapping`（且不匹配静态资源）时抛
`org.springframework.web.servlet.resource.NoResourceFoundException`，Spring 默认映射为 **404**。
但 `ApiExceptionHandler` 的兜底 `@ExceptionHandler(Exception.class)`
**优先于** Spring 内置的 `DefaultHandlerExceptionResolver`
→ 未映射路径被**吞成 500**。

这与 P4-D09 已修的 `ResponseStatusException` 属**同族缺陷**（该族的三重危害）：
① 状态码语义反转（客户端无法区分「路径写错」与「服务端故障」，会做出**完全相反**的重试决策）；
② 破坏存在性探测纪律；③ 监控污染（用户笔误被计入服务端错误率）。

**相关解决方案**
新增**更具体**的处理器（Spring 按最具体匹配优先）：
```java
@ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
public ResponseEntity<ErrorResponse> handleNoResourceFound(
        org.springframework.web.servlet.resource.NoResourceFoundException e) {
    // ⚠️ 不回显 e.getMessage()：它含请求路径原文（可能含客户端数据）
    log.info("请求路径未映射到任何端点: path={}", e.getResourcePath());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(codeOf(HttpStatus.NOT_FOUND), messageOf(HttpStatus.NOT_FOUND)));
}
```
归一化口径：`code=NOT_FOUND` / `message=资源不存在` ——
与「已映射但资源不存在」的 `ResponseStatusException` 分支**同码同文案**，两类「不存在」不再分叉。

> ⚠️ **为什么必须有真实 HTTP 的 IT**：`@ExceptionHandler` 的**选路优先级**属 Spring 框架行为，
> 处理器自身的单元测试证明不了。同时安全链是 `anyRequest().authenticated()`，
> **无令牌的请求在到达 DispatcherServlet 之前就被 401 拦下** —— 故 IT 必须带有效令牌，
> 否则测到的是认证链。

**来源**：`docs/E2E实测缺陷修复与复验_20261023.md` §2
**状态**：✅ 已修

---

## API-02 · `handleResponseStatus` 把控制器声明的 5xx 覆盖成 500

**问题描述**
`ApiExceptionHandler.handleResponseStatus` 处理 `ResponseStatusException` 时，
对 5xx 状态码将其转换为 500。当控制器显式声明 5xx（如 503 `SERVICE_UNAVAILABLE`）时，
此方法会把它变成 500，**破坏了语义**。

**产生原因**
把「5xx 一律当内部错误兜底」写成了无条件分支，没有区分
「非法状态码」与「合法的服务端错误状态」。

**相关解决方案**
```java
HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
if (status == null) {                       // 未映射到 HttpStatus 的状态码
    log.error("控制器声明了非法状态码: code={}", e.getStatusCode(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "服务暂时不可用"));
}
if (status.is5xxServerError()) {           // ⚠️ 控制器显式声明的 5xx 保持原样
    log.warn("控制器声明了服务端错误状态: code={}", e.getStatusCode());
    return ResponseEntity.status(status)
            .body(new ErrorResponse(codeOf(status), messageOf(status)));
}
```

**来源**：`platform/问题记录/记录-2026-10-30.md` 问题 5、`git log d64be65`
**状态**：✅ 已修

---

## API-03 · 响应 `code` 与审计 `reason_code` 不同源 → 8 个闸门原因码塌缩成一句通用文案

**问题描述**
执行票证端点 8 个闸门原因码（`CHANNEL_DISABLED` / `RATE_LIMITED` /
`RATE_LIMIT_UNAVAILABLE` / `TENANT_NOT_FOUND` / `TENANT_NOT_ACTIVE` /
`PRINCIPAL_NOT_FOUND` / `PRINCIPAL_NOT_ACTIVE` / `TICKET_ROLE_NOT_HELD`）
在 HTTP 侧**全部塌缩成**「资源不存在」，而库里写着 `TENANT_NOT_FOUND` ——
两份记录对不上号，运维只能进库查审计。

**产生原因**
`ApiExceptionHandler` 对 `ResponseStatusException` **刻意不回显 `getReason()`**
（按状态码归一，防止把内部原因泄露成枚举）。
这个决策本身合理，但**闸门需要把原因码透出给调用方** —— 两者共用一个异常类型就必然冲突。

**相关解决方案**
按项目既有先例（`ConfigOverrideController.ApprovalRequiredException`）改为**专用异常 + 专属 handler**：
- `PlatformExecutionTicketService.TicketRejectedException(status, reasonCode, message)`，
  所有闸门经 `denyAndReject(...)` **一处写审计、一处出响应**，保证两侧原因码同源；
- `ApiExceptionHandler.handleTicketRejected` 返回 `ErrorResponse(reasonCode, message)`，
  即 `code` 直接就是审计表的 `reason_code`，可 grep、可对照；
- 文案补上可行动信息（租户 UUID、登录名、当前状态）。

**这条是被测试钉住的，不是靠 review 看出来的**：新增
`responseCodeAndAuditReasonCodeAreTheSameValue` 把 6 个闸门逐个走一遍，
断言「响应 code == 审计 reason_code」且文案含标识符。
写完第一条就抓到一处真缺陷 —— `TENANT_NOT_ACTIVE` 的文案只说「当前状态不允许」而不说**是哪个租户**，
断言直接失败（`没给出任何可行动信息`）。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §9.3
**状态**：✅ 已修（13/13 绿）

---

## API-04 · `reindex?limit=` 查询参数被静默忽略

**问题描述**
`POST /api/v1/management/reindex?limit=4` 返回 200，但实际按默认上限 500 跑 ——
调用方以为「只跑了 4 条」，响应里也没有回显生效的 limit。

**产生原因**
`limit` 只声明在请求体 `ManagementDtos.ReindexRequest` 里（`@RequestBody(required=false)`），
查询参数从未参与绑定，于是被**静默忽略**。
前端两个调用点都正确地发 body，所以这个坑只咬手工 curl 的运维路径 ——
**而恰恰是这条路径最需要小步试跑**。

**相关解决方案**
`RagManagementController.reindex` + `resolveReindexLimit`：
`?limit=` 现在生效；请求体与查询参数同时给以**请求体为准**（它是带 Bean Validation 的正式契约）；
非整数与越界都显式 400，**不放进端口层变 500**。
新增 4 个单测，真实服务上验证：`?limit=3` → `{"indexedChunks":3,"scannedChunks":3}`
（修复前同样请求跑掉的是 500 条的批次）。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §2.3
**状态**：✅ 已修

---

## API-05 · `input` 形状与服务端契约不符 → 400 `INVALID_REQUEST`

**问题描述**
admin-console AI 实验室发出的请求体：
```json
{"agentId":"…","timeoutMs":30000,"input":"一元二次方程的怎么计算"}
```
→ **400** `{"code":"INVALID_REQUEST"}`。
改成 `"input":{"userMessage":"…"}` → **201** `{"runId":"…","state":"QUEUED"}`。

**产生原因**
服务端 `CreateRunRequest.input` 是 `Map<String,Object>`（`CreateRunRequest.java:36`），
而 `agentLabApi.ts` 把 `input` 声明成 `string` 并原样 `JSON.stringify` 发出 ——
**类型与线格式不一致**。且这个 400 会一路盖在 401 之后，让人误判「权限还是不通」。

**相关解决方案**
1. 调用方类型改成 `userMessage: string`，线格式封装收进 `createRun`；
2. 新增 `admin-console/tests/agentLabRunBody.test.ts` 三条断言把实测固化
   （input 必须是对象、顶层不得出现 `userMessage`/`tenantId`、未声明的可选项不进体）。

> **分离问题的技巧**：修完形状后 401 仍在，「形状已对、401 仍在」正好把
> 「通道问题」与「体形状问题」分离干净 —— 两个缺陷叠在一起时，
> **先把能分离的那层修掉**，另一个才会显形。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §8.2
**状态**：✅ 已修

---

## API-06 · 401 与 403 语义混同 → 前端误清会话跳登录

**问题描述**
前端对 401 **一律**清会话跳登录，把三类情形混为一谈：
① 未登录；② 业务无权限（403 但某些路径返 401）；③ **跨身份域 401**（平台令牌打租户域端点）。
结果正常业务被拒也被踢回登录页、**丢失操作现场**。

**产生原因**
前端只按 HTTP 状态码分支，而 401 在本项目里**不只表示「未登录」**。
「一个状态码表达多种语义」是根因，前端的「一律处理」只是症状。

**相关解决方案**
1. 保留 401/403 + 统一错误体 `{code, message}`；
2. 后端 `SecurityConfiguration` 的 401/403 错误体统一为
   `{code:UNAUTHENTICATED}` / `{code:ACCESS_DENIED}`（HTTP 状态不变）；
3. 前端**严格按 code 分支**：仅 `UNAUTHENTICATED` 清会话跳登录；
   `ACCESS_DENIED` 与跨域 401 **绝不跳登录**，交页面提示或渲染无权限整页；
4. 管理端用「**路径域 + code**」双轴判定；
5. 双端新增 `NoPermissionPage` + `/no-permission` 路由 + `isAccessDenied`/`notifyAccessDenied` 助手；
6. 产出契约文档 `docs/权限管控体系/10_前后端权限响应契约.md`；
7. 测试：前端 32/32、管理端 9/9。

**来源**：`git log fd63fa2`（2026-11-01）
**状态**：✅ 已修

---

## API-07 · 登录端点 401 被当作会话失效 → 用户刚输完密码就被弹回登录页

**问题描述**
登录接口 `/platform/v1/auth/login` 的 401 表示**凭据错误**，而不是会话过期。
原逻辑把任意 401 都按「平台会话失效」处理，清会话并跳登录页，
导致用户刚输完密码就被弹回登录页、**看不到任何错误原因**，表现上像「登录卡死 / 超时」。

**产生原因**
API-06 的残留：修完之后「登录端点的 401」仍落在「清会话跳登录」分支里。
**同一个状态码在不同端点上的语义不同**，统一规则必须留例外。

**相关解决方案**
1. `client.ts`：登录端点的 401 **原样抛出**，消息统一为「用户名或密码错误」；
2. 其余 401 分支语义保持不变（平台会话域清会话跳登录、跨身份域原样抛出）；
3. 补测试：断言**不调用 `clearSession`、不跳转、会话保留**。

**来源**：`git log 3a925c1`（2026-11-01）
**状态**：✅ 已修

---

## API-08 · `handleZeroRetention` 用 422 而非 451（注释与实现不符）

**问题描述**
代码注释写「用 451（Legal Obstacle）表达合规阻断」，但实际返回的是 **422**。

**产生原因**
注释描述的是**意图**，实现是**另一个版本**；改代码时改了实现没改注释（或反之）。

**相关解决方案**
使用 451 状态码。

**来源**：`审查文档/审查-2026-10-30.md` 问题 10
**状态**：✅ 已修

---

## API-09 · 「拒绝未填理由」抛 409 → 前端提示「已被别人处理」

**问题描述**
`ApprovalQueryPort` 在「拒绝操作未填理由」时抛 **409 类**异常，
前端据此提示「**已被别人处理**」，但真相是「少填一个字段」。

**产生原因**
409（Conflict）的语义是「资源状态与操作前提冲突」，而「必填字段为空」是 400（Bad Request）。
**语义用错 → 客户端做出错误判断**（用户以为要重新加载，实际只需补一个字段）。

**相关解决方案**
改为 400。

**来源**：`docs/开发进度总览.md` §3.1 第 5 条
**状态**：✅ 已修

---

## API-10 · `doRollback` 的审计日志 Kind 写成 `PUBLISH`

**问题描述**
回滚操作的审计日志中 `Kind` 写的是 `PUBLISH`，动作描述也是「发布」。
→ 审计记录中**回滚操作被错误地归类为发布操作**，违反审计可追溯性要求。

**产生原因**
复制粘贴错误，从 `doPublish` 复制过来后未修改 Kind 和描述。

**相关解决方案**
改为 `Kind.ROLLBACK`，动作描述改为「回滚」。

**来源**：`审查文档/审查-2026-10-30.md` 问题 5
**状态**：✅ 已修

---

## API-11 · `RequestDigest.escape` 漏控制字符 —— 刻意不动（记录决策）

**问题描述**
`RequestDigest.escape` 与 SEC-18 的两处同族：**漏控制字符**。
但它**刻意不修**。

**产生原因**
它的产物是**幂等摘要的输入**，不是 JSON。
改了会让**在途请求的摘要全部对不上** → 触发幂等冲突。

**相关解决方案**
保持不动并写明理由。**这是「同族缺陷里必须排除的一个」** ——
修缺陷时必须先判断「这个字段的消费者是谁」，否则会引入比原缺陷更大的问题。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §10.3
**状态**：✅ 已决策不修

---

## API-12 · `parsePayload` 手写 `split(",")` 不识别字符串内逗号 → 错误码被静默改写

**问题描述**
`JdbcRunEventQueryRepository.parsePayload` 用 `body.split(",")` 解析载荷，
不识别字符串内的逗号：`errorCode="E1,E2"` 被读成 `{errorCode=E1, E2=null}`
→ **错误码被静默改写**。

**产生原因**
手写 JSON 解析（同样动机见 SEC-18：「避免引入完整 JSON 库」—— 前提是假的）。

**相关解决方案**
改用 Jackson（`MAPPER.readValue(…, MAP_TYPE)`）。

> **本条与 API-13/14 共同形态**：**不抛异常、不断言报错，只是值悄悄变了**。
> 与 P4-D08 R-1（工具未注册为 Bean → 表永远为空）同源 ——
> **静默失效靠「逐行看断言」抓不到，只能靠「针对可疑行为的专项用例」抓。**

**来源**：`docs/开发进度总览.md` §3.1 第 1 条
**状态**：✅ 已修

---

## API-13 · `unquote` 只处理 `\"` 与 `\\` → 含换行的值回读为字面量 `\n`

**问题描述**
`unquote` 只处理 `\"` 与 `\\`，不含 `\n`。含换行的值回读为**字面量两字符 `\n`**（而不是真换行）。

**产生原因**
同上：手写解析的转义表不完整。

**相关解决方案**
同 API-12，一并由 Jackson 接管。

**来源**：`docs/开发进度总览.md` §3.1 第 2 条
**状态**：✅ 已修

---

## API-14 · 手写 `coerce` 把整数全当 `Long` → 写入/读取不对称

**问题描述**
手写 `coerce` 把整数全当 `Long` → 写入 `Integer` / 读取 `Long` 不对称，
下游 `==` **静默失败**（`Integer(1).equals(Long(1L))` 为 false）。

**产生原因**
JSON 里没有整数/长整之分，Java 里必须选一个。手写解析时选了 `Long`，
而写入侧写的是 `Integer` —— **两侧的类型选择没有对齐**。

**相关解决方案**
用 Jackson 的统一类型映射，并补「写入→读回类型一致」的断言。

**来源**：`docs/开发进度总览.md` §3.1 第 3 条
**状态**：✅ 已修

---

## API-15 · `handleResponseStatus` 日志可能包含敏感路径

**问题描述**
`ApiExceptionHandler.handleResponseStatus()` 日志中带异常对象，可能包含敏感路径。

**产生原因**
日志级别/内容与「这条信息会不会流向不该去的地方」没有一起考虑。

**相关解决方案**
移除日志中的异常对象（或降级为只记路径，不记原始异常）。

**来源**：`审查文档/审查-2026-10-30.md` 问题 17
**状态**：✅ 已修

---

## API-16 · `ConfigPublishService.publish()` 中 `lastConflict == null` 分支不可达

**问题描述**
`lastConflict == null` 分支实际**不可达**，可能误导维护者。

**产生原因**
防御式分支与真实控制流脱节 —— 写的时候以为可能为 null，实际路径保证了非 null。

**相关解决方案**
简化为直接 `throw lastConflict`。

**来源**：`审查文档/审查-2026-10-30.md` 问题 9
**状态**：✅ 已修

---

## API-17 · SSE 帧被 Spring 二次包裹 → 事件名全丢，前端零分派

**问题描述**
DevTools 的 EventStream 面板能看到数据（`Type=message`，`Data=event: thinking data: …`），
但右侧对话气泡始终空白、停在「正在生成回答…」。数据到了浏览器，界面却不动。

**产生原因**
`ConsoleChatController.sendFrame` 手工拼 `"event: x\ndata: y\n\n"` 再调 `emitter.send(String)`。
Spring Framework 7 覆写了 `SseEmitter#send(Object)`，语义等价于 `send(event().data(object))`：
整段字符串被当作**一条 data 值**，框架再逐行补 `data:` 前缀。实测原始字节是

```
data:event: thinking
data:data: We need answer Chinese
data:
```

`thinking` / `delta` / `done` 全部退化成匿名 message，前端按 `eventName === …` 分派时一帧都匹配不上。
**契约的破裂点在框架的序列化层，不在业务代码，也不在前端解析**。

**相关解决方案**
1. 改用 `SseEmitter.event().name(name).data(safe)`，由框架生成原生 `event:` 行；
   只做 CR 归一，data 内换行交给 builder 规范为多行 `data:` 续行，不手工拼帧文本；
2. 前端以 `event:done` 终帧收口并 `reader.cancel()`，**不再依赖 TCP EOF**（与 SEC-26 配套）；
3. `ConsoleChatControllerTest` 替身改捕获原生 `SseEventBuilder` 并加「禁止 `send(String)`」守卫；
   前端补「Spring 原生无空格帧」与「流不 close 也靠 done 收口」用例。

**来源**：`git log eac390d`、[`记录-2026-11-07_AI直接对话链路.md`](记录-2026-11-07_AI直接对话链路.md) §二 R3
**状态**：✅ 已修

---

## 本分类小结

| 编号 | 一句话 | 状态 |
|---|---|---|
| API-01 | 未映射路径 500 而非 404 | ✅ |
| API-02 | 5xx 被覆盖成 500 | ✅ |
| API-03 | 响应 code 与审计 reason_code 不同源 | ✅ |
| API-04 | `reindex?limit=` 静默忽略 | ✅ |
| API-05 | `input` 形状与契约不符 | ✅ |
| API-06 | 401/403 语义混同 → 误跳登录 | ✅ |
| API-07 | 登录端点 401 被当会话失效 | ✅ |
| API-08 | 零保留用 422 而非 451 | ✅ |
| API-09 | 「未填理由」抛 409 | ✅ |
| API-10 | `doRollback` 审计 Kind 写错 | ✅ |
| API-11 | `RequestDigest.escape` 漏控制字符（刻意不动） | ✅ |
| API-12 | `split(",")` 破坏字符串内逗号 | ✅ |
| API-13 | `unquote` 转义表不完整 | ✅ |
| API-14 | 手写 `coerce` 整数类型不对称 | ✅ |
| API-15 | 错误日志可能含敏感路径 | ✅ |
| API-16 | 不可达的 `lastConflict == null` 分支 | ✅ |
| API-17 | SSE 帧二次包裹 → 事件名全丢 | ✅ |

> **本类统一教训**：**状态码是契约，不是分类标签**。
> 同一个 401 在不同端点可能表达完全不同的语义（API-06 / API-07）；
> 同一个 500 可能有完全不同的根因（见 [TST-10](07-测试基建与验证方法论.md)）。
> **错误体里的 `code` 必须与审计/日志里的原因码同源**，
> 否则运维要在两个系统之间做人肉对照。

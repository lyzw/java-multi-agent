# RLS → 应用层权限迁移：实现过程的问题—方案—方法论归档

> **归档时间**：2026-11-03
> **主题**：「12_RLS到应用层权限迁移方案」**实现过程**（2026-11-02 集中落地，贯穿 P0 骨架 / P1 影子 / P2 双写起手）中真实遇到的问题、解决措施与可复用方法论
> **依据文档**：`docs/权限管控体系/12_RLS到应用层权限迁移方案.md`（决策规范）、`docs/权限管控体系/13_RLS到应用层权限迁移_设计与实施计划.md`（实施计划 + §10 修订记录，含全部「⚠️ 坑/实测/纠错」）、`问题记录/03-数据库权限与RLS.md`（DBP-01～DBP-18）
> **关联提交**：`4d7c31a`(platform: RLS→应用层权限迁移 + CAP 双写 + IT 测试硬化) 等 W-PERM-P0~P6 系列
> **当前进度**：D1–D4/D7/D11–D13 ✅；D5/D6 🟡 影子态（默认关闭，可由单开关开启）；D8–D10 ⬜ 未开始；约 55 个 `Jdbc*Repository` 尚未接 Gate

---

## 一、背景

迁移的实质不是重写权限逻辑，而是把**已在应用层手写的 `WHERE tenant_id`** 扶正为行级隔离的**唯一权威**，把 RLS 策略谓词逐条翻译成数据库无关的语义对象（`ScopeFilter`），并把 RLS 从「唯一权威」降级为「可拆卸的最内层兜底」。双写期纪律是 **RLS 一条策略都不许动、应用层过滤全部加在 RLS 之内（取交集，只会变紧不会变松）**。

风险最高的是**收回裁决权的过程中两层防线出现空窗**——下面记录的问题，绝大多数是「看似在验证、实际在制造假象」一类：测试绿了、日志干净了，越权却一条没被抓到。

---

## 二、问题—方案—方法论（按影响面与深刻度排序）

### P1 · P0 翻译基线跨库错配（DBP-18，最高频陷阱）

**背景与现象**
- 早前记录「`agent_dev` 仅 10 条单策略、须对齐到 35 条」；那 35 条来自 `rag_base`——它是 **RAG 域的陈旧副本**（比 `study_rag` 少 2 条），从来不是 `agent_dev` 的形态。
- 比的不是「同一批策略翻译得对不对」，而是「两个不同数据库的策略对不对得上」→ P1 影子期**必然全量假 mismatch**，且根因会被误判为「翻译表错了」。

**解决措施及效果**
- 2026-11-02 直连 RDS 从**两个活库各导一份**（`scripts/export-rls-policies.sh` 新增「远程活库」模式 `PG_URI`+`PSQL_BIN`，绕开 docker）：`agent_dev` **50 条**（全 `TENANT_ONLY`）、`study_rag` **37 条**（29+8 读写分），`UNKNOWN=0`，与 DDL 快照策略名逐一比对**零漂移**。
- 旧 `rag_base`(35) 基线归档为 `policy-mapping.legacy-rag_base.yaml`；产出 `authz/policy-mapping.yaml`（agent_dev）+ `authz/policy-mapping-rag.yaml`（study_rag）。
- 立为落地红线：**基线一库一份、先闭合再比对**。

**方法论**
> 基线必须绑定**目标环境的实际形态**且一源一份。跨库混用基线比的是「两个不同库的表对不对得上」，而非「翻译得对不对」，必出假阳性。P1 任何 mismatch 一律先归因基线，禁止修代码迁就。

---

### P2 · 结果集染色校验的同义反复（D5，两层假绿，全文最深刻）

**背景与现象**
- **第一层**：首版把「查询绑定租户」当行租户传给 `TaintVerifier`，比对的是「我们打算查谁 vs 我们打算查谁」→ 染色**恒不违例**、零检出力，比不做染色更危险（提供虚假覆盖感）。
- **第二层（更隐蔽）**：用**行租户**当主体 `SubjectContext.forTenant(rowTenantId)`，策略引擎对 `TENANT_ONLY` 必然输出 `TenantOnly(行租户)`，`row.tenantId == 行租户` 恒等 → 逐行比对恒通过，除 `None` 外毫无检出力；且被单测替身返回的**常量 filter** 完美掩盖。

**解决措施及效果**
- 行租户**必须从结果行读出**：`SELECT` 显式取 `tenant_id::text`，用 `rs.getString("tenant_id")` 染色；给 5 个仓库的 SQL 补该列（`run`/`agent`/`agent_version`/`weakness_record`/`run_step`/`tool_operation`/`budget_*`）。
- **主体与行归属分离**：`shadowTaint(type, subjectTenantId, rowTenantId)`；4 仓同步改签名，并加**真实 `RuleBasedPolicyEngine`** 回归用例（主体 A + 行 B → 必须违例），改回旧实现即红。
- 聚合查询**不可染色**（移除 `terminationDistribution` 该类调用，改由谓词比对 + SQL 审计保证）；哨兵行由「行租户 == 哨兵租户」直接推断（否则 `TenantWithShared` 共享行被误判，`JdbcResultQueryRepositoryShadowTest` 首跑即抓到）。

**方法论**
> 「跑起来了」「日志干净」**不构成证据**。唯一可接受的证据是：**注入一个他租户行，必须产生违例计数**。测试替身返回常量 filter 会掩盖同义反复——必须用真实引擎做回归。染色只信**结果行的真实归属**，绝不信任调用方传入的「打算查谁」。

---

### P3 · 「接缝插电」缺失导致必然假绿（P1 装配）

**背景与现象**
- D2–D7 的能力与单测全绿，但**没有任何装配把 `Gate`/`Reporter` 交给仓库**——5 仓的 `scoped` 恒 `null`、`shadowEnabled` 恒 `false`。
- 若不修，P1 出口条件「≥2 周 `AUTHZ_MISMATCH=0`」会在**一次都没运行过**的情况下达成（假绿）。

**解决措施及效果**
- 新增 `AuthzShadowConfiguration`（`platform-bootstrap`，`@ConditionalOnProperty(agent.authz.shadow.enabled=true)`）：装配 `AuthzSamplingPolicy → AuthzMismatchMetrics → AuthzShadowReporter → RuleBasedPolicyEngine → DefaultAccessGate → ScopedDataAccess → AuthzShadowBinding`。
- **单开关语义**：`AuthzShadowBinding` **存在即开启、不存在即关闭**，仓库经 `@Autowired(required=false)` setter 接收；刻意不做「Bean 在 + enabled 布尔」双开关（那会造出「半开」静默失效，与 `platform-ops` 配置错挂事故同型）。
- 两道新守卫：`AuthzShadowWiringTest`（无 DB——开启时 5 仓私有字段真的被填、关闭保持 dormant）+ `ShadowPolicyCoverageTest`（钩子用到的 `resourceType` 未登记策略即红，否则恒定 `NO_RULE` 假 mismatch）。

**方法论**
> **能力的存在 ≠ 能力的生效**。必须区分三件事——①「机制本身灵不灵」（`AuthzShadowLiveIT` 真库实证）、②「生产开了两周没有 mismatch」（运行时长）、③「接线存在与否」（`AuthzShadowWiringTest`）。三者**不可互替**。装配/接线必须被测试钉死，否则测试绿只是装饰。

---

### P4 · D7 架构守卫首跑即变红（装饰性接线）

**背景与现象**
- 守卫上线首跑即报红：`JdbcAgentMetricRepository` 当时仍是**自建**的 `shadowEnter`/`checkTaint` 实现，未走统一入口 `AuthzShadowHooks`——否则影子期「`AUTHZ_MISMATCH=0`」是假绿，而 P1 出口恰恰依赖这个 0。

**解决措施及效果**
- 新增统一静态入口 `AuthzShadowHooks`（比对 + 染色各两种主体来源：显式 / 环境），**禁止各仓自建实现**；该仓库已重构为委托统一入口。
- `ScopeBypassArchitectureTest`（自研源码扫描，不引 ArchUnit 依赖）钉死 4 条不变量 + 3 条扫描器自证：①`Unrestricted` 唯一产出点为 `RuleBasedPolicyEngine`（R3 不变式）；②已登记仓库必须**真的调用** `AuthzShadowHooks`（防装饰性接线）；③Gate 持有者必须登记（防漏过滤入口）；④台账无漂移。

**方法论**
> 允许「可选接线」= 允许「静默无效」。用**统一入口 + 静态扫描**强制「注入即调用」，把「装饰性接线」在 CI 阶段就判红，而不是等运行期用 `AUTHZ_MISMATCH=0` 自我欺骗。

---

### P5 · JOIN 从表泄漏面 + 活库 SQL 校验缺失（D13）

**背景与现象**
- RLS 的隐性收益是**对查询树中每一张表**（含 JOIN 从表、子查询、CTE）自动注入 `tenant_id` 谓词；应用层 `ScopeFilter` 默认只在**主表**渲染 `WHERE`。这是迁移引入的新型泄漏面。
- 穷举发现**唯一真实缺口 J10**：`JdbcTraceFactRepository#readToolOperations` 是 `att.operation_id = op.operation_id` **单键 JOIN** + `WHERE op.run_id = ?`，主从表都无显式租户谓词，完全依赖 RLS。后果不是「多返回几行」而是**指标口径污染**（他租户 attempt 计入本租户失败率/超时率），既不报错也不越权可见，比主表泄漏更难发现。
- `J3` 首版 `MAX(av.tenant_id)::text` 在活库**直接报错**：PG 无 `max(uuid)` 聚合（`function max(uuid) does not exist`）。

**解决措施及效果**
- D13 由「估算」改为**穷举**：platform 15 处（13 合规 + 2 平台目录豁免）/ RAG 24 处（全复合键）/ SQL 5 处（全豁免）。
- J10 补 `att.tenant_id = op.tenant_id` 与 `op.tenant_id = ?::uuid`；JOIN 从表**二次染色**（投影补 `join_tenant_id`，`LEFT JOIN` 空值跳过）；守卫 `JoinTenantPredicateArchitectureTest`（连接条件窗口必须含 `tenant_id`、新从表未登记即红、3 条扫描器自证）。
- **4 条改动 SQL 全部在 `agent_dev`（`app_agent`，`BEGIN…PREPARE…ROLLBACK` 零写入）上做解析校验**，输出 `ALL_PREPARE_OK`。

**方法论**
> ① RLS 给每张连接表自动加谓词，是迁移**拿走**的能力，必须主动**穷举**而非估算（实测「到处都漏」的直觉不成立，真缺口只有 1 处，但 1 处就是口径污染）。② 影子单测用 `NoConnectDataSource`，**SQL 语法/列解析根本测不到**——任何会进真实库的 SQL，都要在活库上做解析校验，不只靠单测替身。

---

### P6 · 决策日志「只写不读」的连锁坑（D6，P2，三坑同源）

**背景与现象（三个相互关联的坑）**
- **a. 授权回潮**：`03_platform_roles.sql` 的 `ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT ON TABLES TO app_agent` 会让**此后新建的任何 platform 表**自动获得 SELECT；只写 `GRANT INSERT` 不写 `REVOKE SELECT` = 「这次修好、下次建表又漏」。
- **b. RETURNING 需要 SELECT 权限**：本表按设计恰恰不给应用角色 SELECT，于是「为拿插入 id 顺手加 `RETURNING`」会让**每次判定都写失败**，且失败被「限频告警 + 绝不外抛」吞掉 → 判定照常、审计零行、系统全绿。
- **c. 自相矛盾的全站故障**：`JdbcAuthzDecisionLog` 类注释写「任何 SQL 异常只记录、不阻断」，实现却 `throw`——Permit 路径上日志写在返回 filter **之前** ⇒ 审计表抖动把「放行」变成 5xx；Deny 路径上异常覆盖原始 `reasonCode` ⇒ 调用方分不清 403 与 500。

**解决措施及效果**
- 显式 `REVOKE SELECT` 抵消 `DEFAULT PRIVILEGES`（两道守卫：`AuthzDecisionLogSchemaTest` 验 03 同时含 `GRANT INSERT` 与 `REVOKE SELECT`）。
- 两道 `RETURNING` 守卫：源码 `AuthzDecisionLogSchemaTest#insertMustNotUseReturning` + 活库 `AuthzDecisionLogLiveIT#insertWithReturningIsDenied`（后者防绕过源码直拼 SQL）。
- `tenant_id` 用 **text 而非 uuid**（审计流水要能记任意形态主体，转型失败 = 这次判定没留痕迹，而那正是最需要痕迹时）。
- 双层 `try/catch`：`DefaultAccessGate.record` 统一兜底（换任何 recorder 都不破坏判定）+ `JdbcAuthzDecisionLog` 自身不外抛 + 60s 限频告警。
- 启动期两道 fail-closed（`AuthzDecisionLogGuard`）：①开开关但无记录器 → 拒启；②记录器是 Jdbc 但**零行写入探测**（`INSERT…SELECT…WHERE FALSE`）失败 → 拒启（用「真走一遍 INSERT」而非 `has_table_privilege`，后者对列漂移无感）。

**方法论**
> ①「只写不读」**不只是一个授权配置，它还是对 SQL 写法的约束**——授权收紧会连带改变哪些语句可用，这类耦合只有真库跑才暴露。② 区分**「可写」（零行探测证明）** 与 **「在写」（写后由库 owner 回读整行证明）**——两者不可互替。③ 权限 / SQL / 异常三者的耦合必须在活库上实证，单测用动态代理 JDBC 测不到。

---

### P7 · G3 清理通道凭据隔离（运行期 permission denied 陷阱）

**背景与现象**
- 库侧撤销 `app_agent` 对 `audit_record`/`erasure_certificate` 的 DELETE 之后，若应用仍用**主数据源**跑保留期清理，清理任务会在**运行期** `permission denied`——而清理是低频批处理，可能几周后才被发现。

**解决措施及效果**
- 接入独立清理数据源 `agent_retention`（`AGENT_RETENTION_DATASOURCE_*`）+ `AGENT_RETENTION_REQUIRE_ISOLATED_CREDENTIAL=true`（缺清理凭据即拒启，fail-closed）。
- `RetentionErasureIT` 改**双凭据**：应用池验「删不了」、清理池验「删得对」，并在 `@BeforeAll` 断言两侧身份，防止「清理池其实连成了应用角色」。
- 同时排掉 3 个「测试看似在验证、实际在制造假象」的坑：①FORCE RLS 下未绑 `app.tenant_id` 的 DELETE 策略求 NULL → 影响 0 行不报错（故障在下一条断言以无关面貌出现）；②`erasure_certificate` 被 `forbid_certificate_mutation` 无条件拒删，连清理角色也无通道 → 断言只能写「本次未新增凭证」而非「为空」；③`set_config(...,true)` 事务局部，autocommit 上声明完 actor 就失效。

**方法论**
> 撤销权限的**另一半是应用侧接线**。权限收窄的影响面要在**运行期**而非编译期发现；低频批处理路径的权限缺口有长潜伏期，必须用**双凭据隔离 + 启动期断言**固化。FORCE RLS 的「静默 0 行」是最危险的无声失败——不报错、不越权可见，却让后续断言指向完全无关方向。

---

### P8 · 测试隔离：IT 与运行实例共库污染（同源根因）

**背景与现象**
- `PgQueueIT`/`MessagingSwitchGuardIT`/`JdbcStatisticsRepositoryIT` 等长期在种子租户 `t-a` 做「入队→我领得到」「基线差值」类断言，而本机常驻平台实例按 `agent.worker.tenant-slugs`（默认 `t-a`）轮询**同一个 `agent_dev`**：
  - 把消息抢先 `claim`+`ack` 删除（`claim` 返回 0 行、无异常无日志；同秒 `count(*)` 还能数到）；
  - 把 IT 遗留的 Run 当成真活儿跑完（`t-a` 累积 **588 条 Run**，几乎全 `FAILED`，每轮回归 +约 90 条），改写统计基线（`expected 3L but was 4L`）。
- 长期被当作「flaky」忽略，实际每次都在掩盖真实前提缺失。

**解决措施及效果**
- **根治 = 整库隔离**：`scripts/dev-it-db.sh` 建 `agent_it`（本地容器 / RDS 独立库），IT 全部指向它，与运行实例彻底分开。
- **最小前提 = 租户级隔离**：`ItTenantProvisioner`（test 作用域，唯一幂等供给入口，`it-` 前缀硬校验 + tenant/agent/agent_version/execution_snapshot 一次建齐）；钉死**幂等**——首版用 `ON CONFLICT DO NOTHING` 但唯一约束是 `(tenant_id, snapshot_id)`、`snapshot_id` 每次新随机 ⇒ 冲突永不发生、等于没写，连跑 5 轮攒 5 份快照且不可删；改 `AND NOT EXISTS`，`ItTenantProvisionerIT` 断言**行数不增长**而非「调用没抛异常」。
- 共享库上的断言**一律带租户过滤**（`GovernanceQueryIT` 的 `SELECT snapshot_id … LIMIT 1` 补租户条件，否则取错租户让复合外键报错且信息不指向真因）。

**方法论**
> 测试绿可能只是「**环境前提刚好成立**」。断言隐含的「数据量不会涨 / 一页装得下 / 只有我能做 X」前提一旦被运行实例改写，失败信息指向完全无关方向。要把隔离做成**硬约束**（整库或独占租户），而非靠「记得清理」；且「幂等」必须由**行数不增长**这类真证据钉死，ON CONFLICT 不报错 ≠ 真幂等。

---

### P9 · 采样率 0.1% 盲区（D6 采样）

**背景与现象**
- 0.1% 在「(主体,资源) × 300s 一桶」的确定性采样下，单个组合平均要等约 **100 小时**才被采到一次；两个月观察期里小流量租户可能**一次都没被看过**，读数却仍是 `AUTHZ_MISMATCH=0`。

**解决措施及效果**
- 实现 `AuthzSamplingPolicy`：**确定性分桶**（同窗口结论恒定 → mismatch 可复现）+ **窗口滚动**（长期覆盖每个组合，不靠运气）+ **高敏清单 100%**（导出/检索/统计/跨租户读）；默认 `rate=0.01`。
- 明确：**采样只决定「整次查询要不要看」**；被采到的查询**内每一行都染色**，违例不会被二次采样丢掉。

**方法论**
> 可观测性要**可复现 + 全覆盖**。纯随机不可复现（mismatch 复现不出就无法定位）；过低采样率会漏掉小流量租户制造假阴性。确定性分桶兼顾「mismatch 可复现」与「长期覆盖每个组合」。

---

## 三、其余已记录的次要问题（清单式，详见 12/13 文与 问题记录/03）

| # | 问题 | 处置 |
|---|---|---|
| S1 | `ScopeFilter.None` 用匿名类实现密封接口，**无法编译** | 改 permitted `record None()`，`switch` 用 `case ScopeFilter.None n` 满足穷尽性 |
| S2 | `AccessGate` 原「构造期冻结 filter」的 `scoped()` 工厂与单例 Repository / 原始 `DataSource`/`Connection` 现实不符 | 改 Repository 持有 Gate 单例、每查询**执行期**现算 filter 并渲染 |
| S3 | 决策日志表 `tenant_id` 用 uuid → 转型失败 = 没留痕迹 | 改 **text**，能记任意形态主体标识 |
| S4 | `GovernanceQueryIT.findItem` 用「取 500 条再筛」，而 `t-a` Run 累积 588 条被挤出第一页 | 改**沿游标翻页**（带页数上限）+ 以库 `input_payload->>'subject'` 聚合核对（O(n²)→一次查询） |
| S5 | `RateLimitIT` 窗口起点由 DB `now()` 决定，远端 RDS 往返数百 ms 跨秒 → 偶红（单跑恒绿、全量偶红） | 紧贴边界 + **跨窗即重试（上限 5 次）**，超限提示「非限流缺陷」 |
| S6 | 平台全域聚合（无租户主体）在影子期拿租户态主体比对 = 恒定假 mismatch | `boundTenantId==null` 跳过该路径比对，归 D12/P3，不在影子期冒充已验证 |
| S7 | 决策日志 `NO_SUBJECT` 拒绝路径无法记日志（无主体可记） | 已知边界，靠调用方日志补 |
| S8 | 约 55 个 `Jdbc*Repository` 仍未接 Gate | `AUTHZ_MISMATCH=0` **不可外推**；守卫只扫 `platform/`，RAG 与 `deploy/sql` 靠 `authz/join-inventory.md` 人工登记 |

---

## 四、跨问题总体方法论（可复用原则）

把上面的问题提炼成可带回未来「安全/权限/可观测性」类改造的原则：

1. **「绿」不等于「对」** —— 测试通过、日志干净、指标为 0，都可能是假象。唯一可信的证据是**注入一个反例必须被检出**（他租户行必须违例、绕 Gate 必须红、删 RLS 必须红）。
2. **能力的存在 ≠ 能力的生效** —— 代码写了 ≠ 被装配、被调用。接线/装配必须被测试钉死（WiringTest / 真库 LiveIT），区分「机制灵不灵 / 生产开了没 / 接线在不在」。
3. **同义反复是假绿的第一来源** —— 用「打算查谁」比「打算查谁」、用「行租户」当主体，都能让校验恒通过。校验必须基于**客观第三方的真实状态**（结果行的真实 `tenant_id`、真实策略引擎）。
4. **单测替身会掩盖真问题** —— 常量 filter、动态代理 JDBC、`NoConnectDataSource` 测不到 SQL 语法、列解析、RLS 行为、真实行归属。任何进真实库的语句都要在**活库上做解析/回读实证**。
5. **区分三类「证明」不可互替** —— 可写（零行探测）/ 在写（写后回读）/ 运行期开启（时长），每类解决不同疑问，缺一类就是盲区。
6. **权限收窄会连带改变 SQL 可用性** —— 「只写不读」是对写法的约束（`RETURNING` 要 SELECT）；DEFAULT PRIVILEGES 会悄悄回潮授权。这类耦合只在真库跑才暴露。
7. **基线/口径必须绑定实际形态且一源一份** —— 跨库混用、跨环境错配比的是「两个不同对象对不对得上」，必出假阳性。
8. **可观测性要可复现 + 全覆盖** —— 确定性分桶兼顾复现与长期覆盖；低采样率对小流量租户是假阴性。
9. **隔离做成硬约束而非约定** —— 整库或独占租户，且「幂等/无泄漏」由行数不增长这类真证据钉死，不靠 ON CONFLICT 不报错。
10. **fail-closed 贯穿三层** —— 无上下文即拒绝、filter 缺失即构造失败、`None` 渲染恒假、染色违例即告警、缺凭据即拒启；任何「降级即放行」的写法都要被双层守卫堵住。

---

## 五、关联资产索引

**方案与计划（本归档依据）**
- `docs/权限管控体系/12_RLS到应用层权限迁移方案.md`（决策规范，§11 评审修订、§11.1 实施进展与全部「⚠️ 坑」）
- `docs/权限管控体系/13_RLS到应用层权限迁移_设计与实施计划.md`（§8.1 测试隔离、§10 修订记录逐条实测）
- `问题记录/03-数据库权限与RLS.md`（DBP-01～DBP-18，含 DBP-18 跨库错配）

**P0 盘点交付物（authz/）**
- `authz/policy-mapping.yaml`（`agent_dev` 50 条）/ `authz/policy-mapping-rag.yaml`（`study_rag` 37 条）/ `authz/policy-mapping.legacy-rag_base.yaml`（归档）
- `authz/guc-dependency.md`（D11）/ `authz/non-repo-surface.md`（D12）/ `authz/join-inventory.md`（D13，含 J10 缺口与二次染色）
- `scripts/export-rls-policies.sh`（远程活库模式 `PG_URI`+`PSQL_BIN`）

**关键实现类（已确认存在）**
- 领域/鉴权层：`agent-core/.../core/security/`（ScopeFilter/Scope/Action/Decision）、`agent-core/.../core/tenant/SubjectContext.java`、`agent-infrastructure/.../security/`（AccessGate/DefaultAccessGate/PolicyEngine/AuthzShadowHooks/AuthzDecisionRecorder）
- 方言/染色：`agent-infrastructure/.../persistence/scope/SqlScopeTranslator.java`、`.../persistence/scope/TaintVerifier.java`
- 装配/守卫：`platform-bootstrap/.../config/AuthzShadowConfiguration.java`、`agent-infrastructure/.../security/ScopeBypassArchitectureTest.java`、`.../security/JoinTenantPredicateArchitectureTest.java`、`.../security/AuthzShadowWiringTest.java`、`.../security/ShadowPolicyCoverageTest.java`
- 真库实证：`agent-infrastructure/.../security/AuthzShadowLiveIT.java`（5 例）、`.../security/AuthzDecisionLogLiveIT.java`（5 例）、`.../persistence/ItTenantProvisionerIT.java`（2 例）
- 测试隔离：`agent-test-support/.../db/ItTenantProvisioner.java` + `ItTenantProvisionerTest.java`、`scripts/dev-it-db.sh`（建 `agent_it`）

**提交**
- `4d7c31a` platform: RLS→应用层权限迁移 + CAP 双写 + IT 测试硬化（及 W-PERM-P0~P6 系列：`0d20d9e`/`b30ccdd`/`c1680cf`/`b770141`/`17d50f1`/`b418f1b`/`3c6372f` 等）

**验证结论**
- 2026-11-02 全量：10 模块 BUILD SUCCESS，2672 例 0 失败 / 5 跳过（bootstrap 外部依赖用例）；后续 RDS `agent_it` 全量 2693 例 0 失败 / 5 跳过。
- D5/D6 仍为影子态：默认关闭，可由 `agent.authz.shadow.enabled=true` 整体开启；生产 2 周观察期未开始计时。

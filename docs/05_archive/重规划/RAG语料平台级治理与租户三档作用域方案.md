# RAG 语料平台级治理（第四刀）+ admin-console 三档作用域改造方案

> **适用范围**：`rag/`（服务与 DDL）、`admin-console/`（平台控制台前端）、`platform/`（治理票据签发）。
> **上游依据**：`rag/docs/RAG-D11_全局与租户双作用域.md`（本片关闭其 R-1 / R-3 两条遗留风险）、
> `docs/重规划/平台身份与租户身份分离改造方案.md` §5.6 / §6 / §10 禁区。
> **决策前提**：项目未上线，dev 库可整体重置，作为新基线，不迁就现有实现细节。
> **用户定案（2026-10-29）**：① RAG 语料页「不选租户」既要能看**平台全局语料**，也要能看**全部租户盘点**；
> ② 平台自己维护的全局语料**本轮就要能写**（不接受「只读 + 运维脚本灌入」）。

---

## 0. 先纠正前提：四类对象里只有 RAG 真的有租户前置

用户原始判断是「RAG 语料、Agent、Skill、Tools、MCP 的维护都要先选租户，应改成可选」。
逐页 + 逐表核实后，**四类里有三类根本不存在租户维度**，方案落点完全不同：

| 对象 | 前端是否要选租户 | 数据模型是否有租户列 | 结论 |
|---|---|---|---|
| **Tool / Skill / MCP 维护** | **否**，注册/改版/停用全走平台级三步申请单（`pages/console/{Tool,Skill,Mcp}CatalogPage.tsx` 内零 `tenantId`） | **没有** `tenant_id` 列，且 `CHECK (namespace='platform')`（`deploy/sql/platform/01_platform_ddl.sql:2110 / :2065 / :1846`） | 「租户可选」= 该概念在此处不成立。**要做的是反方向**：让租户能维护自己的私有 Tool/Skill（新基线，另立项） |
| **Agent 盘点** | 否，`tenantId` 是可选过滤器（SQL `WHERE (?::uuid IS NULL OR a.tenant_id = ?::uuid)`，`JdbcPlatformAgentDirectoryRepository.java:80`） | `cap_agent_def.tenant_id NOT NULL`（`01_platform_ddl.sql:1455-1478`） | 平台级（无租户）Agent **模型装不下** → 属《松绑方案》阻塞项 B2，另立项 |
| **Agent 维护（租户级编辑）** | 路由已摘除（`admin-console/src/router.tsx:28-31`），平台令牌打租户域必 401 | 同上 | 落点在《admin-console_Agent操作_租户绑定松绑方案》§2.3，不在本片 |
| **RAG 语料** | **是**，硬要求：17 个管理端点全部 `requireTenantContext`，缺 `X-Tenant-Id` → 400（`RagManagementController.java:742-755`）；前端未选租户即禁一切入口（`RagManagementPage.tsx:105,183-188`） | **有**：`scope` 判别列 + 全局哨兵 `00000000-…-000000000000`（`01_rag_ddl.sql:237-246`），8 张表已按双作用域建模 | **本片唯一的真实改造对象** |

### 0.1 RAG 的真问题不是「必填」，是「平台全局语料这条链在代码层是断的」

DDL 早就支持全局语料，但**管理面既看不见也写不出**，四处各自堵死：

| # | 堵点 | 证据 |
|---|---|---|
| C1 | REST 层拒绝「无租户」和「哨兵租户」 | `RagManagementController.java:742`（缺头 400）、`RagTenantContext.java:42`（构造器拒哨兵） |
| C2 | 列表 SQL 用**显式等值** `WHERE tenant_id = rag_current_tenant()`，不靠 RLS 的「本租户 ∪ 全局」 | `JdbcCorpusManagementRepository.java:184,373,464,484,509,541,573-623,669,705,717,729,804,829,847`（共 23 处 SELECT） |
| C3 | 写/改/删 RLS 策略恒为 `tenant_id = rag_current_tenant() AND scope='TENANT'`，不含全局分支；`rag_current_tenant()` 遇到哨兵直接 `RAISE`（SQLSTATE 42501） | `01_rag_ddl.sql:217-220`、读/写策略 `:1096 / :1110 / :1103 / :1055` |
| C4 | 应用连接角色 `rag_app` = NOSUPERUSER + **NOBYPASSRLS**（启动自检检出即拒启） | `03_rag_roles.sql:78-83`、`RagStartupSelfCheck.java:88-97` |
| C5 | worker 重索引显式跳过哨兵租户，注释写着「全局索引通道属第四刀」 | `ReindexRunner.java:100-107`、`ChunkIndexer.java:127` |

> **C1–C3 是纯代码问题**（第 1 刀可解，零 DDL）；
> **C4–C5 必须新增一条独立的平台治理数据源**（第 2 刀），因为 RAG-D11 §2.1 把「应用角色写不出全局行」
> 本身就当成越权闸门——**不能为了打通功能而放宽 `rag_app` 的策略**，只能另开一条有身份、有审计、限范围的通道。

---

## 1. 方案总纲：三档作用域 + 一条独立的治理通道

### 1.1 三档作用域（前端可见、后端可验、审计可述）

| 档位 | 名称 | `X-Tenant-Id` | 数据可见范围 | 可写范围 | 通道 |
|---|---|---|---|---|---|
| **G** | 平台全局语料 | **不传** | `scope='PLATFORM'`（哨兵桶） | 可写（第 2 刀起） | 治理票据 → `rag_ops` 会话窗 |
| **T** | 单租户语料 | 传真实租户 UUID | 该租户私有（现状不变） | 可写 | 现状 `rag_app`，不变 |
| **A** | 全部租户盘点 | **不传** | 全库**元数据**（来源/文档/统计/问题清单） | **只读**（红线，见 §2.3） | 治理票据 → `rag_ops` 只读窗 |

**作用域由请求头显式声明**（`X-Rag-Scope: PLATFORM|TENANT|ALL`），**不由「有没有传租户」隐式推断**：
隐式推断会让「忘记传租户」这种本地配置错误静默变成一次跨租户查询——这正是 C1 fail-closed 想拦住的事。
缺省恒为 `TENANT`，且 `TENANT` 档行为与今天逐字节一致。

### 1.2 治理票据（对齐执行票证，不复用登录令牌）

现状有一条必须一并修掉的隐性越权：**任何**登录进控制台的账号（不分角色）持平台域令牌打 RAG，
`RagJwtAuthFilter` 对平台域**不做任何归属校验、也不做权限校验**（`:166-178`，域信息在 `doFilter` 返回时即丢弃），
等于今天就能改任意租户语料。所以 G/A 两档的前提是「RAG 能自己判断这个调用配不配」。

| 设计点 | 取法 | 样板 |
|---|---|---|
| 谁签 | platform 服务新增 `POST /api/platform/v1/rag/governance-ticket`，签发前 `PlatformSecurity.require(principal, RESOURCE_MANAGE, "rag_corpus", null)` | `PlatformExecutionTicketController.java:62-75` |
| 票里写什么 | `rag_scope`（PLATFORM/ALL）+ 签发人 + 短 TTL + `principal_type=PLATFORM_USER`；**不写租户列表** | `PlatformJwtTokenService.java:188-211` |
| RAG 怎么认 | `RagJwtAuthFilter` 解析平台域令牌后，把「域 + 票据声明」写入 **request attribute**（今天只写了日志上下文），控制器据此放行 G/A | `RagJwtAuthFilter.java:177`（现仅 `LogContext`） |
| 审计 | 签发与每次 G/A 档写动作均落平台审计（与租户 `audit_record` 物理隔离），`correlation_id` 关联 | `PlatformAuditPort.java:21-31`、`PlatformCapabilityConsoleController.java:465-471` |
| 落盘纪律 | 票据只在内存，**不进任何浏览器存储**（刷新即失效是特性） | `api/executionTicketApi.ts:11-15` |

> **不选「给 `rag_app` 放开全局写策略」的理由**：`scope='PLATFORM' ∧ tenant_id=哨兵` 的写策略一旦对应用角色开放，
> 所有走 `rag_app` 的租户请求就都拿到了一条「够得着全局桶」的写路径，越权面从「控制台」扩散到「每一个租户」。
> 用独立角色 + 独立连接池，把这条能力**物理限长在一个入口上**。

### 1.3 `rag_ops` 数据源（逐件对齐 cap 侧现成样板）

| 件 | cap 侧样板 | RAG 侧对应 |
|---|---|---|
| 角色 | `platform_ops`：`LOGIN NOSUPERUSER BYPASSRLS`（`03_platform_roles.sql:122-123`） | 新增 `rag_ops`，同形状（`deploy/sql/rag/05_rag_ops_role.sql`） |
| 第二数据源 | 独立 Hikari 池，`initializationFailTimeout=-1`、池上限 2（`PlatformOpsDataSourceConfiguration.java:102-120`） | `rag.platform-ops.datasource.*`，凭据独立（`rag.env.example` 加 4 个变量） |
| 会话窗 | `PlatformOpsSessionPort.inPlatformScopeSession(...)` **刻意不暴露 DataSource**（`:44-46`）；`ThreadLocal` 深度计数 + `finally` 恢复（`PlatformOpsDataSourceProvider.java:149-175`） | `RagOpsSessionPort` 同形状，窗口外取连接抛 `IllegalStateException`（`GuardedPlatformOpsDataSource.java:38-40`） |
| 未接入 | 抛 `PlatformOpsUnavailableException` → 全局异常处理器归 **503**，绝不回落应用角色（`ApiExceptionHandler.java:231-237`） | `RagOpsUnavailableException` → 503；`Configuration` 在 `enabled=true` 而缺 url/username 时**拒绝启动** |
| 越权护栏 | 事务入口先拒 `tenantId != null`（`JdbcPlatformOpsConfigPublishTransaction.java:60-65`），ops 凭据不得写租户数据 | 同形护栏：G 档写路径**只允许** `tenant_id = rag_global_tenant_id()`；A 档窗口**只允许** SELECT |

**风险姿态按用户定案执行**：`rag.platform-ops.enabled` **默认开**，补偿控制常驻
（票据鉴权 + 权限闸门 + 逐动作平台审计 + 写路径租户等值护栏 + 503 fail-closed），
不用「默认关更保险」当护栏。

---

## 2. 红线（本片不许触碰）

1. **`rag_app` 的读写策略一条不改**（§1.2 理由）。C3 的「写不出全局行」是越权闸门本身。
2. **启动自检不放宽**：`RagStartupSelfCheck` 检出主数据源角色带 superuser/BYPASSRLS 仍拒启（`:88-97`）；
   自检的「无租户会话可见集合」口径改判为「只见 `scope='PLATFORM'`」，探针同步改（RAG-D11 §4 已给形状）。
3. **A 档不出正文**：全部租户盘点只回 `rag_source` / `rag_document` 的管理元数据与计数，
   **chunk 正文与向量在 A 档不可取**（`rag/docs/RAG-D11` §0.1 与《分离方案》§6「默认禁止」+ §10 禁区）。
   要看某租户正文，必须显式切到 T 档并指名租户——这条在控制器与 SQL 两层同时收口，不只写在注释里。
4. **T 档行为零变化**：现有 75 项语料反证（`scripts/rag-corpus-scope-counterproof.sh`）与字典反证 39 项
   必须**原样保持绿**；本片只新增断言，不放宽任何一条既有断言。
   > ⚠️ 2026-10-29 第二刀实测：这条红线当时**无法被验证**——两个脚本仍在扫 commit `826d77b` 删掉的
   > `db/migration/`（空目录），实跑第一步就 `die`。「75 项」是 2026-10-27 的数字，「39 项」也同批。
   > 已把两处重指向库结构权威源 `deploy/sql/rag/01_rag_ddl.sql` 后实测：**语料反证 102 项 / 0 失败**
   > （76 项既有 + 新增 P13 组 26 条）、**字典反证 39 项 / 0 失败**。数字以运行输出为准，见
   > [第二刀实测记录](RAG语料平台级治理_第二刀实测记录.md) §2、§4-1。
5. **不改检索面**（`rag-api`）：本片只动管理面。租户检索时「本租户 ∪ 全局」的既有可见性（RLS 读策略）不动。

### 2.3 A 档只读的取舍（需要用户确认的一处）

用户选了「全部租户盘点」。盘点要真有用就得跨租户聚合，但跨租户读语料正文直接撞 §2 禁区 3。
本片取**元数据盘点**（谁有多少来源/文档/待审/过期/问题行），正文与向量不下钻。
若需要「平台能读任意租户的语料正文」，那是独立的数据处理授权议题，须另立评审，不在本片默认实现内。

---

## 3. 批次拆分

### 第 1 刀：作用域读侧打通（**零 DDL**、纯代码 + 前端）

| 步 | 内容 | 落点 | 验收 |
|---|---|---|---|
| 1.1 | `RagJwtAuthFilter` 把「令牌域 + principalId」写入 request attribute（不再只进日志） | `rag-bootstrap/.../security/RagJwtAuthFilter.java:160-178` | 单元：平台域/租户域各一条断言；缺令牌仍 401 |
| 1.2 | 引入 `RagScope`（PLATFORM/TENANT/ALL）请求头解析 + 「`TENANT` 缺省、`ALL/PLATFORM` 需平台域身份」闸门；`RagTenantContext` 拆成 `RagManagementContext`（作用域 + 可空租户） | `RagManagementController.java:742`、`core/RagTenantContext.java:30-49` | 单元：租户域令牌声明 `PLATFORM` → 403；平台域声明 `TENANT` 不带租户 → 400 |
| 1.3 | 把 23 处显式等值 SELECT 的租户谓词改成**绑定参数**（`?::uuid`），G 档绑哨兵、T 档绑租户、A 档走 §1.3 的只读窗（本刀先只放 G） | `JdbcCorpusManagementRepository.java:184,…,847` | IT：同一份数据 G 档只见全局行、T 档只见本租户行，两者行数差 = 1（照 RAG-D11 §3 的钉法） |
| 1.4 | 前端三档切换：租户下拉拆成「作用域」分段控件 + 「租户」选择器（仅 T 档出现，`allowClear` 去掉，改为作用域驱动）；未选态文案按档位重写 | `RagManagementPage.tsx:39-189`、`ragClient.ts:62-99`、`navConfig.tsx:177` | 组件测试：G 档不发 `X-Tenant-Id`、A 档本轮置灰并说明「第 3 刀」 |
| 1.5 | **补测试**：`RagManagementPage` 目前零租户作用域断言 | `admin-console/tests/` | 全绿 + `--testTimeout=60000` 复跑判定（并发假坑见项目记忆） |

### 第 2 刀：全局语料可写（治理票据 + `rag_ops` + worker）

| 步 | 内容 | 落点 |
|---|---|---|
| 2.1 | `rag_ops` 角色 SQL + 授权 + 校验迁移（对齐 `03_platform_roles.sql:122-168`、`V__manage_platform_ops_role.sql:72-78`） | 新增 `deploy/sql/rag/05_rag_ops_role.sql` |
| 2.2 | 第二数据源 + guarded 会话窗 + 503 异常链 | `rag-bootstrap/.../RagOpsDataSourceConfiguration.java`、`rag-management/.../RagOpsSessionPort` 及其 Jdbc 实现 |
| 2.3 | 治理票据端点（权限闸门 + 平台审计 + 限流） | `platform/agent-api/.../api/platform/PlatformRagGovernanceTicket*` |
| 2.4 | G 档写路径参数化：5 处 `rag_current_tenant()` 写点（`:114,274+290,308+315,414+425,864`）改为按作用域绑定，并在事务入口加「G 档只准写哨兵桶」硬护栏 | `JdbcCorpusManagementRepository.java` |
| 2.5 | worker 全局桶重索引（撤 `ReindexRunner:100-107` 的跳过，改走 ops 窗；`ChunkIndexer:127` 的 `set_config` 换成不经过 `rag_current_tenant()` 的显式绑定） | `rag-worker/.../ReindexRunner.java`、`rag-infrastructure/.../ChunkIndexer.java` |
| 2.6 | 全局语料的准入判定沿用部署级门槛（R-4 未解，本片不装 solved） | `RagManagementController.java:89` |
| 2.7 | 前端 G 档放开「登记 / 校验 / 撤回 / 重索引」四组入口 + 票据内存态纪律 + 换作用域即作废 | `RagManagementPage.tsx`、`RagSourceTable.tsx:135`、`RagMaintenancePanel.tsx:143` |
| 2.8 | 反证脚本扩展：`P4/P5/P6` 三条既有断言保持（`rag_app` 仍写不出全局），新增 ops 角色可写 / 非票据调用被拒 / 票据过期被拒 | `scripts/rag-corpus-scope-counterproof.sh` |

> **实测交付与原表的三处偏差（2026-10-29 第二刀，见 [`第二刀实测记录`](RAG语料平台级治理_第二刀实测记录.md)）**：
> ① **2.2 落点不同**：运维数据源五件套在 `rag-infrastructure/.../infrastructure/ops/`
> （`RagOpsDataSourceProvider` / `GuardedRagOpsDataSource` / `RagOpsSessionGuard` / `RagOpsSessionPort` /
> `RagOpsUnavailableException`），不是计划写的 `rag-bootstrap/.../RagOpsDataSourceConfiguration.java`；
> `rag-bootstrap` 只承接启动自检。**2.8 的「非票据调用被拒 / 票据过期被拒」不在反证脚本里**——
> 脚本无法签发合法票据（票据由平台应用签、需平台身份 JWT），这两条钉在
> `PlatformRagGovernanceTicketServiceTest`（10）与 `RagManagementControllerTest`（57）；
> 脚本侧新增的是**库侧能力与代价**（P13 组 26 条：ops 写得进哨兵桶 / 删不动任何行 / 读得到全部租户私有行）。
> ③ 附带两条从未被写进计划、但实测必须钉的守卫：`scripts/check-rag-ops-role.sh`（真库只读巡检，退出码可挂 CI）
> 与「反证脚本改跑**权威源本身**」这次改造。

> ⚠️ **2.1 与 2.4 触碰共享库**（远程 RDS `agent_dev` / RAG 库；`rag_ops` 需 owner 执行）。
> 本刀开工前单独取得授权，执行方式沿用「owner 凭据 + 补丁 SQL + 巡检脚本退出码」的既有纪律。

### 第 3 刀：A 档跨租户盘点（只读）

| 步 | 内容 |
|---|---|
| 3.1 | A 档只读窗（护栏：窗口内仅 SELECT；SQL 不带 chunk 正文列） |
| 3.2 | 列表/统计按租户聚合，新增「所属租户」列与租户筛选器（复用 `getPlatformTenants()`） |
| 3.3 | 正文下钻在 A 档明确禁用，文案说清「要读正文请切到单租户档并指名租户」 |

### 第 4 刀：文档与文案回写

| 步 | 内容 |
|---|---|
| 4.1 | `RAG-D11` 的 R-1 / R-3 标闭环，§0.1「不交付」列改写；实测数字进 §6 证据索引 |
| 4.2 | `navConfig.tsx:177` 的「必须携带 X-Tenant-Id，故需显式选择租户」按三档重写 |
| 4.3 | Tool/Skill/MCP 与 Agent 两处「本就没有租户维度」的结论写入《松绑方案》§4 阻塞项（B2/B3 维持未解，另立项） |

---

## 4. 阻塞项与未决问题

| # | 项 | 状态 |
|---|---|---|
| B1 | **今天任何控制台账号都能改任意租户语料**（RAG 无权限校验，域信息丢失） | 本片 1.1/2.3 顺手关闭；**在 2.3 落地前，G/A 档不得上线** |
| B2 | 平台级 Agent 定义模型缺失（`cap_agent_def.tenant_id NOT NULL`） | **不在本片**，属《松绑方案》Q4 |
| B3 | 租户私有 Tool/Skill/MCP 模型缺失（表无 `tenant_id`） | **不在本片**，反向需求，需新基线 |
| B4 | R-2：全局语料挤占 HNSW `ef_search` 候选槽位，大租户召回率未测量 | 上线全局语料**之前**须按租户做召回基线对比（RAG-D08）；本片只打通通道，不替它背书 |
| B5 | R-4：全局行准入仍用部署级门槛 | 维持现状，文档如实标注 |
| Q1 | A 档是否允许下钻正文（§2.3） | 待用户确认；本片默认「否」 |

---

## 5. 验收标准

| # | 验收项 | 验证方式 |
|---|---|---|
| A1 | 不选租户（G 档）能列出平台全局语料，且**不含**任何租户私有行 | IT + 反证脚本 |
| A2 | 选租户（T 档）结果与改造前逐字节一致 | 既有 75+39 项反证保持绿 |
| A3 | 租户域令牌声明 `PLATFORM`/`ALL` → 403；平台域令牌无票据 → 503/401 | 单元 + IT |
| A4 | 全局语料能在控制台登记→校验→撤回→重索引，每步落平台审计且可回溯「谁、哪档、哪条」 | E2E + 审计断言 |
| A5 | `rag_app` 角色**仍然**写不出全局行（闸门未被本片打开） | 反证脚本新增断言 |
| A6 | ops 数据源未接入时 G/A 档 503 fail-closed，不回落应用角色、不伪装成空列表 | IT |
| A7 | 票据不落 `sessionStorage`/`localStorage`，换作用域即作废 | 前端单测断言 |

> **实测状态（2026-10-29 第二刀，数字来自运行输出）**：
> A1 ✅（IT `JdbcCorpusManagementRepositoryIT` 27/0 跳过 + 反证 P13）；
> A2 ✅ 但**验收口径要更正**：本表原先写的「75+39 项」测于 2026-10-27，那两个脚本自 `826d77b`
> 起已失效（扫空目录、第一步 `die`）；重指向权威源后实测**语料 102 项 / 字典 39 项，0 失败**；
> A3 ⚠️ **交付形态与预期码不同**：无票据的 G 档写是 **403**（`requireWriteContext`：票据声明档位 ≠ 请求档位），
> `503` 只用于 ops 通道未接入，`401` 只用于鉴权失败——本节按实现为准；
> A5 ✅（反证 P13-10/P13-26 + 巡检 ⑩⑪⑫）；A6 ✅（`RagOpsChannelTest` 7 + IT）；A7 ✅（`ragGovernanceTicket.test.tsx`）；
> **A4 ⚠️ 未达成**：审计能记录签发事件，但表上**没有票据档位/作用域列**，「谁、哪档」查不出来（第二刀 U-8）。
> 全文见 [第二刀实测记录](RAG语料平台级治理_第二刀实测记录.md) §2/§4/§5。

---

## 6. 一句话给评审者

这不是「把租户选择改成可选」，而是**把一条 DDL 里已经建好、代码里四处断掉的平台治理链补齐**：
读侧（第 1 刀）零 DDL 就能让全局语料在管理台被看见；
写侧（第 2 刀）必须新增一条有身份、有权限闸门、有审计、限长在一个入口上的运维通道，
而不能去放宽所有租户共用那个 `rag_app` 角色。

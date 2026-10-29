# RAG 语料平台级治理 · 第二刀实测记录

> 配套方案：[RAG语料平台级治理与租户三档作用域方案.md](RAG语料平台级治理与租户三档作用域方案.md)（第二刀 = 2.1～2.8）
> 前一刀：[RAG语料平台级治理_第一刀实测记录.md](RAG语料平台级治理_第一刀实测记录.md)
> 本刀范围：**平台全局语料（哨兵桶）在控制台可写** —— 库侧运维角色 + 第二数据源 + 治理票据 + G 档写路径 + worker 全局索引 + 前端写入口 + 反证扩展。
> 记录日期：2026-10-29 · 所有数字为本机/远程实测，非推算。

---

## 1. 结论

全局语料的写通道落地为**四段串起来的结构约束**，缺一段就写不进去，且没有任何一段是可以关掉的：

1. **库侧**：`rag_ops` 角色（NOSUPERUSER + **BYPASSRLS** + NOINHERIT、无任何成员关系、无 DELETE）。
   它是唯一能写哨兵桶的身份；`rag_app` 的读写策略**一条没改**（P13-10、P13-26、巡检 ⑩⑪⑫ 钉住）。
2. **连接侧**：运维数据源是 **guarded** 池——会话窗外取不到连接，所以「忘了开窗」的失败方式是异常，
   而不是静默写到别的桶；通道未接入时一律 `RagOpsUnavailableException` → **503**，绝不回落应用角色。
3. **调用侧**：平台档写动作必须带**治理票据**（`platform.resource.manage` → 开关 → 档位 → 限流四道闸门签出的
   短 TTL 空 roles JWT）；票据只在内存、只随写请求出发。
4. **UI 侧**：G 档四个写入口收敛在票据之后；无票据不给写入口，而不是「点了再报 403」。

风险姿态沿用既定决策（**默认开 + 常驻补偿控制**，不加特性开关）：补偿控制是上述四处结构约束 +
13 项带外巡检 + 102 条反证断言，不是一个环境变量。

---

## 2. 实测数字（本刀交付即测得，2026-10-29）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| rag 后端全量单测 | `cd rag && mvn -o test` | **BUILD SUCCESS · 318 tests / 0 失败 / 0 错误 / 0 跳过**（第一刀 284 → +34） |
| └ 分模块 | 同上 reactor | rag-core **50** / rag-ports **2** / rag-application **47** / rag-infrastructure **50** / rag-worker **3** / rag-api **6** / rag-management **126** / rag-bootstrap **34** |
| rag-management IT（远程 RDS `study_rag`，给 admin + ops 凭据） | `mvn -o -pl rag-management verify` | **45 IT / 0 失败 / 0 跳过**：`JdbcCorpusManagementRepositoryIT` **27**、`JdbcDictionaryRepositoryIT` **13**、`JdbcCorpusManagementDailyStatisticsIT` **5** |
| └ G 档写路径真库实测 | 同上 | `JdbcCorpusManagementRepositoryIT` 第一刀 19 → **27**（+8：ops 写进哨兵桶、租户读得到、`rag_app` 照样写不进、通道缺失 503） |
| 运维通道单测 | `cd rag && mvn -o test` | `RagOpsChannelTest` **7**、`ChunkIndexerGlobalScopeTest` **6**、`ReindexRunnerRoutingTest` **3**、`RagStartupSelfCheckOpsRoleTest` **7** |
| 管理闸门单测 | 同上 | `RagManagementControllerTest` **57**（第一刀 46 → +11：票据 + 档位 + 哨兵护栏） |
| 平台票据签发单测 | `cd platform && mvn -o -pl agent-api -am test -Dtest=PlatformRagGovernanceTicketServiceTest` | **10 / 0 失败**（⚠️ 必须带 `-am`，见 §4-6）（U-8 补列后为 **11**，见 §7.3） |
| 前端类型检查 | `cd admin-console && npx tsc --noEmit` | **clean（exit 0）** |
| 前端全量 | `npx vitest run --testTimeout=60000` | **34 文件 / 379 tests 全绿**（第一刀 33/360） |
| 库结构反证（含 P13 运维通道组） | `./scripts/rag-corpus-scope-counterproof.sh` | **102 项通过 / 0 失败**（P13 组 26 条；第一刀记录里的是 76 项） |
| 字典反证（修好之后） | `./scripts/rag-dict-scope-counterproof.sh` | **39 项通过 / 0 失败** |
| 运维通道带外巡检（远程 RDS，连接身份 `rag_ops`） | `./scripts/check-rag-ops-role.sh` | 修复前 **13 项 / 1 违规 / exit 1**（⑬ = 真库上那个坏 `search_path`，§5 U-6）→ 重跑 05 后 **13 项 / 0 违规 / exit 0** |
| 修复后回归（同一台远程 RDS） | `mvn -o -pl rag-management verify`（给 admin + ops 凭据） | **45 IT / 0 失败 / 0 跳过**（重授权没有动坏应用侧通道） |

---

## 3. 已修（逐条对账方案 2.1～2.8）

**2.1 库侧运维角色**
- [deploy/sql/rag/05_rag_ops_role.sql](../../deploy/sql/rag/05_rag_ops_role.sql) —— `REVOKE ALL` 后逐条 `GRANT`（幂等，可重复达成最小授权）；
  读 = rag 全库 11 张、写 = **恰好 6 张**、删 = **0 张**；角色级 `statement_timeout=30s`、
  `idle_in_transaction_session_timeout=15s`、`search_path`；**刻意不设** `app.tenant_id` 角色默认
  （连接池会把上一个持有者的作用域伪装成当前请求的）。
- [deploy/sql/rag/03_rag_roles.sql](../../deploy/sql/rag/03_rag_roles.sql) —— 与主数据源角色的差异只在写面，其余口径一致。

**2.2 第二数据源 + guarded 会话窗 + 503 异常链**
- [rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsDataSourceProvider.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsDataSourceProvider.java) —— 独立池，自带 `SET search_path TO rag, public`（运行期因此不受角色默认值那个坑影响）。
- [GuardedRagOpsDataSource.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/GuardedRagOpsDataSource.java) / [RagOpsSessionGuard.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsSessionGuard.java) / [RagOpsSessionPort.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsSessionPort.java) —— 窗口外 `getConnection` 直接抛；会话窗把「连接 + 事务 + 不设置任何租户」绑成一个动作交付给写路径。
- [RagOpsUnavailableException.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsUnavailableException.java) + [RagManagementExceptionHandler.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementExceptionHandler.java) —— 通道缺失呈现 **503**，不回落 `rag_app`（回落的结果是「扫到行、写不进去、报告 0 行成功」，比失败更难发现）。
- [RagStartupSelfCheck.java](../../rag/rag-bootstrap/src/main/java/cn/studykid/rag/bootstrap/RagStartupSelfCheck.java) —— 启动自检**不放宽**：配了 ops URL 却没配齐身份/角色体检就不放行。

**2.3 治理票据签发端点**
- [platform/agent-api/src/main/java/cn/studykid/multiagent/api/platform/PlatformRagGovernanceTicketController.java](../../platform/agent-api/src/main/java/cn/studykid/multiagent/api/platform/PlatformRagGovernanceTicketController.java) / [PlatformRagGovernanceTicketService.java](../../platform/agent-api/src/main/java/cn/studykid/multiagent/api/platform/PlatformRagGovernanceTicketService.java) —— 四道闸门顺序：权限 `platform.resource.manage` → 档位开关 → 作用域合法性 → 限流；签出的 JWT **roles 为空**（票据不是身份，是这一次写动作的授权凭证），TTL 60–900s（默认 300）。
- [PlatformJwtTokenService.java](../../platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/PlatformJwtTokenService.java) —— `issueRagGovernanceTicket(...)` 携带 `rag_scope` 声明；[RateLimitPort.java](../../platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence/RateLimitPort.java) 承接限流；每次签发进平台审计。

**2.4 G 档写路径参数化 + 哨兵桶硬护栏**
- [JdbcCorpusManagementRepository.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java) —— `WriteChannel.ofTenant/ofGlobal` 两条通道：租户档 `tenant_id = rag_current_tenant()` + 会话租户；平台档 `tenant_id = rag_global_tenant_id()`、**open() 什么都不做**（把哨兵写进会话变量会撞 V8 那条 RAISE 42501，是承重设计）；`writeVia` 统一负责事务与会话窗，ALL 档写动作直接抛。
- [RagManagementController.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java) —— `requireWriteContext`：身份域不够格 `403` / 无 `rag_scope` 声明 `403` / 档位不符 `403` / 平台档带 `X-Tenant-Id` `400`。
- [CorpusManagementPort.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/ports/CorpusManagementPort.java) —— 端口签名带上下文，写站点结构性拿不到「裸租户字符串」。

**2.5 worker 全局桶重索引走 ops 窗**
- [ChunkIndexer.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/indexing/ChunkIndexer.java) —— `indexGlobalCorpus(limit)` **不接收任何租户参数**，整段（扫描 + 落库）包在同一个会话窗里；分页逻辑只有一份，差异全压在 `ScanChannel` 的三个字段上。
- [JdbcCorpusRepository.java](../../rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java) —— `upsertEmbeddingInGlobalBucket(...)`：租户是 SQL 文本里的 `rag_global_tenant_id()`、作用域写死 `'PLATFORM'`，**没有可替换的位置**。
- [ReindexRunner.java](../../rag/rag-worker/src/main/java/cn/studykid/rag/worker/ReindexRunner.java) / [ChunkIndexerIndexingAdapter.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/ChunkIndexerIndexingAdapter.java) —— 按作用域路由到两条通道，limit 语义在两处保持一致。

**2.6 准入判定如实标注**（提交 `b7e72a9`）—— 「门槛/水位」是**逐租户**概念，全局档没有它；
javadoc 改为陈述这个事实，而不是留一句看着像承诺的话。

**2.7 前端 G 档写入口 + 票据内存态纪律**
- [admin-console/src/api/ragTicket.ts](../../admin-console/src/api/ragTicket.ts) / [useRagGovernanceTicket.ts](../../admin-console/src/features/rag/useRagGovernanceTicket.ts) —— 票据只在内存、不落 storage、不打印；每次写现签或复用未过期的一张；卸载即丢。
- [ragClient.ts](../../admin-console/src/api/ragClient.ts) —— 只**再导出票据类型**，不导出签发函数，避免第二处持有者；读列表的 effect 依赖 `readKey` 而不是票据身份（否则签一张票就多一次全量读）。
- [RagSourceTable.tsx](../../admin-console/src/pages/rag/RagSourceTable.tsx) / [RagSourceDrawer.tsx](../../admin-console/src/pages/rag/RagSourceDrawer.tsx) / [RagMaintenancePanel.tsx](../../admin-console/src/pages/rag/RagMaintenancePanel.tsx) / [RagManagementPage.tsx](../../admin-console/src/pages/rag/RagManagementPage.tsx) —— 无票据不给写入口，并写明「平台全局语料的写需要治理票据」。

**2.8 反证脚本扩展**
- [scripts/rag-corpus-scope-counterproof.sh](../../scripts/rag-corpus-scope-counterproof.sh) —— 新增 **P13 组 26 条**（E 类）：通道身份自证 → 真写得进哨兵桶 → 改得动全局片段且**租户在应用上下文读得到**（闭环）→ 原闸门一处没动 → 授权面比 `rag_app` 还窄 → BYPASSRLS 的代价如实量出。
  ⚠️ 两处结构性改造：① 库结构从「扫已删除的迁移目录」改为**应用权威源** `deploy/sql/rag/01_rag_ddl.sql`；
  ② ops 角色**直接执行发布脚本 `05_rag_ops_role.sql`**，不照抄授权清单——照抄的版本会跟 05 漂移，
  于是 P13 测的是「我以为的形状」，而生产上的 `rag_ops` 是另一个形状。
- [scripts/check-rag-ops-role.sh](../../scripts/check-rag-ops-role.sh) + [scripts/tools/CheckRagOpsRole.java](../../scripts/tools/CheckRagOpsRole.java)（新）—— 真库、**只读**、13 项、退出码 0/1/2。分工明确：能力面形状 = 本巡检；**写动作与代价** = P13 组；**票据闸门** = Java/前端单测。三者互不冒充。

**远程开发库修复（原 U-6；用户 2026-10-29 授权后执行，写的是共享 RDS）**
- 落点确认（先只读预检再动手）：`current_database() = study_rag`、连接身份 = 库 owner `ai_agent`、PG 18.6。
  ⚠️ 只碰 `study_rag`，`agent_dev` / `growth_planet` 未涉及，也没有任何 `DROP`。
- 修复前 `pg_roles.rolconfig`：`{statement_timeout=30s, idle_in_transaction_session_timeout=15s, "search_path=\"rag, public\""}`
  —— 那个引号包整串就是 §4-5 的缺陷本体。
- 执行：`psql … -v ON_ERROR_STOP=1 -v rag_ops_role=rag_ops -f deploy/sql/rag/05_rag_ops_role.sql`
  （**不提供 `rag_ops_password`** → 05 走「跳过口令设置」分支，既有凭据不动）。
- 修复后：`rolconfig` = `… "search_path=rag, public"`（列表语法）；
  授权面逐条打印 = 11 张 SELECT / 6 张可写 / **无一行 DELETE**；`pg_policies` 的 8 条写类策略 `with_check` 仍含 `scope = 'TENANT'`；
  巡检 **13 项 / 0 违规 / exit 0**；应用侧回归 `mvn -o -pl rag-management verify` = **45 IT / 0 跳过**。

---

## 4. 证伪（本刀推翻的 6 条，全部有实测输出）

1. **「第一刀说的既有反证全部保持绿色」——那句话当时并未实测。**
   真实状态：`826d77b`（2026-10-27 23:05，「库结构收编为单一权威源，删除 Flyway 迁移链」）删掉 42 个 `V*.sql` 之后，
   `rag-dict-scope-counterproof.sh` 与 `rag-v7-to-v8-retrofit-check.sh` 仍在扫
   `rag/rag-infrastructure/src/main/resources/db/migration/`——那目录现在是**空的**。
   实跑输出：`cat: .../V[0-9]*__*.sql: No such file or directory` → 第一步 `die`。
   → 字典反证从 10-27 起**一条都没测过**（本刀已修，39/0；第一刀记录 §1.5 同步更正）。
   同批扫空的第三个 `rag-v7-to-v8-retrofit-check.sh` **没有可修的对象**（它测的正是被删掉的那条迁移路径），
   已于同日定案退役，见 §5 U-7。
2. **「运维通道该有 `rag_chunk` 的 INSERT 权」——探针的假设错了，05 是对的。**
   代码里全局写路径从不插片段：片段由 worker 产生，全局索引只 `UPDATE rag_chunk` + `INSERT rag_chunk_embedding`。
   首跑 4 条失败正是这个假设造成的 → 判定「测的是想象，不是代码」，把 P13-7 改写为
   **「ops 对 rag_chunk 只有 UPDATE，插不出新片段」**并断言被权限拒。
   这条差异现在同时是证据：授权面贴着代码，且「治理通道凭空造全局片段」不成立。
3. **「BYPASSRLS 绕得过库内 CHECK」这件事，上一版根本没测到。**
   拿 ops **没有** INSERT 权的表去试 CHECK，会先撞 `permission denied`，走不到约束——
   红/绿都在，但证明的是别的事。改到 `rag_source`（ops 有 INSERT）后才看到
   `violates check constraint "rag_source_scope_ck"`（P13-12）。CHECK 不受 BYPASSRLS 影响，这条现在才真成立。
4. **`information_schema.role_table_grants` 不能用来量「别人」的授权面。**
   实测：超级用户查别人的 grantee **有行**（05 的 §3.2 就靠它打印证据）；非超级用户查别人的 grantee **0 行**；
   同一库同一角色，`has_table_privilege` 给 **11**。用前者会得出「授权面比设计更窄」的假绿。
   → P13-19~22 与巡检全部改用 `has_table_privilege`（按角色名判定，与谁来问无关）。
5. **「角色级默认值用 `%L` 转义更安全」——`search_path` 是列表型 GUC，整串引号会静默变坏。**
   `ALTER ROLE x SET search_path = 'rag, public'`（`format('%L')` 的产物）把它存成**一个含逗号的标识符**：
   `SHOW search_path` → `"rag, public"`、`to_regclass('rag_source')` → **NULL**、**没有任何报错**。
   首跑 10 条失败全是这个原因（`relation "rag_chunk" does not exist`）。
   05 已改 `= rag, public`（列表语法）并留注释；运行期不受影响（运维池自带 `SET search_path`），
   **受害的是带外 psql 运维**——而这正是运维通道的主要使用方式。
6. **「巡检 12 项 0 违规 = 这条通道健康」——测不到的东西不会变绿，只会缺席。**
   加上 ⑬（未限定名在运维会话里必须解析得到）之后，同一台远程 RDS 立刻 **1 违规 / exit 1**：
   那里的 `rag_ops` 角色还带着修复前那个坏 `search_path`。→ 已在同日经授权修复（§3 末 + §5 U-6 关闭）。
   另附一条跑法陷阱：`mvn -o -pl agent-api test`（不带 `-am`）会解析本地仓库里的**旧 SNAPSHOT jar**，
   报 `NoSuchMethodError: PlatformJwtTokenService.issueRagGovernanceTicket(...)` ——看着像产品缺陷，
   实际是 5 个用例全红的构建假象；带 `-am` 复跑 **10/0 全绿**。（与 rag 那条「跑 IT 勿加 `-am`」相反，
   因为 rag 加 `-am` 会被 §5 U-1 的检索 IT 拖成 BUILD FAILURE。）

---

## 5. 未解决

| # | 事项 | 实测状态 | 归属 |
| --- | --- | --- | --- |
| U-1 | `RetrievalRepositoryIT` 既有失败（第一刀 U-1，本刀复现） | **15 / 15 errors：`expected 4096 dimensions, not 1024`** —— 夹具仍 1024 维，列是 `halfvec(4096)`。与本刀无关，但 `-am` 的 reactor `verify` 会被它拖红。 | 独立收口 |
| ~~U-6~~ **已关闭** | ~~共享远程 RDS 上的 `rag_ops` 仍带坏 `search_path`~~ | 2026-10-29 经用户授权后由库 owner 重跑 `05_rag_ops_role.sql`（`study_rag`，幂等、不动口令）修复：巡检 **13/0 · exit 0**、应用侧 IT **45/0 跳过**。前后 `rolconfig` 与授权面打印见 §3 末「远程开发库修复」。 | 已完成 |
| ~~U-7~~ **已关闭（定案退役）** | ~~`rag-v7-to-v8-retrofit-check.sh` 仍指向已删除的迁移目录~~ | 2026-10-29 用户定案「退役 + 教训写入注释」，`git rm` 执行。**删前先只读实测确认被测对象真的不存在**（不是靠推断）：远程 `study_rag` 的 `rag.*.tenant_id` **可空列数 = 0**、`scope` 列 8 张、全局字典 4 行在哨兵桶、`information_schema.tables` 里**已无 `flyway_schema_history`** → 「V7 的 NULL 表示法库」在这条链上不可能再出现。教训去向：① 升级路径纪律 → `deploy/sql/rag/01_rag_ddl.sql` 头注释【存量库改动纪律】段；② 同类存量库迁移的执行纪律 → `04_migrate_embedding_halfvec_4096.sql` 头注释；③ RLS 静默 0 行 / NULL 上下文两条 → 已在 `rag-dict-scope-counterproof.sh`（`super_val` 注释 + P7-1）与 `JdbcTenantScopedGeneration` 类注释里，**逐条 grep 核对过确实还在**才写进注释。 | 已完成 |
| ~~U-8~~ **已关闭（含远程）** | ~~平台审计表没有**票据档位**列~~ | 2026-10-29 落列 + CHECK + 回填 + 读写链贯通，A4「谁、哪档」从推断变成查询。全过程与实测数字见 **§7**。共享远程 RDS `agent_dev` 的 patch 已按「只读预检 → dry-run → 授权后 COMMIT」执行完毕，远程复查数字见 **§7.7**。 | 已完成 |
| U-9 | ALL（跨租户盘点）档 | 仍 `501` + 前端置灰；能否读**语料正文**未定案（默认只元数据）。 | 第三刀 |
| U-10 | 票据四道闸门**反证脚本测不到** | 脚本无法签发合法票据（票据由平台应用签、需平台身份 JWT）。钉在三处：`PlatformRagGovernanceTicketServiceTest` 10、`RagManagementControllerTest` 57、`admin-console/tests/ragGovernanceTicket.test.tsx`。这是**如实边界**，不是遗漏。 | 记录 |

---

## 6. 复现配方

```bash
# ① 库结构 + 运维通道 + 全局/租户双作用域的反证（本地 docker 容器，自建自删临时库）
docker ps --format '{{.Names}}' | grep -q agent-pg17 || ./scripts/dev-postgres.sh start
./scripts/rag-corpus-scope-counterproof.sh      # 期望：通过 102 项 / 失败 0 项
./scripts/rag-dict-scope-counterproof.sh        # 期望：通过 39 项 / 失败 0 项

# ② 后端单测（无需 DB）
cd rag && mvn -o test                            # 318 / 0 失败
cd ../platform && mvn -o -pl agent-api -am test -Dtest=PlatformRagGovernanceTicketServiceTest   # 10 / 0（-am 必须）

# ③ 后端 IT（远程 RDS；G 档用例需要 admin + ops 两套凭据，勿加 -am，见 U-1）
cd ../rag
set -a; source ../rag.env; source ../platform.env; set +a
export IT_RAG_PG_URL="$RAG_PG_URL" IT_RAG_PG_USERNAME="$RAG_PG_USERNAME" IT_RAG_PG_PASSWORD="$RAG_PG_PASSWORD"
export IT_RAG_PG_ADMIN_URL="$RAG_PG_URL" IT_RAG_PG_ADMIN_USERNAME="$SPRING_DATASOURCE_USERNAME" IT_RAG_PG_ADMIN_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
export IT_RAG_OPS_URL="$RAG_OPS_DATASOURCE_URL" IT_RAG_OPS_USERNAME="$RAG_OPS_DATASOURCE_USERNAME" IT_RAG_OPS_PASSWORD="$RAG_OPS_DATASOURCE_PASSWORD"
mvn -o -pl rag-management verify                 # 126 单测 + 45 IT / 0 跳过

# ④ 运维通道带外巡检（连接身份 = rag_ops 本身；只读；口令只走环境变量，不打印）
cd .. && ./scripts/check-rag-ops-role.sh         # 期望：13 项 / 0 违规 / exit 0（U-6 已修复）

# ④b 远程库的角色修复通道（仅在巡检报 ⑬/③/④/⑤ 漂移时使用；⚠️ 写共享库，需用户授权）
set -a; source rag.env; source platform.env; set +a
export PGPASSWORD="$SPRING_DATASOURCE_PASSWORD"                 # 只进环境变量，不进命令行
/opt/homebrew/opt/libpq/bin/psql "${RAG_PG_URL#jdbc:}" -U "$SPRING_DATASOURCE_USERNAME" \
    -v ON_ERROR_STOP=1 -v rag_ops_role=rag_ops -f deploy/sql/rag/05_rag_ops_role.sql
unset PGPASSWORD

# ⑤ 前端
cd admin-console && npx tsc --noEmit && npx vitest run --testTimeout=60000   # 34 文件 / 379 全绿
```

> ⚠️ `check-rag-ops-role.sh` 的退出码是**给 CI 用的**：它连的身份必须是运维角色本身，
> 换身份（例如用 owner）测的就是别人，13 条断言全部失真。
> ⚠️ 反证脚本全程 `docker exec`，**不接受外部 PG 连接串**——临时库必须能自建自删，共享实例没这个前提。

---

## 7. U-8 补记（同日追加）：平台审计加「票据档位」列

方案 §5 的 **A4** 原话是「回溯谁、哪档」，第二刀交付时只做到了「谁」——
`platform_audit_event` 里没有档位的落点，「哪档」得从 `reason_code` 反推。
本轮之前可签档位唯一（`PLATFORM`），所以那一档**恰好没有丢**；
但第三刀放开 `ALL` 之后，同一张表里会同时出现两种档位的签发与拒绝，
**推断出来的审计不能作为追责证据**，所以补列。

### 7.1 形状

| 落点 | 内容 |
| --- | --- |
| [deploy/sql/platform/01_platform_ddl.sql](../../deploy/sql/platform/01_platform_ddl.sql) | `ticket_scope varchar(32)` 可空 + 内联 `CHECK (IS NULL OR ∈ {PLATFORM,TENANT,ALL})` + 列注释（值域＝RAG 侧 `RagCorpusScope` 三档，档位与作用域是同一根轴，**只需一列**） |
| [deploy/sql/platform/patch_audit_ticket_scope.sql](../../deploy/sql/platform/patch_audit_ticket_scope.sql) | 存量库通道，按 U-7 立下的纪律 **先加可空列 → 回填 → 最后加 CHECK**；幂等；只回填**能从已存数据证明**的行 |
| `PlatformAuditPort.PlatformAuditEvent` | 末位加 `@Nullable String ticketScope`（组件顺序＝列顺序，见该 record javadoc） |
| `JdbcPlatformAuditRepository` | INSERT 列清单 + 第 12 个占位符、SELECT 列表 + `mapEvent` 读回 |
| `PlatformRagGovernanceTicketService` | 5 个留痕点全部带上请求档位；`AUDITABLE_SCOPES` 规范集 + `auditableScope()` 折成「规范值或 NULL」 |
| `PlatformAuditController.AuditSummary` | 暴露 `ticketScope`（**不加过滤参数**：按 `action` 收窄已够用） |
| `admin-console` | `PlatformAuditEvent.ticketScope` + 审计页「票据档位」列 + Drawer 行 |

### 7.2 两个不是风格问题的取舍

- **⚠️ 档位必须先过规范集再入库**。被拒请求里的 `scope` 是**调用方可控文本**
  （闸门 2「通道开关」排在闸门 3「档位合法性」**之前**），而列上有 CHECK。
  直接写原文的后果不是脏数据而是**审计写入抛 SQLException**——
  一次 `?scope=<随意编>` 的取票请求会把 503 变成 500，**闸门自己制造故障**。
  所以规范集之外一律 `NULL`，而「档位不可知」本身就是可查的事实。
  钉在 `PlatformRagGovernanceTicketServiceTest` 新增的第 11 个用例上。
- **回填不过度推断**。`CHANNEL_DISABLED` 的行**留 NULL**：闸门 2 在档位校验之前，
  历史行里调用方到底要哪档确实无从证明。这一条不是靠注释自证的——
  反证脚本第 3 组断言就是「该行档位仍必须是 NULL」，谁将来把它「补齐」都会红。

### 7.3 实测（2026-10-29）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 结构等价性反证（新建） | `./scripts/platform-audit-ticket-scope-retrofit-check.sh` | **exit 0**：`pg_dump --schema-only -n platform` 两侧各 **994 行完全一致（零漂移）**；回填分布 **5/5 ✓**（ALLOW→PLATFORM、`SCOPE_NOT_MINTABLE`→ALL、`RATE_LIMITED`→PLATFORM、`CHANNEL_DISABLED`→NULL、对照组 `TENANT_SUSPEND`→NULL）；CHECK 在**两条路径**上都拒非规范值、放行 `TENANT` 与 `NULL`；patch **重跑第二遍通过且 NULL 行数不变**（幂等） |
| 平台审计往返 IT（新建） | `mvn -o -pl agent-infrastructure -am verify -Dit.test=PlatformAuditTicketScopeIT`（`IT_PG_URL` 指本机 `agent_it`，身份 `app_agent`） | **6 / 0 失败 / 0 跳过**：全字段逐一对账（防按位串列）、三档都可落、非票据行读回 NULL、CHECK 拒 `'platform'` 与带引号的注入文本、列与约束在 `pg_catalog` 里确实在位 |
| 票据闸门单测 | `mvn -o -pl agent-api -am test -Dtest=PlatformRagGovernanceTicketServiceTest` | **11 / 0 失败**（第二刀 10 → +1：垃圾 scope 不入列） |
| 平台侧全量单测 | `mvn -o -pl agent-api,agent-infrastructure -am test` | **agent-api 218 / agent-infrastructure 250 · 0 失败 0 错误 0 跳过 · BUILD SUCCESS** |
| 共享远程 RDS 打 patch | 预检 → dry-run → 授权后 `-f` 原文件 → 复查（**§7.7**） | **列数 0→1、约束数 0→1、12 行零改动**；dry-run 回滚后复查确认零痕迹 |
| 前端 | `npx tsc --noEmit` / `npx vitest run tests/p5Governance.test.tsx --testTimeout=60000` | **tsc clean** / **5 tests 全绿**（新增「票据档位单独成列，非票据行为空」） |

### 7.4 本刀新踩到的坑（都已写进文件注释）

1. **`COMMENT ON … IS` 只接受单个字符串字面量，不接受 `||` 拼接**。
   第一版把长注释写成多段 `||`，`psql -v ON_ERROR_STOP=1` 在空库复放快照时
   直接 `syntax error at or near "||"`（第 928 行）——**整个 platform 快照建不起库**。
   这是反证脚本第一次运行就撞出来的，不是读出来的。
2. **`pg_dump` 每次导出随机生成 `\restrict` 防护令牌**，不过滤的话结构差分**永远命中这两行**，
   真漂移会被埋掉；差分函数里过滤 + 内层 `|| true`（某些导出没有防护行时 grep 返回 1，
   别让 `pipefail` 误判成脚本失败）。
3. **`scripts/dev-it-db.sh` 的「结构已存在则跳过快照」是个幂等保护，代价是老 IT 库停在旧形状上**。
   快照加了列而老库没有 → 新 jar 的审计读写直接失败，现场长得像「代码改坏了」。
   已补 §2.5「无条件重放 `patch_audit_ticket_scope.sql`」，本机 `agent_it` 实测由旧形状升到新形状
   （这正是**存量库路径**，比空库复放更接近真实升级）。
4. **`dev-it-db.sh` 的授权循环原来只枚举 `schemaname='public'`** → 平台仓储的 IT 撞
   `permission denied for schema platform`。已扩到 `public platform` 两个 schema（表权限 + USAGE + 序列）。
5. 反证脚本回填断言第一版把 `action` 当成 `reason_code` 比对，对照组（`reason_code` 本就是 NULL 的
   `TENANT_SUSPEND`）报「无此行」——**谓词按行的来历选**，已改为显式 SQL 谓词表。
6. **取列注释是 `col_description(table_oid, attnum::int)`，不是 `shobj_description(relid,…)`**
   （后者签名不匹配，`ON_ERROR_STOP=1` 下当场中止整段）。远程复查 SQL 连撞两次才改对——
   教训：**共享库上的只读预检也要带 `ON_ERROR_STOP`，写错的语句会吞掉后面的断言**，
   我这次就是前三条已经打印、第四条报错，容易看成「检查通过了」。
7. **`pg_dump` 的随机 `\restrict` 令牌只在差分里有影响**；远程复查用的是 `pg_attribute` 目录视图，
   不经 `pg_dump`，所以 §7.7 的列序是**从目录直接读的**，比 dump 文本更接近权威源。

### 7.5 部署耦合（必须如实声明，不是「以后再补」）

新 jar 的 INSERT/SELECT 都点 `ticket_scope`。
**存量库若没先打 patch，平台审计的写入与读取会直接失败**（不是降级、是报错）。
顺序因此是硬性的：**先打 patch → 再启动新版本**。
本机 `agent_it` 与**共享远程 RDS `agent_dev` 均已执行**（远程过程与复查数字见 §7.7）；
patch 头【执行】段保留了这条通道的用法：`-v ON_ERROR_STOP=1 -f` 原文件即真提交，
把末尾 `COMMIT` 换成 `ROLLBACK` 即 dry-run（预检/试跑/提交三步都要留痕，别只留最后一步）。

### 7.7 共享远程 RDS 的执行过程（2026-10-29，三段式）

远程写库的纪律是「**只读预检 → dry-run → 用户授权后才 COMMIT**」，三步各有独立的证据，
任何一步和预测不符就停下来，而不是靠 patch 幂等兜底。身份始终是**库 owner `ai_agent`**
（`information_schema` 里对 `platform.platform_audit_event` 的权限实测 = owner），
口令只进 `PGPASSWORD` 环境变量、不落文件、不回显。

| 步 | 动作 | 实测 |
| --- | --- | --- |
| ① 只读预检 | 连 `agent_dev`（**PG 18.6**）查形状 / 约束 / 触发器 / 行数 / 持锁 | **11 列且 `has_ticket_scope_col=0`**；7 条既有约束（不含目标 ck）；**触发器 0 / 规则 0**（平台审计表**没有** append-only 保护，「只追加」目前只靠应用层不提供改删端点）；表 48 kB、**12 行**；`action='platform.rag.governance_ticket'` = **0 行**（其余 12 行 = `platform.tenant.execution_ticket` 11 + `platform.tenant.create` 1）；无其他事务持锁 |
| ② dry-run | `sed 's/^COMMIT;$/ROLLBACK;/'` 后执行，再复查形状 | rc=0；`ALTER TABLE` / **`UPDATE 0` ×2** / `DO` / `COMMENT` 全部命中却**一行没改**——与预检「票据行 0 行」的预测逐字一致；自证 `档位分布 <NULL> = 12`（12 行对照组一条没被误填）；**回滚后复查：`ticket_scope` 列数=0、约束数=0、行数=12（零痕迹）** |
| ③ COMMIT（授权后） | 直接 `-f deploy/sql/platform/patch_audit_ticket_scope.sql` | rc=0；同一串 `ALTER TABLE / UPDATE 0 / UPDATE 0 / DO / COMMENT` + `COMMIT` |
| ④ 只读复查 | 独立会话重新查目录 | **列数=1**（`可空=YES`、`character varying(32)`）；**约束数=1**，`pg_get_constraintdef` 与快照里的定义**逐字一致**；**总行数=12 / 档位 NULL=12 / 非 NULL=0**（旧行未被动过）；**列序** `id,actor_id,…,occurred_at,ticket_scope` 与 Java INSERT 清单按位对齐（串列风险就地排除） |

⚠️ **这次远程执行只证明了「加列 + 加约束 + 不动存量行」**。②③ 里的两次 `UPDATE 0` 意味着
**回填映射在远程库上不可测**（远程压根没有治理票据行可回填）——那一半证据仍然只在本机
`platform-audit-ticket-scope-retrofit-check.sh` 的播种断言上（§7.3 的 5/5 ✓）。
这不是遗漏，是这台库的当前数据决定的**如实边界**；等远程出现第一条 `governance_ticket`
审计行后，A4 的追溯口径（`actor_id` + `ticket_scope` + `decision` + `occurred_at`）才在这台库上真正可用。

### 7.6 复现配方（补 §6）

```bash
# ⑥ U-8：存量库升级路径 ≡ 全新库快照（本机容器，自建自删两个一次性库）
./scripts/platform-audit-ticket-scope-retrofit-check.sh   # 期望：exit 0 / 零漂移 / 回填 5✓

# ⑥b 平台审计往返 IT（先刷新 IT 库，脚本内含 patch 重放与 platform 授权）
./scripts/dev-it-db.sh
cd platform && IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_it IT_PG_USER=app_agent \
  mvn -o -pl agent-infrastructure -am verify -Dit.test=PlatformAuditTicketScopeIT \
     -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false

# ⑥c 共享远程 RDS 的档位列 patch（⚠️ 写共享库：须先 §7.7 的只读预检 + dry-run，再经用户授权）
set -a; source platform.env; set +a
# 先 dry-run（末尾 COMMIT 换 ROLLBACK，跑完复查形状应回到列数 0）
sed 's/^COMMIT;$/ROLLBACK;/' deploy/sql/platform/patch_audit_ticket_scope.sql \
  | PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" /opt/homebrew/opt/libpq/bin/psql \
      "${SPRING_DATASOURCE_URL#jdbc:}" -U "$SPRING_DATASOURCE_USERNAME" -v ON_ERROR_STOP=1 -P pager=off -f -
# 授权后真提交（口令只进环境变量，不进命令行历史、不打印）
PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" /opt/homebrew/opt/libpq/bin/psql \
  "${SPRING_DATASOURCE_URL#jdbc:}" -U "$SPRING_DATASOURCE_USERNAME" \
  -v ON_ERROR_STOP=1 -P pager=off -f deploy/sql/platform/patch_audit_ticket_scope.sql
```

# RAG 语料平台级治理 · 第一刀实测记录

> 配套方案：[RAG语料平台级治理与租户三档作用域方案.md](RAG语料平台级治理与租户三档作用域方案.md)
> 本刀范围：**零 DDL**，只做代码 + 前端（方案 1.1～1.5）。全局行写通道属第二刀，跨租户盘点属第三刀。
> 记录日期：2026-10-29 · 所有数字为本机实测，非推算。

---

## 1. 结论

平台侧（admin-console）与租户侧的 RAG 语料**作用域改为显式声明**：请求带 `X-Rag-Scope`，
后端按「调用方身份域 + 作用域」双重闸门裁决，**不再从「有没有 `X-Tenant-Id`」反推作用域**。
第一刀交付 **TENANT（读写俱全）** 与 **PLATFORM（读）** 两档；ALL 档后端 `501`、前端置灰。

风险姿态沿用既定决策（**默认开 + 常驻补偿控制**，不加开关）：新链路一上来就在
`rag-management` 的每个管理端点上生效，补偿控制是四处闸门 + 两处 UI 置灰 + 逐条断言，
而不是一个可以被环境变量关掉的特性标志。

---

## 2. 实测数字（本刀交付即测得）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| rag 后端全量单测 | `cd rag && mvn -o test` | **BUILD SUCCESS · 284 tests / 0 失败 / 0 错误 / 0 跳过**（本刀前 273，+11） |
| rag-management 单测 | `mvn -o -pl rag-management verify` | **115**（本刀前 109）；其中 `RagManagementControllerTest` **46**（本刀前 35，+11 条闸门用例） |
| rag-core 作用域解析 | 同上 reactor | `RagCorpusScopeTest` **5** 全绿 |
| 作用域上下文契约 | 同上 | `RagManagementContextTest` **6** 全绿 |
| IT（远程 RDS `study_rag`，含管理通道） | `mvn -o -pl rag-management verify` + `IT_RAG_PG_*` + `IT_RAG_PG_ADMIN_*` | **37 IT / 0 失败 / 0 跳过**（含本刀新增 3 条平台档用例） |
| IT（**不给**管理通道） | 同上，仅 `IT_RAG_PG_*` | **30 跑 / 7 跳过**：`JdbcCorpusManagementRepositoryIT` 19 中跳 1、`JdbcDictionaryRepositoryIT` 13 中跳 6 → 守门是 skip 不是 fail，缺凭据不把 CI 变红 |
| 前端类型检查 | `cd admin-console && npx tsc --noEmit` | **clean（exit 0）** |
| 前端全量 | `npx vitest run --testTimeout=60000` | **33 文件 / 360 tests 全绿**（本刀前 31/347，+13） |

新增/改动的测试文件：

- [rag/rag-core/src/test/java/cn/studykid/rag/core/scope/RagCorpusScopeTest.java](../../rag/rag-core/src/test/java/cn/studykid/rag/core/scope/RagCorpusScopeTest.java) —— 头契约（字面量 `X-Rag-Scope`）、缺省即 TENANT、大小写/空格容忍、非法值报错**回显坏值**、`requiresPlatformCaller`。
- [rag/rag-management/src/test/java/cn/studykid/rag/management/core/RagManagementContextTest.java](../../rag/rag-management/src/test/java/cn/studykid/rag/management/core/RagManagementContextTest.java) —— 钉住**会话租户 ≠ 查询租户**这条不显然的分裂、平台档无写入口、scope↔tenant 一致性双向、ALL 档不带会话租户、空值拒绝。
- [rag/rag-management/src/test/java/cn/studykid/rag/management/api/RagManagementControllerTest.java](../../rag/rag-management/src/test/java/cn/studykid/rag/management/api/RagManagementControllerTest.java) —— 11 条闸门用例：坏作用域 `400`、租户身份读平台档 `403`、身份域缺失/空白/猜测值 `403`、平台档读**绑定哨兵并清空会话**（ArgumentCaptor 断言）、`PLATFORM` + `X-Tenant-Id` `400`、`ALL` `501`、写请求带非租户作用域 `409`、TENANT 档空白租户 `400`（平台身份也不例外）、TENANT 档会话==查询、字典读共享闸门、字典写拒绝非租户作用域。
- 三条新 IT：`platformScopeDoesNotSeeTenantRows`、`platformScopeStatisticsIgnoreTenantRows`、`globalRowIsVisibleOnlyInPlatformScope`（后者需管理通道 seed 一行全局语料，`finally` 只删自己那行 UUID）。
- [admin-console/tests/ragScope.test.ts](../../admin-console/tests/ragScope.test.ts)（7）与 [admin-console/tests/ragScopePage.test.tsx](../../admin-console/tests/ragScopePage.test.tsx)（6）—— 请求头实际发出什么、未就绪不发请求、非租户档不给写入口、Segmented 第三档 disabled 且带原因。

---

## 3. 已修（逐条对账方案）

### 1.1 令牌域下传

身份域从 JWT 受众推导，落到**不可伪造的 request attribute**，而不是让下游读 header：

- [rag/rag-core/src/main/java/cn/studykid/rag/core/security/RagCallerAttributes.java](../../rag/rag-core/src/main/java/cn/studykid/rag/core/security/RagCallerAttributes.java) —— `DOMAIN_PLATFORM / DOMAIN_TENANT / DOMAIN_UNVERIFIED` 与属性名。
- [rag/rag-bootstrap/src/main/java/cn/studykid/rag/bootstrap/security/RagJwtAuthFilter.java](../../rag/rag-bootstrap/src/main/java/cn/studykid/rag/bootstrap/security/RagJwtAuthFilter.java) —— `aud=admin-console-api` → 平台域；其余已验证令牌 → 租户域；无令牌/不透明 → UNVERIFIED。测试 22 条全绿。

**为什么必须是 attribute**：若身份域取自客户端可控的 header，租户只要自称平台就拿到全局档，闸门形同虚设。

### 1.2 作用域解析与闸门

- [rag/rag-core/src/main/java/cn/studykid/rag/core/scope/RagCorpusScope.java](../../rag/rag-core/src/main/java/cn/studykid/rag/core/scope/RagCorpusScope.java) —— `TENANT/PLATFORM/ALL`，空白按 TENANT，非法值抛错并回显。
- [rag/rag-management/src/main/java/cn/studykid/rag/management/core/RagManagementContext.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/core/RagManagementContext.java) —— `forTenant/forPlatform/forAll` 三个工厂各自校验不变式；`sessionTenantId()` 与 `queryTenantId()` 是两个值。
- [rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java) —— `requireManagementContext`（403 身份域不够格 / 400 缺参 / 501 盘点档）与 `requireTenantContext`（非租户作用域的写请求 409）。

### 1.3 读侧租户谓词参数化

- [rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java) —— **23 处** `tenant_id = ?::uuid` 显式谓词改为绑定 `queryTenantId()`；**写侧方法签名保持 `RagTenantContext` 未动**（结构性拿不到全局行）。
- [rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/RagSessionScope.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/RagSessionScope.java) —— 平台档**显式** `set_config('app.tenant_id','',true)` 清空会话（不是「跳过设置」：连接池残留与角色默认值会伪装成租户档）。
- [rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcDictionaryRepository.java](../../rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcDictionaryRepository.java) —— 字典读保留 `IN (rag_current_tenant(), rag_global_tenant_id())`：会话清空后 `rag_current_tenant()` 退化为 NULL，谓词只剩全局项，与 RLS 结论一致。

平台档因此是**双重保证**：RLS 放开 + 谓词独立成立，任何一处漏绑只可能是 0 行，不会串租户。

### 1.4 前端三档作用域切换

- [admin-console/src/api/ragClient.ts](../../admin-console/src/api/ragClient.ts) —— `RagTarget` 契约层：`tenantTarget()/PLATFORM_TARGET/isRagTargetReady()/isRagWriteTarget()`，`X-Rag-Scope` 恒定发出、仅 TENANT 档带 `X-Tenant-Id`。
- [admin-console/src/features/rag/useRagManagement.ts](../../admin-console/src/features/rag/useRagManagement.ts) —— 未就绪（TENANT 档没选租户）**不发请求**；写动作只在可写作用域放行。
- [admin-console/src/pages/rag/RagManagementPage.tsx](../../admin-console/src/pages/rag/RagManagementPage.tsx) —— `Segmented` 三档，第三档 `disabled` 且 `title` 写明原因；切档即清空上一档数据（治理结论不能张冠李戴）。
- [admin-console/src/pages/rag/RagSourceTable.tsx](../../admin-console/src/pages/rag/RagSourceTable.tsx) / [RagSourceDrawer.tsx](../../admin-console/src/pages/rag/RagSourceDrawer.tsx) / [RagMaintenancePanel.tsx](../../admin-console/src/pages/rag/RagMaintenancePanel.tsx) —— 平台档只读：三个写入口收敛在一个 `writable` 标志后，并给出「本档只读」说明。
- [admin-console/src/layout/navConfig.tsx](../../admin-console/src/layout/navConfig.tsx) —— 菜单描述同步作用域口径。

### 1.5 测试补齐 + 回归

见 §2。

> ⚠️ **2026-10-29 第二刀更正（本刀未实测，故原句不成立）**：本节原先写的是「既有反证断言全部保持绿色」，
> 但 `scripts/rag-corpus-scope-counterproof.sh` 与 `scripts/rag-dict-scope-counterproof.sh` 自
> commit `826d77b`（2026-10-27 23:05，删除 Flyway 迁移链、库结构收编为 `deploy/sql/rag/01_rag_ddl.sql`）起
> 仍在扫 `rag/rag-infrastructure/src/main/resources/db/migration/`——那目录已是**空的**，
> 两个脚本实跑都在第一步 `die`（`cat: .../V[0-9]*__*.sql: No such file or directory`）。
> 也就是说第一刀交付时**这两组反证一条都没跑过**，「保持绿色」是从旧记录继承的说法，不是测得的事实。
> 第二刀已把两处重指向权威源并实测：**语料反证 102 项 / 0 失败**、**字典反证 39 项 / 0 失败**；
> 详见 [RAG语料平台级治理_第二刀实测记录.md](RAG语料平台级治理_第二刀实测记录.md) §4-1。
> 教训：**「断言保持绿」必须有当场的运行输出**，脚本失效的形态是「第一步就死」，而不是「红了」。

---

## 4. 证伪（本刀推翻了 4 条先看似的直觉）

1. **「RLS 放开了，读侧不用改 SQL 就能看到全局行」——错。**
   RLS 只是第二道墙；仓库里 23 处 `tenant_id = ?` 是硬谓词。二者必须同向：
   实测中「策略放开、谓词没跟上」的表现是**静默 0 行**，不是报错。字典链反向同理——
   把谓词写成 `tenant_id = rag_current_tenant()` 等值会把全局行**静默滤掉**。
2. **「不选租户就是少发一个请求，前端可以留空」——错。**
   RAG 管理端点 fail-closed，缺 `X-Tenant-Id` 返回 400。所以作用域必须**显式声明**；
   从「有没有租户头」反推作用域会把「本地没填租户」伪装成「后端故障」，也会让
   平台档请求因为多带一个租户头而口径不明（现在直接 400）。
3. **「缺管理员凭据会让 IT 变红，所以要么内置凭据要么删用例」——错。**
   实测：缺 `IT_RAG_PG_ADMIN_*` 时 7 条用例 skip、其余 30 条照常真跑，`BUILD SUCCESS`。
   skip 而非 fail 是对的守门姿势，管理员通道用例因此可以留在库里。
4. **「owner 账号只连 `agent_dev`，RAG 的管理通道得另找」——错。**
   远程 RDS 是同一实例；`platform.env` 的 owner 账号同样能连 `study_rag`。
   因此 IT 的管理通道配方 = `IT_RAG_PG_ADMIN_URL` 取 `rag.env` 的库、
   `IT_RAG_PG_ADMIN_USERNAME/PASSWORD` 取 `platform.env` 的 owner（仅用于 seed/清理全局夹具，业务断言仍走 `rag_app`）。

---

## 5. 未解决

| # | 事项 | 实测状态 | 归属 |
| --- | --- | --- | --- |
| U-1 | `RetrievalRepositoryIT` 既有失败 | **15 errors：`expected 4096 dimensions, not 1024`** —— 该 IT 用 1024 维确定性向量夹具，而库内 `rag_chunk_embedding.embedding` 自 2026-10-28 迁移起是 `halfvec(4096)`（`RAG_EMBEDDING_DIMENSION=4096`）。与本刀无关，**但带 `-am` 的 reactor `verify` 会被它拖成 BUILD FAILURE**，故本刀 IT 用 `-pl rag-management` 单模块跑。修法：夹具向量维度对齐 4096，或改跑本地 pgvector 探针库。 | 独立收口 |
| U-2 | ALL（跨租户盘点）档 | 后端一律 `501`，前端第三档置灰。逐租户遍历 + 汇聚需要平台运维数据源。 | 第三刀 |
| U-3 | 平台全局语料的**写**通道（本轮要求） | 本刀只读。写需要 `rag_ops` 角色 DDL + 第二数据源 + 治理工单 + worker 全局重建索引。**涉及共享远程 RDS，需用户显式授权后才动。** | 第二刀 |
| U-4 | ALL 档能否读**语料正文** | 未定；当前默认只元数据。等 U-2 开工前定案。 | 待定 |
| U-5 | Tool/Skill/MCP + Agent 四块的同类改造 | 方案里判定「同为平台维护」，本刀只动 RAG。 | 第四刀（文档回写 + 后续实施） |

---

## 6. 复现配方

```bash
# 后端（全量单测，无需 DB）
cd rag && mvn -o test                                  # 284 / 0 失败

# 后端 IT（远程 RDS；给不给管理通道分别是 37/0 跳过 与 30/7 跳过）
cd rag
set -a; source ../rag.env; source ../platform.env; set +a
export IT_RAG_PG_URL="$RAG_PG_URL" IT_RAG_PG_USERNAME="$RAG_PG_USERNAME" IT_RAG_PG_PASSWORD="$RAG_PG_PASSWORD"
export IT_RAG_PG_ADMIN_URL="$RAG_PG_URL" IT_RAG_PG_ADMIN_USERNAME="$SPRING_DATASOURCE_USERNAME" IT_RAG_PG_ADMIN_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
mvn -o -pl rag-management verify                       # 115 单测 + 37 IT（勿加 -am，见 U-1）

# 前端
cd admin-console && npx tsc --noEmit && npx vitest run --testTimeout=60000   # 33 文件 / 360 全绿
```

> ⚠️ `--testTimeout=60000` 不是凑数：`.test.tsx` 与 `mvn` 并发跑时默认 5s 超时会**成片假失败**。
> ⚠️ IT 的管理通道只用于 seed/清理全局夹具；业务断言必须继续走非特权 `rag_app`，
>    否则 BYPASSRLS 会让租户隔离结论失效。

# 03 · 数据库权限与 RLS 类

> **判据**：根因在**库结构 / 角色授权 / 行级安全策略 / 迁移脚本 / 执行顺序**。
> 典型形态：授权漏了一张表、RLS 策略缺口、DDL 双源、脚本执行顺序颠倒导致授权被覆盖。
>
> **📌 后续归属说明**：RLS 正在迁往应用层（见 [docs/权限管控体系/12_RLS到应用层权限迁移方案.md](../docs/权限管控体系/12_RLS到应用层权限迁移方案.md)）。
> 本文档**继续收录迁移期间的新问题**（编号续接 DBP-18 起）；
> 但凡根因落在「应用层权限模型 / ScopeFilter / AccessGate / 翻译对照表」的，
> 登记时须标注 **[数据层迁移]** 标签，并注明所属阶段（P0–P5）。

---

## DBP-01 · `platform_ops` 对 `cap_config_publish_seq` 无 UPDATE 权 → `nextval()` 报 permission denied

**问题描述**
执行发布头补丁时：
```
ERROR: permission denied for sequence cap_config_publish_seq
```
实测 `platform_ops` 对该序列 `USAGE=t, SELECT=t, **UPDATE=f**`，而 `nextval()` **要推进序列**。

**产生原因**
`USAGE` 允许「读取当前值」和「调用 nextval」，但**推进序列需要 UPDATE 权限**。
授权脚本只授了 `USAGE` + `SELECT`（按「读序列」的直觉），没意识到 `nextval` 是写操作。

**相关解决方案**
授权侧修法（给 `platform_ops` 该序列的 UPDATE）**本次未做**，登记为未解决；
改由**库 owner** 执行补丁，并在 README / patch 头部都写明执行身份要求。

> ⚠️ **仍未闭环的一条**：这意味着**走应用侧平台发布通道**（`/api/cap/platform/*`）时同样会失败 ——
> 本轮只验了脚本通道，没验 API 通道。若平台运维角色真要走发布 API，需要单独授权。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §12.3
**状态**：⛔ 未解决（授权侧未做，改由 owner 执行）

---

## DBP-02 · `app_agent` 对 `platform` schema 整域失读 → 崩溃恢复扫描器每 30 秒报 ERROR

**问题描述**
`RunRecoverySweeper` 每 30 秒报一次 `ERROR: permission denied for schema platform`。
**崩溃恢复链路事实上失效** —— worker 崩溃后其持有的 RUNNING 租约过期，却无人接管
→ Run 永久卡死；而**系统表面完全正常**（API 正常、无告警）。

**产生原因**
根因**不是「权限给少了」，是「授权漏了一张表」**：
- `03_platform_roles.sql` 只授 `public` schema（应用角色的业务域）；
- `09_platform_schema_grants.sql` 只授 `platform_ops`（平台运维通道）；
- ⇒ 切 `app_agent`（2026-10-30）后，`app_agent` 对 `platform` schema **一张表都读不到**；
- 而 `platform.tenant` 是租户登记表（**无 RLS**，`relrowsecurity=f`），**本就该被应用角色读**。

**相关解决方案**
见 [DAT-13](02-数据一致性与写链.md#dat-13--切-app_agent-后-platform-schema-授权未补--平台域表整域失读)
（授权侧修复：新增 `11_platform_tenant_read_grant.sql`，只读 `platform.tenant` + `platform.tenant_quota`）。

> ⚠️ 附带的方法论：**故障现场极具误导性** —— 真因在 `GovernanceEndpointIT` 等类的池泄漏，
> 报错在 `ConfigEndpointIT` 的上下文启动；PG 把连接槽位**优先留给超管**，
> **泄漏者正是超管，被拒者却是普通角色**。

**来源**：`git log b31963f`（2026-11-01）
**状态**：✅ 已修

---

## DBP-03 · `cap_*` 4 表缺 DELETE 策略 → 默认拒绝下 DELETE 被完全禁止

**问题描述**
`cap_config_pointer` / `cap_config_publish_request` / `cap_config_release` / `cap_decision_log`
（`01_platform_ddl.sql:3892–4028`）**只有 SELECT/INSERT/UPDATE，缺 DELETE 策略**。
在 RLS 默认拒绝的语义下，DELETE 被完全禁止；
`cap_decision_log` 是最高频追加日志、只增不减，**只能靠 BYPASSRLS 清理**。

**产生原因**
建策略时按「这张表会被怎么用」逐条列了 3 个操作，漏了 DELETE —— 而 RLS 是**默认拒绝**的，
漏写 ≠ 放开，等于多了一道不在设计里的闸门。

**相关解决方案**
补 DELETE 策略（按各表实际语义：`cap_decision_log` 允许按租户删、`cap_config_pointer` 需 CAS 语义等）。
配套巡检：`check-cap-release-head.sh` / `check-tenant-registry.sh` 之类的**只读**脚本退出码约定。

**来源**：`docs/系统优化建议-全量评审.md` §4.3
**状态**：✅ 已修

---

## DBP-04 · DDL 双源分裂：05/07/08 完全不在 Flyway → 线上根本没有这些对象

**问题描述**
`05_platform_identity_ddl.sql`、`07_platform_dictionary_ddl.sql`、`08_platform_agent_metrics_ddl.sql`
**完全不在 Flyway V1–V29 中**，而 `deploy/sql/README.md` 要求生产走 Flyway
→ **线上根本没有这些对象**（指标表、`run.trace_id`、字典表），
`JdbcAgentMetricRepository` 会报 `relation does not exist`。

**产生原因**
「建 DDL」这件事有两条路（Flyway 迁移 vs `deploy/sql` 脚本），
前者有版本号和顺序保证，后者没有；两边都在被人用。

**相关解决方案**
新建 V30+ 收编（**逐字收编 + 修 05 枚举守卫真 bug**，在 `agent_dev` 做了两轮幂等实测）。

> ⚠️ 后续演进（本项目 2026-11-01 已进一步收敛）：
> 改 `01_*_ddl.sql` **终态快照** + 活库等价 ALTER + 同步种子，**不再新增增量 patch**；
> 初始化序列收敛为一条 `01 → 02 → 03 → 04`。

**来源**：`docs/系统优化建议-全量评审.md` §4.1
**状态**：✅ 已修（V31–V33 逐字收编）

---

## DBP-05 · 外键缺索引 → DELETE 父表全表扫子表

**问题描述**
PG **不自动给外键建索引**，DELETE 父表会全表扫子表：
- `budget_reservation(account_id)`、`usage_record(run_id)`、`approval(operation_id)`、
  `cap_approval(operation_id/run_id)`（`01_platform_ddl.sql:3592/3672/3448/3488/3496`）
- RAG：`rag_answer_citation(chunk_id)`、`rag_edition(source_id)`、`rag_ingest_job`（**零二级索引**）

**产生原因**
`CREATE TABLE` 时声明了外键，但没意识到 PG 不会自动建被引用列的索引。

**相关解决方案**
`CREATE INDEX CONCURRENTLY ...` 建（CONCURRENTLY 避免锁表，见 DBP-14）。

**来源**：`docs/系统优化建议-全量评审.md` §4.2
**状态**：✅ 已修

---

## DBP-06 · 角色授权顺序颠倒 → 应用角色全表无权限

**问题描述**
`ALTER DEFAULT PRIVILEGES` **只覆盖此后新建的对象**。
若在 DDL 之前建角色并设默认权限，之后建的表**不在默认权限范围内** → 应用角色全表无权限。

**产生原因**
把「角色」当成「一次性授予」而不是「对未来对象的持续承诺」，忽略了
`ALTER DEFAULT PRIVILEGES` 的**时间锚点**语义。

**相关解决方案**
固化初始化顺序（两库通用，来自 README/脚本内铁律）：
```
建库（owner）→ 01 DDL → 02 种子 → 03 角色授权 → 04（platform 为 retention；rag 为 ops）
```
**角色必须在 DDL 之后建。**
并新增 `scripts/init-db.sh` 作为初始化总入口（建库→DDL→种子→角色→保留期角色→自检），
一条命令跑完，顺序由脚本保证而非靠人记。

**来源**：`docs/数据库用户与权限梳理_2026-11-01.md` §三
**状态**：✅ 已固化

---

## DBP-07 · `03` 重跑静默覆盖 `04` 的 `REVOKE DELETE` → 凭据隔离被抹掉

**问题描述**
`audit_record` / `erasure_certificate` 已 `REVOKE DELETE`（04_retention_role.sql，凭据隔离，
防应用凭据删审计），但 **`03_platform_roles.sql` 重跑会重新 `GRANT` 全表权限**，
**静默覆盖**掉那条撤销。

**产生原因**
「授权脚本」与「撤权脚本」被当成两个独立的、各自幂等的东西，
但 `GRANT` 与 `REVOKE` 作用在**同一个对象**上，谁后跑谁赢 —— 顺序即语义。

**相关解决方案**
固化顺序：**04 必须排在 03 之后**。
`03` 重跑后必须重跑 `04`。这一条写进 README 与 `init-db.sh` 的执行序列。

> **可推广的结论**：本项目的授权纪律里，**顺序是有向的**，不是集合。
> `GRANT → REVOKE → 回填` 三者不能颠倒。

**来源**：`docs/数据库用户与权限梳理_2026-11-01.md` §1.3 / §三
**状态**：✅ 已固化

---

## DBP-08 · `rate_limit_bucket` 策略直接用 `current_setting` 而未走 `current_tenant_id()` → 空 GUC 报 500

**问题描述**
`rate_limit_bucket` 的 RLS 策略**直接用 `current_setting`**，没有走
`current_tenant_id()` 那套「空 GUC → 返回 NULL → 0 行（fail-closed）」的统一判据。
未设置会话 GUC 时直接抛错（500），而不是像其它表那样返回 0 行。

**产生原因**
该策略是早期手写的，与后来统一的租户上下文函数（`public.current_tenant_id()`）**没有对齐**。
RLS 判据一旦分叉，fail-closed 的行为就不一致。

**相关解决方案**
统一到 `current_tenant_id()`：空 GUC → 0 行（fail-closed 默认拒绝）。
重建前后对比：
| | 重建前 | 重建后 |
|---|---|---|
| RLS 判据 | `rate_limit_bucket` 手写版（空 GUC 报 500） | 统一 `current_tenant_id()`（空 GUC 0 行，fail-closed） |

**来源**：`docs/数据库规范化整理与远程重建实录_2026-11-01.md` §5
**状态**：✅ 已修

---

## DBP-09 · 审计「不可篡改」依赖会话自设 GUC → 可自封绕过

**问题描述**
审计追加即写由触发器 `forbid_audit_mutation()` 强制，但其放行 DELETE 的条件是
会话声明 `agent.retention.actor` GUC —— **DDL 注释自承「任何持有 DB 凭据的会话都可自行 set_config」**。
`erasure_certificate` 亦可用同通道删。

**产生原因**
把「不可篡改」建立在**调用方可以自己声明的身份**上。
GUC 是会话级变量，任何能连库的会话都能 `SET`。

**相关解决方案**
真正的防线改为**DB 凭据隔离**：
- 新增独立角色 `agent_retention`（`NOSUPERUSER` + `NOBYPASSRLS`），
  **唯一**拥有 `audit_record` / `erasure_certificate` 的 DELETE 权；
- 应用侧启用对应 `agent.retention.datasource.*`（`RetentionDataSourceConfiguration`）；
- 触发器改用 `pg_has_role` 判**会话的连接凭据成员身份**（会话无法自封），且声明 `agent.retention.actor` 仅作留痕；
- `erasure_certificate` 的 `forbid_certificate_mutation`：**UPDATE/DELETE 一律拒绝，无例外通道**
  （连 `agent_retention` 也不放行 —— 删除凭证即销毁证据链）。

**重建后核验**：`app_agent` 对 `audit_record`/`erasure_certificate` **无 DELETE** ✅（凭据隔离闭环）。

**来源**：`审查文档/护栏设计综合评估文档1.md` P2-2、`docs/数据库用户与权限梳理_2026-11-01.md` §1.4③
**状态**：✅ 已修

---

## DBP-10 · `rag_ops` 角色级 `search_path` 用 `%L` 包裹列表型 GUC → 存成带引号的单标识符

**问题描述**
`rag_ops` 角色级默认 `search_path` 被设成 `'rag, public'`，但用 `%L`（字面量转义）包装，
在库里存成了**一个带逗号的标识符**（`"rag, public"`），而不是两个 schema 的列表
→ 角色级默认 `search_path` **静默解析不到任何对象**。

**产生原因**
`%L` 是**字符串字面量**转义，`search_path` 是**列表型 GUC**（值是 identifier 列表）。
用单引号语义去写列表型 GUC，PG 会把整串当成**一个**标识符。

**相关解决方案**
列表型 GUC 不能用 `%L` 包整体 —— 改用能表达「多个 identifier」的写法
（如 `set search_path = rag, public` 的裸 SQL 片段，或逐项拼接）。
应用侧 HikariCP `connection-init-sql` 的 `SET search_path TO rag, public` 是正确的写法，
但**角色级默认**与**连接级 init-sql** 是两处，都要对。

**来源**：`docs/数据库用户与权限梳理_2026-11-01.md` §2.1、`git log 48c3d05`
**状态**：✅ 已修

---

## DBP-11 · `02_platform_seed.sql` 附录 A 空库复放必炸（`setval` 序列下界越界）

**问题描述**
附录 A 的 `setval('cap_config_publish_seq', coalesce(max,0))` 在**空表**时执行 `setval(0)`，
而序列下界是 1 → **越界报错**。
该附录 2026-10-29 为存量库补写、**从未在空库验证过**。

**产生原因**
「为存量库补写的脚本」与「空库复放要跑的脚本」是两种场景，作者只验了前者。
这正是 DAT-04 的同款病灶：**存量库 OK ≠ 空库 OK**。

**相关解决方案**
改为「空表归位 1（`is_called=false`）→ 非空追到 max」。
并把「空库复放」纳入发布前的标准验证动作（在真实 RDS 上开临时库跑两轮）。

**来源**：`docs/数据库规范化整理与远程重建实录_2026-11-01.md` §2.2 第 1 条
**状态**：✅ 已修

---

## DBP-12 · 种子只有 t-a，t-b 夹具靠老库残留 → 删库重建即凭空消失

**问题描述**
IT 依赖的 t-b 租户夹具此前靠「老库残留」存在，删库重建后会**凭空消失** ——
正是靠空库复放验证才暴露。

**产生原因**
夹具的来源没写进脚本，而是「库里一直有」。
这类隐式依赖在**第一次重建库**时才暴露，而那正是最不该出事的时刻。

**相关解决方案**
按「**三处齐**」形状固化进 `02_platform_seed.sql` 附录 B：
`platform.tenant` + `tenant_quota` + `public.tenant` 三处齐（+ 预算 + agent1/v1/快照），**全幂等**。

**来源**：`docs/数据库规范化整理与远程重建实录_2026-11-01.md` §2.2 第 2 条
**状态**：✅ 已修

---

## DBP-13 · 两个 `04_` 前缀文件 → 按名排序执行顺序不确定

**问题描述**
`deploy/sql/platform` 下有**两个** `04_` 前缀文件（`04_retention_role.sql` 与
`04_platform_admin_grant.sql`），按名排序执行顺序不确定。

**产生原因**
用序号前缀表达顺序，但**序号不能重复**；重复后排序退化，结果依赖文件系统/排序实现的细节。

**相关解决方案**
文件编号唯一化（`04_retention_role.sql` / `06_platform_grant.sql`），
并把 `04_platform_admin_grant.sql` / `06_platform_grant.sql` 明确标注为**带外模板，非初始化序列**。

**来源**：`docs/系统优化建议-全量评审.md` §4.4
**状态**：✅ 已修

---

## DBP-14 · 迁移锁表风险 + 全仓 `CREATE INDEX CONCURRENTLY` 0 处

**问题描述**
- `V23` / `rag V3` 改列类型 = **表重写 + ACCESS EXCLUSIVE** 锁；
- 全仓库 `CREATE INDEX CONCURRENTLY` **0 处**（应该 CONCURRENTLY 建索引避免锁表）。

**产生原因**
写 DDL 时用的是「DDL 脚本」的心智模型（一次性、可停机），
而不是「在线迁移」的心智模型（对运行中的库做）。

**相关解决方案**
改列类型改为「新增列 → 回填 → 切读写 → 删旧列」的多步迁移；
建索引一律 `CONCURRENTLY`。
迁移纪律同步写入 README。

**来源**：`docs/系统优化建议-全量评审.md` §4.4
**状态**：✅ 已修

---

## DBP-15 · `cap_config_release_two_person_ck` 谓词允许双 NULL 放行 → 名不符实

**问题描述**
约束谓词是：
```sql
approver_id IS NULL OR requester_id IS NULL OR approver_id <> requester_id
```
「两人都为空」也放行 → 它**并不是**两人复核约束，**名字比实现更严格**。

**产生原因**
约束用「否定式」写法时，NULL 的三值逻辑天然把「缺信息」当成了「不违规」。
复核类约束应当是「肯定式」的：**两人都非空且不相等**才成立，缺任一项即拒绝。

**相关解决方案**
发布头补丁里填 `requester='seed-replay'`、`approver=NULL`，
只是**如实标明这是复放产物**，不代表走了双人复核 —— 约束本身仍然名不符实，未改。
**仍未闭环的一条**：如需真正强制，谓词应改为
`(approver_id IS NOT NULL AND requester_id IS NOT NULL AND approver_id <> requester_id)`，
但这会与「复放产物」的语义冲突，需先定「复放是否允许免复核」的口径。

**来源**：`docs/RAG检索与端到端实测问题记录_20261028.md` §11.5 / §12.5 第 4 条
**状态**：⛔ 未解决（需先定口径）

---

## DBP-16 · 冗余索引 `idx_audit_tenant_time`

**问题描述**
`idx_audit_tenant_time`（`:2889`）是 `idx_audit_tenant_time_id`（`:2896`）的**前缀**，可删。

**产生原因**
加索引时按「查询维度」逐个加，没检查已有索引是否已覆盖。

**相关解决方案**
删除冗余索引。

**来源**：`docs/系统优化建议-全量评审.md` §4.4
**状态**：✅ 已修

---

## DBP-17 · Schema 漂移风险：迁移链已删，结构靠 `pg_dump` 快照带外恢复

**问题描述**
`PlatformDdl.java:15-17`；`db/migration` 仅含 `verify/`。
迁移链已删，schema 由 `pg_dump` 快照带外恢复；**CI 中无「活库 == 提交快照」的强制比对**。
→ 结构漂移会使 RLS 策略 / CHECK 在运行库与实际不符。

**产生原因**
「快照即真相」在没有比对机制时，会退化成「谁最后跑快照谁说了算」。

**相关解决方案**
1. 新增 `SchemaDriftIT`（活库 vs 01 快照）→ 重建后实测 **2/2 通过，BUILD SUCCESS**；
2. 增加「审计动作覆盖、schema 漂移、RLS 策略对齐」门禁（`86c267b` 落地）。

**来源**：`审查文档/护栏设计综合评估文档1.md` P3-2、`docs/数据库规范化整理与远程重建实录_2026-11-01.md` §4
**状态**：✅ 已修

---

## DBP-18 · [数据层迁移] 活库 RLS 策略与 DDL 脚本漂移 —— `agent_dev` 仍是单策略旧形态

**问题描述**
用 `scripts/export-rls-policies.sh` 导出各库策略时，发现**同一个容器的不同库策略形态不一致**：

| 库 | rag 域策略数 | 形态 |
|---|---|---|
| `rag_base` / `rag_final` / `rag_replay` / `rag_scope_probe` … | **35** | 读写分策略（读=4 条/表，含哨兵桶；写=不含哨兵） |
| `agent_dev` / `rag_it` / `study_rag` | **10** | **单策略**（`cmd='*'`，严格相等，读写共用同一谓词） |

而 `deploy/sql/rag/01_rag_ddl.sql` 定义的是**读写分策略**（如 `:1283` 的 `rag_chunk_read` 用
`tenant_id = ANY (ARRAY[rag_current_tenant(), rag_global_tenant_id()])`，`:1290/:1297` 的
`rag_chunk_update` / `rag_chunk_write` 用 `AND (scope = 'TENANT')`）。

⇒ **`agent_dev` 这个「主开发库」的 RLS 形态落后于 DDL 定义**：它没有「读放行到平台全局桶」的能力，
全局语料在 `agent_dev` 上对租户**读不到**。

**产生原因**
DBP-04（DDL 双源分裂）的延续形态 —— DBP-04 修的是「脚本不在 Flyway」，
但**策略本身在多个库之间的部署进度是不受约束的**：
没有「活库策略形态必须等于当前 DDL 定义」的强制比对，
而 DBP-17 的 `SchemaDriftIT` 比对的是**表结构**，**不比对策略谓词**。

**影响**
1. **迁移基线不可信**：RLS → 应用层迁移的 P1 影子阶段要拿「活库实际谓词」与「翻译对照表」比对。
   若基线取自 `agent_dev`（10 条单策略），而生产库是 `rag_base`（35 条读写分策略），
   则**同一份翻译表在两个环境产出不同结论** —— 影子阶段会全量 mismatch，而根因是**基线取错库**，
   不是翻译错。⇒ 迁移基线必须**指定环境**并记录在对照表里。
2. **语义差异被误读为「翻译错误」**：单策略（读写共用严格相等）会分类成 `TENANT_ONLY`；
   读写分策略的读侧会分类成 `TENANT_WITH_SHARED`。**合并成一个分类 = 丢掉写侧闸门 = 越权**。
3. 更根本的：`agent_dev` 上全局语料读不到，说明**该库压根没跑过 V8 之后的语料迁移**。

**相关解决方案**
1. **已落地**：`scripts/export-rls-policies.sh` 增加三重检测 ——
   ① **读写不对称检测**：同表读 scope ≠ 写 scope 时单列成清单（迁移时必须保留该区分，
   否则**租户可写全局桶 ⇒ 越权**）；② **缺口扫描**：带 `tenant_id` 却无任何策略的表；
   ③ **活库 vs DDL 漂移检测**（`--ddl` 参数）：策略条数不一致即告警并要求人工判定方向。
2. **待办（迁移 P0）**：判定 `agent_dev` 是「待重建」还是「有意保留旧形态」，
   并把**对照表基线绑定到具体环境**（对照表 `meta.source` 已记录来源库，须评审确认该库即目标环境）。
3. **待办**：把「策略谓词比对」补进 DBP-17 的漂移门禁 —— 当前 `SchemaDriftIT` 只比对表结构，
   **策略谓词漂移是它看不见的一类**。

**来源**：`scripts/export-rls-policies.sh` 实测（2026-11-02）· `deploy/sql/rag/01_rag_ddl.sql:1283-1297`
**状态**：🟡 已定位（工具已落地），库对齐待办

---

## 本分类小结

| 编号 | 一句话 | 状态 |
|---|---|---|
| DBP-01 | `platform_ops` 对序列无 UPDATE → `nextval()` 报权限错 | ⛔ |
| DBP-02 | `app_agent` 对 platform schema 整域失读 | ✅ |
| DBP-03 | `cap_*` 4 表缺 DELETE 策略 | ✅ |
| DBP-04 | DDL 双源分裂（不在 Flyway） | ✅ |
| DBP-05 | 外键缺索引 | ✅ |
| DBP-06 | 角色授权必须排在 DDL 之后 | ✅ |
| DBP-07 | `03` 重跑静默覆盖 `04` 的 REVOKE | ✅ |
| DBP-08 | `rate_limit_bucket` 未走统一租户上下文函数 | ✅ |
| DBP-09 | 审计「不可篡改」依赖可自设 GUC | ✅ |
| DBP-10 | `search_path` 列表型 GUC 用 `%L` 包裹 | ✅ |
| DBP-11 | 附录 A 空库复放 `setval` 越界 | ✅ |
| DBP-12 | t-b 夹具靠老库残留 | ✅ |
| DBP-13 | 两个 `04_` 前缀 → 顺序不确定 | ✅ |
| DBP-14 | 迁移锁表风险 + 无 CONCURRENTLY 建索引 | ✅ |
| DBP-15 | 双人复核 CHECK 允许双 NULL 放行 | ⛔ |
| DBP-16 | 冗余索引 | ✅ |
| DBP-17 | Schema 漂移无强制比对 | ✅ |
| DBP-18 | [数据层迁移] 活库 RLS 策略与 DDL 漂移 | 🟡 |
| **DBP-18** | **[数据层迁移]** 活库策略与 DDL 漂移（`agent_dev` 仍单策略旧形态） | 🟡 已定位 |

> **本类统一教训**：**授权是有向的**——`GRANT → REVOKE → 回填` 的顺序即语义，
> 而 `ALTER DEFAULT PRIVILEGES` 有时间锚点，`%L` 不能用于列表型 GUC，
> RLS 漏写策略等于多了一道闸门。**顺序一换，语义就变。**
>
> **追加教训（DBP-18）**：**结构比对 ≠ 策略比对**。DBP-17 的漂移门禁查表结构，
> 查不出「策略形态落后」—— 策略谓词是**语义**不是结构。
> 凡是要据此做决策的东西（本次是迁移基线），**先确认它比对的是什么维度**。

# 集成测试（IT）运行指南

> 本文件回答一个问题：**「集成测试要跑起来，需要准备什么；跑绿了又意味着什么」**。
>
> ⚠️ 本项目反复踩过一个坑：**测试从未运行，但构建是绿的**。
> 「跳过 249 项」与「249 项全过」在 CI 界面上长得一模一样。
> 所以本文档不只讲「怎么跑」，更讲**「怎么确认它真的跑了」**。

---

## 0. 一句话结论

```bash
cd /path/to/java-multi-agent
export AI_AGENT_PASSWORD='...'      # RDS 上唯一带 CREATEDB 的业务角色（不落盘）
./scripts/init-it-db.sh             # 首次：建 agent_it 并灌结构/种子（--recreate 可推倒重来）
source ./scripts/it-env.sh --probe  # 导出 IT_PG_* / RABBITMQ_*，并自检结构

cd platform
mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
  -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false
```

**判绿的标准不是「BUILD SUCCESS」，而是「Tests run: N, Failures: 0, Errors: 0, Skipped: 0」。**

> ⚠️ **IT 的目标库是 `agent_it`，不是应用库 `agent_dev`**（2026-11-03 决策）。
> 两者共库会让 IT 与运行实例互相污染，而 `execution_snapshot` 受
> `forbid_snapshot_mutation` 保护、**污染不可逆**（实测 `t-a` 累积 1018 Run / 139 快照）。
> `scripts/it-env.sh` 在「目标库 == 应用库」时**直接拒绝导出环境**——
> 这条守卫是 fail-closed 的，想复现历史问题必须显式 `IT_ALLOW_SHARED_DB=1`。

> ⚠️ `-Dtest` 后面那串正向模式**不能省**：写成纯排除（`-Dtest='!X'`）会让 surefire
> 把 38 个 `*IT` 类也拉进单元阶段 —— 见 §6.4。


---

## 1. 为什么 IT 必须连**真实** PostgreSQL

| 理由 | 说明 | 缺库时的假象 |
|---|---|---|
| **RLS 只在真实 PG 上生效** | 租户隔离靠 `FORCE ROW LEVEL SECURITY` + `current_tenant_id()`，这是 PG 特性，无法用 mock/H2 替代 | 用超级用户跑 → 得到「隔离失效」的**反向结论** |
| **约束/触发器是不变量** | `run_completed_at_consistent`、`forbid_snapshot_mutation`、`weakness_idempotency_uniq` 等 | 约束不触发 → 写入路径的缺陷全部漏检 |
| **SQL 语义** | 窗口函数、`AT TIME ZONE 'UTC'`、`date_trunc`、`ON CONFLICT` | 换成内存实现 → 统计口径（归日、环比边界）验证不到 |

**因此 IT 用 `@EnabledIfEnvironmentVariable(named = "IT_PG_URL")` 守门：**
无库 → **跳过**（不是失败），这样本地无库也能构建；
有库 → **必须真跑**，跳过数会被 failsafe 如实记录，由 CI 断言「跳过数必须为 0」。

---

## 2. 环境变量契约

| 变量 | 必需 | 默认 | 用途 |
|---|---|---|---|
| `IT_PG_URL` | **是** | 无 | JDBC 连接串。**没有它 → 全部 IT 跳过**（最危险的形态） |
| `IT_PG_USER` | 否 | `app_agent` | 应用连接角色。**必须是非特权角色** |
| `IT_PG_PASSWORD` | 视环境 | 空 | 应用连接口令 |
| `IT_PG_ADMIN_USER` | 否 | `agent` | 管理通道角色（造跨租户夹具、清理） |
| `IT_PG_ADMIN_PASSWORD` | 视环境 | 无 | 管理通道口令。**密码认证的库（RDS/CI）必须显式给**，缺失时报 `server requested password-based authentication, but no password was provided`，表现为整类 IT 无法启动 |

> ⚠️ `IT_PG_ADMIN_*` 与 `IT_PG_*` 是**两个不同的连接池**，权限不同：
> 前者用管理角色（可绕过 RLS），后者用应用角色（受 RLS 约束）。
> 两者混用会让「隔离测试」测了个寂寞。

---

## 3. 角色铁律：应用连接必须**非特权**

```sql
-- 应用连接角色：必须 NOSUPERUSER + NOBYPASSRLS
CREATE ROLE app_agent NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '...';
GRANT USAGE ON SCHEMA public TO app_agent;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_agent;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_agent;
```

**为什么不能用超级用户验 RLS：**
超级用户 / `BYPASSRLS` 角色**直接绕过策略**，于是「租户 B 能看到租户 A 的数据」这个断言会**假绿**。
这正是 `migration/verify/V1_constraint_check.sql` 开头写明的原因：
> 用特权角色验证 RLS 会得到虚假结论。

> ⚠️ 本项目 `dev` 环境的 `ai_agent` 角色**带 BYPASSRLS**。
> 这意味着**双层隔离（显式 `tenant_id` + FORCE RLS）只在应用角色无 BYPASSRLS 时成立**。
> 该风险已登记在进度台账，需产品/运维决策，**不擅自改账号权限**。

---

## 4. 库的准备

### 4.1 本地容器（推荐，与运行实例隔离）

```bash
./scripts/dev-it-db.sh            # 建库 + 应用迁移 + 清理残留 + 播种 + 授权
```

脚本是**幂等**的，重复执行不会重复插入。默认库名 `agent_it`（可用 `IT_DB=xxx` 覆盖）。

> ⚠️ **脚本会核对「库的结构是不是当前快照」**（§2.2）。只看 `run` 表在不在是不够的：
> 快照是**终态导出**、只适用于空库，后续新增的表永远补不上。10-31 引入
> `platform.authz_decision_log` 时就这样漏过 —— 本地库停在 65 表、快照已是 66 表，
> 脚本一路绿灯，直到 `SchemaDriftIT` 报「表不存在」才暴露，而失败信息不指向「库是旧的」。
> 现在少了就直接打印重建命令并退出。

> ⚠️ **为什么 IT 必须用自己的库**：
> 本机开发库 `agent_dev` 被正在运行的 `PlatformApplication` 持续写入（上千条僵尸 `state='RUNNING'` 的 run）。
> IT 与运行实例共用一个库时**双方互相污染**，且失败现场指向错误方向：
> 僵尸 run 占满 `claimTasks` 的 `LIMIT 1000` 窗口 → 后来者报「任务未被领取」，
> 看起来像 claim 逻辑坏了，真因是「上轮测试没清库」。

### 4.2 远端 PG（RDS / 无 Docker 环境）

```bash
export AI_AGENT_PASSWORD='...'   # 特权角色：建库 / 建角色 / DROP 重建
./scripts/init-it-db.sh          # = init-db.sh platform --db agent_it，但口令自动取自 platform.env
./scripts/init-it-db.sh --recreate   # 推倒重来（DROP DATABASE … WITH (FORCE)）
```

`init-it-db.sh` 做四件事：建库 → 灌 `01_platform_ddl.sql` → 灌 `02_platform_seed.sql`
→ 配 `03/04` 角色并自检（表 66 / RLS 42 / 策略 50 / `run_state.WAITING_APPROVAL` / `agent_retention`）。

> ⚠️ **为什么由脚本代取三个非特权口令**：`03_platform_roles.sql` / `04_retention_role.sql`
> 是 `ALTER ROLE … PASSWORD`，**改的是集群级角色**。若随手传一个新口令，
> 运行实例（`platform.env` 里那套）会立刻连不上库。脚本从 `platform.env` 原值回填，
> 保证「建 IT 库」这个动作对应用侧**零副作用**。
>
> ⚠️ 若结构快照与活库出现分歧（`SchemaDriftIT` 报红），唯一权威源是
> `deploy/sql/platform/01_platform_ddl.sql`：**改快照 + 对活库执行等价 ALTER**，
> 不要新增增量 patch（2026-11-01 起的结构变更纪律）。


### 4.3 种子数据

`verify/V1_seed.sql` 提供：`t-a` / `t-b` 两个租户、各自的 agent / agent_version / execution_snapshot、预算账户。

**统计类 IT 必须复用这两个种子租户**（原因见 §6.1）。

---

### 4.4 隔离的四道闸（拦住「又一次浇到应用库」）

脚本只是**一条**入口。IDE 里单跑一个 IT、CI 直接 `mvn verify`、或者谁手写
`export IT_PG_URL=…/agent_dev`，全都能绕开脚本。所以判据分四层各守一段：

| 阶段 | 谁 | 拦住什么 | 失败表现 |
|---|---|---|---|
| 建库 | `init-it-db.sh` / `dev-it-db.sh` | 目标库 == 应用库（脚本要 `DROP DATABASE`） | 直接拒绝执行 |
| 结构 | `dev-it-db.sh` §2.2 版本核对 | 库停在旧快照（65 表 vs 66 表） | 打印重建命令后退出 |
| 导出变量 | `it-env.sh` | 目标库 == 应用库 / 缺特权口令 | 拒绝导出并说明原因 |
| **构建（surefire）** | `ItTargetDatabaseIsolationTest`（`agent-infrastructure` 与 `platform-bootstrap` 各一份） | `IT_PG_URL` 指向应用库、或连接串解析不出库名 | 构建失败，一条可读的错 |

最后一道为什么要放在**测试里**：前两道都跟着脚本走，只有它跟着**构建**走。
放在 surefire 阶段（而不是 failset 的 `*IT`）是刻意的 —— 它在任何 IT 连库**之前**失败，
得到的是「一条人话」，而不是「五十条各自诡异的断言失败」。

判据实现：`agent-test-support` 的 `ItTargetDatabase`（`violation` / `requireIsolated`）。
它比对**库名**（硬编码 `agent_dev` / `study_rag` 兜底），并读 `platform.env` 里声明的
`SPRING_DATASOURCE_URL` 做**交叉比对** —— 应用库改了名也挡得住。反证（该红时必须红）
在 `ItTargetDatabaseTest` 里，共 10 例。

> **诚实边界**：判据只做字符串比对，**不连库**。刻意如此：surefire 若依赖数据库可达，
> 任何一次网络抖动都会变成假红；而「库空/结构旧」在 IT 里有更准确的报错，
> 结构自检交给 `it-env.sh --probe`（表数 / RLS / 策略）。
> 另：`platform.env` 被 `.gitignore` 忽略，CI 上没有它，交叉比对自动降级为硬编码名单。

---


`verify/V1_seed.sql` 提供：`t-a` / `t-b` 两个租户、各自的 agent / agent_version / execution_snapshot、预算账户。

**统计类 IT 必须复用这两个种子租户**（原因见 §6.1）。

---

## 5. 跑法

### 5.1 全 reactor（CI 等价，也是判定「真的跑了」的口径）

```bash
cd platform
mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
  -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false
```

- `-Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest'`：surefire（单元阶段）。
  四个正向模式 = surefire 默认 includes；`!MessagingCanaryWiringTest` 排除「必须要有 broker」
  的灰度装配测试。**必须带正向模式**，理由见 §6.4。
- `-Dit.test='*IT,!RealBrokerEndToEndIT'`：failsafe（集成阶段）。
  `RealBrokerEndToEndIT` **没有跳过门禁**（源码注释明写「没有 broker 的环境跑此 IT 会失败」）
  → 无 broker 时 3 项 ERROR。
- 两个测试**都保留**在代码里，供有 broker 的环境单独运行；CI 只是**不跑**它们。

### 5.2 单模块（迭代快，但要认清副作用）

```bash
mvn -o -pl agent-infrastructure verify -Dit.test=JdbcStatisticsRepositoryIT -DfailIfNoTests=false
mvn -o -pl platform-bootstrap verify -Dit.test='StatisticsEndpointIT' \
    -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
    -Dfailsafe.failIfNoSpecifiedTests=false
```

> ⚠️ 第二条里的 `-Dtest` 同样**必须带正向模式**（§6.4）：
> 写成 `-Dtest='!MessagingCanaryWiringTest'` 会把本模块的 `*IT` 类
> 拉进单元阶段跑一遍 —— 白跑一遍，且没有 broker 时直接红。

⚠️ **单模块跑会改变库的状态，进而改变其它 IT 的结果。**
典型：`MessagingSwitchGuardIT.refusesWhenDatabaseHasResidual` 的前置条件是
「DB 队列里确有残留」——单跑 `platform-bootstrap` 时条件不满足 → 该用例失败；
全 reactor 顺序跑时（`agent-infrastructure` 先跑并留下残留）→ 通过。

**这不是测试不稳定，是「测试之间共享数据库状态」的事实**。
判读结果时必须知道自己是哪种跑法。

---

## 6. 四个 IT 陷阱（都实测踩过）

### 6.1 「建租户 → 用完删掉」= 永久泄漏

`execution_snapshot` 上有 `BEFORE UPDATE OR DELETE` 触发器 `forbid_snapshot_mutation`，
**快照删不掉** → 依赖它的租户也删不掉 → 每跑一轮泄漏一批租户。

**正解**：复用种子租户 `t-a` / `t-b`，断言写成**增量**（before/after 差值），
而不是绝对值。增量断言还顺带免疫其它 IT 留下的残留。

> ⚠️ **不要为了「方便清库」绕过不变量**：
> 用便利换掉一条安全不变量，是本项目明确禁止的做法。
> 一次性测试库若必须清理，只能在**该库**上用 `session_replication_role = replica`，
> **绝不改动产品 schema 或触发器**。

### 6.2 管理通道写入未显式提交 = 夹具「写了但没写进去」

`JdbcTemplate` 默认 `autoCommit=true`；若改用 `autoCommit=false` 却**忘记 `commit()`**，
夹具数据不会落库 → 外键报「这行不存在」→ **整类 IT 变红**，而单跑却通过。

**正解**：管理通道用显式事务 + **显式 `commit()`**，并**插入后立刻回读自检**。

### 6.3 RLS 之下，「DELETE 没删掉」是**静默**的

`run` 等表是 `FORCE ROW LEVEL SECURITY`，策略 `USING (tenant_id = current_tenant_id())`。
若在**没有绑定租户**的连接上执行 `DELETE FROM run WHERE run_id = ?`：

- **不报错**（RLS 是过滤，不是拒绝）；
- **删掉 0 行**（策略把所有行都过滤掉了）。

症状是「测试全绿，但库里每轮多一条残留」，并且会污染其它 IT 的统计基线
（当日辅导量被越算越高）。

**正解**：清理必须在**绑定租户的事务**里做：

```java
conn.setAutoCommit(false);
set_config('app.tenant_id', tenantId, true);   // 事务级租户上下文
DELETE FROM run WHERE run_id = ?::uuid;
conn.commit();
```

> 实测对照：本指南配套的 `StatisticsEndpointIT` 在修复前每轮泄漏 1 条 `stat-http-%` run；
> 修复后同一库 `SELECT count(*) FROM run WHERE idempotency_key LIKE 'stat-http-%'` → **0**。

### 6.4 `-Dtest` 写「纯排除」会把 surefire 的扫描集合放大到「全部类」

`-Dtest` 是 **surefire（单元测试阶段）**的参数。写成一串**只有排除项**时：

```bash
# ⛔ 错：纯排除
mvn verify -Dtest='!MessagingCanaryWiringTest'
```

surefire 的扫描集合会从「默认 includes（`Test*` / `*Test` / `*Tests` / `*TestCase`）」
退化成「**全部类**」→ 38 个 `*IT` 类被拉进**单元测试**阶段：

- `RealBrokerEndToEndIT`（**没有**跳过门禁）当场 3 项 ERROR 拖垮构建；
- 接着 failsafe 阶段根本轮不到跑 → **「绿/红都发生在单元阶段，IT 一条没执行」**。

**正解：排除时必须同时写出正向模式**，逐个复现 surefire 默认 includes：

```bash
# ✅ 对：Test* / *Test / *Tests / *TestCase + 排除
mvn verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
      -Dit.test='*IT,!RealBrokerEndToEndIT'
```

实测对照（`agent-infrastructure` 模块）：

| 写法 | 扫到的 `*IT` 类 | 执行项 | 结果 |
|---|---|---|---|
| `-Dtest='!MessagingCanaryWiringTest'`（纯排除） | **38** | 432 | 3 ERROR |
| `-Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest'` | **0** | 141 | 全绿 |

> ⚠️ **不对称**：`failsafe` 的 `-Dit.test` **带正向模式**时排除是**生效**的
> （实测 `-Dit.test='PgQueueIT,!RealBrokerEndToEndIT'` → `RealBrokerEndToEndIT` 出现 **0** 次）。
> 也就是说：**问题不在「`!` 排除」，而在「纯排除没有正向模式」**。
> 少写一个正向模式 → 那个类在本 job 里**静默不跑**，且没有任何提示。


---

## 7. 已知的环境依赖缺口（不是缺陷，但必须说清）

| 测试 | 依赖 | 无依赖时的表现 | 处置 |
|---|---|---|---|
| `RealBrokerEndToEndIT` | 真实 RabbitMQ broker | 3 项 **ERROR**（无跳过门禁） | CI 显式排除；有 broker 的环境单独跑 |
| `MessagingCanaryWiringTest` | 真实 RabbitMQ broker | 3 项 **ERROR**（应用 fail-closed 守卫拒绝启动） | CI 显式排除；有 broker 的环境单独跑 |

**这两项在 CI 中「没有被验证过」——台账上必须记为「未覆盖」，
不能因为「CI 绿了」就当作「broker 链路没问题」。**

---

## 8. 「绿」的三种形态，必须分清

| 形态 | 判定 | 含义 |
|---|---|---|
| ✅ **真绿** | `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` | N 项确实执行并通过 |
| ⚠️ **假绿（跳过）** | `Skipped: N > 0` | IT 被 `@EnabledIfEnvironmentVariable` 整体跳过，**一条都没跑** |
| ❌ **假绿（管道吞退出码）** | 构建步骤绿但 Maven 红了 | `mvn ... \| tee log` 在 `bash -e` 下退出码取自 `tee`（恒 0） |

**CI 的防假绿闸门**（`.github/workflows/ci.yml`）：
- 步骤用 `set -o pipefail`；
- 汇总行正则**锚定行尾**（`Skipped: N$`），避免把逐类明细行重复计数；
- 断言 **跳过数必须为 0**，且执行数**不得低于实测基线**（低于 = 环境退步，不是「代码变好了所以测得少」）。

---

## 9. 相关文件

| 文件 | 作用 |
|---|---|
| `platform/pom.xml` | failsafe 注册（`*IT.java` 才进 integration-test 阶段） |
| `scripts/dev-it-db.sh` | 本地容器形态：建隔离库 + 应用结构快照 + 清残留 + 播种 + 授权 |
| `scripts/init-it-db.sh` | 远端（RDS）形态：建 `agent_it` + 灌结构/种子 + 配角色 + 自检；`--recreate` 推倒重来 |
| `scripts/it-env.sh` | IT 连接环境唯一入口：导出 `IT_PG_*` / `RABBITMQ_*`，**共库时 fail-closed 拒绝** |
| `platform/agent-test-support/.../testsupport/db/ItTargetDatabase.java` | 判据实现：IT 目标库 vs 应用库（库名 + `platform.env` 交叉比对） |
| `platform/agent-test-support/.../testsupport/db/ItTargetDatabaseTest.java` | 判据反证（该红时必须红），10 例 |
| `.../agent-infrastructure/src/test/java/.../infrastructure/it/ItTargetDatabaseIsolationTest.java` | surefire 阶段闸门（基础设施模块，4 例，含「判据非空转」自证） |
| `.../platform-bootstrap/src/test/java/.../bootstrap/ItTargetDatabaseIsolationTest.java` | surefire 阶段闸门（bootstrap 模块，2 例） |
| `platform/agent-infrastructure/src/main/resources/db/migration/verify/V1_seed.sql` | 种子租户 `t-a` / `t-b` |
| `platform/agent-infrastructure/src/main/resources/db/migration/verify/V1_constraint_check.sql` | 15 项约束校验（**用非特权角色跑**） |
| `.github/workflows/ci.yml` | integration-test job 的真实配置与防假绿断言 |
| `deploy/sql/platform/01_platform_ddl.sql` | **结构唯一权威源**（IT 库就是它建出来的） |
| `.../agent-infrastructure/src/test/java/.../PlatformDdl.java` | 构建期结构门禁的取数入口（指向上一行那个文件） |
| `docs/项目进度台账.md` | 权威台账：IT 缺口、污染记录、实测数字 |

---

## 10. 一页速查

```bash
# 0) 环境
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"

# 1) 准备库（二选一）
./scripts/init-it-db.sh                 # 远端 RDS：建 agent_it（需 AI_AGENT_PASSWORD）
./scripts/dev-it-db.sh                  # 本地容器 agent-pg17:5433/agent_it

# 2) 导出连接（唯一入口；目标库 == 应用库时拒绝）
export AI_AGENT_PASSWORD='...'
source ./scripts/it-env.sh --probe

# 3) 跑（CI 等价）
cd platform
mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
  -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false

# 4) 判绿（不是看 BUILD SUCCESS，是看这四行）
grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" \
  */target/failsafe-reports/*.txt | sort -u
```

---

## 11. 隔离库实测基线（2026-11-03）

在本机 `agent-pg17:5433` 上用 `01/02/03/04` **从空库重建** `agent_it_fresh`，
按 §10 的 CI 等价命令连跑 4 轮（第 1 轮漏了保留期凭据，见下）：

| 轮次 | 用例 | 失败 | 跳过 | 说明 |
|---|---|---|---|---|
| 1 | 2658 | 0 | 5+1 | ⚠️ 缺 `IT_RETENTION_*` → `RetentionErasureIT` **整类挂起**（20 例一条没跑，failsafe 只显示 `Skipped: 1`） |
| 2 | 2677 | 0 | 5 | 补上保留期凭据后 |
| 3 | 2677 | 0 | 5 | 同库第二轮，跑在上一轮的残留之上 |
| 4 | 2677 | 0 | 5 | 改由 `source scripts/it-env.sh` 导出环境（脚本入口自证） |

- **2677 = 2685 − 5（`RealBrokerEndToEndIT`）− 3（`MessagingCanaryWiringTest`）**，
  两个差值都是 CI 等价命令**刻意排除**的类，逐类核对过，没有「静默少跑」。
- 5 个跳过全部是**显式挂起**的用例（依赖已下线的三段式发布端点），与库无关。
- **跨轮增量**：`it-*` 租户逐位相同（`it-gov-a` 19 / `it-pgq-a` 14 / `it-budget-a` 16 …）；
  只有 `t-a` 每轮 +7（`ep-run-*`，来自 6 个端点契约 IT），且**全部是终态、`QUEUED=0`**
  —— 不会挤占 `claimTasks` 的 `LIMIT 1000` 窗口，属已知可接受残留。
  真嫌脏就 `./scripts/init-it-db.sh --recreate`（约 10 秒）。

> ⚠️ **表数期望值是 66**（`public` 46 + `platform` 20）。历史脚本写死 65，
> 会让**正确建出来的库**判自检失败 —— 2026-11-03 已修（多出来的是 `platform.authz_decision_log`）。


## 12. 隔离判据进构建后的基线（2026-11-03 晚）

同一台机器，新建本地 `agent_it`（`dev-it-db.sh`，66 表 / 42 RLS / 50 策略），连跑 2 轮：

| 轮次 | 用例 | 失败 | 跳过 | 耗时 |
|---|---|---|---|---|
| 1 | 2693 | 0 | 5 | 58s |
| 2 | 2693 | 0 | 5 | 60s |

**2693 = 2677 + 16**，新增的 16 例正是本轮加的三道构建期判据：

| 类 | 例数 |
|---|---|
| `agent-test-support`…`ItTargetDatabaseTest`（判据反证） | 10 |
| `agent-infrastructure`…`infrastructure.it.ItTargetDatabaseIsolationTest` | 4 |
| `platform-bootstrap`…`bootstrap.ItTargetDatabaseIsolationTest` | 2 |

跨轮增量：`it-*` 租户**逐位相同**（`it-gov-a` 19 / `it-budget-a` 16 / `it-pgq-a` 14 /
`it-atomic-a` 9 / `it-evt-a` 9 / `it-ledger-a` 6 / `it-fence-a` 5 / `it-fi-a` 4 /
`it-runcap-a` 4 / `it-audit-a` 3 / `it-claim-a` 2 / `it-tx-a` 2 / 其余 0～1）；
仅 `t-a` 每轮 +7（`ep-run-*`），与上一节一致。

> ⚠️ **建库脚本与生产授权口径必须一致**。本地跑 `dev-it-db.sh` 时若图省事「对每张表
> GRANT ALL」，会把最小权限模型抹平，实测立刻红三个 IT：
> `RetentionErasureIT`（应用角色竟有 `audit_record` 的 DELETE）、
> `AuthzDecisionLogLiveIT.appRoleCannotSelectDecisionLog`、
> `AuthzDecisionLogLiveIT.insertWithReturningIsDenied`（有 SELECT 就放行了 `INSERT … RETURNING`）。
> 现在 `dev-it-db.sh` 直接执行权威脚本 `03_platform_roles.sql` + `04_retention_role.sql`，
> 并在末尾做权限自检（`has_table_privilege` 五项），不一致即 `exit 1`。

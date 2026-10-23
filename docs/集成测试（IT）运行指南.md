# 集成测试（IT）运行指南

> 本文件回答一个问题：**「集成测试要跑起来，需要准备什么；跑绿了又意味着什么」**。
>
> ⚠️ 本项目反复踩过一个坑：**测试从未运行，但构建是绿的**。
> 「跳过 249 项」与「249 项全过」在 CI 界面上长得一模一样。
> 所以本文档不只讲「怎么跑」，更讲**「怎么确认它真的跑了」**。

---

## 0. 一句话结论

```bash
cd platform
export IT_PG_URL="jdbc:postgresql://<host>:5432/<isolated_db>" \
       IT_PG_USER=app_agent IT_PG_PASSWORD=<口令> \
       IT_PG_ADMIN_USER=<管理角色> IT_PG_ADMIN_PASSWORD=<管理口令>
mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
  -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false
```

**判绿的标准不是「BUILD SUCCESS」，而是「Tests run: N, Failures: 0, Errors: 0, Skipped: 0」。**

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

> ⚠️ **为什么 IT 必须用自己的库**：
> 本机开发库 `agent_dev` 被正在运行的 `PlatformApplication` 持续写入（上千条僵尸 `state='RUNNING'` 的 run）。
> IT 与运行实例共用一个库时**双方互相污染**，且失败现场指向错误方向：
> 僵尸 run 占满 `claimTasks` 的 `LIMIT 1000` 窗口 → 后来者报「任务未被领取」，
> 看起来像 claim 逻辑坏了，真因是「上轮测试没清库」。

### 4.2 远端 PG（RDS / 无 Docker 环境）

本机无 Docker 守护进程时，用 JDBC 探针代替 `psql` 完成建库与迁移，**步骤等价**：

1. 建管理角色 + 建库（`CREATE DATABASE`）；
2. 按版本号顺序应用 `agent-infrastructure/src/main/resources/db/migration/V*.sql`
   （排序按 `V` 后面的数字，不用字典序）；
3. `GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO app_agent`；
4. 用**管理角色**应用 `migration/verify/V1_seed.sql`（种子层与测试层必须分层：
   以非特权角色执行会被 RLS 拒 → `new row violates row-level security policy`）。

> ⚠️ **迁移必须全量按序**：只跑 `V1__baseline_schema.sql` 而漏掉
> `V7 rate_limit_bucket` / `V8 retention` / `V11 tenant_config_override` /
> `V12 config_change_request` 时，IT 会在「表不存在」上炸，而**根因在迁移没跑全**。

### 4.3 种子数据

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
| `scripts/dev-it-db.sh` | 建隔离库 + 迁移 + 清残留 + 播种 + 授权 |
| `platform/agent-infrastructure/src/main/resources/db/migration/verify/V1_seed.sql` | 种子租户 `t-a` / `t-b` |
| `platform/agent-infrastructure/src/main/resources/db/migration/verify/V1_constraint_check.sql` | 15 项约束校验（**用非特权角色跑**） |
| `.github/workflows/ci.yml` | integration-test job 的真实配置与防假绿断言 |
| `docs/项目进度台账.md` | 权威台账：IT 缺口、污染记录、实测数字 |

---

## 10. 一页速查

```bash
# 0) 环境
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"

# 1) 准备库（本地容器）
./scripts/dev-it-db.sh

# 2) 准备库（远端 PG，无 psql 时用 JDBC 探针：建角色→建库→按序迁移→GRANT→种子）

# 3) 导出连接
export IT_PG_URL="jdbc:postgresql://<host>:5432/<db>" \
       IT_PG_USER=app_agent IT_PG_PASSWORD=<口令> \
       IT_PG_ADMIN_USER=<管理角色> IT_PG_ADMIN_PASSWORD=<管理口令>

# 4) 跑（CI 等价）
cd platform
mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \
  -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false

# 5) 判绿（不是看 BUILD SUCCESS，是看这四行）
grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" \
  */target/failsafe-reports/*.txt | sort -u
```

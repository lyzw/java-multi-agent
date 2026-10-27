# 生产环境初始化 SQL（platform / rag）

> **本目录是库结构与种子数据的唯一权威源。**
> 2026-10-27 起，`db/migration` 下的 Flyway 迁移脚本（platform V1~V34、rag V1~V8）**已删除**，
> 原「Flyway 迁移 + 手工 DDL 双源」的收编问题随之消失 —— 不再有「两份定义必须同步」的纪律负担，
> 因为只剩一份。

---

## 目录结构

```
deploy/sql/
├── README.md                              ← 本文件
├── platform/
│   ├── 01_platform_ddl.sql                完整结构（65 表 / 680 列 / 255 约束 /
│   │                                      15 枚举 / 15 函数 / 172 索引 / 33 触发器 /
│   │                                      42 RLS 开启 + 50 RLS 策略）
│   ├── 02_platform_seed.sql               种子数据（pg_dump --inserts 导出）
│   ├── 03_platform_roles.sql              数据库角色 + 授权（集群级，需 CREATEROLE）
│   ├── 04_platform_admin_grant.sql        PLATFORM_ADMIN 带外赋权模板
│   └── 06_platform_grant.sql              平台角色绑定（带外运维通道）
└── rag/
    ├── 01_rag_ddl.sql                     完整结构（11 表 / 135 列 / 53 约束 / 9 枚举 /
    │                                      36 索引 / 11 RLS 开启 + 35 RLS 策略）
    │                                      + 手工补写的 vector / pgcrypto 扩展头
    ├── 02_rag_seed.sql                    种子数据（全局字典 + 演示语料）
    └── 03_rag_roles.sql                   数据库角色 + 授权（集群级）
```

**已删除的旧文件**（历史职责由 `01_*_ddl.sql` / `02_*_seed.sql` 接管）：

| 文件 | 原职责 | 现由谁承担 |
|---|---|---|
| `platform/04_capability_seed.sql` | 能力目录种子 | `02_platform_seed.sql`（已含 Tool/Skill/MCP/Agent/子引用边） |
| `platform/05_platform_identity_ddl.sql` | 平台身份域 | `01_platform_ddl.sql`（原 V31 内容） |
| `platform/07_platform_dictionary_ddl.sql` | 系统字典 | `01_platform_ddl.sql`（原 V32 内容） |
| `platform/08_platform_agent_metrics_ddl.sql` | Agent 指标 | `01_platform_ddl.sql`（原 V33 内容） |
| `db/migration/V1…V34`、`V1…V8` | 结构演进链 | `01_*_ddl.sql`（终态快照，无演进历史） |

---

## 执行顺序

### platform（目标库 `agent_dev` / `agent_prod`）

```bash
# ① 建库（空库）
psql -h <host> -p 5432 -U <超管> -d postgres -c "CREATE DATABASE <库名> OWNER <owner>"

# ② 结构（owner 执行；内含 CREATE SCHEMA IF NOT EXISTS public/platform）
psql -h <host> -p 5432 -U <owner> -d <库名> -v ON_ERROR_STOP=1 \
     -f deploy/sql/platform/01_platform_ddl.sql

# ③ 种子（非特权应用角色执行，能真正跑到 RLS 分支）
#    ⚠️ 必须在 ② 之后、④ 之前 —— 授权语句只覆盖执行时已存在的表
psql -h <host> -p 5432 -U <owner> -d <库名> -v ON_ERROR_STOP=1 \
     -f deploy/sql/platform/02_platform_seed.sql

# ④ 角色 + 授权（集群级操作，需 CREATEROLE 及以上）
psql -h <host> -p 5432 -U <超管> -d <库名> -v ON_ERROR_STOP=1 \
     -v app_password='<app_agent 口令>' \
     -v ops_password='<platform_ops 口令>' \
     -f deploy/sql/platform/03_platform_roles.sql
```

> ⚠️ **角色必须在结构之后建**：`ALTER DEFAULT PRIVILEGES` 只覆盖**此后**新建的表，
> 顺序颠倒会导致应用角色对全部表都没有权限。
>
> ⚠️ **平台级发布依赖 `platform_ops`**：应用侧必须配好，否则平台级发布会**明确失败**（不会静默回落）：
> ```
> AGENT_PLATFORM_OPS_ENABLED=true
> AGENT_PLATFORM_OPS_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<库名>
> AGENT_PLATFORM_OPS_DATASOURCE_USERNAME=platform_ops
> AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD=<口令>
> ```
> 库重建后角色若丢失，需重跑
> `platform/agent-infrastructure/src/main/resources/db/migration/verify/V__manage_platform_ops_role.sql`。

### rag（目标库 `study_rag`）

```bash
# ① 建库（扩展由 01 文件自带，无需单独 CREATE EXTENSION）
psql -h <host> -p 5432 -U <超管> -d postgres -c "CREATE DATABASE study_rag OWNER <owner>"

# ② 结构（⚠️ 需超级用户：vector / pgcrypto 均非 trusted 扩展）
psql -h <host> -p 5432 -U <超管> -d study_rag -v ON_ERROR_STOP=1 \
     -f deploy/sql/rag/01_rag_ddl.sql

# ③ 种子（⚠️ 02 里的 tenant_id 已固化为 platform 种子中 t-a 租户的 UUID；
#    换租户需先 UPDATE，或改用带 :tenant_id 参数的自建脚本）
psql -h <host> -p 5432 -U <迁移角色> -d study_rag -v ON_ERROR_STOP=1 \
     -f deploy/sql/rag/02_rag_seed.sql

# ④ 角色 + 授权
psql -h <host> -p 5432 -U <超管> -d study_rag -v ON_ERROR_STOP=1 \
     -v rag_password='<rag_app 口令>' \
     -f deploy/sql/rag/03_rag_roles.sql
```

---

## 这些文件是怎么产生的（来源与验证链）

> ⚠️ **不是从远程库直接导出的**，这是本次收口最重要的一个判断。
> 直接以远程为源会**永久丢失**四个版本的对象 —— 见下表。

| 环节 | 做法 | 实测结果 |
|---|---|---|
| ① 权威源 | 迁移脚本 V1→V34 / V1→V8（结构与种子的定义出处） | — |
| ② 建基准库 | `agent_base` / `rag_base`：空库逐个应用迁移 | **0 错误** |
| ③ 灌种子 | 原 `02_platform_seed` / `04_capability_seed` / `02_rag_seed` / `04_rag_seed_global_dictionary` | 脚本自带校验通过（Tool 6 / Skill 4 / MCP 2 / Agent 3 / 边 2；语料 来源 3 / 载体 7 / 片段 8） |
| ④ 导出 | `pg_dump --schema-only` + `--data-only --inserts`（**仅参考表**，不含运行数据） | platform 5352 行 / rag 1335 行 |
| ⑤ 空库复放 | 全新库重放导出文件，`ON_ERROR_STOP=1` | **0 错误** |
| ⑥ 等价比对 | `scripts/compare-db-schema.sh` 基准 vs 复放 | **逐项一致** |

**为什么不能从远程导出**（远程当时状态，2026-10-27 实查）：

| | 远程 Flyway | 远程缺失的对象 |
|---|---|---|
| `agent_dev` | 只到 **V27** | V31 平台身份域 / V32 `platform_dictionary` / V33 `platform_agent_metric` / V34 状态守卫 |
| `study_rag` | 只到 **V6** | V7 字典全局作用域 / V8 语料全局作用域 |

**为什么种子不能从远程导**：远程有 11 个租户，其中 9 个是 `stat-it-*` / `probe-*` 测试残留；
`agent` 表 95 行混杂 IT 产物。直接导会把测试垃圾固化成生产基线。
参考数据行数已逐项核对一致：字典 12/61、权限矩阵 23/6/52、`cap_agent_def` 3 ——
差异仅在「应当剔除的垃圾」。

**导出时的两处人工补写**（pg_dump 的已知盲区，均已实测撞到）：

1. `CREATE SCHEMA public/rag/platform` → 改为 `IF NOT EXISTS`：空库自带 public，否则复放即停。
2. rag 的 `CREATE EXTENSION vector / pgcrypto`：pg_dump 不导出扩展本体，
   而 `rag_chunk_embedding.embedding` 依赖 `public.vector(1024)`，缺则报
   `ERROR: type "public.vector" does not exist`。

---

## 结构变更今后怎么做

迁移链已删，**没有自动演进机制**。两种可选路径：

| 路径 | 做法 | 代价 |
|---|---|---|
| A（当前） | 直接改 `01_*_ddl.sql`，在目标库手工执行等价 ALTER，再重新导出 | 变更不留演进历史 |
| B | 重新引入 Flyway（建 `flyway_schema_history` + 以本快照作 baseline 迁移） | 需先决定是否让应用启动自动改结构 |

> ⚠️ 选 B 时的既有坑：Flyway 见历史表为空会重跑 baseline，`CREATE TYPE` 撞「类型已存在」
> 使整条链断在第一条。必须先写入基线记录再启用。

**应用侧现状**：`spring.flyway.locations` 指向 `classpath:db/migration`，该目录现已清空 →
启动时 Flyway 无脚本可跑（no-op）；全部 IT 早已显式 `spring.flyway.enabled=false`，
因此运行期与 IT 都不受影响。

> ⚠️ **但「不影响测试」这句 2026-10-27 说早了**：有四类**构建期结构门禁**
> （`SchemaStructureTest` / `CapCoreSchemaStructureTest` / `MultiAgentSchemaStructureTest` /
> `TutoringVocabularySchemaTest`）直接读迁移脚本原文做断言，删脚本时它们当场变红。
> 这类守卫的价值恰恰在于「不连库也能拦住被改坏的定义」，所以正确的收尾不是删掉它们，
> 而是把取数源换成本目录 —— 现由
> `platform/agent-infrastructure/src/test/java/.../PlatformDdl.java` 统一指向
> `deploy/sql/platform/01_platform_ddl.sql`（见该类 javadoc：`pg_dump` 输出的四处解析形状差异）。
> 同步退役的还有「迁移脚本可重放（幂等）」两条 IT 用例：
> 幂等是**增量脚本**的性质，终态快照只在空库上执行一次，
> 这条保证改由 CI 的「空库应用快照」步骤承担。

**环境准备脚本**：`scripts/dev-it-db.sh`（隔离 IT 库）与 `scripts/dev-postgres.sh`（本机容器）
已改为**应用本目录快照**，并因快照不幂等而加了「结构已存在则跳过」的判断。

---

## 相关脚本

| 脚本 | 用途 |
|---|---|
| `scripts/apply-migrations.sh <库> <迁移目录>` | 按**版本号数值序**应用迁移。⚠️ 迁移链已删，本脚本仅用于临时/历史目录 |
| `scripts/compare-db-schema.sh <A> <B> [schema]` | 比对两库结构/索引/RLS/函数/触发器/约束/枚举是否等价 |
| `scripts/dev-it-db.sh` | 建隔离 IT 库（应用快照 + 播种 + 授权） |
| `scripts/dev-postgres.sh` | 本机 PG 容器（start 时应用快照，幂等跳过） |

## 版本

platform 与 rag 两套 reactor 当前版本：**`1.0.1-SNAPSHOT`**（由 `0.1.0-SNAPSHOT` 升级）。

---

## 远程重置实录（2026-10-27 · `agent_dev` + `study_rag`）

> 两个远程库已按本文件的 DDL + seed 完整重建，并与本地基准库逐项比对一致。
> 重置前的完整留档：`/tmp/remote-backup/agent_dev_pre-*.sql`（3.0M）、
> `/tmp/remote-backup/study_rag_pre-reset_20261027-2245.sql`（259K，含全部数据）。

### 🔴 `agent_dev` 与另一个产品同库共存 —— 重置只能按 schema 粒度做

远程 `agent_dev` **不是本项目独占**：它里面还有一个 `growth_planet` schema
（39 表 / 1040 行，含 `usr_user`、`usr_child_profile`、`usr_family`、`sys_audit_log` 等真实用户数据）。

所以**不能 `DROP DATABASE agent_dev`** —— 那会连带毁掉 growth_planet。
本次采用的是 **schema 级重置**（实测 growth_planet 零波及：重置前后均 39 表 / 1040 行）：

```sql
DROP SCHEMA IF EXISTS platform CASCADE;
DROP SCHEMA IF EXISTS public    CASCADE;      -- growth_planet 是独立 schema，不受级联影响
CREATE SCHEMA public AUTHORIZATION pg_database_owner;
GRANT ALL   ON SCHEMA public TO pg_database_owner;
GRANT USAGE ON SCHEMA public TO PUBLIC;
GRANT ALL   ON SCHEMA public TO app_agent;
GRANT USAGE ON SCHEMA public TO platform_ops;
-- 之后按「执行顺序」小节的 ②③④ 灌 DDL / seed / roles
```

**为什么必须先验依赖再动手**（三条查询，任一条非空即不可用 schema 级重置）：

| 验证项 | 结论 |
|---|---|
| growth_planet 是否有外键指向 `public`/`platform` | 无 |
| growth_planet 表上是否有依赖我们函数的 RLS 策略 | 无 |
| growth_planet 是否依赖 `public` 里的函数（`pg_depend`） | 无（`public` 的 15 个函数全是我们自己的） |
| `agent_dev` 除 plpgsql 外是否还有扩展 | 无（故重建 public 不会带走扩展） |

### ⚠️ 结构脚本必须由**库 owner**执行，不能由应用角色执行

`01_*_ddl.sql` 里有 `CREATE SCHEMA IF NOT EXISTS public;`。以 `rag_app` 执行时报

```
ERROR: permission denied for database study_rag
```

—— 它虽有库级 `CREATE`，但对**已存在且不由它持有**的 `public` 无权置办。
远程的正确身份是库 owner **`ai_agent`**（有 `CREATEROLE`/`BYPASSRLS`，非超级用户）。
README「执行顺序」里写的「② 结构 ⚠️ 需超级用户」在 RDS 上应读作「需库 owner」。

### 本次重置有意改变的三件事

| 项 | 重置前 | 重置后 |
|---|---|---|
| 对象 owner | `public` 里 **24 张运行表由 `app_agent` 持有**（Flyway 当年以应用角色建表的残留） | 统一由库 owner 持有，应用角色经 `03_*_roles.sql` 拿 DML 授权 |
| 表数量口径 | `agent_dev` 尚有 `public.flyway_schema_history` | 已删除（迁移链不再存在，历史表是孤儿） |
| 授权覆盖 | `app_agent` 只被授予 28 张表（后期新建的 `cap_*` 漏授，正是本文件警告过的 `ALTER DEFAULT PRIVILEGES` 时序坑） | `03_platform_roles.sql` 在 DDL 之后执行，覆盖全部 65 张 |

### 📌 遗留（重置**未**引入，但下次动库时要一并决定）

1. **`ai_agent` 带 `BYPASSRLS`，而它就是应用主数据源用的角色**
   （`config.env` 的 `SPRING_DATASOURCE_USERNAME=ai_agent`）。
   这意味着**主链路连接绕过一切 RLS**，租户隔离在该环境里不发生 ——
   `03_platform_roles.sql` 特意规定 `app_agent` 必须 `NOBYPASSRLS`，而应用实际没用那个角色。
   要么让主数据源改连 `app_agent`，要么去掉 `ai_agent` 的 `BYPASSRLS`；
   在此之前，**任何以 `ai_agent` 跑的隔离测试结果都不能当作 RLS 生效的证据**。
2. **`platform` schema 对 `app_agent`/`platform_ops` 无授权**（重置前后一致，只授了 owner）。
   后台平台身份域若以非 owner 角色访问，会在库里直接 `permission denied`。
3. **rag 种子里的 `tenant_id` 固化**为 platform 种子中 `t-a` 租户的 UUID
   （`2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2`）；换租户要先 UPDATE。

### 重置后的实测结果

| 比对项 | `agent_base` → `agent_dev` | `rag_base` → `study_rag` |
|---|---|---|
| 表 / 列 | 65 / 680 | 11 / 135 |
| 约束（剔 NOT NULL）/ NOT NULL 列 | 255 / 503 | 53 / 100 |
| 枚举 / 函数 / 触发器 | 15 / 15 / 33 | 9 / 3 / 2 |
| 索引 | 172 | 36 |
| RLS 开启 / 强制 / 策略 | 42 / 42 / 50 | 11 / 11 / 35 |
| RLS 策略逐条文本比对 | **完全一致**（50 行，含 `polcmd`/`qual`/`with check`/绑定角色） | — |
| 种子 | tenant 1、agent 3/3、Tool 6 / Skill 4 / MCP 2 / Agent 3 / 边 2、字典 12/61、平台权限 6/23/52 | 字典、语料 来源 3 / 载体 7 / 片段 8 |
| 运行数据 | `run` / `outbox_event` = 0（预期：重置清掉了旧的 run 776、tenant 11、95 行 agent 残留） | — |

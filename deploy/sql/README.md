# 数据库初始化 SQL（platform / rag）—— 权威源

> **本目录是库结构与种子数据的唯一权威源。**
> 迁移链（Flyway V1~V34 / V1~V8）已于 2026-10-27 删除；**增量/补丁脚本已于 2026-11-01 全部收编**，
> 现在每个目标库只按「01 → 02 → 03 → 04」一条序列初始化，没有第二个来源。

---

## 目录结构（2026-11-01 收敛后）

```
deploy/sql/
├── README.md                              ← 本文件
├── platform/                              目标库：agent_dev / agent_prod / 隔离 IT 库
│   ├── 01_platform_ddl.sql                完整结构终态快照（65 表 / 680 列 / 255 约束 /
│   │                                      15 枚举 / 15 函数 / 172 索引 / 33 触发器 /
│   │                                      42 RLS 开启 + 50 策略；public + platform 两域）
│   ├── 02_platform_seed.sql               种子（pg_dump --inserts 导出）
│   │                                      + 附录 A：发布头 + 指针（按定义表推导，非 dump）
│   │                                      + 附录 B：t-b 租户与跨租户夹具（原 10 号脚本收编）
│   ├── 03_platform_roles.sql              角色 + 授权（app_agent / platform_ops）
│   │                                      + §2b platform 域授权（原 09/11 号脚本收编）
│   ├── 04_retention_role.sql              agent_retention 保留期专用角色（凭据隔离）
│   ├── 04_platform_admin_grant.sql        PLATFORM_ADMIN 带外赋权模板（非初始化序列）
│   └── 06_platform_grant.sql              平台角色绑定模板（带外运维通道，非初始化序列）
└── rag/                                   目标库：study_rag
    ├── 01_rag_ddl.sql                     完整结构（11 表 / halfvec(4096) / 36 索引 /
    │                                      11 RLS 开启 + 35 策略；含扩展头）
    ├── 02_rag_seed.sql                    种子（全局字典 + 演示语料）
    ├── 03_rag_roles.sql                   rag_app 角色 + 授权（NOBYPASSRLS）
    └── 04_rag_ops_role.sql                rag_ops 全局语料治理通道（BYPASSRLS，最小授权）
```

**初始化总入口**：`scripts/init-db.sh platform|rag`（建库 → DDL → 种子 → 角色 → 自检一条命令）。

## 2026-11-01 收编清单（删除的脚本与去向）

| 已删除脚本 | 原职责 | 去向 |
|---|---|---|
| `platform/05_rls_policy_alignment.sql` | rate_limit_bucket 策略统一 `current_tenant_id()` | 已在 `01_platform_ddl.sql` 终态快照内 |
| `platform/06_run_waiting_approval.sql` | `run_state` 增 WAITING_APPROVAL | 已在 01 快照内 |
| `platform/07_cap_approval_pending_human.sql` | `cap_approval_state` 增 AWAITING_HUMAN | 已在 01 快照内 |
| `platform/08_cap_skill_def_content.sql` | `cap_skill_def.content` 列 | 已在 01 快照内 |
| `platform/09_platform_schema_grants.sql` | platform 域授权给 platform_ops | 收编进 `03_platform_roles.sql` §2b |
| `platform/10_dev_seed_tb_snapshot.sql` | t-b 租户执行快照夹具 | 收编进 `02_platform_seed.sql` 附录 B |
| `platform/11_platform_tenant_read_grant.sql` | app_agent 只读 platform.tenant | 收编进 `03_platform_roles.sql` §2b |
| `platform/12_cap_authz_version_backfill.sql` | 真实用户授权版本回填 | 数据修复类，一次性；新库种子无需（principal 不入种子） |
| `platform/13_cap_agent_def_backfill.sql` | 从 agent_version 回填 cap_agent_def | 数据修复类，一次性；种子已自带 3 条 cap_agent_def |
| `platform/14_cap_platform_release_backfill.sql` | 平台 Tool/Skill 发布头补登记 | 被 02 附录 A 覆盖（按 PUBLISHED 定义推导发布头+指针） |
| `platform/patch_audit_ticket_scope.sql` | audit 工单范围列补录 | 列已在 01 快照内；`scripts/dev-it-db.sh` 的重放循环一并移除 |
| `platform/patch_cap_release_head.sql` | 发布头/指针补齐 | 与 02 附录 A 同源，附录 A 已覆盖 |
| `platform/patch_execution_ticket_permission.sql` | 权限码补录 | 权限矩阵以代码 `PlatformRolePermissionMatrix` 为权威源，治理副本随种子 |
| `platform/patch_platform_tenant_registration.sql` | 租户平台侧登记补录 | 被 02 种子「三处齐」形状覆盖 |
| `rag/04_migrate_embedding_halfvec_4096.sql` | 向量列 halfvec(4096) 迁移 | 一次性迁移；`01_rag_ddl.sql` 终态即 halfvec(4096) |
| `scripts/cleanup-platform-stray-agent-tables.sql` | 平台误建表清理 | 一次性清理；重建后无此问题 |

> 历史版本均在 git（删除前基线 `ddf0f9b`）可考。

---

## 执行方式

### 方式一：总入口脚本（推荐）

```bash
# platform（agent_dev / agent_prod）
scripts/init-db.sh platform \
  --host <host> --port 5432 \
  --admin-user <具备 CREATEDB/CREATEROLE 的账号> --admin-password '<口令>' \
  --db agent_dev \
  --app-password '<app_agent 口令>' \
  --ops-password '<platform_ops 口令>' \
  --retention-password '<agent_retention 口令>'

# rag（study_rag）
scripts/init-db.sh rag \
  --host <host> --port 5432 \
  --admin-user <...> --admin-password '<口令>' \
  --db study_rag \
  --rag-password '<rag_app 口令>' \
  --rag-ops-password '<rag_ops 口令>'
```

### 方式二：手工逐文件

```bash
# platform
psql -h <host> -U <超管> -d postgres -c "CREATE DATABASE <库名> OWNER <owner>"
psql -h <host> -U <owner> -d <库名> -v ON_ERROR_STOP=1 -f deploy/sql/platform/01_platform_ddl.sql
psql -h <host> -U <owner> -d <库名> -v ON_ERROR_STOP=1 -f deploy/sql/platform/02_platform_seed.sql
psql -h <host> -U <超管> -d <库名> -v ON_ERROR_STOP=1 \
     -v app_password='<口令>' -v ops_password='<口令>' \
     -f deploy/sql/platform/03_platform_roles.sql
psql -h <host> -U <超管> -d <库名> -v ON_ERROR_STOP=1 \
     -v retention_password='<口令>' -f deploy/sql/platform/04_retention_role.sql

# rag
psql -h <host> -U <超管> -d postgres -c "CREATE DATABASE study_rag OWNER <owner>"
psql -h <host> -U <owner> -d study_rag -v ON_ERROR_STOP=1 -f deploy/sql/rag/01_rag_ddl.sql
psql -h <host> -U <owner> -d study_rag -v ON_ERROR_STOP=1 -f deploy/sql/rag/02_rag_seed.sql
psql -h <host> -U <超管> -d study_rag -v ON_ERROR_STOP=1 \
     -v rag_password='<口令>' -f deploy/sql/rag/03_rag_roles.sql
psql -h <host> -U <owner> -d study_rag -v ON_ERROR_STOP=1 \
     -v rag_ops_password='<口令>' -f deploy/sql/rag/04_rag_ops_role.sql
```

### ⚠️ 身份与顺序纪律

| 纪律 | 原因 |
|---|---|
| **角色必须在 DDL 之后建** | `ALTER DEFAULT PRIVILEGES` 只覆盖此后新建的表；顺序颠倒 = 应用角色对全部表无权限（2026-10 实测坑） |
| **DDL/种子必须由库 owner 执行** | RDS 上非超级用户（ai_agent）是 owner；用应用角色跑会 `permission denied for database/schema` |
| **CREATE ROLE 是集群级** | DROP DATABASE 不删角色；重复执行脚本会因重名失败 → 03/04 脚本已做判存在处理 |
| **study_rag 的 vector/pgcrypto 扩展** | 装在 public 模式；重置该库走「DROP SCHEMA rag CASCADE + 保留扩展」而非删库，避免非可信扩展重建权限问题 |
| **验 RLS 必须用非特权角色** | app_agent / rag_app 实连验证；owner / BYPASSRLS 验隔离 = 假结论 |

---

## 种子内容速览

| 库 | 种子 |
|---|---|
| platform | 租户 t-a（平台侧登记+配额+业务侧镜像三处齐）+ t-b 跨租户夹具（含 agent1/v1/快照/预算）+ 3 agent/版本 + 能力目录（Tool 6 / Skill 4 / MCP 2 / cap_agent_def 3 / 子引用边 2）+ PLATFORM/TENANT 发布头与指针 + 平台权限矩阵（角色 6 / 权限 24 / 绑定 53）+ 系统字典（类 12 / 项 61） |
| rag | 全局字典 + 演示语料（来源 3 / 载体 7 / 片段 8；tenant_id 固化为 t-a 的 UUID `2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2`） |

**不含**：principal / 平台账号口令哈希（环境相关，见 `scripts/dev-seed-platform-users.sh` 与 `04_platform_admin_grant.sql`）。

## 结构变更今后怎么做

迁移链与增量脚本都没有了，**改 `01_*_ddl.sql` 终态快照本身**：

1. 改 `01_platform_ddl.sql`（或 `01_rag_ddl.sql`）；
2. 在目标库手工执行等价 ALTER；
3. 若涉及种子，同步改 `02_*_seed.sql`；
4. 跑构建期结构守卫（`SchemaStructureTest` 等四类直接读本快照原文断言）与 `SchemaDriftIT`（活库 vs 快照）。

> ⚠️ 不要再新增「增量 patch 脚本」——那正是 2026-11-01 收敛掉的东西。
> 环境漂移用 ALTER 修到活库、快照改到最新，两者同步，然后删除迁移痕迹。

## 相关脚本

| 脚本 | 用途 |
|---|---|
| `scripts/init-db.sh` | 初始化总入口（建库+DDL+种子+角色+自检） |
| `scripts/dev-it-db.sh` | 建隔离 IT 库（应用 01 快照 + 播种 + 授权） |
| `scripts/dev-postgres.sh` | 本机 PG 容器（start 时应用快照，幂等跳过） |
| `scripts/compare-db-schema.sh <A> <B>` | 比对两库结构/索引/RLS/函数/触发器/约束/枚举 |
| `scripts/check-tenant-registry.sh` | 租户「三处齐」巡检（只读，退出码 0 才算一致） |
| `scripts/check-rag-ops-role.sh` | rag_ops 通道复核 |
| `scripts/dev-seed-platform-users.sh` | 平台账号播种（口令哈希现场算，幂等） |

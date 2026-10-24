# 生产环境初始化 SQL（platform / rag）

> 用途：把两个模块的**完整表结构**与**种子数据**落成可直接执行的 SQL 文件，
> 供生产环境空库初始化，或用于与线上库做结构漂移比对。
>
> **来源**：远程 RDS（`rm-cn-hhf50fbhh0001uzo.rwlb.rds.aliyuncs.com:5432`，PostgreSQL 18.6）。
> 全部文件均**在远程库上实跑验证通过**（2026-10-23），非静态推断。

---

## 目录结构

```
deploy/sql/
├── README.md                        ← 本文件
├── platform/
│   ├── 01_platform_ddl.sql          完整 DDL（42 表 / 13 枚举 / 14 函数 / 19 触发器 / 46 RLS 策略）
│   ├── 02_platform_seed.sql         种子数据（租户 / 预算账户 / 管理员 / 可选基线 Agent）
│   └── 03_platform_roles.sql        数据库角色 + 授权（集群级，需超管）
└── rag/
    ├── 01_rag_ddl.sql               完整 DDL（11 表 / 9 枚举 / 2 函数 / 11 RLS 策略 + vector/pgcrypto 扩展）
    ├── 02_rag_seed.sql              种子数据（演示语料，tenant_id 必填）
    └── 03_rag_roles.sql             数据库角色 + 授权（集群级，需超管）
```

---

## 执行顺序

### platform（目标库 `agent_prod`）

```bash
# ① 建库（空库）
psql -h <host> -p 5432 -U <超管> -d postgres -c "CREATE DATABASE agent_prod OWNER <owner>"

# ② DDL（用 owner 执行）
psql -h <host> -p 5432 -U <owner> -d agent_prod -v ON_ERROR_STOP=1 \
     -f deploy/sql/platform/01_platform_ddl.sql

# ③ 种子（建议用**非特权**应用角色执行，能真正跑到 RLS 分支）
psql -h <host> -p 5432 -U <owner> -d agent_prod -v ON_ERROR_STOP=1 \
     -v tenant_slug='t-prod' \
     -v tenant_name='某某教育科技' \
     -v budget_limit='100000.00' \
     -v admin_login='tenant-admin' \
     -v admin_password_hash='$pbkdf2-sha256$i=600000$...' \
     -v admin_roles='TENANT_ADMIN' \
     -f deploy/sql/platform/02_platform_seed.sql

# ④ 角色 + 授权（集群级，需 CREATEROLE 及以上）
psql -h <host> -p 5432 -U <超管> -d agent_prod -v ON_ERROR_STOP=1 \
     -v app_password='<app_agent 口令>' \
     -v ops_password='<platform_ops 口令>' \
     -f deploy/sql/platform/03_platform_roles.sql
```

> ⚠️ **角色必须在 DDL 之后建**：授权语句针对「已存在的表」，
> 而 `ALTER DEFAULT PRIVILEGES` 只覆盖**此后**新建的表。
> 顺序颠倒会导致应用角色对全部 42 张表都没有权限。
>
> ⚠️ **生产用平台级发布时**，`03_platform_roles.sql` 里的 `platform_ops` 角色
> 必须配置到应用侧（否则平台级发布会**明确失败**，不会静默回落）：
> ```
> AGENT_PLATFORM_OPS_ENABLED=true
> AGENT_PLATFORM_OPS_DATASOURCE_URL=jdbc:postgresql://<host>:5432/agent_prod
> AGENT_PLATFORM_OPS_DATASOURCE_USERNAME=platform_ops
> AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD=<口令>
> ```

### rag（目标库 `study_rag`）

```bash
# ① 建库 + 装扩展（CREATE EXTENSION vector 需超级用户）
psql -h <host> -p 5432 -U <超管> -d postgres -c "CREATE DATABASE study_rag OWNER <owner>"
psql -h <host> -p 5432 -U <超管> -d study_rag -c "CREATE EXTENSION IF NOT EXISTS vector"
psql -h <host> -p 5432 -U <超管> -d study_rag -c "CREATE EXTENSION IF NOT EXISTS pgcrypto"

# ② DDL（01 内含 CREATE EXTENSION IF NOT EXISTS，已装则跳过）
psql -h <host> -p 5432 -U <超管> -d study_rag -v ON_ERROR_STOP=1 \
     -f deploy/sql/rag/01_rag_ddl.sql

# ③ 种子（tenant_id 必须是 platform 库中目标租户的 tenant_id）
psql -h <host> -p 5432 -U <迁移角色> -d study_rag -v ON_ERROR_STOP=1 \
     -v tenant_id='<platform.tenant.tenant_id 的 UUID>' \
     -f deploy/sql/rag/02_rag_seed.sql

# ④ 角色 + 授权
psql -h <host> -p 5432 -U <超管> -d study_rag -v ON_ERROR_STOP=1 \
     -v rag_password='<rag_app 口令>' \
     -f deploy/sql/rag/03_rag_roles.sql
```

---

## ⚠️ 与 Flyway 的关系：二选一，**勿混用**

| 场景 | 做法 |
|---|---|
| 生产由 **Flyway** 管理迁移（推荐，与仓库现状一致） | **不要**执行本目录的 DDL 文件，让 Flyway 跑 V1→V18 |
| 生产**手工 / 离线**初始化（无 Flyway） | 执行本目录的 DDL 文件，并自行记录版本基线 |

**混用的具体后果**（不是泛泛而谈）：
Flyway 看到 `flyway_schema_history` 为空 → 判定「未迁移」→ 重跑 V1 →
V1 的 `CREATE TYPE` 直接因类型已存在而失败 → **整个迁移链断在第一条**。

> RAG 侧尤其要注意：`rag-bootstrap` 引入了 `spring-boot-starter-flyway`，
> 应用启动时**自动**执行 V1→V5。手工建好表但不写 flyway 历史，
> 应用启动就会炸。

---

## 实测记录（2026-10-23，远程 RDS）

全部在**远程真实 PostgreSQL 18.6** 上跑通，非推断：

| 项 | 结果 |
|---|---|
| platform DDL 在空库执行 | ✅ 0 错误 → 42 表 / 38 表启用+强制 RLS / 46 策略 / 13 枚举 / 19 触发器 / 89 检查约束 |
| platform DDL 与线上 `agent_dev` 逐列比对 | ✅ 差异 0（线上 28 张业务表全部一致）；多出 14 表为线上**尚未执行**的 V15–V18（13 张 `cap_*` + `execution_capability_snapshot`） |
| platform 种子（**非特权角色**执行）×3 | ✅ 0 错误；租户/预算/账号/角色/Agent/版本/快照 各恒为 1（幂等） |
| platform RLS 实测 | ✅ 未设上下文→0 行；正确租户→1 行；错误租户→0 行；跨租户写入→被拒 |
| rag DDL 在空库执行 | ✅ 0 错误 → 11 表 / 11 策略 / 9 枚举 / 2 函数 / vector 0.8.1.3 + pgcrypto 1.4 |
| rag DDL 与线上 `study_rag` 逐列比对 | ✅ 差异 0（线上 10 表全部一致）；多出 1 表为线上**尚未执行**的 V6 `rag_dictionary` |
| rag 种子 | ✅ 0 错误 → 来源 3 / 版本坐标 3 / 载体 7 / 片段 8 |
| rag RLS 实测 | ✅ 未设上下文→0 行；正确租户→3 行；错误租户→0 行；跨租户写入→被拒 |
| 清理 | ✅ 验证库与临时角色已全部删除，线上 `agent_dev` / `study_rag` 零改动 |

---

## 五个必须注意的坑

1. **PG 版本 ≥ 17**：DDL 含 `SET transaction_timeout`（PG 17 引入）。PG 16 上会直接报错。
2. **`public` schema 不可创建**：PG 每库自带 `public`，脚本里已剔除 `CREATE SCHEMA public;`
   （pg_dump 导出时会带上它，直接执行会报 `schema "public" already exists`，
   且错误在文件开头 → 后续语句全部不执行）。
3. **RAG 的 `search_path`**：RAG 全部对象在 schema `rag`，而应用 SQL 用**未限定表名**。
   每条连接都必须 `SET search_path TO rag, public`
   （应用侧已由 HikariCP `connection-init-sql` 设置；手工连库必须自行执行）。
4. **验证 RLS 必须用非特权角色**：超管 / BYPASSRLS 角色会绕过所有策略，
   `FORCE ROW LEVEL SECURITY` 也拦不住 —— 用它们跑隔离测试会得到
   「隔离生效」的**虚假结论**。应用角色必须是 `NOSUPERUSER + NOBYPASSRLS`。
5. **`tenant_slug` 必须与 `AGENT_TENANT_SLUGS` 一致**：Worker 启动期按 slug 解析租户，
   slug 不存在即 fail-closed 拒绝启动，症状是「消息静默不发布、Run 永远不动」。

---

## 重新生成

DDL 必须与仓库迁移脚本**严格同源**。迁移脚本变更后按下列步骤重新导出：

```bash
# ① 在远程建空库
psql -h <host> -p 5432 -U ai_agent -d postgres -c "CREATE DATABASE agent_ddl_build OWNER ai_agent"

# ② 顺序执行全部迁移（版本号自动排序，禁止硬编码版本列表）
for f in $(ls platform/agent-infrastructure/src/main/resources/db/migration/V*__*.sql | sort -V); do
  psql -h <host> -p 5432 -U ai_agent -d agent_ddl_build -v ON_ERROR_STOP=1 -f "$f" || break
done

# ③ 导出（--no-owner --no-privileges：授权要落在实际使用的角色上）
pg_dump -h <host> -p 5432 -U ai_agent -d agent_ddl_build --schema-only --schema=public \
        --exclude-table=public.flyway_schema_history --no-owner --no-privileges \
        -f 01_platform_ddl.sql

# ④ 清理
psql -h <host> -p 5432 -U ai_agent -d postgres -c "DROP DATABASE agent_ddl_build"
```

RAG 侧同理，但**不能用 `study_rag` 直接导**——线上该库只迁移到 V5，
而仓库里已有 V6（`rag_dictionary`，2026-10-23 新增、尚未提交）。
要拿到「仓库当前状态的完整表结构」，需在空库上跑完 V1→V6 再导：

```bash
# ① 空库 + 扩展（CREATE EXTENSION vector 需超级用户）
psql -h <host> -p 5432 -U ai_agent -d postgres -c "CREATE DATABASE rag_ddl_build OWNER ai_agent"
psql -h <host> -p 5432 -U ai_agent -d rag_ddl_build -c "CREATE EXTENSION IF NOT EXISTS vector"
psql -h <host> -p 5432 -U ai_agent -d rag_ddl_build -c "CREATE EXTENSION IF NOT EXISTS pgcrypto"

# ② 顺序执行全部迁移（版本号自动排序，禁止硬编码版本列表）
for f in $(ls rag/rag-infrastructure/src/main/resources/db/migration/V*__*.sql | sort -V); do
  psql -h <host> -p 5432 -U ai_agent -d rag_ddl_build -v ON_ERROR_STOP=1 -f "$f" || break
done

# ③ 导出
pg_dump -h <host> -p 5432 -U ai_agent -d rag_ddl_build --schema-only \
        --exclude-table=public.flyway_schema_history --no-owner --no-privileges \
        -f 01_rag_ddl.sql

# ④ 清理
psql -h <host> -p 5432 -U ai_agent -d postgres -c "DROP DATABASE rag_ddl_build"
```

> ⚠️ **导出后必须做漂移比对**（与线上库逐列 diff）：
> 若线上已先于仓库执行了某个迁移（例如线上 V6 已应用、仓库又改了 V6），
> 两者会分叉。比对方法见上方「实测记录」章节使用的查询。

---

## 相关文件

- 迁移脚本（**唯一权威来源**）
  - `platform/agent-infrastructure/src/main/resources/db/migration/V1 … V18`
  - `rag/rag-infrastructure/src/main/resources/db/migration/V1 … V5`
- 角色管理脚本（仓库原有版本，本目录为其生产化改编）
  - `platform/agent-infrastructure/src/main/resources/db/migration/verify/V__manage_app_role.sql`
  - `platform/agent-infrastructure/src/main/resources/db/migration/verify/V__manage_platform_ops_role.sql`
- RAG 演示语料种子（本目录为改编版，`tenant_id` 改为必填参数）
  - `scripts/dev-seed-rag-corpus.sql`
- 本地开发环境脚本（**仅本地**，与生产初始化无关）
  - `scripts/dev-postgres.sh` / `scripts/dev-it-db.sh` / `scripts/dev-seed.sh`

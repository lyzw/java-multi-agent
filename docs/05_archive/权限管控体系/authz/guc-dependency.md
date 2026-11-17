# D11｜GUC 依赖对象盘点（RLS 迁移前置）

> **关联**：[12_RLS到应用层权限迁移方案.md](../../12_RLS到应用层权限迁移方案.md) §2.5 / §7.1 R6 / D11
> **目标**：穷举所有依赖 PG 会话变量 `app.tenant_id`（GUC）的数据库对象与 Java 绑定点，
> 逐一给出「改写 / 删除 / 保留」处置与反证方法，避免退役 RLS 后出现“静默 NULL / 报错”盲窗。
> **状态**：🟡 P0 基线（已扫描 `deploy/sql` + `TenantSessionBinder`）

---

## 1. 风险回顾（12 文 §7.1 R6）

迁移 RLS 后，`current_setting('app.tenant_id')` 不再被写入，依赖它的函数会**返回 NULL 或报错**，
导致“静默 0 行”或异常——这正是双写期最大的开天窗风险。本清单是 P0 的硬交付物。

---

## 2. 依赖对象清单

| # | 对象 | 落点 | 依赖 GUC 的方式 | 退役处置 | 反证 |
|---|---|---|---|---|---|
| **G1** | `public.current_tenant_id()` | `deploy/sql/platform/01_platform_ddl.sql`（`CREATE FUNCTION` + 50+ 策略引用） | `SELECT NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID`；未设置返回 NULL | **随策略 DDL 退役一并删除**（不保留）。退役前须 grep 确认无其他函数/视图引用 | 删除后 `SELECT current_tenant_id()` 必须**报错（不存在）**，而非静默 NULL |
| **G2** | `rag.rag_current_tenant()` | `deploy/sql/rag/01_rag_ddl.sql:225` | `current_setting('app.tenant_id', true)`；未设置返回 NULL；设为哨兵则 `RAISE 42501` | **退役时删除**；等价能力由 `SqlScopeTranslator` 注入 `tenantId` + 配置项哨兵替代（12 文 §4.1 / §4.3） | 删除后引用它的对象必须报错 |
| **G3** | `rag.rag_global_tenant_id()` | `deploy/sql/rag/01_rag_ddl.sql:262` | **IMMUTABLE 常量**，返回哨兵 UUID `'00000000-0000-0000-0000-000000000000'`；**不依赖 GUC** | **保留**（哨兵值来源）。应用层用配置项 `sharedTenantId` 等价替代（`SqlScopeTranslator` 已注入） | 迁移后应用层哨兵值须 `==` 该常量 |
| **G4** | `rag.forbid_rag_audit_mutation()` | `deploy/sql/rag/01_rag_ddl.sql:217`（`trg_rag_audit_append_only`） | **不依赖 GUC**，但属 DB 强制“追加写”完整性 | **保留**（与租户隔离正交）。应用层写入路径须同样禁止 `UPDATE/DELETE` `rag_access_audit`（领域校验或保留触发器） | 退役后尝试 `UPDATE/DELETE rag_access_audit` 仍报错 |
| **G5** | `TenantSessionBinder.set_config(...)` | `platform/agent-infrastructure/.../persistence/TenantSessionBinder.java:31-33` | 写侧：每事务 `set_config('app.tenant_id', ?, true)` | **P5 删除**（现标记 `@Deprecated(forRemoval = true)`）；P0–P4 过渡期保留（RLS 还在就必须钉） | 删除后全量回归绿，无 GUC 绑定残留 |

---

## 3. 关键结论

1. **真正依赖 GUC 的是 G1、G2**（读侧函数）。二者均随 RLS 策略 DDL 退役一并删除；
   应用层不再需要“当前会话租户”这一概念——租户身份由 `SubjectContext` 显式传入 `ScopeFilter`。
2. **G3（哨兵常量）不依赖 GUC，应保留**：它的唯一职责是提供哨兵 UUID，应用层改为配置项 `sharedTenantId`，
   值必须与该常量一致（已在 `SqlScopeTranslator` 构造期校验非空）。
3. **G4（追加写触发器）与租户隔离正交**：它保护的是 `rag_access_audit` 的不可变性，与 RLS 退役无关，保留。
4. **G5（写侧绑定）是 Java 层**，P5 随 `TenantSessionBinder` 整体删除；过渡期不可提前移除，否则 RLS 失效。

---

## 4. P0 出口动作

- [ ] 本清单与 `deploy/sql` 实际对象逐一核对（grep `current_tenant_id\(\)` / `rag_current_tenant\(\)` 全仓零残留引用方可退役）。
- [ ] G3 哨兵值已落入应用层配置项 `sharedTenantId`（`SqlScopeTranslator` 已就绪）。
- [ ] 退役阶段的反证脚本（删 GUC 后依赖对象必须报错/返回哨兵）纳入 P3 每批出口。

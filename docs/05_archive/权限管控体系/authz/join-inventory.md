# D13｜跨租户 JOIN 清单（RLS 迁移前置）

> **关联**：[12_RLS到应用层权限迁移方案.md](../../12_RLS到应用层权限迁移方案.md) §4.5 / §7.1 R7 / §7.3 C5 / D13
> **目标**：RLS 会对查询树中**每一张表**（含 JOIN 从表、子查询、CTE）自动注入 `tenant_id` 谓词；
> 应用层 `ScopeFilter` 默认只在**主表**渲染。本清单穷举所有 JOIN，逐条登记
> “主表谓词 / 从表是否已带 `tenant_id` / 染色覆盖 / 反证方法”，是消除“JOIN 泄漏”这一攻击面的基础。
> **状态**：✅ **P0 穷举完成 + P1 从表补谓词与染色覆盖完成**（2026-11-02）
> **守卫**：`JoinTenantPredicateArchitectureTest`（4 条不变量 + 3 条自证，随 D7 一起纳入 CI）

---

## 0. 为什么 JOIN 从表是独立风险（R7）

主表过滤正确的查询仍可能泄漏，形态只有一种：

```sql
-- 主表过滤正确：只查本租户的预留
FROM budget_reservation r JOIN tool_operation o ON o.operation_id = r.operation_id
WHERE r.tenant_id = ?::uuid
```

`operation_id` 单键 JOIN 只保证“操作号相同”，**不保证同租户**。RLS 在时，
`tool_operation` 自身策略会把 `o` 限到本租户；退役 RLS 后，若 `operation_id` 跨租户重复
（导入、重放、测试夹具、历史数据合并都可能造出来），从表就会带出他租户行。

**它比主表泄漏更隐蔽**：返回行的主表字段仍属本租户，**主表染色（只查主表 `tenant_id`）永远发现不了**。
因此“主表 filter 正确”与“JOIN 安全”是两件事，必须分别固定。

---

## 1. 扫描方法与范围（可复现）

| 范围 | 方法 | 结果 |
|---|---|---|
| `platform/*/src/main/java` | 去注释后按 `JOIN <table>` 正则扫描，取连接条件窗口（到下一个 `JOIN/WHERE/GROUP BY/...` 为止），判定窗口内是否含 `tenant_id` | **17 处 JOIN**：15 处合规、2 处平台目录豁免、**1 处缺口已修** |
| `rag/*/src/main/java` | 同上 | **24 处 JOIN**（检索 19 + 语料管理 5），**全部为复合键**，0 缺口 |
| `deploy/sql/**` | 人工审阅（运维/种子脚本，非运行时租户查询） | 5 处，全部豁免（理由见 §3） |
| 守卫固化 | `JoinTenantPredicateArchitectureTest`（扫 `platform/` Java 主源码） | 新 JOIN 无 `tenant_id` → 红；新从表未登记 → 红 |

> ⚠️ **扫描必须保留字符串字面量**：SQL 写在 Java 文本块里，若像 `ScopeBypassArchitectureTest`
> 那样连字符串一起去掉，等于把检查对象删了。守卫为此单独实现了 `stripComments`（只去注释）。

---

## 2. Java 侧 JOIN 全量清单（platform）

### 2.1 已合规（连接条件含 `tenant_id`，13 处）

| # | 文件:行 | JOIN | 从表 | 谓词形态 | 染色覆盖 |
|---|---|---|---|---|---|
| **J1** | `JdbcDelegationBudgetRepository:60`、`:78` | `LEFT JOIN agent_invocation i` | `agent_invocation` | `i.tenant_id = r.tenant_id`（复合键） | ✅ `join_tenant_id` 二次染色（空值跳过） |
| **J2** | `JdbcBudgetRepository:643`、`:682` | `JOIN tool_operation o` | `tool_operation` | `o.tenant_id = r.tenant_id` | ✅ `join_tenant_id` 二次染色 |
| **J3** | `JdbcAgentQueryRepository:56` | `LEFT JOIN LATERAL (agent_version)` | `agent_version` | 子查询内 `av.tenant_id = a.tenant_id` | ✅ `MAX(av.tenant_id)::text AS join_tenant_id` 二次染色 |
| **J4** | `JdbcApprovalQueryRepository:48` | `JOIN tool_operation o` | `tool_operation` | `o.tenant_id = a.tenant_id` | ⬜ 未接 Gate（D5 剩余仓） |
| **J5** | `JdbcExecutionSnapshotResolver:70`、`:72` | `JOIN agent_version v` / `JOIN execution_snapshot es` | 两表 | `v.tenant_id = a.tenant_id`、`es.tenant_id = v.tenant_id`（链式复合键） | ⬜ 未接 Gate |
| **J6** | `JdbcRunBudgetRepository:67` | `LEFT JOIN budget_reservation b` | `budget_reservation` | `b.tenant_id = r.tenant_id` | ⬜ 未接 Gate |
| **J7** | `AdminTenantRepository:168` | `LEFT JOIN principal_role pr` | `principal_role` | `pr.tenant_id = p.tenant_id` | ⬜ 未接 Gate（管理端） |
| **J8** | `CapDualWriteComparator:54`、`:63` | `JOIN agent a` | `agent` | `a.tenant_id = v.tenant_id` | ⬜ 对照器（只读诊断） |
| **J9** | `JdbcPlatformAgentDirectoryRepository:67`、`:73` | `LEFT JOIN LATERAL (agent_version)` / `LEFT JOIN LATERAL (cap_agent_def)` | 两表 | 子查询内 `v.tenant_id = a.tenant_id`、`d.tenant_id = a.tenant_id` | ⬜ 平台目录（运维通道） |
| **J10** | `JdbcTraceFactRepository:277` | `JOIN public.tool_operation_attempt att` | `tool_operation_attempt` | **本次补齐**：`att.tenant_id = op.tenant_id` + 主表 `op.tenant_id = ?::uuid` | ⬜ 未接 Gate |

> **J10 是本次穷举抓到的唯一真实缺口**。修前是 `att.operation_id = op.operation_id` 单键 JOIN +
> `WHERE op.run_id = ?`，既无主表显式租户谓词也无从表谓词，完全依赖 RLS。
> 症状会非常隐蔽：`tool_operation_attempt` 的 `(operation_id, attempt_number)` 唯一键跨租户不冲突，
> 但一旦 `operation_id` 有重复，本租户的**工具失败率/超时率**会把他租户的重试算进来 ——
> 是指标口径污染，不是“多返回几行”那么好发现。

> ⚠️ **SQL 类改动必须过活库解析校验**：J3 首版写 `MAX(av.tenant_id)::text`，PG 没有 `max(uuid)`
> 聚合，直接 `function max(uuid) does not exist`。而影子单测用 `NoConnectDataSource`，
> **SQL 语法与列解析一条都测不到**。本次 4 条改动语句已在 `agent_dev`（`app_agent` 角色，
> `BEGIN … PREPARE … ROLLBACK`，零写入）上验证 `ALL_PREPARE_OK`。
> 同理：`JOIN … AND o.tenant_id = r.tenant_id` 这类复合键只能靠活库验证列存在性。

### 2.2 平台目录豁免（无 `tenant_id` 列，2 处）

| # | 文件:行 | JOIN | 豁免理由 |
|---|---|---|---|
| **X1** | `JdbcPlatformPrincipalRepository:108`、`:132` | `JOIN platform.platform_role r` | `platform.*` schema 无 RLS、无 `tenant_id` 列；访问受 `GuardedPlatformOpsDataSource` 通道闸门约束（未配置即 503，绝不回落） |

### 2.3 本次一并修掉的染色缺陷（影响所有已接仓库）

> ⚠️ **染色原先存在“同义反复”**：`shadowTaint` 用**行租户**当主体
> （`SubjectContext.forTenant(rowTenantId)`），策略引擎必然算出 `TenantOnly(行租户)`，
> 逐行比对**恒通过** —— 除 `None`（期望 0 行）外，染色没有任何检出力。
> 假绿被测试替身掩盖：测试里的 `AccessGate` 返回**常量** filter，所以看起来能抓到跨租户行。

本次改为**主体与行归属分离**：

```java
void shadowTaint(String resourceType, String subjectTenantId, String rowTenantId)
// 主体 = 发起查询的租户；行归属 = 结果行真实 tenant_id
```

并新增回归用例 `JdbcResultQueryRepositoryShadowTest#shadowTaint_realPolicyEngine_crossTenantRowIsViolation`：
用**真实** `RuleBasedPolicyEngine`（`TENANT_ONLY` 规则）断言“主体 tenant-A + 行 tenant-B → 必须违例”。
若退回旧实现，该用例必红。

---

## 3. SQL 侧 JOIN 清单（`deploy/sql`，全部豁免）

| # | 文件:行 | JOIN 内容 | 性质 | 处置 |
|---|---|---|---|---|
| **S1** | `02_platform_seed.sql:414`、`:500` | `JOIN public.cap_config_release r ON r.release_id = p.current_release_id` | 种子数据关联 | 豁免：非运行时查询；写入须带 `tenant_id` |
| **S2** | `02_platform_seed.sql:512` | `JOIN LATERAL (...)` | 种子数据 | 同上 |
| **S3** | `03_platform_roles.sql:200`、`:207` | `JOIN pg_namespace n` | 角色/DDL 运维 | 豁免：`pg_catalog` 系统目录 |
| **S4** | `04_platform_admin_grant.sql:61`、`:103`；`06_platform_grant.sql:22-23` | `LEFT JOIN principal_role` / `CROSS JOIN platform_role` / `CROSS JOIN platform_user` | 授权脚本 | 豁免：平台运维域，走带外通道 |
| **S5** | `04_rag_ops_role.sql:168` | `JOIN pg_roles m` | RAG 运维 | 豁免：`pg_catalog` |

---

## 4. RAG 侧 JOIN 清单（B3 前置，全部合规）

| # | 文件 | JOIN 数 | 形态 | 结论 |
|---|---|---|---|---|
| **R1** | `rag/rag-infrastructure/.../JdbcRetrievalRepository.java` | 19（6 条检索 SQL） | `c.tenant_id = ed.tenant_id` / `= doc.tenant_id` / `= src.tenant_id` / `= e.tenant_id` | ✅ 全复合键；且主表 `c.tenant_id IN (rag_current_tenant(), rag_global_tenant_id())` |
| **R2** | `rag/rag-management/.../JdbcCorpusManagementRepository.java` | 5 | `c.tenant_id = d.tenant_id` / `e.tenant_id = c.tenant_id` / `= doc.tenant_id` / `= src.tenant_id` | ✅ 全复合键，主表带显式租户 |
| **R3** | `ChunkIndexer` / `RagScope` | 0（仅注释提及） | — | 无需处置 |

> ⚠️ **哨兵（全局语料）模型是 RAG 侧 JOIN 成立的前提**：全局行的 `tenant_id` 是**哨兵值**而非 NULL，
> 因此 `ON c.tenant_id = ed.tenant_id` 天然成立。若改成“`tenant_id` 可空”的表示法，这些 JOIN 会全部连不上
> （实测 0 行）—— 变更表示法前必须先改 JOIN 与准入谓词，属 B3 的破坏性变更。

---

## 5. 复合外键（跨租户引用完整性，12 文 §7.3 C5）

- `deploy/sql/rag/01_rag_ddl.sql`：`rag_chunk_document_fk` =
  `FOREIGN KEY (tenant_id, document_id) REFERENCES rag_document(tenant_id, document_id)`。
- 含义：子行 `rag_chunk` 的 `(tenant_id, document_id)` 必须对应**本租户**父行。移除 RLS 后，
  外键**不阻止**“子行 `tenant_id` 被写成 A、`document_id` 指向 B 的父行”——因为外键只校验**组合值存在**。
  应用层必须保证 FK 推导源自租户内查询。
- 反证脚本：`scripts/authz-cross-tenant-fk-probe.sql`（**需在演练库执行**，见脚本头注释）。

---

## 6. 统一处置规则（12 文 §4.5）

1. **JOIN 从表必须显式补谓词**，优先用**复合键等值**（`o.tenant_id = r.tenant_id`）而非
   两个独立参数（`o.tenant_id = ?`）—— 前者在 `r` 被换绑定时自动跟随，后者容易漏改一处。
2. **染色校验扩展到 JOIN 路径**：查询投影必须带出从表的 `tenant_id`（别名 `join_tenant_id`），
   在返回路径**二次染色**；`LEFT JOIN` 的空值必须跳过而不是判违例。
3. **染色主体 = 查询租户，行归属 = 行内 `tenant_id`**（不可用行租户当主体，否则恒通过）。
4. **高敏接口 100% 染色**：导出 / 检索 / 统计 / 跨租户读，生产也 100% 染色 + 异常告警（§9 Q6）。
5. **关键表保留 RLS 作 JOIN 兜底**：混合终态（§9 Q1）下，高敏表 RLS 不退役，天然覆盖 JOIN 从表。
6. **复合 FK 完整性**：应用层写入须维持 `(tenant_id, ...)` 同租户引用，反证脚本固定。

---

## 7. 出口动作

### P0（穷举与基线）

- [x] **J1–J10 逐条登记**（本次由“J1–J6 待穷举”收敛为 10 组 15 处具体 JOIN，附行号与谓词形态）。
- [x] **J10 缺口已修**（`JdbcTraceFactRepository` 从表 + 主表补租户谓词）。
- [x] **S1–S5 豁免理由写明**；`rag_chunk_document_fk` 反证脚本就绪。
- [x] **RAG 侧 24 处 JOIN 复核**（全复合键，0 缺口）。
- [x] **守卫上线**：`JoinTenantPredicateArchitectureTest`
      —— ①从表谓词；②新从表未登记即红；③台账无死条目；④3 条扫描器自证。

### P1（影子期，本次完成）

- [x] **已接 Gate 仓库的 JOIN 从表补谓词 + 二次染色**：J1 / J2 / J3（3 组 6 处查询）。
- [x] **染色主体分离修复** + 真实策略引擎回归用例（4 个已接仓库全部改签名）。
- [x] **改动 SQL 过活库解析校验**（`agent_dev` / `app_agent` / `BEGIN…PREPARE…ROLLBACK`，零写入，`ALL_PREPARE_OK`）。
- [ ] J4–J9 的染色覆盖：待各自接入 Gate（D5 逐域改造，P1–P2）。
- [ ] 生产采样率按“高敏 100%”落地（依赖染色开关与指标上报，P1 出口项）。

### 残余边界（勿遗忘）

1. **约 55 个 `Jdbc*Repository` 仍未接 Gate**，本清单只保证“JOIN 谓词正确”，
   **不等于**这些仓库已受应用层权限约束（`AUTHZ_MISMATCH=0` 不可外推）。
2. **守卫只扫 `platform/`**：`deploy/sql` 与 `rag/` 的 JOIN 目前靠本清单人工登记；
   RAG 侧守卫随 B3 批次落地。
3. **`JdbcTraceFactRepository` 其余查询仍是隐式租户**（`readRun` / `readUsage` / `readSteps` 只用 `run_id`），
   本次只修了 JOIN 那一条；主表显式谓词属 D5/D12 面。

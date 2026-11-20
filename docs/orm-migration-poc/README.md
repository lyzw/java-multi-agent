# MyBatis-Plus 迁移 POC —— 试点 Port 示例代码

> 配套主文档：`../../JPA迁移MyBatis-Plus可行性与调整方案.md`
> 生成日期：2026-11-20
> 状态：**参考实现（可编译级）**——代码按本项目真实包名/约定/表结构编写，用于 W0/W1 落地时直接复制改造；尚未接入构建（依赖与守卫改动见 `README.md` 的落地清单）。

## 目录

| 文件 | 作用 | 对应主文档章节 |
| --- | --- | --- |
| `README.md` | 本说明 + 落地清单 + 红线 | 全文 |
| `01-rls/TenantScope.java` | 租户作用域上下文（口径与 `TenantContext` 一致） | §10 D2 |
| `01-rls/RlsTenantBindingInterceptor.java` | **核心**：RLS 会话绑定的 MyBatis 拦截器（代码保证 RLS） | §10 D2/D3 |
| `01-rls/RlsGuardConfiguration.java` | 拦截器/双 SqlSessionFactory/运维通道豁免装配 | §3.2~3.5、§10 |
| `02-pilot/ConfigDraftDO.java` | 试点 DO（`@TableName` / `@TableId` / JSONB TypeHandler） | §4.1 |
| `02-pilot/JsonbTypeHandler.java` | JSONB 字段类型处理器 | §4.1、§8.3 |
| `02-pilot/ConfigDraftMapper.java` | Mapper 接口（`BaseMapper` + 自定义方法） | §4.2 |
| `02-pilot/ConfigDraftMapper.xml` | 试点 XML（游标分页、JSONB、`::uuid` cast、安全注释保留） | §5.1/5.2 |
| `02-pilot/MybatisConfigDraftRepository.java` | **Port 签名不变的适配层**（`JdbcXxxRepository` 委托 Mapper 的范式） | §4.2、§9.3 |
| `03-tests/RlsBindingIT.java` | RLS 绑定时序 / 串租户 / 平台哨兵 / 回滚清空 专项 IT | §8.3、§10 D4 |
| `04-guard/守卫改动片段.md` | 两个架构守卫的精确改动（批准的决策变更留痕） | §1.9、§9.2 W0 |

## 试点对象

- **platform**：`ConfigDraftPort` / `JdbcConfigDraftRepository`（表 `cap_config_draft`，RLS 策略 `tenant_id IS NULL OR tenant_id = current_tenant_id()`）
- **rag**：`KnowledgeAliasPort` / `JdbcKnowledgeAliasRepository`（表 `rag_kp_alias`，与本范式同构）

选择理由见主文档 §9.1。

## ⚠️ 三条不可逾越的红线（复制代码前必读）

1. **Port 签名零改动**：`ConfigDraftPort` / `KnowledgeAliasPort` 及所有 `*Port` 一字不改，上层 service/controller 零感知。改动只在 persistence 模块内部。
2. **RLS 由代码保证且 fail-closed**：任何 Mapper 调用都必须经 `RlsTenantBindingInterceptor` 在同一 `Connection` 上先执行 `set_config('app.tenant_id', ?, true)`；**租户上下文缺失直接抛异常**，绝不「无租户静默查询」。运维 BYPASSRLS 通道走独立 `SqlSessionFactory`（无该拦截器）。
3. **复杂 SQL 原样搬迁**：游标分页的 `limit+1` 探底、全序 `ORDER BY`、`ON CONFLICT`、幂等键、`FOR UPDATE`、影子态钩子——**逐字搬迁**并保留原注释，不改用 MP 的 `Page` / `LambdaQueryWrapper` 重写。

## 落地清单（W0 必须先完成，阻塞后续所有波次）

- [ ] 父 POM `dependencyManagement` 锁定 `mybatis-plus-spring-boot4-starter` **3.5.15+**（Boot 4.1.1 兼容，见主文档 R2）
- [ ] `agent-persistence` / `rag-persistence` 引入依赖；确认 Jackson 3 不冲突
- [ ] 应用 `04-guard/守卫改动片段.md` 的两个守卫变更（提交信息注明「架构决策变更：批准引入 MyBatis-Plus」）
- [ ] 装配 `RlsGuardConfiguration`：租户通道 `SqlSessionFactory`（含拦截器）+ 运维通道 `SqlSessionFactory`（无拦截器）
- [ ] 跑 `RlsBindingIT`：4 个用例全绿（含非特权角色、串租户反证）
- [ ] 启动冒烟：Boot 4.1.1 + MP 3.5.15 动态参数/自动填充无异常（issue #7064）

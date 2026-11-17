# CAP 全量开启 —— 运维检查清单（OPs Runbook）

- **配套脚本**：`scripts/cap-gray-step.sh`（段）、`scripts/cap-gray-metrics-check.sh`（E1/E2 判定）、`scripts/cap-enable-global.sh`（全量开启人工检查单）
- **依据**：《运行时灰度验证方案》+《阶段一》I2-2
- **红线**：全局开关 `config.flag.cap-authorization-enforced` 默认 `false`，本清单全程把「翻 true」留给【人工】执行。

---

## 0. 前置核对（开始前必查）

- [ ] Prometheus 抓取端已启用：`management.endpoints.web.exposure.include` 含 `prometheus`（本交付已配），并可 `curl <host>:<port>/actuator/prometheus`。
- [ ] `micrometer-registry-prometheus` 依赖已加入（本交付已加）。
- [ ] `/actuator/prometheus` 已在 `SecurityConfiguration.PUBLIC_ENDPOINTS` 匿名白名单（本交付已加；只读抓取）。
- [ ] 开关默认仍为 `false`：`grep cap-authorization-enforced application.yml` → `${CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED:false}`。

---

## 1. 三段灰度（`cap-gray-step.sh`）

| 阶段 | 命令（示例，租户按实际替换） | 观察 | 通过条件 |
|------|------|------|------|
| 段 1（1 租户） | `./scripts/cap-gray-step.sh --name s1 --tenants <t1> --observe-minutes 1440 --metrics-base-url http://<host>:8080 --baseline-ratio 0.90 --log-scan "grep -E '\[authz-mismatch\]' /var/log/app.log \| grep <tenantId>"` | 24h | 返回 0 |
| 段 2（5 租户） | 同，`--tenants t1,t2,t3,t4,t5` | 24h | 返回 0 |
| 段 3（~15 租户） | 同，`--tenants ...` | 24h | 返回 0 |

> `--log-scan` 段里的 `<tenantId>` 占位会被脚本逐个租户替换（`${LOG_SCAN//<tenantId>/$t}`），
> 用它按租户扫 `[authz-mismatch]` WARN，因为 `cap_authz_mismatch_total` 是【全局、按 resource_type】的计数，**不带租户标签**（如实口径）。

### 判定口径（`cap-gray-metrics-check.sh`）
- **E1**：`cap_authz_mismatch_total` 全系列求和 = 0（`check` 命令已实现；非零即返回 1）。⚠️ 这只是【集群级】；租户级零须叠加段 1 `--log-scan`。
- **E2**：`cap_tool_availability_ratio` 按 `tenant` 标签取灰度租户值，与开启前基线比，`可用率 ≥ 基线 − 1%` 才 PASS（`--baseline-ratio`）。

---

## 2. 熔断 / 回退（任何 FAIL）

```
# 从灰度白名单移除租户（全局开关仍 false）
export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED=false
export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS=''
# 重启 / 热载
```
- 回退 = **停止走 CAP 链路**，**不删除** cap_* 数据（DualWrite 语义：关开关 ≠ 删数据）。
- 排查：E1 非零 → 授权影子判定绑定漂移；E2 跌破 → RLS/Schema/预算误拒。

---

## 3. 全量开启（`cap-enable-global.sh`，人工执行）

前置三绿后才建议：
1. `CAP_GRAY_ALL_GREEN=yes ./scripts/cap-enable-global.sh --metrics-base-url http://<host>:8080`
2. 若脚本通过前置检查，**人工**执行其打印的三步：
   - `export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED=true`
   - `export CONFIG_FLAG_CAP_AUTHORIZATION_ENFORCED_TENANTS=''`
   - 重启 / 热载
3. 确认启动日志 `CAP 授权链路=ENABLED`（E3 判据；`CapEnforcementReadinessGuard` WARN→INFO）。

---

## 4. 全量开启后 24h 观察（I2-2 验收）

| # | 判据 | 验证 |
|----|------|------|
| A | 全租户 `cap_authz_mismatch_total` = 0 | 每小时 `./scripts/cap-gray-metrics-check.sh check --base-url ...` |
| B | 工具可用率无退化（偏差 < 1%） | 与开启前 24h 基线对比 |
| C | 启动日志 `CAP 授权链路=ENABLED` | 抓取实例启动日志 |

三判据全过 = **P0-1d 交付完成**。任一 FAIL → 回退到 `false` 并排障。

---

## 5. 上报模板

```
阶段: s1/s2/s3 | 租户数: N | 观察窗口: 24h
E1(AUTHZ_MISMATCH 全局)=0? PASS/FAIL
E1(该租户日志扫描)=0?  PASS/FAIL
E2(工具可用率 baseline→now)=X.XX → Y.YY?  PASS/FAIL
熔断: 无/有(触发租户=..., 原因=...)
结论: 通过→进入[s2/s3/全量] | 回退
```
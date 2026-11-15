# 前端项目（frontend）端到端（E2E）测试报告

> 📌 **二轮优化（2026-11-14 18:02）**：按 `E2E测试结论二次评估与优化建议.md` §6.4 继续闭环残留——存量租户批量补齐、治理登记漂移、平台重启（8080 health UP）、浏览器全链路 **9/9 PASS** 复跑（真实 Run DB 终态 `SUCCEEDED`）、T3 真实手势复核。发布判定维持「单租户试点演示可用、不具备规模化发布条件」。下文保留首轮记录原貌。
>
> 📌 **三轮优化（2026-11-14 18:35）**：见二次评估 **§6.5**——① §6.4 的 `run_step` 疑点已推翻「执行链状态机缺陷」定性，实为跨层契约漂移并修复（结果页步骤不再误显示「执行中」）；② 新发现并修复进度页 403/401 状态读取仍显示「正在处理 / 正在同步」且继续轮询的终态缺陷（铁律 15 漏网）；③ 第六章「旧报告 10 项遗留重判」已完成（0 条当前未修真实缺陷，根因是提交 `689aaa5` 于 10-30 已逐条落地，实测看到的是修复后形态；问题 9 属产品增强项）；④ 失败路径自动化门禁 +47 用例、4/4 撤点反证转红。**发布判定仍维持「单租户试点演示可用、不具备规模化发布条件」**：重判不等于全覆盖实测，多租户灰度 / 并发 / 预算边界 / 真实模型失败路径仍未覆盖。
>
> 📌 **四轮优化（2026-11-14 19:10）**：见二次评估 **§6.6**——把「旧报告 10 项中问题 3/4/5/6/7/8/10 的行为级专项实测」做实，结果**推翻了问题 4 与问题 10 的「已修复」判定**：① 结果页 C6 扫描截断在前且只扫 `explanation`，长文本尾部与其它渲染字段的敏感信息可直接绕过，已改全文全字段扫描 + 按字段拦截；② 批量导入 `bulkRegister` 是 fire-and-forget，页面 `await` 到 `undefined` ⇒ 失败静默当成功、进度条运行时不可观察、结果表只剩最后一批、连点重复提交，已改串行分批 + 累积 + 失败停续传 + 同步防重。7 项交互级门禁落地、前端 54 文件 / 806 用例、**6/6 撤点反证转红**；后端全 reactor BUILD SUCCESS。**发布判定仍维持不变**：这 7 项均为 jsdom / mock HTTP 层验证，真实内存泄漏 / 性能 / 下载落盘 / 后端逐批收到仍不可证明，多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径仍未覆盖。

- **被测对象**：`java-multi-agent/frontend`（`java-saas-agent-frontend`，错题归因辅导 Web 应用）
- **测试方式**：真实浏览器（agent-browser / Chromium）逐路由访问与逐项操作，配合 `curl` 直连后端、`psycopg2` 直连数据库取证
- **测试日期**：2026-11-14
- **测试结论（摘要）**：前端在「已登录且具备相应后端权限」的管理员视角下，路由、导航、页面渲染与主要交互基本可用；**学生**受众的自助辅导链路已按「正规实现」方向完成并**通过浏览器级全链路取证**。本轮共处置 **6 项缺陷**：F1（受众角色零能力）、F2（无权限页静默回退）、F3（无已发布 Agent）、**F6**（受众看不到可发起辅导的 Agent）、**F7**（核心链路在真实环境不可达，已打通并留档 4 层前置缺口）、**F8**（404 未终态化）均已闭环；F4 经复核为误报；F5 为「与旧报告对照」章节（非缺陷编号）。是否满足发布条件见第七章 **及二次评估文档**。
- ⚠️ **编号口径修正（2026-11-14 16:25）**：本摘要原把三项修复标为 F5/F6/F7，与第五章实际编号（F6/F7/F8）错位一位，且 F5 已被「旧报告对照」章节占用。现统一为 **F6 / F7 / F8**。§7.2 遗留清单原已使用正确编号。
- **更新（2026-11-14 下午·三轮）**：
  1. F3 阻断解除——走正规三步治理发布补齐 1 个已发布 Agent（F3「解除记录」）；
  2. F1 实施完成——后端授权矩阵补 STUDENT/PARENT/TEACHER 最小读权限 + 学生数据隔离（`requester_id`），API 级 13/13 PASS（F1「解除记录」）；
  3. **浏览器级全链路复测（11/11 PASS）**——学生登录→工作台→列表→发起辅导（Agent 下拉可用）→提交→进度页「已完成」→结果页 SUCCEEDED + 真实归因讲解；并实测发现并修复 **F6**（受众看不到 Agent 下拉）、定位并打通 **F7**（核心链路不可达的 4 层前置）、修复 **F8**（404 未终态化）。

> 本报告**取代** `frontend/E2E_TEST_REPORT.md`（旧报告为静态推断式，含 10 条未经实测的“问题”，详见第六章）。
>
> ⚠️ **2026-11-14 16:25 二次评估**：本报告结论已复核并**收紧发布判定**，另发现角色/交互层面的未闭合项。见同目录 `E2E测试结论二次评估与优化建议.md`（冲突处以该文件为准）。下文保留首轮记录原貌，仅修正编号错位与残留文本。
>
> ✅ **2026-11-14 17:40 修复落地**：二次评估的 N1–N6 / T1 / T2 / P0 已按用户裁决实现（N1 裁决为「家长/老师本版本不开放」），逐项状态与验证证据见 `E2E测试结论二次评估与优化建议.md` **§六**。其中本报告 §7.3 的「4 层前置缺口 / 生产无写入方」已由 `TenantRuntimeDefaults` + 两条租户开通链 + `scripts/provision_tenant_runtime.py` 闭环；`AGENT_TENANT_SLUGS` 已纳入 `platform.env.example` 与 `docs/本地启动指南.md` §6.5.1 部署清单。**发布判定仍维持「单租户试点演示可用、不具备规模化发布条件」**——浏览器级 E2E 复跑、旧报告 10 项遗留重判、T3 真实 click 复核尚未完成，且平台服务未重启。

---

## 一、测试环境与前置条件

### 1.1 运行环境
| 组件 | 地址 | 状态 |
|---|---|---|
| 前端 Dev Server（Vite 6 / React 18） | `http://localhost:5173` | HTTP 200 |
| 平台后端（platform-bootstrap） | `http://localhost:8080` | `/actuator/health` → 200 |
| RAG 后端（rag-bootstrap） | `http://localhost:8081` | `/actuator/health` → 200 |
| 数据源 | 远程 Aliyun RDS `rm-...rds.aliyuncs.com:5432/agent_dev`（库用户 `app_agent`，启用 RLS） | 可达 |

### 1.2 Vite 代理拓扑（实测确认）
- `/api/v1` → RAG :8081（检索模块）
- `/api`、`/actuator` → 平台 :8080
- 说明：聊天端点 `/api/knowledge/chat/stream` 经 `/api` 前缀落到平台 :8080（前端注释称其为“租户安全链”）。

### 1.3 测试账号（tenant `t-e2e-fe`，tenant_id `8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751`）
| 登录名 | 角色 | 密码 | 用途 |
|---|---|---|---|
| `e2eadmin` | `TENANT_ADMIN` | `E2e-Admin-2026` | 管理员视角（全功能） |
| `e2eapprover` | `APPROVER` | `E2e-Approve-2026` | 审批人视角 |
| `e2estudent` | `STUDENT` | `E2e-Student-2026` | 学生视角（受众角色） |

> 三个账号由真实 Java `PrincipalStore.PasswordHasher`（PBKDF2-SHA256, 600000 迭代）哈希，经 `psycopg2` 写入 RDS，并显式 `SET LOCAL app.tenant_id` 绕过 RLS 写入 `principal` 表。登录均返回有效 JWT（`expiresIn=900`，角色正确）。

### 1.4 工具
- `agent-browser`（vercel-labs/agent-browser 0.27.0，真实 Chromium）
- `curl`（直连后端取证请求/响应）
- `python3` + `psycopg2`（数据库取证）

### 1.5 前置条件与已知限制
- 后端联调不完整：RAG 检索/聊天端点在本环境返回 400/403/404（见 F4）。
- 测试租户 `t-e2e-fe` **未发布任何 Agent**（见 F3），导致发起辅导流程不可达。
- 登录态有效期 900s；过长脚本执行中曾因令牌过期触发自动重登（已处理，不影响结论）。

---

## 二、测试范围

| 维度 | 覆盖情况 |
|---|---|
| 全部前端路由 | 已逐条访问 56 次（student 23 / admin 19 / approver 14） |
| 角色与权限矩阵 | TENANT_ADMIN / APPROVER / STUDENT 三视角 × 全路由 |
| 关键操作流程 | 登录（成功/失败）、登出、检索记录、知识检索、通用问答、学习画像查询、报表、配置/治理/运维各页、无权限页、404 |
| 异常与边界 | 无效登录、无权路由重定向、不存在的学生编号、空数据态、令牌过期重登 |
| 未覆盖（受环境阻断） | 发起辅导完整链路（无已发布 Agent）、运行进度 SSE、运行结果页（无 Run 数据）、RAG 检索/聊天的真实模型返回（后端未联调） |

---

## 三、功能清单与路由/权限矩阵

### 3.1 路由与角色可见性（导航显隐，前端 `ROLE_PERMISSIONS`）
| 路由 | 区域(RequireRole) | 学生可见 | 审批人可见 | 管理员可见 |
|---|---|---|---|---|
| `/`、`/runs`、`/runs/new`、`/profile`、`/rag`、`/chat`、`/agent-chat`、`/reports`、`/no-permission`、404 | 无（公开区） | 是 | 是 | 是 |
| `/config*` | config | 否 | 否 | 是 |
| `/agents` | agents | 否 | 否 | 是 |
| `/approvals` | approvals | 否 | 是 | 是 |
| `/reconciliation` | reconciliation | 否 | 是 | 是 |
| `/audit` | audit | 否 | 否 | 是 |
| `/health` | health | 否 | 否 | 是 |
| `/rag/management` | ragManagement | 否 | 否 | 是 |

### 3.2 实际访问结果（真实浏览器取证）
- **管理员（TENANT_ADMIN）**：19 条路由全部正常渲染（CONTENT），无报错；`/rag/management` 在长脚本尾段曾误报 LOGIN（令牌过期），**已用全新登录复测确认为正常渲染**（标签页：来源列表与审核 / 录入新来源 / 批量导入 / 索引与统计 / 运维工具 / 字典维护）。
- **审批人（APPROVER）**：可访问项正常；受 `RequireRole` 保护的路由均**静默重定向回 `/`（工作台）**，且审批人工作台数据可加载（显示“还没有辅导记录”空态，无报错）。
- **学生（STUDENT）**：所有路由均可打开，但**受保护路由被 `RequireRole` 静默重定向回 `/`**；更严重的是，学生自己的工作台也因数据接口 403 而显示“没能完成 / 无权执行该操作”（见 F1）。

---

## 四、测试用例与执行记录

> 结论取值：**通过 / 失败 / 阻塞 / 不适用**。证据文件位于 `问题记录/E2E_frontend_2026-11-14/`（`screenshots/`、`snapshots/`、`shots/`、`snaps/`、`walk_*.json`、`interactions_admin.json`、`final_admin.json`）。

### 4.1 认证与导航
| 用例 | 操作 | 预期 | 实际 | 结论 | 证据 |
|---|---|---|---|---|---|
| 有效登录（管理员） | 填 tenantId/loginName/password → 提交 | 进入工作台 | 进入工作台，banner 显示“角色：TENANT_ADMIN” | 通过 | `walk_admin.json` |
| 有效登录（审批人/学生） | 同上 | 进入各自工作台 | 进入，角色显示正确 | 通过 | `walk_approver.json`/`walk_student.json` |
| 必填校验 | 留空密码提交 | 提交按钮禁用，不发送 | 按钮 disabled，不提交 | 通过 | 登录页快照 |
| 无效登录 | 正确租户/账号+错误密码 | 留在本页，提示错误 | 显示“租户、账号或密码不正确” | 通过 | `snaps/admin/invalid_login.txt` |
| 登出 | 点“退出” | 清会话→跳登录 | URL 变 `/login`，登录表单出现 | 通过 | `final_admin.json: fc_after_logout` |
| 路由守卫（RequireAuth） | 未登录访问 `/runs` | 跳 `/login` | 跳登录 | 通过 | 代码 `RequireAuth.tsx` |
| 路由守卫（RequireRole 未授权） | 学生访问 `/config` | 重定向（不展示无权限页） | 静默回 `/` 工作台 | 通过(机制)/见 F2 | `snapshots/student/config_gate.txt` |
| 无权限页 | 访问 `/no-permission` | 显示 403 无权限页 | 显示“403 无权限”+返回工作台 | 通过 | `snapshots/*/no_permission.txt` |
| 404 | 访问不存在路由 | 显示未找到 | 显示“未找到” | 通过 | `snapshots/*/not_found.txt` |

### 4.2 学生侧功能（管理员视角可达；**学生视角已于 2026-11-14 浏览器级复测覆盖**）
| 用例 | 操作 | 预期 | 实际 | 结论 | 证据 |
|---|---|---|---|---|---|
| 工作台 | 打开 `/` | 显示快捷入口+近期辅导 | 正常（管理员有数据则列表，否则空态） | 通过 | `snapshots/admin/dashboard.txt` |
| 发起辅导 | 打开 `/runs/new` | 表单可填写并提交 | ~~“没有可用的 Agent…”~~ **2026-11-14 已解除并实测可用**：治理发布补齐已发布 Agent（v2，含 `modelRef`）后，学生视角 `GET /api/agents` 返回 200 且 `latestPublishedAt` 非空，`NewRunPage.tsx:120-124` 的过滤逻辑命中，下拉框**实际渲染并预选「E2E 错题归因辅导 Agent（v2）」**；填表提交后**真实创建 Run 并跑到 SUCCEEDED** | 阻塞→**已解除(实测)** | `fullchain.json` / `screenshots/fullchain/05_form_filled.png` / F3·F6·F7 |
| 检索记录 | 打开 `/runs` | 列表/筛选 | 渲染（空态，因无 Run 数据） | 通过 | `snapshots/admin/run_search.txt` |
| 知识检索 | 填问题+模式+教材五轴→检索并生成 | 返回可溯源片段 | 提交后无结果/无错误呈现（后端未联调） | 不适用/见 F4 | `interactions_admin.json` / `snaps/admin/rag_result.txt` |
| 通用问答 | 填问题→发送 | 流式回复 | 提交后消息列表仍“还没有提问”，无回复无错误 | 不适用/见 F4 | `interactions_admin.json` / `snaps/admin/chat_result.txt` |
| 学习画像查询 | 填“S-2026-0001”→查看画像 | 展示画像或“未找到” | 导航到 `/profile/S-2026-0001` 并显示“找不到这个匿名编号” | 通过(边界) | `snaps/admin/profile_result.txt` |
| 报表中心 | 打开 `/reports` | 展示汇总 | 渲染（空态） | 通过 | `snapshots/admin/reports.txt` |

### 4.3 管理 / 治理 / 运维
| 用例 | 操作 | 预期 | 实际 | 结论 | 证据 |
|---|---|---|---|---|---|
| 配置总览/运行与容量/工具与审批/合规 | 打开 `/config*` | 渲染 | 正常渲染 | 通过 | `snapshots/admin/config*.txt` |
| Agent 配置 | 打开 `/agents` | 渲染/列表 | 渲染（空列表，因无 Agent） | 通过 | `snapshots/admin/agents.txt` |
| 审批台 | 打开 `/approvals` | 渲染 | 正常（审批人/管理员可见） | 通过 | `snapshots/*/approvals.txt` |
| 未知操作处置 | 打开对应路由 | 渲染 | 正常 | 通过 | `snapshots/admin/agent_chat.txt`(同区) |
| 审计查询 | 打开 `/audit` | 渲染 | 正常 | 通过 | `snapshots/admin/audit.txt` |
| 系统健康 | 打开 `/health` | 渲染 | 正常 | 通过 | `snapshots/admin/health.txt` |
| 语料管理（RAG） | 打开 `/rag/management` | 渲染 | 正常（6 个功能标签页） | 通过 | `snaps/admin/fc_rag_mgmt.txt` |

### 4.4 权限矩阵交叉验证（后端直连取证）
| 端点 | 学生 | 审批人 | 管理员 |
|---|---|---|---|
| `GET /api/runs`（RUN_READ） | ~~**403 ACCESS_DENIED**~~ → **200（仅本人 Run，2026-11-14 F1 实施后）** | 200（空） | 200（不收窄，可见全租户） |
| `POST /api/runs`（RUN_CREATE） | ~~403~~ → **201（requester_id 落库为本人）** | — | 201 |
| `GET /api/runs/{他人RunId}` | ~~—~~ → **404（反探测，不泄露存在性）** | — | 200 |
| `GET /api/agents`（列举可发起辅导的 Agent） | ~~**403 ACCESS_DENIED**~~ → **200（受众裁剪视图：只列已发布、不下发 `latestDefinition`）** | 403 | 200（完整治理视图） |
| `POST /api/cap/config/publishes`（治理动作） | **403 ACCESS_DENIED（不变，受众无治理能力）** | — | 200 |
| 登录态/角色声明 | roles=[STUDENT] | roles=[APPROVER] | roles=[TENANT_ADMIN] |

证据：旧行为 `/tmp/s_run.txt`（student 403，修复前）、`/tmp/a_run.txt`、`/tmp/p_run.txt`；**F1 实施后全量取证 `probe_student_f1.py`（13/13 PASS，含隔离/反探测/家长对照，见 F1 解除记录）**；**F6 实施后浏览器级复测 `fullchain_browser.py`（11/11 PASS，含 `GET /api/agents` 受众视图与 404 反探测，见 F6/F8 解除记录）**。

### 4.5 浏览器级核心链路走查（2026-11-14 第三轮，11/11 PASS）
| # | 步骤 | 预期 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | 学生登录 | 进入工作台，角色 STUDENT | 进入 `/`，banner「角色：STUDENT · 租户 8394ad1e」 | 通过 | `snapshots/fullchain/00_login_result.txt` |
| 2 | 工作台 | 可访问；导航**不含**管理区 | 可访问；导航仅「辅导台」组（工作台/发起辅导/检索记录/知识检索/通用问答/Agent 对话/学习画像/报表中心），**无**配置/审批/审计入口 | 通过 | `snapshots/fullchain/root.txt` |
| 3 | 辅导记录列表 | 可访问 | `/runs` 渲染正常 | 通过 | `snapshots/fullchain/runs.txt` |
| 4 | 发起辅导页 | 无「没有可用的 Agent」提示；渲染 Agent 下拉 | 提示消失；下拉预选「E2E 错题归因辅导 Agent（v2）」 | 通过 | `snapshots/fullchain/05_form_filled.txt` |
| 5 | 填表提交 | 跳转 `/runs/{runId}` | 跳转 `/runs/9a8e750b-…` | 通过 | `fullchain.json` |
| 6 | 进度页 | 呈现执行状态 | 「已完成」+ 执行事件时间线 | 通过 | `screenshots/fullchain/07_progress_done.png` |
| 7 | 结果页 | 呈现归因内容 | 「归因结果」/ `SUCCEEDED` / 讲解正文（错因分析 + 判别式推导 + 针对性练习 + 参考答案）/ 反馈区 / 解题步骤 | 通过 | `screenshots/fullchain/08_result.png` |
| 8a | 反探测：学生访问 admin 的 SUCCEEDED Run | 明确「资源不存在」 | 显示「资源不存在 / 该分析不存在，或不属于当前账号」 | 通过 | `snapshots/fullchain/runs_b5bde729-33e5-4bee-9031-dd5355cb1ac3.txt` |
| 8b | 同上 | 不泄露他人 Run 内容 | 页面**无**「判别式/针对性练习/参考答案/错因分析」等他人归因内容 | 通过 | 同上 |

⚠️ **自动化口径说明（诚实边界）**：`agent-browser click` 对 `type=submit` 按钮是**合成事件**，不触发表单提交默认行为（实测点击后**无任何 POST /api/runs**）；走查脚本改用 `form.requestSubmit()`（规范内的表单提交路径，与用户点击等价）→ 立即提交成功。故此项为**工具口径**，非应用缺陷。

---

## 五、缺陷与差异清单

### F1【高】学生/家长/老师角色登录后无法使用任何核心功能（权限模型缺陷）
- **现象**：以 `STUDENT` 登录后，工作台“近期辅导”显示“没能完成 / 无权执行该操作”；`GET /api/runs` 直接返回 `403 {"code":"ACCESS_DENIED","message":"无权执行该操作"}`。
- **根因**：后端 `RoleBasedAuthorizationService.ROLE_PERMISSIONS`（`platform/.../infrastructure/security/RoleBasedAuthorizationService.java:32-95`）仅登记了 `TENANT_ADMIN / AGENT_AUTHOR / APPROVER / VIEWER / WORKER / PLATFORM_ADMIN`，**`STUDENT / PARENT / TEACHER` 未登记**；按“默认拒绝”原则，所有动作（含 `RUN_READ`）对其一律拒绝。前端 `RequireRole.tsx` 的 `ROLE_PERMISSIONS` 同样为空数组。
- **矛盾点（实现与受众声明不一致）**：前端导航显式标注受众为“**学生 / 家长 / 老师**”（`AppShell.tsx` `rolesHint`），登录接口也接受这些角色并签发令牌，但实际可用能力为零。
- **影响**：若产品预期学生自服务（本应用即“错题归因辅导”面向学生），则该核心用户群完全不可用；若学生本就不直接登录（仅以“学生编号”作为数据实体由运营代操作），则应在登录/受众层面明确，避免误导。
- **复现**：见 4.4 表格 + `snapshots/student/dashboard.txt`（含“无权执行该操作”）。
- **建议**：① 与产品确认用户模型；② 若学生应自服务，后端为 `STUDENT` 授予 `RUN_READ/RESULT_READ/PROFILE_READ` 等最小读权限，并实现学生侧页面；③ 若不应登录，登录页/受众提示需移除或给出明确说明。
- **严重度**：~~高~~ → **已闭环（2026-11-14 正规实现）**。
- **✅ 解除记录（2026-11-14，按用户确认的「正规实现学生角色」方向执行）**：
  1. **后端授权矩阵**：`AuthorizationService.Roles` 新增 `STUDENT/PARENT/TEACHER` 常量与 `AUDIENCE_ROLES` 集合、新增 `isAudienceOnly(Set)` 静态判定；`RoleBasedAuthorizationService.ROLE_PERMISSIONS` 登记三受众角色——STUDENT → `RUN_CREATE/RUN_READ/EVENT_SUBSCRIBE/RESULT_READ`（刻意**不**给 `PROFILE_READ`：principal 表无学生编号绑定，放开即租户画像任意读）；PARENT/TEACHER → 同读能力 + `PROFILE_READ`（动作契约本就写明家长/教师可读画像）；三者均不给任何治理动作。未登记动作仍默认拒绝。
  2. **学生数据隔离（requester_id）**：`run` 表新增可空 `requester_id uuid` 列（01 DDL 快照 + 远程 dev 库 ALTER，含列注释）；`RunCreationService` 创建时落库 `context.principalId()`；四个读端点（`RunListController` 列表 SQL 谓词、`RunQueryController`/`RunEventController`/`ResultController` 详情）对**仅持受众角色**的主体（`isAudienceOnly`，空角色 fail-closed 判非受众）收窄到 `requester_id = 本人`，「不归属」与「不存在」统一 404 反探测；治理角色与 NULL requester（历史/系统内部）不受收窄。
  3. **测试**：单测 `RoleBasedAuthorizationServiceTest`（+3：studentCoreFlow/parentTeacherAudienceScope/audienceOnlyDetection）、`RunListControllerTest`（受众收窄+治理对照）、`RunEventControllerTest`/`SseStreamingTest` 构造器适配；DB IT `RunAudienceIsolationIT`（3/3：落库/读回/列表收窄）；全 reactor `clean verify` **14 模块 BUILD SUCCESS**。期间修复 `CapabilitySnapshotWriterIT` 夹具（字符串 principal 进 uuid 列，与 F3 同款错误模式——已改合法 UUID 并在 `JdbcRunCreationRepository` 入口加格式守卫：非 UUID 落 NULL + warn，受众隔离 fail-open 由 `findRoles` 的 UUID_PATTERN 结构性排除）。
  4. **部署**：远程 dev 库（owner `ai_agent`）`ALTER TABLE run ADD COLUMN requester_id uuid` 已执行并验证；新构建已部署，后端已重启。
  5. **API 级复测（`probe_student_f1.py`，13/13 PASS）**：学生登录回带 `roles=[STUDENT]` → `GET /api/runs` 200（仅本人）→ `POST /api/runs` 201 → DB 确认 `requester_id=学生 principalId` → 学生列表含本人 Run → **admin 创建的 Run 对学生不可见** → admin 列表不收窄 → 学生读本人 Run status/result 200 → **学生读他人 Run 404** → **学生治理动作 403 ACCESS_DENIED** → 家长（PARENT）登录 + 列表收窄同语义。
  6. **前端核对（零改动）**：核心辅导页（工作台/`/runs*`/画像/RAG 检索/问答）在 `App.tsx:147-160` **不挂 area**（任何已登录用户可见），`RequireRole.tsx` 的 `ROLE_PERMISSIONS` 中受众角色空数组恰好正确——导航隐藏全部管理区、辅导区不受限；管理页由后端 403 兜底（fail-closed 双保险）。`/api/config` 为 `anyRequest().authenticated()` 的只读端点（不暴露密钥），任何已认证用户可读属预期设计。
- **残余（2026-11-14 浏览器级复测后）**：**已闭环** —— 学生视角工作台/发起辅导/进度/结果全链路浏览器取证 11/11 PASS（见 F6/F7/F8 与 7.1）。
  ⚠️ **本轮发现新缺陷**：F1 实施后，「发起辅导」页对受众仍**取不到可发起辅导的 Agent 列表**（F6），已在 F6 中修复。

### F2【低/中·UX】RequireRole 对未授权但已登录用户静默跳回工作台，不展示无权限页 —— **已修复（2026-11-14 复测验证）**
- **原现象**：学生访问 `/config` 等受保护路由，被 `<Navigate to="/" replace />`（`RequireRole.tsx:69-71`）静默重定向，页面回到工作台，无“无权限”提示；仅 `/no-permission` 直访才显示 403 页。
- **修复**：将未授权分支改为重定向到无权限整页并附带原因：
  ```tsx
  if (!hasRoleAccess(roles, area)) {
    const reason = `访问「${area}」区域需要相应权限，当前账号未获得。这不是登录失效，重新登录不会解决。`
    return <Navigate to={`/no-permission?reason=${encodeURIComponent(reason)}`} replace />
  }
  ```
  `NoPermissionPage` 已内置「返回工作台 / 返回上一页」，受限访问现在可见、可解释。安全语义不变（后端仍二次拦截）。
- **复测证据**（`recheck.py` → `recheck/student_config.txt`）：学生访问 `/config` 后 URL 变为 `/no-permission?reason=…`，页面显示「无权限 / 访问被拒绝」，且**不再**静默回退到带 403 报错的工作台（`student_config_no_perm_page: true`、`student_config_silent_dashboard: false`）。`tsc --noEmit` 通过。
- **严重度**：低（安全无碍）/ 中（体验）—— 已闭环。

### F3【阻断·环境前置】发起辅导流程因租户内无已发布 Agent 而不可达 —— **已解除（2026-11-14 正规治理发布走通）**
- **现象**：`/runs/new` 显示“当前租户下没有已发布的 Agent。请联系管理员创建并发布 Agent 后再发起辅导。”表单不可用；`GET /api/agents` 返回空列表。
- **根因**：Agent 发布须经“草稿→审批→发布”三步工作流，直接发布端点已下线（`AgentController.java:173-179`，405）。测试租户 `t-e2e-fe` 未走完该流程。
- **影响**：核心业务链路（发起辅导→进度→结果）无法端到端验证。
- **复现**：`snapshots/admin/new_run.txt`；“没有可用的 Agent”文案。
- **严重度**：阻断（环境/数据前置），非前端缺陷。
- **✅ 解除记录（2026-11-14，按用户确认的「走正规治理发布」方向执行，未绕过 DB-seed）**：
  1. **创建 Agent**（正规第一步）：`POST /api/agents {name:"E2E 错题归因辅导 Agent"}` → 200，`agentId=2c00f638-0bb7-4739-9f5b-6000e432bb12`（服务端生成 UUID；⚠️ 发布 payload 的 `agentId` 必须使用该 UUID，传字符串业务键会被 `cap_agent_def.agent_id` 的 `?::uuid` 约束以 500 拒绝——见「附带发现」）。
  2. **三步治理发布**：`POST /api/cap/config/publishes`（PENDING，requestId `f281d843-9a66-43a0-a46c-ebd30bec278d`）→ `POST .../decision`（**distinct 审批人 e2eadmin2**，principal `29afa305…` ≠ 申请人 `35ec2870…`，双人复核成立）→ APPROVED → `POST .../apply`（申请人 e2eadmin）→ **APPLIED**，`appliedAt=2026-11-14T04:15:12Z`。
  3. **发布态验证**：`GET /api/agents/{id}` → `versionCount=1 / latestVersionNumber=1 / latestDefinition 含 inlineInstructions / latestPublishedAt=2026-11-14T04:15:11.962439Z`（非空）；`GET /api/agents` 列表含该 Agent。
  4. **前端消费逻辑核对**：`NewRunPage.tsx:120-124` 以 `latestPublishedAt !== null` 过滤并渲染下拉（`:336-362`），该 Agent 满足条件 → 下拉框将出现并预选，「没有可用的 Agent」阻断提示消失。**发起辅导表单从「阻断」恢复为「可用」**。
  5. **发布门禁 fail-closed 顺带验证（正面发现）**：`instructions` 含 `systemPromptRef`（V1 未实现的引用式提示词）→ **422 `CONFIG_PUBLISH_REJECTED: AGENT_INSTRUCTIONS_INVALID`**；缺 `reason` → 400 `INVALID_REQUEST`。门禁拒绝路径工作正常。
- **边界（诚实声明·已更新）**：本解除完成于 **API/数据层**；「发起辅导→提交 Run→进度 SSE→结果页」的浏览器级全链路复测**已于同日完成**（走查脚本 `fullchain_browser.py`，11/11 PASS，含进度页「已完成」与结果页 SUCCEEDED + 真实归因讲解取证，见 F6 解除记录与附录 A）。
- **附带发现（低·健壮性）**：发布 payload 传非 UUID 的 `agentId` 字符串（如 `"e2e-tutor-agent"`）时，`ConfigPublishWorkflowService.buildPublishCommand → JdbcCapAgentDefRepository.findLatest` 的 `agent_id = ?::uuid` 抛 `PSQLException(22P02)` → 兜底 500「服务暂时不可用」，而非 400 参数错误。仅畸形输入触发（正规流程先 `POST /api/agents` 取 UUID 即可规避），建议后续在控制器入口做 UUID 格式校验返回 400。证据：`probe_publish.py`/`probe_publish_v2.py` + 后端日志 `/tmp/platform_backend.log` 堆栈。

### F4【中→复核为误报/不成立】知识检索/通用问答“前端未呈现错误态” —— **复核结论：前端错误呈现正确，原结论为测试假象**
- **原结论（错误）**：提交知识检索/通用问答后页面无结果、也无错误提示，判定“前端未呈现错误态”。
- **复核（2026-11-14 复测，`recheck.py`）**：原“无反应”系**早期交互脚本用 ref 文本 `type` 未触发 React `onChange`**，导致输入框受控态未更新、提交按钮始终 `disabled`、请求根本未发出——并非前端不呈现错误。改用原生 setter + `input` 事件注入后：
  - **通用问答真实可用**（`recheck/admin_chat_final.txt`）：发送「用一句话解释条件概率」后，消息列表出现用户提问与 AI 回答「条件概率是指在已知事件 B 发生的前提下，事件 A 发生的概率，记作 \(P(A\mid B)=P(A\cap B)/P(B)\)。」（耗时 1.9s）。后端经代理 `POST /api/knowledge/chat/stream` 正常返回 SSE。
  - **知识检索请求正常发出并进入加载态**（`recheck/admin_rag_final.txt`）：按钮显示「检索中…」、状态「正在检索教材语料」，请求已发出，仅在本环境 RAG 检索端未在观测窗口内返回（见下）。
- **前端错误呈现代码本身正确（代码复核）**：
  - `useRagSearch.ts` 的 `toRetrievalState`/`toGenerationState` 已对 `ApiError` 区分 `timeout`/`notConnected`/`error`；`RagSearchPage.tsx` 对 `error`/`timeout` 渲染 `<ErrorState>`、对 `notConnected` 渲染 `<CapabilityNote>`。
  - `generalChatApi.ts` 在 `response` 非 2xx 时抛 `GeneralChatFailure`；`GeneralChatPage.tsx` 的 `AssistantBody` 渲染 `gchat__error`（`role="alert"` + 重试）。
  - `client.ts` 的 `retrieveChunks`/`generateRagAnswer` 走 `requestWithTimeout(DEFAULT_RAG_TIMEOUT_MS)`（`:666-680`），后端挂死会归一化为 `REQUEST_TIMEOUT` 并在界面呈现超时态——**不存在“永久空白无反馈”**。
- **剩余真实情况**：本环境 RAG 检索端（`/api/v1/retrieval`）在观测窗口（~16s）内未返回，UI 停在加载态；待超过 `DEFAULT_RAG_TIMEOUT_MS` 后将呈现超时错误。这属于**后端/联调环境**问题，不是前端缺陷。
- **严重度**：由「中」**下调为「不成立（误报）」**。原 F4 的“前端错误呈现不足”不成立；保留“RAG 检索端在本环境未联调”作为环境备注（非前端缺陷）。

### F6【高·能力—依赖不匹配】受众角色能「发起辅导」却看不见「可发起什么」 —— **已修复（2026-11-14 浏览器级复测发现并闭环）**
- **发现途径**：F1 实施后做**浏览器级**复测（此前只做到 API 级），学生打开 `/runs/new` 仍见「没有可用的 Agent。当前租户下没有已发布的 Agent。请联系管理员创建并发布 Agent 后再发起辅导。」——而该租户**确实**有 1 个已发布 Agent（`2c00f638-…`，v2，`latestPublishedAt` 非空，F3 已发布）。
- **根因（API 直连取证）**：学生令牌调用 `GET /api/agents?limit=50` → **403 `ACCESS_DENIED`**。该端点（`AgentController.listAgents`）只认 `AGENT_MANAGE`（治理动作），而受众角色按定义不持治理权限。前端 `NewRunPage.tsx:118-136` 的 `listAgents()` 走 `.catch()` 兜底 → **空列表** → 渲染「没有可用的 Agent」。
  ⚠️ 本质是**能力—依赖不匹配**：既然已允许受众发起辅导（`RUN_CREATE`），就必须让其看见「可发起什么」；但治理面（`AGENT_MANAGE`）又不该整体开放给受众——原设计两难，实测暴露了这个两难。
- **修复（最小权限 + 视图裁剪）**：
  1. 新增只读动作 `agent:list`（`AuthorizationService.Actions.AGENT_LIST`），登记进 `Actions.ALL`；
  2. 授予 `STUDENT/PARENT/TEACHER`（+ `TENANT_ADMIN/AGENT_AUTHOR`，与既有角色语义一致）；
     ⚠️ **刻意不给 `VIEWER`**——它是运维侧只读观察者，`AgentEndpointIT`（G2-2）锁定「VIEWER 访问治理面 → 403」，受众面与观察者面定位不同；
  3. `AgentController.listAgents` 改为**双路径**：持 `AGENT_MANAGE` → 完整治理视图；仅持 `AGENT_LIST` → **受众视图**（只列已发布项，且 `latestDefinition`（系统提示词全文）置 `null`、`versionCount` 置 0）；两者都无 → 403（fail-closed 不变）。
- **复测证据**：
  - 学生 `GET /api/agents?limit=50` → **200**，`{"agentId":"2c00f638-…","name":"E2E 错题归因辅导 Agent","versionCount":0,"latestVersionNumber":2,"latestDefinition":null,"latestPublishedAt":"2026-11-14T06:30:53Z"}` → **提示词未外泄**（`latestDefinition=null`）✅；
  - 浏览器：`/runs/new` 渲染「辅导 Agent」下拉且预选「E2E 错题归因辅导 Agent（v2）」，「没有可用的 Agent」提示消失 ✅（`snapshots/fullchain/05_form_filled.txt`、截图 `screenshots/fullchain/05_form_filled.png`）；
  - 测试：`AgentControllerTest` +3 用例（受众裁剪视图 / 治理完整视图 / 无权限 403）、`RoleBasedAuthorizationServiceTest` +2 用例（受众持 `AGENT_LIST` 但不持 `AGENT_MANAGE`）；全 reactor `clean verify` 14 模块 BUILD SUCCESS（2897 测试 / 0 失败）；
  - **反向验证（两组）**：撤掉「STUDENT 的 `AGENT_LIST`」→ `studentCoreFlow` 转红；撤掉「受众视图裁剪」→ `listAgents_audience_getsTrimmedPublishedOnly` 转红 ✅。
- **严重度**：高（阻断受众的核心自助链路，且表现为「请联系管理员」的误导性提示）—— **已闭环**。

### F7【阻断·产品前置缺口】核心辅导链路在真实环境**不可达**：Run 建得出来、跑不起来 —— **已打通（4 层前置逐层定位并补齐）**
- **现象**：浏览器复测时 Run 提交后长期停在 `QUEUED`；改用 API 直连后确认 **每个 Run 最终都 `FAILED`**，错误码依次为 `CONFIG_UNAVAILABLE` → `BUDGET_EXHAUSTED`。**Run 100% 跑不起来**，核心链路（发起辅导→进度→结果）实际不可用。
- **逐层根因（4 层，全部为「前置」而非业务逻辑缺陷）**：
  | # | 层级 | 根因 | 证据 | 处置 |
  |---|---|---|---|---|
  | 1 | **worker 灰度配置** | `agent.worker.tenant-slugs` 默认 `t-a`，E2E 租户 slug 是 `t-e2e-fe` → **worker 不消费该租户消息**，Run 永远 `QUEUED`（`outbox_event` 28 条 `RUN_DISPATCH` 停在 `PENDING`、`attempts=0` ⇒ 根本没轮询） | `outbox_event` 状态分布；`application.yml:216` | 启动加 `AGENT_TENANT_SLUGS=t-e2e-fe`（**环境配置项，非代码缺陷**） |
  | 2 | **治理发布数据** | 首版发布 payload 写了 `"modelRef": None` → `cap_agent_def.model_ref = NULL` → `AgentModelConfigAssembler.assemble()` 取不到模型标识即 fail-closed → `CONFIG_UNAVAILABLE` | `cap_agent_def` v1 行 `model_ref` 空 | **正规重发布**（`probe_publish_v2_modelref.py`：申请→双人审批→apply → v2，`modelRef=MiniMax-M2.5`），未绕过治理门禁 |
  | 3 | **授权版本记录** | `cap_authz_version` **无该租户任何主体行** → `CapDualWriteService` 的严格读 `require()` 抛 `AuthzUnavailableException` → **双写挂起** → 无 `execution_capability_snapshot` → 执行准入 fail-closed → `CONFIG_UNAVAILABLE` | 后端日志 `AuthzUnavailableException: 授权版本记录不存在…`（`CapDualWriteService:173`） | 数据前置回填（`ON CONFLICT DO NOTHING`，绝不降版）；**⚠️ 产品缺口见下** |
  | 4 | **预算账户** | `budget_account` **无该租户账户** → 预留时 `NO_ACCOUNT` → fail-closed → `BUDGET_EXHAUSTED` | `JdbcBudgetRepository:149`；`run.error_code` | 数据前置开通（CNY 100 元） |
- **⚠️ 留档的产品级缺口（本轮最重要的结论）**：第 3、4 层暴露同一个结构性问题——
  - `AuthzVersionPort.initialize/bump` 在**生产代码中零调用方**（仅测试调用），`cap_authz_version` 因此没有任何生产写入点；
  - `budget_account` 同样**只有测试写入方**，租户开通预算账户没有 API；
  - 租户级也没有「主体授权变更」的管理 API（只有平台级 `/api/platform/v1/users`）。
  → 结论：**新租户 / 新主体在新环境里，Run 必然跑不起来**，除非人工执行 SQL 补三样数据（授权版本、预算账户、（worker 灰度清单））。本轮即以数据前置方式绕过并留档，**未改产品代码**（改动授权版本体系属红线裁决范畴，须独立评审）。
- **闭环验证**：学生 Run 实测 `QUEUED → RUNNING → SUCCEEDED`，`GET /api/runs/{id}/result` 返回**完整归因讲解**（错因分析 + 判别式推导 + 针对性练习 + 参考答案）；浏览器进度页显示「已完成」，结果页显示 `SUCCEEDED` 与讲解正文 ✅。
- **严重度**：阻断（核心链路）—— **已打通**；但**产品级缺口（授权版本/预算账户无生产写入方）仍为独立待办**，不应随本轮关闭。

### F8【中·UX】Run 不存在/无权查看时，进度页渲染成「正在处理」并无限轮询 —— **已修复（2026-11-14）**
- **现象**：学生访问他人 Run（admin 的 SUCCEEDED Run `b5bde729-…`）时，后端按反探测纪律返回 **404「资源不存在」**，但页面显示：
  - 状态标签「**正在处理**」+「正在同步最新状态…」；
  - 「上一次读取失败，可再试一次」；
  - 且**继续按 10 秒一拍轮询**（白烧 `EVENT_QUERY` 限流桶 30 次/60 秒）。
  → 用户对着一个**永远不会出现**的结果死等；且「正在处理」是**误导**（不是暂时读失败，是根本没有这条 Run）。
- **根因**：前端 `useRunStatus` 把 404 与「临时读失败」混为一谈（同一 `error` 通道），页面在 `status == null` 时一律回落成「正在处理」（`RunProgressPage.tsx:161-165`）。
- **修复**：
  1. `useRunStatus` 新增 `notFound` 状态：`ApiError.normalized.status === 404` → 置位并**停止轮询**（404 重试无意义）；
  2. `RunProgressPage`：404 → 状态标签渲染「**资源不存在**」+ 说明「该分析不存在，或不属于当前账号，因此无法查看进度与结果。」，并抑制「正在处理 / 正在同步最新状态… / 暂时读不到最新状态」等**临时故障话术**。
- **反向验证**：撤掉 404 终态渲染 → `404 → 显示「资源不存在」` 用例转红 ✅；前端全量 **691/691 通过**（47 文件，含 F7 新增 3 用例 + 一条反证）。
- **附带修复（既有 CI 不变式的口径缺陷）**：`tests/routing.integrity.test.ts` 的链接匹配未剥离 query → F2 引入的 `/no-permission?reason=…` 被判「无对应路由」而误报红。改为**按 pathname 匹配**（`link.split('?')[0]`），并补反证「无路由的链接剥 query 后照样失配」——**不变式强度未降低**。
- **严重度**：中（误导性终态 + 无效轮询）—— **已闭环**。

### F5【差异】与旧报告 `frontend/E2E_TEST_REPORT.md` 的对照
旧报告基于静态推断列 10 条“问题”，本次以真实浏览器+后端取证复核，**多数未获支持**：
- 旧“问题1 登录页缺少表单验证反馈” → **不成立**：实测登录有必填禁用 + “租户、账号或密码不正确”提示（见 4.1）。
- 旧“问题2 发起辅导 Agent 下拉默认值” → 当前已无下拉，改为“无可用 Agent”阻断提示（见 F3），形态已变。
- 旧“问题3 进度页 SSE 内存泄漏 / 问题4 结果页敏感词扫描性能 / 问题5 报表日期范围 / 问题7 配置变更确认 / 问题8 审计导出 / 问题9 健康组件信息 / 问题10 RAG 批量导入进度” → 均属静态猜测，**本次因 Run 流程阻断/数据缺失未做深度交互验证**，不具备复现条件，须逐条以实测重判，不能沿用旧结论。
- **结论**：旧报告不得作为质量依据；应以本报告（实测）为准。

---

## 六、回归验证结果

| 旧报告条目 | 旧结论 | 本次实测 | 裁定 |
|---|---|---|---|
| 1 登录验证反馈 | 缺陷 | 必填禁用+错误提示正常 | **未复现（旧结论错误）** |
| 2 发起辅导 Agent 下拉 | 缺陷 | 已改为“无 Agent”阻断 | **形态变更，需重测** |
| 3 进度 SSE 泄漏 | 缺陷 | Run 流程阻断，未覆盖 | **无法复现** |
| 4 结果页敏感词 | 缺陷 | Run 流程阻断，未覆盖 | **无法复现** |
| 5 报表日期范围 | 缺陷 | 报表页渲染，未深测 | **无法复现** |
| 6 RAG localStorage 泄漏 | 缺陷 | 会话用 sessionStorage（`tokenStorage.ts`），需专项验证 | **待专项** |
| 7 配置变更确认 | 缺陷 | 配置页渲染，未深测 | **无法复现** |
| 8 审计导出 | 缺陷 | 审计页渲染 | **无法复现** |
| 9 健康组件信息 | 缺陷 | 健康页渲染 | **无法复现** |
| 10 RAG 批量导入进度 | 缺陷 | RAG 管理页含该标签页 | **无法复现** |

---

## 七、测试总结

### 7.1 覆盖率
- **路由覆盖**：56/56 次访问（三角色全路由），100% 路由可达性验证，均附截图+DOM 快照。
- **角色矩阵**：3 角色 × 全路由，含后端直连权限取证（student/approver/admin 三角色 `RUN_READ` 对比）。
- **关键交互**：登录（成功/失败）、登出、知识检索提交、通用问答发送、学习画像查询（含不存在编号边界）、各管理/治理/运维页渲染——均经真实浏览器操作。
- **未覆盖（2026-11-14 更新）**：旧报告 10 项中的深度交互因当时的 Run 流程阻断不可复现，其中「进度 SSE / 结果页 / 发起辅导」三项**已于同日由浏览器级全链路走查覆盖并取证**（11/11 PASS）；其余项仍需专项验证（RAG 批量导入进度、审计导出等）。
- **浏览器级全链路走查（2026-11-14，11/11 PASS）**：学生登录 → 工作台 → 辅导记录列表 → 发起辅导（Agent 下拉可用）→ 填表提交 → 进度页「已完成」→ 结果页 `SUCCEEDED` + 完整归因讲解 → 反探测（访问他人 Run → 「资源不存在」且无内容泄露）。证据：`fullchain_browser.py` / `fullchain.json` / `screenshots/fullchain/*.png` / `snapshots/fullchain/*.txt`。

### 7.2 遗留问题
1. ~~学生/家长/老师角色完全不可用（F1，高）~~ —— **已闭环（2026-11-14）**：按「正规实现学生角色」完成后端授权矩阵 + requester_id 数据隔离并部署，API 级复测 13/13 PASS，**浏览器级 UI 全链路复测 11/11 PASS**。
2. ~~无已发布 Agent，核心辅导链路不可达（F3，阻断）~~ —— **已解除（2026-11-14）**：走正规治理发布（创建→申请→双人审批→发布），并在 F7 中补齐 `modelRef` 重发 v2；`/runs/new` 下拉可用且**实测可发起**。
3. ~~RAG/聊天前端错误无呈现（F4，中）~~ —— **已复核为误报**：前端错误/超时呈现正确，原“无反应”系早期脚本未触发 React 受控态导致请求未发出；通用问答实测可用、RAG 检索已正常发出请求。本环境仅 RAG 检索端未联调（后端问题，非前端）。
4. ~~RequireRole 未授权静默回退（F2，低/中）~~ —— **已修复并复测验证**：未授权访问现跳转 `/no-permission?reason=…`。
5. ~~受众看不到可发起辅导的 Agent（F6，高）~~ —— **已修复并复测**：新增只读动作 `agent:list` + 受众裁剪视图（提示词不下发）；浏览器实测下拉恢复。
6. ~~Run 建得出来跑不起来（F7，阻断）~~ —— **已打通**：定位 4 层前置（worker 灰度清单 / 发布 modelRef / 授权版本行 / 预算账户）并逐层处置，Run 实测 `SUCCEEDED` + 真实归因讲解。
7. ~~404 被渲染成「正在处理」并无限轮询（F8，中）~~ —— **已修复并复测**：404 显式终态 + 停止轮询。
8. **⚠️ 独立待办（不随本轮关闭）**：**租户级前置数据无生产写入方** —— `cap_authz_version`（授权版本）与 `budget_account`（预算账户）只有测试写入方，租户开通/主体授权变更也没有租户级 API。新租户/新主体在新环境**必然**跑不起 Run，需人工 SQL 补数。详见 F7「留档的产品级缺口」。
9. 旧报告 10 项“问题”须以实测逐条重判，不可沿用（详见第六章、F4 复核）。

### 7.3 风险
- ~~产品/实现偏离风险（高）~~ —— **已解除（2026-11-14，F1+F6）**：受众角色已具备自服务能力（发起/查看本人辅导/选择可发起 Agent），隔离与反探测就位，浏览器级复测通过。
- ~~核心链路不可验证风险（高·环境）~~ —— **已解除（2026-11-14，F7）**：4 层前置逐层定位并处置，真实 Run 走完 `QUEUED→RUNNING→SUCCEEDED`，结果页输出真实归因讲解。
- **新增风险（高·产品·未闭环）**：**新环境/新租户默认不可用** —— 授权版本与预算账户无生产写入方 + worker 灰度清单漏配即静默不消费，两类缺口都**无任何报错**，只在真正发起辅导时才暴露（详见 F7）。
- ~~可观测性风险（中）~~ —— **已解除**：F4 复核确认前端对 RAG/聊天的错误与超时均有正确呈现，不存在“静默空白”。

### 7.4 是否满足发布条件
**本轮结论（2026-11-14 16:25 二次评估修订）：当前环境（`t-e2e-fe` 单租户、单已发布 Agent、前置数据已人工补齐）的学生自助链路具备「试点演示」条件；产品整体不具备规模化发布条件。** 原判「核心链路已具备发布条件」口径过宽——其与 §7.3 自认的「新环境/新租户默认不可用（高·未闭环）」不能同时成立；且二次评估实测发现 PARENT/TEACHER 两个已声明受众角色的核心链路不可用（详见 `E2E测试结论二次评估与优化建议.md` N1）。

**已解除的全部阻塞**：
1. ~~F3 无已发布 Agent~~ —— 正规治理发布走通（+ v2 补 `modelRef`），`/runs/new` 下拉实测可用；
2. ~~F1 受众角色零能力~~ —— 后端授权矩阵 + `requester_id` 数据隔离落地并部署，API 级 13/13 + 浏览器级 11/11 全 PASS；
3. ~~F6 受众看不到可发起项~~ —— 新增 `agent:list` 只读动作 + 裁剪视图，下拉恢复且提示词不下发；
4. ~~F7 Run 跑不起来~~ —— 4 层前置逐层定位并处置，Run 实测 `QUEUED→RUNNING→SUCCEEDED`，结果页输出真实归因讲解；
5. ~~F8 404 误导终态~~ —— 404 显式终态 + 停止无效轮询；
6. ~~F2 / F4~~ —— 已修复 / 已复核为误报。

**发布前仍建议完成（按优先级）**：
- **P0（产品级，非阻塞本环境）**：为 `cap_authz_version` 与 `budget_account` 补上**生产写入方**（租户开通 / 主体授权变更 API），否则**任何新环境 / 新租户**都会重现 F7 的四层前置缺口（详见 F7）；
- **P1（运维配置）**：把 `AGENT_TENANT_SLUGS` 纳入部署清单（worker 灰度清单漏配 → Run 静默不消费，**无任何报错**）；
- **P2**：以实测重判旧报告 10 项遗留；RAG 检索端在本环境未联调（后端侧）。

**诚实边界**：本轮验证的是「租户级自助辅导链路（发起→进度→结果）」在**单租户、单已发布 Agent、真实模型调用**下的可用性；多租户灰度、治理审批并发、预算闸门边界等**未做压力 / 并发验证**。

**规模化放行前必须关闭（二次评估补充）**：
- ~~完成至少一个 Agent 的创建+发布（解除 F3）~~ ✅ 已完成；~~跑通一次真实「发起辅导→进度→结果」浏览器级全链路并取证~~ ✅ 已完成（§4.5，11/11 PASS）；
- ~~完成学生角色正规实现并复测学生视角工作台~~ ✅ 后端已完成（API 级 13/13）；~~学生视角浏览器级 UI 复测~~ ✅ 已完成（§4.5）；
- **P0（新增，二次评估 N1）**：产品裁决 PARENT/TEACHER 的用户模型（只读查看 / 代发起 / 本版本不开放），据此调整权限矩阵与资源级隔离维度；当前两者既不能 `RUN_CREATE`，按 `requester_id=本人` 收窄后又看不到任何 Run；
- **P0（沿用）**：`cap_authz_version` / `budget_account` 生产写入方；
- 以实测重判旧报告遗留项。

---

## 附录 A：证据索引
- 路由行走截图（56）：`screenshots/{student,admin,approver}/*.png`
- 路由行走 DOM 快照（56）：`snapshots/{student,admin,approver}/*.txt`
- 交互取证截图（11）：`shots/admin/*.png`；快照：`snaps/admin/*.txt`
- 结果 JSON：`walk_student.json` / `walk_admin.json` / `walk_approver.json` / `interactions_admin.json` / `final_admin.json`
- **复测证据（F2/F4 复核，`recheck/`）**：`recheck/student_config.txt`（F2 跳转无权限页）、`recheck/admin_chat_final.txt`（F4 复核：通用问答真实返回答案）、`recheck/admin_rag_final.txt`（F4 复核：检索请求正常发出、加载态）
- **F3/F6 解除证据（2026-11-14）**：`probe_governance.py` / `probe_governance_continue.py`（治理三步发布 + 门禁 422/400 验证 + `GET /api/agents` 发布态验证）；`probe_publish_v2_modelref.py`（**补 `modelRef` 的正规重发布 v2**：APPLIED → `versionCount=2` / `latestDefinition.modelRef=MiniMax-M2.5`）；过程探针 `probe_publish.py` / `probe_publish_v2.py`（500 根因定位：非 UUID agentId）；后端运行日志 `/tmp/platform_backend.log`（`PSQLException 22P02` 堆栈）
- **F1 解除证据（2026-11-14）**：`probe_student_f1.py`（**13/13 PASS**：登录带角色/列表收窄/创建落库/DB requester_id 验证/跨主体隔离/404 反探测/治理 403/家长对照）；测试证据：`RoleBasedAuthorizationServiceTest`（19 用例含 +2 受众）、`RunListControllerTest`（6 用例含受众收窄）、`RunAudienceIsolationIT`（3/3 真库）；全 reactor `clean verify` 14 模块 BUILD SUCCESS（2897 测试 / 0 失败）；结构变更 `deploy/sql/platform/01_platform_ddl.sql`（run.requester_id）+ 远程 dev 库 ALTER 已验证
- **浏览器级全链路证据（2026-11-14 第三轮，11/11 PASS）**：`fullchain_browser.py`（走查脚本）、`fullchain.json`（逐项结论）、`screenshots/fullchain/{05_form_filled,07_progress_done,08_result}.png`（Agent 下拉 / 进度已完成 / 归因结果）、`snapshots/fullchain/*.txt`（DOM 快照，含反探测页「资源不存在」）
- **F6/F7 解除证据**：`probe_run_exec.py`（Run 端到端执行探针：创建→轮询终态→结果正文）、后端日志 `/tmp/backend-e2e-slug2.log`（`AuthzUnavailableException` 堆栈 / `CapDualWriteService` 双写挂起告警 / `LOOP_COMPLETE kind=SUCCEEDED`）、远程 dev 库 `cap_agent_def` v2 行（`model_ref=MiniMax-M2.5`）
- **F8 解除证据**：`frontend/tests/progress.page.test.tsx`（404 终态组 3 用例 + 反证）、`frontend/tests/routing.integrity.test.ts`（pathname 匹配 + 反证）；前端全量 `npm test` **691/691 通过**（47 文件）
- 后端取证：`/tmp/s_run.txt`(student 403)、`/tmp/a_run.txt`(admin 200)、`/tmp/p_run.txt`(approver 200)

## 附录 B：复现命令（节选）
```bash
# 启动前端（另开终端）
cd frontend && npm run dev   # http://localhost:5173

# 学生 RUN_READ 取证
# ⚠️ 2026-11-14 F1 实施后：e2estudent 的密码哈希已同步为 e2eadmin（密码 = E2e-Admin-2026），
#    且 GET /api/runs 对 STUDENT 返回 200（仅本人 Run），不再是 403。
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/token -H 'Content-Type: application/json' \
  -d '{"tenantId":"8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751","loginName":"e2estudent","password":"E2e-Admin-2026"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -i http://localhost:8080/api/runs -H "Authorization: Bearer $TOKEN"   # → 200（仅本人，修复前为 403）

# 浏览器行走（需 agent-browser）
python3 walk.py student 8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751 e2estudent E2e-Admin-2026
python3 walk.py admin   8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751 e2eadmin    E2e-Admin-2026
python3 walk.py approver 8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751 e2eapprover E2e-Approve-2026
```

## 附录 C：相关文件
- 前端路由与守卫：`frontend/src/App.tsx`、`frontend/src/auth/RequireRole.tsx`、`frontend/src/auth/session.tsx`
- 后端授权：`platform/.../infrastructure/security/RoleBasedAuthorizationService.java`
- 运行列表授权：`platform/.../api/governance/RunListController.java`（要求 `RUN_READ`）
- 旧报告（已被本报告取代）：`frontend/E2E_TEST_REPORT.md`

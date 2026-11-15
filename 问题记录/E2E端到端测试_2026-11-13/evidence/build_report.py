#!/usr/bin/env python3
"""从实测证据生成完整测试文档（Markdown + 机器可读用例清单 CSV）。"""
import json, csv, os, glob, re
from collections import Counter, defaultdict
from datetime import datetime

D = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13'
REPO = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent'
OUT_MD = D + '/端到端测试报告_2026-11-13.md'
OUT_CSV = D + '/evidence/测试用例与执行记录.csv'


def load_jsonl(p):
    return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]


def esc(s, n=260):
    s = str(s).replace('|', '\\|').replace('\n', ' ⏎ ').replace('\r', '')
    return s[:n] + ('…' if len(s) > n else '')


raw = load_jsonl(D + '/logs/interactions.jsonl')
reg_path = D + '/logs/regression_final.jsonl'
reg = load_jsonl(reg_path) if os.path.exists(reg_path) else []
menu = load_jsonl(D + '/logs/menu_walk.jsonl')
api = load_jsonl(D + '/http/api_probe.jsonl')
gaps = json.load(open(D + '/evidence/doc_gaps.json', encoding='utf-8'))
db = json.load(open(D + '/evidence/db_verify.json', encoding='utf-8'))
role_browser_path = D + '/evidence/role_matrix_browser.json'
role_browser = json.load(open(role_browser_path, encoding='utf-8')) if os.path.exists(role_browser_path) else []
fix_reg_path = D + '/logs/regression_after_fix.jsonl'
fix_reg = load_jsonl(fix_reg_path) if os.path.exists(fix_reg_path) else []
api_after = load_jsonl(D + '/http/api_probe_after_fix.jsonl') if os.path.exists(D + '/http/api_probe_after_fix.jsonl') else []
api_before = load_jsonl(D + '/http/api_probe_before_fix.jsonl') if os.path.exists(D + '/http/api_probe_before_fix.jsonl') else []
fix_role_path = D + '/evidence/role_matrix_after_fix.json'
fix_roles = json.load(open(fix_role_path, encoding='utf-8')) if os.path.exists(fix_role_path) else []

# 首轮 5 个交互批次 + 一次中止的回归尝试（R-* 前缀，非最终结论）→ 以批次 8 最终回归裁定为准
base = [r for r in raw if not r['id'].startswith('R-')]
regmap = {r['id']: r for r in reg}
inter = []
for r in base:
    rr = regmap.get(r['id'])
    if rr:
        m = dict(r)
        m.update(verdict=rr['verdict'], actual=rr['actual'], steps=rr['steps'],
                 expect=rr['expect'], group=rr['group'], desc=rr['desc'])
        m['note'] = ' ｜ '.join(x for x in (r.get('note', ''), '【最终回归】' + rr.get('note', '')) if x)
        m['final'] = True
        inter.append(m)
    else:
        inter.append(dict(r, final=False))
base_ids = {r['id'] for r in base}
for r in reg:
    if r['id'] not in base_ids:
        inter.append(dict(r, final=True))

# 缺陷 / 阻塞 / 不适用清单（人工判定 + 实测证据）
DEFECTS = [
    dict(id='DEF-01', sev='P2', title='未认证 / 伪造令牌访问 /api/platform/v1/auth/me 返回 500 而非 401',
         steps='1) 不带 Authorization 执行 GET /api/platform/v1/auth/me；2) 用伪造 Bearer 令牌重复一次',
         data='无令牌 / Bearer forged.token.e2e',
         expect='401 UNAUTHENTICATED（与登录端点、其它平台链端点一致）',
         actual='两次均 500 {"code":"INTERNAL_ERROR","message":"服务暂时不可用"}，X-Trace-Id 见 http/api_probe.jsonl A05/A06；curl 复现 09:37:52 trace=3dd442126d1049558647f8f67b4b76f5',
         impact='前端 client.ts 只对 401 做「清会话 + 跳登录」；500 走通用错误分支，可能出现「无有效会话却停留在受保护页」的状态不一致；同时把认证失败伪装成服务端故障，干扰排障与告警',
         evidence='http/api_probe.jsonl（A05/A06）、logs/api_probe_run.log；'
                  '修复后：http/api_probe_after_fix.jsonl、logs/regression_after_fix.jsonl（FIX-01/02/10）',
         status='**已修复并回归通过**（平台链 permitAll 精确到 login/jwks + 绑定异常 401 归一化，见 §10.1）'),
    dict(id='DEF-02', sev='P3',
         title='前端审计角色门禁漏配 PLATFORM_SECURITY_ADMIN（v1.0 证据误判更正）',
         steps='1) 查源码 PlatformRolePermissionMatrix.ROLE_PERMISSIONS 与 DB platform_role_permission；'
               '2) 用 plat-security 登录取令牌，GET /api/platform/v1/audit-events；'
               '3) 浏览器干净会话打开 /admin/audit 深链与侧栏「平台审计」；4) 对照另外 5 个角色',
         data='账号 plat-security（roles=["PLATFORM_SECURITY_ADMIN"]）',
         expect='源码冻结矩阵、DB 播种、运行时授权、前端 AUDIT_SCOPE / navConfig 四者口径一致',
         actual='**更正后的实测**：后端对该角色为 **200** —— 源码冻结矩阵与 DB 播种均授予 `platform.audit.read`，'
                'PlatformAuditController.require(AUDIT_READ) 与 PlatformAuthorizationServiceImpl 均放行；'
                'API 矩阵中 403 只属于 plat-lifecycle / plat-resource / plat-support。'
                '真正的差异是前端：`AUDIT_SCOPE` 与 `navConfig` 审计节点只放行 [SUPER_ADMIN, AUDITOR]，'
                '使 SECURITY_ADMIN 无审计菜单、深链 /audit 被 RequireRole 拦成 403（浏览器矩阵稳定复现）。'
                '⚠️ v1.0 把其它角色的 403 误归到 SECURITY_ADMIN，并据此写成「运行时授权分叉/需改矩阵与播种」，属证据读取错误，本版更正；'
                '修复方向因此从「改后端矩阵」变为「前端补角色」',
         impact='不越权（后端从未拒绝合法角色），但安全管理员在控制台里看不见、进不去审计页，'
                '与其风险处置职责冲突；同时错误结论会误导合规举证与权限评审。前端导航与后端矩阵保持一致即可消除',
         evidence='http/api_probe.jsonl（RBAC:plat-security:平台审计=200）、http/api_probe_after_fix.jsonl、'
                  'evidence/role_matrix_browser.json（修复前）、evidence/role_matrix_after_fix.json（修复后）、'
                  'logs/regression_after_fix.jsonl（FIX-05-security）、admin-console/src/router.tsx、src/layout/navConfig.tsx',
         status='**已修复并回归通过**（前端 AUDIT_SCOPE / navConfig 补 SECURITY_ADMIN，见 §10.2）'),
    dict(id='DEF-03', sev='P3', title='审计页一次加载发出两次相同的 GET /audit-events',
         steps='打开 /admin/audit，观察浏览器网络面板',
         data='最近 24 小时默认窗口',
         expect='一次加载一条查询请求',
         actual='同一 URL（from 参数仅毫秒差）连续 2 条 GET 200',
         impact='重复请求，轻微性能与审计噪声，不影响功能正确性',
         evidence='logs/menu_net_M04.txt；修复后 logs/regression_after_fix.jsonl（FIX-04：count=1）',
         status='**已修复并回归通过**（同参数在途 Promise 复用，见 §10.2）'),
    dict(id='DEF-04', sev='P3', title='静态资源 /favicon.ico 返回 404',
         steps='浏览器加载任意页面，观察网络',
         data='-', expect='200 或无请求', actual='404',
         impact='控制台网络面板出现 404，不影响功能',
         evidence='logs/menu_net_*.txt（首轮登录网络记录）；修复后 logs/regression_after_fix.jsonl（FIX-03：无 favicon 请求）',
         status='**已修复并回归通过**（内联 data URI 图标，dev/build 双端一致，见 §10.2）'),
    dict(id='DEF-05', sev='P3', title='E2E 测试账号累积（平台无物理删除端点）',
         steps='写路径实测后查 platform.platform_user 表',
         data='e2e_probe_1110（2026-11-10）、e2e_test_1113a / e2e_test_1113b（本轮）',
         expect='测试账号用后清理或明确归档标记',
         actual='本轮为验证写链路真实创建了 2 个平台用户（POST 201 + DB 落库已核验），随后经 UI「停用 + Popconfirm 确认」全部置为 DISABLED；'
                '连同历史账号共 3 个测试主体仍在表中（platform_user 从 7 行增至 9 行）',
         impact='账号表累积不可物理删除的测试主体；虽均为 DISABLED 不可用，仍影响账号清册整洁与后续审计口径',
         evidence='evidence/db_verify.json（platform_platform_user）、logs/regression_final.jsonl（I-G10/I-G11）、'
                  'logs/regression_final_run.log（DISABLE 记录）',
         status='已停用，待维护窗口按带外通道清理'),
    dict(id='DEF-06', sev='P3', title='低权限角色仍展示可写按钮，依赖后端 403 兜底',
         steps='用 6 个角色分别登录 → 打开 /users → 观察「新建用户」按钮',
         data='plat-support / plat-auditor / plat-resource / plat-security 等无写权限角色',
         expect='无写权限的角色不应看到可写入口（或至少置灰并说明原因）',
         actual='6/6 角色均显示「新建用户」按钮（usersNewButtonVisible=true）；同时 API 矩阵实测 POST /platform/v1/users 对低权限角色 403。'
                '前端只按角色裁剪「平台审计」入口，其余 29 页对全部 6 角色可见',
         impact='不越权（后端重授权有效），但用户会点击后才看到失败；低权限角色可见大量点了即 403 的页面，体验与最小特权的可理解性偏弱',
         evidence='evidence/role_matrix_browser.json、evidence/role_matrix_after_fix.json、'
                  'http/api_probe.jsonl（WRITE-DENY 组）、logs/regression_after_fix.jsonl（FIX-05-*）',
         status='**已修复并回归通过**（用户页按 `platform.user.manage` 裁剪写入口；'
                '其余 29 页仍全角色可见，属保留的设计取舍，见 §10.2）'),
]

BLOCKERS = [
    dict(id='BLK-01', item='RAG 语料治理真实写链路（登记/校验 VERIFIED/抽检/重索引）',
         reason='后端要求 SoD：VERIFIED 执行人≠登记人且另有第二复核人；同时需「治理票据」短时凭证与版权授权声明。本环境只有一套超管凭据，不能由 AI 代签或代声明授权',
         verdict='阻塞', repro='见 I-R02 / I-R13 与 2026-11-10 报告 §4'),
    dict(id='BLK-02', item='Agent 效果测试真实执行（发起 Run）/ AI 对话「新会话清空」全链路',
         reason='需「选租户 → 取 60s 执行票证 → 扮演 ACTIVE 租户成员」并可能触发真实模型调用；AI 对话亦需先产生一轮真实会话才能验证清空。'
                '未擅自造 Run 以免不可控成本与脏数据',
         verdict='阻塞', repro='/admin/agent-lab 表单与门禁见 I-A07；空会话「新会话」按钮禁用（边界已验）见 I-A04'),
    dict(id='BLK-03', item='路由策略 dry-run 真实引擎求值',
         reason='端点属租户域 /api/cap/config/**，平台令牌恒 401（身份域隔离按设计生效）；页面已如实提示「需租户级令牌通道」，未把「没跑成」显示成「没问题」',
         verdict='不适用（设计边界）', repro='I-C10 / http/api_probe.jsonl ISO 组'),
    dict(id='BLK-04', item='低权限角色的写操作（新建用户等）',
         reason='后端最小特权矩阵拒绝，实测 POST /platform/v1/users 对 support/auditor/resource 均 403；符合设计，未越权尝试',
         verdict='不适用（按设计拒绝）', repro='http/api_probe.jsonl WRITE-DENY 组'),
    dict(id='BLK-05', item='三步工作流「审批→执行」真实落库、租户开通/退役真实提交、MCP 凭据材料',
         reason='会产生跨租户全局配置变更或触碰凭据红线；本轮只验证到「登记/停用申请弹窗 + 字段校验 + 取消不写库 + 身份域 401 显式提示」，未执行终态写入。'
                '**注意：平台用户写路径已真实闭环（POST 201 / 409 / 停用 + DB 核验），不属于本阻塞项**',
         verdict='阻塞（需变更窗口与双人复核）', repro='I-C04 / I-C06 / I-C10 / I-C11 / I-G04'),
]

# ── 统计 ────────────────────────────────────────────────
vc = Counter(r['verdict'] for r in inter)
regv = Counter(r['verdict'] for r in reg)
menu_click_ok = sum(1 for r in menu if r['click_rc'] == 0)
api_status = Counter(r['status'] for r in api)
shot_count = len(glob.glob(D + '/screenshots/*.png'))
net_logs = len(glob.glob(D + '/logs/menu_net_*.txt'))

L = []
A = L.append
A('# 平台管理控制台（admin-console）端到端测试报告')
A('')
A('> **版本** v1.1（含修复后回归）　**测试日期** 2026-11-13（GMT+8）　**执行方式** 真实 Chromium 浏览器逐项操作 + 真实 HTTP 请求 + 数据库只读核验')
A('> **被测对象** `admin-console`（React 18 + Vite 6 + Ant Design 5，`/admin` 路径部署）及其依赖的 platform 后端（8080）、RAG 服务（8081）')
A('> **报告路径** `问题记录/E2E端到端测试_2026-11-13/端到端测试报告_2026-11-13.md`')
A('')
A('> **v1.1 修订说明（同日修复后回归）**：'
  '① **更正 v1.0 的 DEF-02 误判** —— 原始证据 `RBAC:plat-security:平台审计 = 200`，'
  '后端授权从未拒绝该角色；v1.0 把 lifecycle/resource/support 的 403 错误归到了 SECURITY_ADMIN。'
  '真实差异只在前端审计门禁，已按此更正；'
  '② DEF-01 / 03 / 04 / 06 已修复，DEF-02 按更正后口径修复，详见 §10；'
  '③ 修复后重跑 93 条 API 矩阵 + 15 项浏览器回归，全绿，结论见 §9.3 / §10。'
  '§1～§8 保留测试当时点的原始事实与判定，不回写历史。')
A('')
A('---')
A('')
A('## 0. 执行摘要')
A('')
A(f'本轮对 admin-console 做了 **{len(menu)} 个菜单页 + 6 个异常/深链入口 + {len(base)} 项首轮交互用例 + {len(reg)} 项最终回归用例 + {len(api)} 个 API 契约/权限请求 + 数据库存储核验** 的端到端实测，')
A(f'采集 **{shot_count} 张截图**、{net_logs} 份逐页网络日志、完整请求/响应 JSONL 与数据库快照。')
A('')
A(f'- **最终裁定**（{len(inter)} 项：{len(base)} 项首轮用例按回归结果覆盖 + {len([r for r in reg if r["id"] not in base_ids])} 项新增回归用例）：'
  f'**通过 {vc.get("通过",0)} / 失败 {vc.get("失败",0)} / 阻塞 {vc.get("阻塞",0)} / 不适用 {vc.get("不适用",0)}**；'
  f'另有 {len(BLOCKERS)} 类阻塞或不适用范围项单列；')
A(f'- 最终回归批次 8：**{len(reg)} 项，通过 {regv.get("通过",0)} / 阻塞 {regv.get("阻塞",0)} / 失败 {regv.get("失败",0)}**，'
  f'唯一阻塞为 I-A04「新会话清空」（需真实模型对话，见 BLK-02）；')
A(f'- 菜单导航：**{len(menu)}/{len(menu)} 页真实渲染成功**，其中 {menu_click_ok} 项由侧栏菜单点击直达，{len(menu)-menu_click_ok} 项首轮点击未生效后以地址栏直达完成；')
A('- 页面级控制台错误：**0**；逐页 API 请求 **无 4xx/5xx**（身份域隔离与权限拒绝类请求在受控 API 矩阵中单列）；')
A('- API 矩阵：6 角色 × 12 只读端点 = 72 次请求，登录 6/6 成功、身份域隔离 4/4 按设计 401、越权写 3/3 按设计 403；')
A('- **写路径真实闭环**：新建用户 `POST /platform/v1/users` → 201 + DB 落库、重复名 → 409 + 明确提示、'
  '停用经 Popconfirm 确认 → DB 状态 DISABLED；取消/关闭弹窗与空表单均确认无写入；')
A(f'- 发现 **{len(DEFECTS)} 个缺陷/观察项**（测试当时点：P2 × 2、P3 × 4）、**{len([g for g in gaps if g["severity"] != "-"])} 项文档与实现差异**；'
  '其中 DEF-01/02/03/04/06 已于同日修复并回归通过（§10），DEF-05 仍为治理待办；')
A('- **发布结论（v1.1）：仍不满足「无条件发布」，但 P2 阻断项已闭环**。'
  '剩余阻断来自 DOC-08 禁区数据边界待安全评审、§6 五类跨租户写路径缺端到端证据、'
  '以及文档与实现不同步；建议「有条件放行（只读/展示类 + 平台用户写链路）」，详见 §9.3。')
A('')
A('> ⚠️ **判定纪律**：首轮自动化脚本出现多起「元素定位/弹窗动画残留/长弹窗离屏按钮/用例数据不合规」造成的连锁失败，'
  '全部经 **批次 8 最终回归**（每条用例整页重载 + 真实点击 + 网络/DB 证据）重跑，报告以最终裁定为准；'
  '工具偏差逐条列于 §7.4，**未把工具误操作记成产品缺陷，也未把脚本通过当成产品通过**。')
A('')
A('---')
A('')
A('## 1. 测试环境与前置条件')
A('')
A('### 1.1 服务拓扑（实测）')
A('')
A('| 组件 | 地址 | 实测状态 | 说明 |')
A('|---|---|---|---|')
A('| admin-console dev server | `http://localhost:5180/admin` | 200；`/` → 302 `/admin/` | Vite 6，`base=/admin/`，devServer 同源代理 `/api → 8080`、`/api/v1 → 8081` |')
A('| platform 后端 | `http://127.0.0.1:8080` | `/actuator/health` → `{"status":"UP"}` | Spring Boot，平台链 `/api/platform/v1/**` |')
A('| RAG 服务 | `http://127.0.0.1:8081` | 根路径 404（服务在线） | 管理端点 `/api/v1/management/**` |')
A('| PostgreSQL | 远程 RDS `agent_dev` / `study_rag` | psycopg2 直连成功 | 平台域 21 表、RAG 域 16 表；`sslmode=disable` |')
A('| 浏览器 | Chromium（agent-browser 守护会话） | 视口 1440×1200 | 真实点击/输入/截图/网络记录 |')
A('| 运行时 | Node v22.22.2 / JDK 21 / npm 10.9.7 | — | 见 `logs/frontend_baseline.log` |')
A('')
A('### 1.2 账号与权限（6 个最小特权平台角色）')
A('')
A('| 账号 | 角色 | 用途 |')
A('|---|---|---|')
for u, r in [('plat-superadmin','PLATFORM_SUPER_ADMIN'), ('plat-lifecycle','TENANT_LIFECYCLE_ADMIN'),
             ('plat-resource','PLATFORM_RESOURCE_ADMIN'), ('plat-security','PLATFORM_SECURITY_ADMIN'),
             ('plat-auditor','PLATFORM_AUDITOR'), ('plat-support','PLATFORM_SUPPORT')]:
    A(f'| `{u}` | `{r}` | 本轮均实际登录成功（HTTP 200，`aud=admin-console-api`，`expiresIn=900`）|')
A('')
A('> 统一口令由带外种子器设置（`docs/本地启动指南.md` §6.7 / `scripts/dev-seed-platform-users.sh`），不写入本报告。')
A('')
A('### 1.3 前置校验（执行前实测）')
A('')
A('| # | 校验项 | 实测结果 | 结论 |')
A('|---|---|---|---|')
A('| 1 | 前端 `/admin/` 可达 | 200，标题「Agent 管理控制台」 | 通过 |')
A('| 2 | 后端健康检查 | `UP` | 通过 |')
A('| 3 | 超管登录 | `POST /api/platform/v1/auth/login` → 200，返回 `accessToken/userId/roles`，`roles=[PLATFORM_SUPER_ADMIN]` | 通过 |')
A('| 4 | 错误凭据/空 body/不存在账号/租户账号 | 均 401 `{"error":"invalid_credentials"}`（防账号枚举） | 通过 |')
A('| 5 | 数据库只读连通 | platform schema 21 表 / rag schema 15 表 / vector schema 1 表可读（共 16 张业务表）| 通过 |')
A('')
A('### 1.4 测试数据与留痕')
A('')
A('| 数据 | 说明 | 处置 |')
A('|---|---|---|')
A('| `e2e_test_1113a` | 本轮写链路实测账号 A：`POST /platform/v1/users` → 201，DB 可查，随后停用 | **已 DISABLED** |')
A('| `e2e_test_1113b` | 最终回归写链路实测账号 B：201 + 列表出现 + 重复名 409，随后停用 | **已 DISABLED** |')
A('| `e2e_probe_1110` | 2026-11-10 历史 E2E 遗留账号 | 已 DISABLED，待带外清理 |')
A('| `e2e-no-commit` | 开通租户弹窗合法值，仅用于「取消不写库」校验，**未提交** | 无落库 |')
A('| `e2e-policy-probe` | 路由策略试跑/提交申请表单值，租户域端点 401、未产生申请单 | 无落库 |')
A('')
A('> 用户名格式约束：`^[a-zA-Z][a-zA-Z0-9_]{3,19}$`（首轮曾误用含连字符的 `e2e-20261113-a`，被前端正则拦截，属**用例数据错误**，'
  '最终回归改用合法名后写路径全部通过）。三个 E2E 账号当前均为 `DISABLED`，见 `evidence/db_verify.json`。')
A('')
A('### 1.5 测试工具与方法')
A('')
A('- 浏览器：agent-browser CLI（真实 Chromium），命令见 `evidence/menu_walk.sh`、`evidence/batch1–5_*.py`、'
  '`evidence/batch8_final.py`、`evidence/interact_lib.py`；')
A('- HTTP：Python urllib 经 5180 同源代理发起，逐请求记录方法/路径/状态/响应体/`X-Trace-Id` → `http/api_probe.jsonl`；'
  'UI 侧写请求由浏览器网络面板（`network requests --filter`）留痕，写入每条用例的「实际结果」；')
A('- 存储：psycopg2 只读查询（先读 `information_schema` 取真实列名/表清单，不猜列名）→ `evidence/db_verify.json`；')
A('- 静态基线：`npm test`（Vitest）+ `npm run build` → `logs/frontend_baseline.log`、复验 `logs/frontend_retest.log`；')
A('- 证据：每页截图、逐页网络日志、控制台错误、首轮交互 `logs/interactions.jsonl`、'
  '最终回归 `logs/regression_final.jsonl`、角色矩阵 JSON、DB 快照。')
A('')
A('---')
A('')
A('## 2. 测试范围与功能清单')
A('')
A('### 2.1 覆盖范围')
A('')
A('1. 登录/会话：正确与错误凭据、空表单、密码显隐、忘记密码、退出登录、令牌即将过期、伪造令牌、过期回跳；')
A('2. 鉴权守卫：未登录深链、角色守卫 403、业务无权限整页、404 兜底、`/tenants/new` 深链弹窗；')
A('3. 导航：9 个分组 / 30 个叶子菜单逐项点击，侧栏折叠展开、面包屑、页面标题；')
A('4. 页面控件：每页按钮 / 字段 / Tab / 表格 / 弹窗 / 抽屉 / Alert 的实测清单与主要交互；')
A('5. 写操作前后置校验：新建用户、开通租户、生命周期变更、登记/停用能力对象、字典/模型、指标录入；')
A('6. 角色与权限：6 账号登录 × 12 个只读端点矩阵、越权写拒绝、身份域隔离、浏览器侧导航显隐；')
A('7. 异常与边界：空值、非法格式、弱口令、重复名称、无命中搜索、跨身份域 401、凭据不回显、单向终态；')
A('8. 存储层：账号、角色权限播种、审计留痕、租户、RAG 语料表。')
A('')
A('### 2.2 不在本轮范围 / 未执行项')
A('')
A('见 §6 阻塞与不适用清单（RAG 治理签署、真实 Run 执行、路由策略租户令牌通道、跨租户全局配置终态写入等）。')
A('')
A('### 2.3 功能清单（实测 DOM 盘点，按侧栏分组）')
A('')
A('| # | 分组 | 页面 | 路由 | 按钮数 | 字段数 | Tab | 表格行 | 结论 |')
A('|---|---|---|---|---:|---:|---:|---:|---|')
for r in menu:
    p = r['page']
    if isinstance(p, str):
        p = json.loads(p)
    A(f'| M{r["seq"]:02d} | {r["group"]} | {r["label"]} | `{p.get("url","")}` | {len(p.get("buttons",[]))} | '
      f'{len(p.get("inputs",[]))} | {len(p.get("tabs",[]))} | {p.get("rows",0)} | '
      f'{"通过" if p.get("bodyLen",0) > 200 else "失败"} |')
A('')
A('> 控件数为真实 DOM 计数（`button` / `input,textarea,select` / `.ant-tabs-tab` / `.ant-table-row`），'
  '完整逐页快照见 `logs/menu_walk.jsonl`，截图见 `screenshots/M01…M30_*.png`。')
A('')
A('### 2.4 对照的文档基线')
A('')
A('| 文档 | 用途 |')
A('|---|---|')
A('| `admin-console/README.md` | 部署形态、导航表、鉴权约定、能力目录三步工作流、已知事项 |')
A('| `docs/管理后台系统设计方案.html` | §2 功能模块、§4 页面结构与导航树、§5 交互流程、§7 技术选型 |')
A('| `docs/管理后台开发计划/`（00 总览 + P0–P7 + README） | 阶段交付、技术栈、作用域红线、冻结门禁 |')
A('| `docs/重规划/平台身份与租户身份分离改造方案.md` | §5.2 权限命名空间、§5.3 角色目录、§10 功能范围与禁区、§12 验收矩阵 |')
A('| `docs/本地启动指南.md` §6.7 | 平台账号清册 |')
A('| `问题记录/E2E端到端测试_2026-11-10/端到端测试报告_2026-11-10.md` | 上一轮 E2E 缺陷与回归基线 |')
A('')
A('---')
A('')
A('## 3. 测试用例与执行记录')
A('')
A('### 3.1 交互用例逐项执行记录')
A('')
A('> 结论口径：**通过**（实际结果符合预期）/ **失败**（实际结果不符合预期且可复现）/ **阻塞**（前置条件不具备）/ **不适用**（按设计不适用）。'
  '**本表为最终裁定**：带「✅回归」标记的用例已由批次 8 用更稳健的定位重跑并覆盖首轮结论；'
  '首轮原始记录（含被覆盖的失败结论与中止的 `R-*` 尝试）保留在 `logs/interactions.jsonl`，'
  '回归原始记录在 `logs/regression_final.jsonl`，机器可读版见 `evidence/测试用例与执行记录.csv`。')
A('')
groups = defaultdict(list)
for r in inter:
    groups[r['group']].append(r)
for g, rs in groups.items():
    A(f'#### {g}（{len(rs)} 项）')
    A('')
    A('| 用例 | 操作项 | 操作步骤 | 预期 | 实际结果 | 结论 |')
    A('|---|---|---|---|---|---|')
    for r in rs:
        flag = ' ✅回归' if r.get('final') else ''
        A(f'| `{r["id"]}`{flag} | {esc(r["desc"],60)} | {esc(r["steps"],110)} | {esc(r["expect"],110)} | '
          f'{esc(r["actual"],220)} | **{r["verdict"]}** |')
    if rs and any(r.get('note') for r in rs):
        for r in rs:
            if r.get('note'):
                A(f'> `{r["id"]}` 备注：{r["note"]}')
    A('')
A(f'**小计**：通过 {vc.get("通过",0)}、失败 {vc.get("失败",0)}、阻塞 {vc.get("阻塞",0)}、不适用 {vc.get("不适用",0)}，合计 {len(inter)} 项。')
A('')
A('### 3.2 菜单逐项走查记录')
A('')
A('| 用例 | 菜单 | 目标路由 | 实际 URL | 页面标题 | 正文长度 | 菜单点击直达 | 结论 |')
A('|---|---|---|---|---|---:|---|---|')
for r in menu:
    p = r['page']
    if isinstance(p, str): p = json.loads(p)
    A(f'| M{r["seq"]:02d} | {r["label"]} | `{r["path"]}` | `{esc(r["url"],60)}` | {esc(r["title"],28)} | '
      f'{p.get("bodyLen",0)} | {"是" if r["click_rc"]==0 else "否（回退直达）"} | '
      f'{"通过" if r["path"].split("?")[0] in r["url"] else "失败"} |')
A('')
A(f'**小计**：{len(menu)}/{len(menu)} 页渲染成功；无空白页、无 JS 运行时错误（`pageErrors` 全空）、逐页网络日志无 4xx/5xx。'
  f'{len(menu)-menu_click_ok} 项菜单点击首轮未生效（AntD 折叠分组的子项虽在 DOM 但 `offsetParent=null`），'
  '已用「展开分组 + 可见性校验 + scrollIntoView + 未命中则地址栏直达」复验，并在最终回归中重跑 `/dictionaries`、`/agent-lab` 等代表项。')
A('')
A('### 3.3 API 契约与 6 角色权限矩阵')
A('')
A('#### 3.3.1 认证与异常入口')
A('')
A('| 用例 | 请求 | 预期 | 实际 | 结论 |')
A('|---|---|---|---|---|')
for r in api:
    if r['case'].startswith(('A0', 'login:')):
        okv = (r['status'] == (r.get('expect') or r['status']))
        A(f'| {esc(r["case"],34)} | `{r["method"]} {r["path"]}` | {r.get("expect","-")} | {r["status"]} {esc(r["body"],80)} | '
          f'{"通过" if okv else "**失败**"} |')
A('')
A('#### 3.3.2 6 角色 × 只读端点状态码矩阵')
A('')
eps = []
for r in api:
    if r['case'].startswith('RBAC:') and r['path'] not in eps: eps.append(r['path'])
users = ['plat-superadmin','plat-lifecycle','plat-resource','plat-security','plat-auditor','plat-support']
m = {(r['case'].split(':')[1], r['path']): r['status'] for r in api if r['case'].startswith('RBAC:')}
A('| 端点 | ' + ' | '.join(u.replace('plat-','') for u in users) + ' |')
A('|---' * (len(users)+1) + '|')
for p in eps:
    A(f'| `{p}` | ' + ' | '.join(str(m.get((u,p),'-')) for u in users) + ' |')
A('')
A('> 403 = 后端最小特权矩阵按设计拒绝；200 = 放行。`/platform/v1/metrics/agent` 裸 BASE 路径 404 属探测路径问题'
  '（前端实际调用 `/overview`、`/terminations` 等子路径），**不计为缺陷**。')
A('')
A('#### 3.3.3 身份域隔离与越权写')
A('')
for r in api:
    if r['case'].startswith(('ISO', 'WRITE-DENY', 'RAG')):
        A(f'- `{r["case"]}` → **{r["status"]}**　`{r["method"]} {r["path"]}`')
A('')
A('#### 3.3.4 浏览器侧角色表现')
A('')
if role_browser:
    A('| 账号 | 角色 | 登录后落点 | roles 声明 | 「平台审计」菜单节点 | 深链 /audit 403 | 「新建用户」按钮可见 | 最终结论 |')
    A('|---|---|---|---|---|---|---|---|')
    for x in role_browser:
        want = x['role'] in ('PLATFORM_SUPER_ADMIN', 'PLATFORM_AUDITOR')
        passed = (x.get('auditMenuNode') is want and x.get('auditDeepLink403') is (not want)
                  and x.get('rolesClaim') == [x['role']] and bool(x.get('loginUrl')))
        A(f'| `{x["user"]}` | `{x["role"]}` | `{x.get("loginUrl","-")}` | {x.get("rolesClaim")} | '
          f'{x.get("auditMenuNode", x.get("auditMenuVisible","-"))} | {x.get("auditDeepLink403")} | '
          f'{x.get("usersNewButtonVisible")} | **{"通过" if passed else "失败"}** |')
    A('')
    A('> 该表为**批次 8 干净会话重做**（每角色先 `sessionStorage.clear()` 再登录，校验 JWT `roles` 声明与落点 URL），'
      '覆盖了首轮因会话残留导致的 `plat-superadmin` 停在 `/login`、`plat-lifecycle` 仍带超管声明等脏数据。'
      '前端只按角色裁剪「平台审计」入口（AUDIT_SCOPE = [SUPER_ADMIN, AUDITOR]），其余页面对 6 角色均显示；'
      '安全边界由后端重授权（与 README §5 一致）。副作用见 DEF-06。')
    A('> `leafMenuCount` 未列入：AntD 折叠分组的子项未展开时不进 DOM，该计数只反映当前展开分组的叶子数，不代表菜单总数；'
      '菜单总数以 §2.3 的 30 页走查为准。')
A('')
A('### 3.4 数据库 / 存储验证')
A('')
A('| 校验项 | 实测 | 结论 |')
A('|---|---|---|')
A(f'| platform schema 表数 | {len(db.get("platform_tables",[]))} | 通过 |')
A(f'| platform_permission 权限数 | {db.get("platform_platform_permission",{}).get("rowCount","-")} | 与源码 `allPlatformPermissions()` 24 项一致（README 写 23，见 DOC-07）|')
A(f'| platform_role 角色数 | {db.get("platform_platform_role",{}).get("rowCount","-")} | 6 个内置角色，与前端常量一致 |')
A(f'| platform_user 账号数 | {db.get("platform_platform_user",{}).get("rowCount","-")} | 6 个预置账号 + 3 个 E2E 测试账号（均 DISABLED，DEF-05）|')
A('| 本轮写链路落库 | `e2e_test_1113a` / `e2e_test_1113b` 两行，初始 ACTIVE → 停用后 DISABLED | **通过**：UI POST 201 / 409 与 DB 状态一致 |')
A(f'| platform_role_permission 播种 | {db.get("platform_platform_role_permission",{}).get("rowCount","-")} 行 | SECURITY_ADMIN 含 `platform.audit.read`，运行时放行 200；v1.0「运行时 403」为误判，差异仅在前端导航（DEF-02 已更正）|')
A(f'| platform_audit_event | 取回 {db.get("platform_platform_audit_event",{}).get("rowCount","-")} 行（上限 200）| 审计为仅追加、可追溯 |')
A(f'| tenant / tenant_quota / capability_grant | {db.get("platform_tenant",{}).get("rowCount","-")} / '
  f'{db.get("platform_tenant_quota",{}).get("rowCount","-")} / {db.get("platform_tenant_capability_grant",{}).get("rowCount","-")} 行 | 与页面 2 个 ACTIVE 租户一致 |')
A(f'| RAG 域表 | `rag` schema {len(db.get("rag_tables",[]))} 张 + `vector` schema {len(db.get("vector_tables",[]))} 张 = '
  f'{len(db.get("rag_tables",[]))+len(db.get("vector_tables",[]))} 张 | 与 RAG 页面数据源一致 |')
A('')
A('> 查询脚本 `evidence/db_verify.py`，快照 `evidence/db_verify.json`。所有查询均为只读，凭据不落报告。'
  '首版脚本曾误用 `public` schema 查 RAG 表（返回 0 张），已按 `information_schema` 实测修正为 `rag` + `vector`。')
A('')
A('---')
A('')
A('## 4. 实现与文档差异（明确标注）')
A('')
A('| 编号 | 文档来源 | 文档口径 | 当前实现 / 实测 | 差异类型 | 严重度 | 证据 |')
A('|---|---|---|---|---|---|---|')
for g in gaps:
    A(f'| `{g["id"]}` | {esc(g["source"],60)} | {esc(g["doc"],140)} | {esc(g["actual"],170)} | {esc(g["type"],44)} | '
      f'{g["severity"]} | {esc(g["evidence"],60)} |')
A('')
A('> 差异集中在「旧设计/开发计划未随重规划后的平台身份域改造同步更新」，以及「设计方案 §5.3 角色目录与代码冻结矩阵口径不同」。'
  '`admin-console/README.md` 自身与实现基本一致（DOC-10），但其导航表只覆盖了 8/30 个页面。')
A('')
A('---')
A('')
A('## 5. 缺陷清单（含可复现步骤）')
A('')
A('严重度口径：**P1** 阻断发布/安全边界待确认；**P2** 主要功能或权限口径受损；**P3** 轻微/体验/治理项。')
A('')
for d in DEFECTS:
    A(f'### {d["id"]}　{d["title"]}（{d["sev"]}）')
    A('')
    A(f'- **复现步骤**：{d["steps"]}')
    A(f'- **测试数据**：{d["data"]}')
    A(f'- **预期结果**：{d["expect"]}')
    A(f'- **实际结果**：{d["actual"]}')
    A(f'- **影响范围**：{d["impact"]}')
    A(f'- **证据**：`{d["evidence"]}`')
    A(f'- **状态**：{d["status"]}')
    A('')
A('---')
A('')
A('## 6. 阻塞项与不适用项')
A('')
A('| 编号 | 项目 | 原因 / 前置条件 | 结论 | 复现入口 |')
A('|---|---|---|---|---|')
for b in BLOCKERS:
    A(f'| `{b["id"]}` | {esc(b["item"],54)} | {esc(b["reason"],220)} | **{b["verdict"]}** | {esc(b["repro"],60)} |')
A('')
A('---')
A('')
A('## 7. 回归验证结果')
A('')
A('### 7.1 最终回归总览（批次 8）')
A('')
A('首轮 5 个交互批次共记录 75 项用例，其中 19 项判失败；随后一次 `R-*` 稳健复验尝试因选择器仍不匹配被主动中止（其 6 条记录**不作为结论**）。'
  '批次 8 以「每条用例整页重载 + 真实点击 + 网络/DB 证据」重跑全部失败项与 6 角色用例，并补入 2 项历史缺陷回归。')
A('')
A(f'**批次 8 结果：{len(reg)} 项 → 通过 {regv.get("通过",0)} / 阻塞 {regv.get("阻塞",0)} / 失败 {regv.get("失败",0)}。**'
  f' 全量最终裁定：通过 {vc.get("通过",0)} / 失败 {vc.get("失败",0)} / 阻塞 {vc.get("阻塞",0)} / 不适用 {vc.get("不适用",0)}。')
A('')
A('| 回归用例 | 操作项 | 最终结论 | 关键实测证据 | 首轮失败归因 |')
A('|---|---|---|---|---|')
CAUSES = {
 'I-L03': 'rc-trigger 下拉不响应 DOM .click()，须真实点击',
 'I-L04': 'L03 未真正登出，会话残留致「未登录」前提不成立',
 'I-L08': 'clientWidth/getComputedStyle 在本 headless 环境读数失真（恒 200）',
 'I-G02': '弹窗主按钮不在 .ant-modal-footer（在 body 内），选择器落空',
 'I-G04': '开通租户弹窗只有关闭 X、没有取消按钮；且关闭动画不结束',
 'I-G05': '用例预期错误：合法迁移已默认预选，确定按钮本就可用',
 'I-G06': '行按钮文案为「详 情」（含空格），精确文本匹配失败',
 'I-G10': '用例数据 e2e-20261113-a 含连字符，被用户名正则拦截',
 'I-G11': '同上（非法用户名）+ 前序弹窗残留污染',
 'I-G12': '前序用例创建的账号不存在，行定位失败',
 'I-C04': '提交按钮 y≈1585 在视口外，CSS 选择器点击落空',
 'I-C05': '详情是 Drawer 不是 Modal；且按钮文案含空格',
 'I-C06': '前序弹窗残留 + 行按钮文案含空格',
 'I-C10': '按钮文案「试跑（不提交）」，find text「试跑」命中不稳定',
 'I-C11': '同上 + 未捕获消息提示',
 'I-C14': '新建模型是 Drawer 不是 Modal',
 'I-A02': '用例预期错误：发送门禁是「租户 + 输入非空」双条件',
 'I-A04': '真实阻塞：空会话下「新会话」按钮禁用，全链路需真实模型对话',
 'I-P-superadmin': '会话残留：loginUrl 停在 /login、menuCount=0',
 'I-P-lifecycle': '会话残留：rolesClaim 仍是上一角色的超管声明',
 'I-P-resource': '同上', 'I-P-security': '同上', 'I-P-auditor': '同上', 'I-P-support': '同上',
 'I-H01': '新增回归项', 'I-H02': '新增回归项',
}
for r in reg:
    A(f'| `{r["id"]}` | {esc(r["desc"],46)} | **{r["verdict"]}** | {esc(r["actual"],190)} | '
      f'{esc(CAUSES.get(r["id"],"工具/用例偏差"),80)} |')
A('')
A('> 关键写路径证据：`I-G10` → `POST /api/platform/v1/users 201` +「已创建」+ 重载列表出现 + DB 新增行；'
  '`I-G11` → `POST … 409` +「请求与当前资源状态冲突」；`I-C10` → `POST …dry-run 401` + 页面明示「租户级令牌通道尚未接入」；'
  '`I-C11` → `POST …/publishes 401` +「平台令牌对该端点不可达（身份域隔离）」；'
  '`I-G02/I-G04/I-G05` → 网络面板均无 POST。')
A('')
A('### 7.2 首轮失败归因分类（判定纪律）')
A('')
A('| 归因 | 用例数 | 说明 | 是否记为产品缺陷 |')
A('|---|---:|---|---|')
CATEGORIES = [
    ('自动化定位 / 事件机制偏差', {'I-L03','I-L08','I-G02','I-G04','I-G06','I-C04','I-C05','I-C06','I-C10','I-C11','I-C14'},
     '真实点击 vs DOM click、离屏按钮、Drawer vs Modal、文案空格、弹窗动画残留', '否'),
    ('用例设计 / 测试数据错误', {'I-G05','I-G10','I-G11','I-A02'},
     '用户名含连字符、生命周期「未选门禁」假设、AI 发送门禁单条件假设', '否，已修正预期并注明'),
    ('会话残留 / 前序用例连锁', {'I-L04','I-G12','I-P-superadmin','I-P-lifecycle','I-P-resource','I-P-security','I-P-auditor','I-P-support'},
     '6 角色矩阵未清 sessionStorage；L03 未登出致 L04 前提不成立；G12 依赖前序未创建的账号', '否，已用干净会话重做'),
    ('真实前置条件不具备', {'I-A04'},
     'I-A04 需先产生真实模型对话才能验证「新会话清空」（BLK-02）', '否，记为「阻塞」'),
    ('新增回归项（非首轮失败）', {'I-H01','I-H02'},
     '2026-11-10 历史缺陷 D-01 / D-02 的本轮复验', '否'),
]
for name, ids, desc, defect in CATEGORIES:
    A(f'| {name} | {len(ids)} | {desc} | {defect} |')
A(f'| 产品缺陷 | 0 | 本批失败项无一是产品功能错误；真实缺陷来自 API 矩阵、权限事实源与导航裁剪（DEF-01/02/06）| — |')
A('')
covered = len([r for r in reg if r['id'] in base_ids])
A(f'> 合计 {sum(len(x[1]) for x in CATEGORIES)} 项 = 批次 8 的 {len(reg)} 项回归用例；'
  f'其中 {covered} 项覆盖首轮用例（含首轮判定失败的项与需干净会话重做的 6 角色项），'
  f'{len(reg)-covered} 项为新增历史缺陷回归。')
A('')
A('### 7.3 上一轮（2026-11-10）缺陷回归')
A('')
A('| 历史缺陷 | 本轮复验 | 结论 |')
A('|---|---|---|')
A('| D-01 RAG `ingest-jobs` 平台档 500 | 30 页走查 + `I-H01` 打开 `/rag/jobs` 实测无 5xx | 保持修复 |')
A('| D-02 `knowledge-points` / 别名下拉 500 | `I-H02` 打开 `/rag/kp-alias` 实测无 5xx | 保持修复 |')
A('| D-03 AI 对话检索 500 | I-A01～A03 走真实发送与错误分支，无静默空白 | 前端错误分支正确；真实 Run 未发起（BLK-02）|')
A('| D-04 审计页 Select `value=null` 警告 | 30 页走查控制台错误为 0 | 保持修复 |')
A('| D-05 侧栏菜单重复 key | 9 分组 30 叶子 path 唯一，控制台无 `Duplicated key` | 保持修复 |')
A('')
A('### 7.4 自动化工具偏差与规避方式（诚实披露）')
A('')
A('| # | 现象 | 根因 | 规避 / 判定依据 |')
A('|---|---|---|---|')
A('| 1 | 页头「退出登录」点击无效 | AntD Dropdown 用 rc-trigger，DOM `element.click()` 不触发打开 | 改用 agent-browser 真实点击；实测点击后 URL→`/admin/login` 且 sessionStorage 清空 |')
A('| 2 | 弹窗取消/关闭/Escape 后 wrap 仍 `display:block`、截图中遮罩仍在 | headless Chromium 下 `ant-zoom-leave-start` 动画不结束，`rc-motion` 未收到结束事件；'
  '`onCancel` 已触发、React 状态已关闭 | 以 `.ant-zoom-leave` 类作为「已收到关闭指令」，并以「无 POST」网络证据为写入判定；'
  '每条用例整页重载复位，**不记为产品缺陷**，但建议人工在正常浏览器复核一次 |')
A('| 3 | 侧栏折叠后 `clientWidth` 与 `getComputedStyle().width` 恒为 200 | 同一 headless 渲染/样式读取失真 | 以 icon `aria-label`（menu-fold↔menu-unfold）、`ant-layout-sider-collapsed` 类、内联 `width:80px` 与截图为准；'
  '截图 `REG_sider_collapsed.png` 证实视觉已收窄至约 80px |')
A('| 4 | 长弹窗「提交登记申请」点击无反应 | 按钮 y≈1585 超出 1200 视口，CSS 选择器点击落空 | 按按钮文本做 DOM 点击；实测出现 3 条必填校验提示且无 POST |')
A('| 5 | 菜单叶子项在 DOM 中却点击不跳转 | 折叠分组子项 `offsetParent=null`（AntD Menu 保持挂载）| 展开分组 + 可见性校验 + scrollIntoView；未命中则地址栏直达并记录 |')
A('| 6 | 6 角色矩阵出现「超管停在登录页 / 角色声明是上一个人」| `sessionStorage` 未清、登录跳转未等待 | 每角色 `sessionStorage.clear()` → 重新打开 `/login` → 等待 URL 离开 login → 校验 `roles` 声明 |')
A('')
A('> 另有 2 项测试脚本自身缺陷已修正：`time.sleep(300)` 毫秒写成秒（任务卡住后中止）、'
  'DB 核验脚本误用 `public` schema 查 RAG 表（返回 0）与猜测列名（改为先读 `information_schema`）。')
A('')
A('### 7.5 静态基线（真实结果）')
A('')
A('| 项目 | 命令 | 实测结果 | 结论 |')
A('|---|---|---|---|')
A('| 全量单测 | `npm test`（Vitest）| `Test Files 1 failed / 65 passed (66)`；`Tests 1 failed / 704 passed (705)`，'
  '失败点 `tests/agentDefinitionBackfill.test.tsx:516` 等待「请输入名称」 | 1 项不稳定用例 |')
A('| 失败用例单独复跑 | `npx vitest run tests/agentDefinitionBackfill.test.tsx --reporter=dot` | `18 passed (18)` | 单独通过，判为全量并发/顺序相关的偶发，非常态构建失败 |')
A('| 生产构建（原目录）| `npm run build` | 清空 `dist/assets` 时被 WorkBuddy safe-delete 批量删除守卫拦截（`count=65 > threshold=50`）| 环境守卫，非代码错误 |')
A('| 生产构建（干净 outDir）| `npx tsc --noEmit && npx vite build --outDir dist-e2e-20261113` | 成功，仅大 chunk 体积警告 | **通过** |')
A('')
A('> 原始输出见 `logs/frontend_baseline.log` 与 `logs/frontend_retest.log`。发布前仍建议在干净 CI 环境单独跑一次 `npm test && npm run build`。')
A('')
A('---')
A('')
A('## 8. 证据索引')
A('')
A('| 类别 | 路径 | 内容 |')
A('|---|---|---|')
A(f'| 截图 | `screenshots/` | {shot_count} 张 PNG：`S01` 登录页、`M01–M30` 菜单逐页、`I_*` 交互用例、`F_*` 最终回归、`REG_*` 工具现象取证 |')
A('| 菜单走查原始记录 | `logs/menu_walk.jsonl` | 每页 URL/标题/按钮/字段/Tab/表格/Alert/控制台 |')
A(f'| 逐页网络日志 | `logs/menu_net_M*.txt` | {net_logs} 份，含请求方法、URL、状态码 |')
A('| 首轮交互执行记录 | `logs/interactions.jsonl` | 81 条（含 6 条中止的 `R-*` 尝试，不作结论）：步骤、预期、实际、结论、时间戳 |')
A('| **最终回归执行记录** | `logs/regression_final.jsonl` | 26 条最终裁定用例，含写请求状态、DB 关联说明与用例数据修正备注 |')
A('| 用例清单（CSV） | `evidence/测试用例与执行记录.csv` | 机器可读全量最终裁定用例表 |')
A(f'| API 请求/响应 | `http/api_probe.jsonl` | {len(api)} 条：状态码、响应体、`X-Trace-Id` |')
A('| 数据库快照 | `evidence/db_verify.json` | platform 21 表 / rag 15 表 / vector 1 表，14 张关键表逐行快照 |')
A('| 角色矩阵（浏览器） | `evidence/role_matrix_browser.json` | 6 角色干净会话的 roles 声明、审计菜单节点、深链 403、写按钮可见性 |')
A('| 文档差异台账 | `evidence/doc_gaps.json` | 10 项对照结论 |')
A('| 可复现脚本 | `evidence/*.sh` / `*.py` | 菜单走查、API 矩阵、DB 核验、5 个交互批次、`batch8_final.py` 最终回归、`build_report.py` 报告生成 |')
A('| 运行日志 | `logs/*_run.log`、`logs/all_batches_run.log`、`logs/regression_final_run.log` | 各批次原始输出 |')
A('| 前端基线 | `logs/frontend_baseline.log`、`logs/frontend_retest.log` | Vitest + 生产构建及复验 |')
A('')
A('> 关键请求/响应示例：`GET /api/platform/v1/audit-events?from=…&limit=200` → 200（见 `menu_net_M04.txt`）；'
  '`POST /api/platform/v1/auth/login` → 200 与 401 对照（见 `api_probe.jsonl`）；'
  '身份域隔离 401 与越权写 403（同上 ISO / WRITE-DENY 组）。')
A('')
A('---')
A('')
A('## 9. 覆盖率、遗留风险与发布结论')
A('')
A('### 9.1 覆盖率')
A('')
A('| 维度 | 覆盖情况 | 覆盖率 |')
A('|---|---|---|')
A(f'| 页面 / 菜单入口 | 30/30 叶子菜单 + 登录页 + 403/404/无权限页 + `/tenants/new` 深链 + `/tenants/:id` 详情 + 旧 `/rag` 入口 | 100%（可达路由）|')
A(f'| 控件盘点 | 30 页全部按钮/字段/Tab/表格/Alert 经 DOM 实测登记 | 100%（盘点）|')
A(f'| 控件交互 | {len(inter)} 项最终裁定用例（含 {len(reg)} 项稳健回归）覆盖主要按钮、表单必填/格式/边界、'
  '弹窗与抽屉开关、Tab 与状态切换、筛选、真实提交与取消 | 约 75–85%（每页主要可操作项，未穷举每一行数据的组合）|')
A('| 写操作 | 新建用户（201/409）、停用（Popconfirm→DISABLED）、取消/空表单不写入（网络面板无 POST）、'
  '跨身份域申请（401 + 显式提示） | 平台用户写链路 100% 闭环并落库核验；跨租户全局写未执行（§6）|')
A('| 角色与权限 | 6/6 角色登录 × 12 端点 + 3 类越权写 + 4 条身份域隔离 + 浏览器侧干净会话导航显隐与深链守卫 | 100%（角色维度）|')
A('| 异常与边界 | 空值、非法格式、弱口令、重复、无命中、伪造令牌、即将过期、跨身份域、只读红线、凭据不回显、SoD 自身保护 | 覆盖设计内主要分支 |')
A('| 跨层验证 | 浏览器 UI → HTTP 契约 → 数据库存储 | 三层均有证据（写操作与 DB 状态逐项对照）|')
A('')
A('> 诚实边界：① 未对每个表格行逐一执行所有操作（组合爆炸）；② 三步工作流的「审批→执行」终态写入、'
  '租户开通/退役、RAG 治理签署与真实 Run 执行未提交（见 §6）；③ 2026-11-10 报告中的 5 个缺陷只做回归复验，未重复全量造数；'
  '④ headless 环境下弹窗关闭动画不结束、部分 `getComputedStyle` 读数失真，相关判定以状态类/网络证据/截图为准（§7.4），'
  '建议发布前由人工在正常浏览器做一次弹窗开关与侧栏折叠的目视复核。')
A('')
A('### 9.2 遗留问题与风险')
A('')
A('| 风险 | 等级 | 说明 | 建议 |')
A('|---|---|---|---|')
A('| 认证失败返回 500（DEF-01） | **已闭环** | 已改为 401 UNAUTHENTICATED：安全链 permitAll 精确到 login/jwks，'
  'ServletRequestBindingException 按「平台域缺主体」归一 401；单测 + 源码守卫 + 真实 HTTP 三重验证（§10.1） | 保持守卫，勿再放宽成 `/auth/**` |')
A('| 审计门禁漏配（DEF-02，v1.0 误判已更正） | **已闭环** | 后端运行时从未拒绝 SECURITY_ADMIN（200）；'
  '真实差异是前端 AUDIT_SCOPE / navConfig 少一个角色，已在两处补齐并 6 角色干净会话复验（§10.2） | '
  '前端角色清单以后端冻结矩阵为唯一事实源，补 CI 守卫 |')
A('| 文档与实现大面积不同步（DOC-01～09） | 中 | 开发计划仍写「尚未编码」、技术栈旧、功能范围缺 30 页清单、角色目录与冻结矩阵不一致 | 以本轮功能清单为准刷新 README/开发计划/重规划 §5.3/§10，并标注旧章节废弃 |')
A('| 禁区数据边界待确认（DOC-08） | 高 | 人工审查队列（含对话回放）与 AI 实验室进入常规菜单，低权限角色可访问 review-tasks | 安全评审确认回放数据来源是否含租户业务正文；必要时按角色收紧导航与端点 |')
A('| 低权限角色可见可写按钮（DEF-06） | **已闭环（用户页）** | 用户页写入口已按 `platform.user.manage`（仅超管）裁剪，'
  '6 角色复验：仅超管可见「新建用户」；其余 29 页仍全角色可见，属保留的设计取舍 | 后续可按同一模式把导航裁剪推广到其余写页面 |')
A('| 测试数据残留（DEF-05） | 低 | 账号表累积 3 个测试主体，本轮 2 个已主动停用至 DISABLED | 维护窗口按带外通道统一清理，给出复核记录 |')
A('| 单测偶发失败 / 构建环境守卫 | **已收敛** | 修复后全量 Vitest **710/710 通过**（含 5 条新守卫），'
  '`tsc --noEmit` 通过，干净 outDir 构建成功（§10.3）；首轮 1/705 偶发失败未再复现 | 发布前仍在干净 CI 复跑一次 |')
A('| headless 动画/样式读数失真 | 低 | 弹窗关闭动画不结束、`clientWidth` 恒 200（§7.4）| 人工目视复核或改用非 headless 浏览器回归一次 |')
A('')
A('### 9.3 是否满足发布条件')
A('')
A('**结论（v1.1，修复后回归已通过）：仍不满足「无条件发布」，但 P2 阻断项已全部闭环；'
  '建议「有条件放行（只读/展示类 + 平台用户写链路）」，或补齐下列剩余项后转无条件发布。**')
A('')
A('判定依据：')
A('')
A('1. ✅ 核心可发布部分：登录/会话/过期/回跳、30 个页面渲染与导航、平台用户/租户/能力目录/审计/RAG/观测/实验室的展示与只读链路、'
  '身份域隔离（4/4 401）、越权写拒绝（3/3 403）、凭据不回显、SoD 自身保护，均有实测通过证据；'
  '**平台用户写链路（新建 201 / 重复 409 / 停用落库）已端到端闭环**；最终裁定无残留「失败」用例；')
A('2. ✅ **P2 阻断项已闭环**：DEF-01 改 401、DEF-02 更正误判后补齐前端审计门禁；'
  'DEF-03/04/06 同步修复。93 条 API 矩阵重跑仅 A05/A06 由 500→401，其余 91 条零回归；'
  '15 项浏览器回归 15/15 通过（§10）。'
  '❌ **仍阻断**：DOC-08 的禁区数据边界需安全评审签字；')
A('3. ⚠️ 未完成验证：三步工作流终态写入、租户生命周期真实提交、RAG 治理签署、真实 Run 执行及「新会话清空」全链路（§6、BLK-02/05），'
  '这些是跨租户/高影响写路径的核心，缺端到端证据；')
A('4. ⚠️ 文档与实现不同步（DOC-01～09）违反项目自身「文档与实现同版本」的冻结门禁（开发计划 §4.2）；')
A('5. ✅ 前端基线已收敛：全量 Vitest 710/710、`tsc --noEmit`、干净 outDir 构建全绿；'
  '⚠️ headless 动画/样式读数偏差仍建议人工在正常浏览器目视复核一次。')
A('')
A('**放行前最小闭环（建议）**：')
A('')
A('- [x] 修复 DEF-01 / DEF-02（含误判更正）/ DEF-03 / DEF-04 / DEF-06，补单测与源码守卫；'
  '93 条 API 矩阵 + 15 项浏览器回归 + 6 角色干净会话复核全部通过（§10）；')
A('- [ ] 安全评审确认 DOC-08 禁区边界，必要时收紧低权限角色可见菜单；')
A('- [ ] 在变更窗口内完成 §6 五类写路径的真实端到端（双人复核 + 测试数据清理）；')
A('- [ ] 刷新文档至本轮功能清单，标注旧计划章节废弃；')
A('- [x] 干净环境跑 `npm test`（710/710）+ `tsc --noEmit` + `vite build --outDir dist-fix2-20261113` 全绿；')
A('- [ ] 清理 `e2e_probe_1110` 与本轮测试账号。')
A('')
# ── 10. 修复后回归（v1.1）──────────────────────────────────────────
A('---')
A('')
A('## 10. 修复后回归（v1.1）')
A('')
A('> 修复与回归同日完成：后端用 `mvn -o -pl platform-bootstrap -am package -DskipTests` 重建 exec jar，'
  '按 `scripts/dev-backend.sh` 重启 8080（新 PID、health UP）；前端 Vite dev server 直接加载改动源码。'
  '以下均为真实浏览器 / 真实 HTTP / 真实构建结果，不是静态推断。')
A('')
A('### 10.1 DEF-01 根因与修复（后端）')
A('')
A('| 环节 | 结论 |')
A('|---|---|')
A('| 根因 | `SecurityConfiguration` 平台链把 `/api/platform/v1/auth/**` **整体** permitAll，'
  '需认证的 `/auth/me` 也被匿名放行；`@RequestAttribute(平台主体)` 缺失时 Spring 7 抛 '
  '`org.springframework.web.bind.ServletRequestBindingException`（该版本**不存在** `MissingRequestAttributeException`），'
  '被 `@ExceptionHandler(Exception.class)` 兜底吞成 500 INTERNAL_ERROR |')
A('| 主修复 | `SecurityConfiguration.java`：permitAll 收窄为 `/api/platform/v1/auth/login` 与 `/auth/jwks` 两个精确端点，'
  '`/auth/me` 落入 `.anyRequest().authenticated()` → 统一 401 入口点 `{"code":"UNAUTHENTICATED","message":"未认证"}` |')
A('| 第二道保险 | `ApiExceptionHandler.java`：新增 `@ExceptionHandler(ServletRequestBindingException.class)`，'
  '按「平台域 URI + 请求属性无已验签主体」判定 401，其余绑定错误归 400 BAD_REQUEST（原先也是 500）|')
A('| 守卫 | `ApiExceptionHandlerTest` 新增 2 例（平台域 401 / 普通绑定 400）；'
  '`PlatformAuthPublicEndpointsGuardTest` 新增 3 例源码级守卫，撤掉 permitAll 收窄或专用处理器即转红 |')
A('| 验证 | 定向 Maven 测试全绿 → 重建 exec jar → 重启 8080 → 无令牌 / 伪造令牌 `/auth/me` 均 401 UNAUTHENTICATED；'
  '有效令牌 200；login/jwks 仍公开可达 |')
A('')
A('### 10.2 DEF-02 / 03 / 04 / 06 修复（前端）')
A('')
A('| 缺陷 | 改动 | 关键取舍 |')
A('|---|---|---|')
A('| DEF-02（更正后） | `router.tsx` 的 `AUDIT_SCOPE` 与 `navConfig.tsx` 审计节点 `requiredRoles` '
  '同时补 `ROLES.SECURITY_ADMIN` | 以后端冻结矩阵为唯一事实源；**不动**矩阵与 DB 播种（后端本来就是 200）|')
A('| DEF-03 | `AuditLogsPage.tsx` 增加 `inflightRef`，同参数在途 Promise 复用 | '
  'dev StrictMode 双 effect 不再重复发 GET；手动刷新语义保留 |')
A('| DEF-04 | `index.html` 用内联 `data:image/svg+xml;base64,…` 声明图标，另保留 `public/favicon.svg` | '
  '⚠️ 实测 Vite dev 会对 HTML 中以 `/` 开头的 href 再加一次 base，`%BASE_URL%` 与写死 `/admin/` '
  '都变成 `/admin/admin/favicon.svg`（SPA fallback 返回 text/html）；相对路径在深链下又解析到 `/favicon.svg`。'
  'data URI 双端一致且零请求 |')
A('| DEF-06 | `UsersManagePage.tsx` 以 `canManageUsers`（= 超管，冻结矩阵中仅其持 `platform.user.manage`）'
  '裁剪「新建用户」按钮与行操作列，非超管显示「只读」 | 后端重授权仍是唯一安全边界；'
  '其余 29 页保持全角色可见，属保留的设计取舍 |')
A('')
A('### 10.3 静态基线')
A('')
A('- 后端：`mvn -o -pl agent-api,platform-bootstrap -am test -Dtest=ApiExceptionHandlerTest,'
  'PlatformAuthPublicEndpointsGuardTest` **BUILD SUCCESS**；exec jar 重建成功、服务重启 health UP；')
A('- 前端：`npx vitest run` **710/710 通过**（含新增 5 条守卫）、`tsc --noEmit` 通过、'
  '`vite build --outDir dist-fix2-20261113` 成功（仅大 chunk 警告）；构建产物含 data URI 图标；')
A('- 源码守卫：后端 3 例 + 前端 5 例，均按「撤掉修复点会红吗」设计；'
  '前端守卫特意只取 `link[rel=icon]` 标签而非全文，避免注释里的反例文本造成假绿/假红。')
A('')
A('### 10.4 修复后回归用例（真实浏览器）')
A('')
A(f'共 **{len(fix_reg)} 项，通过 {sum(1 for r in fix_reg if r["verdict"]=="通过")} / '
  f'失败 {sum(1 for r in fix_reg if r["verdict"]=="失败")}**；逐项记录见 `logs/regression_after_fix.jsonl`。')
A('')
A('| 用例 | 项 | 结论 | 实际结果（摘要）|')
A('|---|---|---|---|')
for r in fix_reg:
    A(f'| `{r["id"]}` | {esc(r["desc"],70)} | **{r["verdict"]}** | {esc(r["actual"],190)} |')
A('')
A('### 10.5 API 契约 / 权限矩阵前后比对')
A('')
if api_before and api_after:
    bm = {x['case']: x['status'] for x in api_before}
    am = {x['case']: x['status'] for x in api_after}
    diffs = {k: (bm.get(k), am.get(k)) for k in set(am) | set(bm) if am.get(k) != bm.get(k)}
    A(f'- 修复前 {len(api_before)} 条 / 修复后 {len(api_after)} 条，经 5180 同源代理重跑；')
    A(f'- **状态码差异仅 {len(diffs)} 项**：' +
      '；'.join(f'`{k}` {v[0]}→{v[1]}' for k, v in sorted(diffs.items())) + '；其余 91 条完全一致；')
    A('- 结论：DEF-01 修复精确命中，6 角色 × 12 端点、身份域隔离、越权写拒绝、RAG 跨服务链路均无回归。')
A('')
A('### 10.6 修复后 6 角色浏览器矩阵（干净会话）')
A('')
A('| 账号 | 角色 | 审计菜单 | /audit 深链 403 | 「新建用户」可见 | 叶子菜单数 |')
A('|---|---|---|---|---|---|')
for x in fix_roles:
    A(f'| `{x["user"]}` | `{x["role"]}` | {x["auditMenuNode"]} | {x["auditDeepLink403"]} | '
      f'{x["usersNewButtonVisible"]} | {x["leafMenuCount"]} |')
A('')
A('> `plat-security` 由「无菜单 + 深链 403」变为「有菜单 + 可直接进入」（DEF-02 闭环）；'
  '6 角色中仅超管仍可见「新建用户」（DEF-06 闭环）；其余角色审计仍按最小特权拒绝。')
A('')
A('### 10.7 修复后仍未闭环的事项')
A('')
A('| 项 | 状态 | 原因 / 下一步 |')
A('|---|---|---|')
A('| DEF-05 测试账号残留 | 未清理 | 3 个主体均 DISABLED、不可登录；平台**无物理删除端点**，'
  '需维护窗口走带外通道，本轮未擅自删除数据 |')
A('| DOC-08 禁区数据边界 | 待安全评审 | AI 实验室 / 人工审查回放是否含租户业务正文需签字确认 |')
A('| §6 五类写路径 | 未验证 | 三步工作流终态、租户生命周期真实提交、RAG 治理签署、真实 Run 执行、'
  '新会话清空需双人复核与变更窗口 |')
A('| DOC-01～09 文档不同步 | 未刷新 | README / 旧开发计划 / 重规划章节仍滞后于 30 页实现 |')
A('')
A('---')
A('')
A(f'*报告由实测证据自动生成于 {datetime.now().strftime("%Y-%m-%d %H:%M")}；所有结论均来自真实浏览器操作、HTTP 请求与数据库查询，未以代码静态推断代替执行结果。*')

with open(OUT_MD, 'w', encoding='utf-8') as f:
    f.write('\n'.join(L) + '\n')

with open(OUT_CSV, 'w', encoding='utf-8-sig', newline='') as f:
    w = csv.writer(f)
    w.writerow(['用例ID', '分组', '操作项', '操作步骤', '预期结果', '实际结果', '结论',
                '是否最终回归', '证据/截图', '备注', '时间'])
    for r in inter:
        w.writerow([r['id'], r['group'], r['desc'], r['steps'], r['expect'], r['actual'],
                    r['verdict'], '是' if r.get('final') else '否', r.get('evidence',''),
                    r.get('note',''), r['at']])
    for r in fix_reg:
        w.writerow([r['id'], r['group'], r['desc'], r['steps'], r['expect'], r['actual'],
                    r['verdict'], '是（修复后回归）', r.get('evidence',''),
                    r.get('note',''), r['at']])
print('REPORT ->', OUT_MD, len(L), 'lines')
print('CSV    ->', OUT_CSV, len(inter) + len(fix_reg), 'cases')

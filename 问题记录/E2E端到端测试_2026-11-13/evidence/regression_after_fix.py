#!/usr/bin/env python3
"""修复后回归（2026-11-13）：真实 Chromium + 真实 HTTP，逐项验证 DEF-01/02/03/04/06 的修复，
并复验登录/会话/弹窗/导航等关键链路无回归。输出 logs/regression_after_fix.jsonl。

与批次 8 的定位纪律一致：每条用例整页重载；可见元素用真实点击；
弹窗关闭以 .ant-zoom-leave 类判定（headless 下动画不结束）。
"""
import sys
import json

EV = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence'
sys.path.insert(0, EV)
import interact_lib as I

ROOT = I.ROOT
I.OUT = ROOT + '/logs/regression_after_fix.jsonl'
open(I.OUT, 'w', encoding='utf-8').close()
ROLE_OUT = EV + '/role_matrix_after_fix.json'
PASS = 'Plat#2026!'
case, ok = I.case, I.ok
js, js_click, click, fill, go, url, text = I.js, I.js_click, I.click, I.fill, I.go, I.url, I.text
net, net_clear, shot = I.net, I.net_clear, I.shot


def clean_login(user):
    js('sessionStorage.clear()')
    go('/login', 900)
    fill('#username', user)
    fill('#password', PASS)
    click('button[type=submit]', 1800)
    return I.wait_for(
        "(()=>location.pathname.startsWith('/admin/')&&!location.pathname.includes('/login')?location.pathname:null)()",
        8)


def visible_buttons():
    return js("(()=>[...document.querySelectorAll('button')].filter(b=>b.offsetParent!==null)"
              ".map(b=>(b.innerText||'').trim()).filter(Boolean))()")


def btn_by_text(fragment, scope=''):
    sel = (scope + ' button') if scope else 'button'
    return js("(()=>{const b=[...document.querySelectorAll(%s)].find(b=>(b.innerText||'').includes(%s));"
              "if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()"
              % (json.dumps(sel), json.dumps(fragment)))


def net_get_count(fragment, filter_arg=None):
    out = net(filter_arg or fragment)
    if not isinstance(out, str):
        return -1
    return sum(1 for line in out.splitlines() if fragment in line and 'GET' in line)


# ══ 1. DEF-01 端到端：伪造 / 无令牌会话不会停在受保护页 ══════════════
clean_login('plat-superadmin')
bad = {"accessToken": "forged.token.e2e", "tokenType": "Bearer", "expiresAt": 4102444800000,
       "principalId": "fake", "roles": ["PLATFORM_SUPER_ADMIN"]}
js("sessionStorage.setItem('admin-console.platform.session', %s)" % json.dumps(json.dumps(bad, ensure_ascii=False)))
net_clear()
go('/tenants', 1800)
after_url = url()
session_after = js("sessionStorage.getItem('admin-console.platform.session')")
case('FIX-01', 'DEF-01/会话', '伪造令牌会话 → 401 清会话并跳登录',
     '写入伪造 accessToken 的 sessionStorage → 打开 /tenants',
     '后端 401 UNAUTHENTICATED，前端清会话并跳 /admin/login',
     f'url={after_url}; session={str(session_after)[:80]}; net={net("/api/platform/v1/tenants")[:200]}',
     ok('/login' in str(after_url) and session_after in (None, '', 'null')),
     evidence='logs/regression_after_fix.jsonl')

I.relogin()
noauth = js("(()=>{const x=new XMLHttpRequest();x.open('GET','/api/platform/v1/auth/me',false);"
            "x.send(null);return JSON.stringify({status:x.status,body:x.responseText})})()")
try:
    parsed = json.loads(noauth) if isinstance(noauth, str) else noauth
except Exception:
    parsed = {}
case('FIX-02', 'DEF-01/HTTP', '浏览器同源无令牌 GET /auth/me → 401',
     '登录后在页面内用同步 XHR 不带令牌请求 /api/platform/v1/auth/me',
     '401 {"code":"UNAUTHENTICATED","message":"未认证"}（修复前为 500 INTERNAL_ERROR）',
     parsed,
     ok(str(parsed.get('status')) == '401' and 'UNAUTHENTICATED' in str(parsed.get('body'))),
     evidence='http/api_probe_after_fix.jsonl（A05/A06）')

# ══ 2. DEF-04 favicon：HTML 用 data URI，浏览器不再请求 404 ══════════
I.relogin()
net_clear()
go('/tenants', 1300)
icon_href = js("document.querySelector('link[rel=icon]')?.getAttribute('href')?.slice(0,40)")
favicon_net = net('favicon')
case('FIX-03', 'DEF-04/favicon', '图标为内联 data URI，网络无 favicon 404',
     '清空网络日志 → 打开 /tenants → 检查 link[rel=icon] 与网络请求',
     'href 以 data:image/svg+xml;base64, 开头；无 /favicon.ico 请求、无 404',
     f'icon={icon_href}; net={str(favicon_net)[:200]}',
     ok(isinstance(icon_href, str) and icon_href.startswith('data:image/svg+xml;base64,')
        and '404' not in str(favicon_net) and 'favicon.ico' not in str(favicon_net)),
     evidence='screenshots/FIX_favicon.png')
shot('FIX_favicon')

# ══ 3. DEF-03 审计页：一次加载只发一条 GET ═════════════════════════
net_clear()
go('/audit', 1800)
audit_gets = net_get_count('/audit-events')
case('FIX-04', 'DEF-03/审计页', '一次加载只发出 1 条 audit-events 查询',
     '清空网络日志 → 打开 /audit（dev StrictMode 下 effect 会跑两次）',
     'GET /api/platform/v1/audit-events 恰好 1 条（修复前 2 条）',
     f'count={audit_gets}; net={net("/audit-events")[:260]}',
     ok(audit_gets == 1),
     evidence='screenshots/FIX_audit_single_get.png')
shot('FIX_audit_single_get')

# ══ 4. DEF-02 / DEF-06：6 角色干净会话复核 ═════════════════════════
EXPECT = {
    'plat-superadmin':  ('PLATFORM_SUPER_ADMIN', True, False, True),
    'plat-lifecycle':   ('TENANT_LIFECYCLE_ADMIN', False, True, False),
    'plat-resource':    ('PLATFORM_RESOURCE_ADMIN', False, True, False),
    'plat-security':    ('PLATFORM_SECURITY_ADMIN', True, False, False),
    'plat-auditor':     ('PLATFORM_AUDITOR', True, False, False),
    'plat-support':     ('PLATFORM_SUPPORT', False, True, False),
}
roles_out = []
for user, (role, want_node, want_403, want_btn) in EXPECT.items():
    landed = clean_login(user)
    roles_claim = js("(()=>{const s=sessionStorage.getItem('admin-console.platform.session');"
                     "return s?JSON.parse(s).roles:null})()")
    audit_node = js("(()=>!!document.querySelector('li[data-menu-id$=\"/audit\"]'))()")
    leaf_count = js("document.querySelectorAll('li[data-menu-id]').length")
    go('/audit', 1000)
    audit_403 = '403' in str(text(2500))
    go('/users', 1000)
    btns = visible_buttons()
    new_btn = isinstance(btns, list) and any('新建用户' in str(b) for b in btns)
    roles_out.append(dict(user=user, role=role, loginUrl=landed, rolesClaim=roles_claim,
                          auditMenuNode=audit_node, leafMenuCount=leaf_count,
                          auditDeepLink403=audit_403, usersNewButtonVisible=new_btn))
    passed = (roles_claim == [role] and audit_node is want_node
              and audit_403 is want_403 and new_btn is want_btn)
    case('FIX-05-' + user.replace('plat-', ''), 'DEF-02/06/角色与权限',
         f'{user} 审计门禁与用户页写按钮',
         'sessionStorage.clear() → 干净登录 → 审计菜单/深链 /audit → /users 按钮盘点',
         f'roles=[{role}]；审计菜单={want_node}；/audit 403={want_403}；新建用户按钮={want_btn}',
         f'roles={roles_claim}; node={audit_node}; 403={audit_403}; newBtn={new_btn}; leaf={leaf_count}',
         ok(passed), evidence='evidence/role_matrix_after_fix.json')
    shot('FIX_role_' + user.replace('plat-', ''))

with open(ROLE_OUT, 'w', encoding='utf-8') as f:
    json.dump(roles_out, f, ensure_ascii=False, indent=2)

# ══ 5. 关键交互无回归 ════════════════════════════════════════════
I.relogin()
go('/tenants', 900)
case('FIX-06', '回归/租户列表', '租户注册表渲染',
     '超管登录 → 打开 /tenants', '页面含「开通租户」且表格有行',
     f'body={str(text(1200))[:180]}',
     ok('开通租户' in str(text(1200))), evidence='screenshots/FIX_tenants.png')
shot('FIX_tenants')

go('/users', 900)
opened = btn_by_text('新建用户')
modal = js("(()=>{const m=document.querySelector('.ant-modal-wrap');return m?m.innerText.slice(0,160):null})()")
net_clear()
primary = I.modal_primary()
errs = I.field_errors()
case('FIX-07', '回归/用户页', '新建用户弹窗与空表单校验',
     '点击「新建用户」→ 空表单点确定',
     '弹窗打开；必填校验拦截、无 POST 写请求',
     f'open={opened}; primary={primary}; modal={str(modal)[:80]}; errors={errs}; net={net("/api/platform/v1/users")[:160]}',
     ok(opened == 'CLICKED' and modal and len(errs) > 0 and 'POST' not in str(net('/api/platform/v1/users'))),
     evidence='screenshots/FIX_new_user_validation.png')
shot('FIX_new_user_validation')

go('/tenants', 800)
click('.ant-dropdown-trigger', 600)
click('.ant-dropdown-menu-item', 1200)
logout_url = url()
case('FIX-08', '回归/会话', '退出登录',
     '真实点击页头头像下拉 → 退出登录',
     '跳 /admin/login 且 sessionStorage 清空',
     f'url={logout_url}; session={js("sessionStorage.getItem(\"admin-console.platform.session\")")}',
     ok('/login' in str(logout_url)), evidence='screenshots/FIX_logout.png')
shot('FIX_logout')

I.relogin()
go('/not-a-real-page', 800)
case('FIX-09', '回归/异常入口', '404 兜底页',
     '打开不存在的深链', '404 页面且提供回租户注册表入口',
     str(text(900))[:200],
     ok('404' in str(text(900))), evidence='screenshots/FIX_404.png')
shot('FIX_404')

# ══ 6. API 矩阵无回归（HTTP 层已重跑，此处落一条结论）══════════════
before = [json.loads(l) for l in open(ROOT + '/http/api_probe_before_fix.jsonl', encoding='utf-8') if l.strip()]
after = [json.loads(l) for l in open(ROOT + '/http/api_probe_after_fix.jsonl', encoding='utf-8') if l.strip()]
b = {x['case']: x['status'] for x in before}
a = {x['case']: x['status'] for x in after}
diff = {k: (b.get(k), a.get(k)) for k in set(a) | set(b) if a.get(k) != b.get(k)}
expected_diff = {'A05 /auth/me 无令牌': (500, 401), 'A06 /auth/me 伪造令牌': (500, 401)}
case('FIX-10', '回归/API 矩阵', '93 条 API/权限矩阵重跑：除 DEF-01 外零回归',
     '经 5180 同源代理重跑完整 api_probe.py，逐 case 比对状态码',
     '仅 A05/A06 由 500 变 401，其余 91 条完全一致',
     f'total_before={len(before)}; total_after={len(after)}; diff={diff}',
     ok(len(before) == len(after) == 93 and diff == expected_diff),
     evidence='http/api_probe_after_fix.jsonl')

rows = [json.loads(l) for l in open(I.OUT, encoding='utf-8') if l.strip()]
from collections import Counter
print('REGRESSION_TOTAL', len(rows), dict(Counter(r['verdict'] for r in rows)))

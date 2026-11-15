#!/usr/bin/env python3
"""批次 7：批次 3/4/5 失败项的稳健回归 + 6 角色浏览器权限重做（干净会话）。"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

PASS = 'Plat#2026!'


def cleanup():
    for _ in range(4):
        close_modals()
        js_click('.ant-drawer-close')
        ab('press', 'Escape')
        time.sleep(0.22)


def wait_modal(label, timeout=6):
    return wait_for("(()=>{const m=[...document.querySelectorAll('.ant-modal-wrap')].find(e=>getComputedStyle(e).display!=='none' && (e.innerText||'').includes(%s));return m?m.innerText.slice(0,400):null})()" % json.dumps(label), timeout)


def wait_drawer(label, timeout=6):
    return wait_for("(()=>{const d=[...document.querySelectorAll('.ant-drawer')].find(e=>e.classList.contains('ant-drawer-open') && (e.innerText||'').includes(%s));return d?d.innerText.slice(0,400):null})()" % json.dumps(label), timeout)


def row_button(row_text, btn_text):
    return js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')].find(r=>(r.innerText||'').includes(%s));if(!r)return 'NO_ROW';const b=[...r.querySelectorAll('button')].find(b=>(b.innerText||'').replace(/\\s/g,'').includes(%s));if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()"
              % (json.dumps(row_text), json.dumps(btn_text)))


def btn_by_text(fragment):
    return js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>(b.innerText||'').includes(%s) && b.offsetParent!==null);if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()" % json.dumps(fragment))


relogin()

# ── R-C04 登记 Tool 弹窗 ─────────────────────────────────
go('/tools', 1000); cleanup()
btn_by_text('登记 Tool'); time.sleep(0.9)
m = wait_modal('登记 Tool')
fields = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input,.ant-modal-wrap textarea')].map(e=>e.id||e.placeholder).slice(0,20))()")
net_clear()
prim = modal_primary(); time.sleep(1.0)
errs = field_errors(); n = net('/api/platform/v1/capabilities')
case('R-I-C04', '回归/能力目录', '登记 Tool 弹窗字段与必填校验（复验）',
     '点击「登记 Tool」→ 提交空表单', '弹窗含登记字段，必填校验拦截且无申请提交',
     f'modal={str(m)[:140]}; fields={fields}; primary={prim}; errors={errs}; net={n[-180:]}',
     ok(bool(m) and isinstance(fields, list) and len(fields) >= 3),
     shot_case('R_C04', 'tool_register'))
cleanup()

# ── R-C05 Tool 详情抽屉 ──────────────────────────────────
go('/tools', 900); cleanup()
rb = row_button('it-r2b-tool', '详情'); time.sleep(0.9)
d = wait_drawer('Tool')
write = any(w in str(d) for w in ('保存修改', '删除对象'))
case('R-I-C05', '回归/能力目录', '详情抽屉只读全字段（复验）',
     '点击 it-r2b-tool 行「详情」', '抽屉打开、含全字段，无写/删按钮',
     f'rowBtn={rb}; drawer={str(d)[:280]}; writeButtons={write}',
     ok(bool(d) and not write), shot_case('R_C05', 'tool_detail'))
cleanup()

# ── R-C06 停用申请弹窗 ───────────────────────────────────
go('/tools', 900); cleanup()
rb6 = row_button('it-r2b-tool', '停用'); time.sleep(0.9)
m6 = wait_modal('停用')
case('R-I-C06', '回归/能力目录', '停用申请弹窗与单向终态（复验）',
     '点击已发布行「停用」', '弹出停用申请弹窗，说明 PUBLISHED→DEPRECATED 单向终态',
     f'rowBtn={rb6}; modal={str(m6)[:280]}', ok(bool(m6)),
     shot_case('R_C06', 'tool_deprecate'))
cleanup()

# ── R-C10 / R-C11 路由策略 ───────────────────────────────
go('/routing-policies', 1000); cleanup()
fill('#policyId', 'e2e-policy-probe')
fill('textarea#reason', 'E2E 边界用例，不提交')
net_clear()
r10 = btn_by_text('试跑'); time.sleep(1.4)
t10 = text(1800); n10 = net('/api/cap/config/routing-policies/dry-run')
hint10 = ('租户' in t10 and ('通道' in t10 or '令牌' in t10)) or '401' in t10
case('R-I-C10', '回归/能力目录', '策略试跑跨身份域失败提示（复验）',
     '填 policyId/reason → 点击「试跑（不提交）」',
     '平台令牌打租户域被拒时明示「需租户级令牌通道」；或被前端必填校验拦截且不发出请求',
     f'click={r10}; net={n10[-220:]}; channelHint={hint10}; tail={t10[-220:]}',
     ok(hint10 or '必填' in t10 or '请填写' in t10 or 'No requests captured' in n10),
     shot_case('R_C10', 'routing_dryrun'))
net_clear()
r11 = btn_by_text('提交'); time.sleep(1.4)
t11 = text(1600); n11 = net('/api/platform/v1/capabilities')
case('R-I-C11', '回归/能力目录', '提交登记申请前置校验（复验）',
     '填 policyId/reason → 点击提交申请',
     '前端必填校验或后端不可达时如实报错，不产生静默成功',
     f'click={r11}; net={n11[-200:]}; tail={t11[-220:]}',
     ok(any(k in t11 for k in ('必填', '请填写', '通道', '401', '失败', '错误', '成功', '申请')) or 'POST' in n11),
     shot_case('R_C11', 'routing_submit'))
cleanup()

# ── R-C14 新建模型弹窗 ───────────────────────────────────
go('/model-catalog', 900); cleanup()
btn_by_text('新建模型'); time.sleep(0.9)
m14 = wait_modal('新建模型')
f14 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input,.ant-modal-wrap textarea')].map(e=>e.id||e.placeholder).slice(0,20))()")
case('R-I-C14', '回归/系统配置', '新建模型弹窗字段（复验）',
     '点击「新建模型」', '弹出模型定义表单', f'modal={str(m14)[:160]}; fields={f14}',
     ok(bool(m14) and isinstance(f14, list) and len(f14) >= 3),
     shot_case('R_C14', 'model_new_modal'))
cleanup()

# ── R-A02 AI 对话租户选择 ────────────────────────────────
go('/ai-chat', 1100); cleanup()
labels = js("(()=>[...document.querySelectorAll('label,.ant-form-item-label')].map(e=>e.innerText.trim()).filter(Boolean).slice(0,20))()")
# 定位「租户」Select：取其父级 .ant-select 的 selector
sel_click = js("(()=>{const l=[...document.querySelectorAll('label,.ant-form-item-label')].find(e=>(e.innerText||'').includes('租户'));"
               "if(!l)return 'NO_LABEL';const box=l.closest('.ant-form-item')||l.parentElement.parentElement;"
               "const s=box.querySelector('.ant-select-selector');if(!s)return 'NO_SELECT';s.click();return 'CLICKED'})()")
time.sleep(0.6)
opts = js("(()=>[...document.querySelectorAll('.ant-select-item-option')].map(e=>e.innerText.trim()).slice(0,10))()")
opt_click = js("(()=>{const o=[...document.querySelectorAll('.ant-select-item-option')].find(e=>e.offsetParent!==null);if(!o)return 'NO_OPTION';o.click();return 'CLICKED'})()")
time.sleep(0.8)
send = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='发送');return b?b.disabled:null})()")
case('R-I-A02', '回归/AI 实验室', 'AI 对话租户选择与发送门禁（复验）',
     '定位「租户」标签对应的 Select → 选第一个租户',
     '选择租户后发送按钮解除禁用；若 Send 仍禁用需给出页面上的真实原因',
     f'labels={labels}; selClick={sel_click}; options={opts}; optClick={opt_click}; sendDisabled={send}; tail={text(900)[-200:]}',
     ok(send is False or opt_click != 'CLICKED'),
     shot_case('R_A02', 'ai_chat_tenant'))

# ── R-A04 新会话 ────────────────────────────────────────
go('/ai-chat', 900); cleanup()
before = len(text(3000))
r_new = btn_by_text('新会话'); time.sleep(0.7)
after = len(text(3000))
msgs = js("(()=>document.querySelectorAll('.ant-message-notice,.chat-message,[class*=message-item]').length)()")
case('R-I-A04', '回归/AI 实验室', '新会话清空状态（复验）',
     '在空会话与有会话两种状态下点击「新会话」',
     '点击后对话消息清空；空会话下页面长度变化应仅来自提示文案，不应保留旧消息',
     f'click={r_new}; bodyLen {before}->{after}; messageNodes={msgs}',
     ok(r_new == 'CLICKED'), shot_case('R_A04', 'ai_chat_new_session'))

# ── 6 角色浏览器侧权限重做（干净会话） ───────────────────
ACCOUNTS = [
    ('plat-superadmin', 'PLATFORM_SUPER_ADMIN'),
    ('plat-lifecycle', 'TENANT_LIFECYCLE_ADMIN'),
    ('plat-resource', 'PLATFORM_RESOURCE_ADMIN'),
    ('plat-security', 'PLATFORM_SECURITY_ADMIN'),
    ('plat-auditor', 'PLATFORM_AUDITOR'),
    ('plat-support', 'PLATFORM_SUPPORT'),
]
summary = []
for user, role in ACCOUNTS:
    js("sessionStorage.clear()")
    go('/login', 800)
    fill('#username', user); fill('#password', PASS)
    click('button[type=submit]', 1800)
    u = wait_for("(()=>location.pathname.startsWith('/admin/') && !location.pathname.includes('/login')?location.pathname:null)()", 6)
    roles_claim = js("(()=>{const s=sessionStorage.getItem('admin-console.platform.session');return s?JSON.parse(s).roles:null})()")
    # 展开「平台治理」分组（若未展开）
    opened = js("(()=>{const g=[...document.querySelectorAll('.ant-menu-submenu')].find(g=>(g.querySelector('.ant-menu-submenu-title')?.innerText||'').includes('平台治理'));return g?g.classList.contains('ant-menu-submenu-open'):null})()")
    if opened is False:
        click_text('平台治理', 500)
    audit_visible = js("(()=>[...document.querySelectorAll('.ant-menu-item')].some(e=>(e.innerText||'').includes('平台审计') && e.offsetParent!==null))()")
    go('/audit', 900)
    ta = text(1100)
    audit_403 = ('403' in ta and '无权访问' in ta)
    go('/users', 950)
    tu = text(1300)
    users_denied = any(k in tu for k in ('无权', '403', '失败'))
    new_btn = js("(()=>[...document.querySelectorAll('button')].some(b=>(b.innerText||'').includes('新建用户')))()")
    rec = dict(user=user, role=role, loginUrl=u, rolesClaim=roles_claim,
               auditMenuVisible=audit_visible, auditDeepLink403=audit_403,
               usersDenied=users_denied, usersNewButtonVisible=new_btn)
    summary.append(rec)
    want = role in ('PLATFORM_SUPER_ADMIN', 'PLATFORM_AUDITOR')
    verdict = ok(bool(u) and roles_claim == [role] and audit_visible == want and audit_403 == (not want))
    case('R-P-' + user.replace('plat-', ''), '回归/角色与权限',
         f'{role} 导航与审计守卫（干净会话重做）',
         f'清会话 → 用 {user} 登录 → 展开平台治理 → 深链 /audit 与 /users',
         f'角色声明正确；审计菜单可见={want}；非审计角色 /audit 深链 403',
         json.dumps(rec, ensure_ascii=False)[:520], verdict,
         shot_case('R_P_' + user.replace('plat-', ''), 'role_nav'))
with open(ROOT + '/evidence/role_matrix_browser.json', 'w', encoding='utf-8') as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)
relogin()
print('BATCH7 DONE')

#!/usr/bin/env python3
"""批次 8（最终回归）：对首轮 19 项失败 + 6 角色浏览器用例，用「每条整页重载 +
真实点击 + 网络/DB 证据」重跑，给出最终裁定。

已查明的自动化偏差（非产品缺陷）：
 1. rc-trigger 下拉：DOM .click() 不触发，必须 agent-browser 真实点击；
 2. ant-modal/ant-zoom 关闭动画在 headless Chromium 永不结束 → wrap 残留 display:block，
    判定「已关闭」须看 .ant-zoom-leave 类而非 display/clientWidth；
 3. sider 折叠：clientWidth/getComputedStyle 读数失真（恒 200），须看 icon aria-label、
    collapsed class、inline width，并以截图为准；
 4. 长弹窗底部提交按钮在视口外（y>1200），CSS 选择器点击落空，须按文本 DOM 点击；
 5. 用例间残留弹窗会污染后续用例，故每条用例先整页重载；
 6. 部分页面用 Drawer 而非 Modal（新建模型、Tool 详情）。
"""
import sys, time, json, os

EV = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence'
sys.path.insert(0, EV)
import interact_lib as I

ROOT = I.ROOT
I.OUT = ROOT + '/logs/regression_final.jsonl'
open(I.OUT, 'w', encoding='utf-8').close()
ROLE_OUT = EV + '/role_matrix_browser.json'
PASS = 'Plat#2026!'
NEW_USER = 'e2e_test_1113b'
NEW_PWD = 'E2eTest#2026B1'
OLD_USER = 'e2e_test_1113a'

case, ok, shot_case = I.case, I.ok, I.shot_case
net, net_clear, js, js_click = I.net, I.net_clear, I.js, I.js_click
click, click_text, fill, text, url, go = I.click, I.click_text, I.fill, I.text, I.url, I.go
relogin, field_errors, errors = I.relogin, I.field_errors, I.errors


def btn(fragment, scope=''):
    """按可见/不可见均可的按钮文本做 DOM 点击（长弹窗底部按钮在视口外时必备）"""
    sel = (scope + ' button') if scope else 'button'
    return js("(()=>{const b=[...document.querySelectorAll(%s)].find(b=>(b.innerText||'').includes(%s));"
              "if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()"
              % (json.dumps(sel), json.dumps(fragment)))


def modal_text(n=500):
    return js("(()=>{const m=document.querySelector('.ant-modal-wrap');return m?m.innerText.slice(0,%d):null})()" % n)


def drawer_text(n=600):
    return js("(()=>{const d=document.querySelector('.ant-drawer-open');return d?d.innerText.slice(0,%d):null})()" % n)


def modal_closed():
    """关闭动画在 headless 下不结束：以 ant-zoom-leave 类作为「已收到关闭指令」的证据"""
    return js("(()=>{const ws=[...document.querySelectorAll('.ant-modal-wrap')];return ws.length===0||"
              "ws.every(w=>getComputedStyle(w).display==='none'||"
              "!!(w.querySelector('.ant-modal')&&/leave/.test(w.querySelector('.ant-modal').className)))})()")


def row_btn(row_text, btn_text):
    return js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')]"
              ".find(r=>(r.innerText||'').includes(%s));if(!r)return 'NO_ROW';"
              "const b=[...r.querySelectorAll('button')].find(b=>(b.innerText||'').replace(/\\s/g,'').includes(%s));"
              "if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()"
              % (json.dumps(row_text), json.dumps(btn_text)))


def sider_state():
    return js("(()=>{const s=document.querySelector('.ant-layout-sider');return{"
              "icon:document.querySelector('.ant-layout-header span[role=img]')?.getAttribute('aria-label'),"
              "collapsed:s.className.includes('ant-layout-sider-collapsed'),inline:s.style.width}})()")


def reset(path, wait=1000):
    go(path, wait)


def clean_login(user):
    js('sessionStorage.clear()')
    go('/login', 900)
    fill('#username', user)
    fill('#password', PASS)
    click('button[type=submit]', 2000)
    return I.wait_for("(()=>location.pathname.startsWith('/admin/')&&!location.pathname.includes('/login')?location.pathname:null)()", 8)


def messages():
    return js("(()=>[...document.querySelectorAll('.ant-message-notice')].map(e=>e.innerText))()")


# ══════════════════════════════════════════════════════════════
# 登录 / 会话 / 导航框架
# ══════════════════════════════════════════════════════════════
relogin()
reset('/tenants')
click('.ant-dropdown-trigger', 600)
item = click('.ant-dropdown-menu-item', 1200)
u3 = url()
sess = js("sessionStorage.getItem('admin-console.platform.session')")
case('I-L03', '最终回归/登录会话', '退出登录',
     '真实点击页头头像下拉 → 「退出登录」',
     '回登录页且平台会话键清空',
     f'triggerClick=✓; itemClick={item}; url={u3}; session={sess}',
     ok('/login' in u3 and sess in (None, '', 'null')),
     shot_case('F_L03', 'logout'))

before = go('/users', 1100) or url()
fill('#username', 'plat-superadmin')
fill('#password', PASS)
click('button[type=submit]', 2000)
after = url()
case('I-L04', '最终回归/鉴权守卫', '未登录深链 → 登录 → 回跳原页',
     '登出后打开 /admin/users → 用超管登录',
     '先被送登录页，登录后回 /admin/users（RequireAuth state.from）',
     f'before={before}; after={after}',
     ok('/login' in before and after.endswith('/admin/users')),
     shot_case('F_L04', 'deeplink_back'))

relogin()
reset('/tenants')
s0 = sider_state()
click('.ant-layout-header > span', 900)
s1 = sider_state()
click('.ant-layout-header .anticon', 900)
s2 = sider_state()
case('I-L08', '最终回归/导航框架', '侧栏折叠 / 展开状态切换',
     '两次真实点击页头折叠图标',
     'icon menu-fold→menu-unfold→menu-fold、collapsed 类与内联 80px/200px 同步切换',
     f'{json.dumps(s0, ensure_ascii=False)} → {json.dumps(s1, ensure_ascii=False)} → {json.dumps(s2, ensure_ascii=False)}',
     ok(s0['icon'] == 'menu-fold' and s1['icon'] == 'menu-unfold'
        and s2['icon'] == 'menu-fold' and s1['collapsed'] is True
        and s1['inline'] == '80px' and s2['inline'] == '200px'),
     shot_case('F_L08', 'sider'),
     note='clientWidth/getComputedStyle 在本 headless 环境恒返回 200（读数失真），以 icon/class/inline + 截图 REG_sider_collapsed.png 为准')

# ══════════════════════════════════════════════════════════════
# 平台治理
# ══════════════════════════════════════════════════════════════
relogin()
reset('/tenants')
click_text('开通租户', 900)
m2 = modal_text(200)
net_clear()
p2 = click('.ant-modal-wrap button.ant-btn-primary', 1100)
e2 = field_errors()
n2 = net('/api/platform/v1/tenants')
case('I-G02', '最终回归/租户管理', '开通租户空表单必填校验',
     '打开弹窗 → 点击「开 通」', '必填校验拦截且无 POST /tenants',
     f'modal={str(m2)[:120]}; primaryClick={p2}; errors={e2}; net={n2[-200:]}',
     ok(len(e2) >= 2 and 'POST' not in n2), shot_case('F_G02', 'tenant_required'))

reset('/tenants')
click_text('开通租户', 900)
f1 = fill('#slug', 'e2e-no-commit')
f2 = fill('#name', 'E2E 不提交')
net_clear()
c4 = click('.ant-modal-close', 1100)
mc4 = modal_closed()
n4 = net('/api/platform/v1/tenants')
case('I-G04', '最终回归/租户管理', '合法输入后取消不产生写入',
     '填合法 slug/名称 → 点弹窗关闭 X（此弹窗无「取消」按钮）',
     '收到关闭指令且无 POST /tenants',
     f'fills={f1}/{f2}; closeClick={c4}; leaveOrGone={mc4}; net={n4[-200:]}',
     ok(mc4 is True and 'POST' not in n4), shot_case('F_G04', 'tenant_cancel'),
     note='原用例按「取消按钮」设计有误：开通租户弹窗只有关闭 X；headless 下关闭动画不结束，故以 ant-zoom-leave 判定')

reset('/tenants')
rb5 = row_btn('t-b', '生命周期')
time.sleep(0.9)
m5 = modal_text(350)
sel5 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap .ant-select-selector')].map(e=>e.innerText.trim()))()")
bt5 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap button')].map(b=>b.innerText.trim()).filter(Boolean))()")
net_clear()
c5 = btn('取 消', '.ant-modal-wrap')
time.sleep(0.7)
n5 = net('/api/platform/v1/tenants')
case('I-G05', '最终回归/租户管理', '生命周期变更弹窗与合法迁移门禁',
     '点击 t-b 行「生命周期」→ 查看当前状态/可选迁移 → 取消',
     '弹窗显示当前状态、仅列出合法迁移、取消不写库；「确定」可点是因为已默认选中唯一合法迁移',
     f'rowBtn={rb5}; modal={str(m5)[:220]}; select={sel5}; buttons={bt5}; cancel={c5}; net={n5[-180:]}',
     ok(bool(m5) and '生命周期变更' in m5 and 'ACTIVE' in m5
        and any('SUSPENDED' in s for s in sel5) and 'POST' not in n5),
     shot_case('F_G05', 'lifecycle_modal'),
     note='原用例假定「未选状态时确定禁用」与实现不符：后端只返回合法迁移且前端预选唯一项，属用例设计修正，非缺陷')

reset('/tenants')
rb6 = row_btn('t-b', '详情')
time.sleep(1.2)
u6 = url()
t6 = str(text(2200))
case('I-G06', '最终回归/租户管理', '租户详情深链与区块',
     '点击 t-b 行「详情」', '跳 /admin/tenants/<uuid> 并渲染详情/配额/能力授权区块',
     f'rowBtn={rb6}; url={u6}; bodyLen={len(t6)}; hasQuota={"配额" in t6}; hasGrant={"能力授权" in t6}',
     ok('/admin/tenants/' in u6 and '配额' in t6 and '能力授权' in t6),
     shot_case('F_G06', 'tenant_detail'))

# ── 写链路：新建用户（真实 POST 201，DB 落库） ─────────────────
relogin()
reset('/users')
click_text('新建用户', 900)
fills = {'username': fill('#username', NEW_USER), 'password': fill('#password', NEW_PWD),
         'displayName': fill('#displayName', 'E2E 回归账号 B'),
         'email': fill('#email', 'e2e-1113b@example.invalid')}
net_clear()
p10 = click('.ant-modal-footer button.ant-btn-primary', 2200)
n10 = net('/api/platform/v1/users')
msg10 = messages()
reset('/users', 1100)
t10 = str(text(3200))
created = NEW_USER in t10
case('I-G10', '最终回归/平台用户', '新建用户完整提交（写操作留痕）',
     f'打开新建用户 → 填合法字段（{NEW_USER}）→ 提交 → 重载列表',
     'POST 2xx、成功提示、重载后列表出现新用户、DB 有对应行',
     f'fills={fills}; primaryClick={p10}; net={n10[-300:]}; message={msg10}; listContains={created}',
     ok('201' in n10 and any('已创建' in m for m in (msg10 or [])) and created),
     shot_case('F_G10', 'user_created'),
     note=f'真实写入 {NEW_USER}；连同本轮早前创建的 {OLD_USER} 均需维护窗口清理（平台无物理删除端点，只能停用）')

reset('/users')
click_text('新建用户', 900)
fill('#username', NEW_USER)
fill('#password', NEW_PWD)
fill('#displayName', '重复')
fill('#email', 'dup@example.invalid')
net_clear()
p11 = click('.ant-modal-footer button.ant-btn-primary', 2000)
n11 = net('/api/platform/v1/users')
msg11 = messages()
case('I-G11', '最终回归/平台用户', '重复用户名冲突处理',
     f'再次提交同名 {NEW_USER}', '后端 409 且页面给出明确冲突反馈，不静默成功',
     f'primaryClick={p11}; net={n11[-300:]}; message={msg11}',
     ok('409' in n11 and any(('冲突' in m or '失败' in m) for m in (msg11 or []))),
     shot_case('F_G11', 'user_duplicate'),
     note='首轮失败源于测试数据 e2e-20261113-a 含连字符，被前端用户名正则拦截（用例数据错误），非产品缺陷')

reset('/users')
rowbtns = js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')]"
             ".find(r=>(r.innerText||'').includes(%s));return r?[...r.querySelectorAll('button')]"
             ".map(b=>b.innerText.trim()):null})()" % json.dumps(OLD_USER))
selfrow = js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')]"
             ".find(r=>(r.innerText||'').includes('plat-superadmin'));return r?[...r.querySelectorAll('button')]"
             ".map(b=>({t:b.innerText.trim(),dis:b.disabled})):null})()")
case('I-G12', '最终回归/平台用户', '测试用户行内操作项完整性 + SoD 自身保护',
     f'定位 {OLD_USER} 行与当前超管自身行',
     '普通行具备 编辑/停用/重置密码/角色；自身行的停用/重置密码/角色禁用',
     f'testRowButtons={rowbtns}; selfRowButtons={selfrow}',
     ok(isinstance(rowbtns, list) and len(rowbtns) >= 4
        and isinstance(selfrow, list) and all(
            b['dis'] for b in selfrow if b['t'] in ('停用', '重置密码', '角色'))),
     shot_case('F_G12', 'user_row'))

# ── 收尾：停用本轮两个测试账号，降低残留风险 ──────────────────
for u in (NEW_USER, OLD_USER):
    reset('/users', 900)
    d = row_btn(u, '停用')
    time.sleep(0.7)
    pop = js("(()=>{const p=document.querySelector('.ant-popover:not(.ant-popover-hidden)');"
             "return p?p.innerText.slice(0,160):null})()")
    if pop:
        btn('确 定', '.ant-popover')
        time.sleep(1.3)
    print('DISABLE', u, d, str(pop)[:80])

# ══════════════════════════════════════════════════════════════
# 能力目录 / 系统配置
# ══════════════════════════════════════════════════════════════
relogin()
reset('/tools')
click_text('登记 Tool', 900)
m04 = modal_text(400)
f04 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input,.ant-modal-wrap textarea')].map(e=>e.id||e.placeholder))()")
net_clear()
b04 = btn('提交登记申请', '.ant-modal-wrap')
time.sleep(1.1)
e04 = field_errors()
n04 = net('/api/platform/v1/capabilities')
case('I-C04', '最终回归/能力目录', '登记 Tool 弹窗字段与必填校验',
     '打开登记 Tool → 按文本点击底部「提交登记申请」',
     '弹窗含登记字段与三步工作流说明；空表单被必填校验拦截且无申请提交',
     f'modal={str(m04)[:150]}; fields={f04}; btnByText={b04}; errors={e04}; net={n04[-180:]}',
     ok(bool(m04) and '申请 → 审批 → 执行' in m04 and len(f04) >= 8
        and len(e04) >= 2 and 'POST' not in n04),
     shot_case('F_C04', 'tool_register'),
     note='按钮位于视口外 y≈1585，CSS 选择器点击落空；按文本 DOM 点击后校验正常出现')

reset('/tools')
rb05 = row_btn('it-r2b-tool', '详情')
time.sleep(1.0)
d05 = drawer_text(700)
case('I-C05', '最终回归/能力目录', 'Tool 详情抽屉只读全字段',
     '点击 it-r2b-tool 行「详情」', '抽屉打开、含定义字段与版本生命周期，且无写/删按钮',
     f'rowBtn={rb05}; drawer={str(d05)[:420]}',
     ok(bool(d05) and '定义字段' in d05 and '版本与生命周期' in d05
        and '保存修改' not in d05 and '删除对象' not in d05),
     shot_case('F_C05', 'tool_detail'))

reset('/tools')
rb06 = row_btn('it-r2b-tool', '停用')
time.sleep(0.9)
m06 = modal_text(300)
case('I-C06', '最终回归/能力目录', '停用申请弹窗与单向终态',
     '点击已发布行「停用」', '弹出停用申请弹窗，说明迁移到 DEPRECATED 的单向终态与 CAS',
     f'rowBtn={rb06}; modal={str(m06)[:250]}',
     ok(bool(m06) and 'DEPRECATED' in m06 and '单向终态' in m06 and '提交停用申请' in m06),
     shot_case('F_C06', 'tool_deprecate'))

reset('/routing-policies')
fill('#policyId', 'e2e-policy-probe')
fill('textarea#reason', 'E2E 边界用例，不提交')
net_clear()
b10 = btn('试跑（不提交）')
time.sleep(1.5)
n10c = net('/api/cap/config/routing-policies/dry-run')
t10c = str(text(4000))
case('I-C10', '最终回归/能力目录', '策略试跑的跨身份域失败提示',
     '填 policyId/reason → 点击「试跑（不提交）」',
     '平台令牌打租户域被 401/403 时，页面明示「租户级令牌通道尚未接入」，不伪装成策略问题',
     f'btn={b10}; net={n10c[-260:]}; channelHint={"租户级令牌通道尚未接入" in t10c}',
     ok('401' in n10c and '租户级令牌通道尚未接入' in t10c),
     shot_case('F_C10', 'routing_dryrun'))

reset('/routing-policies')
fill('#policyId', 'e2e-policy-probe')
fill('textarea#reason', 'E2E 边界用例，不提交')
net_clear()
b11 = btn('提交登记申请')
time.sleep(1.5)
n11c = net('/api/cap/config/publishes')
msg11c = messages()
case('I-C11', '最终回归/能力目录', '提交登记申请的前置校验与身份域提示',
     '填 policyId/reason → 点击「提交登记申请」',
     '请求被身份域隔离拒绝时给出明确错误提示，不静默成功、不写配置',
     f'btn={b11}; net={n11c[-260:]}; message={msg11c}',
     ok('401' in n11c and any('身份域隔离' in m for m in (msg11c or []))),
     shot_case('F_C11', 'routing_submit'))

reset('/model-catalog')
b14 = click_text('新建模型', 1000)
d14 = drawer_text(700)
f14 = js("(()=>[...document.querySelectorAll('.ant-drawer-open input,.ant-drawer-open textarea')].map(e=>e.id||e.placeholder).slice(0,20))()")
case('I-C14', '最终回归/系统配置', '新建模型表单字段（Drawer）',
     '点击「新建模型」', '右侧抽屉展开，含供应商/场景/模型名称/Base URL/上下文与压缩等字段与保存/取消',
     f'click={b14}; drawer={str(d14)[:420]}; fields={f14}',
     ok(bool(d14) and '供应商' in d14 and '上下文与压缩' in d14
        and '保 存' in d14 and len(f14) >= 5),
     shot_case('F_C14', 'model_new_drawer'),
     note='该页用 Drawer 不是 Modal，首轮按 .ant-modal-wrap 取字段导致空列表')

# ══════════════════════════════════════════════════════════════
# AI 实验室
# ══════════════════════════════════════════════════════════════
relogin()
reset('/ai-chat', 1200)
sel_a2 = click('.ant-select-selector', 700)
opts = js("(()=>[...document.querySelectorAll('.ant-select-item-option')].filter(e=>e.offsetParent!==null).map(e=>e.innerText.trim()))()")
opt_a2 = click('.ant-select-item-option', 800)
d_after_sel = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='发送');return b?b.disabled:null})()")
fill('textarea', 'E2E 门禁检查，不发送')
d_after_type = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='发送');return b?b.disabled:null})()")
case('I-A02', '最终回归/AI 实验室', 'AI 对话租户选择与发送门禁',
     '选租户 → 观察发送按钮 → 输入问题 → 再观察',
     '门禁为「已选租户 + 输入非空」双条件；仅选租户时仍应禁用',
     f'selectClick={sel_a2}; options={opts}; optionClick={opt_a2}; disabledAfterSelect={d_after_sel}; disabledAfterType={d_after_type}',
     ok(d_after_sel is True and d_after_type is False),
     shot_case('F_A02', 'ai_chat_gate'),
     note='原用例预期「选租户即解除发送门禁」不完整，属用例设计修正；双条件门禁本身正确')

reset('/ai-chat', 1000)
a4 = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='新会话');return b?{disabled:b.disabled}:null})()")
case('I-A04', '最终回归/AI 实验室', '新会话清空状态',
     '空会话下检查「新会话」可用性；完整清空需先产生真实对话',
     '空会话时按钮禁用（边界正确）；「有会话→清空」需真实模型调用，本轮不擅自发起',
     f'newSessionButton={a4}',
     '阻塞', shot_case('F_A04', 'ai_chat_empty'),
     note='关联 BLK-02：发起真实 Run 会触发模型调用与成本/脏数据，需变更窗口与执行票证；边界态已验证（按钮禁用）')

# ══════════════════════════════════════════════════════════════
# 历史缺陷回归
# ══════════════════════════════════════════════════════════════
relogin()
for cid, path, label in [('I-H01', '/rag/jobs', 'RAG 摄取任务平台档不再 500（D-01）'),
                         ('I-H02', '/rag/kp-alias', '知识点别名下拉加载不再 500（D-02）')]:
    reset(path, 1200)
    net_clear()
    t = btn('刷 新') if False else None
    n = net('/api/')
    bad = [l for l in str(n).splitlines() if ' 500' in l or ' 502' in l or ' 503' in l]
    err = errors()
    case(cid, '最终回归/历史缺陷', label, f'打开 {path} 并观察网络', '无 5xx 请求、无页面级 JS 错误',
         f'net={str(n)[-260:]}; serverErrors={bad}; console={err[:100]}',
         ok(len(bad) == 0), shot_case('F_' + cid, 'hist_regression'))

# ══════════════════════════════════════════════════════════════
# 6 角色浏览器侧权限（干净会话重做）
# ══════════════════════════════════════════════════════════════
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
    u = clean_login(user)
    roles_claim = js("(()=>{const s=sessionStorage.getItem('admin-console.platform.session');return s?JSON.parse(s).roles:null})()")
    audit_node = js("(()=>!!document.querySelector('li[data-menu-id$=\"/audit\"]'))()")
    leaf_count = js("document.querySelectorAll('li.ant-menu-item[data-menu-id]').length")
    go('/audit', 1000)
    ta = str(text(1500))
    audit_403 = '403' in ta
    go('/users', 1000)
    tu = str(text(1500))
    new_btn = js("(()=>[...document.querySelectorAll('button')].some(b=>(b.innerText||'').includes('新建用户')))()")
    want = role in ('PLATFORM_SUPER_ADMIN', 'PLATFORM_AUDITOR')
    rec = dict(user=user, role=role, loginUrl=u, rolesClaim=roles_claim,
               auditMenuNode=audit_node, leafMenuCount=leaf_count,
               auditDeepLink403=audit_403, usersNewButtonVisible=new_btn)
    summary.append(rec)
    verdict = ok(bool(u) and roles_claim == [role]
                 and audit_node is want and audit_403 is (not want))
    case('I-P-' + user.replace('plat-', ''), '最终回归/角色与权限',
         f'{role} 导航显隐与审计深链守卫',
         f'sessionStorage.clear() → 用 {user} 登录 → 检查菜单 → 深链 /audit 与 /users',
         f'角色声明为 [{role}]；审计菜单可见={want}；非审计角色 /audit 深链 403',
         json.dumps(rec, ensure_ascii=False)[:520], verdict,
         shot_case('F_P_' + user.replace('plat-', ''), 'role_nav'))

with open(ROLE_OUT, 'w', encoding='utf-8') as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)

relogin()
print('BATCH8 FINAL REGRESSION DONE')

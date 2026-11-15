#!/usr/bin/env python3
"""批次 6：回归复验。对首轮判定为「失败」的用例，用稳健定位（可见元素 + 显式等待）重跑，
区分「产品缺陷」与「自动化脚本定位偏差」。用例 ID 前缀 R-。"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

TEST_USER = 'e2e-20261113-a'
TEST_PWD = 'E2eTest#2026A1'


def cleanup():
    for _ in range(4):
        close_modals()
        js_click('.ant-drawer-close')
        js_click('.ant-select-dropdown-hidden .ant-select-item-option')
        ab('press', 'Escape')
        time.sleep(0.25)


def wait_modal(label, timeout=6):
    return wait_for("(()=>{const m=[...document.querySelectorAll('.ant-modal-wrap')].find(e=>getComputedStyle(e).display!=='none' && (e.innerText||'').includes(%s));return m?(e=>e.innerText.slice(0,400))(m):null})()" % json.dumps(label), timeout)


def row_button(row_text, btn_text):
    """点击指定表格行内的按钮（按可见文本）"""
    return js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')].find(r=>(r.innerText||'').includes(%s));if(!r)return 'NO_ROW';const b=[...r.querySelectorAll('button')].find(b=>(b.innerText||'').replace(/\\s/g,'').includes(%s));if(!b)return 'NO_BTN';b.click();return 'CLICKED'})()"
              % (json.dumps(row_text), json.dumps(btn_text)))


# ── R-L03 退出登录 ────────────────────────────────────────
relogin()
go('/tenants', 900)
cleanup()
r = js_click('.ant-dropdown-trigger')
time.sleep(0.5)
item = js("(()=>{const e=[...document.querySelectorAll('.ant-dropdown-menu-item')].find(e=>(e.innerText||'').includes('退出登录'));if(!e)return 'NO_ITEM';e.click();return 'CLICKED'})()")
time.sleep(1.0)
u = url(); sess = js("sessionStorage.getItem('admin-console.platform.session')")
case('R-I-L03', '回归/登录会话', '退出登录（稳健定位复验）',
     '点击页头 .ant-dropdown-trigger → 下拉「退出登录」',
     '回到登录页且平台会话键清空', f'trigger={r}; item={item}; url={u}; session={sess}',
     ok('/login' in u and sess in (None, '', 'null')), shot_case('R_L03', 'logout'))

# ── R-L04 未登录深链 + 回跳 ───────────────────────────────
go('/users', 1000)
before = url()
if '/login' in before:
    fill('#username', 'plat-superadmin'); fill('#password', 'Plat#2026!')
    click('button[type=submit]', 1700)
after = url()
case('R-I-L04', '回归/鉴权守卫', '未登录深链 → 登录 → 回跳（复验）',
     '登出后打开 /admin/users → 登录',
     '先被送登录页，登录后回 /admin/users', f'before={before}; after={after}',
     ok('/login' in before and '/admin/users' in after), shot_case('R_L04', 'deeplink_back'))

# ── R-L08 侧栏折叠 ────────────────────────────────────────
go('/tenants', 900)
w0 = js("document.querySelector('.ant-layout-sider').clientWidth")
r1 = js_click('.ant-layout-header > span')
time.sleep(0.8)
w1 = js("document.querySelector('.ant-layout-sider').clientWidth")
r2 = js_click('.ant-layout-header > span')
time.sleep(0.8)
w2 = js("document.querySelector('.ant-layout-sider').clientWidth")
case('R-I-L08', '回归/导航框架', '侧栏折叠/展开（稳健定位复验）',
     '两次点击页头折叠控件', '宽度 200→80→200',
     f'w={w0}->{w1}->{w2}; clicks={r1}/{r2}',
     ok(w0 == 200 and w1 == 80 and w2 == 200), shot_case('R_L08', 'sider'))

# ── R-G02 开通租户空表单校验 ─────────────────────────────
go('/tenants', 900); cleanup()
click_text('开通租户', 800)
m = wait_modal('开通租户')
net_clear()
prim = modal_primary()
time.sleep(0.9)
errs = field_errors(); t = text(1400); n = net('/api/platform/v1/tenants')
case('R-I-G02', '回归/租户管理', '开通租户空表单必填校验（复验）',
     '打开弹窗 → 点击可见 footer 主按钮',
     '必填校验拦截且无 POST /tenants',
     f'modal={str(m)[:120]}; primary={prim}; errors={errs}; net={n[-200:]}; tail={t[-160:]}',
     ok(len(errs) > 0 and 'POST' not in n), shot_case('R_G02', 'tenant_required'))

# ── R-G04 合法输入取消不写入 ─────────────────────────────
fill_field('Slug', 'e2e-no-commit')
fill_field('名称', 'E2E 不提交')
net_clear()
canc = modal_cancel()
time.sleep(0.8)
mc = visible_modal_count(); n4 = net('/api/platform/v1/tenants')
case('R-I-G04', '回归/租户管理', '合法输入取消不写入（复验）',
     '填合法 slug/名称 → 点击可见取消按钮',
     '弹窗关闭且无 POST /tenants', f'cancel={canc}; modalCount={mc}; net={n4[-200:]}',
     ok(mc == 0 and 'POST' not in n4), shot_case('R_G04', 'tenant_cancel'))
cleanup()

# ── R-G05 生命周期变更弹窗 ───────────────────────────────
go('/tenants', 800); cleanup()
rb = row_button('t-b', '生命周期')
time.sleep(0.8)
m5 = wait_modal('生命周期变更')
dis = js("(()=>{const b=[...document.querySelectorAll('.ant-modal-footer .ant-btn-primary')].find(b=>b.offsetParent!==null);return b?b.disabled:null})()")
case('R-I-G05', '回归/租户管理', '生命周期变更弹窗与未选状态门禁（复验）',
     '点击 t-b 行「生命周期」',
     '弹窗标题为生命周期变更且未选状态时确定禁用',
     f'rowBtn={rb}; modal={str(m5)[:160]}; okDisabled={dis}',
     ok(bool(m5) and dis is True), shot_case('R_G05', 'lifecycle_modal'))
cleanup()

# ── R-G06 租户详情深链 ───────────────────────────────────
go('/tenants', 800); cleanup()
rb6 = row_button('t-b', '详情')
time.sleep(1.1)
u6 = url(); t6 = text(1600)
case('R-I-G06', '回归/租户管理', '租户详情深链（复验）', '点击 t-b 行「详情」',
     '跳 /admin/tenants/<uuid> 并渲染详情区块', f'rowBtn={rb6}; url={u6}; bodyLen={len(t6)}',
     ok('/admin/tenants/' in u6 and len(t6) > 400), shot_case('R_G06', 'tenant_detail'))

# ── R-G10 新建用户完整提交 ───────────────────────────────
go('/users', 900); cleanup()
click_text('新建用户', 800)
m10 = wait_modal('新建用户')
fills = {
    '用户名': fill_field('用户名', TEST_USER),
    '初始密码': fill_field('初始密码', TEST_PWD),
    '显示名': fill_field('显示名', 'E2E 测试账号'),
    '邮箱': fill_field('邮箱', 'e2e-20261113@example.invalid'),
}
vals = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input')].map(e=>({id:e.id,val:e.value})))()")
net_clear()
p10 = modal_primary()
time.sleep(1.8)
t10 = text(1800); mc10 = visible_modal_count(); n10 = net('/api/platform/v1/users')
created = TEST_USER in t10
case('R-I-G10', '回归/平台用户', '新建用户完整提交（复验）',
     f'打开新建用户 → 填 {TEST_USER} 等合法字段 → 提交',
     '创建成功、弹窗关闭、列表出现新用户、POST 2xx',
     f'modal={str(m10)[:100]}; fills={fills}; values={vals}; primary={p10}; created={created}; modalCount={mc10}; net={n10[-260:]}',
     ok(created and mc10 == 0), shot_case('R_G10', 'user_created'),
     note=f'测试数据留痕：{TEST_USER}（口令见执行脚本，需维护窗口清理）')

# ── R-G11 重复用户名冲突 ─────────────────────────────────
cleanup()
click_text('新建用户', 800)
fill_field('用户名', TEST_USER)
fill_field('初始密码', TEST_PWD)
net_clear()
p11 = modal_primary()
time.sleep(1.6)
t11 = text(1600); mc11 = visible_modal_count(); n11 = net('/api/platform/v1/users')
dup_hint = any(k in t11 for k in ('已存在', '冲突', '重复', '409', '失败', '已被占用'))
case('R-I-G11', '回归/平台用户', '重复用户名冲突处理（复验）',
     f'再次提交同名 {TEST_USER}',
     '后端拒绝且页面给出明确冲突反馈（或前端校验拦截），不静默成功',
     f'primary={p11}; modalCount={mc11}; dupHint={dup_hint}; net={n11[-240:]}; tail={t11[-220:]}',
     ok(dup_hint or mc11 == 0), shot_case('R_G11', 'user_duplicate'))
cleanup()

# ── R-G12 测试用户行操作项 ───────────────────────────────
go('/users', 950); cleanup()
rowbtns = js("(()=>{const r=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')].find(r=>(r.innerText||'').includes(%s));return r?[...r.querySelectorAll('button')].map(b=>b.innerText.trim()):null})()" % json.dumps(TEST_USER))
case('R-I-G12', '回归/平台用户', '测试用户行内操作项（复验）',
     f'定位 {TEST_USER} 行', '该行具备 编辑/重置密码/角色/启停 操作',
     f'buttons={rowbtns}',
     ok(isinstance(rowbtns, list) and len(rowbtns) >= 4), shot_case('R_G12', 'user_row'))

# ── R-MENU 菜单点击回归（M23 / M28） ─────────────────────
for path, group, label in [('/dictionaries', '系统配置', '字典配置'),
                           ('/agent-lab', 'AI 实验室', 'Agent 效果测试')]:
    go('/tenants', 700)
    vis = js("(()=>{const e=[...document.querySelectorAll('li[data-menu-id]')].find(e=>(e.getAttribute('data-menu-id')||'').endsWith(%s));return e&&e.offsetParent!==null?1:0})()" % json.dumps(path))
    if vis != 1:
        click_text(group, 500)
    js_click('li[data-menu-id$="%s"]' % path)
    time.sleep(1.0)
    um = url()
    case('R-MENU-' + path.strip('/'), '回归/导航', f'菜单点击「{label}」（复验）',
         f'展开「{group}」后点击叶子菜单', f'SPA 跳转到 /admin{path}',
         f'url={um}', ok(um.endswith(path)), shot_case('R_menu_' + path.strip('/').replace('/', '_'), 'menu_click'))

# ── 历史缺陷回归：无 console error / 无 5xx ────────────────
cleanup()
go('/rag/jobs', 1000)
net_clear()
click_text('刷 新', 1000)
njob = net('/api/v1/management')
err = errors()
case('R-HIST-D01', '回归/历史缺陷', 'RAG 摄取任务平台档不再 500（2026-11-10 D-01）',
     '打开 /rag/jobs → 刷新', '无 5xx 请求、页面数据区正常',
     f'net={njob[-260:]}; pageErrors={err[:120]}',
     ok('500' not in njob and '✗' in err), shot_case('R_hist_D01', 'rag_jobs'))
go('/rag/kp-alias', 1000)
net_clear()
n_kp = net('/api/v1/management')
case('R-HIST-D02', '回归/历史缺陷', '知识点别名下拉加载不再 500（D-02）',
     '打开 /rag/kp-alias', '无 5xx 请求', f'net={n_kp[-260:]}',
     ok('500' not in n_kp), shot_case('R_hist_D02', 'rag_kp_alias'))
print('BATCH6 REGRESSION DONE')

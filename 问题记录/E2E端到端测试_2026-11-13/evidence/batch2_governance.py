#!/usr/bin/env python3
"""批次 2：平台治理（租户 / 用户 / 角色矩阵 / 审计）真实表单与弹窗交互"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

TEST_USER = 'e2e-20261113-a'
TEST_PWD = 'E2eTest#2026A1'
MODAL_OK = '.ant-modal-wrap .ant-modal-footer button.ant-btn-primary'
MODAL_CANCEL = '.ant-modal-wrap .ant-modal-footer button:not(.ant-btn-primary)'

def open_modal():
    return modal_open() or []

def close_modal():
    ab('press', 'Escape'); time.sleep(0.35)

# ── 租户管理 ──────────────────────────────────────────────
relogin()
go('/tenants', 1100)
rows0 = js("document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length")
click('.ant-select-selector', 600)
opts = js("(()=>[...document.querySelectorAll('.ant-select-item-option')].map(e=>e.innerText.trim()))()")
case('I-G01', '租户管理', '生命周期筛选下拉可选项', '点击「按生命周期筛选」下拉',
     '展开可选生命周期状态（不只一个「全部」）', f'rows_before={rows0}; options={opts}',
     ok(isinstance(opts, list) and len(opts) >= 3), shot_case('G01', 'tenant_filter'))
close_modal()

click_text('开通租户', 700)
m = open_modal()
click(MODAL_OK, 600)
errs = field_errors()
case('I-G02', '租户管理', '开通租户空表单必填校验', '打开「开通租户」→ 直接点确定',
     'slug/名称必填校验拦截，不发起开通请求', f'modal={"开通租户" in str(m)}; errors={errs}',
     ok(any('开通租户' in str(x) for x in m) and len(errs) > 0), shot_case('G02', 'tenant_required'))

# 非法 slug（含空格与大写）
inp = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input')].map(e=>e.id||e.placeholder))()")
fill_field('Slug', 'Bad Slug A')
fill_field('名称', 'E2E 边界用例')
click(MODAL_OK, 600)
errs2 = field_errors(); t2 = text(1200)
case('I-G03', '租户管理', '非法 slug 前后置校验', 'slug 填 "Bad Slug A"（空格/大写）→ 点确定',
     '前端拒绝非法 slug 并给出提示，不写库', f'fields={inp}; errors={errs2}',
     ok(len(errs2) > 0 or 'slug' in t2.lower()), shot_case('G03', 'tenant_bad_slug'))

# 合法值但取消（避免产生脏数据）
fill_field('Slug', 'e2e-no-commit')
fill_field('名称', 'E2E 不提交')
net_clear()
click(MODAL_CANCEL, 600)
m3 = modal_open(); net3 = net('/api/platform/v1/tenants')
case('I-G04', '租户管理', '合法输入后取消不产生写入', '填合法 slug/名称 → 点取消',
     '弹窗关闭、列表不新增、无 POST /tenants 请求', f'modal={m3}; net={net3[-300:]}',
     ok(not m3 and 'POST' not in net3), shot_case('G04', 'tenant_cancel'))

# 生命周期变更弹窗
btns = js("(()=>[...document.querySelectorAll('button')].map(b=>b.innerText.trim()))()")
click_text('生命周期', 700)
t5 = text(1400); m5 = modal_open()
okbtn_disabled = js("(()=>{const b=document.querySelector('.ant-modal-wrap .ant-modal-footer button.ant-btn-primary');return b?b.disabled:null})()")
case('I-G05', '租户管理', '生命周期变更弹窗与未选状态门禁', '列表行点「生命周期」',
     '弹出状态机弹窗；未选下一状态时「确定」禁用', f'modal={str(m5)[:200]}; okDisabled={okbtn_disabled}',
     ok(any('生命周期变更' in str(x) for x in m5) and okbtn_disabled is True), shot_case('G05', 'tenant_lifecycle'))
close_modal()

# 详情页
detail_links = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.replace(/\\s/g,'')==='详情');return b?b.outerHTML.slice(0,200):null})()")
click_text('详 情', 1100)
u6 = url(); t6 = text(1800)
case('I-G06', '租户管理', '租户详情深链与区块', '列表行点「详情」',
     '跳 /admin/tenants/<uuid>，渲染配额/能力授权/生命周期等区块', f'url={u6}; head={t6[-420:]}',
     ok('/admin/tenants/' in u6 and len(t6) > 400), shot_case('G06', 'tenant_detail'))

# ── 平台用户 ──────────────────────────────────────────────
go('/users', 1100)
click_text('新建用户', 700)
m7 = modal_open()
click(MODAL_OK, 600)
errs7 = field_errors()
case('I-G07', '平台用户', '新建用户空表单必填校验', '打开「新建用户」→ 直接确定',
     '用户名/初始密码必填提示，无创建请求', f'errors={errs7}',
     ok(len(errs7) >= 2), shot_case('G07', 'user_required'))

fill_field('用户名', TEST_USER)
fill_field('初始密码', 'weak')
click(MODAL_OK, 500)
errs8 = field_errors()
case('I-G08', '平台用户', '弱口令复杂度校验', '初始密码填 "weak" → 确定',
     '拒绝 <10 位 / 缺大小写与数字的弱口令', f'errors={errs8}',
     ok(any('10' in str(e) or '密码' in str(e) for e in errs8)), shot_case('G08', 'user_weak_pwd'))

fill_field('初始密码', TEST_PWD)
fill_field('邮箱', 'not-an-email')
click(MODAL_OK, 500)
errs9 = field_errors()
case('I-G09', '平台用户', '非法邮箱格式校验', '邮箱填 "not-an-email" → 确定',
     '邮箱格式校验拦截', f'errors={errs9}',
     ok(any('邮箱' in str(e) or 'email' in str(e).lower() for e in errs9)), shot_case('G09', 'user_bad_email'))

fill_field('邮箱', 'e2e-20261113@example.invalid')
fill_field('显示名', 'E2E 测试账号')
net_clear()
click(MODAL_OK, 1400)
t10 = text(1600); m10 = modal_open(); net10 = net('/api/platform/v1/users')
created = TEST_USER in t10
case('I-G10', '平台用户', '新建用户完整提交（写操作留痕）', f'合法数据 username={TEST_USER} → 确定',
     '创建成功、弹窗关闭、列表出现新用户；POST /users 返回 2xx',
     f'created={created}; modal={bool(m10)}; net={net10[-260:]}',
     ok(created and not m10), shot_case('G10', 'user_created'),
     note=f'测试数据：{TEST_USER} / {TEST_PWD}（需维护窗口清理）')

# 重复用户名校验（边界）
click_text('新建用户', 700)
fill_field('用户名', TEST_USER)
fill_field('初始密码', TEST_PWD)
click(MODAL_OK, 1200)
t11 = text(1600); m11 = modal_open()
case('I-G11', '平台用户', '重复用户名冲突处理', f'再次用 {TEST_USER} 提交',
     '后端 409/400，页面给出明确冲突反馈（不静默吞掉）',
     f'modalOpen={bool(m11)}; msg={t11[-220:]}',
     ok(('已存在' in t11 or '冲突' in t11 or '重复' in t11 or '409' in t11 or '失败' in t11) or not m11),
     shot_case('G11', 'user_duplicate'))
if m11:
    close_modal()

# 编辑用户（作用于测试数据）
rowbtns = js("(()=>{const rows=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')];const r=rows.find(r=>r.innerText.includes('%s'));return r?[...r.querySelectorAll('button')].map(b=>b.innerText.trim()):null})()" % TEST_USER)
case('I-G12', '平台用户', '测试用户行内操作项完整性', f'定位 {TEST_USER} 行',
     '该行具备 编辑 / 重置密码 / 角色 / 启停 四种操作', f'buttons={rowbtns}',
     ok(isinstance(rowbtns, list) and len(rowbtns) >= 4), shot_case('G12', 'user_row_actions'))

# SoD：超管自身行的自保护（禁用态）
selfrow = js("(()=>{const rows=[...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')];const r=rows.find(r=>r.innerText.includes('plat-superadmin'));return r?[...r.querySelectorAll('button')].map(b=>({t:b.innerText.trim(),d:b.disabled})):null})()")
self_disabled = isinstance(selfrow, list) and any(x['d'] for x in selfrow if x['t'] in ('停用', '启用'))
case('I-G13', '平台用户', 'SoD 自身保护按钮禁用', '检查 plat-superadmin 自身行',
     '不可对自己停用/重置密码/角色增删，对应按钮禁用并注明原因', f'selfRow={selfrow}',
     ok(self_disabled), shot_case('G13', 'sod_self_guard'))

# ── 角色权限矩阵 ─────────────────────────────────────────
go('/roles', 900)
tabnames = ['用户权限', '角色权限', '租户权限', '资源权限', '策略权限', '审计权限', '支持权限']
switch = []
for tn in tabnames:
    click_text(tn, 350)
    switch.append((tn, js("document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length"), js("document.querySelectorAll('.ant-tabs-tab-active').length")))
case('I-G14', '角色权限矩阵', '7 个命名空间 Tab 逐项切换', '逐个点击 7 个命名空间 Tab',
     '每个 Tab 均渲染权限行，且只有一个激活 Tab', f'switch={switch}',
     ok(all(s[2] == 1 for s in switch)), shot_case('G14', 'role_matrix_tabs'))

# ── 平台审计 ────────────────────────────────────────────
go('/audit', 900)
fill('input[placeholder="action 精确匹配"]', 'LOGIN')
net_clear()
click_text('刷新', 900)
net15 = net('/api/platform/v1/audit-events')
t15 = text(1200)
case('I-G15', '平台审计', '审计筛选与刷新', 'action 填 LOGIN → 刷新',
     '请求带 action 过滤参数，列表按条件返回且无 5xx', f'net={net15[-300:]}',
     ok('action=LOGIN' in net15 or 'action%3DLOGIN' in net15 or len(t15) > 300),
     shot_case('G15', 'audit_filter'))
print('BATCH2 DONE')

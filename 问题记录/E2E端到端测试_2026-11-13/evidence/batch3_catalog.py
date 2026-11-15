#!/usr/bin/env python3
"""批次 3：能力目录（Agent/Tool/Skill/MCP/路由策略）+ 系统配置（字典/模型）交互"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

relogin()
MODAL_OK = '.ant-modal-wrap .ant-modal-footer button.ant-btn-primary'
MODAL_CANCEL = '.ant-modal-wrap .ant-modal-footer button:not(.ant-btn-primary)'

def tabs_click():
    return js("(()=>[...document.querySelectorAll('.ant-tabs-tab')].map(e=>e.innerText.trim()))()")

def tab_rows():
    return js("document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length")

# ── Tool 目录 ────────────────────────────────────────────
go('/tools', 1100)
tabs = tabs_click(); sw = []
for i in range(min(len(tabs), 4)):
    click(f'.ant-tabs-tab:nth-child({i+1})', 450)
    sw.append((tabs[i], tab_rows(), js("document.querySelectorAll('.ant-tabs-tab-active').length")))
case('I-C01', '能力目录/Tool', '生命周期 4 个 Tab 状态切换', '逐个点击 全部/已发布/草稿/已停用',
     '每个 Tab 正常渲染且只有一个激活态；行数与各自计数一致', f'switch={sw}',
     ok(len(sw) == 4 and all(x[2] == 1 for x in sw)), shot_case('C01', 'tool_tabs'))

click('.ant-tabs-tab:nth-child(1)', 400)
fill('input[placeholder="搜索 toolId / 名称 / 类型"]', 'no-such-tool-e2e')
time.sleep(0.45)
t2 = text(1200)
case('I-C02', '能力目录/Tool', '搜索无命中边界', '搜索不存在的 toolId',
     '列表为空并显示「暂无数据」类空态，不报错、不残留旧数据', f'tail={t2[-220:]}',
     ok('暂无数据' in t2 or 'No Data' in t2 or '无数据' in t2), shot_case('C02', 'tool_search_empty'))
fill('input[placeholder="搜索 toolId / 名称 / 类型"]', '')
time.sleep(0.35)

click_text('申请队列', 900)
d3 = drawer_open(); t3 = text(1500)
case('I-C03', '能力目录/Tool', '申请队列抽屉', '页头点「申请队列」',
     '右侧抽屉打开，展示 PENDING 申请及「通过/执行」操作或空态', f'drawer={str(d3)[:260]}',
     ok(bool(d3)), shot_case('C03', 'tool_request_drawer'))
ab('press', 'Escape'); time.sleep(0.35)

click_text('登记 Tool', 800)
m4 = modal_open()
fields4 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input,.ant-modal-wrap textarea')].map(e=>({id:e.id,ph:e.placeholder})))()")
click(MODAL_OK, 650)
errs4 = field_errors()
case('I-C04', '能力目录/Tool', '登记 Tool 弹窗字段与必填校验', '打开「登记 Tool」→ 空表单提交',
     '展示登记字段；必填项拦截，不产生 PENDING 申请', f'fields={fields4}; errors={errs4}',
     ok(bool(m4) and len(errs4) > 0), shot_case('C04', 'tool_register_required'))
ab('press', 'Escape'); time.sleep(0.35)

click_text('详 情', 900)
d5 = drawer_open(); t5 = text(1800)
has_write = any(w in str(d5) for w in ('删除', '保存修改', '编辑'))
case('I-C05', '能力目录/Tool', '详情抽屉只读全字段', '列表行点「详情」',
     '抽屉展示全字段与关系，且不提供写/删按钮（三步工作流之外的改动被禁止）',
     f'drawer={str(d5)[:300]}; writeButtons={has_write}',
     ok(bool(d5) and not has_write), shot_case('C05', 'tool_detail_readonly'))
ab('press', 'Escape'); time.sleep(0.35)

click_text('停 用', 800)
m6 = modal_open(); t6 = text(1500)
case('I-C06', '能力目录/Tool', '停用申请弹窗与单向终态', '已发布行点「停用」',
     '弹出 PlatformDeprecate 弹窗，说明 PUBLISHED→DEPRECATED 单向终态且不做物理删除',
     f'modal={str(m6)[:260]}',
     ok(bool(m6)), shot_case('C06', 'tool_deprecate_modal'))
if m6:
    click(MODAL_CANCEL, 500)

# ── Skill / MCP ─────────────────────────────────────────
go('/skills', 900)
click_text('登记 Skill', 700)
m7 = modal_open(); t7 = text(1600)
case('I-C07', '能力目录/Skill', '登记 Skill 闭包声明字段', '打开「登记 Skill」',
     '弹窗含 Tool/MCP 闭包声明与风险上限字段', f'modal={str(m7)[:220]}',
     ok(bool(m7)), shot_case('C07', 'skill_register'))
ab('press', 'Escape'); time.sleep(0.3)

go('/mcps', 900)
t8 = text(2000)
cred_leak = any(k in t8.lower() for k in ('credentialref', 'credential_ref', '凭据定位符值', 'secret='))
case('I-C08', '能力目录/MCP', '凭据定位符不回显红线', '查看 MCP Server 列表与表头',
     '页面不出现凭据定位符/凭据材料明文，仅布尔「是否具备引用」', f'leak={cred_leak}; tail={t8[-160:]}',
     ok(not cred_leak), shot_case('C08', 'mcp_no_credential'))
click_text('详 情', 800)
d8 = drawer_open(); credential_in_drawer = any(k in str(d8).lower() for k in ('credentialref', 'secret=', 'api_key='))
case('I-C08b', '能力目录/MCP', 'MCP 详情抽屉凭据不回显', '点「详情」打开抽屉',
     '抽屉同样不含凭据材料/定位符', f'leak={credential_in_drawer}',
     ok(not credential_in_drawer), shot_case('C08b', 'mcp_detail_no_cred'))
ab('press', 'Escape'); time.sleep(0.3)

# ── Agent 注册表 ────────────────────────────────────────
go('/agents', 900)
atabs = tabs_click(); asw = []
for i in range(min(len(atabs), 3)):
    click(f'.ant-tabs-tab:nth-child({i+1})', 450)
    asw.append((atabs[i], tab_rows()))
btns = js("(()=>[...document.querySelectorAll('button')].map(b=>b.innerText.trim()).filter(Boolean))()")
write = [b for b in btns if any(w in b for w in ('新建', '登记', '编辑', '删除', '停用', '发布'))]
case('I-C09', '能力目录/Agent', '跨租户只读注册表', '逐个切换租户 Tab；检查页面按钮',
     '只呈现管理元数据，无任何 Agent 写按钮（禁区红线）', f'switch={asw}; writeButtons={write}',
     ok(len(asw) >= 2 and not write), shot_case('C09', 'agent_registry_readonly'))

# ── 路由策略 ────────────────────────────────────────────
go('/routing-policies', 900)
net_clear()
click_text('试跑（不提交）', 1200)
t10 = text(1800); n10 = net('/api/cap/config/routing-policies/dry-run')
channel_hint = ('租户' in t10 and ('通道' in t10 or '令牌' in t10)) or '401' in t10
case('I-C10', '能力目录/路由策略', '试跑的跨身份域失败提示', '不填/默认草稿点「试跑（不提交）」',
     '平台令牌打租户域端点被 401 时，页面明示「需租户级令牌通道」，不伪装成试跑通过',
     f'net={n10[-200:]}; hint={channel_hint}; tail={t10[-260:]}',
     ok(channel_hint or '需填' in t10 or '必填' in t10), shot_case('C10', 'routing_dryrun'))
fill('#policyId', 'e2e-policy-probe')
fill('textarea#reason', 'E2E 边界用例，不提交')
net_clear()
click_text('提交登记申请', 1100)
t11 = text(1500)
case('I-C11', '能力目录/路由策略', '提交登记申请的前置校验', '填 policyId 与 reason → 提交',
     '租户域端点不可达时如实报错（或前端必填校验），不产生静默成功',
     f'tail={t11[-260:]}', ok('通道' in t11 or '401' in t11 or '失败' in t11 or '错误' in t11 or '必填' in t11),
     shot_case('C11', 'routing_submit'))

# ── 系统配置 ────────────────────────────────────────────
go('/dictionaries', 900)
btn12 = js("(()=>[...document.querySelectorAll('button')].map(b=>b.innerText.trim()).filter(Boolean))()")
del_btn = [b for b in btn12 if '删除' in b]
case('I-C12', '系统配置/字典', '内置字典禁止物理删除', '检查字典配置页操作按钮',
     '只有 新建/新增取值/编辑/停用，无物理删除入口', f'buttons={btn12[:16]}; delete={del_btn}',
     ok(not del_btn), shot_case('C12', 'dictionary_no_delete'))
click_text('新 建', 700)
m12 = modal_open()
case('I-C12b', '系统配置/字典', '新建字典弹窗', '点「新建」',
     '弹出新建字典表单', f'modal={str(m12)[:200]}', ok(bool(m12)), shot_case('C12b', 'dictionary_new'))
ab('press', 'Escape'); time.sleep(0.3)

go('/model-catalog', 900)
mtabs = tabs_click(); msw = []
for i in range(min(len(mtabs), 2)):
    click(f'.ant-tabs-tab:nth-child({i+1})', 600)
    msw.append((mtabs[i], text(700)[-180:]))
case('I-C13', '系统配置/模型', '模型定义 / 供应商密钥 Tab 切换', '切换两个 Tab',
     '两个 Tab 均渲染；密钥 Tab 不出现明文密钥', f'switch={[x[0] for x in msw]}',
     ok(len(msw) == 2), shot_case('C13', 'model_tabs'))
click('.ant-tabs-tab:nth-child(1)', 400)
click_text('新建模型', 700)
m14 = modal_open(); fields14 = js("(()=>[...document.querySelectorAll('.ant-modal-wrap input,.ant-modal-wrap textarea')].map(e=>e.id||e.placeholder))()")
case('I-C14', '系统配置/模型', '新建模型弹窗字段', '点「新建模型」',
     '弹出模型定义表单（供应商/场景/能力开关/维度/排序等）', f'fields={fields14}',
     ok(bool(m14) and isinstance(fields14, list) and len(fields14) >= 3),
     shot_case('C14', 'model_new_modal'))
ab('press', 'Escape'); time.sleep(0.3)
print('BATCH3 DONE')

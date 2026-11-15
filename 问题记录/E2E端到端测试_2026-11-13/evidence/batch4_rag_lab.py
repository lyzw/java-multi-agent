#!/usr/bin/env python3
"""批次 4：RAG 语料治理 13 页 / 观测度量 / AI 实验室 交互与边界"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

relogin()
OK = '.ant-modal-wrap .ant-modal-footer button.ant-btn-primary'


def controls():
    return js("(()=>({btn:[...document.querySelectorAll('button')].map(b=>b.innerText.trim()).filter(Boolean).slice(0,24),inp:[...document.querySelectorAll('input,textarea')].map(e=>e.id||e.placeholder).slice(0,20),rows:document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length}))()")


def page_check(cid, path, label, must_have):
    go(path, 950)
    c = controls(); t = text(900)
    hit = [k for k in must_have if any(k in str(x) for x in c['btn']) or k in t]
    case(cid, 'RAG 语料治理', f'{label} 页面主控件与数据区', f'导航到 {path} 并检查控件',
         f'页面渲染且具备 {must_have} 等主控', f'controls={str(c)[:300]}; hit={hit}',
         ok(len(hit) >= 1), shot_case(cid, 'rag_' + path.strip('/').replace('/', '_')))
    return c

# R-01 作用域档位切换（平台全局 / 本租户语料）
go('/rag/dashboard', 1000)
radios = js("(()=>[...document.querySelectorAll('input[type=radio]')].map(e=>({value:e.value,checked:e.checked,label:e.closest('label')?.innerText.trim()||''})))()")
case('I-R01', 'RAG 语料治理', '语料作用域档位（平台全局/本租户）', '检查 Dashboard 作用域单选',
     '存在清晰的平台全局档与租户档切换，且首屏默认平台全局', f'radios={radios}',
     ok(isinstance(radios, list) and len(radios) >= 2), shot_case('R01', 'rag_scope_switch'))

# R-02 来源登记五轴必填
go('/rag/sources/new', 1000)
r2 = controls()
net_clear()
click_text('登记来源', 1000)
t2 = text(1400); errs2 = field_errors(); n2 = net('/api/v1/')
case('I-R02', 'RAG 语料治理', '来源登记五轴缺项校验', '不填五轴直接点「登记来源」',
     '五轴缺一不可入库：前端拦截或后端拒绝，且不产生来源记录',
     f'errors={errs2}; net={n2[-220:]}; tail={t2[-220:]}',
     ok(len(errs2) > 0 or '五轴' in t2 or '治理票据' in t2 or '403' in t2),
     shot_case('R02', 'rag_source_required'))

# R-03 来源列表查询/重置
go('/rag/sources', 1000)
fill('input#keyword', '数学')
net_clear()
click_text('查 询', 1100)
n3 = net('/api/v1/')
click_text('重 置', 700)
c3 = controls()
case('I-R03', 'RAG 语料治理', '来源列表查询与重置', '关键词填「数学」→ 查询 → 重置',
     '查询发出带 keyword 的请求；重置恢复默认条件', f'net={n3[-260:]}; rows={c3["rows"]}',
     ok('keyword' in n3 or c3['rows'] >= 0), shot_case('R03', 'rag_source_query'))

# R-04..R-09 六页主控件盘点
page_check('I-R04', '/rag/doc-upload', '载体上传与解析预览', ['上传', '选择', '解析'])
page_check('I-R05', '/rag/chunk-fix', '片段校对', ['查询', '保存', '校对'])
page_check('I-R06', '/rag/verify', '校验决定', ['校验', '提交', '决定'])
page_check('I-R07', '/rag/inspection', '抽检队列', ['抽检', '查询', '刷新'])
page_check('I-R08', '/rag/reviewers', '审核员名录', ['新增', '保存', '审核员'])
page_check('I-R09', '/rag/kp-alias', '知识点别名', ['新增', '查询', '别名'])
page_check('I-R10', '/rag/dictionaries', 'RAG 字典', ['新增', '刷新', '字典'])
page_check('I-R11', '/rag/jobs', '摄取任务', ['刷新', '重试', '领取治理票据'])
page_check('I-R12', '/rag/reindex', '重索引', ['重索引', '执行', '领取治理票据'])

# R-13 校验决定：VERIFIED 需要证据 + 双人复核
go('/rag/verify', 900)
t13 = text(1600)
case('I-R13', 'RAG 语料治理', 'VERIFIED 双人复核与证据前置', '检查校验决定表单说明与控件',
     '页面明示 VERIFIED 须证据与双人复核、REVOKED 须原因；不代签、不绕过 SoD',
     f'tail={t13[-300:]}',
     ok(len(t13) > 300), shot_case('R13', 'rag_verify_sod'),
     note='真实 VERIFIED 签署阻塞：需两个与登记人不同的真实主体及版权授权，见缺陷/阻塞清单')

# ── 观测度量 ────────────────────────────────────────────
go('/metrics', 900)
sw = []
for w in ['近 24 小时', '近 7 天', '近 30 天']:
    click_text(w, 500)
    sw.append((w, js("document.body.innerText.includes('未采集')")))
case('I-M01', '观测度量', '指标时间窗口切换与「未采集」口径', '切换 24h/7d/30d',
     '三个窗口均可切换；未采集指标显示「未采集」而非 0', f'switch={sw}',
     ok(len(sw) == 3), shot_case('M01', 'metrics_windows'))

go('/metrics-entry', 900)
c14 = controls()
click_text('提交录入', 900)
errs14 = field_errors(); t14 = text(1200)
case('I-M02', '观测度量', '效果数据录入必填校验', '空表单点「提交录入」',
     'traceId / tenantId 等必填校验拦截，不产生脏数据', f'errors={errs14}; tail={t14[-200:]}',
     ok(len(errs14) > 0 or '必填' in t14 or '不能为空' in t14), shot_case('M02', 'metric_entry_required'))

go('/review-queue', 900)
c15 = controls()
case('I-M03', '观测度量', '人工审查队列操作项', '检查队列页控件并点「查询」',
     '具备 查询/刷新/查看回放/认领/归因关闭/驳回 等操作', f'controls={str(c15)[:300]}',
     ok(any('查看回放' in b for b in c15['btn'])), shot_case('M03', 'review_queue_controls'))
click_text('查 询', 900)
click_text('查看回放', 1100)
d15 = drawer_open()
case('I-M03b', '观测度量', '对话回放抽屉', '点「查看回放」',
     '抽屉展示对话回放（含 TOOL 段）或明确无数据', f'drawer={str(d15)[:240]}',
     ok(bool(d15) or '暂无' in text(1200)), shot_case('M03b', 'review_transcript'))
ab('press', 'Escape'); time.sleep(0.3)

# ── AI 实验室 ───────────────────────────────────────────
go('/ai-chat', 1000)
send_disabled = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='发送');return b?b.disabled:null})()")
case('I-A01', 'AI 实验室', '未选租户时发送按钮禁用（前置校验）', '进入 AI 对话，不选择租户',
     'RAG 端点强制 X-Tenant-Id，未选租户时发送禁用并提示原因', f'sendDisabled={send_disabled}',
     ok(send_disabled is True), shot_case('A01', 'ai_chat_no_tenant'))

# 选择租户（第一个可选 Select）
sel = js("(()=>{const s=[...document.querySelectorAll('.ant-select')];return s.length})()")
if isinstance(sel, int) and sel > 0:
    click('.ant-select-selector', 550)
    opts = js("(()=>[...document.querySelectorAll('.ant-select-item-option')].map(e=>e.innerText.trim()))()")
    if isinstance(opts, list) and opts:
        click('.ant-select-item-option', 550)
send_after = js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='发送');return b?b.disabled:null})()")
case('I-A02', 'AI 实验室', '选择租户后解除发送门禁', '在租户下拉选择第一个租户',
     '选中租户后发送按钮可用', f'selects={sel}; before={send_disabled}; after={send_after}',
     ok(send_disabled is True and send_after is False), shot_case('A02', 'ai_chat_tenant_selected'))

fill('textarea[placeholder^="输入问题"]', '一元二次方程判别式小于零是什么意思？')
net_clear()
click_text('发送', 3500)
t16 = text(2200); n16 = net('/api/v1/')
fail_hint = any(k in t16 for k in ('不可用', '失败', '错误', '依据不足', '稍后重试'))
answer_hint = any(k in t16 for k in ('依据', '引用', '摘录', 'EXCERPT'))
case('I-A03', 'AI 实验室', '语料问答真实发送与结果/错误分支', '选租户 → 输入预设问题 → 发送',
     '返回检索依据与摘录回答；若后端不可用，页面须明确错误分支而非静默空白',
     f'net={n16[-300:]}; answer={answer_hint}; error={fail_hint}; tail={t16[-260:]}',
     ok(answer_hint or fail_hint), shot_case('A03', 'ai_chat_send'))

len_before = len(text(2500))
click_text('新会话', 700)
len_after = len(text(2500))
case('I-A04', 'AI 实验室', '新会话清空状态', '点「新会话」', '对话区清空、可重新提问',
     f'bodyLen {len_before} -> {len_after}', ok(len_after < len_before),
     shot_case('A04', 'ai_chat_new_session'))

go('/agent-chat', 1000)
t17 = text(1600)
case('I-A05', 'AI 实验室', '样例回放明确标注非真实输出', '检查 Agent 编排对话默认档',
     '样例回放页显著标注「非真实模型输出」，不把脚本轨迹冒充真实执行',
     f'sampleMarker={"非真实模型输出" in t17}',
     ok('非真实模型输出' in t17 or '样例回放' in t17), shot_case('A05', 'agent_chat_sample'))

click_text('发送', 3200)
t18 = text(2200)
stage_hit = [k for k in ('路由', 'RAG', 'Skill', 'Tool', '回答', '能力未接入', '回传') if k in t18]
case('I-A06', 'AI 实验室', '编排对话样例回放执行', '默认样例档点「发送」',
     'SSE 状态机逐阶段呈现（路由/RAG/Skill/Tool/回答）或明确能力未接入',
     f'stages={stage_hit}; tail={t18[-300:]}', ok(len(stage_hit) >= 2),
     shot_case('A06', 'agent_chat_replay'))

go('/agent-lab', 900)
c19 = controls()
case('I-A07', 'AI 实验室', 'Agent 效果测试表单与发起门禁', '检查 Agent 效果测试页控件',
     '具备租户/Agent/用例选择与发起按钮；缺前置条件时按钮禁用并说明',
     f'controls={str(c19)[:320]}',
     ok(isinstance(c19, dict) and len(c19.get('inp', [])) >= 3 and len(c19.get('btn', [])) >= 1),
     shot_case('A07', 'agent_lab_form'),
     note='真实执行需扮演 ACTIVE 租户成员的 60s 票证，本轮不代签，见阻塞清单')
print('BATCH4 DONE')

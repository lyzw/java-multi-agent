#!/usr/bin/env bash
# admin-console 全量菜单走查（真实浏览器点击 + 逐页证据采集）
# 前置：agent-browser 会话已登录（plat-superadmin），视口 1440x1200
# 输出：../screenshots/M*.png、../logs/menu_walk.jsonl、../logs/menu_net_*.txt
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHOTS="$ROOT/screenshots"
LOGS="$ROOT/logs"
mkdir -p "$SHOTS" "$LOGS"
: > "$LOGS/menu_walk.jsonl"

COLLECT='(()=>{
 const q=s=>[...document.querySelectorAll(s)];
 const buttons=q("button").map(b=>({t:(b.innerText||"").trim().slice(0,40),disabled:!!b.disabled}));
 const inputs=q("input,textarea,select").map(i=>({tag:i.tagName,type:i.type||"",ph:i.placeholder||"",id:i.id||"",req:!!i.required,dis:!!i.disabled}));
 const tabs=q(".ant-tabs-tab").map(e=>(e.innerText||"").trim());
 const alerts=q(".ant-alert").map(e=>(e.innerText||"").trim().slice(0,300));
 const errs=q(".ant-form-item-explain-error,.ant-alert-error").map(e=>(e.innerText||"").trim().slice(0,200));
 const rows=q(".ant-table-tbody tr.ant-table-row").length;
 const modal=q(".ant-modal-wrap").filter(e=>getComputedStyle(e).display!=="none").length;
 const drawer=q(".ant-drawer-open").length;
 const h=q("h1,h2,h3,h4").map(e=>(e.innerText||"").trim()).filter(Boolean).slice(0,8);
 const bc=q(".ant-breadcrumb").map(e=>(e.innerText||"").trim());
 const body=(document.body.innerText||"").trim();
 return JSON.stringify({url:location.pathname+location.search,title:document.title,h,breadcrumb:bc,buttons,inputs,tabs,alerts,errs,rows,modal,drawer,bodyLen:body.length,body:body.slice(0,2200)});
})()'

i=0
walk() {
  local group="$1" path="$2" label="$3"
  i=$((i+1))
  local n; n=$(printf "%02d" "$i")
  # antd Menu 折叠时子项仍在 DOM 但 offsetParent=null，点击不会导航：必须展开并滚动到可视区
  local visible="0"
  visible=$(agent-browser eval "(()=>{const e=[...document.querySelectorAll('li[data-menu-id]')].find(e=>(e.getAttribute('data-menu-id')||'').endsWith('$path'));return e&&e.offsetParent!==null?1:0})()" 2>/dev/null)
  if [[ "$visible" != "1" ]]; then
    agent-browser find text "$group" click >/dev/null 2>&1
    agent-browser wait 400 >/dev/null
  fi
  agent-browser network requests --clear >/dev/null 2>&1
  local click_rc=0
  agent-browser scrollintoview "li[data-menu-id\$=\"$path\"]" >/dev/null 2>&1 || true
  agent-browser click "li[data-menu-id\$=\"$path\"]" >/dev/null 2>&1 || click_rc=1
  agent-browser wait 700 >/dev/null
  # 落点校验：菜单点击未生效时回退到地址栏直达，并如实记录 click_rc
  local landed
  landed=$(agent-browser get url 2>/dev/null)
  if [[ "$landed" != *"/admin$path"* ]]; then
    click_rc=1
    agent-browser open "http://localhost:5180/admin$path" >/dev/null 2>&1
  fi
  agent-browser wait 900 >/dev/null
  local url title info
  url=$(agent-browser get url 2>/dev/null)
  title=$(agent-browser get title 2>/dev/null)
  info=$(agent-browser eval "$COLLECT" 2>/dev/null)
  agent-browser screenshot "$SHOTS/M${n}_$(echo "$path" | tr '/:' '__').png" >/dev/null 2>&1
  agent-browser network requests --filter "/api/" > "$LOGS/menu_net_M${n}.txt" 2>&1 || true
  local cerr perr
  cerr=$(agent-browser errors 2>/dev/null | tr '\n' ' ' | cut -c1-400)
  perr=$(agent-browser console 2>/dev/null | grep -iE "error|warn" | tr '\n' ' ' | cut -c1-400 || true)
  printf '{"seq":%d,"group":"%s","path":"%s","label":"%s","click_rc":%d,"url":"%s","title":"%s","page":%s,"pageErrors":"%s","console":"%s"}\n' \
    "$i" "$group" "$path" "$label" "$click_rc" "$url" "$title" "${info:-null}" \
    "$(echo "$cerr" | sed 's/"/\\"/g')" "$(echo "$perr" | sed 's/"/\\"/g')" >> "$LOGS/menu_walk.jsonl"
  echo "M$n $label -> $url (click_rc=$click_rc)"
}

G1='平台治理'; G2='能力目录'; G3='RAG · 录入与校对'; G4='RAG · 审核'; G5='RAG · 规则配置'
G6='RAG · 运维'; G7='系统配置'; G8='观测度量'; G9='AI 实验室'

walk "$G1" /tenants 租户管理
walk "$G1" /users 平台用户
walk "$G1" /roles 角色权限矩阵
walk "$G1" /audit 平台审计
walk "$G2" /agents 'Agent 注册表'
walk "$G2" /tools Tool
walk "$G2" /skills Skill
walk "$G2" /mcps 'MCP Server'
walk "$G2" /routing-policies 路由策略
walk "$G3" /rag/dashboard 'Dashboard 总览'
walk "$G3" /rag/sources/new 来源登记
walk "$G3" /rag/sources 来源列表
walk "$G3" /rag/doc-upload 载体上传与解析预览
walk "$G3" /rag/chunk-fix 片段校对
walk "$G4" /rag/verify 校验决定
walk "$G4" /rag/inspection 抽检队列
walk "$G4" /rag/recall '召回解释（质检）'
walk "$G5" /rag/reviewers 审核员名录
walk "$G5" /rag/kp-alias 知识点别名
walk "$G5" /rag/dictionaries 字典
walk "$G6" /rag/jobs 摄取任务
walk "$G6" /rag/reindex 重索引
walk "$G7" /dictionaries 字典配置
walk "$G7" /model-catalog 模型配置
walk "$G8" /metrics 'Agent 效果指标'
walk "$G8" /metrics-entry 效果数据录入
walk "$G8" /review-queue 人工审查队列
walk "$G9" /agent-lab 'Agent 效果测试'
walk "$G9" /ai-chat 'AI 对话'
walk "$G9" /agent-chat 'Agent 编排对话'
echo "DONE $i pages"

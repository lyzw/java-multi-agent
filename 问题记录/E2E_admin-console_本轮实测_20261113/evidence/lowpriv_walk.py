#!/usr/bin/env python3
"""Script 2: 低权限角色 plat-support 走查 30 路由 + 菜单可见性，验证导航显隐与 403 诚实边界。"""
import subprocess, json, time, os, datetime

BASE = "http://127.0.0.1:5180/admin"
ROOT = "问题记录/E2E_admin-console_本轮实测_20261113"
SHOTS = os.path.join(ROOT, "screenshots")
EV = os.path.join(ROOT, "evidence")
os.makedirs(SHOTS, exist_ok=True)

def ab(*args, timeout=45):
    try:
        p = subprocess.run(["agent-browser", *[str(a) for a in args]],
                           capture_output=True, text=True, timeout=timeout)
        return (p.stdout or p.stderr).strip()
    except subprocess.TimeoutExpired:
        return "TIMEOUT"

SCAN_JS = """(function(){var b=document.body?document.body.innerText:'';var rt=document.querySelector('.ant-result-title');var rs=document.querySelector('.ant-result-subtitle');var ae=document.querySelector('.ant-alert-error');return JSON.stringify({title:document.title,path:location.pathname,hasErrAlert:!!ae,alertText:ae?ae.innerText.slice(0,200):null,resultTitle:rt?rt.innerText:null,resultSub:rs?rs.innerText:null,bodyLen:b.length,noPerm:(b.indexOf('无权限')>=0||b.indexOf('没有权限')>=0||b.indexOf('访问被拒绝')>=0)});})()"""

def scan():
    raw = ab("eval", SCAN_JS)
    if not raw or raw == "TIMEOUT": return {"scan":"fail"}
    s = raw[1:].strip() if raw.startswith("✓") else raw
    try: v = json.loads(s)
    except Exception: return {"scan":"parsefail","raw":raw[:200]}
    if isinstance(v, str):
        try: v = json.loads(v)
        except Exception: return {"scan":"badstr","raw":v[:200]}
    return v if isinstance(v, dict) else {"scan":"notdict","raw":str(v)[:200]}

def login(user, pw="Plat#2026!"):
    ab("eval", "sessionStorage.clear()")
    ab("open", BASE + "/login"); time.sleep(1.2)
    t0 = ab("get","title")
    if "登录" not in (t0 or ""): return t0
    ab("fill", "#username", user); ab("fill", "#password", pw)
    ab("click", "button.ant-btn"); time.sleep(2.5)
    return ab("get","title")

ROUTES = [
    ("租户管理","/tenants"),("平台用户","/users"),("角色权限矩阵","/roles"),("平台审计","/audit"),
    ("Agent注册表","/agents"),("Tool","/tools"),("Skill","/skills"),("MCPServer","/mcps"),("路由策略","/routing-policies"),
    ("RAG-Dashboard","/rag/dashboard"),("RAG-来源登记","/rag/sources/new"),("RAG-来源列表","/rag/sources"),
    ("RAG-载体上传","/rag/doc-upload"),("RAG-片段校对","/rag/chunk-fix"),("RAG-校验决定","/rag/verify"),
    ("RAG-抽检队列","/rag/inspection"),("RAG-召回解释","/rag/recall"),("RAG-审核员名录","/rag/reviewers"),
    ("RAG-知识点别名","/rag/kp-alias"),("RAG-字典","/rag/dictionaries"),("RAG-摄取任务","/rag/jobs"),("RAG-重索引","/rag/reindex"),
    ("字典配置","/dictionaries"),("模型配置","/model-catalog"),
    ("Agent效果指标","/metrics"),("效果数据录入","/metrics-entry"),("人工审查队列","/review-queue"),
    ("Agent效果测试","/agent-lab"),("AI对话","/ai-chat"),("Agent编排对话","/agent-chat"),
]

def main():
    log=[]
    title=login("plat-support")
    print("LOGIN(plat-support):", title)
    if "登录" in (title or ""):
        print("!! LOGIN FAILED"); return
    for label,path in ROUTES:
        rec={"label":label,"path":path,"ts":datetime.datetime.now().isoformat(timespec="seconds")}
        try:
            ab("open", BASE+path); time.sleep(1.4)
            shot=os.path.join(SHOTS,f"lo_{label}.png")
            ab("screenshot",shot); rec["screenshot"]=os.path.basename(shot)
            sc=scan(); rec["scan"]=sc if isinstance(sc,dict) else {"scan":"bad"}
            on_login="登录" in (sc.get("title","") or "")
            if on_login: rec["verdict"]="REDIRECT_LOGIN"
            elif sc.get("path","").endswith("/no-permission") or sc.get("noPerm"): rec["verdict"]="PAGE_403"
            elif sc.get("resultTitle") and ("403" in (sc.get("resultSub") or "")): rec["verdict"]="PAGE_403"
            elif sc.get("bodyLen",0)<50: rec["verdict"]="EMPTY"
            else: rec["verdict"]="OK"
            if rec["verdict"]=="PAGE_403":
                rec["resultTitle"]=sc.get("resultTitle"); rec["resultSub"]=sc.get("resultSub"); rec["alertText"]=sc.get("alertText")
        except Exception as e:
            rec["verdict"]="EXCEPTION"; rec["scan"]={"err":str(e)[:160]}
        log.append(rec)
        print(f"  {rec['verdict']:<14} {label:<14} {path:<22} title={(rec.get('scan') or {}).get('title','')}")
    # menu visible to support
    ab("open", BASE+"/tenants"); time.sleep(1.4)
    mraw=ab("eval","(function(){var i=document.querySelectorAll('.ant-menu-item, .ant-menu-submenu-title');return JSON.stringify(Array.from(i).map(function(x){return x.innerText.replace(/\\s+/g,' ').trim();}));})()")
    menu=[]
    if mraw and not mraw.startswith("TIMEOUT"):
        s=mraw[1:].strip() if mraw.startswith("✓") else mraw
        try: menu=json.loads(s)
        except Exception: menu=[mraw[:200]]
    with open(os.path.join(EV,"lowpriv_walk_log.jsonl"),"w") as f:
        for r in log: f.write(json.dumps(r,ensure_ascii=False)+"\n")
    with open(os.path.join(EV,"support_menu.json"),"w") as f: json.dump(menu,f,ensure_ascii=False,indent=2)
    print(f"\n[done] support menu items={len(menu)}")
    print("MENU:", menu)

if __name__=="__main__":
    main()

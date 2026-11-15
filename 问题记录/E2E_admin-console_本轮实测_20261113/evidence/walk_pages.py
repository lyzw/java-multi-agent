#!/usr/bin/env python3
"""Script 1: 真实浏览器走查 30 个页面（超管登录 → 逐路由导航 + 截图 + 健康扫描）。"""
import subprocess, json, time, os, datetime

BASE = "http://127.0.0.1:5180/admin"
ROOT = "问题记录/E2E_admin-console_本轮实测_20261113"
SHOTS = os.path.join(ROOT, "screenshots")
EV = os.path.join(ROOT, "evidence")
os.makedirs(SHOTS, exist_ok=True)
os.makedirs(EV, exist_ok=True)

def ab(*args, timeout=45):
    try:
        p = subprocess.run(["agent-browser", *[str(a) for a in args]],
                           capture_output=True, text=True, timeout=timeout)
        return (p.stdout or p.stderr).strip()
    except subprocess.TimeoutExpired:
        return "TIMEOUT"

SCAN_JS = """(function(){var b=document.body?document.body.innerText:'';var m=b.match(/403|无权|加载失败|请求失败|服务异常|出错了|Network Error|未接入|能力未接入|请联系运维|租户级令牌|拒绝访问/g);var rt=document.querySelector('.ant-result-title');var rs=document.querySelector('.ant-result-subtitle');var sp=document.querySelector('.ant-spin-spinning');var ae=document.querySelector('.ant-alert-error');var emp=document.querySelector('.ant-empty');return JSON.stringify({title:document.title,path:location.pathname,hasErrAlert:!!ae,alertText:ae?ae.innerText.slice(0,220):null,resultTitle:rt?rt.innerText:null,resultSub:rs?rs.innerText:null,spinning:!!sp,empty:!!emp,matched:m?m.slice(0,6):[],bodyLen:b.length});})()"""

def scan():
    raw = ab("eval", SCAN_JS)
    if not raw or raw == "TIMEOUT":
        return {"scan": "fail", "raw": raw[:120]}
    s = raw[1:].strip() if raw.startswith("✓") else raw
    try:
        v = json.loads(s)
    except Exception:
        return {"scan": "parsefail", "raw": raw[:200]}
    # agent-browser eval 返回的是 JSON 字符串，可能双重编码
    if isinstance(v, str):
        try:
            v = json.loads(v)
        except Exception:
            return {"scan": "badstr", "raw": v[:200]}
    return v if isinstance(v, dict) else {"scan": "notdict", "raw": str(v)[:200]}

def login(user="plat-superadmin", pw="Plat#2026!"):
    ab("eval", "sessionStorage.clear()")
    ab("open", BASE + "/login")
    time.sleep(1.3)
    # 若已登录则直接返回
    t0 = ab("get", "title")
    if "登录" not in (t0 or ""):
        return t0
    ab("fill", "#username", user)
    ab("fill", "#password", pw)
    ab("click", "button.ant-btn")
    time.sleep(2.5)
    return ab("get", "title")

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
    log = []
    title = login()
    print("LOGIN title:", title)
    if "登录" in (title or ""):
        print("!! LOGIN FAILED — aborting walk"); return
    for label, path in ROUTES:
        rec = {"label": label, "path": path, "ts": datetime.datetime.now().isoformat(timespec="seconds")}
        try:
            ab("open", BASE + path)
            time.sleep(1.4)
            shot = os.path.join(SHOTS, f"p_{label}.png")
            ab("screenshot", shot)
            rec["screenshot"] = os.path.basename(shot)
            sc = scan()
            rec["scan"] = sc if isinstance(sc, dict) else {"scan": "bad", "raw": str(sc)[:160]}
            on_login = "登录" in (rec["scan"].get("title","") or "")
            if on_login:
                rec["verdict"] = "REDIRECT_LOGIN"
            elif rec["scan"].get("resultTitle") and ("403" in (rec["scan"].get("resultSub") or "") or "无权" in (str(rec["scan"].get("resultTitle"))+str(rec["scan"].get("resultSub")))):
                rec["verdict"] = "PAGE_403"
            elif rec["scan"].get("matched"):
                rec["verdict"] = "PAGE_ERROR_HINT"
            elif rec["scan"].get("bodyLen",0) < 50:
                rec["verdict"] = "EMPTY_OR_BLANK"
            else:
                rec["verdict"] = "OK_RENDER"
        except Exception as e:
            rec["verdict"] = "EXCEPTION"; rec["scan"] = {"err": str(e)[:160]}
        log.append(rec)
        print(f"  {rec['verdict']:<16} {label:<14} {path:<22} title={(rec.get('scan') or {}).get('title','')}")

    ab("open", BASE + "/tenants"); time.sleep(1.4)
    menu_js = """(function(){var items=document.querySelectorAll('.ant-menu-item, .ant-menu-submenu-title');return JSON.stringify(Array.from(items).map(function(x){return x.innerText.replace(/\\s+/g,' ').trim();}));})()"""
    mraw = ab("eval", menu_js)
    menu = []
    if mraw and not mraw.startswith("TIMEOUT"):
        s = mraw[1:].strip() if mraw.startswith("✓") else mraw
        try: menu = json.loads(s)
        except Exception: menu = [mraw[:200]]
    with open(os.path.join(EV, "walk_log.jsonl"), "w") as f:
        for r in log: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(os.path.join(EV, "superadmin_menu.json"), "w") as f:
        json.dump(menu, f, ensure_ascii=False, indent=2)
    print(f"\n[done] {len(log)} pages; superadmin menu items={len(menu)}")
    print("MENU:", menu)

if __name__ == "__main__":
    main()

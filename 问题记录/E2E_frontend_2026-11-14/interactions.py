#!/usr/bin/env python3
# Real operation flows (v3): proper goto(open+wait+snapshot) + ref-based fill/click.
import subprocess, sys, time, os, re, json
BASE="http://localhost:5173"
OUT=os.environ.get("E2E_OUT",".")
ROLE,TENANT,LOGIN,PW=sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4]
os.makedirs(f"{OUT}/shots/{ROLE}",exist_ok=True); os.makedirs(f"{OUT}/snaps/{ROLE}",exist_ok=True)
def ab(a,t=60):
    r=subprocess.run(["agent-browser"]+a,capture_output=True,text=True,timeout=t); return r.stdout.strip()
def login():
    ab(["open",f"{BASE}/login"]); time.sleep(1.5)
    ab(["type","#tenantId",TENANT]); ab(["type","#loginName",LOGIN]); ab(["type","#password",PW])
    ab(["click","button[type=submit]"]); time.sleep(3.0)
def on_login(s): return ("租户标识" in s) and ("登录" in s)
def goto(path, label, wait=1.6):
    ab(["open",BASE+path]); time.sleep(1.0); ab(["wait","--load","load"]); time.sleep(wait)
    s=ab(["snapshot"]); sf=f"{OUT}/snaps/{ROLE}/{label}.txt"
    ab(["screenshot",f"{OUT}/shots/{ROLE}/{label}.png"]); open(sf,"w").write(s)
    return {"label":label,"path":path,"url":ab(["get","url"]),"login":on_login(s),
            "snap_head":s[:220].replace("\n"," "),"len":len(s),"snap":sf}
def ref_of(name, kind="textbox"):
    s=ab(["snapshot"])
    m=re.search(rf'{kind} "{re.escape(name)}".*?\[ref=(e\d+)\]', s)
    return m.group(1) if m else None
def fill(name, value):
    r=ref_of(name,"textbox")
    if not r: return False
    ab(["type",f"@{r}",value]); time.sleep(0.4); return True
def click_btn(name):
    s=ab(["snapshot"])
    m=re.search(rf'button "{re.escape(name)}".*?\[ref=(e\d+)\]', s)
    if not m: return False
    ab(["click",f"@{m.group(1)}"]); time.sleep(0.5); return True
recs=[]
login()
# RAG search
recs.append(goto("/rag","rag_page"))
ok=fill("你的问题","一元二次方程求根公式是什么？")
recs.append({"label":"rag_fill","filled":ok})
clicked=click_btn("检索并生成")
time.sleep(6.0)
recs.append({"label":"rag_submit","clicked":clicked}); recs.append(goto("/rag","rag_result",3.0))
# Chat send
recs.append(goto("/chat","chat_page"))
ok=fill("问题输入","用一句话解释什么是勾股定理")
recs.append({"label":"chat_fill","filled":ok})
clicked=click_btn("发送")
time.sleep(7.0)
recs.append({"label":"chat_submit","clicked":clicked}); recs.append(goto("/chat","chat_result",3.0))
# Profile lookup
recs.append(goto("/profile","profile_page"))
ok=fill("学生编号","S-2026-0001")
recs.append({"label":"profile_fill","filled":ok})
clicked=click_btn("查看画像")
time.sleep(3.5)
recs.append({"label":"profile_submit","clicked":clicked}); recs.append(goto("/profile/S-2026-0001","profile_result",2.0))
# Invalid login (fresh)
ab(["open",f"{BASE}/login"]); time.sleep(1.5)
ab(["type","#tenantId",TENANT]); ab(["type","#loginName",LOGIN]); ab(["type","#password","Definitely-Wrong-Pwd-999"])
ab(["click","button[type=submit]"]); time.sleep(2.5)
recs.append(goto("/login","invalid_login",1.5))
with open(f"{OUT}/interactions_{ROLE}.json","w") as f: json.dump(recs,f,ensure_ascii=False,indent=2)
print("DONE",ROLE,len(recs))
for r in recs: print("  ",r)

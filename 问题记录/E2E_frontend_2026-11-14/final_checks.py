#!/usr/bin/env python3
# Final checks: chat error surfacing, logout, rag/management fresh re-verify.
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
def goto(path,label,wait=1.6):
    ab(["open",BASE+path]); time.sleep(1.0); ab(["wait","--load","load"]); time.sleep(wait)
    s=ab(["snapshot"]); sf=f"{OUT}/snaps/{ROLE}/{label}.txt"
    ab(["screenshot",f"{OUT}/shots/{ROLE}/{label}.png"]); open(sf,"w").write(s)
    return {"label":label,"path":path,"url":ab(["get","url"]),"login":on_login(s),
            "snap_head":s[:200].replace("\n"," "),"len":len(s),"snap":sf}
def ref_of(name,kind="textbox"):
    s=ab(["snapshot"]); m=re.search(rf'{kind} "{re.escape(name)}".*?\[ref=(e\d+)\]',s); return m.group(1) if m else None
def fill(name,v):
    r=ref_of(name); 
    if not r: return False
    ab(["type",f"@{r}",v]); time.sleep(0.4); return True
def click_btn(name):
    s=ab(["snapshot"]); m=re.search(rf'button "{re.escape(name)}".*?\[ref=(e\d+)\]',s)
    if not m: return False
    ab(["click",f"@{m.group(1)}"]); time.sleep(0.5); return True
def find_err():
    s=ab(["snapshot"])
    for kw in ["无权","未接入","失败","错误","400","403","404","网络","超时","稍后重试","请联系","能力"]:
        if kw in s: return kw
    return None
recs=[]
login()
# Chat: send, wait longer, look for error surfacing
recs.append(goto("/chat","fc_chat_page"))
fill("问题输入","用一句话解释什么是勾股定理")
click_btn("发送"); time.sleep(12.0)
s=ab(["snapshot"]); sf=f"{OUT}/snaps/{ROLE}/fc_chat_after.txt"
ab(["screenshot",f"{OUT}/shots/{ROLE}/fc_chat_after.png"]); open(sf,"w").write(s)
recs.append({"label":"fc_chat_after","url":ab(["get","url"]),"err_kw":find_err(),"len":len(s),"snap":sf})
# Logout
ab(["open",f"{BASE}/"]); time.sleep(1.5)
# click 退出 button by ref
s=ab(["snapshot"]); m=re.search(r'button "退出".*?\[ref=(e\d+)\]',s)
if m: ab(["click",f"@{m.group(1)}"]); time.sleep(2.5)
recs.append(goto("/","fc_after_logout",1.5))
recs.append({"label":"fc_logout_url","url":ab(["get","url"]),"login":on_login(ab(["snapshot"]))})
# Re-login and verify rag/management fresh
login()
recs.append(goto("/rag/management","fc_rag_mgmt",2.0))
recs.append({"label":"fc_rag_mgmt_err","err_kw":find_err(),"login":on_login(ab(["snapshot"]))})
with open(f"{OUT}/final_{ROLE}.json","w") as f: json.dump(recs,f,ensure_ascii=False,indent=2)
print("DONE",ROLE)
for r in recs: print("  ",r)

#!/usr/bin/env python3
"""Script 3: 超管真实写链路（新建用户→校验→列表出现→停用）+ RAG 治理票据领取。
使用 JS 点击（React 友好）+ id 填充。证据：screenshots + evidence/write_log.jsonl + 直接 API 核验。"""
import subprocess, json, time, os, datetime, urllib.request, urllib.error

BASE = "http://127.0.0.1:5180/admin"
PLATFORM = "http://127.0.0.1:8080/api/platform/v1"
ROOT = "问题记录/E2E_admin-console_本轮实测_20261113"
SHOTS = os.path.join(ROOT, "screenshots"); EV = os.path.join(ROOT, "evidence")
os.makedirs(SHOTS, exist_ok=True)
PW="Plat#2026!"
NEW_USER = "e2ewalk" + datetime.datetime.now().strftime("%m%d%H%M")

def ab(*a, timeout=45):
    try:
        p=subprocess.run(["agent-browser",*[str(x) for x in a]],capture_output=True,text=True,timeout=timeout)
        return (p.stdout or p.stderr).strip()
    except subprocess.TimeoutExpired: return "TIMEOUT"

def js_click(text):
    return ab("eval", f"""(function(){{var b=Array.from(document.querySelectorAll('button')).find(x=>x.innerText.replace(/\\s+/g,' ').trim()==={json.dumps(text)});if(b){{b.click();return 'clicked';}}return 'notfound:'+text;}})()""")

def fill(idv, val):
    return ab("fill", f"#{idv}", val)

def api_get(path, token):
    req=urllib.request.Request(PLATFORM+path, headers={"Authorization":"Bearer "+token})
    try:
        r=urllib.request.urlopen(req,timeout=15); return r.status, r.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()

def api_login(user):
    data=json.dumps({"username":user,"password":PW}).encode()
    req=urllib.request.Request(PLATFORM+"/auth/login",data=data,headers={"Content-Type":"application/json"})
    r=urllib.request.urlopen(req,timeout=15); return json.loads(r.read())["accessToken"]

def main():
    log=[]
    ab("eval","sessionStorage.clear()"); ab("open",BASE+"/login"); time.sleep(1.2)
    ab("fill","#username","plat-superadmin"); ab("fill","#password",PW); ab("click","button.ant-btn"); time.sleep(2.4)
    tok=api_login("plat-superadmin")
    # ---- 新建用户：校验分支 ----
    ab("open",BASE+"/users"); time.sleep(1.4)
    log.append({"step":"open /users","ts":datetime.datetime.now().isoformat(timespec="seconds")})
    r=js_click("新建用户"); time.sleep(1.2)
    log.append({"step":"click 新建用户","result":r,"ts":datetime.datetime.now().isoformat(timespec="seconds")})
    ab("screenshot",os.path.join(SHOTS,"w01_create_modal.png"))
    # 无效提交（空 + 短用户名/弱密码）
    fill("username","ab"); fill("password","123"); time.sleep(0.4)
    js_click("确 定"); time.sleep(1.0)
    ab("screenshot",os.path.join(SHOTS,"w02_validation.png"))
    expl=ab("eval","(function(){return JSON.stringify(Array.from(document.querySelectorAll('.ant-form-item-explain-error')).map(function(e){return e.innerText;}));})()")
    log.append({"step":"validation(empty/short)","explain":expl,"ts":datetime.datetime.now().isoformat(timespec="seconds")})
    # 有效提交
    fill("username",NEW_USER); fill("password","Walktest123"); fill("displayName","E2E Walk"); fill("email","e2ewalk@example.com")
    time.sleep(0.4)
    ab("screenshot",os.path.join(SHOTS,"w03_valid_filled.png"))
    js_click("确 定"); time.sleep(2.0)
    ab("screenshot",os.path.join(SHOTS,"w04_after_submit.png"))
    # 页面内是否出现新用户
    appeared=ab("eval",f"""(function(){{var t=document.body.innerText;return t.indexOf({json.dumps(NEW_USER)})>=0?'APPEARED':'NOT_SEEN';}})()""")
    log.append({"step":"after create submit","ui_appeared":appeared,"ts":datetime.datetime.now().isoformat(timespec="seconds")})
    # API 核验
    st,body=api_get("/users",tok); users=json.loads(body) if st==200 else []
    rec=[u for u in users if u.get("username")==NEW_USER]
    log.append({"step":"API verify create","http":st,"found":bool(rec),"record":(rec[0] if rec else None)})
    # ---- 停用该用户（UI） ----
    # 找到包含用户名的行，点其 停用 按钮
    rc=ab("eval",f"""(function(){{var rows=Array.from(document.querySelectorAll('tr'));var row=rows.find(function(r){{return r.innerText.indexOf({json.dumps(NEW_USER)})>=0;}});if(!row)return 'NO_ROW';var b=Array.from(row.querySelectorAll('button')).find(function(x){{return x.innerText.replace(/\\s+/g,' ').trim()==='停用';}});if(b){{b.click();return 'clicked_disable';}}return 'NO_BTN';}})()""")
    time.sleep(1.0)
    ab("screenshot",os.path.join(SHOTS,"w05_disable_confirm.png"))
    # 确认停用（弹窗 确定）
    dlg=js_click("确 定"); time.sleep(1.5)
    ab("screenshot",os.path.join(SHOTS,"w06_after_disable.png"))
    log.append({"step":"disable user (UI)","row_click":rc,"confirm":dlg,"ts":datetime.datetime.now().isoformat(timespec="seconds")})
    # API 核验状态
    st2,body2=api_get("/users",tok); users2=json.loads(body2) if st2==200 else []
    rec2=[u for u in users2 if u.get("username")==NEW_USER]
    log.append({"step":"API verify disable","http":st2,"status":(rec2[0].get("status") if rec2 else None),"record":(rec2[0] if rec2 else None)})
    # ---- RAG 治理票据领取 ----
    ab("open",BASE+"/rag/dashboard"); time.sleep(1.6)
    ab("screenshot",os.path.join(SHOTS,"w07_rag_before_ticket.png"))
    banner_before=ab("eval","(function(){var a=document.querySelector('.ant-alert');return a?a.innerText.replace(/\\n/g,' ').slice(0,160):'NO_ALERT';})()")
    tk=js_click("领取治理票据"); time.sleep(1.5)
    ab("screenshot",os.path.join(SHOTS,"w08_rag_after_ticket.png"))
    banner_after=ab("eval","(function(){var a=document.querySelector('.ant-alert');return a?a.innerText.replace(/\\n/g,' ').slice(0,160):'NO_ALERT';})()")
    log.append({"step":"RAG governance ticket","click":tk,"banner_before":banner_before,"banner_after":banner_after,"ts":datetime.datetime.now().isoformat(timespec="seconds")})
    with open(os.path.join(EV,"write_log.jsonl"),"w") as f:
        for r in log: f.write(json.dumps(r,ensure_ascii=False)+"\n")
    print("NEW_USER=",NEW_USER)
    print(json.dumps(log,ensure_ascii=False,indent=2))

if __name__=="__main__":
    main()

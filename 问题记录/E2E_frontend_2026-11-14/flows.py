#!/usr/bin/env python3
# Real browser interaction flows for frontend E2E.
# Captures: get url (real final URL), snapshot (a11y tree), screenshot per step.
import subprocess, sys, time, os, re, json

BASE = "http://localhost:5173"
OUT = os.environ.get("E2E_OUT", ".")
ROLE = sys.argv[1]
TENANT = sys.argv[2]
LOGIN = sys.argv[3]
PW = sys.argv[4]
os.makedirs(f"{OUT}/shots/{ROLE}", exist_ok=True)
os.makedirs(f"{OUT}/snaps/{ROLE}", exist_ok=True)

def ab(args, timeout=60):
    r = subprocess.run(["agent-browser"] + args, capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()

def login():
    ab(["open", f"{BASE}/login"]); time.sleep(1.5)
    ab(["type", "#tenantId", TENANT])
    ab(["type", "#loginName", LOGIN])
    ab(["type", "#password", PW])
    ab(["click", "button[type=submit]"]); time.sleep(2.5)

def on_login(snap):
    return ("租户标识" in snap) and ("登录" in snap)

def goto(path, label, wait=1.4):
    url = BASE + path
    ab(["open", url]); time.sleep(1.0); ab(["wait", "--load", "load"]); time.sleep(wait)
    got = ab(["get", "url"])
    snap = ab(["snapshot"])
    shot = f"{OUT}/shots/{ROLE}/{label}.png"
    ab(["screenshot", shot])
    sf = f"{OUT}/snaps/{ROLE}/{label}.txt"
    with open(sf, "w") as f: f.write(snap)
    rec = {"label": label, "path": path, "url": got, "login": on_login(snap),
           "snap_head": snap[:200].replace("\n"," "), "snap_len": len(snap),
           "shot": shot, "snap": sf}
    if on_login(snap):
        login()
        ab(["open", url]); time.sleep(1.0); ab(["wait","--load","load"]); time.sleep(wait)
        got = ab(["get","url"]); snap = ab(["snapshot"]); ab(["screenshot", shot])
        with open(sf,"w") as f: f.write(snap)
        rec["url_retry"] = got; rec["snap_head_retry"] = snap[:200].replace("\n"," ")
    return rec

def click_text(txt):
    return ab(["click", f"text={txt}"])

def type_sel(sel, val):
    return ab(["type", sel, val])

def snapshot_head(label):
    snap = ab(["snapshot"])
    print(f"--- {label} ---")
    print(snap[:600])

records = []
login()
# ---- admin accessible pages: render check ----
pages = ["/", "/runs", "/runs/new", "/profile", "/rag", "/chat", "/agent-chat",
         "/reports", "/agents", "/approvals", "/reconciliation", "/audit",
         "/health", "/rag/management", "/config", "/config/runtime",
         "/config/tools", "/config/compliance", "/no-permission",
         "/this-route-does-not-exist"]
for p in pages:
    lab = re.sub(r"[^a-zA-Z0-9_]", "_", p.strip("/") or "root")
    records.append(goto(p, lab))

# ---- profile lookup interaction (valid + invalid) ----
# go to /profile, type a student id, submit
goto("/profile", "profile_page")
type_sel("#studentId", "S-2026-0001")
click_text("查询")  # may be button text; fallback below
time.sleep(2.0)
records.append({"label":"profile_submit_valid", "url": ab(["get","url"]),
                "snap_head": ab(["snapshot"])[:200].replace("\n"," ")})

with open(f"{OUT}/flows_{ROLE}.json","w") as f:
    json.dump(records, f, ensure_ascii=False, indent=2)
print("DONE", ROLE, len(records))

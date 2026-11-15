#!/usr/bin/env python3
# Browser walk orchestration for frontend E2E.
# Usage: python3 walk.py <role> <tenant> <login> <password>
import subprocess, sys, time, os, re, json

BASE = "http://localhost:5173"
OUT = os.environ.get("E2E_OUT", ".")
os.makedirs(f"{OUT}/screenshots/{sys.argv[1]}", exist_ok=True)
os.makedirs(f"{OUT}/snapshots/{sys.argv[1]}", exist_ok=True)

role, tenant, login, pw = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

# path, label, expect  (expect: accessible | redirect)
ROUTES = {
    "student": [
        ["/", "dashboard", "accessible"],
        ["/runs", "run_search", "accessible"],
        ["/runs/new", "new_run", "accessible"],
        ["/runs/00000000-0000-0000-0000-000000000000", "run_progress_missing", "accessible"],
        ["/runs/00000000-0000-0000-0000-000000000000/result", "run_result_missing", "accessible"],
        ["/profile", "profile_lookup", "accessible"],
        ["/profile/S-2026-0001", "profile_detail_missing", "accessible"],
        ["/rag", "rag_search", "accessible"],
        ["/chat", "general_chat", "accessible"],
        ["/agent-chat", "agent_chat", "accessible"],
        ["/reports", "reports", "accessible"],
        ["/no-permission", "no_permission", "accessible"],
        ["/this-route-does-not-exist", "not_found", "accessible"],
        ["/config", "config_gate", "redirect"],
        ["/config/runtime", "config_runtime_gate", "redirect"],
        ["/config/tools", "config_tools_gate", "redirect"],
        ["/config/compliance", "config_compliance_gate", "redirect"],
        ["/agents", "agents_gate", "redirect"],
        ["/approvals", "approvals_gate", "redirect"],
        ["/reconciliation", "reconciliation_gate", "redirect"],
        ["/audit", "audit_gate", "redirect"],
        ["/health", "health_gate", "redirect"],
        ["/rag/management", "rag_mgmt_gate", "redirect"],
    ],
    "admin": [
        ["/", "dashboard", "accessible"],
        ["/runs", "run_search", "accessible"],
        ["/runs/new", "new_run", "accessible"],
        ["/profile", "profile_lookup", "accessible"],
        ["/rag", "rag_search", "accessible"],
        ["/chat", "general_chat", "accessible"],
        ["/agent-chat", "agent_chat", "accessible"],
        ["/reports", "reports", "accessible"],
        ["/no-permission", "no_permission", "accessible"],
        ["/config", "config", "accessible"],
        ["/config/runtime", "config_runtime", "accessible"],
        ["/config/tools", "config_tools", "accessible"],
        ["/config/compliance", "config_compliance", "accessible"],
        ["/agents", "agents", "accessible"],
        ["/approvals", "approvals", "accessible"],
        ["/reconciliation", "reconciliation", "accessible"],
        ["/audit", "audit", "accessible"],
        ["/health", "health", "accessible"],
        ["/rag/management", "rag_mgmt", "accessible"],
    ],
    "approver": [
        ["/", "dashboard", "accessible"],
        ["/runs", "run_search", "accessible"],
        ["/runs/new", "new_run", "accessible"],
        ["/profile", "profile_lookup", "accessible"],
        ["/rag", "rag_search", "accessible"],
        ["/reports", "reports", "accessible"],
        ["/no-permission", "no_permission", "accessible"],
        ["/approvals", "approvals", "accessible"],
        ["/reconciliation", "reconciliation", "accessible"],
        ["/config", "config_gate", "redirect"],
        ["/agents", "agents_gate", "redirect"],
        ["/audit", "audit_gate", "redirect"],
        ["/health", "health_gate", "redirect"],
        ["/rag/management", "rag_mgmt_gate", "redirect"],
    ],
}

def ab(args):
    r = subprocess.run(["agent-browser"] + args, capture_output=True, text=True, timeout=90)
    return r.stdout

def parse_url(snap):
    m = re.search(r"https?://\S+", snap)
    if not m:
        return ""
    u = m.group(0)
    return u.rstrip(" ✓").rstrip()

def on_login(snap):
    return ("租户标识" in snap) and ("登录" in snap)

def do_login():
    ab(["open", f"{BASE}/login"])
    time.sleep(1.5)
    ab(["type", "#tenantId", tenant])
    ab(["type", "#loginName", login])
    ab(["type", "#password", pw])
    ab(["click", "button[type=submit]"])
    time.sleep(2.5)

routes = ROUTES.get(role, [])
log = []
do_login()
for path, label, expect in routes:
    url = BASE + path
    ab(["open", url]); time.sleep(1.2); ab(["wait", "--load", "load"]); time.sleep(1.2)
    snap = ab(["snapshot"])
    shot = f"{OUT}/screenshots/{role}/{label}.png"
    snapfile = f"{OUT}/snapshots/{role}/{label}.txt"
    ab(["screenshot", shot])
    with open(snapfile, "w") as f:
        f.write(snap)
    got = parse_url(snap)
    rec = {"path": path, "label": label, "expect": expect, "url_after": got,
           "login_shown": on_login(snap), "snap_len": len(snap),
           "screenshot": shot, "snapshot": snapfile,
           "snap_head": snap[:240].replace("\n", " ")}
    # auto re-login if token expired (redirected to login)
    if on_login(snap):
        do_login()
        ab(["open", url]); time.sleep(1.2); ab(["wait", "--load", "load"]); time.sleep(1.2)
        snap = ab(["snapshot"]); ab(["screenshot", shot])
        with open(snapfile, "w") as f:
            f.write(snap)
        rec["url_after_retry"] = parse_url(snap)
        rec["snap_head_retry"] = snap[:240].replace("\n", " ")
    log.append(rec)
    print(f"{label:24s} expect={expect:10s} got={got}")

with open(f"{OUT}/walk_{role}.json", "w") as f:
    json.dump(log, f, ensure_ascii=False, indent=2)
print("DONE", role, len(log))

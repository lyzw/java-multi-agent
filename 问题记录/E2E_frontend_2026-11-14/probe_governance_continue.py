#!/usr/bin/env python3
"""Continue F3 governance publish: decision + apply + verify, reusing session IDs."""
import json, subprocess, sys

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
ADMIN, ADMIN2, PW = "e2eadmin", "e2eadmin2", "E2e-Admin-2026"
AGENT_ID = "2c00f638-0bb7-4739-9f5b-6000e432bb12"
PUB_ID = "f281d843-9a66-43a0-a46c-ebd30bec278d"

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def login(name):
    out = sh(f'''curl -s --noproxy '*' -X POST {BASE}/api/auth/token -H 'Content-Type: application/json' -d '{json.dumps({"tenantId": TENANT, "loginName": name, "password": PW})}' ''')
    try:
        return json.loads(out.stdout)["accessToken"]
    except Exception:
        print(f"[login {name}] FAIL {out.stdout[:200]}"); return None

def call(method, path, token, body=None):
    cmd = f"curl -s --noproxy '*' -w '\\n__STATUS__%{{http_code}}' -X {method} {BASE}{path} -H 'Authorization: Bearer {token}'"
    if body is not None:
        cmd += " -H 'Content-Type: application/json' -d '" + json.dumps(body).replace("'", "'\\''") + "'"
    out = sh(cmd)
    text = out.stdout
    bt, st = text.rsplit("__STATUS__", 1) if "__STATUS__" in text else (text, "?")
    return st, bt

tok, tok2 = login(ADMIN), login(ADMIN2)
if not tok or not tok2:
    sys.exit(1)

print("=== current publish request state ===")
s, b = call("GET", f"/api/cap/config/publishes/{PUB_ID}", tok)
print("GET PUB", s, b[:400])

print("\n=== decision by distinct approver (e2eadmin2) ===")
s, b = call("POST", f"/api/cap/config/publishes/{PUB_ID}/decision", tok2, {"decision": "APPROVE", "comment": "E2E 审批通过"})
print("DECISION", s, b[:500])

print("\n=== apply (requester e2eadmin) ===")
s, b = call("POST", f"/api/cap/config/publishes/{PUB_ID}/apply", tok, {})
print("APPLY", s, b[:600])

print("\n=== verify GET /api/agents/{id} ===")
s, b = call("GET", f"/api/agents/{AGENT_ID}", tok)
print("GET AGENT", s, b[:500])

print("\n=== list /api/agents (dropdown source candidate) ===")
s, b = call("GET", "/api/agents", tok)
print("LIST AGENTS", s, b[:600])

print("\n=== governance catalog /api/cap/agents ===")
s, b = call("GET", "/api/cap/agents", tok)
print("GET CAP/AGENTS", s, b[:400])

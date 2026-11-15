#!/usr/bin/env python3
"""Governance publish flow (proper 3-step) using curl under the hood.

This avoids the urllib auth quirk seen earlier; mirrors a real browser/curl client.
"""
import json, subprocess, sys

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
ADMIN = "e2eadmin"
ADMIN2 = "e2eadmin2"
PW = "E2e-Admin-2026"

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def login(name):
    out = sh(f'''curl -s --noproxy '*' -X POST {BASE}/api/auth/token \
        -H 'Content-Type: application/json' \
        -d '{json.dumps({"tenantId": TENANT, "loginName": name, "password": PW})}' ''')
    if out.returncode != 0 or not out.stdout.strip():
        print(f"[login {name}] FAILED: {out.stderr}")
        return None
    try:
        return json.loads(out.stdout)["accessToken"]
    except Exception as e:
        print(f"[login {name}] parse fail: {out.stdout[:200]} ({e})")
        return None

def call(method, path, token, body=None):
    cmd = f"curl -s --noproxy '*' -w '\\n__STATUS__%{{http_code}}' -X {method} {BASE}{path}"
    cmd += f" -H 'Authorization: Bearer {token}'"
    if body is not None:
        cmd += " -H 'Content-Type: application/json'"
        cmd += f" -d '{json.dumps(body)}'"
    out = sh(cmd)
    text = out.stdout
    if "__STATUS__" in text:
        body_txt, status = text.rsplit("__STATUS__", 1)
    else:
        body_txt, status = text, "?"
    return status, body_txt

print("=== logins ===")
tok = login(ADMIN)
tok2 = login(ADMIN2)
if not tok or not tok2:
    print("LOGIN FAILED"); sys.exit(1)
print(f"  e2eadmin OK, e2eadmin2 OK (distinct approver)")

print("\n=== 1) create agent (proper first step) ===")
s, b = call("POST", "/api/agents", tok, {"name": "E2E 错题归因辅导 Agent", "description": "针对学生错题做归因分析与针对性练习"})
print("CREATE AGENT", s, b[:300])
if s != "200":
    print("  !! create agent failed; abort"); sys.exit(1)
agent_id = json.loads(b)["agentId"]
print("  agentId =", agent_id)

def payload(aid):
    return {
        "operation": "PUBLISH", "scope": "TENANT",
        "reason": "E2E 治理发布 e2e-tutor-agent（走正规三步流程）",
        "agents": [{
            "agentId": aid, "name": "E2E 错题归因辅导 Agent",
            "description": "针对学生错题做归因分析与针对性练习",
            "roles": ["tutor"], "enabledSkills": [], "skillBundles": [],
            "capabilityTags": [], "overrides": "{}", "orchestration": "{}",
            "instructions": json.dumps({"inlineInstructions": "你是错题归因辅导助手，帮助学生分析错题原因并给出针对性练习。"}),
            "modelRef": None, "generationParams": "{}"
        }]
    }

print("\n=== 2) valid publish (UUID agentId) -> expect PENDING ===")
s, b = call("POST", "/api/cap/config/publishes", tok, payload(agent_id))
print("VALID PUBLISH", s, b[:500])
if s not in ("200", "201"):
    print("  !! valid publish failed"); sys.exit(1)
pid = json.loads(b).get("requestId") or json.loads(b).get("id") or json.loads(b).get("publishId")
print("  publishId(requestId) =", pid)
if not pid:
    print("  !! no requestId in response; abort"); sys.exit(1)

print("\n=== 3) gate-reject payload (systemPromptRef) -> expect 422 ===")
bad = payload(agent_id)
bad["agents"][0]["instructions"] = json.dumps({"systemPromptRef": "x", "systemPromptVersion": 1})
s, b = call("POST", "/api/cap/config/publishes", tok, bad)
print("GATE-REJECT", s, b[:400])

print("\n=== 4) missing reason -> expect 400 ===")
miss = payload(agent_id); del miss["reason"]
s, b = call("POST", "/api/cap/config/publishes", tok, miss)
print("MISSING-REASON", s, b[:200])

print("\n=== 5) decision by distinct approver (e2eadmin2) -> APPROVE ===")
s, b = call("POST", f"/api/cap/config/publishes/{pid}/decision", tok2,
            {"decision": "APPROVE", "comment": "E2E 审批通过"})
print("DECISION", s, b[:400])

print("\n=== 6) apply (requester e2eadmin) -> APPLIED ===")
s, b = call("POST", f"/api/cap/config/publishes/{pid}/apply", tok, {})
print("APPLY", s, b[:500])

print("\n=== 7) verify GET /api/agents/{id} (should show latestPublishedAt) ===")
s, b = call("GET", f"/api/agents/{agent_id}", tok)
print("GET AGENT", s, b[:500])

print("\n=== 8) verify governance catalog GET /api/cap/agents ===")
s, b = call("GET", "/api/cap/agents", tok)
print("GET CAP/AGENTS", s, b[:500])

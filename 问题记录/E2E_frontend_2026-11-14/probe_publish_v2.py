#!/usr/bin/env python3
import json, base64, urllib.request, urllib.error

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
LOGIN = "e2eadmin"
PW = "E2e-Admin-2026"

def b64d(s):
    s += "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s).decode("utf-8", "replace")

def post(path, body, token=None, method="POST"):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return "ERR", str(e)

def get(path, token):
    req = urllib.request.Request(BASE + path, method="GET")
    req.add_header("Authorization", "Bearer " + token)
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return "ERR", str(e)

def login(name, pw):
    s, b = post("/api/auth/token", {"tenantId": TENANT, "loginName": name, "password": pw})
    print(f"[login {name}] {s}")
    if s == 200:
        tok = json.loads(b)["accessToken"]
        parts = tok.split(".")
        payload = json.loads(b64d(parts[1]))
        print(f"    roles={payload.get('roles')} principalId={payload.get('sub')}")
        return tok, payload
    print("    body:", b[:200])
    return None, None

tok, me = login(LOGIN, PW)
if not tok:
    raise SystemExit("login failed")

# try distinct approver
tok2, me2 = login("e2eadmin2", PW)

print("\n=== CREATE AGENT (proper first step) ===")
s, b = post("/api/agents", {"name": "E2E 错题归因辅导 Agent", "description": "针对学生错题做归因分析与针对性练习"})
print("CREATE AGENT", s, b[:400])
agent_id = None
if s == 200:
    agent_id = json.loads(b)["agentId"]
    print("  -> agentId =", agent_id)
elif s == 409:
    # already exists? try listing to find it
    print("  (conflict — agent may already exist; will look up via list)")

def build_payload(aid):
    return {
        "operation": "PUBLISH",
        "scope": "TENANT",
        "reason": "E2E 治理发布 e2e-tutor-agent（走正规三步流程）",
        "agents": [{
            "agentId": aid,
            "name": "E2E 错题归因辅导 Agent",
            "description": "针对学生错题做归因分析与针对性练习",
            "roles": ["tutor"],
            "enabledSkills": [],
            "skillBundles": [],
            "capabilityTags": [],
            "overrides": "{}",
            "orchestration": "{}",
            "instructions": json.dumps({"inlineInstructions": "你是错题归因辅导助手，帮助学生分析错题原因并给出针对性练习。"}),
            "modelRef": None,
            "generationParams": "{}"
        }]
    }

if agent_id:
    print("\n=== VALID PAYLOAD (UUID agentId) ===")
    s, b = post("/api/cap/config/publishes", build_payload(agent_id), tok)
    print("STATUS", s, b[:600])
    publish_id = None
    if s in (200, 201):
        try:
            publish_id = json.loads(b).get("id") or json.loads(b).get("publishId")
        except Exception:
            pass
        print("  publish_id =", publish_id)

    print("\n=== GATE-REJECT PAYLOAD (systemPromptRef) ===")
    bad = build_payload(agent_id)
    bad["agents"][0]["instructions"] = json.dumps({"systemPromptRef": "x", "systemPromptVersion": 1})
    s2, b2 = post("/api/cap/config/publishes", bad, tok)
    print("STATUS", s2, b2[:600])

    print("\n=== MISSING REASON ===")
    miss = build_payload(agent_id); del miss["reason"]
    s3, b3 = post("/api/cap/config/publishes", miss, tok)
    print("STATUS", s3, b3[:300])

    # If valid publish created, attempt governance decision + apply with distinct approver
    if publish_id and tok2:
        print("\n=== DECISION (distinct approver e2eadmin2) ===")
        s4, b4 = post(f"/api/cap/config/publishes/{publish_id}/decision",
                      {"decision": "APPROVE", "comment": "E2E 审批通过"}, tok2)
        print("DECISION", s4, b4[:400])
        print("\n=== APPLY (requester e2eadmin) ===")
        s5, b5 = post(f"/api/cap/config/publishes/{publish_id}/apply", {}, tok)
        print("APPLY", s5, b5[:400])
        print("\n=== VERIFY GET /api/agents/{id} ===")
        s6, b6 = get(f"/api/agents/{agent_id}", tok)
        print("GET AGENT", s6, b6[:600])

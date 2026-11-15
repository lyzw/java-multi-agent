#!/usr/bin/env python3
import json, urllib.request, urllib.error

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
LOGIN = "e2eadmin"
PW = "E2e-Admin-2026"

def post(path, body, token=None):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        r = urllib.request.urlopen(req, timeout=20)
        return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return "ERR", str(e)

def login():
    s, b = post("/api/auth/token", {"tenantId": TENANT, "loginName": LOGIN, "password": PW})
    print("LOGIN", s, b[:200])
    if s == 200:
        return json.loads(b)["accessToken"]
    return None

tok = login()
if not tok:
    print("LOGIN FAILED"); raise SystemExit(1)

valid_payload = {
    "operation": "PUBLISH",
    "scope": "TENANT",
    "reason": "E2E 治理发布 e2e-tutor-agent（走正规三步流程）",
    "agents": [{
        "agentId": "e2e-tutor-agent",
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

# 1) valid payload
s, b = post("/api/cap/config/publishes", valid_payload, tok)
print("\n=== VALID PAYLOAD ===")
print("STATUS", s)
print("BODY", b[:800])

# 2) gate-rejected payload: instructions with systemPromptRef -> ConfigPublishRejectedException
bad_payload = json.loads(json.dumps(valid_payload))
bad_payload["agents"][0]["instructions"] = json.dumps({"systemPromptRef": "x", "systemPromptVersion": 1})
s2, b2 = post("/api/cap/config/publishes", bad_payload, tok)
print("\n=== GATE-REJECT PAYLOAD (systemPromptRef) ===")
print("STATUS", s2)
print("BODY", b2[:800])

# 3) missing reason -> 400 branch
miss = json.loads(json.dumps(valid_payload)); del miss["reason"]
s3, b3 = post("/api/cap/config/publishes", miss, tok)
print("\n=== MISSING REASON ===")
print("STATUS", s3)
print("BODY", b3[:400])

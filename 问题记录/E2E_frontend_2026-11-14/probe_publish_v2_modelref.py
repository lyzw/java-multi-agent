#!/usr/bin/env python3
"""E2E 治理重发布（v2）：补齐 modelRef。

背景（2026-11-14 浏览器级复测根因）：
  首轮治理发布（probe_governance.py）payload 里写了 `"modelRef": None`
  → cap_agent_def.model_ref = NULL
  → AgentModelConfigAssembler.assemble() 取不到模型标识即 fail-closed
  → 每个 Run 执行准入判成 CONFIG_UNAVAILABLE（Run 永远跑不起来）。

本脚本走**同样的正规三步治理流程**（申请 → 双人审批 → apply）重发一版，
补上 modelRef=MiniMax-M2.5（worker 实际使用的模型），不绕过任何治理门禁。
"""
import json, subprocess, sys

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
AGENT_ID = "2c00f638-0bb7-4739-9f5b-6000e432bb12"
ADMIN = "e2eadmin"
ADMIN2 = "e2eadmin2"
PW = "E2e-Admin-2026"
MODEL = "MiniMax-M2.5"


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


def login(name):
    out = sh(f"""curl -s --noproxy '*' -X POST {BASE}/api/auth/token \
        -H 'Content-Type: application/json' \
        -d '{json.dumps({"tenantId": TENANT, "loginName": name, "password": PW})}' """)
    try:
        return json.loads(out.stdout)["accessToken"]
    except Exception as e:
        print(f"[login {name}] FAILED: {out.stdout[:200]} ({e})")
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
    print("LOGIN FAILED")
    sys.exit(1)
print("  e2eadmin OK, e2eadmin2 OK (distinct approver)")

payload = {
    "operation": "PUBLISH",
    "scope": "TENANT",
    "reason": "E2E 治理发布 v2：补齐 modelRef（首版 modelRef 为 null 导致执行准入 fail-closed CONFIG_UNAVAILABLE）",
    "agents": [{
        "agentId": AGENT_ID,
        "name": "E2E 错题归因辅导 Agent",
        "description": "针对学生错题做归因分析与针对性练习",
        "roles": ["tutor"],
        "enabledSkills": [],
        "skillBundles": [],
        "capabilityTags": [],
        "overrides": "{}",
        "orchestration": "{}",
        "instructions": json.dumps({
            "inlineInstructions": "你是错题归因辅导助手，帮助学生分析错题原因并给出针对性练习。"
        }),
        "modelRef": MODEL,
        "generationParams": json.dumps({
            "temperature": 0.2,
            "maxOutputTokens": 1024,
            "timeoutMs": 60000,
        }),
    }],
}

print("\n=== 1) publish -> expect PENDING ===")
s, b = call("POST", "/api/cap/config/publishes", tok, payload)
print("PUBLISH", s, b[:400])
if s not in ("200", "201"):
    print("  !! publish failed")
    sys.exit(1)
pid = json.loads(b).get("requestId") or json.loads(b).get("id")
print("  requestId =", pid)
if not pid:
    print("  !! no requestId")
    sys.exit(1)

print("\n=== 2) decision by distinct approver -> APPROVE ===")
s, b = call("POST", f"/api/cap/config/publishes/{pid}/decision", tok2,
            {"decision": "APPROVE", "comment": "E2E 审批通过（补齐 modelRef）"})
print("DECISION", s, b[:300])

print("\n=== 3) apply (requester) -> APPLIED ===")
s, b = call("POST", f"/api/cap/config/publishes/{pid}/apply", tok, {})
print("APPLY", s, b[:400])

print("\n=== 4) verify GET /api/agents/{id} ===")
s, b = call("GET", f"/api/agents/{AGENT_ID}", tok)
print("GET AGENT", s, b[:600])

print("\n=== 5) verify GET /api/cap/agents ===")
s, b = call("GET", "/api/cap/agents", tok)
print("GET CAP/AGENTS", s, b[:600])

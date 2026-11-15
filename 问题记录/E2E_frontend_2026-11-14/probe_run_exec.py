#!/usr/bin/env python3
"""Run 端到端执行探针：创建 → 轮询状态到终态。

用途：验证 worker 灰度配置（AGENT_TENANT_SLUGS）+ 治理发布 modelRef 补齐后，
      学生发起的辅导 Run 能真正被消费、执行并落终态（不再 CONFIG_UNAVAILABLE）。
"""
import json, subprocess, sys, time

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
AGENT_ID = "2c00f638-0bb7-4739-9f5b-6000e432bb12"
LOGIN = sys.argv[1] if len(sys.argv) > 1 else "e2estudent"
PW = "E2e-Admin-2026"


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


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


out = sh(f"""curl -s --noproxy '*' -X POST {BASE}/api/auth/token \
    -H 'Content-Type: application/json' \
    -d '{json.dumps({"tenantId": TENANT, "loginName": LOGIN, "password": PW})}' """)
tok = json.loads(out.stdout)["accessToken"]
print(f"[login {LOGIN}] OK")

idem = f"e2e-exec-{int(time.time())}"
payload = {
    "agentId": AGENT_ID,
    "idempotencyKey": idem,
    "input": {
        "studentId": "S-2026-0001",
        "subject": "数学",
        "grade": "初三",
        "questionText": "已知抛物线 y=x^2-2x+3 与 x 轴无交点，判断下列说法是否正确，并说明理由。",
        "studentSolution": "我直接代入了 x=0 得到 y=3 大于 0，就认为没有交点。",
        "correctAnswer": "没有交点是对的，但理由不完整：应通过判别式 b^2-4ac=-8<0 来说明恒大于 0。",
    },
}
s, b = call("POST", "/api/runs", tok, payload)
print("CREATE RUN", s, b[:300])
if s not in ("200", "201"):
    sys.exit(1)
run_id = json.loads(b)["runId"]
print("  runId =", run_id)

final = None
for i in range(60):
    s, b = call("GET", f"/api/runs/{run_id}", tok)
    try:
        st = json.loads(b).get("state") or json.loads(b).get("status")
    except Exception:
        st = f"<{s}:{b[:120]}>"
    print(f"  [{i:02d}] status={s} state={st}")
    if st in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT"):
        final = st
        break
    time.sleep(3)

print("\n=== final ===", final)
s, b = call("GET", f"/api/runs/{run_id}", tok)
print("STATUS", s, b[:500])
s, b = call("GET", f"/api/runs/{run_id}/result", tok)
print("RESULT", s, b[:700])
print("RUN_ID=" + run_id)

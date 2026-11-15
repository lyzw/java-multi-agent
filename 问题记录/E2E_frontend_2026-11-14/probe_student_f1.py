#!/usr/bin/env python3
"""F1 学生角色 API 级端到端验证（2026-11-14）。

覆盖：登录带角色 → 列表收窄 → 创建 Run（requester_id 落库）→ 学生可见本人 Run
→ 学生不可见他人/历史 Run → 学生被治理端点 403 拒绝 → 家长（PARENT）同能力 → admin 对照不受收窄。
"""
import json, subprocess, sys

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
PW = "E2e-Admin-2026"
AGENT_ID = "2c00f638-0bb7-4739-9f5b-6000e432bb12"  # F3 已发布的 Agent
STUDENT_PID = "5abc1bfc-1824-46b5-b20d-36f20f56dc27"

results = []

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def login(name):
    out = sh(f"""curl -s --noproxy '*' -X POST {BASE}/api/auth/token -H 'Content-Type: application/json' -d '{json.dumps({"tenantId": TENANT, "loginName": name, "password": PW})}'""")
    try:
        body = json.loads(out.stdout)
        return body.get("accessToken"), body.get("roles")
    except Exception:
        return None, out.stdout[:200]

def call(method, path, token, body=None):
    cmd = f"curl -s --noproxy '*' -w '\n__STATUS__%{{http_code}}' -X {method} {BASE}{path} -H 'Authorization: Bearer {token}'"
    if body is not None:
        cmd += f" -H 'Content-Type: application/json' -d '{json.dumps(body, ensure_ascii=False)}'"
    out = sh(cmd)
    text = out.stdout
    if "__STATUS__" in text:
        b, s = text.rsplit("__STATUS__", 1)
        return s.strip(), b
    return "?", text

def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), name, detail[:160])

# ── 1) 学生登录：角色回带 ──
stok, sroles = login("e2estudent")
record("1 学生登录且 roles=[STUDENT]", stok is not None and sroles == ["STUDENT"], f"roles={sroles}")
if not stok:
    sys.exit("学生登录失败，终止")
atok, aroles = login("e2eadmin")
record("1b admin 登录对照", atok is not None and aroles and "TENANT_ADMIN" in aroles, f"roles={aroles}")

# ── 2) 学生 GET /api/runs：200（初始空/仅本人）──
s, b = call("GET", "/api/runs", stok)
before_ids = []
try:
    before_ids = [i["runId"] for i in json.loads(b).get("items", [])]
except Exception:
    pass
record("2 学生 GET /api/runs → 200", s == "200", f"status={s} items={len(before_ids)}")

# ── 3) 学生创建 Run → 201 ──
key = f"e2e-f1-student-{__import__('uuid').uuid4()}"
s, b = call("POST", "/api/runs", stok, {"idempotencyKey": key, "agentId": AGENT_ID, "input": {"question": "解方程 x^2-5x+6=0"}})
run_id = None
try:
    run_id = json.loads(b).get("runId")
except Exception:
    pass
record("3 学生 POST /api/runs → 201", s == "201" and run_id, f"status={s} runId={run_id} body={b[:120]}")

# ── 4) requester_id 落库验证（DB）──
if run_id:
    q = subprocess.run(["docker", "exec", "agent-pg17", "psql",
        "host=rm-cn-hhf50fbhh0001uzo.rwlb.rds.aliyuncs.com port=5432 dbname=agent_dev user=ai_agent password=Aiagent123$",
        "-tAc", f"SELECT requester_id::text FROM run WHERE run_id='{run_id}'"],
        capture_output=True, text=True)
    rid = q.stdout.strip()
    record("4 run.requester_id = 学生 principalId", rid == STUDENT_PID, f"requester_id={rid}")

# ── 5) 学生再次列表：可见本人 Run ──
s, b = call("GET", "/api/runs", stok)
ids = []
try:
    ids = [i["runId"] for i in json.loads(b).get("items", [])]
except Exception:
    pass
record("5 学生列表含本人 Run", run_id in ids, f"items={len(ids)}")

# ── 6) admin 创建一个 Run（requester=admin）→ 学生不可见 ──
key2 = f"e2e-f1-admin-{__import__('uuid').uuid4()}"
s, b = call("POST", "/api/runs", atok, {"idempotencyKey": key2, "agentId": AGENT_ID, "input": {"question": "admin 建的对照 Run"}})
admin_run = None
try:
    admin_run = json.loads(b).get("runId")
except Exception:
    pass
s, b = call("GET", "/api/runs", stok)
ids = []
try:
    ids = [i["runId"] for i in json.loads(b).get("items", [])]
except Exception:
    pass
record("6 admin 的 Run 对学生不可见（隔离）", admin_run is not None and admin_run not in ids,
       f"adminRun={admin_run} inStudentList={admin_run in ids if admin_run else None}")

# ── 7) admin 列表不受收窄（可见两个 Run）──
s, b = call("GET", "/api/runs", atok)
aids = []
try:
    aids = [i["runId"] for i in json.loads(b).get("items", [])]
except Exception:
    pass
record("7 admin 列表可见 student Run + admin Run（不收窄）",
       (run_id in aids) and (admin_run in aids), f"admin items={len(aids)}")

# ── 8) 学生读本人 Run 详情/结果 → 200 ──
if run_id:
    s1, _ = call("GET", f"/api/runs/{run_id}", stok)
    s2, _ = call("GET", f"/api/runs/{run_id}/result", stok)
    record("8 学生读本人 Run status/result", s1 == "200" and s2 in ("200", "404"),
           f"status={s1}/{s2}（result 404=尚未完成，合法）")

# ── 9) 学生读 admin 的 Run → 404（反探测）──
if admin_run:
    s1, _ = call("GET", f"/api/runs/{admin_run}", stok)
    record("9 学生读他人 Run → 404（不泄露存在性）", s1 == "404", f"status={s1}")

# ── 10) 学生被治理动作拒绝（治理端点 POST /api/cap/config/publishes）──
# 注：GET /api/config 是 anyRequest().authenticated() 的只读端点（不暴露密钥），
#     任何已认证租户用户可读 → 200 是预期设计，不是越权。
s1, b1 = call("POST", "/api/cap/config/publishes", stok,
              {"agentId": AGENT_ID, "reason": "学生尝试治理动作（应被拒）"})
record("10 学生治理动作 → 403", s1 == "403", f"status={s1} body={b1[:120]}")

# ── 11) 家长（PARENT）登录与列表 ──
ptok, proles = login("e2eparent")
record("11 家长登录且 roles=[PARENT]", ptok is not None and proles == ["PARENT"], f"roles={proles}")
if ptok:
    s, b = call("GET", "/api/runs", ptok)
    record("11b 家长 GET /api/runs → 200（同样收窄语义）", s == "200", f"status={s}")

print("\n==== 汇总 ====")
ok = sum(1 for _, o, _ in results if o)
print(f"{ok}/{len(results)} PASS")
sys.exit(0 if ok == len(results) else 1)

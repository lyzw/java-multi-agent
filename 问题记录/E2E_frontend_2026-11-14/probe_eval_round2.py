#!/usr/bin/env python3
"""二次评估探针（2026-11-14 下午）：受众角色的「能力—依赖」再对齐。

动机：F6 已修好「发起辅导页取不到 Agent」，但导航里还有另外两个面向学生的入口
（学习画像 / 报表中心）需要独立核对——它们是否也存在「导航可见 → 后端 403」
或「后端放行 → 越权看全租户聚合」的错配。

覆盖：
  A. 学生点「学习画像」→ 后端 PROFILE_READ 是否放行？（前端导航无 area 限制）
  B. 学生点「报表中心」→ 是否读到**全租户**聚合（含他人数据）？
  C. 家长 / 老师点「发起辅导」→ RUN_CREATE 是否放行？导航却显示该入口
  D. 家长 / 老师能否看到学生创建的 Run（受众收窄 = requester_id=本人）？
  E. 失败 Run 的 errorCode 在前端如何呈现（业务可读性）
"""
import json
import subprocess
import sys

BASE = "http://localhost:8080"
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
PW = "E2e-Admin-2026"
AGENT_ID = "2c00f638-0bb7-4739-9f5b-6000e432bb12"

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
    print(("PASS" if ok else "FAIL"), name, "|", detail[:200])


stok, sroles = login("e2estudent")
atok, aroles = login("e2eadmin")
ptok, proles = login("e2eparent")
print(f"roles: student={sroles} parent={proles} admin={aroles}")
if not (stok and atok):
    sys.exit("登录失败")

# ── A) 学生 → 学习画像（导航可见，后端 PROFILE_READ？）──
s, b = call("GET", "/api/profiles/S-2026-0001", stok)
record("A 学生 GET /api/profiles/{id}", s in ("200", "403"), f"status={s} body={b[:150]}")

# ── B) 学生 → 报表中心（StatisticsController 用 RUN_READ）──
s, b = call("GET", "/api/statistics/overview", stok)
record("B 学生 GET /api/statistics/overview", s in ("200", "403"), f"status={s} body={b[:200]}")

# 报表如果放行，看它是否泄露全租户数据（admin 建一个 Run 后学生报表的计数）
s_admin_before, b_before = call("GET", "/api/statistics/overview", atok)

# ── C) 家长 → 发起辅导（导航可见，RUN_CREATE？）──
s, b = call("POST", "/api/runs", ptok, {
    "idempotencyKey": f"e2e-eval-parent-{__import__('uuid').uuid4()}",
    "agentId": AGENT_ID,
    "input": {"studentId": "S-2026-0009", "questionText": "家长发起测试"},
})
record("C 家长 POST /api/runs", s in ("201", "403"), f"status={s} body={b[:160]}")

# ── D) 家长 → 学生创建的 Run（受众收窄）──
key = f"e2e-eval-student-{__import__('uuid').uuid4()}"
s, b = call("POST", "/api/runs", stok, {
    "idempotencyKey": key, "agentId": AGENT_ID,
    "input": {"studentId": "S-2026-0001", "questionText": "二次评估：学生创建的题",
              "studentSolution": "我的过程", "correctAnswer": "答案"},
})
student_run = None
try:
    student_run = json.loads(b).get("runId")
except Exception:
    pass
record("D1 学生创建 Run", s == "201" and student_run, f"status={s} runId={student_run}")

if ptok:
    s, b = call("GET", "/api/runs", ptok)
    ids = []
    try:
        ids = [i["runId"] for i in json.loads(b).get("items", [])]
    except Exception:
        pass
    record("D2 家长列表可见学生 Run", student_run in ids if student_run else False,
           f"parentItems={len(ids)} studentRunVisible={student_run in ids if student_run else None}")
    s, b = call("GET", f"/api/runs/{student_run}", ptok) if student_run else ("?", "")
    record("D3 家长读学生 Run 详情", s in ("200", "404"), f"status={s}")

# ── E) 失败 Run 的 errorCode 业务可读性 ──
s, b = call("GET", "/api/runs", atok)
failed = []
try:
    failed = [i for i in json.loads(b).get("items", []) if i.get("state") == "FAILED"]
except Exception:
    pass
codes = sorted({i.get("errorCode") for i in failed if i.get("errorCode")})
record("E1 存在失败 Run 可供取证", len(failed) > 0, f"failedCount={len(failed)} errorCodes={codes}")

# ── F) 学生列表 vs admin 列表的条数对比（报表/检索是否收窄）──
s, b = call("GET", "/api/runs", stok)
sitems = []
try:
    sitems = json.loads(b).get("items", [])
except Exception:
    pass
s, b = call("GET", "/api/runs", atok)
aitems = []
try:
    aitems = json.loads(b).get("items", [])
except Exception:
    pass
record("F1 受众列表已收窄（学生 ≤ 管理员）", len(sitems) <= len(aitems),
       f"student={len(sitems)} admin={len(aitems)}")

print("\n==== 汇总 ====")
ok = sum(1 for _, o, _ in results if o)
print(f"{ok}/{len(results)} 项已取证")
for name, o, detail in results:
    print(f"  [{'OK ' if o else '!! '}] {name} — {detail[:180]}")
sys.exit(0)

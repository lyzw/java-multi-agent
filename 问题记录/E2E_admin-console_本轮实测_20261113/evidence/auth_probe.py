#!/usr/bin/env python3
"""Phase A/B 真实 HTTP 探针：6 平台角色登录 + 身份域隔离 + 错误路径 + 只读端点矩阵。
直接打平台后端 8080（与 admin-console 的 5180 同源代理转发目标一致），RAG 直打 8081。
结果写入 evidence/auth_probe.jsonl（每行一条：method,path,role,status,bytes,x_trace_id,ts）。
"""
import urllib.request, urllib.error, json, time, datetime, ssl

PLATFORM = "http://127.0.0.1:8080/api/platform/v1"
RAG = "http://127.0.0.1:8081/api/v1"
PW = "Plat#2026!"
ROLES = {
    "plat-superadmin": "PLATFORM_SUPER_ADMIN",
    "plat-lifecycle": "TENANT_LIFECYCLE_ADMIN",
    "plat-resource": "PLATFORM_RESOURCE_ADMIN",
    "plat-security": "PLATFORM_SECURITY_ADMIN",
    "plat-auditor": "PLATFORM_AUDITOR",
    "plat-support": "PLATFORM_SUPPORT",
}

out = []
def record(method, path, role, status, body_len, trace, note=""):
    out.append({"ts": datetime.datetime.now().isoformat(timespec="seconds"),
                "method": method, "path": path, "role": role,
                "status": status, "bytes": body_len, "x_trace_id": trace, "note": note})

def req(method, full_url, token=None, body=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(full_url, data=data, headers=headers, method=method)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        status = resp.status
        raw_body = resp.read()
        trace = resp.headers.get("X-Trace-Id", "")
        return status, raw_body, trace
    except urllib.error.HTTPError as e:
        raw_body = e.read()
        trace = e.headers.get("X-Trace-Id", "") if e.headers else ""
        return e.code, raw_body, trace

# ---------- 1. 6 角色登录 ----------
print("=" * 70)
print("1) 6 平台角色登录 (POST /api/platform/v1/auth/login)")
print("=" * 70)
tokens = {}
for user, role in ROLES.items():
    st, body, trace = req("POST", PLATFORM + "/auth/login", body={"username": user, "password": PW})
    rec = {}
    try: rec = json.loads(body)
    except Exception: pass
    tokens[user] = rec.get("accessToken")
    aud = rec.get("aud") or (rec.get("token", {}).get("aud") if isinstance(rec.get("token"), dict) else None)
    roles_ret = rec.get("roles")
    exp = rec.get("expiresIn") or rec.get("expires_in")
    ok = st == 200 and rec.get("accessToken")
    print(f"  [{st}] {user:<16} role={role:<26} aud={aud} expiresIn={exp} roles={roles_ret} trace={trace}")
    record("POST", "/auth/login", user, st, len(body), trace, f"aud={aud};roles={roles_ret}")

# ---------- 2. 错误路径 / 身份域隔离 ----------
print("\n" + "=" * 70)
print("2) 错误路径：无令牌401 / 错误凭据401 / 身份域隔离401")
print("=" * 70)
# 无令牌
st, body, trace = req("GET", PLATFORM + "/users")
print(f"  [无令牌]   GET /users -> {st} (期望401)")
record("GET", "/users", "none", st, len(body), trace, "期望401")
# 错误凭据
st, body, trace = req("POST", PLATFORM + "/auth/login", body={"username": "plat-superadmin", "password": "wrong"})
print(f"  [错口令]   POST /auth/login -> {st} (期望401)")
record("POST", "/auth/login", "bad-cred", st, len(body), trace, "期望401")
# 不存在账号
st, body, trace = req("POST", PLATFORM + "/auth/login", body={"username": "ghost-user", "password": PW})
print(f"  [不存在]   POST /auth/login(ghost) -> {st} (期望401)")
record("POST", "/auth/login", "ghost", st, len(body), trace, "期望401")
# 身份域隔离：平台令牌打租户链端点 /api/cap/catalog/tools
st, body, trace = req("GET", "http://127.0.0.1:8080/api/cap/catalog/tools", token=tokens["plat-superadmin"])
print(f"  [隔离]     GET /api/cap/catalog/tools(平台令牌) -> {st} (期望401 身份域隔离)")
record("GET", "/api/cap/catalog/tools", "plat-superadmin", st, len(body), trace, "期望401 身份域隔离")
# 隔离：平台令牌打 /api/chat/stream (租户链 SSE)
st, body, trace = req("POST", "http://127.0.0.1:8080/api/chat/stream", token=tokens["plat-superadmin"], body={})
print(f"  [隔离]     POST /api/chat/stream(平台令牌) -> {st} (期望401 身份域隔离)")
record("POST", "/api/chat/stream", "plat-superadmin", st, len(body), trace, "期望401 身份域隔离")

# ---------- 3. 只读端点矩阵（6 角色 × 端点） ----------
print("\n" + "=" * 70)
print("3) 只读端点矩阵（6 角色 × 关键只读端点）")
print("=" * 70)
READ_EP = [
    ("GET", "/auth/me"),
    ("GET", "/users"),
    ("GET", "/roles"),
    ("GET", "/permissions"),
    ("GET", "/tenants"),
    ("GET", "/capabilities/agents"),
    ("GET", "/capabilities/tools"),
    ("GET", "/capabilities/skills"),
    ("GET", "/capabilities/mcps"),
    ("GET", "/audit-events"),
]
for user, _ in ROLES.items():
    line = f"  {user:<16}"
    for method, ep in READ_EP:
        st, body, trace = req(method, PLATFORM + ep, token=tokens[user])
        record(method, ep, user, st, len(body), trace)
        line += f" {ep.split('/')[-1]}={st}"
    print(line)

# ---------- 4. RAG 管理面只读（超管，直打 8081） ----------
print("\n" + "=" * 70)
print("4) RAG 管理面只读端点（plat-superadmin，直打 8081）")
print("=" * 70)
for ep in ["/management/statistics", "/management/sources", "/management/dictionaries",
           "/management/reviewers", "/management/kp-aliases", "/management/ingest-jobs",
           "/management/review-queue", "/management/sources?limit=5"]:
    st, body, trace = req("GET", RAG + ep, token=tokens["plat-superadmin"])
    record("GET", ep, "plat-superadmin", st, len(body), trace)
    print(f"  [RAG {st}] GET {ep}  bytes={len(body)}")

# ---------- 写 JSONL ----------
with open("问题记录/E2E_admin-console_本轮实测_20261113/evidence/auth_probe.jsonl", "w") as f:
    for r in out:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print(f"\n[done] {len(out)} 条记录 -> evidence/auth_probe.jsonl")

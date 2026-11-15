#!/usr/bin/env python3
"""Phase B 补充探针：澄清权限模型与 RAG 作用域头。"""
import urllib.request, urllib.error, json, datetime

PLATFORM = "http://127.0.0.1:8080/api/platform/v1"
RAG = "http://127.0.0.1:8081/api/v1"
PW = "Plat#2026!"
ROLES = ["plat-superadmin","plat-lifecycle","plat-resource","plat-security","plat-auditor","plat-support"]

def req(method, full_url, token=None, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    if token: h["Authorization"] = "Bearer " + token
    if headers: h.update(headers)
    r = urllib.request.Request(full_url, data=data, headers=h, method=method)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        return resp.status, resp.read().decode("utf-8", "replace"), resp.headers.get("X-Trace-Id","")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace"), (e.headers.get("X-Trace-Id","") if e.headers else "")

# login
tokens = {}
for u in ROLES:
    st, body, _ = req("POST", PLATFORM+"/auth/login", body={"username":u,"password":PW})
    tokens[u] = json.loads(body).get("accessToken")

print("### A) 403 响应体（识别所需权限）###")
checks = [("plat-lifecycle","/users"),("plat-resource","/tenants"),("plat-security","/tenants"),
          ("plat-lifecycle","/capabilities/tools"),("plat-security","/capabilities/agents"),
          ("plat-resource","/audit-events"),("plat-support","/audit-events"),
          ("plat-lifecycle","/roles"),("plat-auditor","/permissions")]
for u, ep in checks:
    st, body, _ = req("GET", PLATFORM+ep, token=tokens[u])
    short = body[:240].replace("\n"," ")
    print(f"  {u} GET {ep} -> {st} | {short}")

print("\n### B) 超管成功响应形状（证据）###")
for ep in ["/users","/tenants","/roles","/capabilities/tools","/audit-events"]:
    st, body, _ = req("GET", PLATFORM+ep, token=tokens["plat-superadmin"])
    try: d=json.loads(body)
    except Exception: d={}
    keys = list(d.keys())[:8] if isinstance(d,dict) else type(d).__name__
    n = len(d.get("data", d.get("content", []))) if isinstance(d,dict) else "?"
    print(f"  GET {ep} -> {st} keys={keys} items~{n}")

print("\n### C) RAG 作用域头（前端会带 X-Rag-Scope）###")
for scope_hdr, label in [( {"X-Rag-Scope":"PLATFORM"},"PLATFORM"),
                         ( {"X-Rag-Scope":"ALL"},"ALL(期望501)"),
                         ( {"X-Rag-Scope":"TENANT"},"TENANT无租户(期望400/403)") ]:
    st, body, _ = req("GET", RAG+"/management/statistics", token=tokens["plat-superadmin"], headers=scope_hdr)
    print(f"  [{label}] GET /management/statistics -> {st} | {body[:160].replace(chr(10),' ')}")

print("\n### D) RAG 只读端点（带 PLATFORM 作用域头）###")
for ep in ["/management/sources?limit=3","/management/dictionaries","/management/reviewers",
           "/management/kp-aliases","/management/ingest-jobs?limit=3","/management/review-queue","/management/sources"]:
    st, body, _ = req("GET", RAG+ep, token=tokens["plat-superadmin"], headers={"X-Rag-Scope":"PLATFORM"})
    print(f"  [RAG {st}] GET {ep} bytes={len(body)}")

print("\n### E) /api/chat/stream 身份域隔离（平台令牌+合理body）###")
st, body, _ = req("POST", "http://127.0.0.1:8080/api/chat/stream",
                  token=tokens["plat-superadmin"],
                  body={"tenantId":"t-a","message":"hello","conversationId":"x"})
print(f"  POST /api/chat/stream(平台令牌) -> {st} | {body[:200].replace(chr(10),' ')}")

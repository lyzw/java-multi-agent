#!/usr/bin/env python3
"""admin-console 端到端测试 · API 契约 / 身份域 / 6 角色权限矩阵实测
经 Vite devServer 同源代理（localhost:5180）发起，与浏览器链路一致。
输出 ../http/api_probe.jsonl（含请求、响应状态、响应体摘要、X-Trace-Id）
"""
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone

BASE = 'http://localhost:5180'
OUT = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/http/api_probe.jsonl'
PASSWORD = 'Plat#2026!'
ACCOUNTS = {
    'plat-superadmin': 'PLATFORM_SUPER_ADMIN',
    'plat-lifecycle': 'TENANT_LIFECYCLE_ADMIN',
    'plat-resource': 'PLATFORM_RESOURCE_ADMIN',
    'plat-security': 'PLATFORM_SECURITY_ADMIN',
    'plat-auditor': 'PLATFORM_AUDITOR',
    'plat-support': 'PLATFORM_SUPPORT',
}
rows = []


def call(case, method, path, token=None, body=None, extra=None, expect=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Accept': 'application/json'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if extra:
        headers.update(extra)
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    status, text, trace = 0, '', ''
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            status = r.status
            text = r.read().decode('utf-8', 'replace')
            trace = r.headers.get('X-Trace-Id', '')
    except urllib.error.HTTPError as e:
        status = e.code
        text = e.read().decode('utf-8', 'replace')
        trace = e.headers.get('X-Trace-Id', '')
    except Exception as e:
        text = 'NETWORK_ERROR: ' + str(e)
    rec = {
        'case': case, 'at': datetime.now(timezone.utc).isoformat(),
        'method': method, 'path': path, 'hasToken': bool(token),
        'status': status, 'traceId': trace, 'expect': expect,
        'body': text[:1500],
    }
    rows.append(rec)
    print(f"{case:44s} {method:5s} {path:56s} -> {status}")
    return rec


def login(user, password=PASSWORD):
    r = call('login:' + user, 'POST', '/api/platform/v1/auth/login',
             body={'username': user, 'password': password}, expect=200)
    try:
        return json.loads(r['body']).get('accessToken')
    except Exception:
        return None


# ── A. 认证与异常 ───────────────────────────────────────────
call('A01 登录-空 body', 'POST', '/api/platform/v1/auth/login', body={}, expect=401)
call('A02 登录-不存在的账号', 'POST', '/api/platform/v1/auth/login',
     body={'username': 'no-such-user-e2e', 'password': PASSWORD}, expect=401)
call('A03 登录-错误口令', 'POST', '/api/platform/v1/auth/login',
     body={'username': 'plat-superadmin', 'password': 'wrong-pass-e2e'}, expect=401)
call('A04 登录-租户账号尝试登录平台', 'POST', '/api/platform/v1/auth/login',
     body={'username': 'e2eadmin', 'password': PASSWORD}, expect=401)
tokens = {}
for u, role in ACCOUNTS.items():
    tokens[u] = login(u)

call('A05 /auth/me 无令牌', 'GET', '/api/platform/v1/auth/me', expect=401)
call('A06 /auth/me 伪造令牌', 'GET', '/api/platform/v1/auth/me', token='forged.token.e2e', expect=401)
call('A07 /auth/me 有效令牌', 'GET', '/api/platform/v1/auth/me', token=tokens['plat-superadmin'], expect=200)

# ── B. 6 角色 × 只读端点权限矩阵 ───────────────────────────
READ_ENDPOINTS = [
    ('/api/platform/v1/users', '平台用户列表'),
    ('/api/platform/v1/roles', '角色矩阵'),
    ('/api/platform/v1/tenants', '租户列表'),
    ('/api/platform/v1/audit-events', '平台审计'),
    ('/api/platform/v1/capabilities/agents', 'Agent 注册表'),
    ('/api/platform/v1/capabilities/tools', 'Tool 目录'),
    ('/api/platform/v1/capabilities/skills', 'Skill 目录'),
    ('/api/platform/v1/capabilities/mcps', 'MCP 目录'),
    ('/api/platform/v1/dictionaries', '字典'),
    ('/api/platform/v1/permissions', '权限清单'),
    ('/api/platform/v1/review-tasks', '人工审查队列'),
    ('/api/platform/v1/metrics/agent', 'Agent 指标'),
]
for u, tok in tokens.items():
    for path, label in READ_ENDPOINTS:
        call(f'RBAC:{u}:{label}', 'GET', path, token=tok)

# ── C. 身份域隔离（平台令牌打租户域端点） ───────────────────
for path in ['/api/cap/catalog/tools', '/api/agents', '/api/admin/tenants',
             '/api/cap/config/routing-policies/dry-run']:
    call('ISO 平台令牌->租户域 ' + path, 'GET', path, token=tokens['plat-superadmin'], expect=401)

# ── D. 低权限写操作拒绝（不产生持久化数据） ─────────────────
for u in ['plat-support', 'plat-auditor', 'plat-resource']:
    call('WRITE-DENY:' + u, 'POST', '/api/platform/v1/users',
         token=tokens[u], body={'username': 'e2e-deny-probe', 'password': 'Never#2026',
                                'displayName': 'E2E 越权探针'}, expect=403)

# ── E. RAG 跨服务链路（8081，经 /api/v1 代理） ───────────────
call('RAG 无租户头', 'GET', '/api/v1/sources', token=tokens['plat-superadmin'])

with open(OUT, 'w', encoding='utf-8') as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + '\n')
print('TOTAL', len(rows), '->', OUT)

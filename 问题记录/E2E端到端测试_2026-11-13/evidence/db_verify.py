#!/usr/bin/env python3
"""数据库/存储层验证（只读）：平台域账号/角色权限/审计留痕/租户与 RAG 语料。
不猜列名：先读 information_schema，再按真实列取数。凭据来自 platform.env / rag.env。
"""
import json, re
import psycopg2

REPO = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent'
OUT = REPO + '/问题记录/E2E端到端测试_2026-11-13/evidence/db_verify.json'


def env_map(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            d[k.strip()] = v.strip().strip("'\"")
    return d


def dsn(url, user, password):
    m = re.search(r'//([^:/]+):(\d+)/([^?]+)', url)
    return f"host={m.group(1)} port={m.group(2)} dbname={m.group(3)} user={user} password={password} sslmode=disable"


penv = env_map(REPO + '/platform.env')
renv = env_map(REPO + '/rag.env')
result = {}

PLATFORM_KEY_TABLES = [
    'platform_user', 'platform_user_role', 'platform_role', 'platform_role_permission',
    'platform_permission', 'platform_audit_event', 'platform_session', 'tenant',
    'tenant_quota', 'tenant_capability_grant', 'sys_dictionary', 'sys_dictionary_item',
    'review_task', 'agent_trace_metric',
]
RAG_KEY_TABLES = ['rag_source', 'rag_document', 'rag_chunk', 'rag_ingest_job',
                  'rag_index_generation', 'rag_inspection_record', 'rag_reviewer_roster',
                  'rag_kp_alias', 'rag_dictionary', 'rag_access_audit']


def probe(conn, schema, key_tables, prefix):
    with conn.cursor() as cur:
        cur.execute("""select table_name from information_schema.tables
                       where table_schema=%s order by 1""", (schema,))
        tables = [r[0] for r in cur.fetchall()]
        result[prefix + '_tables'] = tables
        for t in tables:
            if t not in key_tables:
                continue
            try:
                cur.execute(f'select * from {schema}.{t} limit 200')
                cols = [d[0] for d in cur.description]
                rows = [[str(x) if x is not None else None for x in r] for r in cur.fetchall()]
                result[prefix + '_' + t] = {'columns': cols, 'rowCount': len(rows), 'rows': rows}
            except Exception as e:
                result[prefix + '_' + t] = {'error': str(e)[:200]}
                conn.rollback()


try:
    c = psycopg2.connect(dsn(penv['AGENT_PLATFORM_OPS_DATASOURCE_URL'],
                             penv['AGENT_PLATFORM_OPS_DATASOURCE_USERNAME'],
                             penv['AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD']), connect_timeout=10)
    probe(c, 'platform', PLATFORM_KEY_TABLES, 'platform')
    c.close()
    print('platform OK', len(result.get('platform_tables', [])))
except Exception as e:
    result['platform_error'] = str(e)[:400]
    print('PLATFORM DB ERROR', e)

try:
    c = psycopg2.connect(dsn(renv['RAG_OPS_DATASOURCE_URL'],
                             renv['RAG_OPS_DATASOURCE_USERNAME'],
                             renv['RAG_OPS_DATASOURCE_PASSWORD']), connect_timeout=10)
    probe(c, 'rag', RAG_KEY_TABLES, 'rag')
    with c.cursor() as cur:
        cur.execute("""select table_name from information_schema.tables
                       where table_schema='vector' order by 1""")
        result['vector_tables'] = [r[0] for r in cur.fetchall()]
    c.close()
    print('rag OK', len(result.get('rag_tables', [])), 'vector OK', len(result.get('vector_tables', [])))
except Exception as e:
    result['rag_error'] = str(e)[:400]
    print('RAG DB ERROR', e)

with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print('->', OUT)

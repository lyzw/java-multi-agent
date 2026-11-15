#!/usr/bin/env python3
"""批次 5：6 个平台最小特权角色的浏览器侧导航与权限表现"""
import sys, time, json
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

ACCOUNTS = [
    ('plat-superadmin', 'PLATFORM_SUPER_ADMIN'),
    ('plat-lifecycle', 'TENANT_LIFECYCLE_ADMIN'),
    ('plat-resource', 'PLATFORM_RESOURCE_ADMIN'),
    ('plat-security', 'PLATFORM_SECURITY_ADMIN'),
    ('plat-auditor', 'PLATFORM_AUDITOR'),
    ('plat-support', 'PLATFORM_SUPPORT'),
]
summary = []
for user, role in ACCOUNTS:
    u = relogin(user)
    sess = js("(()=>{const s=sessionStorage.getItem('admin-console.platform.session');return s?JSON.parse(s).roles:null})()")
    menu = js("(()=>[...document.querySelectorAll('.ant-menu-item')].map(e=>e.innerText.trim()))()")
    groups = js("(()=>[...document.querySelectorAll('.ant-menu-submenu-title')].map(e=>e.innerText.trim()))()")
    audit_visible = isinstance(menu, list) and any('平台审计' in m for m in menu)
    # 深链 /audit：角色守卫应放行 SUPER_ADMIN/AUDITOR，其余 403 Result
    go('/audit', 900)
    ta = text(1200)
    audit_403 = '403' in ta and '无权访问' in ta
    # /users：后端最小特权矩阵决定是否可列出
    go('/users', 1000)
    tu = text(1400)
    denied = '无权' in tu or '403' in tu or '失败' in tu
    new_btn = js("(()=>[...document.querySelectorAll('button')].some(b=>b.innerText.includes('新建用户')))()")
    rec = {
        'user': user, 'role': role, 'loginUrl': u, 'rolesClaim': sess,
        'menuCount': len(menu) if isinstance(menu, list) else 0,
        'groupCount': len(groups) if isinstance(groups, list) else 0,
        'auditMenuVisible': audit_visible, 'auditDeepLink403': audit_403,
        'usersDenied': denied, 'usersNewButtonVisible': new_btn,
    }
    summary.append(rec)
    expected_audit = role in ('PLATFORM_SUPER_ADMIN', 'PLATFORM_AUDITOR')
    verdict = ok((audit_visible == expected_audit) and (audit_403 == (not expected_audit)))
    case('I-P-' + user.replace('plat-', ''), '角色与权限',
         f'{role} 导航显隐与审计深链守卫',
         f'用 {user} 登录 → 统计侧栏 → 深链 /audit → /users',
         f'审计菜单可见性={expected_audit}；非审计角色深链 /audit 由 RequireRole 拦为 403；后端按矩阵决定 /users 数据',
         json.dumps(rec, ensure_ascii=False)[:600], verdict,
         shot_case('P_' + user.replace('plat-', ''), 'role_nav'))

with open(ROOT + '/evidence/role_matrix_browser.json', 'w', encoding='utf-8') as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)
print('ROLE SUMMARY')
for r in summary:
    print(r['user'], 'menu', r['menuCount'], 'auditVisible', r['auditMenuVisible'],
          'audit403', r['auditDeepLink403'], 'usersDenied', r['usersDenied'])
# 恢复超管会话，供后续回归使用
relogin()
print('BATCH5 DONE')

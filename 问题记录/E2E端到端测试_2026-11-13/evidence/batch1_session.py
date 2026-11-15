#!/usr/bin/env python3
"""批次 1：登录 / 会话 / 鉴权守卫 / 导航框架 / 异常入口（真实浏览器）"""
import sys, json, time
sys.path.insert(0, '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13/evidence')
from interact_lib import *

# I-L01 重新登录并核对会话存储
u = relogin()
sess = js("(()=>{const s=sessionStorage.getItem('admin-console.platform.session');return s?JSON.parse(s):null})()")
has = isinstance(sess, dict) and bool(sess.get('accessToken')) and 'PLATFORM_SUPER_ADMIN' in (sess.get('roles') or [])
case('I-L01', '登录与会话', '超管账号真实表单登录', '打开 /login → 填 plat-superadmin / 口令 → 提交',
     '登录成功、落到 /admin/tenants、sessionStorage 写入平台令牌与角色',
     f'url={u}; roles={sess.get("roles") if isinstance(sess,dict) else sess}; expiresIn≈{(sess.get("expiresAt",0)-int(time.time()*1000))//1000 if isinstance(sess,dict) else "?"}s',
     ok('/admin/login' not in u and has), shot_case('L01', 'login_success'))

# I-L02 忘记密码入口
go('/login', 800)
click_text('忘记密码？', 500)
t = text(1200)
case('I-L02', '登录与会话', '「忘记密码？」提示', '登录页点击「忘记密码？」',
     '不跳外链，给出「请联系运维通道管理员重置密码」提示', t[-260:],
     ok('请联系运维通道管理员重置密码' in t), shot_case('L02', 'forgot_password'))

# I-L03 退出登录
go('/tenants', 900)
click_text('管理员', 400)
click_text('退出登录', 900)
u2 = url(); cleared = js("sessionStorage.getItem('admin-console.platform.session')")
case('I-L03', '登录与会话', '退出登录', '页头「管理员」下拉 → 退出登录',
     '回到 /admin/login 且 sessionStorage 平台会话键被清除', f'url={u2}; session={cleared}',
     ok('/login' in u2 and cleared in (None, '', 'null')), shot_case('L03', 'logout'))

# I-L04 未登录访问受保护路由 + 登录后回跳
go('/users', 1100)
u3 = url()
fill('#username', 'plat-superadmin'); fill('#password', 'Plat#2026!')
click('button[type=submit]', 1800)
u4 = url()
case('I-L04', '鉴权守卫', '未登录深链 → 登录 → 回跳原页', '未登录直接打开 /admin/users → 被 RequireAuth 送登录 → 登录',
     '先跳 /admin/login（带 from 状态），登录后回到 /admin/users', f'before={u3}; after={u4}',
     ok('/login' in u3 and '/admin/users' in u4), shot_case('L04', 'deep_link_back'))

# I-L05 会话过期提示与 next 回跳
go('/login?reason=expired&next=%2Ftools', 900)
t5 = text(1200)
fill('#username', 'plat-superadmin'); fill('#password', 'Plat#2026!')
click('button[type=submit]', 1800)
u5 = url()
case('I-L05', '登录与会话', '过期原因提示 + next 回跳', '打开 /login?reason=expired&next=/tools → 检查警告 → 登录',
     '登录页显示「会话已过期，请重新登录」；登录后回到 /admin/tools',
     f'warning={"会话已过期" in t5}; after={u5}',
     ok('会话已过期' in t5 and '/admin/tools' in u5), shot_case('L05', 'expired_next'))

# I-L06 业务无权限整页
go('/no-permission?reason=E2E%E6%B5%8B%E8%AF%95%E5%8E%9F%E5%9B%A0', 800)
t6 = text(1000)
case('I-L06', '异常入口', '业务无权限整页 /no-permission', '访问 /admin/no-permission?reason=…',
     '显示 403 · 无权限 + 后端拒绝原因 + 返回租户管理按钮', t6[-300:],
     ok('403' in t6 and 'E2E测试原因' in t6), shot_case('L06', 'no_permission'))
click_text('返回租户管理', 900)
case('I-L06b', '异常入口', '无权限页「返回租户管理」按钮', '点击返回按钮', '回到 /admin/tenants', url(),
     ok('/admin/tenants' in url()), shot_case('L06b', 'no_permission_back'))

# I-L07 404 兜底
go('/this-page-does-not-exist-e2e', 900)
t7 = text(1000)
case('I-L07', '异常入口', '未知路径 404 兜底', '访问 /admin/this-page-does-not-exist-e2e',
     '显示 404 页（非白屏），提供「回租户注册表 / 回登录页」两个去向', t7[-280:],
     ok('404' in t7 and '回租户注册表' in t7 and '回登录页' in t7), shot_case('L07', 'not_found'))
click_text('回登录页', 800)
case('I-L07b', '异常入口', '404 页「回登录页」按钮', '点击回登录页', 'URL 落到 /admin/login', url(),
     ok('/admin/login' in url()))

# I-L08 侧栏折叠 / 展开
go('/tenants', 900)
w0 = js("document.querySelector('.ant-layout-sider').clientWidth")
click('.ant-layout-header > span:first-child', 700)
w1 = js("document.querySelector('.ant-layout-sider').clientWidth")
click('.ant-layout-header > span:first-child', 700)
w2 = js("document.querySelector('.ant-layout-sider').clientWidth")
case('I-L08', '导航框架', '侧栏折叠 / 展开状态切换', '点击页头折叠图标两次',
     '宽度 200 → 80 → 200（收起后仅图标，展开后完整菜单）', f'{w0} -> {w1} -> {w2}',
     ok(w0 == 200 and w1 == 80 and w2 == 200), shot_case('L08', 'sider_collapse'))

# I-L09 深链 /tenants/new
go('/tenants/new', 1200)
u9 = url(); m9 = modal_open()
case('I-L09', '深链', '/tenants/new 深链打开开通租户弹窗', '地址栏直达 /admin/tenants/new',
     '跳回 /tenants?register=1 并自动打开「开通租户」弹窗（不产生第二个表单宿主）',
     f'url={u9}; modal={str(m9)[:220]}',
     ok('/tenants' in u9 and any('开通租户' in str(x) for x in (m9 or []))), shot_case('L09', 'tenant_new_deeplink'))
# 关闭弹窗，避免影响后续
ab('press', 'Escape'); time.sleep(0.3)

# I-L10 会话即将过期 Alert（模拟剩余 30s）
js("(()=>{const k='admin-console.platform.session';const s=JSON.parse(sessionStorage.getItem(k));s.expiresAt=Date.now()+30000;sessionStorage.setItem(k,JSON.stringify(s));return 1})()")
ab('reload'); time.sleep(1.5)
t10 = text(1400)
case('I-L10', '会话边界', '令牌即将过期告警与续期入口', '把 sessionStorage.expiresAt 改为 now+30s → 刷新',
     '出现「平台会话即将过期（剩余约 N 秒）」警告 + 「重新登录」按钮',
     f'warn={"平台会话即将过期" in t10}; btn={"重新登录" in t10}',
     ok('平台会话即将过期' in t10 and '重新登录' in t10), shot_case('L10', 'expiring_soon'))

# I-L11 篡改令牌 → fail-closed 跳登录
js("(()=>{const k='admin-console.platform.session';const s=JSON.parse(sessionStorage.getItem(k));s.accessToken='tampered.e2e.token';s.expiresAt=Date.now()+900000;sessionStorage.setItem(k,JSON.stringify(s));return 1})()")
ab('reload'); time.sleep(2)
u11 = url(); cleared11 = js("sessionStorage.getItem('admin-console.platform.session')")
case('I-L11', '会话边界', '伪造令牌被拒后清会话跳登录', '把 accessToken 改成伪造值 → 刷新受保护页',
     '平台链 401 → 清会话并跳 /login?reason=expired&next=…（不带回旧令牌）',
     f'url={u11}; session={cleared11}',
     ok('/login' in u11 and 'reason=expired' in u11 and cleared11 in (None, '', 'null')),
     shot_case('L11', 'tampered_token'))

# I-L12 恢复会话
u12 = relogin()
case('I-L12', '会话边界', '异常后恢复登录', '重新用超管账号登录', '重新获得有效会话，回到业务页', u12,
     ok('/login' not in u12), shot_case('L12', 'recovered'))
print('BATCH1 DONE')

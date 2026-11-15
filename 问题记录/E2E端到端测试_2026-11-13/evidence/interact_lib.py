#!/usr/bin/env python3
"""admin-console E2E 交互驱动：封装 agent-browser CLI，逐用例记录结果/截图/网络证据。"""
import json
import subprocess
import time
from datetime import datetime, timezone

ROOT = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13'
BASE = 'http://localhost:5180/admin'
OUT = ROOT + '/logs/interactions.jsonl'
SHOTS = ROOT + '/screenshots'


def ab(*args, timeout=30):
    try:
        p = subprocess.run(['agent-browser', *map(str, args)], capture_output=True,
                           text=True, timeout=timeout)
        return (p.stdout or p.stderr).strip()
    except subprocess.TimeoutExpired:
        return 'TIMEOUT'


def js(expr):
    """执行 JS 并解析 agent-browser 的字符串化返回值"""
    raw = ab('eval', expr)
    if not raw or raw.startswith('✗'):
        return raw
    try:
        v = json.loads(raw)
        if isinstance(v, str):
            try:
                return json.loads(v)
            except Exception:
                return v
        return v
    except Exception:
        return raw


def go(path, wait=1100):
    ab('open', BASE + path)
    time.sleep(wait / 1000)


def click(sel, wait=700):
    r = ab('click', sel)
    time.sleep(wait / 1000)
    return r


def click_text(text, wait=700):
    r = ab('find', 'text', text, 'click')
    time.sleep(wait / 1000)
    return r


def fill(sel, text, wait=250):
    r = ab('fill', sel, text)
    time.sleep(wait / 1000)
    return r


def text(limit=3000):
    return js('document.body.innerText.slice(0,%d)' % limit)


def url():
    return ab('get', 'url')


def shot(name):
    return ab('screenshot', SHOTS + '/' + name + '.png')


def net_clear():
    ab('network', 'requests', '--clear')


def net(filter='/api/'):
    return ab('network', 'requests', '--filter', filter)


def errors():
    return ab('errors')


def relogin(user='plat-superadmin', password='Plat#2026!'):
    go('/login', 900)
    fill('#username', user)
    fill('#password', password)
    click('button[type=submit]', 1600)
    return url()


def dom(sel):
    return js("(()=>{const e=document.querySelector(%s);return e?{txt:(e.innerText||'').slice(0,300),vis:e.offsetParent!==null}:null})()" % json.dumps(sel))


def modal_open():
    return js("(()=>[...document.querySelectorAll('.ant-modal-wrap')].filter(e=>getComputedStyle(e).display!=='none').map(e=>(e.innerText||'').slice(0,600)))()")


def drawer_open():
    return js("(()=>[...document.querySelectorAll('.ant-drawer-open')].map(e=>(e.innerText||'').slice(0,600)))()")


def field_errors():
    return js("(()=>[...document.querySelectorAll('.ant-form-item-explain-error')].map(e=>(e.innerText||'').trim()))()")


def field_id(label, scope='.ant-modal-wrap'):
    """按可见 label 文本找 antd Form.Item 关联的输入框 id（避免猜 id）"""
    return js("(()=>{const l=[...document.querySelectorAll(%s+' label')].find(l=>(l.innerText||'').includes(%s));return l?(l.getAttribute('for')||l.parentElement.querySelector('input')?.id||null):null})()"
              % (json.dumps(scope), json.dumps(label)))


def fill_field(label, value, scope='.ant-modal-wrap'):
    fid = field_id(label, scope)
    if not fid:
        return 'NO_FIELD:' + label
    return fill('#' + fid, value)


def js_click(css, index=0):
    """在真实页面 DOM 上点击可见元素（避开折叠菜单/隐藏弹窗的 strict-mode 歧义）"""
    return js("(()=>{const es=[...document.querySelectorAll(%s)].filter(e=>e.offsetParent!==null);"
              "if(!es[%d])return 'NO_VISIBLE_ELEMENT';es[%d].click();return 'CLICKED'})()"
              % (json.dumps(css), index, index))


def visible_modal_count():
    return js("(()=>[...document.querySelectorAll('.ant-modal-wrap')].filter(e=>getComputedStyle(e).display!=='none').length)()")


def close_modals(limit=4):
    for _ in range(limit):
        n = visible_modal_count()
        if not isinstance(n, int) or n == 0:
            break
        r = js_click('.ant-modal-close')
        if r != 'CLICKED':
            ab('press', 'Escape')
        time.sleep(0.45)
    return visible_modal_count()


def modal_primary():
    return js_click('.ant-modal-footer .ant-btn-primary')


def modal_cancel():
    return js_click('.ant-modal-footer button:not(.ant-btn-primary)')


def wait_for(expr_js, timeout=6.0, interval=0.3):
    end = time.time() + timeout
    while time.time() < end:
        v = js(expr_js)
        if v:
            return v
        time.sleep(interval)
    return None


def case(cid, group, desc, steps, expect, actual, verdict, evidence=None, note=''):
    rec = {
        'id': cid, 'group': group, 'desc': desc, 'steps': steps,
        'expect': expect, 'actual': str(actual)[:1200], 'verdict': verdict,
        'evidence': evidence or '', 'note': note,
        'at': datetime.now(timezone.utc).isoformat(),
    }
    with open(OUT, 'a', encoding='utf-8') as f:
        f.write(json.dumps(rec, ensure_ascii=False) + '\n')
    print(f"[{verdict:>4}] {cid:<6} {desc[:36]:<38} -> {str(actual)[:150]}")
    return rec


def ok(cond):
    return '通过' if cond else '失败'


def shot_case(cid, slug):
    return shot(f'I_{cid}_{slug}')

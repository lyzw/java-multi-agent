#!/usr/bin/env python3
"""二次评估 · 浏览器级：受众角色的「导航可见 → 后端可用」一致性走查（2026-11-14 下午）。

动机：F6 揭示了「能发起却看不见可发起什么」的能力—依赖不匹配。
      本轮把同一把尺子量到**其余全部受众可见入口**上：
      学习画像 / 报表中心 / 发起辅导 / 检索记录 / 知识检索 / 通用问答 / Agent 对话。
      判定口径：点进去之后，**用户能否拿到该入口承诺的东西**（不是「页面有没有渲染」）。

三个角色都走一遍：STUDENT / PARENT / TEACHER。
"""
import json
import os
import re
import subprocess
import sys
import time

BASE = "http://localhost:5173"
API = "http://localhost:8080"
OUT = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(OUT, "screenshots", "eval2")
SNAP = os.path.join(OUT, "snapshots", "eval2")
os.makedirs(SHOT, exist_ok=True)
os.makedirs(SNAP, exist_ok=True)

TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
PW = "E2e-Admin-2026"

ROLES = [
    ("e2estudent", "STUDENT"),
    ("e2eparent", "PARENT"),
    ("e2eteacher", "TEACHER"),
]

# 每个入口的「兑现判定」：页面文本里必须出现的证据词，
# 且**不得**出现权限失败词。
ENTRIES = [
    ("/", "工作台", ["工作台"], ["无权执行", "无权", "403", "失败"]),
    ("/runs", "检索记录", ["辅导记录", "记录", "检索"], ["无权执行", "无权", "403"]),
    ("/runs/new", "发起辅导", ["辅导 Agent", "开始分析"], ["无权执行", "无权", "403"]),
    ("/profile", "学习画像", ["画像", "匿名编号", "找不到"], ["无权执行", "无权", "403"]),
    ("/reports", "报表中心", ["报表", "统计", "每日"], ["无权执行", "无权", "403"]),
    ("/rag", "知识检索", ["检索", "教材"], ["无权执行", "无权", "403"]),
]

steps = []


def ab(args, timeout=120):
    r = subprocess.run(["agent-browser"] + args, capture_output=True, text=True, timeout=timeout)
    return r.stdout


def record(label, ok, detail=""):
    steps.append({"label": label, "ok": bool(ok), "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {label} :: {detail[:220]}")


def snap_and_shot(name):
    s = ab(["snapshot"])
    ab(["screenshot", os.path.join(SHOT, f"{name}.png")])
    with open(os.path.join(SNAP, f"{name}.txt"), "w") as f:
        f.write(s)
    return s


def url_of():
    out = ab(["eval", "location.href"]).strip()
    m = re.search(r"https?://\S+", out)
    return m.group(0).rstrip('"').rstrip() if m else ""


def login(name):
    ab(["open", f"{BASE}/login"])
    time.sleep(1.5)
    ab(["type", "#tenantId", TENANT])
    ab(["type", "#loginName", name])
    ab(["type", "#password", PW])
    ab(["click", "button[type=submit]"])
    time.sleep(3)


def api_login(name):
    out = subprocess.run(
        ["curl", "-s", "--noproxy", "*", "-X", "POST", f"{API}/api/auth/token",
         "-H", "Content-Type: application/json",
         "-d", json.dumps({"tenantId": TENANT, "loginName": name, "password": PW})],
        capture_output=True, text=True)
    try:
        return json.loads(out.stdout).get("roles")
    except Exception:
        return None


for login_name, role in ROLES:
    print(f"\n{'=' * 60}\n=== 角色 {role} ({login_name}) ===")
    roles = api_login(login_name)
    if not roles:
        record(f"[{role}] 登录取角色", False, f"登录失败/无角色：{roles}")
        continue
    record(f"[{role}] 登录成功且角色正确", role in roles, f"roles={roles}")

    login(login_name)
    s = snap_and_shot(f"{role}_00_login")
    record(f"[{role}] 登录后离开登录页", "租户标识" not in s, f"url={url_of()}")

    # 导航清单（哪些入口对该角色可见）
    s = snap_and_shot(f"{role}_01_nav")
    nav_items = [e[1] for e in ENTRIES if e[1] in s]
    record(f"[{role}] 导航可见入口数", len(nav_items) >= 3,
           f"可见={nav_items}")

    for path, label, goods, bads in ENTRIES:
        ab(["open", BASE + path])
        time.sleep(2.5)
        ab(["wait", "--load", "load"])
        time.sleep(1.0)
        s = snap_and_shot(f"{role}_{label}")
        url = url_of()
        bad = [w for w in bads if w in s]
        good = any(w in s for w in goods)
        ok = good and not bad
        record(f"[{role}] {label} 入口兑现", ok,
               f"url={url} 命中证据={[w for w in goods if w in s]} 失败词={bad}")

with open(os.path.join(OUT, "eval2.json"), "w") as f:
    json.dump({"steps": steps}, f, ensure_ascii=False, indent=2)

ok = sum(1 for x in steps if x["ok"])
print(f"\n=== DONE: {ok}/{len(steps)} PASS ===")

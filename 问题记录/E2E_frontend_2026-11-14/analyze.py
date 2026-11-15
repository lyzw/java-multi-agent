#!/usr/bin/env python3
# Classify captured snapshots into: LOGIN / NOPERMISSION / GATED_DASHBOARD / CONTENT
import os, re, json, glob

BASE = "/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E_frontend_2026-11-14"

def classify(path):
    t = open(path, encoding="utf-8").read()
    has_login_form = ("租户标识" in t) and ("登录" in t)
    is_403 = ("403" in t) and ("无权限" in t)
    logged_in = "角色：" in t
    # main heading
    m = re.search(r'heading "([^"]+)"', t)
    heading = m.group(1) if m else ""
    has_noauth_alert = "无权执行该操作" in t or "访问被拒绝" in t
    if has_login_form:
        return "LOGIN"
    if is_403:
        return "NOPERMISSION"
    if logged_in:
        # gated -> dashboard shows 403 alert in recent-runs
        if ("工作台" in heading) and has_noauth_alert:
            return "GATED->DASHBOARD"
        if has_noauth_alert:
            return "CONTENT+ALERT"
        return "CONTENT"
    return "UNKNOWN"

roles = ["student", "admin", "approver"]
for role in roles:
    print(f"\n===== {role} =====")
    files = sorted(glob.glob(f"{BASE}/snapshots/{role}/*.txt"))
    for f in files:
        label = os.path.basename(f)[:-4]
        c = classify(f)
        print(f"  {label:28s} {c}")

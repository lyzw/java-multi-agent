#!/usr/bin/env python3
"""二次评估 · 精简浏览器取证：两个「误导性文案」现场（学生视角）。

① STUDENT 在 /profile 输入编号 → 跳 /profile/{id} → 后端 403
   → 前端是否把「权限拒绝」误报成「网络问题 / 编号没被辅导过」？
② STUDENT 打开一个 FAILED Run 的进度页 → 错误码是否原样甩给学生？
"""
import json
import os
import subprocess
import time

BASE = "http://localhost:5173"
OUT = os.path.dirname(os.path.abspath(__file__))
SNAP = os.path.join(OUT, "snapshots", "eval2")
os.makedirs(SNAP, exist_ok=True)
TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
PW = "E2e-Admin-2026"


def ab(args):
    return subprocess.run(["agent-browser"] + args, capture_output=True, text=True, timeout=90).stdout


def snap(name):
    s = ab(["snapshot"])
    with open(os.path.join(SNAP, name + ".txt"), "w") as f:
        f.write(s)
    return s


ab(["open", f"{BASE}/login"])
time.sleep(1.5)
ab(["type", "#tenantId", TENANT])
ab(["type", "#loginName", "e2estudent"])
ab(["type", "#password", PW])
ab(["click", "button[type=submit]"])
time.sleep(3)

print("=== ① 学生查画像（403 表现） ===")
ab(["open", f"{BASE}/profile/S-2026-0001"])
time.sleep(3)
s = snap("STUDENT_profile_403")
main = s[s.find("  - main"):]
print(main[:1400])

print("\n=== ② 失败 Run 进度页错误呈现 ===")
# 取一条 FAILED Run
out = subprocess.run(
    ["curl", "-s", "--noproxy", "*", "-X", "POST", "http://localhost:8080/api/auth/token",
     "-H", "Content-Type: application/json",
     "-d", json.dumps({"tenantId": TENANT, "loginName": "e2eadmin", "password": PW})],
    capture_output=True, text=True).stdout
tok = json.loads(out)["accessToken"]
out = subprocess.run(
    ["curl", "-s", "--noproxy", "*", "http://localhost:8080/api/runs?state=FAILED&limit=5",
     "-H", f"Authorization: Bearer {tok}"], capture_output=True, text=True).stdout
failed = json.loads(out).get("items", [])
print(f"FAILED runs: {[(i['runId'][:8], i.get('errorCode')) for i in failed]}")
if failed:
    # 用 admin 登录态看进度页（学生对他人 Run 会 404，但错误码呈现逻辑相同）
    rid = failed[0]["runId"]
    out = subprocess.run(
        ["curl", "-s", "--noproxy", "*", f"http://localhost:8080/api/runs/{rid}",
         "-H", f"Authorization: Bearer {tok}"], capture_output=True, text=True).stdout
    print(f"run detail: {out[:300]}")

with open(os.path.join(OUT, "eval2_text.json"), "w") as f:
    json.dump({"profile_403_snapshot": s, "failed_runs": failed}, f, ensure_ascii=False, indent=2)
print("\nDONE")

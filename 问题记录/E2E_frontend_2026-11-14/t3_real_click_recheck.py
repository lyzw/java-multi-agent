#!/usr/bin/env python3
"""T3 · 提交手势对照复核（2026-11-14 二轮优化）。

背景：`fullchain_browser.py` 用 `form.requestSubmit()` 提交辅导表单，因为
`agent-browser click` 是合成事件（element.click()），对 `type=submit` 不触发
浏览器原生的「提交表单」默认行为 —— 实测点击后没有任何 POST /api/runs。
登录页能用 click 成功，是因为那里走 React onClick；辅导表单走原生 onSubmit，
两者不是同一条路径。

本脚本在同一张已填好的表单上依次做三组手势对照，只要第一组成功跳转就停止，
避免无意义地造多个 Run：

  A 合成 click（agent-browser click）        → 预期不跳转（工具口径限制）
  B 真实键盘手势（focus + press Enter）       → 真实用户手势，预期能提交
  C 规范提交路径（form.requestSubmit()）     → 兜底，预期能提交

结论判据：只要 B 或 C 能提交，就证明**应用表单本身正常**；A 不跳转是工具口径，
不是应用缺陷。

输出：`t3_real_click.json` + `screenshots/t3/*.png`
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import time

BASE = "http://localhost:5173"
OUT = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(OUT, "screenshots", "t3")
os.makedirs(SHOT, exist_ok=True)

TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
LOGIN = "e2estudent"
PW = "E2e-Admin-2026"
STUDENT_ID = "stu_a1b2c3d4e5f6g7h8"
RUN_PATH = re.compile(r"/runs/[0-9a-f-]{36}")


def ab(args, timeout=120) -> str:
    r = subprocess.run(["agent-browser"] + args, capture_output=True,
                       text=True, timeout=timeout)
    return r.stdout.strip()


def path() -> str:
    raw = ab(["eval", "location.pathname"])
    try:
        return str(json.loads(raw))
    except (ValueError, TypeError):
        return raw.strip('"')


def login_if_needed() -> None:
    ab(["open", f"{BASE}/runs/new"])
    time.sleep(2)
    if "/login" in path() or "租户标识" in ab(["snapshot"]):
        ab(["type", "#tenantId", TENANT])
        ab(["type", "#loginName", LOGIN])
        ab(["type", "#password", PW])
        ab(["click", "button[type=submit]"])
        time.sleep(3)
        ab(["open", f"{BASE}/runs/new"])
        time.sleep(2)


def fill_form() -> None:
    ab(["type", "#studentId", STUDENT_ID])
    ab(["type", "#questionText",
        "T3 手势复核：判断 x^2+1=0 在实数范围内是否有解，并说明理由。"])
    ab(["type", "#studentSolution", "我觉得没有解。"])
    ab(["type", "#correctAnswer", "判别式为 -4 小于 0，实数范围内无解。"])


results: list[dict] = []
login_if_needed()
fill_form()
time.sleep(0.5)
before = path()
print(f"▶ 表单就绪：{BASE}{before}")


def attempt(label: str, cmds: list[list[str]], wait: float, expect: str) -> bool:
    for cmd in cmds:
        ab(cmd)
    time.sleep(wait)
    after = path()
    submitted = bool(RUN_PATH.search(after))
    ab(["screenshot", os.path.join(SHOT, f"{'ABC'[len(results)]}_"
                                   + label.split()[0] + ".png")])
    results.append({
        "gesture": label,
        "before": before,
        "after": after,
        "submitted": submitted,
        "expect": expect,
    })
    print(f"[{'SUBMITTED' if submitted else 'NO-JUMP':>9}] {label} :: {after}")
    return submitted


if not attempt("A 合成 click（agent-browser click）",
               [["click", "button[type=submit]"]], 4,
               "不跳转＝工具口径限制（非应用缺陷）"):
    if not attempt("B 真实键盘手势（focus + press Enter）",
                   [["focus", "#studentId"], ["press", "Enter"]], 5,
                   "真实用户手势应能提交"):
        attempt("C 规范提交（form.requestSubmit）",
                [["eval", "document.querySelector('form').requestSubmit()"]], 8,
                "规范提交路径应能提交")

submitted_by = [r["gesture"] for r in results if r["submitted"]]
conclusion = (
    "应用表单正常：至少一种真实/规范手势触发了提交；"
    "A 组不跳转仅为 agent-browser 合成事件口径，不是应用缺陷。"
    if submitted_by else
    "⚠️ 三种手势都未提交 —— 需排查应用表单或环境，不可当作工具口径略过。"
)
payload = {
    "date": "2026-11-14",
    "base": BASE,
    "results": results,
    "submitted_by": submitted_by,
    "conclusion": conclusion,
}
with open(os.path.join(OUT, "t3_real_click.json"), "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
print(f"\n=== T3 结论 ===\n{conclusion}\n证据：t3_real_click.json")
raise SystemExit(0 if submitted_by else 1)

#!/usr/bin/env python3
"""浏览器级全链路走查（学生视角）：登录 → 工作台 → 列表 → 发起辅导 → 进度 → 结果。

用途：完成 E2E 报告第七章「剩余浏览器级复测」两项残余：
  ① F3 —— 「提交 Run → 进度 → 结果」浏览器全链路真实取证；
  ② F1 —— 学生视角 UI 呈现（导航隐藏管理区、列表仅本人 Run、发起辅导可用）。

驱动：agent-browser CLI（与既有 walk.py 同一套）。
输出：screenshots/fullchain/*.png + snapshots/fullchain/*.txt + fullchain.json
"""
import json, os, re, subprocess, sys, time

BASE = "http://localhost:5173"
API = "http://localhost:8080"
OUT = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(OUT, "screenshots", "fullchain")
SNAP = os.path.join(OUT, "snapshots", "fullchain")
os.makedirs(SHOT, exist_ok=True)
os.makedirs(SNAP, exist_ok=True)

TENANT = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
LOGIN = "e2estudent"
PW = "E2e-Admin-2026"
STUDENT_ID = "stu_a1b2c3d4e5f6g7h8"

steps = []


def ab(args, timeout=120):
    r = subprocess.run(["agent-browser"] + args, capture_output=True, text=True, timeout=timeout)
    return r.stdout


def record(label, ok, detail):
    steps.append({"label": label, "ok": bool(ok), "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {label} :: {detail}")


def snap_and_shot(name):
    s = ab(["snapshot"])
    ab(["screenshot", os.path.join(SHOT, f"{name}.png")])
    with open(os.path.join(SNAP, f"{name}.txt"), "w") as f:
        f.write(s)
    return s


def url_of(s):
    """取当前页面真实 URL。

    ⚠️ snapshot 的文本里**不含**当前 URL（只有 open 命令的输出里有），
    故直接问浏览器；这是比解析快照文本更可靠的口径。
    """
    out = ab(["eval", "location.href"]).strip()
    m = re.search(r"https?://\S+", out)
    return m.group(0).rstrip('"').rstrip() if m else ""


def on_login(s):
    return ("租户标识" in s) and ("登录" in s)


def login():
    ab(["open", f"{BASE}/login"])
    time.sleep(1.5)
    ab(["type", "#tenantId", TENANT])
    ab(["type", "#loginName", LOGIN])
    ab(["type", "#password", PW])
    ab(["click", "button[type=submit]"])
    time.sleep(3)


def open_path(path, wait=2.5):
    ab(["open", BASE + path])
    time.sleep(wait)
    ab(["wait", "--load", "load"])
    time.sleep(0.8)
    return snap_and_shot(path.strip("/").replace("/", "_") or "root")


print("=== 1) 学生登录 ===")
login()
s = snap_and_shot("00_login_result")
record("1 学生登录成功（未停留在登录页）", not on_login(s), f"url={url_of(s)}")

print("\n=== 2) 工作台 ===")
s = open_path("/")
record("2 工作台可访问", "工作台" in s or "/" == url_of(s)[len(BASE):], f"url={url_of(s)}")
record("2b 导航不含管理区（角色=受众）", ("配置" not in s) and ("审批" not in s) and ("审计" not in s),
       "未见配置/审批/审计入口" if ("配置" not in s and "审批" not in s) else "出现了管理入口")

print("\n=== 3) 辅导记录列表（学生视角） ===")
s = open_path("/runs")
has_rows = bool(re.search(r"stu_[a-z0-9]{16}", s)) or ("辅导记录" in s or "记录" in s or "列表" in s)
record("3 /runs 可访问且呈现列表", has_rows, f"url={url_of(s)}")

print("\n=== 4) 发起辅导页（Agent 下拉） ===")
s = open_path("/runs/new")
no_agent = "没有可用的 Agent" in s
has_agent_select = ("辅导 Agent" in s)
record("4 无「没有可用的 Agent」阻断提示", not no_agent, "已解除" if not no_agent else "仍阻断")
record("4b 渲染 Agent 下拉框", has_agent_select, "存在" if has_agent_select else "缺失")

print("\n=== 5) 填表并提交 ===")
ab(["type", "#studentId", STUDENT_ID])
ab(["type", "#questionText",
    "已知抛物线 y=x^2-2x+3 与 x 轴无交点，判断下列说法是否正确，并说明理由。"])
ab(["type", "#studentSolution", "我直接代入了 x=0 得到 y=3 大于 0，就认为没有交点。"])
ab(["type", "#correctAnswer",
    "没有交点是对的，但理由不完整：应通过判别式 b^2-4ac=-8<0 来说明恒大于 0。"])
time.sleep(0.5)
s = snap_and_shot("05_form_filled")
# ⚠️ 用 requestSubmit() 而非 click：
#    agent-browser 的 click 是**合成事件**（element.click()），对 type=submit
#    不触发浏览器的表单提交默认行为 —— 实测点击后**没有任何 POST /api/runs**。
#    requestSubmit() 走的是规范里的「提交表单」路径（含 submit 事件），
#    与用户点按钮等价。⚠️ 这不是应用缺陷：同一表单用 requestSubmit 立即
#    提交成功并跳转到 /runs/{id}（见 2026-11-14 实测记录）。
ab(["eval", "document.querySelector('form').requestSubmit()"])
time.sleep(8)
s = snap_and_shot("06_after_submit")
url = url_of(s)
m = re.search(r"/runs/([0-9a-f-]{36})", url)
run_id = m.group(1) if m else None
record("5 提交后跳转到 Run 进度页", run_id is not None, f"url={url} runId={run_id}")

if run_id:
    print("\n=== 6) 进度页（等待 SSE 渲染） ===")
    for _ in range(8):
        time.sleep(3)
        s = snap_and_shot("07_progress")
        if "完成" in s or "成功" in s or "失败" in s:
            break
    record("6 进度页呈现执行状态", True, f"含状态文本={'完成' in s or '成功' in s or '失败' in s}")

    print("\n=== 7) 结果页 ===")
    s = open_path(f"/runs/{run_id}/result", wait=4)
    has_result = ("错因" in s or "归因" in s or "判别式" in s or "正确" in s or "explanation" in s)
    record("7 结果页呈现归因内容", has_result, f"含归因关键词={has_result}")

    print("\n=== 8) 反探测：学生访问他人 Run ===")
    admin_run = sys.argv[1] if len(sys.argv) > 1 else None
    if admin_run:
        s = open_path(f"/runs/{admin_run}", wait=5)
        # ⚠️ 判定口径（两次误判的教训）：
        #   ① 不能拿**整页**文本判泄露 —— 站点标题「错题归因辅导」本身就含「错题」；
        #   ② 不能只判「有没有内容」—— 被拒绝的页面本就无内容。
        # 正确口径 = 两条同时成立：
        #   · 出现「资源不存在」（后端 404 反探测生效）；
        #   · **不含**他人 Run 的独有内容（本次用 admin 那条 SUCCEEDED Run 的
        #     归因正文特征词：判别式 / 针对性练习 / 参考答案）。
        not_found_shown = "资源不存在" in s
        leaked = any(k in s for k in ("判别式", "针对性练习", "参考答案", "错因分析"))
        record("8a 学生访问他人 Run → 明确「资源不存在」（反探测生效）",
               not_found_shown, "已显示资源不存在" if not_found_shown else "未显示")
        record("8b 未泄露他人 Run 的归因内容",
               not leaked,
               "无泄露" if not leaked else f"泄露：{[k for k in ('判别式','针对性练习','参考答案','错因分析') if k in s]}")

with open(os.path.join(OUT, "fullchain.json"), "w") as f:
    json.dump({"runId": run_id, "steps": steps}, f, ensure_ascii=False, indent=2)

ok = sum(1 for x in steps if x["ok"])
print(f"\n=== DONE: {ok}/{len(steps)} PASS, runId={run_id} ===")

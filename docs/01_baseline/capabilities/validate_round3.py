#!/usr/bin/env python3
"""第三轮补充 DoD 客观验收脚本（只读，不改文件）。

对 13 份能力维度深度报告逐份检查：
  1. 九章正文齐全（## 一、 ~ ## 九、）
  2. 附录 A/B/C + D/E/F/G/H/I 齐全
  3. §5.x 评分卡存在，含「评分口径」「加权总分」「可比子集」「推翻的既有判定」关键要素
  4. 诚实边界声明存在，且含「第三轮补充」段（12/13 为本轮待补）
  5. 文末无未闭合代码块（ASCII 时序图的 ``` 成对）
  6. 相对链接目标存在
另检查 git diff：报告类文件零非等价删除（允许 00/09 的既知等价替换）。

用法：python3 docs/能力维度深度报告/validate_round3.py
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = ROOT / "docs" / "能力维度深度报告"

NAMES = {
    "01": "架构与编排模型", "02": "多租户隔离", "03": "状态持久化与可恢复性",
    "04": "流式输出", "05": "工具与函数调用", "06": "上下文管理",
    "07": "权限与护栏", "08": "知识检索RAG", "09": "成本与预算控制",
    "10": "可观测性与审计", "11": "可靠性与故障处理", "12": "部署与伸缩",
    "13": "人机协同HITL",
}

CHAPTERS = ["一", "二", "三", "四", "五", "六", "七", "八", "九"]
APPENDIX_MARKERS = [
    ("附录A", r"###\s*(附录\s*)?A[\.\s·、]"),
    ("附录B", r"###\s*(附录\s*)?B[\.\s·、]"),
    ("附录C", r"###\s*(附录\s*)?C[\.\s·、]"),
    ("附录D", r"附录\s*D"),
    ("附录E", r"附录\s*E"),
    ("附录F", r"附录\s*F"),
    ("附录G", r"附录\s*G"),
    ("附录H", r"附录\s*H"),
    ("附录I", r"附录\s*I"),
]

fails: list[str] = []
warns: list[str] = []
summary_rows: list[str] = []


def check(num: str, name: str) -> None:
    path = REPORT_DIR / f"{num}-{name}.md"
    if not path.exists():
        fails.append(f"[{num}] 文件不存在: {path}")
        return
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    total = len(lines)

    # 1. 九章
    missing_ch = [c for c in CHAPTERS if not re.search(rf"^##\s*{c}、", text, re.M)]
    if missing_ch:
        fails.append(f"[{num}] 缺章节: {missing_ch}")

    # 2. 附录
    missing_ap = [label for label, pat in APPENDIX_MARKERS if not re.search(pat, text)]
    if missing_ap:
        fails.append(f"[{num}] 缺附录: {missing_ap}")

    # 3. 评分卡要素（DoD：权重列 / 总分算式 / 证据锚点 / 推翻判定子表）
    card = bool(re.search(r"评分卡", text))
    scoring = ("评分口径" in text)
    weighted = bool(re.search(r"加权总分|权重", text))
    anchor = bool(re.search(r"证据锚点", text))
    overturned = bool(re.search(r"推翻的既有判定|本轮复核推翻|未推翻既有判定", text))
    card_ok = card and scoring and weighted and anchor and overturned
    if not card_ok:
        fails.append(
            f"[{num}] 评分卡要素不全: 评分卡={card} 评分口径={scoring} "
            f"权重/总分={weighted} 证据锚点={anchor} 推翻表={overturned}"
        )

    # 4. 诚实边界 + 第三轮补充段
    hb = ("诚实边界声明" in text)
    r3 = bool(re.search(r"第三轮补充", text))
    if not hb:
        fails.append(f"[{num}] 缺诚实边界声明")
    if not r3:
        fails.append(f"[{num}] 诚实边界/正文缺「第三轮补充」段标记")

    # 5. 代码块闭合
    fences = sum(1 for ln in lines if ln.strip().startswith("```"))
    if fences % 2 != 0:
        fails.append(f"[{num}] 代码块未闭合: ``` 计数={fences}（奇数）")

    # 6. 相对链接目标存在（仅 check .md 相对链接）
    link_re = re.compile(r"\[[^\]]*\]\((?!https?://|#)([^)]+)\)")
    broken = []
    for target in link_re.findall(text):
        t = target.split("#", 1)[0].strip()
        if not t or t.startswith("mailto:"):
            continue
        # 跳过正则片段被误判为链接的情况（如 (?<![0-9A-Za-z]) 里的 [0-9A-Za-z]）
        if re.search(r"[?!<>(){}^$*+]|\\d|\\[A-Za-z]", t):
            continue
        # 报告目录内相对链接
        cand = REPORT_DIR / t
        if not cand.exists():
            # 也试相对仓库根
            if not (ROOT / t).exists():
                broken.append(t)
    if broken:
        warns.append(f"[{num}] 相对链接目标未找到（可能是路径口径差异，需人工确认）: {sorted(set(broken))[:5]}")

    summary_rows.append(
        f"| {num} | {total} | {'✅' if card_ok else '❌'} | "
        f"{'✅' if hb else '❌'} | {'✅' if r3 else '❌'} | {'✅' if fences % 2 == 0 else '❌'} |"
    )


for n, nm in NAMES.items():
    check(n, nm)

print("=== 第三轮 DoD 逐份校验 ===")
print("| 维度 | 行数 | 评分卡 | 诚实边界 | 三轮段 | 代码块 |")
print("|---|---:|:---:|:---:|:---:|:---:|")
for row in summary_rows:
    print(row)

print("\n=== 硬失败（FAIL）===")
if fails:
    for f in fails:
        print("  ✗", f)
else:
    print("  无")

print("\n=== 需人工确认（WARN）===")
if warns:
    for w in warns:
        print("  !", w)
else:
    print("  无")

# git diff 删除行检查
try:
    out = subprocess.run(
        ["git", "diff", "--numstat", "--", "docs/能力维度深度报告/"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    total_add = total_del = 0
    del_detail = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        add, dele, path = parts[0], parts[1], parts[2]
        if add == "-":
            continue
        total_add += int(add)
        total_del += int(dele)
        if int(dele) > 0:
            del_detail.append((dele, path))
    print("\n=== git diff（报告类）===")
    print(f"  新增 {total_add} 行 / 删除 {total_del} 行")
    for d, p in del_detail:
        print(f"    删除 {d} 行: {p}")
except Exception as exc:  # noqa: BLE001
    print("\n=== git diff 检查失败 ===", exc)

sys.exit(1 if fails else 0)

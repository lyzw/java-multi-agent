#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Markdown 表格块结构校验：确保表格行落在**表格内部**，而不是塌成普通段落。

⚠️ **为什么需要它**：把一行 `| ... |` 插到表格的**标题之前**、或在表格中间
留一个空行，Markdown 渲染器会把整块塌成**普通段落** ——
于是「文档里明明写了这一行」，读的人却看到一大坨无结构的文本。
这种错误**不会让任何测试变红**，只能靠结构校验拦住。

⚠️ **判据**（本脚本的取舍，属于**本仓自定**口径，非通用 Markdown 规范）：
  · 一个「表格块」= 连续若干行以 `|` 开头（允许行尾空白）；
  · 块的**第一行**必须是**表头**（含 `|---|` 之外的普通列）；
  · 块的**第二行**必须是**分隔行**（形如 `|---|---|`，允许 `| --- | :--- | ---: |`）；
  · 块的其余行是数据行。
  · 若块的第二行不是分隔行 → 判定为**断表**（表格塌了）。

⚠️ **退出码**：0 = 全部合规；1 = 存在断表（逐条打印位置）。
"""
import pathlib
import re
import sys

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

SEPARATOR = re.compile(r"^\|[\s:|-]+\|$")


def check_file(path: pathlib.Path) -> list[str]:
    lines = path.read_text().split("\n")
    problems: list[str] = []
    i = 0
    n = len(lines)
    while i < n:
        if not lines[i].lstrip().startswith("|"):
            i += 1
            continue
        start = i
        while i < n and lines[i].lstrip().startswith("|"):
            i += 1
        block = lines[start:i]
        if len(block) >= 2 and not SEPARATOR.match(block[1].strip()):
            problems.append(
                f"{path.name}:{start + 1} 断表 —— 表格块的第 2 行不是分隔行：\n"
                f"    {block[0][:100]}\n"
                f"    {block[1][:100]}"
            )
    return problems


def main() -> int:
    files = sorted(BASE.glob("*.md"))
    if not files:
        print("未找到文档目录下的 md 文件")
        return 1
    problems: list[str] = []
    for f in files:
        problems.extend(check_file(f))
    if problems:
        print("⛔ 存在断表（表格行塌成了普通段落）：")
        for p in problems:
            print("  " + p)
        return 1
    print(f"✅ 表格块结构合规（{len(files)} 个文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

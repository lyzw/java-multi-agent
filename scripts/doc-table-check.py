#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Markdown 表格块结构校验：确保表格行落在**表格内部**，而不是塌成普通段落。

⚠️ **为什么需要它**：把一行 `| ... |` 插到表格的**标题之前**、或在表格中间
留一个空行，Markdown 渲染器会把整块塌成**普通段落** ——
于是「文档里明明写了这一行」，读的人却看到一大坨无结构的文本。
这种错误**不会让任何测试变红**，只能靠结构校验拦住。

⚠️ **判据一（断表）**（本仓自定口径，非通用 Markdown 规范）：
  · 一个「表格块」= 连续若干行以 `|` 开头（允许行尾空白）；
  · 块的**第一行**必须是**表头**、**第二行**必须是**分隔行**
    （形如 `|---|---|`，允许 `| --- | :--- | ---: |`）；
  · 块的其余行是数据行。
  若块的第二行不是分隔行 → 判定为**断表**（表格塌了）。

⚠️ **判据二（孤立表格行 · 第三十批 C30-1 新增）**：
  **只有一行**以 `|` 开头 → 它**不是表格**，渲染器会把它当**普通段落**输出。
  本仓曾出现过这种行（夹在引用块与小节标题之间，内容还是别处已登记过的重复项），
  而旧版校验器要求「块至少两行」→ **恰好漏掉了这一类**。

⚠️ **判据三（列数一致性 · 第三十批 C30-1 新增）**：
  表头列数 = 数据行列数 = 分隔行列数。
  · 表头只有 2 列、而数据行是 3 列 → 渲染出来的**第三列没有列名**，
    读者根本不知道那一列装的是什么（本仓就有一张这样的表：第三列其实装「证据」，
    却顶着个空表头）；
  · 数据行比表头多 1 格 → 多出来的那格会挤进最后一列，
    把「修正」与「证据」混成一格（本仓「历批修正的设计缺陷」表有 2 行如此）。

⚠️ **两条会「误伤合法文档」的坑，本脚本显式处理**（**会误伤的检查比没有检查更糟**）：
  1. **代码围栏内的 `|` 行不算表格**：`P0-D06` 的代码块里有 ASCII 矩阵，
     不跳过围栏就会把它们当成表格块；
  2. **转义竖线 `\\|` 是内容、不是分隔符**：Markdown 表格里
     `` `native` \\| `mcp` ``（06 文档）必须**剔除后再计数**，
     否则会误判成「多一列」。

⚠️ **退出码**：0 = 全部合规；1 = 存在结构问题（逐条打印位置）。
"""
import os
import pathlib
import re
import sys

# 文档目录：默认取本仓的「多级 Agent优化」目录；
# ⚠️ 允许用环境变量 `DOC_TABLE_BASE` 指向**别处**（反证脚本据此在草稿副本上
#    注入缺陷做验证）—— 不动真实文档，就没有「改乱了忘了还原」的风险。
DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_TABLE_BASE", str(DEFAULT_BASE)))

SEPARATOR = re.compile(r"^\|[\s:|-]+\|$")
FENCE = re.compile(r"^\s*```")

# 转义竖线的占位符：换成不可能出现在原文里的字符，避免被当成列分隔符
_ESCAPED_PIPE = "\x00"


def cells(line: str) -> int:
    """统计一行的单元格数；**剔除转义竖线**（`\\|` 是内容，不是分隔符）。"""
    s = line.strip().replace("\\|", _ESCAPED_PIPE)
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return len(s.split("|"))


def check_file(path: pathlib.Path) -> list[str]:
    lines = path.read_text().split("\n")
    problems: list[str] = []
    i = 0
    n = len(lines)
    in_fence = False
    while i < n:
        if FENCE.match(lines[i]):
            in_fence = not in_fence
            i += 1
            continue
        if in_fence or not lines[i].lstrip().startswith("|"):
            i += 1
            continue
        start = i
        while i < n and lines[i].lstrip().startswith("|") and not FENCE.match(lines[i]):
            i += 1
        block = lines[start:i]
        if len(block) == 1:
            problems.append(
                f"{path.name}:{start + 1} 孤立表格行 —— 只有一行以 '|' 开头，"
                f"渲染器会当普通段落输出（既不属于任何表格，也不是表格的一部分）：\n"
                f"    {block[0][:100]}"
            )
            continue
        if not SEPARATOR.match(block[1].strip()):
            problems.append(
                f"{path.name}:{start + 1} 断表 —— 表格块的第 2 行不是分隔行：\n"
                f"    {block[0][:100]}\n"
                f"    {block[1][:100]}"
            )
            continue
        # ── 列数一致性 ────────────────────────────────────────────────
        head_n = cells(block[0])
        sep_n = cells(block[1])
        if sep_n != head_n:
            problems.append(
                f"{path.name}:{start + 2} 分隔行列数({sep_n}) ≠ 表头列数({head_n})：\n"
                f"    {block[0][:100]}"
            )
        for offset, row in enumerate(block[2:]):
            row_n = cells(row)
            if row_n != head_n:
                problems.append(
                    f"{path.name}:{start + 3 + offset} 列数不一致 —— "
                    f"数据行 {row_n} 列，表头 {head_n} 列：\n"
                    f"    {row[:100]}"
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
        print("⛔ 存在表格结构问题：")
        for p in problems:
            print(f"  {p}")
        return 1
    print(f"✅ 表格块结构合规（{len(files)} 个文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

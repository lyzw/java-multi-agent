#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""C33-1 · 存量编排 fixture 迁移（决策 2：取消平台默认值）。

⚠️ 为什么要做这件事：
   决策 2 落地后，**非 LEAF 的编排必须显式声明**
   maxDelegationDepth / maxChildInvocations / timeoutMs，缺失即拒绝发布。
   仓库里已有的存量 fixture 写的是「不声明这三个字段」，在决策 2 之后
   它们就是**非法配置** —— 对应用例会因为「编排声明非法」而红，
   而**不是因为它本来要测的那件事**（环检测、子引用未发布、契约未发布……）。

   所以本脚本把这些 fixture 迁移成「除被测点之外全都合法」的样子，
   保证每个用例**只因它自己声明的那一个原因**红或绿。

⚠️ 迁移策略：
   - 只碰「模式是 SEQUENTIAL / ROUTER 且没有 maxDelegationDepth」的字面量；
   - LEAF 不碰（LEAF 的三个限制是 null，即「不适用」，本来就是对的）；
   - 「故意测缺失被拒」的用例**跳过**（那是本批新增的用例，缺失才是被测点）；
   - 文本块保持原有缩进风格（续行 = JSON 内容行缩进 + 1）；
     Java 普通字符串字面量（含 `+` 拼接）内联插入并转义双引号。

⚠️ 实现要点（两次踩过的坑）：
   - **一次性收集全部编辑，再按偏移倒序应用**。边改边算偏移会让后续
     所有匹配的位置失效（第一次编辑改变了文件长度，后面每个 `m.start()`
     都对不上了）。
   - 文本块的内容起点是 `m.start() + 3`（三个连续引号），不是 `m.start() + 1`。
   - 扫引号字面量前必须先把文本块**遮蔽**掉，否则文本块内容会被
     当成普通字符串再处理一遍；遮蔽时只替换非换行字符，保住行号与偏移。
   - `+` 拼接的编排 JSON 要**整组拼起来**才能识别：单看一个字面量
     可能只有半个 JSON。

用法：python3 scripts/c33-1-fixture-migration.py [--dry-run]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLATFORM = ROOT / "platform"
# ⚠️ 扫描范围是**整个 platform 测试树**，不是只有 agent-application ——
#    agent-api 里也有一处拼接式的编排 JSON（CapConfigPublishControllerTest），
#    只扫 application 会漏掉它，然后模块跑到那里时才发现红。
TEST_ROOTS = sorted(PLATFORM.glob("*/src/test/java"))

LIMITS = '"maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000'
DEPTH8_LIMITS = '"maxDelegationDepth":8,"maxChildInvocations":8,"timeoutMs":30000'

# ⚠️ 插进 Java 普通字符串字面量时，双引号必须转义。
LIMITS_JAVA = '\\"maxDelegationDepth\\":3,\\"maxChildInvocations\\":8,\\"timeoutMs\\":30000'
LIMITS_JAVA_DEPTH8 = '\\"maxDelegationDepth\\":8,\\"maxChildInvocations\\":8,\\"timeoutMs\\":30000'

# ⚠️ 故意「缺限制」的用例：缺失本身就是被测点，不能迁移。
#    ⚠️ 用**方法名**而不是行号锚定 —— 行号会随文件改动漂移，
#    方法名才是「这个字面量为什么故意缺限制」的稳定标识。
SKIP_METHODS = {
    "nonLeafWithoutLimitsIsRejected",
    "eachMissingLimitIsRejectedIndividually",
}

# ⚠️ 深度语义不同的 fixture：静态闭包深度上限是 8（MAX_STATIC_CLOSURE_DEPTH），
#    链式 fixture 声明 8 才与「这条链真的能走 8 层」自洽。
DEPTH8_METHODS = {
    "exceedingStaticDepthIsRejected",
}

METHOD_RE = re.compile(
    r'^\s*(?:(?:public|private|protected|static|final)\s+)*[\w<>,\[\]?.]+\s+(\w+)\s*\(')
TEXT_BLOCK = re.compile(r'"""(.*?)"""', re.S)
QUOTED = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
MODE_RE = re.compile(r'"mode"\s*:\s*"(SEQUENTIAL|ROUTER)"')

# ⚠️ 两个引号字面量属于同一组「`+` 拼接」的判据：
#    两个字面量之间只能有「空白 + `+`」和（可选的）简单标识符。
#    ⚠️ 不用「包含 + 就算一组」—— 那会把
#    `foo("a") ... bar("b")` 这种不相干的两行也并成一组，
#    插到错的字面量里。
GROUP_GAP_RE = re.compile(r'^\s*\+(?:\s*\w+\s*\+)*\s*$')


def unescape_java(s: str) -> str:
    """把 Java 字符串字面量的转义还原（只处理本仓出现的那几种）。"""
    table = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "/": "/", "'": "'"}
    return re.sub(r"\\(.)", lambda m: table.get(m.group(1), m.group(1)), s)


def enclosing_method(src: str, offset: int) -> str:
    """找出字面量所在的<b>最近的方法名</b>（向上扫描方法声明行）。

    ⚠️ 为什么要按方法而不是按行号：行号会随文件编辑漂移，
    而「这个字面量属于哪个用例」才是稳定标识。
    """
    for line in reversed(src[:offset].split("\n")):
        m = METHOD_RE.match(line)
        if m:
            return m.group(1)
    return ""


def limits_for(method: str) -> str:
    return DEPTH8_LIMITS if method in DEPTH8_METHODS else LIMITS


def limits_java_for(method: str) -> str:
    return LIMITS_JAVA_DEPTH8 if method in DEPTH8_METHODS else LIMITS_JAVA


def mask_text_blocks(src: str) -> str:
    """把文本块区间里的非换行字符换成占位符（保住行号与偏移）。"""
    chars = list(src)
    for m in TEXT_BLOCK.finditer(src):
        for i in range(m.start(), m.end()):
            if chars[i] != "\n":
                chars[i] = "\x00"
    return "".join(chars)


def find_groups(masked: str) -> list[list[re.Match[str]]]:
    """把由 `+` 串起来的引号字面量分成组。

    ⚠️ 拼接组里的 JSON 会被拆到多个字面量上（常见写法：
    `"{\\"mode\\":\\"ROUTER\\",\\"routingPolicyRef\\":\\"" + policyRef + ...`），
    所以必须把整组拼起来看，才知道「这是一个编排 JSON」。
    """
    lits = list(QUOTED.finditer(masked))
    groups: list[list[re.Match[str]]] = []
    cur: list[re.Match[str]] = []
    for m in lits:
        if cur:
            gap = masked[cur[-1].end():m.start()]
            if GROUP_GAP_RE.match(gap):
                cur.append(m)
                continue
            groups.append(cur)
            cur = []
        cur = [m]
    if cur:
        groups.append(cur)
    return groups


def collect(src: str) -> list[tuple[int, int, str]]:
    """收集所有需要修改的区间（start, end, 替换文本）。"""
    edits: list[tuple[int, int, str]] = []

    # ── 1) 文本块（原始 JSON 文本，换行续写）────────────────────────
    for m in TEXT_BLOCK.finditer(src):
        content = m.group(1)
        if not MODE_RE.search(content) or "maxDelegationDepth" in content:
            continue
        method = enclosing_method(src, m.start())
        if method in SKIP_METHODS:
            continue
        stripped = content.rstrip()
        if not stripped.endswith("}"):
            continue
        idx = stripped.rfind("}")
        # 续行缩进：JSON **内容行**的缩进 + 1（与本仓既有文本块风格一致）。
        # ⚠️ 要量的是「JSON 内容行」，不是「`"""` 所在的那一行」——
        #    后者是调用链所在行（`.withAgent(..., """`），缩进更浅，
        #    照它算会让续行比 JSON 行还要浅，看起来像缩进塌了。
        nl = src.index("\n", m.start())
        content_line_start = nl + 1
        rest = src[content_line_start:]
        content_line_end = content_line_start + (rest.index("\n") if "\n" in rest else len(rest))
        head_src = src[content_line_start:content_line_end]
        json_indent = len(head_src) - len(head_src.lstrip())
        cont_indent = " " * (json_indent + 1)
        new_content = (f"{content[:idx].rstrip()},\n{cont_indent}"
                       f"{limits_for(method)}{content[idx:]}")
        edits.append((m.start() + 3, m.start() + 3 + len(content), new_content))

    # ── 2) 引号字面量（含 `+` 拼接的编排 JSON）───────────────────────
    masked = mask_text_blocks(src)
    for group in find_groups(masked):
        joined = "".join(unescape_java(m.group(1)) for m in group)
        if not MODE_RE.search(joined) or "maxDelegationDepth" in joined:
            continue
        method = enclosing_method(src, group[0].start())
        if method in SKIP_METHODS:
            continue
        last = group[-1]
        raw = last.group(1)
        idx = raw.rfind("}")
        if idx < 0:
            continue
        new_raw = raw[:idx] + "," + limits_java_for(method) + raw[idx:]
        edits.append((last.start() + 1, last.end() - 1, new_raw))

    # ⚠️ 倒序应用：前面的编辑不会影响后面已算好的偏移
    edits.sort(key=lambda e: e[0], reverse=True)
    return edits


def process(path: Path, dry_run: bool) -> int:
    src = path.read_text(encoding="utf-8")
    edits = collect(src)
    if not edits:
        return 0
    out = src
    for start, end, new in edits:
        out = out[:start] + new + out[end:]
    if not dry_run:
        path.write_text(out, encoding="utf-8")
    return len(edits)


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    total = 0
    files = [p for root in TEST_ROOTS for p in sorted(root.rglob("*.java"))]
    for path in files:
        n = process(path, dry_run)
        if n:
            print(f"{path.relative_to(ROOT)}: 迁移 {n} 处")
            total += n
    print(f"合计迁移 {total} 处" + ("（dry-run，未落盘）" if dry_run else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

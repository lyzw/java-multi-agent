#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十六批 C36-1 文档回写门禁：管理台的 **Tool 全量信息**呈现。

⚠️ 本批的用户诉求（2026-10-25 · zhouwei）：
   「admin-console 中 Tool 管理，新增、修改、详情显示所有的 Tool 信息」。

涉及两份文档：

① **00 修订说明**：
   · 快照标题 + 本批性质段落；
   · 「已实现」表新增 1 行（Tool 全量信息呈现）；
   · 「历批修正的设计缺陷」表新增 1 行（**只呈现一半的 Tool 信息**）。

② **06 配置数据模型**：新增「管理台的 Tool 全量信息呈现」——
   响应层补字段（`configFingerprint` / `createdAt`）；
   展示层三处共用（详情概览 / 修改表单旁 / 登记字段清单）；
   登记**不伪造基线**、修改**给基线**；JSON **只格式化不改写**。

⚠️ 本脚本是**门禁**（校验回写是否到位），不是回写器 ——
回写已随本批代码一起落盘，脚本只负责「缺一节就红」。
"""
import os
import pathlib
import sys

DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C36_1_BASE", str(DEFAULT_BASE)))

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"

MARK = "<!-- C36-1 -->"

_failures: list[str] = []


def _require(path: pathlib.Path, needle: str, why: str) -> None:
    if not path.exists():
        _failures.append(f"文件不存在：{path}")
        return
    text = path.read_text()
    if needle not in text:
        _failures.append(f"{path.name}：缺 {why}")


def check_00() -> None:
    _require(F00, "代码落地第三十六批", "快照标题未更新")
    _require(F00, "第三十六批（C36-1）是管理台的「全量信息」补充批次", "缺本批性质段落")
    _require(F00, "管理台前端：Tool 管理的新增 / 修改 / 详情显示", "「已实现」表缺本批行")
    _require(F00, "三个目录列表补列", "「已实现」表缺「目录列表补列」这一半")
    _require(F00, "catalogListColumns.test.tsx", "「已实现」表缺目录列表的证据")
    _require(F00, "管理台 Tool 管理只呈现一半的 Tool 信息", "「历批修正的设计缺陷」表缺本批行")
    _require(F00, "已停用的对象被显示成「已发布」", "「历批修正的设计缺陷」表缺「状态列」这一行")
    _require(F00, MARK, "缺幂等标记")


def check_06() -> None:
    _require(F06, "### 管理台的 Tool 全量信息呈现（第三十六批补充 · C36-1）", "缺本节标题")
    _require(F06, "configFingerprint", "缺响应层补字段的口径")
    _require(F06, "不得伪造", "缺「登记模式不得伪造基线」的口径")
    _require(F06, "解析失败必须原样展示并标红", "缺「坏 JSON 原样展示」的口径")
    _require(F06, "### 三个能力目录的列表列口径（第三十六批补充 · C36-1 · 目录这半）", "缺目录列表那节")
    _require(F06, "lifecycleStateCell", "缺「状态列合并两个事实」的口径")
    _require(F06, "凭据引用", "缺 MCP 目录的「凭据引用」列口径")
    _require(F06, MARK, "缺幂等标记")


def main() -> int:
    check_00()
    check_06()
    if _failures:
        print("❌ 文档回写门禁未通过：")
        for f in _failures:
            print(f"   · {f}")
        return 1
    print("✅ 文档回写门禁通过（第三十六批 · C36-1）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

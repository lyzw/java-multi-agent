#!/usr/bin/env python3
"""三轮优化撤点反证：逐个撤掉修复点，跑对应门禁，必须转红；随后强制复原。

⚠️ 中断安全：SIGINT / SIGTERM 都会先复原再退出；任何异常也在 finally 复原。
⚠️ 三件套：① old 锚点在原文唯一 ② 撤点后旧锚点残留为 0 ③ 复原后文件与原文逐字节一致。
"""

from __future__ import annotations

import json
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "frontend"
OUT = ROOT / "问题记录" / "E2E_frontend_2026-11-14" / "revoke_check_round3.json"

CASES = [
    {
        "id": "A_run_step_frontend_label",
        "desc": "撤掉结果页 RUNNING→已完成（改回「执行中」）",
        "file": "frontend/src/pages/business/RunResultPage.tsx",
        "old": "  RUNNING: '已完成',",
        "new": "  RUNNING: '执行中',",
        "tests": ["tests/result.page.test.tsx", "tests/runStep.contract.test.ts"],
    },
    {
        "id": "B_shouldShowSyncing_terminal",
        "desc": "撤掉 shouldShowSyncing 对终态权限错误的排除",
        "file": "frontend/src/pages/RunProgressPage.tsx",
        "old": (
            "    !statusNotFound &&\n"
            "    !statusDone &&\n"
            "    !statusReadIsTerminal &&\n"
            "    (currentState === null || !isTerminalState(currentState))"
        ),
        "new": (
            "    !statusNotFound &&\n"
            "    !statusDone &&\n"
            "    (currentState === null || !isTerminalState(currentState))"
        ),
        "tests": ["tests/progress.page.test.tsx"],
    },
    {
        "id": "C_useRunStatus_stop_on_terminal_error",
        "desc": "撤掉 403/401 停状态轮询，退回「只有 404 停」的旧实现",
        "file": "frontend/src/features/run/useRunStatus.ts",
        "old": (
            "        if (!errorGuidance(normalized).retryable) {\n"
            "          // 404 仍单列：后端刻意统一为 404 做反探测，页面要显示「资源不存在」。\n"
            "          if (cause instanceof ApiError && cause.normalized.status === 404) {\n"
            "            setNotFound(true)\n"
            "          }\n"
            "          doneRef.current = true\n"
            "          return\n"
            "        }"
        ),
        "new": (
            "        if (cause instanceof ApiError && cause.normalized.status === 404) {\n"
            "          setNotFound(true)\n"
            "          doneRef.current = true\n"
            "          return\n"
            "        }"
        ),
        "tests": ["tests/progress.page.test.tsx"],
    },
    {
        "id": "D_service_config_code_full_table",
        "desc": "从租户级故障分类表删掉 CONFIG_MISSING",
        "file": "frontend/src/api/errorGuidance.ts",
        "old": "  'NO_ACCOUNT',\n  'CONFIG_MISSING',\n  'AGENT_CONFIG_UNAVAILABLE',",
        "new": "  'NO_ACCOUNT',\n  'AGENT_CONFIG_UNAVAILABLE',",
        "tests": ["tests/errorGuidance.test.ts", "tests/progress.page.test.tsx"],
    },
]

_active_path: Path | None = None
_active_original: str | None = None


def restore_active() -> None:
    if _active_path is not None and _active_original is not None:
        _active_path.write_text(_active_original, encoding="utf-8")


def on_signal(signum: int, _frame: object) -> None:
    restore_active()
    print(f"⚠️ 收到信号 {signum}，已复原当前文件后退出", file=sys.stderr)
    sys.exit(130)


signal.signal(signal.SIGINT, on_signal)
signal.signal(signal.SIGTERM, on_signal)


def run_case(case: dict) -> dict:
    global _active_path, _active_original
    path = ROOT / case["file"]
    original = path.read_text(encoding="utf-8")
    old, new = case["old"], case["new"]

    # ① 锚点唯一
    if original.count(old) != 1:
        raise RuntimeError(f"{case['id']}: old 锚点出现 {original.count(old)} 次，必须恰好 1 次")

    _active_path, _active_original = path, original
    try:
        revoked = original.replace(old, new, 1)
        # ② 撤点后旧锚点残留为 0
        if old in revoked:
            raise RuntimeError(f"{case['id']}: 撤点后旧锚点仍有残留")
        path.write_text(revoked, encoding="utf-8")

        cmd = ["npx", "vitest", "run", *case["tests"]]
        proc = subprocess.run(
            cmd, cwd=FRONTEND, capture_output=True, text=True, timeout=300
        )
        output = proc.stdout + proc.stderr
        failed_files = [
            line.strip() for line in output.splitlines()
            if "failed" in line.lower() and ("❯" in line or "FAIL" in line)
        ]
        result = {
            "id": case["id"],
            "desc": case["desc"],
            "file": case["file"],
            "tests": case["tests"],
            "vitest_exit": proc.returncode,
            "must_be_red": True,
            "is_red": proc.returncode != 0,
            "failure_lines": failed_files[:6],
            "tail": output[-700:],
        }
        return result
    finally:
        # ③ 强制复原并逐字节校验
        restore_active()
        assert path.read_bytes() == original.encode("utf-8"), (
            f"{case['id']}: 复原失败！文件仍是被撤点的状态"
        )
        _active_path, _active_original = None, None


def main() -> int:
    results = []
    for case in CASES:
        r = run_case(case)
        results.append(r)
        flag = "✅ 转红" if r["is_red"] else "❌ 没红（门禁失效）"
        print(f"{flag}  {r['id']}  vitest_exit={r['vitest_exit']}")
        if not r["is_red"]:
            print(r["tail"], file=sys.stderr)

    OUT.write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"\n证据：{OUT}")
    ok = all(r["is_red"] for r in results)
    print(f"结论：{len(results)} 个撤点全部转红 = {ok}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

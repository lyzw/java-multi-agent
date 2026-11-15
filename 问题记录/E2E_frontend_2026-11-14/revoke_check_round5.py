#!/usr/bin/env python3
"""
五轮优化「撤掉修复点会红」真实反证（2026-11-15）

⛔ 纪律（沿用 revoke_check_round4.py）：
  1. 每个 case 先把生产文件的一个**精确锚点**替换成「修复前形态」；
  2. 跑对应门禁，断言**必须转红**（非 0 退出码）；
  3. 无论成功/失败/中断，都在 finally 里按字节复原，并校验
     「旧锚点=1 / 新形态残留=0 / git diff --check 干净」；
  4. 注册 SIGINT/SIGTERM 信号处理器，Ctrl-C 也强制复原。

覆盖的 7 个五轮修复点：
  DEF-1 顶层错误边界（AppErrorBoundary 捕获渲染异常）
  DEF-2 全局未捕获错误安全网（installGlobalErrorHandlers）
  D1    配置「恢复默认」二次确认
  D2    治理翻页失败只记 loadMoreError，不污染首屏 error
  D3    AgentsPage 视图切换清空残留 actionError
  D6    审计明细 renderDetail 类型守卫
  D7    路由守卫登录回跳保留完整 query（pathname + search）

用法：python3 问题记录/E2E_frontend_2026-11-14/revoke_check_round5.py
"""
from __future__ import annotations

import json
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FE = ROOT / "frontend"
VITEST = FE / "node_modules" / ".bin" / "vitest"
OUT = Path(__file__).with_suffix(".json")

# 全局：所有已执行的替换，供信号处理器复原
_applied: list[tuple[Path, bytes]] = []
# 基线快照：脚本启动时每个涉改文件的原始字节，用于逐字节复原校验
BASELINE: dict[Path, bytes] = {}

APP_ERROR_BOUNDARY = FE / "src/shared/AppErrorBoundary.tsx"
GLOBAL_HANDLERS = FE / "src/shared/globalErrorHandlers.ts"
CONFIG_ROW = FE / "src/features/config/ConfigOverrideRow.tsx"
USE_GOV = FE / "src/features/governance/useGovernancePage.ts"
CONFIG_PAGES = FE / "src/pages/config/ConfigPages.tsx"
GOV_PAGES = FE / "src/pages/governance/GovernancePages.tsx"
REQUIRE_AUTH = FE / "src/auth/RequireAuth.tsx"
REQUIRE_ROLE = FE / "src/auth/RequireRole.tsx"
REQUIRE_TASK = FE / "src/auth/RequireTask.tsx"


def restore_all() -> None:
    """按字节复原所有被改过的文件（逆序）。"""
    for path, original in reversed(_applied):
        path.write_bytes(original)
    _applied.clear()


def _handler(signum: int, _frame: object) -> None:
    print(f"\n[INTERRUPT] 收到信号 {signum}，强制复原…", flush=True)
    restore_all()
    sys.exit(130)


signal.signal(signal.SIGINT, _handler)
signal.signal(signal.SIGTERM, _handler)


def replace_once(path: Path, old: str, new: str) -> None:
    """精确替换：old 必须且只能出现 1 次，否则立即失败（不静默改错）。"""
    data = path.read_bytes()
    old_b, new_b = old.encode("utf-8"), new.encode("utf-8")
    count = data.count(old_b)
    if count != 1:
        raise RuntimeError(f"{path.relative_to(ROOT)}: 锚点出现 {count} 次（期望 1）: {old[:70]!r}")
    if not _applied or _applied[-1][0] != path:
        _applied.append((path, data))
    path.write_bytes(data.replace(old_b, new_b, 1))


def run_gate(files: list[str]) -> tuple[int, str]:
    cmd = [str(VITEST), "run", *files]
    proc = subprocess.run(
        cmd, cwd=str(FE), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, timeout=300,
    )
    return proc.returncode, proc.stdout[-3000:]


# ── 七个撤点 case ──────────────────────────────────────────────
# 每个 case：desc / edits(精确锚点→修复前形态，可多文件) / gate / expect
CASES = [
    {
        "id": "R5-DEF1",
        "desc": "AppErrorBoundary 不再捕获渲染异常 → 子组件抛错时整页白屏（无兜底）",
        "edits": [(
            APP_ERROR_BOUNDARY,
            """  static getDerivedStateFromError(error: Error): State {
    return { error }
  }""",
            """  static getDerivedStateFromError(_error: Error): State {
    return { error: null }
  }""",
        )],
        "gate": ["tests/appErrorBoundary.test.tsx"],
        "expect": "「捕获异常并展示整页兜底」与「返回登录页清会话」至少 1 条转红",
    },
    {
        "id": "R5-DEF2",
        "desc": "installGlobalErrorHandlers 不安装监听 → 全局未捕获错误无人兜底",
        "edits": [(
            GLOBAL_HANDLERS,
            """  const onError = (event: ErrorEvent): void => {
    reporter({ type: 'error', error: event.error ?? event.message })
  }
  const onRejection = (event: PromiseRejectionEvent): void => {
    reporter({ type: 'rejection', error: event.reason })
  }
  window.addEventListener('error', onError)
  window.addEventListener('unhandledrejection', onRejection)""",
            """  // 撤点：不安装任何监听，reporter 不会被调用""",
        )],
        "gate": ["tests/globalErrorHandlers.test.ts"],
        "expect": "「捕获 error 与 rejection 并交给 reporter」转红",
    },
    {
        "id": "R5-D1",
        "desc": "配置「恢复默认」去掉二次确认 → 点一下即清掉租户覆盖值",
        "edits": [(
            CONFIG_ROW,
            """              onClick={() => {
                  // ⚠️ 与「提交变更」同纪律：写操作必须二次确认，避免误清覆盖值。
                  const confirmed = window.confirm(
                    `确认要将「${item.key}」恢复为平台默认值吗？\\n\\n已按租户覆盖的值将被清除。`,
                  )
                  if (confirmed) onClear(item.key)
                }}""",
            """              onClick={() => {
                  onClear(item.key)
                }}""",
        )],
        "gate": ["tests/configOverrideRow.test.tsx"],
        "expect": "「确认被拒时不得调用 onClear」转红",
    },
    {
        "id": "R5-D2",
        "desc": "治理翻页失败改回 setError → 已加载列表被整页错误态替换",
        "edits": [(
            USE_GOV,
            """        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        // ⚠️ D2：翻页失败**只记 loadMoreError**，不写全局 error ——
        //    否则已加载并展示的列表会被整页 ErrorState 替换（见各治理页渲染）。
        setLoadMoreError(message)""",
            """        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        setError(message)""",
        )],
        "gate": ["tests/useGovernancePage.test.ts"],
        "expect": "「D2：翻页失败只记 loadMoreError，保留列表、不污染首屏 error」转红",
    },
    {
        "id": "R5-D3",
        "desc": "AgentsPage 切视图不清 actionError → 发布失败错误串味进新建表单",
        "edits": [(
            CONFIG_PAGES,
            """            onClick={() => {
              // ⚠️ D3：切换「新建 / 发布」视图时清空上一次动作的残留错误，
              //    避免发布失败的错误串味进新建表单（反之亦然）。
              setCreating((v) => !v)
              setActionError(null)
            }}""",
            """            onClick={() => {
              setCreating((v) => !v)
            }}""",
        )],
        "gate": ["tests/agentsPageErrorSwitch.test.tsx"],
        "expect": "「发布失败后切到新建视图，错误提示消失」转红",
    },
    {
        "id": "R5-D6",
        "desc": "renderDetail 删掉类型守卫 → 后端回带 null/数组时 Object.entries 抛错崩页",
        "edits": [(
            GOV_PAGES,
            """  if (detail === null || typeof detail !== 'object' || Array.isArray(detail)) {
    return '—'
  }""",
            "",
        )],
        "gate": ["tests/renderDetail.test.ts"],
        "expect": "「null / 字符串 / 数组 → '—'」至少 1 条转红",
    },
    {
        "id": "R5-D7",
        "desc": "路由守卫 state.from 丢掉 search → 带 query 页面登录回跳丢失参数",
        "edits": [
            (REQUIRE_AUTH,
             '    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />',
             '    return <Navigate to="/login" replace state={{ from: location.pathname }} />'),
            (REQUIRE_ROLE,
             '    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />',
             '    return <Navigate to="/login" replace state={{ from: location.pathname }} />'),
            (REQUIRE_TASK,
             '    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />',
             '    return <Navigate to="/login" replace state={{ from: location.pathname }} />'),
        ],
        "gate": ["tests/authGuardRedirect.test.tsx"],
        "expect": "「RequireAuth/RequireRole/RequireTask state.from 含完整 query」三条全转红",
    },
]


def verify_restored() -> list[str]:
    """
    三件套：逐字节与基线一致、每个 case 的 old 锚点仍为 1、git diff --check 干净。
    """
    problems: list[str] = []
    for path, original in BASELINE.items():
        if path.read_bytes() != original:
            problems.append(f"{path.relative_to(ROOT)}: 与基线字节不一致")
    for case in CASES:
        for path, old, _new in case["edits"]:
            if path.read_bytes().count(old.encode()) != 1:
                problems.append(f"{case['id']}: 复原后 old 锚点 ≠ 1 ({path.relative_to(ROOT)})")
    diff = subprocess.run(
        ["git", "diff", "--check"], cwd=str(ROOT),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    if diff.returncode != 0 or diff.stdout.strip():
        problems.append(f"git diff --check 不干净: {diff.stdout[:200]}")
    return problems


def main() -> int:
    results = []
    for case in CASES:
        for path, old, _new in case["edits"]:
            BASELINE.setdefault(path, path.read_bytes())
    baseline_problems = verify_restored()
    if baseline_problems:
        raise RuntimeError(f"启动前基线校验失败: {baseline_problems}")
    try:
        for case in CASES:
            print(f"\n=== {case['id']} {case['desc']} ===", flush=True)
            try:
                for path, old, new in case["edits"]:
                    replace_once(path, old, new)
                code, tail = run_gate(case["gate"])
                turned_red = code != 0
                print(f"  exit={code} → {'✅ 转红' if turned_red else '❌ 仍绿（守卫失效）'}", flush=True)
                if not turned_red:
                    print(tail[-1200:], flush=True)
                results.append({
                    "id": case["id"], "desc": case["desc"],
                    "exit": code, "turned_red": turned_red,
                    "expect": case["expect"],
                })
            finally:
                restore_all()
                problems = verify_restored()
                if problems:
                    raise RuntimeError(f"{case['id']} 复原校验失败: {problems}")
                print("  复原校验：old=1 / 残留=0 / diff --check 干净", flush=True)
    finally:
        restore_all()

    red = sum(1 for r in results if r["turned_red"])
    payload = {
        "round": 5,
        "date": "2026-11-15",
        "total": len(results),
        "turned_red": red,
        "all_red": red == len(results),
        "cases": results,
        "post_check": verify_restored(),
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n=== 汇总：{red}/{len(results)} 撤点转红；复原校验 {payload['post_check']} ===", flush=True)
    print(f"证据：{OUT}", flush=True)
    return 0 if payload["all_red"] and not payload["post_check"] else 1


if __name__ == "__main__":
    sys.exit(main())

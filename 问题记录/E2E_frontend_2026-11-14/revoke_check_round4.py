#!/usr/bin/env python3
"""
四轮优化「撤掉修复点会红」真实反证（2026-11-14）

⛔ 纪律（沿用 revoke_check_round3.py）：
  1. 每个 case 先把生产文件的一个**精确锚点**替换成「修复前形态」；
  2. 跑对应门禁，断言**必须转红**（非 0 退出码）；
  3. 无论成功/失败/中断，都在 finally 里按字节复原，并校验
     「旧锚点=1 / 新形态残留=0 / git diff --check 干净」；
  4. 注册 SIGINT/SIGTERM 信号处理器，Ctrl-C 也强制复原。

用法：python3 问题记录/E2E_frontend_2026-11-14/revoke_check_round4.py
"""
from __future__ import annotations

import json
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FE = ROOT / "frontend"
OUT = Path(__file__).with_suffix(".json")

RESULT_PAGE = FE / "src/pages/business/RunResultPage.tsx"
HOOK = FE / "src/features/rag/useRagManagement.ts"
MGMT_PAGE = FE / "src/pages/rag/RagManagementPage.tsx"
STORAGE = FE / "src/features/rag/ragTenantStorage.ts"
CSV = FE / "src/features/reports/csv.ts"

# 全局：所有已执行的替换，供信号处理器复原
_applied: list[tuple[Path, bytes]] = []
# 基线快照：脚本启动时每个涉改文件的原始字节，用于逐字节复原校验
BASELINE: dict[Path, bytes] = {}


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
    cmd = ["npx", "vitest", "run", *files]
    proc = subprocess.run(
        cmd, cwd=str(FE), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, timeout=300,
    )
    return proc.returncode, proc.stdout[-3000:]


# ── 六个撤点 case ──────────────────────────────────────────────
# 每个 case：文件 / 精确锚点 / 修复前形态 / 对应门禁 / 期望转红的说明
CASES = [
    {
        "id": "R4-1",
        "desc": "结果页退回「只扫 explanation 前 10000 字符」→ 长文本尾部身份证应漏出",
        "path": RESULT_PAGE,
        "old": """    const payload: Record<string, string> = {
      讲解: result.explanation ?? '',
      错因归类: result.weaknesses
        .map((w) => joinFields([w.knowledgePoint, w.errorType]))
        .join(' '),
      解题步骤: result.steps.map((s) => s.output ?? '').join(' '),
      待核对: result.reconciliation.operations.map((o) => o.toolName ?? '').join(' '),
    }
    return findLeakedFields(payload)""",
        "new": """    const text = result.explanation === null ? '' : result.explanation.slice(0, 10000)
    return findLeakedFields({ explanation: text })""",
        "gate": ["tests/result.page.test.tsx"],
        "expect": "T9 长文本尾部 / 错因归类 / 步骤 / 待核对 至少 3 条转红",
    },
    {
        "id": "R4-2",
        "desc": "结果页删掉「错因归类」按字段拦截 → 手机号会被渲染出来",
        "path": RESULT_PAGE,
        "old": """      <Sheet title="错因归类">
        {blocked('错因归类') ? (
          // ⛔ C6：命中字段不得渲染原文，也不得编造替代内容。
          <Empty title="这部分内容里有不该出现的信息，已拦下" hint="换一个匿名编号再试，或联系辅导老师确认。" />
        ) : result.weaknesses.length === 0 ? (""",
        "new": """      <Sheet title="错因归类">
        {result.weaknesses.length === 0 ? (""",
        "gate": ["tests/result.page.test.tsx"],
        "expect": "T9「错因归类字段含手机号被拦下」转红",
    },
    {
        "id": "R4-3",
        "desc": "hook 把 bulkRegister 退回 fire-and-forget（void run()）→ 导入链成片转红",
        "path": HOOK,
        "old": """      setBulk({ status: 'submitting', response: null, error: null })
      try {
        const response = await bulkRegisterRagSources(tenantId, registeredBy, items)
        setBulk({ status: 'success', response, error: null })
        return { ok: true, response, error: null }
      } catch (cause) {
        const error =
          cause instanceof ApiError
            ? cause.normalized.message
            : '导入失败，请稍后重试'
        if (cause instanceof ApiError && isTimeoutError(cause.normalized)) {
          setBulk({ status: 'timeout', response: null, error })
        } else {
          setBulk({ status: 'error', response: null, error })
        }
        // ⛔ 绝不吞掉：调用方靠这个决定「停在已成功行数并给出续传入口」。
        return { ok: false, response: null, error }
      }""",
        "new": """      setBulk({ status: 'submitting', response: null, error: null })
      const run = async (): Promise<void> => {
        try {
          const response = await bulkRegisterRagSources(tenantId, registeredBy, items)
          setBulk({ status: 'success', response, error: null })
        } catch (cause) {
          setBulk({ status: 'error', response: null, error: '导入失败，请稍后重试' })
        }
      }
      void run()
      return undefined as unknown as BulkRegisterOutcome""",
        "gate": ["tests/bulkImport.behavior.test.tsx"],
        "expect": "25 行串行 / 第 2 批失败续传 / 防重 至少 2 条转红",
    },
    {
        "id": "R4-4",
        "desc": "页面退回「只显示最后一批结果」→ 结果表累积断言转红",
        "path": MGMT_PAGE,
        "old": """          for (const item of outcome.response.items) {
            collected.push({ ...item, index: item.index + processed })
          }""",
        "new": """          collected.length = 0
          for (const item of outcome.response.items) {
            collected.push({ ...item, index: item.index })
          }""",
        "gate": ["tests/bulkImport.behavior.test.tsx"],
        "expect": "「已导入 25 / 25 行」与全局行号断言转红",
    },
    {
        "id": "R4-5",
        "desc": "删掉 rag.tenantId 的 TTL 过期判定 → 时间旅行用例转红",
        "path": STORAGE,
        "old": """    if (Date.now() - parsed.timestamp > RAG_TENANT_STORAGE_TTL_MS) {
      window.localStorage.removeItem(RAG_TENANT_STORAGE_KEY)
      return ''
    }
    return parsed.value""",
        "new": """    return parsed.value""",
        "gate": ["tests/ragTenantStorage.test.ts"],
        "expect": "「7 天 + 1ms 后过期清空」转红",
    },
    {
        "id": "R4-6",
        "desc": "删掉 CSV 公式注入中和 → 前缀用例转红",
        "path": CSV,
        "old": """  if (FORMULA_PREFIX.test(text)) {
    // ⚠️ 前置单引号：Excel 把它当文本，不当公式
    text = `'${text}`
  }
""",
        "new": """  // 撤点：不做公式注入中和
""",
        "gate": ["tests/csv.test.ts"],
        "expect": "「公式注入四类前缀被中和」转红",
    },
]


def verify_restored() -> list[str]:
    """
    三件套：逐字节与基线一致、每个 case 的 old 锚点仍为 1、git diff --check 干净。

    ⚠️ 不能用「new 形态子串不出现」判复原：某些撤点形态（如 R4-5 的
       `return parsed.value`）本就是**原文的一部分**，会必然误报。
       逐字节比对才是唯一可靠的复原证据。
    """
    problems: list[str] = []
    for path, original in BASELINE.items():
        if path.read_bytes() != original:
            problems.append(f"{path.relative_to(ROOT)}: 与基线字节不一致")
    for case in CASES:
        data = case["path"].read_bytes()
        if data.count(case["old"].encode()) != 1:
            problems.append(f"{case['id']}: 复原后 old 锚点 ≠ 1")
    diff = subprocess.run(
        ["git", "diff", "--check"], cwd=str(ROOT),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    if diff.returncode != 0 or diff.stdout.strip():
        problems.append(f"git diff --check 不干净: {diff.stdout[:200]}")
    return problems


def main() -> int:
    results = []
    # 先记录基线（此时文件必须是修复后的形态）
    for case in CASES:
        BASELINE.setdefault(case["path"], case["path"].read_bytes())
    baseline_problems = verify_restored()
    if baseline_problems:
        raise RuntimeError(f"启动前基线校验失败: {baseline_problems}")
    try:
        for case in CASES:
            print(f"\n=== {case['id']} {case['desc']} ===", flush=True)
            try:
                replace_once(case["path"], case["old"], case["new"])
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
        "round": 4,
        "date": "2026-11-14",
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

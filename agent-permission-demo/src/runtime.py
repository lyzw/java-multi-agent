"""运行时调用链路模拟（对应 PRD §3.2 / 05 §0-§2 / 07 §2）。

统一决策状态模型（05 §0）：
  authorizationDecision(ALLOW/DENY) · executionDecision(ALLOW/DENY)
  · executionStatus(NOT_STARTED/RUNNING/SUCCEEDED/FAILED/UNKNOWN) · reasonCode
授权失败 → authorizationDecision=DENY；审批/预算失败 → authorizationDecision=ALLOW, executionDecision=DENY。

顺序（07 §2）：主体授权 → 能力链(Phase A) → 配置指纹(Phase A) → 结构/审批/预算/账本(Phase B)
      → 执行准入点重验(TOCTOU) → 执行。
本文件只是"可运行演示"，不实现真实 DB/网络；批准、预算、未知执行以入参/内存模拟。
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Optional

from .model import AuthContext
from .store import ConfigStore
from .validator import (
    STAGE_CONFIG_STALE, STAGE_OK, STAGE_STALE_AUTHZ,
    AuthorizationDecision, validate_mcp, validate_native,
)

# Phase B 执行准入阶段标识（审批/预算/执行准入点），属 runtime 层，不属 Phase A validator。
STAGE_APPROVAL = "approval"
STAGE_BUDGET = "budget"
STAGE_EXEC_ADMISSION = "exec_admission"


class ExecutionStatus(str, Enum):
    """执行状态维度（独立于授权/执行准入决策）。"""
    NOT_STARTED = "NOT_STARTED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"   # 外部副作用已发生、响应丢失 → 进对账队列


@dataclass
class InvocationRequest:
    agent_id: str
    skill_id: str
    # 二选一：
    tool_id: Optional[str] = None                       # native 调用
    mcp_server_id: Optional[str] = None                # mcp 调用
    mcp_tool_name: Optional[str] = None
    discovered_tools: Optional[List[str]] = None        # 模拟 MCP 动态列举结果（V2）
    run_id: str = "run-demo"
    invocation_id: str = "inv-1"
    idempotency_key: str = "idem-1"
    approval_id: Optional[str] = None                   # WRITE_EXTERNAL 需提供（绑定+一次性消费）
    snapshot_closure_fingerprint: Optional[str] = None  # T2 快照的【闭包级】指纹（Phase A 失效判定，P0-6/06 §4）
    simulate_unknown: bool = False                      # 模拟外部执行结果未知（S8）
    simulate_revocation_before_execute: bool = False   # 模拟撤权提交后再启动副作用（S3b TOCTOU）
    params: Optional[dict] = None                        # 入参（Phase B 结构校验用，P0-4）
    simulate_revocation_after_execute: bool = False     # 模拟执行后才提交撤权（S3f 在途不撤销，05 §5 契约 2）


@dataclass
class InvocationResult:
    """一次调用的统一结果（05 §0）。"""
    authorization_decision: str       # ALLOW / DENY
    execution_decision: str           # ALLOW / DENY
    execution_status: str             # NOT_STARTED / RUNNING / SUCCEEDED / FAILED / UNKNOWN
    stage: str
    reason_code: str
    reason: str


@dataclass
class DecisionLog:
    ts: float
    tenant_id: str
    principal_id: str
    agent_id: str
    skill_id: str
    target: str
    authorization_decision: str
    execution_decision: str
    execution_status: str
    stage: str
    reason_code: str
    authz_version: int
    config_version: int
    config_fingerprint: str
    approval_id: Optional[str] = None
    latency_ms: float = 0.0


class Runtime:
    def __init__(self, store: ConfigStore):
        self.store = store
        self.ledger: List[Dict] = []
        self.logs: List[DecisionLog] = []
        self.budgets: Dict[str, int] = {}   # tool_id -> 剩余次数；缺省=不限

    def set_budget(self, tool_id: str, remaining: int) -> None:
        self.budgets[tool_id] = remaining

    def _log(self, ctx: AuthContext, req: InvocationRequest, res: InvocationResult, target: str):
        self.logs.append(DecisionLog(
            ts=time.time(), tenant_id=ctx.tenant_id, principal_id=ctx.principal_id,
            agent_id=req.agent_id, skill_id=req.skill_id, target=target,
            authorization_decision=res.authorization_decision,
            execution_decision=res.execution_decision,
            execution_status=res.execution_status, stage=res.stage, reason_code=res.reason_code,
            authz_version=ctx.authz_version, config_version=self.store.current_config_version,
            config_fingerprint=self.store.current_config_fingerprint,
            approval_id=req.approval_id,
        ))

    def _deny(self, ad: AuthorizationDecision, estatus=ExecutionStatus.NOT_STARTED) -> InvocationResult:
        # 授权阶段失败：authorizationDecision=DENY，executionDecision=DENY
        return InvocationResult(ad.decision, "DENY", estatus.value, ad.stage,
                                 ad.reason_code, ad.reason)

    def run(self, ctx: AuthContext, req: InvocationRequest) -> InvocationResult:
        # 选路：native 还是 mcp
        if req.tool_id:
            ad = validate_native(ctx, self.store, req.agent_id, req.skill_id, req.tool_id)
            target = req.tool_id
            tool = self.store.tools.get(req.tool_id)
        else:
            ad = validate_mcp(ctx, self.store, req.agent_id, req.skill_id,
                              req.mcp_server_id, req.mcp_tool_name, req.discovered_tools)
            tool = self.store.find_mcp_tool(req.mcp_server_id, req.mcp_tool_name)
            target = f"{req.mcp_server_id}/{req.mcp_tool_name}"

        # ---- Phase A 授权 ----
        if not ad.allowed:
            res = self._deny(ad)
            self._log(ctx, req, res, target)
            return res

        # [闭包级指纹失效] Phase A：仅本 Run 依赖闭包内对象变化才触发（P0-6 / 06 §4 / 08 S5a）
        if req.snapshot_closure_fingerprint is not None:
            cur = self.store.closure_fingerprint(
                req.agent_id, req.skill_id, req.tool_id, req.mcp_server_id, req.mcp_tool_name)
            if cur != req.snapshot_closure_fingerprint:
                res = InvocationResult("DENY", "DENY", ExecutionStatus.NOT_STARTED.value,
                                       STAGE_CONFIG_STALE, "CONFIG_FINGERPRINT_STALE",
                                       "快照闭包指纹与当前不一致（本 Run 依赖的能力闭包已变更）")
                self._log(ctx, req, res, target)
                return res

        # ---- Phase B 执行准入 ----
        # [结构校验 Phase B 单次]（P0-4）：inputSchema 必填字段校验，仅此一次，与 Phase A 参数策略不重复
        if req.params is not None:
            schema = tool.input_schema or {}
            missing = [p for p in schema.get("required", []) if p not in req.params]
            if missing:
                res = InvocationResult("ALLOW", "DENY", ExecutionStatus.NOT_STARTED.value,
                                       "schema", "PARAM_SCHEMA_FAIL",
                                       f"入参缺少必填字段 {missing}（结构校验失败）")
                self._log(ctx, req, res, target)
                return res

        # [风险驱动审批] ALLOW 仍记 authorizationDecision=ALLOW，仅 executionDecision=DENY
        need = tool.risk_level.default_approval()
        if need == "REQUIRED" and not req.approval_id:
            res = InvocationResult("ALLOW", "DENY", ExecutionStatus.NOT_STARTED.value,
                                   STAGE_APPROVAL, "APPROVAL_REQUIRED",
                                   f"风险 {tool.risk_level.value} 需要审批，但未提供 approval_id")
            self._log(ctx, req, res, target)
            return res

        # [预算预留] 不足 → 仅 executionDecision=DENY
        tid = tool.id
        if tid in self.budgets and self.budgets[tid] <= 0:
            res = InvocationResult("ALLOW", "DENY", ExecutionStatus.NOT_STARTED.value,
                                   STAGE_BUDGET, "BUDGET_EXCEEDED",
                                   f"工具 {tid} 预算不足")
            self._log(ctx, req, res, target)
            return res
        if tid in self.budgets and self.budgets[tid] > 0:
            self.budgets[tid] -= 1

        # [账本登记 PRE]（演示：直接成功）
        self.ledger.append({
            "tenant_id": ctx.tenant_id, "run_id": req.run_id, "tool_id": tid,
            "invocation_id": req.invocation_id, "idempotency_key": req.idempotency_key,
            "status": "pre",
        })

        # [执行准入点 / TOCTOU] 真正发外部请求前重验版本新鲜度（07 §2[10] / 05 §5）
        if req.simulate_revocation_before_execute:
            # 模拟：调用通过初步授权后，管理员撤权事务提交（bump 版本 + 可能改指纹）
            self.store.bump_authz_version()
        if ctx.authz_version != self.store.current_authz_version:
            res = InvocationResult("ALLOW", "DENY", ExecutionStatus.NOT_STARTED.value,
                                   STAGE_EXEC_ADMISSION, "EXEC_ADMISSION_STALE",
                                   "执行准入点重验：授权/配置状态已失效，不启动新副作用")
            self._log(ctx, req, res, target)
            return res

        # [执行] 外部结果未知 → UNKNOWN（08 S8）
        if req.simulate_unknown:
            estatus = ExecutionStatus.UNKNOWN
            result = None
        else:
            estatus = ExecutionStatus.SUCCEEDED
            result = f"executed[{tid}] risk={tool.risk_level.value}"

        # [在途不撤销] 执行成功后才提交撤权 → 该调用为已进入执行阶段的在途操作（05 §5 契约 2 / S3f）
        if req.simulate_revocation_after_execute:
            self.store.bump_authz_version()

        final = InvocationResult("ALLOW", "ALLOW", estatus.value, STAGE_OK, "", "执行成功")
        self._log(ctx, req, final, target)
        return final

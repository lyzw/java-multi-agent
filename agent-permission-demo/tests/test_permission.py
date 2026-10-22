"""权限解析与校验单元测试（stdlib unittest，可运行：python -m tests.test_permission）。

覆盖：四层闭包、Agent→Skill、Skill→Tool、riskCeiling、MCP 连接/操作级、动态漂移、
authzVersion 重授权、租户收紧覆盖、嵌套 Skill 继承、缺 cap 丢弃。
端到端（TestE2EAcceptance）对齐 08 §1/§2：统一决策状态模型（05 §0）——授权失败记
authorizationDecision=DENY；审批/预算失败记 authorizationDecision=ALLOW, executionDecision=DENY。
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.model import Agent, AuthContext, McpBinding, McpServer, RiskLevel, Skill, Tool
from src.resolver import (
    effective_mcp_bindings,
    effective_skills_of_agent,
    effective_tools_of_skill,
    reachable_tools_closure,
)
from src.store import ConfigStore
from src.validator import (
    STAGE_AGENT_SKILL,
    STAGE_CONFIG_STALE,
    STAGE_MCP_DYNAMIC,
    STAGE_MCP_OP,
    STAGE_OK,
    STAGE_RISK_CEILING,
    STAGE_SKILL_MCP,
    STAGE_STALE_AUTHZ,
    validate_mcp,
    validate_native,
)
from src.runtime import ExecutionStatus, InvocationRequest, Runtime, STAGE_APPROVAL, STAGE_BUDGET

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(HERE, "config")


class TestClosure(unittest.TestCase):
    def setUp(self):
        self.store = ConfigStore(CONFIG, current_authz_version=2)

    def test_effective_skills_omit_tenant_removed(self):
        a = self.store.agents["homework_tutor"]
        eff = effective_skills_of_agent(a, self.store)
        self.assertIn("student_profile_reader", eff)
        self.assertNotIn("weakness_recorder", eff)

    def test_nested_skill_inheritance_tools(self):
        s = self.store.skills["composite_tutor"]
        tools = effective_tools_of_skill(s, self.store)
        self.assertEqual(tools, {"get_student_profile", "record_weakness"})

    def test_reachable_closure(self):
        a = self.store.agents["composite_agent"]
        self.assertEqual(reachable_tools_closure(a, self.store),
                         {"get_student_profile", "record_weakness"})


class TestNativeValidation(unittest.TestCase):
    def setUp(self):
        self.store = ConfigStore(CONFIG, current_authz_version=2)
        self.ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=2)

    def _run(self, agent, skill, tool):
        rt = Runtime(self.store)
        return rt.run(self.ctx, InvocationRequest(agent, skill, tool_id=tool))

    def test_allow_read(self):
        res = self._run("homework_tutor", "student_profile_reader", "get_student_profile")
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "ALLOW")
        self.assertEqual(res.stage, STAGE_OK)
        self.assertEqual(res.execution_status, "SUCCEEDED")

    def test_deny_unenabled_skill(self):
        res = self._run("overconfident_agent", "weakness_recorder", "record_weakness")
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.stage, STAGE_AGENT_SKILL)
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_deny_tenant_removed_skill(self):
        res = self._run("homework_tutor", "weakness_recorder", "record_weakness")
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.stage, STAGE_AGENT_SKILL)
        self.assertEqual(res.execution_status, "NOT_STARTED")


class TestRiskCeiling(unittest.TestCase):
    def test_low_ceiling_blocks_high_risk_tool(self):
        store = ConfigStore(CONFIG, current_authz_version=2)
        low = Skill(id="low", declared_tools=("record_weakness",), risk_ceiling=RiskLevel.READ)
        store.skills["low"] = low
        store.agents["low_agent"] = Agent(
            id="low_agent", tenant_id="t-1001", roles=("AGENT_AUTHOR",),
            enabled_skills=("low",), capability_tags=("edu:write_profile",))
        ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), 2)
        d = validate_native(ctx, store, "low_agent", "low", "record_weakness")
        self.assertFalse(d.allowed)
        self.assertEqual(d.stage, STAGE_RISK_CEILING)


class TestMcpValidation(unittest.TestCase):
    def setUp(self):
        self.store = ConfigStore(CONFIG, current_authz_version=2)
        self.ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=2)

    def test_allow_mcp_within_allowlist(self):
        d = validate_mcp(self.ctx, self.store, "notifier_agent", "notifier",
                         "notify_mcp", "send_mail")
        self.assertTrue(d.allowed)

    def test_deny_mcp_op_not_allowed(self):
        d = validate_mcp(self.ctx, self.store, "notifier_agent", "notifier",
                         "notify_mcp", "send_sms")
        self.assertFalse(d.allowed)
        self.assertEqual(d.stage, STAGE_MCP_OP)

    def test_deny_mcp_dynamic_drift(self):
        d = validate_mcp(self.ctx, self.store, "notifier_agent", "notifier",
                         "notify_mcp", "send_mail", discovered_tools=["send_sms"])
        self.assertFalse(d.allowed)
        self.assertEqual(d.stage, STAGE_MCP_DYNAMIC)

    def test_deny_mcp_server_not_declared(self):
        d = validate_mcp(self.ctx, self.store, "homework_tutor", "student_profile_reader",
                         "notify_mcp", "send_mail")
        self.assertFalse(d.allowed)
        self.assertEqual(d.stage, STAGE_SKILL_MCP)


class TestReauth(unittest.TestCase):
    def test_stale_authz_denied(self):
        store = ConfigStore(CONFIG, current_authz_version=2)
        ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=1)
        d = validate_native(ctx, store, "homework_tutor", "student_profile_reader",
                            "get_student_profile")
        self.assertFalse(d.allowed)
        self.assertEqual(d.stage, STAGE_STALE_AUTHZ)


class TestCapabilities(unittest.TestCase):
    def test_missing_cap_ignored(self):
        # 评审 P0-3：V1 忽略语义 —— requiredCaps 缺失不丢弃 Skill、不用于启用/闭包/授权判定
        store = ConfigStore(CONFIG, current_authz_version=2)
        store.agents["test_agent"] = Agent(
            id="test_agent", tenant_id="t-1001", roles=("AGENT_AUTHOR",),
            enabled_skills=("student_profile_reader", "weakness_recorder"),
            capability_tags=("edu:read",))  # 故意缺 edu:write_profile
        eff = effective_skills_of_agent(store.agents["test_agent"], store)
        self.assertIn("student_profile_reader", eff)
        self.assertIn("weakness_recorder", eff)  # 缺 cap 不丢弃


class TestE2EAcceptance(unittest.TestCase):
    """评审最终意见 + 08 §1/§2：端到端场景必须全绿方可冻结 V1。
    统一决策状态模型（05 §0）：授权失败=authorizationDecision DENY；
    审批/预算失败=authorizationDecision ALLOW + executionDecision DENY。
    """

    def setUp(self):
        self.ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=2)

    def _rt(self, **kw):
        store = ConfigStore(CONFIG, current_authz_version=2)
        return Runtime(store), store

    def test_S1_authorized_tool(self):
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("homework_tutor", "student_profile_reader",
                                                  tool_id="get_student_profile"))
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "ALLOW")
        self.assertEqual(res.execution_status, "SUCCEEDED")

    def test_S2_unauthorized_tool(self):
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("homework_tutor", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_mail"))
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.execution_decision, "DENY")
        self.assertEqual(res.stage, STAGE_AGENT_SKILL)
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S3_revoked_principal(self):
        rt, _ = self._rt()
        stale = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=1)
        res = rt.run(stale, InvocationRequest("homework_tutor", "student_profile_reader",
                                               tool_id="get_student_profile"))
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.reason_code, "AUTHZ_STALE")
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S3a_cross_instance_invalidation(self):
        # A 实例撤权提交（bump 版本），B 实例随后发起调用 → 强一致读取新版本即失效
        rt, store = self._rt()
        store.bump_authz_version()  # 撤权事务提交
        late = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), authz_version=2)
        res = rt.run(late, InvocationRequest("homework_tutor", "student_profile_reader",
                                              tool_id="get_student_profile"))
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.reason_code, "AUTHZ_STALE")

    def test_S3b_execution_admission_blocks_after_revoke(self):
        # 调用通过 Phase A 授权后、撤权提交，再尝试启动外部副作用 → 执行准入点阻断
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                                                  approval_id="apr-1",
                                                  simulate_revocation_before_execute=True))
        self.assertEqual(res.authorization_decision, "ALLOW")   # 授权曾通过
        self.assertEqual(res.execution_decision, "DENY")        # 仅执行准入拒绝
        self.assertEqual(res.reason_code, "EXEC_ADMISSION_STALE")
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S3c_concurrent_monotonic_no_lost_update(self):
        store = ConfigStore(CONFIG, current_authz_version=2)
        seen = []

        def worker():
            for _ in range(50):
                seen.append(store.bump_authz_version())

        ts = [threading.Thread(target=worker) for _ in range(4)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        self.assertEqual(len(seen), 200)
        self.assertEqual(len(set(seen)), 200)   # 无丢失更新：版本值唯一且连续
        self.assertEqual(min(seen), 3)
        self.assertEqual(max(seen), 202)

    def test_S3d_version_service_unavailable(self):
        rt, store = self._rt()
        store.version_service_unavailable = True
        res = rt.run(self.ctx, InvocationRequest("homework_tutor", "student_profile_reader",
                                                  tool_id="get_student_profile"))
        self.assertEqual(res.authorization_decision, "DENY")   # fail-closed
        self.assertEqual(res.reason_code, "AUTHZ_STALE")

    def test_S3e_repeated_revoke_no_rollback(self):
        store = ConfigStore(CONFIG, current_authz_version=2)
        store.bump_authz_version()
        store.bump_authz_version()
        self.assertEqual(store.current_authz_version, 4)   # 继续递增，无回退

    def test_S4_skill_tightened(self):
        rt, store = self._rt()
        base = store.agents["homework_tutor"]
        store.agents["homework_tutor"] = Agent(
            id=base.id, tenant_id=base.tenant_id, roles=base.roles,
            enabled_skills=base.enabled_skills, skill_bundles=base.skill_bundles,
            capability_tags=base.capability_tags, inherits=base.inherits,
            overrides={"removeSkills": ["student_profile_reader"], "removeTools": [], "lowerRiskCeiling": {}},
            version=base.version,
        )
        res = rt.run(self.ctx, InvocationRequest("homework_tutor", "student_profile_reader",
                                                  tool_id="get_student_profile"))
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.stage, STAGE_AGENT_SKILL)

    def test_S5a_closure_fingerprint(self):
        # 评审 P0-6 / 06 §4：闭包级指纹 —— 仅本 Run 依赖闭包内对象变化才触发失效
        rt, store = self._rt()
        fp = store.closure_fingerprint("notifier_agent", "notifier",
                                       mcp_server_id="notify_mcp", mcp_tool_name="send_mail")
        # 1) 正确指纹 → ALLOW
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                     mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                     approval_id="apr-1", snapshot_closure_fingerprint=fp))
        self.assertEqual(res.authorization_decision, "ALLOW")

        # 2) 无关对象变更（修改与本 Run 无关的 student_profile_reader 的 riskCeiling）
        #    → 本 Run 闭包指纹不变 → 仍 ALLOW（不改变无关配置不触发失效）
        store.skills["student_profile_reader"] = Skill(
            id="student_profile_reader", declared_tools=("get_student_profile",),
            declared_mcp=(), risk_ceiling=RiskLevel.WRITE_EXTERNAL,
            param_policies=(), inherits=(), required_caps=("edu:read",))
        fp_unchanged = store.closure_fingerprint("notifier_agent", "notifier",
                         mcp_server_id="notify_mcp", mcp_tool_name="send_mail")
        self.assertEqual(fp, fp_unchanged)
        res2 = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                      mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                      approval_id="apr-1", snapshot_closure_fingerprint=fp))
        self.assertEqual(res2.authorization_decision, "ALLOW")

        # 3) 相关对象变更（MCP server endpoint 变化）→ 闭包指纹变化 → DENY(CONFIG_FINGERPRINT_STALE)
        store.mcp_servers["notify_mcp"] = McpServer(
            id="notify_mcp", transport="http", endpoint="https://changed.example",
            trust_tier="public", exposed_tool_allowlist=None,
            connection_risk=RiskLevel.WRITE_EXTERNAL, dynamic_discovery=False)
        fp_changed = store.closure_fingerprint("notifier_agent", "notifier",
                       mcp_server_id="notify_mcp", mcp_tool_name="send_mail")
        self.assertNotEqual(fp, fp_changed)
        res3 = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                      mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                      approval_id="apr-1", snapshot_closure_fingerprint=fp))
        self.assertEqual(res3.authorization_decision, "DENY")
        self.assertEqual(res3.reason_code, "CONFIG_FINGERPRINT_STALE")
        self.assertEqual(res3.execution_status, "NOT_STARTED")

    def test_S5b_mcp_op_not_allowed(self):
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_sms"))
        self.assertEqual(res.authorization_decision, "DENY")
        self.assertEqual(res.reason_code, "MCP_OP_NOT_ALLOWED")
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S6_approval_missing(self):
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_mail"))
        # 审批缺失：授权通过、仅执行准入拒绝
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "DENY")
        self.assertEqual(res.reason_code, "APPROVAL_REQUIRED")
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S7_budget_exceeded(self):
        rt, _ = self._rt()
        rt.set_budget("send_email", 0)   # WRITE_EXTERNAL 工具预算耗尽
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                                                  approval_id="apr-1"))
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "DENY")
        self.assertEqual(res.reason_code, "BUDGET_EXCEEDED")
        self.assertEqual(res.execution_status, "NOT_STARTED")

    def test_S8_external_result_unknown(self):
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                                                  mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                                                  approval_id="apr-1", simulate_unknown=True))
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "ALLOW")
        self.assertEqual(res.execution_status, "UNKNOWN")  # 非成功，进对账

    def test_risk_level_unknown_runtime_denied(self):
        # 评审 P0-1 / 05 §3.3：风险未知/非法/未登记 → authorizationDecision=DENY(RISK_LEVEL_UNKNOWN)
        # 不降级为 WRITE_EXTERNAL 后放行
        store = ConfigStore(CONFIG, current_authz_version=2)
        store.tools["bad_tool"] = Tool(id="bad_tool", name="bad", kind="native",
                                       risk_level=RiskLevel.UNKNOWN, input_schema={})
        ctx = AuthContext("t-1001", "u-zhou", ("AGENT_AUTHOR",), 2)
        d = validate_native(ctx, store, "homework_tutor", "student_profile_reader", "bad_tool")
        self.assertFalse(d.allowed)
        self.assertEqual(d.reason_code, "RISK_LEVEL_UNKNOWN")

    def test_config_publish_rejects_unknown_risk(self):
        # 配置期拒绝发布：风险等级未知/非法 → 加载即报错（05 §3.3）
        tmp = tempfile.mkdtemp()
        shutil.copytree(CONFIG, tmp)
        tp = os.path.join(tmp, "tools.json")
        with open(tp) as f:
            data = json.load(f)
        data["tools"][0]["riskLevel"] = "SUPER_SECRET"
        with open(tp, "w") as f:
            json.dump(data, f)
        with self.assertRaises(ValueError):
            ConfigStore(tmp, current_authz_version=2)

    def test_S3f_inflight_not_blocked(self):
        # 评审 P0-7 / 05 §5 契约 2：执行准入已通过、副作用已发出后才提交撤权
        # → 在途操作不承诺绝对阻断，按既有结果执行并交由对账/补偿（S3f）
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("notifier_agent", "notifier",
                     mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                     approval_id="apr-1", simulate_revocation_after_execute=True))
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "ALLOW")
        self.assertEqual(res.execution_status, "SUCCEEDED")

    def test_schema_fail_param_required_missing(self):
        # 评审 P0-4：Phase B 结构校验（inputSchema 必填）单次失败 → 仅 executionDecision=DENY
        rt, _ = self._rt()
        res = rt.run(self.ctx, InvocationRequest("homework_tutor", "student_profile_reader",
                     tool_id="get_student_profile", params={}))
        self.assertEqual(res.authorization_decision, "ALLOW")
        self.assertEqual(res.execution_decision, "DENY")
        self.assertEqual(res.reason_code, "PARAM_SCHEMA_FAIL")
        self.assertEqual(res.execution_status, "NOT_STARTED")


if __name__ == "__main__":
    unittest.main(verbosity=2)

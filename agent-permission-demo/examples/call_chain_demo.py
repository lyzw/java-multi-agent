"""典型调用链路示例（可直接运行：python examples/call_chain_demo.py）。

演示 PRD §3.2 的完整校验链与各类 DENY 路径。运行期 authzVersion=当前版本(2)即合法。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.model import AuthContext
from src.resolver import effective_skills_of_agent, reachable_tools_closure
from src.runtime import InvocationRequest, Runtime
from src.store import ConfigStore

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(HERE, "config")

VALID_CTX = AuthContext(tenant_id="t-1001", principal_id="u-zhou", roles=("AGENT_AUTHOR",), authz_version=2)
STALE_CTX = AuthContext(tenant_id="t-1001", principal_id="u-zhou", roles=("AGENT_AUTHOR",), authz_version=1)


def banner(t: str):
    print("\n" + "=" * 70 + f"\n{t}\n" + "=" * 70)


def main():
    store = ConfigStore(CONFIG, current_authz_version=2)
    rt = Runtime(store)

    banner("0. Agent 有效能力闭包（绑定/审计用）")
    for aid in ["homework_tutor", "composite_agent", "notifier_agent", "overconfident_agent"]:
        a = store.agents[aid]
        print(f"  {aid:18s} 有效Skill={sorted(effective_skills_of_agent(a, store))}")
        print(f"  {'':18s} 可达Tool ={sorted(reachable_tools_closure(a, store))}")

    def show(res, r=None):
        print(f"  -> authz={res.authorization_decision} exec={res.execution_decision} "
              f"status={res.execution_status} stage={res.stage} code={res.reason_code}"
              + (f" | result={r}" if r else (f" :: {res.reason}" if res.authorization_decision == 'DENY' or res.execution_decision == 'DENY' else "")))

    banner("1. [ALLOW] homework_tutor → student_profile_reader → get_student_profile (READ)")
    res = rt.run(VALID_CTX, InvocationRequest("homework_tutor", "student_profile_reader",
                                              tool_id="get_student_profile"))
    show(res)

    banner("2. [ALLOW] composite_agent → composite_tutor(继承) → record_weakness (WRITE_PROFILE, OPTIONAL审批)")
    res = rt.run(VALID_CTX, InvocationRequest("composite_agent", "composite_tutor",
                                              tool_id="record_weakness"))
    show(res)

    banner("3. [DENY] overconfident_agent 越权调用 record_weakness（未启用该 Skill）")
    res = rt.run(VALID_CTX, InvocationRequest("overconfident_agent", "weakness_recorder",
                                              tool_id="record_weakness"))
    show(res)

    banner("4. [DENY] homework_tutor 经未启用 Skill notifier 发邮件（Agent→Skill 拒）")
    res = rt.run(VALID_CTX, InvocationRequest("homework_tutor", "notifier",
                                              mcp_server_id="notify_mcp", mcp_tool_name="send_mail"))
    show(res)

    banner("5. [ALLOW] notifier_agent → notifier → notify_mcp → send_mail (WRITE_EXTERNAL, 需审批)")
    res = rt.run(VALID_CTX, InvocationRequest("notifier_agent", "notifier",
                                              mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                                              approval_id="apr-9988"))
    show(res)

    banner("6. [DENY] notifier 调 send_sms（不在 toolAllowlist，操作级越界 → MCP_OP_NOT_ALLOWED）")
    res = rt.run(VALID_CTX, InvocationRequest("notifier_agent", "notifier",
                                              mcp_server_id="notify_mcp", mcp_tool_name="send_sms"))
    show(res)

    banner("7. [DENY] MCP 动态漂移：server 当前不再暴露 send_mail（运行期交集为空）")
    res = rt.run(VALID_CTX, InvocationRequest("notifier_agent", "notifier",
                                              mcp_server_id="notify_mcp", mcp_tool_name="send_mail",
                                              discovered_tools=["send_sms"], approval_id="apr-1"))
    show(res)

    banner("8. [DENY] 陈旧 authzVersion（权限被撤销后，在途 Run 越权 → AUTHZ_STALE）")
    res = rt.run(STALE_CTX, InvocationRequest("homework_tutor", "student_profile_reader",
                                              tool_id="get_student_profile"))
    show(res)

    banner("9. [DENY] 租户收紧覆盖：t-1001 移除 homework_tutor 的 weakness_recorder")
    res = rt.run(VALID_CTX, InvocationRequest("homework_tutor", "weakness_recorder",
                                              tool_id="record_weakness"))
    show(res)

    banner("决策日志（结构化审计样例，四字段）")
    for lg in rt.logs:
        if lg.authorization_decision == "DENY" or lg.execution_decision == "DENY":
            print(f"  DENY  tenant={lg.tenant_id} agent={lg.agent_id} skill={lg.skill_id} "
                  f"target={lg.target} authz={lg.authorization_decision} exec={lg.execution_decision} "
                  f"code={lg.reason_code}")


if __name__ == "__main__":
    main()

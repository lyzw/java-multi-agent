package cn.studykid.multiagent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 运行时人工审批（HITL）就绪自检（P1-C 修复）。
 *
 * <p><b>⚠️ 修复前的缺口</b>：{@code WorkerExecutionConfig#approvalSource} 返回的是
 * <b>空策略</b>的 {@code PolicyEngineApprovalSource}（空 map → 全部
 * {@code FAIL_CLOSED}）。后果是：任何 {@code WRITE_EXTERNAL} 副作用工具在运行时
 * <b>永远被拒</b>——这本身是安全的默认，但它<b>没有任何可见信号</b>：
 * 日志里表现为一条普通的工具拒绝，运维无从判断这是「审批未通过」还是
 * 「压根没有活体审批通道」。而 {@code agent-api} 的 {@code ApprovalController}
 * 操作的是<b>另一条链路</b>（能力/配置发布域），并不覆盖工具副作用执行域，
 * 于是「人工审批」在产品上看似存在、在运行时其实<b>没有放行入口</b>。
 *
 * <p><b>本守卫不改变任何判定</b>（不放松门禁、不替工具放行），只做两件事：
 * <ol>
 *   <li><b>让缺口可见</b>：若存在「需审批」的工具，但没有活体审批通道，
 *       启动时打出<b>醒目</b>告警，并列出因此恒被拒的工具名；</li>
 *   <li><b>可升级为硬失败</b>：配置 {@code agent.approval.require-live-source=true}
 *       时，同一情形直接中止启动（fail-closed），避免带着「审批形同虚设」
 *       的配置对外服务。</li>
 * </ol>
 *
 * <p>纯判定逻辑（{@link #toolsRequiringApproval}）与端口分离，便于单测钉死。
 */
public class ApprovalReadinessGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovalReadinessGuard.class);

    private final List<ToolHandler> tools;
    private final RiskBasedApprovalPolicy policy;
    /** 是否已接入活体审批通道（{@code agent.approval.live-source-enabled}）。 */
    private final boolean liveSourceEnabled;
    /** 未接入活体通道时是否拒绝启动（{@code agent.approval.require-live-source}）。 */
    private final boolean requireLiveSource;

    public ApprovalReadinessGuard(List<ToolHandler> tools,
                                  RiskBasedApprovalPolicy policy,
                                  boolean liveSourceEnabled,
                                  boolean requireLiveSource) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.policy = policy;
        this.liveSourceEnabled = liveSourceEnabled;
        this.requireLiveSource = requireLiveSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> blocked = toolsRequiringApproval(tools, policy);

        if (blocked.isEmpty()) {
            log.info("运行时审批自检通过：当前注册的工具均无需人工审批。");
            return;
        }

        if (liveSourceEnabled) {
            log.info("运行时审批自检通过：{} 个工具需人工审批，已接入活体审批通道。",
                    blocked.size());
            return;
        }

        log.error("⚠️⚠️ 运行时人工审批未接线：以下 {} 个工具需人工审批，"
                        + "但当前没有活体审批来源（审批来源恒 FAIL_CLOSED），"
                        + "运行时将被一律拒绝：{}。"
                        + "这不是故障而是既定安全状态；如需人工放行，"
                        + "须接入真实审批通道并把 agent.approval.live-source-enabled 置为 true。",
                blocked.size(), blocked);

        if (requireLiveSource) {
            throw new IllegalStateException(
                    "运行时审批自检未通过：需人工审批的工具存在，但未接入活体审批通道（"
                            + String.join(", ", blocked) + "）");
        }
    }

    /**
     * 列出 RequiresApproval 的工具名（纯函数，可被单测钉死）。
     *
     * <p>⚠️ 判据必须与运行时{@link RiskBasedApprovalPolicy#evaluate} 一致：
     * 此处若自创一套判据，就会出现「启动时说不用审批、运行时却要审批」的分叉。
     */
    static @org.jspecify.annotations.NonNull List<String> toolsRequiringApproval(
            List<ToolHandler> tools, RiskBasedApprovalPolicy policy) {
        List<String> result = new ArrayList<>();
        if (tools == null || policy == null) {
            return result;
        }
        for (ToolHandler tool : tools) {
            if (tool == null) {
                continue;
            }
            if (policy.evaluate(tool).requiresApproval()) {
                result.add(tool.toolName());
            }
        }
        return List.copyOf(result);
    }
}

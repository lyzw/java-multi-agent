package cn.studykid.multiagent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import cn.studykid.multiagent.application.cap.CapExecutionFlow;

/**
 * CAP 授权链路就绪守卫（P0-1 修复：把「CAP 关闭」这个静默缺口变成显式、可观测的状态）。
 *
 * <p><b>背景</b>：{@code config.flag.cap-authorization-enforced} 默认 {@code false}
 * （{@link CapEnforcementSwitch} 类注释：P5 指针读路径已落地，全量开启待运行时灰度证据）。
 * 关闭时执行点走既有链路，<b>不按 Agent 能力闭包校验工具</b>——
 * 任何已注册工具均可被任意具备 {@code TOOL_INVOKE} 的 Agent 调用。
 *
 * <p><b>本报告评审的 P0-1 核心抱怨是「配了也不生效且无任何报错」</b>（静默失效）。
 * 本守卫把这一状态<b>变得可见</b>：
 * <ul>
 *   <li>CAP 开启但执行流未装配 → <b>fail-closed 拒绝启动</b>
 *       （{@code ToolInvocationGate} 注释断言的「不可能配置」若真发生，必须立刻暴露）；</li>
 *   <li>CAP 关闭 → 启动期打印一条<b>显眼 SECURITY WARN</b>，说明当前不按能力闭包校验工具；</li>
 *   <li>CAP 关闭且 {@code config.flag.cap-enforcement-required=true} →
 *       <b>fail-closed 拒绝启动</b>，供生产以硬失败姿态强制显式授权。</li>
 * </ul>
 *
 * <p>⚠️ 本守卫<b>只观测、不替它做决定</b>：它不翻转开关、不私自放行。完整的「按 Agent 能力闭包
 * 强制校验工具」在开关置 {@code true} 后生效（P5 指针读路径 2026-11-04 已落地，
 * 见 {@link CapEnforcementSwitch} 类注释）。
 */
public class CapEnforcementReadinessGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CapEnforcementReadinessGuard.class);

    private final CapEnforcementSwitch capSwitch;
    private final boolean enforcementRequired;
    private final ObjectProvider<CapExecutionFlow> capFlow;
    /**
     * 闭包校验模式（P0-1a）。
     *
     * <p>⚠️ <b>为什么也要纳入本守卫</b>：
     * 「硬失败」要覆盖的是<b>强制层到底有没有校验</b>，
     * 而强制层有两条通路 —— CAP 链路（{@code cap-authorization-enforced}）
     * 与既有链路的闭包守卫（{@code cap-closure-enforcement}）。
     * 只看前者的话，CAP 关着、闭包守卫又是 warn 模式时，
     * 生产照样「带着一个不校验的强制层」启动，而守卫说「已通过」。
     */
    private final String closureEnforcementMode;

    public CapEnforcementReadinessGuard(
            CapEnforcementSwitch capSwitch,
            boolean enforcementRequired,
            ObjectProvider<CapExecutionFlow> capFlow) {
        this(capSwitch, enforcementRequired, capFlow, null);
    }

    public CapEnforcementReadinessGuard(
            CapEnforcementSwitch capSwitch,
            boolean enforcementRequired,
            ObjectProvider<CapExecutionFlow> capFlow,
            @Nullable String closureEnforcementMode) {
        this.capSwitch = capSwitch;
        this.enforcementRequired = enforcementRequired;
        this.capFlow = capFlow;
        this.closureEnforcementMode = closureEnforcementMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = new ArrayList<>();
        assess(failures, capSwitch.isEnabled(), capFlowPresent(), enforcementRequired,
                closureEnforcementMode);

        if (!failures.isEmpty()) {
            log.error("CAP 授权链路就绪守卫未通过（{} 项）：{}", failures.size(), failures);
            throw new IllegalStateException(
                    "CAP 授权链路就绪守卫未通过：" + String.join("; ", failures));
        }

        if (!capSwitch.isEnabled()) {
            // ⚠️ 显式记录（非静默）：当前执行点不按 Agent 能力闭包校验工具。
            //    ⚠️ P0-1a：既有链路的闭包守卫（cap-closure-enforcement）现在也参与强制层，
            //    故本条告警必须区分「两条通路都没强制」与「闭包守卫已在强制」——
            //    否则告警会声称「任何已注册工具都可被任意 Agent 调用」，
            //    而实际已有闭包校验在拦，**假警报同样有害**（会让人忽略真警报）。
            //    ⚠️ P0-1c：灰度白名单是「给个别租户提前开启」的先行通道（不是豁免）。
            boolean closureForced = closureEnforcementMode != null
                    && "enforce".equalsIgnoreCase(closureEnforcementMode.trim());
            int allowlistSize = capSwitch.tenantAllowlist().size();
            if (closureForced) {
                log.warn("⚠️ [SECURITY] CAP 授权链路未全局启用（config.flag.cap-authorization-enforced=false"
                        + "，灰度白名单 {} 个租户），"
                        + " 但既有链路的闭包守卫已强制（cap-closure-enforcement=enforce）："
                        + " 工具名仍按 Agent 能力闭包校验，但参数/风险上限等更细的判定"
                        + " 仍走既有链路。",
                        allowlistSize);
            } else {
                log.warn("⚠️ [SECURITY] CAP 授权链路未全局启用（config.flag.cap-authorization-enforced=false"
                        + "，灰度白名单 {} 个租户），"
                        + " 且闭包守卫未强制（cap-closure-enforcement=" + closureEnforcementMode + "）。"
                        + " 执行点不按 Agent 能力闭包校验工具名/参数/风险上限"
                        + " —— 任何已注册工具均可被任意具备 TOOL_INVOKE 的 Agent 调用（P0-1）。"
                        + " 白名单内租户（先行者）除外。"
                        + " 请装载能力目录后置 config.flag.cap-closure-enforcement=enforce，"
                        + " 或置 config.flag.cap-authorization-enforced=true 启用 CAP 链路；"
                        + " 若需以硬失败姿态强制，可设 config.flag.cap-enforcement-required=true。",
                        allowlistSize);
            }
        } else {
            log.info("CAP 授权链路已全局启用：执行点将按 Agent 能力闭包强制校验工具/参数/风险上限。");
        }
    }

    private boolean capFlowPresent() {
        try {
            return capFlow.getIfAvailable() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 就绪判定（纯函数，不碰 Spring 上下文，可被单测钉死）。
     *
     * @param failures             收集失败项
     * @param enabled             CAP 开关是否开启
     * @param capFlowPresent      执行流是否已装配
     * @param enforcementRequired 是否要求必须启用 CAP（硬失败开关）
     */
    static void assess(List<String> failures, boolean enabled,
                       boolean capFlowPresent, boolean enforcementRequired) {
        assess(failures, enabled, capFlowPresent, enforcementRequired, null);
    }

    /**
     * 就绪判定（纯函数，不碰 Spring 上下文，可被单测钉死）。
     *
     * @param failures             收集失败项
     * @param enabled             CAP 开关是否开启
     * @param capFlowPresent      执行流是否已装配
     * @param enforcementRequired 是否要求必须启用 CAP（硬失败开关）
     * @param closureMode         闭包校验模式（P0-1a）；{@code null} = 未装配闭包守卫
     */
    static void assess(List<String> failures, boolean enabled,
                       boolean capFlowPresent, boolean enforcementRequired,
                       @Nullable String closureMode) {
        if (enabled && !capFlowPresent) {
            failures.add("CAP 授权链路开关已开启，但执行流（CapExecutionFlow）未装配"
                    + " —— 这是不可能的配置（见 CapEnforcementSwitch 注释），必须立即修复装配");
        }
        // ⚠️ P0-1a：强制层有**两条**通路 —— CAP 链路（cap-authorization-enforced）
        //    与既有链路的闭包守卫（cap-closure-enforcement）。
        //    只要**任意一条**处于强制状态，强制层就真的在校验；
        //    两条都不是 → 平台带着「不校验的强制层」启动，必须拒绝。
        //    ⚠️ 只看 CAP 开关的话，CAP 关着 + 闭包守卫 warn 模式时，
        //    守卫会报「已通过」而强制层其实没在校验 —— 正是本仓最反感的那类假防线。
        boolean closureForced = closureMode != null
                && "enforce".equalsIgnoreCase(closureMode.trim());
        if (enforcementRequired && !enabled && !closureForced) {
            failures.add("能力闭包强制校验未生效（CAP 开关=" + enabled
                    + "，闭包校验模式=" + closureMode + "），但 "
                    + "config.flag.cap-enforcement-required=true：拒绝启动（fail-closed）。"
                    + " 请装载能力目录（cap_tool_def / cap_skill_def）后置 "
                    + "config.flag.cap-closure-enforcement=enforce，"
                    + " 或置 config.flag.cap-authorization-enforced=true 启用 CAP 链路");
        }
    }
}

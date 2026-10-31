package cn.studykid.multiagent.worker.execute;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * 能力闭包守卫（P0-1a）—— <b>把「闭包只在呈现层生效」补成「强制层也生效」</b>。
 *
 * <p><b>缺陷背景（P0-1）</b>：此前闭包校验只存在于 CAP 授权链路
 * （{@code CapExecutionFlow}），而生产默认走的是<b>既有链路</b>
 * （{@code ToolInvocationGate.invokeAllLegacy}）——
 * 全文件 grep {@code closure} 零命中。
 * 于是「能力闭包」这件事<b>只在呈现层生效</b>：
 * {@code AgentModelConfigAssembler} 按闭包裁剪了给模型看的工具 Schema，
 * 但闸门侧不校验，于是任何已注册工具都可被任意 Agent 调用
 * （模型若被注入诱导而报出越权工具名，legacy 链照样放行）。
 *
 * <p><b>本守卫的定位</b>：在既有链路上补一道
 * 「工具名 ∈ 该 Agent 生效闭包」的判定。
 * 它<b>不替代</b> CAP 链路，只负责把「声明即授权」这条底线补上。
 *
 * <p><b>⚠️ 降级策略必须显式</b>（这是本类最重要的设计）：
 * <ul>
 *   <li>{@link Mode#OFF}：完全不校验（历史行为）；</li>
 *   <li>{@link Mode#WARN}：校验但<b>只告警不拒绝</b>——
 *       用于「能力目录尚未装载」的过渡期：把缺口变成<b>可见</b>的
 *       （ERROR 日志 + 安全事件），而不是继续悄悄放行；</li>
 *   <li>{@link Mode#ENFORCE}：校验且<b>拒绝</b>未声明的工具。</li>
 * </ul>
 * <b>为什么 WARN 存在</b>：闭包判定的输入是「能力目录」。
 * 目录为空时，任何强制模式都会把<b>所有</b>工具调用拒掉 ——
 * 那不是「校验生效」，是「平台不可用」。
 * 与其让运维在不知情的情况下遭遇全拒绝，不如让它看见
 * 「你的能力目录是空的，因此闭包校验尚未真正生效」这条明确结论。
 *
 * <p><b>⚠️ 闭包解析不出来时一律判为「未声明」</b>（fail-closed）：
 * 「无法证明它被声明」不能等于「它被声明了」。
 * 在 {@link Mode#WARN} 下这表现为告警，在 {@link Mode#ENFORCE} 下表现为拒绝。
 *
 * <p><b>本类无副作用</b>：只读，不写库、不改状态，可被反复调用。
 */
public class CapabilityClosureGuard {

    private static final Logger log = LoggerFactory.getLogger(CapabilityClosureGuard.class);

    /** 校验模式。 */
    public enum Mode {

        /** 不校验（历史行为）。 */
        OFF,

        /** 校验但只告警（过渡期：把缺口变成可见的）。 */
        WARN,

        /** 校验且拒绝未声明的工具。 */
        ENFORCE;

        /** @return 解析模式；非法值按 {@link #WARN} 处理（不得静默放行）。 */
        public static @NonNull Mode parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return WARN;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn("未知的闭包校验模式 '{}'，按 WARN 处理", raw);
                return WARN;
            }
        }
    }

    /**
     * 判定结论。
     *
     * @param allowed 是否放行
     * @param reason  不放行时的原因（供回灌给模型与安全事件）
     */
    public record Decision(boolean allowed, @NonNull String reason) {

        static @NonNull Decision allow() {
            return new Decision(true, "");
        }

        static @NonNull Decision deny(@NonNull String reason) {
            return new Decision(false, reason);
        }
    }

    private final ClosureResolver closureResolver;
    private final CapabilitySnapshotPort snapshots;
    private final Mode mode;

    public CapabilityClosureGuard(@NonNull ClosureResolver closureResolver,
                                  @NonNull CapabilitySnapshotPort snapshots,
                                  @NonNull Mode mode) {
        this.closureResolver = closureResolver;
        this.snapshots = snapshots;
        this.mode = mode;
    }

    /** @return 当前校验模式。 */
    public @NonNull Mode mode() {
        return mode;
    }

    /**
     * 判定「该工具是否在当前执行主体的生效闭包内」。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @param toolName 工具名
     * @param scope    执行作用域；{@code null} = 根 Agent
     * @return 判定结论
     */
    public @NonNull Decision evaluate(@NonNull String tenantId,
                                       @NonNull String runId,
                                       @NonNull String toolName,
                                       @Nullable ExecutionScope scope) {
        if (mode == Mode.OFF) {
            return Decision.allow();
        }
        Optional<EffectiveClosure> closure = resolveClosure(tenantId, runId, scope);
        if (closure.isEmpty()) {
            // ⚠️ 闭包解析不出来 = 无法证明「已声明」 → 按未声明处理（fail-closed）
            String reason = "无权调用该工具（能力闭包无法解析，无法证明该工具已被声明）";
            log.error("{}: tenant={}, run={}, tool={}", reason, tenantId, runId, toolName);
            return denyOrWarn(reason);
        }
        EffectiveClosure effective = closure.get();
        boolean declared = effective.tool(toolName).isPresent()
                || effective.skills().stream()
                        .anyMatch(skill -> skill.declaredTools().contains(toolName));
        if (declared) {
            return Decision.allow();
        }
        //    ⚠️ 文案须含「无权」：RunExecutionLoop 靠该关键词把拒绝归类为
        //    SEC-2「越权调用」安全事件 —— 换了词就等于这条拒绝不再计入安全事实。
        String reason = "无权调用该工具（不在该 Agent 能力闭包内）";
        log.error("{}: tenant={}, run={}, tool={}, 闭包工具数={}",
                reason, tenantId, runId, toolName, effective.tools().size());
        return denyOrWarn(reason);
    }

    /**
     * 解析当前执行主体的闭包。
     *
     * <p>⚠️ <b>子 Agent 用自己的固定 release</b>：沿用根 Agent 的闭包会让
     * 子 Agent 冒用父的能力（委派绕过能力分层）。
     *
     * @return 闭包；解析不出时为空
     */
    private @NonNull Optional<EffectiveClosure> resolveClosure(@NonNull String tenantId,
                                                                @NonNull String runId,
                                                                @Nullable ExecutionScope scope) {
        try {
            if (scope != null) {
                return Optional.of(closureResolver.resolve(tenantId, scope.agentId(),
                        scope.agentVersion()));
            }
            Optional<CapabilitySnapshot> snapshot =
                    snapshots.find(tenantId, runId);
            if (snapshot.isEmpty()) {
                // 没有能力快照 = 没有「这个 Run 属于哪个 Agent」的证据 → 无法定位闭包
                return Optional.empty();
            }
            return Optional.of(closureResolver.resolveCurrent(tenantId, snapshot.get().agentId()));
        } catch (RuntimeException e) {
            log.warn("能力闭包解析失败: tenant={}, run={}, err={}", tenantId, runId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 按模式决定「拒绝」还是「只告警」。
     *
     * <p>⚠️ {@link Mode#WARN} 下放行但留下明确记录：
     * 「不拒绝」只应作为**过渡期**的妥协，绝不能让缺口重新变得不可见。
     */
    private @NonNull Decision denyOrWarn(@NonNull String reason) {
        if (mode == Mode.ENFORCE) {
            return Decision.deny(reason);
        }
        log.warn("⚠️ 闭包校验未拦截（模式=WARN，能力目录可能尚未装载）: {}", reason);
        return Decision.allow();
    }
}

package cn.studykid.multiagent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.context.ModelCapabilities;

/**
 * 有效策略裁决与 {@code policyVersion} 固化（ADR-013 / 《方案》§3.1、§3.4、§5.8）。
 *
 * <p>⚠️ <b>只在 Run 创建期调用一次</b>：入参的 Agent 策略必须来自
 * {@code execution_snapshot} 的快照，绝不能读「当前已发布版本」——
 * 否则在途 Run 会因一次发布而中途换策略，checkpoint 与摘要口径同时漂移（[D4]）。
 *
 * <p>⚠️ <b>policyVersion 的四个输入</b>：策略全字段 + 模型能力与窗口 +
 * Agent 版本 + 全局硬顶。任一变化都必须使旧 checkpoint 失效重建，
 * ⛔ 绝不跨策略 / 跨模型窗口 / 跨 Agent 版本 / 跨租户复用摘要。
 *
 * <p>⚠️ 纯算法：无 IO、无时钟、无租户上下文；同一输入必得同一版本号。
 */
public final class ContextPolicyResolver {

    /** 版本前缀；运维排障时可从 {@code context.checkpoint.*} 指标直接辨认。 */
    static final String VERSION_PREFIX = "cp_";

    private ContextPolicyResolver() {
    }

    /** 解析输入。全部为不可变值，不含任何「读当前配置」的Supplier。 */
    public record Input(
            @NonNull ContextPolicy agentPolicy,
            @NonNull ModelCapabilities capabilities,
            @NonNull GlobalContextPolicy global,
            int maxOutputTokens,
            @NonNull String agentVersion) {
    }

    /**
     * 裁决有效策略。
     *
     * @throws ContextPolicyRejectedException 窗口溢出或比例反转（fail-closed，绝不夹紧）
     */
    public static @NonNull ContextPolicy resolve(@NonNull Input spec) {
        ContextPolicy agent = spec.agentPolicy();
        GlobalContextPolicy global = spec.global();

        // [D11] 关闭等价：总熔断 / Agent 关闭 / OFF 档一律返回同一个 off() 对象。
        if (!global.enabled() || !agent.enabled()
                || agent.strategy() == ContextPolicy.Strategy.OFF) {
            return ContextPolicy.off();
        }

        ModelCapabilities caps = spec.capabilities();
        int modelCap = caps.maxInputTokens() > 0
                ? caps.maxInputTokens() : global.defaultMaxInputTokens();
        int window = Math.min(Math.min(agent.maxInputTokens(), modelCap),
                global.hardCeilingTokens());

        // ⚠️ 输入 + 输出超过模型总窗口：配置期就拒绝，绝不运行期悄悄夹紧。
        long totalWindow = caps.contextWindowTokens() > 0
                ? caps.contextWindowTokens()
                : (long) global.hardCeilingTokens() + 4_096;
        if ((long) window + Math.max(0, spec.maxOutputTokens()) > totalWindow) {
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_WINDOW_EXCEEDED,
                    "输入硬顶 " + window + " + 输出上限 " + spec.maxOutputTokens()
                            + " 超过模型总窗口 " + totalWindow);
        }
        if (agent.targetRatio() >= agent.triggerRatio()) {
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_RATIO_INVERTED,
                    "targetRatio=" + agent.targetRatio()
                            + " 必须严格小于 triggerRatio=" + agent.triggerRatio());
        }

        // R10：模型能力未知 → 不假设特性，上调 reserve 抵消粗估误差。
        double reserve = agent.reserveRatio();
        if (!caps.known()) {
            reserve = Math.min(0.25,
                    Math.max(reserve, global.unknownCapabilityReserveRatio()));
        }

        ContextPolicy effective = agent
                .withMaxInputTokens(window)
                .withReserveRatio(reserve);
        String version = VERSION_PREFIX + sha256Prefix(
                canonical(effective, caps, global, spec.agentVersion()), 16);
        return effective.withPolicyVersion(version);
    }

    /**
     * 确定性规范化串：逐字段显式列出（⛔ 不用反射 / JSON 库的字段顺序，
     * 否则 JDK 升级或库换版会改变版本号，导致全量 checkpoint 无谓失效）。
     */
    private static @NonNull String canonical(@NonNull ContextPolicy p,
                                             @NonNull ModelCapabilities caps,
                                             @NonNull GlobalContextPolicy g,
                                             @NonNull String agentVersion) {
        StringBuilder b = new StringBuilder(512);
        char sep = '\u001F';
        b.append("policy=").append(p.enabled()).append(sep)
                .append(p.maxInputTokens()).append(sep)
                .append(p.triggerRatio()).append(sep)
                .append(p.targetRatio()).append(sep)
                .append(p.reserveRatio()).append(sep)
                .append(p.recentTurns()).append(sep)
                .append(p.pinFirstUserTurn()).append(sep)
                .append(p.ragRatio()).append(sep)
                .append(p.memoryRatio()).append(sep)
                .append(p.summaryRatio()).append(sep)
                .append(p.strategy()).append(sep)
                .append(p.summarizerModel()).append(sep)
                .append(p.summaryMaxTokens()).append(sep)
                .append(p.summaryTimeoutMs()).append(sep)
                .append(p.maxCompactionsPerRun()).append(sep)
                .append(p.carryOver()).append(sep)
                .append(p.checkpointTtlSeconds()).append(sep);
        b.append("caps=").append(caps.modelId()).append(sep)
                .append(caps.contextWindowTokens()).append(sep)
                .append(caps.maxInputTokens()).append(sep)
                .append(caps.tokenizerId()).append(sep)
                .append(caps.supportsPromptCaching()).append(sep)
                .append(caps.supportsTokenizeApi()).append(sep);
        b.append("global=").append(g.enabled()).append(sep)
                .append(g.hardCeilingTokens()).append(sep)
                .append(g.defaultMaxInputTokens()).append(sep)
                .append(g.unknownCapabilityReserveRatio()).append(sep);
        b.append("agentVersion=").append(agentVersion);
        return b.toString();
    }

    private static @NonNull String sha256Prefix(@NonNull String text, int hexChars) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺失 SHA-256，无法固化 policyVersion", e);
        }
    }
}

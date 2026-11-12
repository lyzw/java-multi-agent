package cn.studykid.multiagent.ports.agentchat;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * L2 SSE 请求侧 {@code contextState} 的<b>中性校验端口</b>（ADR-013 D49 / S3-M8-a）。
 *
 * <p>⚠️ <b>为什么放在 {@code ports/agentchat} 而不是 {@code ports/context}</b>：
 * 后者是 S2-M6 冻结的四个中性端口目录，由 {@code ContextCompressionM6GuardTest#contextPortsRemainNeutralAndFrameworkFree}
 * 以「恰好四个 interface」钉死；本端口是 M8 请求侧新增的第五个契约，加入冻结目录会破坏
 * 契约基线，故与 {@code AgentChatRequest} 同目录，保持端口层零算法层 / 零框架反向依赖。
 *
 * <p>⚠️ <b>不可信纪律（D49 六项）</b>：HMAC / {@code exp} / {@code policyVersion} /
 * tenantId digest / {@code max-state-chars} / {@code upToTurn ≤ history}。
 * 端口只表达「接受 / 无压缩态 / 静默丢弃 + 原因」，⛔ 不把异常抛给 HTTP 层：
 * 任一校验命中即降级为全量历史，不报错、不拒绝请求、不发 {@code error} 帧。
 *
 * <p>⚠️ {@link Accepted#stateJson()} 是<b>验签通过后的结构化状态 JSON</b>（不透明串），
 * 仍是外部数据：下游必须用 {@code PromptGuard.wrapCompactedContext()} 包进
 * {@code <compacted_context trust="untrusted">} 的 USER 消息，⛔ 绝不进 {@code systemPrompt}。
 * 本批（M8-a）只校验并随请求下传，注入由 M8-b 接线。
 *
 * <p>⚠️ 纯端口：不引用 {@code agent-context} 算法层类型，零 Spring / 零 JDBC / 零 IO。
 * 全参构造唯一，不提供兼容构造器（铁律 #10）。
 */
public interface ContextStateVerifier {

    /**
     * 校验客户端回传的压缩态。
     *
     * @param input 请求级校验上下文（租户、历史轮数、客户端原始串、当前已裁决策略指纹）
     * @return 三态结果；调用方只消费 {@link Accepted}，其余一律走全量历史
     */
    @NonNull Result verify(@NonNull Input input);

    /**
     * 请求级校验上下文（中性 DTO）。空白 {@code clientState} 归一为 {@code null}
     * 表示「本轮无压缩态」，与「有 state 但校验失败」严格区分；空白
     * {@code expectedPolicyVersion} 归一为 {@code null}，表示当前策略尚未裁决，
     * verifier 必须 fail-closed 丢弃，⛔ 不接受「信封自己说自己是哪版策略」。
     */
    record Input(
            @Nullable String clientState,
            @NonNull String tenantId,
            int historyTurns,
            @Nullable String expectedPolicyVersion) {

        public Input {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("ContextStateVerifier.Input.tenantId 不得为空（fail-closed）");
            }
            if (historyTurns < 0) {
                throw new IllegalArgumentException("ContextStateVerifier.Input.historyTurns 不得为负");
            }
            clientState = clientState == null || clientState.isBlank() ? null : clientState;
            // ⚠️ 策略指纹不是全局常量：按 D17 只由 ContextPolicyResolver 裁决产生。
            //    调用方拿不到 EffectiveContextPolicy.policyVersion() 时必须传 null，
            //    verifier 以 POLICY_VERSION_MISMATCH 静默丢弃，⛔ 绝不从信封自身反推「当前策略」。
            expectedPolicyVersion = expectedPolicyVersion == null || expectedPolicyVersion.isBlank()
                    ? null : expectedPolicyVersion;
        }
    }

    /** 校验结果：接受 / 无压缩态 / 静默丢弃。 */
    sealed interface Result permits Accepted, Absent, Discarded {
    }

    /**
     * 验签与六项校验全部通过。
     *
     * @param stateJson      验签后的结构化状态 JSON（不透明串，仍须围栏注入）
     * @param policyVersion  信封内的策略指纹
     * @param upToTurn       已吸收轮次；null = 未用轮次坐标
     * @param digest         状态摘要（{@code sha256:…}），幂等键
     */
    record Accepted(
            @NonNull String stateJson,
            @NonNull String policyVersion,
            @Nullable Integer upToTurn,
            @NonNull String digest) implements Result {

        public Accepted {
            Objects.requireNonNull(stateJson, "Accepted.stateJson 不得为空");
            Objects.requireNonNull(policyVersion, "Accepted.policyVersion 不得为空");
            Objects.requireNonNull(digest, "Accepted.digest 不得为空");
        }
    }

    /** 本轮无压缩态（请求未携带 / 空串），走全量历史，不是失败。 */
    record Absent() implements Result {
    }

    /** 携带了 state 但校验未通过；静默丢弃 + 全量历史降级，绝不向用户报错。 */
    record Discarded(@NonNull DropReason reason) implements Result {

        public Discarded {
            Objects.requireNonNull(reason, "Discarded.reason 不得为空");
        }
    }

    /**
     * 丢弃原因（六项 + 结构/密钥变体），供日志与反向验证精确断言。
     *
     * <p>映射：{@code TOO_LARGE}=max-state-chars；{@code MALFORMED}=结构/整数/base64/状态重建；
     * {@code HMAC_MISMATCH}+{@code KEY_UNAVAILABLE}=HMAC；{@code EXPIRED}=exp；
     * {@code POLICY_VERSION_MISMATCH}=policyVersion；{@code TENANT_MISMATCH}=tenantId digest；
     * {@code TURN_CURSOR_AHEAD}=upToTurn &gt; history。
     */
    enum DropReason {
        TOO_LARGE,
        MALFORMED,
        HMAC_MISMATCH,
        KEY_UNAVAILABLE,
        EXPIRED,
        POLICY_VERSION_MISMATCH,
        TENANT_MISMATCH,
        TURN_CURSOR_AHEAD
    }
}

package cn.studykid.multiagent.bootstrap.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.context.ContextStateEnvelope;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Absent;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Input;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;

/**
 * L2 SSE {@code contextState} 校验端口的<b>唯一组合根适配实现</b>（ADR-013 D49 / S3-M8-a）。
 *
 * <p>⚠️ <b>只做接线，不做第二套算法</b>：HMAC / base64url / 六项不可信校验全部委托
 * {@link ContextStateEnvelope}；本类只注入独立 {@code AGENT_CONTEXT_STATE_HMAC_KEY}、
 * {@code max-state-chars} 与 {@link Clock}，并把当前已裁决策略指纹从调用方传入。
 *
 * <p>⚠️ <b>policyVersion 不是全局常量</b>（D17）：它只由 {@code ContextPolicyResolver}
 * 结合「策略全字段 + 模型能力/窗口 + Agent 版本 + 全局硬顶」派生。调用方拿不到
 * {@code EffectiveContextPolicy.policyVersion()} 时必须传 {@code null}，本适配器以
 * {@link DropReason#POLICY_VERSION_MISMATCH} 静默丢弃，⛔ 绝不从信封自身反推「当前策略」
 * 而制造第二套真相，也绝不让旧策略摘要跨版本续写。
 *
 * <p>⚠️ <b>fail-closed 三层</b>：无压缩态 → {@link Absent}；独立密钥为空 →
 * {@link DropReason#KEY_UNAVAILABLE}；策略未裁决 / 任一校验命中 → {@link Discarded}。
 * 以上全部静默降级全量历史，⛔ 不抛给 HTTP 层、不发 {@code error} 帧、不写 outbox。
 *
 * <p>⚠️ 本批不启用 S4 checkpoint 表 / {@code ContextCheckpointPort} 落库 / W3 真摘要。
 */
public final class ContextStateVerifierAdapter implements ContextStateVerifier {

    private final byte[] hmacKey;
    private final int maxStateChars;
    private final Clock clock;

    public ContextStateVerifierAdapter(@NonNull String stateHmacKey,
                                       int maxStateChars,
                                       @NonNull Clock clock) {
        if (maxStateChars <= 0) {
            throw new IllegalArgumentException("maxStateChars 必须为正");
        }
        this.hmacKey = (stateHmacKey == null ? "" : stateHmacKey)
                .getBytes(StandardCharsets.UTF_8);
        this.maxStateChars = maxStateChars;
        this.clock = Objects.requireNonNull(clock, "clock 不得为空");
    }

    @Override
    public @NonNull Result verify(@NonNull Input input) {
        Objects.requireNonNull(input, "ContextStateVerifier.Input 不得为空");

        if (input.clientState() == null) {
            return new Absent();
        }
        if (hmacKey.length == 0) {
            return new Discarded(DropReason.KEY_UNAVAILABLE);
        }
        if (input.expectedPolicyVersion() == null) {
            return new Discarded(DropReason.POLICY_VERSION_MISMATCH);
        }

        return ContextStateEnvelope.verify(input.clientState(),
                new ContextStateEnvelope.VerifyInput(
                        input.expectedPolicyVersion(), input.tenantId(), input.historyTurns()),
                hmacKey.clone(),
                clock.instant().getEpochSecond(),
                maxStateChars);
    }
}

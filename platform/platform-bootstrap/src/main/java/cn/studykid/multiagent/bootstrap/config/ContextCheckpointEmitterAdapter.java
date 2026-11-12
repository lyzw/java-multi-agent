package cn.studykid.multiagent.bootstrap.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.context.CheckpointDeltaFactory;
import cn.studykid.multiagent.context.ContextStateEnvelope;
import cn.studykid.multiagent.context.VerbatimSpanExtractor;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter;
import cn.studykid.multiagent.ports.context.CheckpointDelta;

/**
 * L2 SSE 轮末压缩态封包的<b>唯一组合根适配实现</b>（ADR-013 D49 / S3-M8-b-2）。
 *
 * <p>⚠️ <b>只做接线，不做第二套算法</b>：增量合并 / 逐字抽取 / 状态序列化 / 摘要 / HMAC 信封
 * 全部委托 {@code agent-context} 的三个纯算法内核
 * （{@link CheckpointDeltaFactory} → {@link VerbatimSpanExtractor} → {@link ContextStateEnvelope}）。
 * 本类只注入独立 {@code AGENT_CONTEXT_STATE_HMAC_KEY}、{@code max-state-chars}、
 * {@code checkpoint-ttl-seconds} 与 {@link Clock}，与接收侧 {@link ContextStateVerifierAdapter}
 * <b>共用同一把密钥 / 同一长度上限</b>——否则会出现「发出去的 state 下一轮自己验不过」
 * 的静默失真。
 *
 * <p>⚠️ <b>optional 轮末增强的 fail-soft</b>：与装配入口（fail-closed，拒绝对话）不同，
 * checkpoint 是「没有也能正确回答」的增强。任何一步返回 null（无新增 / 抽取回落 /
 * 状态超长）或抛异常（密钥不可用 / 信封非法 / 算法拒绝）都<b>静默返回 null</b>，
 * 让 L2 不发第 14 事件、Done 不带版本，主流程照常收口。⛔ 绝不把可选增强的失败
 * 变成 error 帧 / FAILED 审计，也绝不吞掉主流程异常。
 *
 * <p>⚠️ <b>policyVersion 与 TTL/cursor 来源唯一</b>：策略指纹由调用方（D17 裁决端口）传入，
 * TTL / max-chars / key / clock 由本类从组合根配置注入；算法层零配置读取。
 *
 * <p>⚠️ 本批不启用 S4 checkpoint 表 / {@code ContextCheckpointPort} 落库 / W3 真摘要；
 * 封包<b>只在内存</b>产出后交给 L2 发射，⛔ 不写 DB / 不写 outbox / 不发 MQ。
 */
public final class ContextCheckpointEmitterAdapter implements ContextCheckpointEmitter {

    private static final Logger log = LoggerFactory.getLogger(ContextCheckpointEmitterAdapter.class);

    private final byte[] hmacKey;
    private final int maxStateChars;
    private final int checkpointTtlSeconds;
    private final Clock clock;
    private final VerbatimSpanExtractor extractor = new VerbatimSpanExtractor();

    public ContextCheckpointEmitterAdapter(@NonNull String stateHmacKey,
                                           int maxStateChars,
                                           int checkpointTtlSeconds,
                                           @NonNull Clock clock) {
        if (maxStateChars <= 0) {
            throw new IllegalArgumentException("maxStateChars 必须为正");
        }
        if (checkpointTtlSeconds <= 0) {
            throw new IllegalArgumentException("checkpointTtlSeconds 必须为正");
        }
        this.hmacKey = (stateHmacKey == null ? "" : stateHmacKey)
                .getBytes(StandardCharsets.UTF_8);
        this.maxStateChars = maxStateChars;
        this.checkpointTtlSeconds = checkpointTtlSeconds;
        this.clock = Objects.requireNonNull(clock, "clock 不得为空");
    }

    @Override
    public @Nullable Emitted emit(@NonNull Input input) {
        Objects.requireNonNull(input, "ContextCheckpointEmitter.Input 不得为空");
        // 独立密钥为空 = 无法签发一个「下一轮自己会接受」的 state，静默不发（fail-soft）。
        if (hmacKey.length == 0) {
            log.debug("L2 checkpoint 未发射：独立 state HMAC 密钥为空（影子期预期行为）");
            return null;
        }
        try {
            CheckpointDelta delta = CheckpointDeltaFactory.build(
                    new CheckpointDeltaFactory.Input(
                            input.scopeKey(), input.modelId(), input.historyMessages(),
                            input.historyTurns(), input.policyVersion(),
                            input.carryOverStateJson(), input.tier(), input.fallback(),
                            checkpointTtlSeconds, maxStateChars),
                    extractor);
            if (delta == null) {
                // 无新增 / 抽取回落 / 状态超长：算法层已判定「不该发」，静默跳过。
                return null;
            }
            String state = ContextStateEnvelope.seal(
                    new ContextStateEnvelope.SealInput(
                            delta.stateJson(), delta.policyVersion(), input.tenantId(),
                            delta.upToTurn(), delta.digest(), delta.checkpointTtlSeconds()),
                    hmacKey.clone(), clock.instant().getEpochSecond(), maxStateChars);
            return new Emitted(state, delta.upToTurn(), delta.policyVersion(),
                    delta.tier(), delta.fallback(), delta.digest());
        } catch (RuntimeException e) {
            // 可选增强的任何失败都不打断 RAG / 回答 / 审计主流程；⛔ 不打印原始 stateJson。
            log.debug("L2 checkpoint 未发射：封包失败 kind={}（静默降级为不发）",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}

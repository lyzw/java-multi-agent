package cn.studykid.multiagent.core.compliance;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 零保留确认状态（DEC-10 / GATE-2）。
 *
 * <p><b>为什么放在领域层</b>：这是合规状态，不是配置项。
 * 把它建模为类型而非布尔开关，是为了让「未确认」无法被静默当作「已确认」——
 * 后者一旦发生，就是 Sev-0 合规事故（学生数据被用于训练或出境）。
 *
 * <p><b>状态迁移</b>：
 * <pre>
 *   PENDING ──confirm()──> CONFIRMED
 *      │                     │
 *      │                     └── 条款到期 → EXPIRED（自动按 expiresAt 判定）
 *      └──reject()──> REJECTED（供应商明确拒绝，永久不可用）
 * </pre>
 *
 * <p><b>不可逆性</b>：{@code REJECTED} 与 {@code EXPIRED} 均<b>不可</b>回到
 * {@code CONFIRMED}。若要重新确认，必须重新走流程产生新记录——
 * 避免「悄悄刷新一下有效期」绕过复审。
 */
public record ZeroRetentionStatus(
        @NonNull State state,
        @Nullable String providerId,
        @Nullable String confirmationEvidenceRef,
        @Nullable Instant confirmedAt,
        @Nullable Instant expiresAt
) {

    public enum State {
        /** 未确认。GATE-1 生效中：禁止真实学生数据进入 Prompt。 */
        PENDING,
        /** 已取得零保留书面条款。GATE-2 通过。 */
        CONFIRMED,
        /** 供应商明确拒绝零保留。永久不可用于真实学生数据。 */
        REJECTED,
        /** 曾确认但已过期。需重新确认。 */
        EXPIRED
    }

    public ZeroRetentionStatus {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (state == State.CONFIRMED) {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("已确认状态必须记录供应商标识");
            }
            if (confirmationEvidenceRef == null || confirmationEvidenceRef.isBlank()) {
                throw new IllegalArgumentException(
                        "已确认状态必须记录证据出处——口头承诺不可作为合规证据");
            }
            if (confirmedAt == null) {
                throw new IllegalArgumentException("已确认状态必须记录确认时间");
            }
        }
    }

    /** 初始状态：未确认。 */
    public static @NonNull ZeroRetentionStatus pending() {
        return new ZeroRetentionStatus(State.PENDING, null, null, null, null);
    }

    /**
     * 记录确认结果。
     *
     * @param providerId   供应商标识
     * @param evidenceRef  证据出处（合同条款号 / 工单号 / 邮件存档编号）
     * @param confirmedAt  确认时间
     * @param expiresAt    条款有效期；null 表示无有效期
     */
    public @NonNull ZeroRetentionStatus confirm(@NonNull String providerId,
                                                 @NonNull String evidenceRef,
                                                 @NonNull Instant confirmedAt,
                                                 @Nullable Instant expiresAt) {
        return new ZeroRetentionStatus(State.CONFIRMED,
                providerId, evidenceRef, confirmedAt, expiresAt);
    }

    /** 记录供应商明确拒绝。 */
    public @NonNull ZeroRetentionStatus reject(@NonNull String providerId) {
        return new ZeroRetentionStatus(State.REJECTED, providerId, null, null, null);
    }

    /**
     * 按当前时间求有效状态。
     *
     * <p>⚠️ 过期判定在此处集中实现，避免各处散落判断导致不一致。
     */
    public @NonNull State effectiveState(@NonNull Instant now) {
        if (state == State.CONFIRMED && expiresAt != null && now.isAfter(expiresAt)) {
            return State.EXPIRED;
        }
        return state;
    }

    /**
     * 是否允许真实学生数据进入 Prompt。
     *
     * <p>⚠️ 仅 {@code CONFIRMED} 且未过期才为 true。
     * 其余一律 false——<b>fail-closed</b>：不确定时按「禁止」处理。
     */
    public boolean allowsRealStudentData(@NonNull Instant now) {
        return effectiveState(now) == State.CONFIRMED;
    }

    /** GATE-1 是否仍在生效。 */
    public boolean gate1Active(@NonNull Instant now) {
        return !allowsRealStudentData(now);
    }

    /** 距过期的天数；无有效期返回空。 */
    public Optional<Long> daysUntilExpiry(@NonNull Instant now) {
        if (expiresAt == null) {
            return Optional.empty();
        }
        return Optional.of(java.time.Duration.between(now, expiresAt).toDays());
    }

    /**
     * 生成放行/拒绝的决策说明。
     *
     * <p>拒绝时<b>不</b>包含供应商内部信息，仅说明当前不可用——
     * 避免异常消息泄漏供应商与合同细节。
     */
    public @NonNull GateDecision evaluate(@NonNull Instant now) {
        State s = effectiveState(now);
        if (s == State.CONFIRMED) {
            return GateDecision.permit();
        }
        String reason = switch (s) {
            case PENDING -> "零保留条款尚未确认（DEC-10 GATE-2）";
            case EXPIRED -> "零保留确认已过期，需重新确认（DEC-10 GATE-2）";
            case REJECTED -> "该供应商明确拒绝零保留条款，不可用于真实学生数据";
            default -> "当前状态不允许接入真实学生数据";
        };
        return GateDecision.reject(reason, s);
    }

    /**
     * GATE 决策。
     *
     * @param allowed    是否放行
     * @param reason     拒绝原因（放行时为 null）
     * @param state      判定所依据的状态
     */
    /**
     * GATE 决策。
     *
     * <p><b>刻意用普通类而非 record</b>：record 的 accessor 与可空注解组合
     * 在 JSpecify 下存在编译期限制，而本类的「放行时 reason 必为 null」
     * 不变式本身就能在构造器中校验，无需 record 的自动生成访问器。
     */
    public static final class GateDecision {

        private final boolean allowed;
        private final String reason;
        private final State state;

        private GateDecision(boolean allowed, String reason, State state) {
            if (state == null) {
                throw new IllegalArgumentException("state 不能为空");
            }
            if (allowed && reason != null) {
                throw new IllegalArgumentException("放行决策不应携带拒绝原因");
            }
            this.allowed = allowed;
            this.reason = reason;
            this.state = state;
        }

        static GateDecision permit() {
            return new GateDecision(true, null, State.CONFIRMED);
        }

        static GateDecision reject(String reason, State state) {
            return new GateDecision(false, reason, state);
        }

        public boolean allowed() {
            return allowed;
        }

        public @Nullable String reason() {
            return reason;
        }

        public State state() {
            return state;
        }

        @Override
        public String toString() {
            return allowed ? "GateDecision[ALLOWED]" : "GateDecision[DENIED: " + reason + "]";
        }
    }
}
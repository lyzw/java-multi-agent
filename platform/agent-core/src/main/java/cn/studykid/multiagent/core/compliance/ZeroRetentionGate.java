package cn.studykid.multiagent.core.compliance;

import java.time.Clock;
import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 零保留闸门（DEC-10 GATE-1 / GATE-2 / R-017）。
 *
 * <p><b>设计立场：代码强制，而非流程约束</b>。
 * 「禁止真实学生数据进入 Prompt」若只写在规范里，
 * 一次疏忽或一次赶工就会击穿——而未成年人数据一旦出境或被用于训练，
 * <b>不可撤回</b>。故必须由代码在调用点强制拒绝。
 *
 * <p><b>按供应商判定</b>：调用时须指明供应商，闸门查该供应商的确认状态。
 * 刻意不提供「所有供应商均已确认」的短路路径——
 * 否则新增供应商时会被无声放行，形成合规漏洞。
 *
 * <p><b>fail-closed</b>：未登记、未确认、已过期一律拒绝。
 *
 * <p><b>依赖方向</b>：本类只接收字符串内容与供应商标识，
 * 不依赖 {@code ModelRequest}（后者位于 ports 模块，属上层）。
 */
public final class ZeroRetentionGate {

    private final ZeroRetentionRegistry registry;
    private final Clock clock;

    public ZeroRetentionGate(@NonNull ZeroRetentionRegistry registry, @NonNull Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 断言给定内容可放行。
     *
     * @param providerId     模型供应商标识（决定适用哪份零保留条款）
     * @param userContent    将进入 Prompt 的用户内容
     * @param classification 数据分级
     * @throws ZeroRetentionViolationException 违反 GATE-1/2 时抛出
     */
    public void assertAllowed(@NonNull String providerId,
                              @NonNull String userContent,
                              @NonNull DataClassification classification) {
        // 合成/模拟数据不受闸门限制——P1 阶段的正常工作模式
        if (!classification.containsRealStudentData()) {
            return;
        }

        Instant now = clock.instant();
        ZeroRetentionStatus status = registry.statusOf(providerId);
        ZeroRetentionStatus.GateDecision decision = status.evaluate(now);
        if (!decision.allowed()) {
            throw new ZeroRetentionViolationException(
                    decision.reason(), decision.state(), classification, providerId);
        }

        // ⚠️ 即便已确认，Prompt 也不得夹带可识别学生信息：
        // 零保留条款解决的是「供应商不存」，不是「我们不传」。
        // 传什么内容同样受白名单约束（P0-D00 §4）。
        assertNoIdentifyingContent(userContent, now, status, providerId);
    }

    /**
     * 检查内容不含可识别学生信息。
     *
     * <p><b>纵深防御</b>：第二道防线，不能替代数据分级——
     * 正则无法穷尽所有编码/隐写形式。
     */
    private void assertNoIdentifyingContent(String content, Instant now,
                                            ZeroRetentionStatus status, String providerId) {
        for (DataClassification.IdentifyingPattern pattern
                : DataClassification.IdentifyingPattern.values()) {
            if (pattern.detect(content)) {
                throw new ZeroRetentionViolationException(
                        "Prompt 含可识别学生信息（命中模式: " + pattern.label() + "）",
                        status.effectiveState(now),
                        DataClassification.REAL_STUDENT, providerId);
            }
        }
    }

    /** 该供应商当前是否允许接入真实学生数据。 */
    public boolean allowsRealStudentData(@NonNull String providerId) {
        return registry.allows(providerId, clock.instant());
    }

    /** 当前注册表快照。 */
    public @NonNull ZeroRetentionRegistry registry() {
        return registry;
    }
}
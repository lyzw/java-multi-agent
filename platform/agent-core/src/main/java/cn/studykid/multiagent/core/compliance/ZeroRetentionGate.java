package cn.studykid.multiagent.core.compliance;

import java.time.Clock;
import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 零保留闸门（DEC-10 GATE-1 / R-017）。
 *
 * <p><b>设计立场：代码强制，而非流程约束</b>。
 * 「禁止真实学生数据进入 Prompt」若只写在规范里，
 * 一次疏忽或一次赶工就会击穿——而未成年人数据一旦出境或被用于训练，
 * <b>不可撤回</b>。故必须由代码在调用点强制拒绝。
 *
 * <p><b>fail-closed</b>：任何不确定情形一律拒绝。
 * 调用方须<b>显式</b>声明数据分级；未声明即视为真实数据。
 *
 * <p><b>依赖方向</b>：本类只接收字符串内容，不依赖 {@code ModelRequest}
 * （后者位于 ports 模块，属上层）。领域层不应认识调用协议类型——
 * 由端口实现方负责把本类接入模型调用链。
 */
public final class ZeroRetentionGate {

    private final ZeroRetentionStatus status;
    private final Clock clock;

    public ZeroRetentionGate(@NonNull ZeroRetentionStatus status, @NonNull Clock clock) {
        this.status = status;
        this.clock = clock;
    }

    /**
     * 断言给定内容可放行。
     *
     * @param userContent    将进入 Prompt 的用户内容
     * @param classification 数据分级
     * @throws ZeroRetentionViolationException 违反 GATE-1/2 时抛出
     */
    public void assertAllowed(@NonNull String userContent,
                              @NonNull DataClassification classification) {
        // 合成/模拟数据不受闸门限制——P1 阶段的正常工作模式
        if (!classification.containsRealStudentData()) {
            return;
        }

        Instant now = clock.instant();
        var decision = status.evaluate(now);
        if (!decision.allowed()) {
            throw new ZeroRetentionViolationException(
                    decision.reason(), decision.state(), classification);
        }

        // ⚠️ 即便已确认，Prompt 也不得夹带可识别学生信息：
        // 零保留条款解决的是「供应商不存」，不是「我们不传」。
        // 传什么内容同样受白名单约束（P0-D00 §4）。
        assertNoIdentifyingContent(userContent, now);
    }

    /**
     * 检查内容不含可识别学生信息。
     *
     * <p><b>纵深防御</b>：这是第二道防线，不能替代数据分级——
     * 正则无法穷尽所有编码/隐写形式。
     */
    private void assertNoIdentifyingContent(String content, Instant now) {
        for (DataClassification.IdentifyingPattern pattern
                : DataClassification.IdentifyingPattern.values()) {
            if (pattern.detect(content)) {
                throw new ZeroRetentionViolationException(
                        "Prompt 含可识别学生信息（命中模式: " + pattern.label() + "）",
                        status.effectiveState(now),
                        DataClassification.REAL_STUDENT);
            }
        }
    }

    /** 当前 GATE-1 是否生效。用于启动自检与告警。 */
    public boolean gate1Active() {
        return status.gate1Active(clock.instant());
    }

    /** 当前状态快照。 */
    public @NonNull ZeroRetentionStatus currentStatus() {
        return status;
    }
}
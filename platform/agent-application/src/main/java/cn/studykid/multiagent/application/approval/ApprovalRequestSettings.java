package cn.studykid.multiagent.application.approval;

import java.time.Duration;

import org.jspecify.annotations.NonNull;

/**
 * 审批请求的登记参数（P0-2 · 审批行的生产写入方）。
 *
 * <p><b>为什么需要它</b>：此前 {@code agent.approval.ttl} 与
 * {@code agent.approval.approver-scope} 两个配置键<b>没有任何运行时读取点</b>
 * （{@code ConfigReadService} 里标注为 {@code effective=false}）——
 * 于是「审批记录」连<b>有效期</b>与<b>审批人范围</b>都没有落库依据，
 * 审批台即便有行也说不清「谁能批、批到什么时候」。
 *
 * <p><b>⚠️ 有效期必须有下界</b>：过短的窗口会让「人工审批」在现实中不可能完成
 * （审批台的人还没看到，待办已过期），等于把 HITL 变成「永远拒绝」。
 * 过长的窗口则让一份旧授权长期有效，撤销不及时。
 * 故下界 1 分钟、上界 24 小时，超界一律拒绝启动（fail-closed）。
 *
 * @param approverScope 审批人范围（如 {@code PARENT_OR_TEACHER}）
 * @param ttl           审批有效期
 */
public record ApprovalRequestSettings(@NonNull String approverScope, @NonNull Duration ttl) {

    /** 审批有效期下界（1 分钟）：低于此值人工审批在现实中不可完成。 */
    public static final Duration MIN_TTL = Duration.ofMinutes(1);

    /** 审批有效期上界（24 小时）：高于此值撤销不及时，安全风险不可接受。 */
    public static final Duration MAX_TTL = Duration.ofHours(24);

    public ApprovalRequestSettings {
        if (approverScope == null || approverScope.isBlank()) {
            throw new IllegalArgumentException("审批人范围不能为空");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("审批有效期必须为正数，收到: " + ttl);
        }
        if (ttl.compareTo(MIN_TTL) < 0) {
            throw new IllegalArgumentException(
                    "审批有效期不得短于 " + MIN_TTL + "（人工审批在现实中不可完成），收到: " + ttl);
        }
        if (ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "审批有效期不得长于 " + MAX_TTL + "（撤销不及时），收到: " + ttl);
        }
    }
}

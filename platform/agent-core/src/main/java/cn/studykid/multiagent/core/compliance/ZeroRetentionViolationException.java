package cn.studykid.multiagent.core.compliance;

import org.jspecify.annotations.NonNull;

/**
 * 违反零保留闸门（DEC-10 GATE-1 / R-017）。
 *
 * <p><b>归类为合规事故而非普通错误</b>：一旦抛出，说明真实学生数据
 * 试图进入模型调用而未被拦截。这需要留痕、上报并复盘——
 * 属于 Sev-0 级事件（P0-D05 R-017 影响：合规违规、隐私泄露）。
 *
 * <p><b>不携带原始数据</b>：异常消息会进日志，
 * 不得包含被拦截的学生内容。
 */
public class ZeroRetentionViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ZeroRetentionStatus.State state;
    private final transient DataClassification classification;

    public ZeroRetentionViolationException(String reason,
                                          ZeroRetentionStatus.State state,
                                          DataClassification classification) {
        super(reason);
        this.state = state;
        this.classification = classification;
    }

    /** 判定所依据的确认状态。 */
    public ZeroRetentionStatus.State state() {
        return state;
    }

    /** 被拦截的数据分级。 */
    public DataClassification classification() {
        return classification;
    }
}
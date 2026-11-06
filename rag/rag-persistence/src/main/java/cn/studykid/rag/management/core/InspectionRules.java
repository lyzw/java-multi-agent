package cn.studykid.rag.management.core;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 抽检判定规则（V1.0.2 §5 R2 / D04 §6）。
 *
 * <p>把「一组抽检记录」聚合成「这次抽检是否通过」。规则来自 D04 §6：
 * <ul>
 *   <li><b>答案区识别准确率 ≥ 95%</b>；</li>
 *   <li><b>答案区漏判 = 0</b>（单点否决——漏判即泄题）；</li>
 *   <li>题号保留率 100%；</li>
 *   <li>版本坐标正确率 100%（本项目由坐标强制 NOT NULL 保证，抽检时按记录核对）。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么用「单点否决」而不是平均分</b>：
 * 漏判一条答案，就意味着<b>某一个具体的标准答案</b>会被检索到并送进讲解模式的上下文。
 * 平均分 99% 掩盖的是「具体哪个学生看到了哪道题的答案」——
 * 这类风险不能用比例摊平。
 */
public final class InspectionRules {

    private InspectionRules() {
    }

    /** 答案区识别准确率下限（D04 §6）。 */
    public static final double ANSWER_ZONE_MIN_ACCURACY = 0.95;

    /**
     * 判定结果。
     *
     * @param passed         是否通过
     * @param total          抽检片段总数
     * @param failed         判错片段数
     * @param answerLeaks    答案区漏判数
     * @param accuracy       准确率（1 - failed/total）
     * @param reason         不通过的原因（通过时为 null）
     */
    public record Verdict(
            boolean passed,
            int total,
            int failed,
            int answerLeaks,
            double accuracy,
            String reason
    ) {
    }

    /**
     * 聚合一组抽检记录得出结论。
     *
     * <p>⚠️ <b>空集合视为不通过</b>：没有抽检任何片段就签发「通过」，
     * 等于把治理要求变成一次空转。必须至少抽检一个片段。
     *
     * @param records 本次抽检的记录
     * @return 判定结果
     */
    public static Verdict evaluate(@NonNull List<InspectionRecord> records) {
        if (records == null || records.isEmpty()) {
            return new Verdict(false, 0, 0, 0, 0.0,
                    "未抽检任何片段，不得判定为通过");
        }
        int total = records.size();
        int failed = 0;
        int answerLeaks = 0;
        for (InspectionRecord r : records) {
            if (r.verdict() == InspectionRecord.Verdict.FAIL) {
                failed++;
                if (r.isAnswerLeak()) {
                    answerLeaks++;
                }
            }
        }
        double accuracy = (total - failed) / (double) total;

        // 规则 1：答案区漏判 = 0（单点否决，优先级最高）
        if (answerLeaks > 0) {
            return new Verdict(false, total, failed, answerLeaks, accuracy,
                    "答案区漏判 " + answerLeaks + " 处（漏判即泄题，单点否决）");
        }
        // 规则 2：准确率 ≥ 95%
        if (accuracy < ANSWER_ZONE_MIN_ACCURACY) {
            return new Verdict(false, total, failed, answerLeaks, accuracy,
                    String.format("识别准确率 %.1f%% 低于下限 %.0f%%",
                            accuracy * 100, ANSWER_ZONE_MIN_ACCURACY * 100));
        }
        return new Verdict(true, total, failed, 0, accuracy, null);
    }

    /**
     * 把判定结果映射为抽检状态（写入 {@code rag_source.inspection_status}）。
     *
     * @param verdict 判定结果
     * @return 抽检状态
     */
    public static InspectionGuard.InspectionStatus toStatus(@NonNull Verdict verdict) {
        return verdict.passed()
                ? InspectionGuard.InspectionStatus.PASSED
                : InspectionGuard.InspectionStatus.FAILED;
    }
}

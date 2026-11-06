package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 抽检记录（{@code rag_inspection_record} 的领域视图，V1.0.2 §5 R2 / 设计 §2.2.3）。
 *
 * <p><b>⚠️ 抽检记录是「质量判定的证据」，不是可覆盖的状态</b>：
 * 它只追加不修改。一组抽检记录构成某次抽检的完整凭据
 * （判定人 / 片段 / 期望角色 / 实际角色 / 错误类型 / 结论），
 * 门禁证据包（D09 G1 ②③④）直接引用这些记录。
 *
 * <p><b>⚠️ {@code expectedRole} 与 {@code actualRole} 必须都记录</b>：
 * 只记「错/对」无法回答「错在哪一类」——而答案区漏判（应为 EXERCISE_ANSWER
 * 却判成 EXPLANATION）与其他错误（如题号丢失）的严重性差一个量级。
 */
public record InspectionRecord(
        @NonNull String recordId,
        @NonNull String tenantId,
        @NonNull String sourceId,
        @NonNull String chunkId,
        @NonNull String inspectorId,
        /** 期望角色（人工判定的正确角色） */
        @NonNull String expectedRole,
        /** 实际角色（系统产出的角色） */
        @NonNull String actualRole,
        /** 错误类型；结论为 PASS 时为 null */
        @Nullable ErrorType errorType,
        @NonNull Verdict verdict,
        @NonNull String createdAt
) {

    /**
     * 抽检结论。
     */
    public enum Verdict {
        /** 该片段判定正确。 */
        PASS,
        /** 该片段判定错误。 */
        FAIL
    }

    /**
     * 错误类型。
     *
     * <p>⚠️ {@link #ANSWER_LEAK} 是<b>单点否决</b>：命中一条即整册不合格（D04 §6）。
     * 其余类型可以统计后按比例判定。
     */
    public enum ErrorType {
        /** 答案区漏判——标准答案被判成讲解，会泄题。单点否决。 */
        ANSWER_LEAK,
        /** 题号丢失/错位。 */
        EXERCISE_NO_LOST,
        /** 章节路径或页码坐标错误（影响可回取原文）。 */
        LOCATOR_WRONG,
        /** 切分边界错误（片段被截断或跨单元合并）。 */
        CHUNK_BOUNDARY,
        /** 其他错误（需在备注中说明）。 */
        OTHER
    }

    /**
     * 紧凑构造器：锁住记录的自洽性。
     */
    public InspectionRecord {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId 不能为空");
        }
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (inspectorId == null || inspectorId.isBlank()) {
            throw new IllegalArgumentException("inspectorId 不能为空");
        }
        if (verdict == Verdict.FAIL && errorType == null) {
            // ⚠️ 判错必须说明错在哪一类，否则无法驱动针对性改进（如只补答案区规则）。
            throw new IllegalArgumentException("判定为 FAIL 的记录必须给出 errorType");
        }
        if (verdict == Verdict.PASS && errorType != null) {
            // ⚠️ 判对却带错误类型是数据矛盾，会污染错误率统计。
            throw new IllegalArgumentException("判定为 PASS 的记录不得携带 errorType");
        }
    }

    /**
     * 是否为答案区漏判（单点否决项）。
     *
     * @return 是漏判返回 true
     */
    public boolean isAnswerLeak() {
        return verdict == Verdict.FAIL && errorType == ErrorType.ANSWER_LEAK;
    }
}

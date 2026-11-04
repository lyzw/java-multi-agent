package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审核待办项（交互文档 §4.6「审核收件箱」）。
 *
 * <p><b>⚠️ 为什么单独一个记录，而不是复用 {@link CorpusSource}</b>：
 * 待办是「**这个人此刻该做什么**」的视图，它需要把
 * 元数据确认状态、抽检状态、片段统计、采集人、下一步动作**合在一起**判断。
 * 塞进 {@code CorpusSource} 会让来源的治理属性与流程调度混成一团。
 *
 * <p>⚠️ {@code stage} 由 {@link ReviewQueueRules} 算出，**不由前端推算**：
 * 前端自行判断会与后端守卫口径漂移，产出「点下去必然 409」的按钮。
 *
 * @param sourceId           来源 ID
 * @param title              书名
 * @param publisher          出版社
 * @param verificationStatus 校验状态
 * @param metadataConfirmed  元数据是否已确认
 * @param inspectionStatus   抽检状态（NOT_REQUIRED / PASSED / FAILED）
 * @param documentCount      载体数
 * @param chunkCount         片段总数
 * @param answerChunkCount   答案类片段数（抽检最关键的对象）
 * @param registeredBy       采集人主体 ID（可空：历史数据无审计记录）
 * @param stage              下一步该做什么
 * @param canAct             当前调用者此刻能否执行
 * @param cantActReason      不可执行的原因（canAct=true 时为 null）
 * @param createdAt          创建时间
 */
public record ReviewQueueEntry(
        @NonNull String sourceId,
        @NonNull String title,
        @NonNull String publisher,
        @NonNull String verificationStatus,
        boolean metadataConfirmed,
        @NonNull String inspectionStatus,
        int documentCount,
        long chunkCount,
        long answerChunkCount,
        @Nullable String registeredBy,
        ReviewQueueRules.Stage stage,
        boolean canAct,
        @Nullable String cantActReason,
        @NonNull String createdAt
) {

    /**
     * 构造时只做**参数守卫**，不推断 canAct。
     *
     * <p>⚠️⚠️ **构造器不猜调用者**（实测修正）：
     *    此前这里用 {@code canActorProceed(stage, registeredBy, null)} 算出 canAct，
     *    第二个参数是 null 表示「没有当前用户」——于是「审核员≠采集人」这条
     *    永远判为「不冲突」，采集人打开自己的待办会看到**可点的签发按钮**。
     *    这不是参数小错，而是「构造器在没有调用者身份的情况下冒充已判定」。
     *    正确做法：构造器留空，由 {@link #withViewer(String)} 在**已知调用者**时补全。
     */
    public ReviewQueueEntry {
        if (stage == null) {
            throw new IllegalArgumentException("stage 不能为空");
        }
    }

    /**
     * 以指定调用者身份重建（算出 canAct / cantActReason）。
     *
     * @param currentUser 当前调用者主体 ID（可空）
     * @return 补全后的待办项
     */
    @NonNull
    public ReviewQueueEntry withViewer(@Nullable String currentUser) {
        boolean can = ReviewQueueRules.canActorProceed(stage, registeredBy, currentUser);
        String reason = can ? null : ReviewQueueRules.cantActReason(stage, registeredBy, currentUser);
        return new ReviewQueueEntry(sourceId, title, publisher, verificationStatus,
                metadataConfirmed, inspectionStatus, documentCount, chunkCount,
                answerChunkCount, registeredBy, stage, can, reason, createdAt);
    }
}

package cn.studykid.rag.management.core;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 元数据确认的结果（交互文档 §4.3）。
 *
 * <p><b>⚠️ 为什么单独出一个结果类型，而不是复用 {@link CorpusSource}</b>：
 * 「是否已确认元数据」是<b>录入流程</b>的状态，不是来源的治理属性。
 * 把它塞进 {@code CorpusSource} 会让「校验状态」「版权状态」「确认状态」
 * 三个正交的轴混在一个记录里，且要改动 5 处构造点（含测试）。
 * 录入流程的进度应该由录入侧的类型承载。
 *
 * @param sourceId            来源 ID
 * @param metadataConfirmed   是否已确认
 * @param metadataConfirmedAt 首次确认时间（未确认时为 null）
 * @param metadataConfirmedBy 首次确认人（未确认时为 null）
 */
public record MetadataConfirmationResult(
        @NonNull String sourceId,
        boolean metadataConfirmed,
        @Nullable Instant metadataConfirmedAt,
        @Nullable String metadataConfirmedBy
) {
}

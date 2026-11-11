package cn.studykid.multiagent.context;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.WaterLevel.Tier;

/**
 * 压缩管线的输出（《方案》§6.2-B，M5-b）。纯值对象，全参构造是唯一构造。
 *
 * <p>⚠️ <b>零请求字节变更（S1/S2 影子纪律）</b>：本对象只是算法产物，
 * M5 阶段<b>不被任何生产链路调用</b>；M7 接线时才由 {@code ContextLayout}
 * 渲染成新的 {@code ModelRequest}。当前新增它不改变任何在途请求。
 *
 * @param blocks          压缩后的上下文块（交给 ContextLayout 渲染）
 * @param tier            实际命中的水位
 * @param fallback        是否发生降级（W3 摘要失败 / 二次硬裁失败回落）
 * @param fallbackReason  降级原因码（{@code null} = 无降级）
 * @param droppedRounds   被整轮丢弃的 TURN 数
 * @param snippedTools    被 W1 可逆外置的工具结果 refId 列表
 * @param beforeTokens    压缩前全窗口估算
 * @param afterTokens     压缩后全窗口估算
 */
public record CompactionResult(
        @NonNull CompactedBlocks blocks,
        @NonNull Tier tier,
        boolean fallback,
        @Nullable String fallbackReason,
        int droppedRounds,
        @NonNull List<String> snippedTools,
        int beforeTokens,
        int afterTokens) {

    public CompactionResult {
        snippedTools = snippedTools == null ? List.of() : List.copyOf(snippedTools);
    }

    /** 是否实际发生了压缩（丢弃轮 / 外置工具 / 摘要替换）。 */
    public boolean compacted() {
        return droppedRounds > 0 || !snippedTools.isEmpty() || tier == WaterLevel.Tier.W3;
    }
}

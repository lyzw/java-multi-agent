package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * 启发式全窗口 Token 计数器（S1 影子期实现，ADR-013）。
 *
 * <p>⚠️ <b>与既有 {@code ContextBudgetTrimmer} 的关系</b>：沿用其
 * 「汉字 1 字 ≈ 1 token、非汉字 3 字符 ≈ 1 token」的保守口径，
 * 但把统计范围从「消息序列」扩到 {@link ContextBlocks} 的<b>六个块</b>。
 * 粗估中英混合误差可达 30–50%，故只用于触发判断，
 * 真值由 {@code usage.prompt_tokens} 校准（§5.7），并由 {@code reserveRatio}
 * 与硬顶二次校验兜底。
 *
 * <p>⚠️ <b>工具 Schema 必须计入</b>（RV-1）：撤掉 {@code toolSchemas}
 * 会让预算退回「消息序列」口径，`HeuristicTokenCounterTest` 必须变红。
 *
 * <p>⚠️ 确定性纯函数：不读时钟、不读租户上下文、无 IO。
 */
public final class HeuristicTokenCounter implements TokenCounter {

    /** 非汉字字符数 ÷ 该值；取 3 为偏保守一侧（估算偏大 → 早压缩）。 */
    static final int CHARS_PER_TOKEN = 3;

    /** 每个块作为一条消息送入模型时的 role / 分隔符封装开销。 */
    static final int PER_BLOCK_OVERHEAD = 4;

    @Override
    public int count(@NonNull ContextBlocks blocks) {
        int total = 0;
        total += countText(blocks.systemPrompt());
        // ⚠️ 不得删除此行：工具定义是上下文窗口里真实占用的 token（RV-1 反向验证点）。
        total += countText(blocks.toolSchemas());
        total += countText(blocks.memoryBlock());
        for (String rag : blocks.ragBlocks()) {
            total += countText(rag);
        }
        for (String history : blocks.historyTexts()) {
            total += countText(history);
        }
        total += countText(blocks.currentInput());
        return total;
    }

    /** 单块估算：汉字按字计，其余按 3 字符/token，外加消息封装开销。 */
    private static int countText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil((double) other / CHARS_PER_TOKEN) + PER_BLOCK_OVERHEAD;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }
}

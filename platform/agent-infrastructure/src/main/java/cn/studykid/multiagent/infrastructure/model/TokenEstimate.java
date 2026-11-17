package cn.studykid.multiagent.infrastructure.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * 本地 token 粗估（流式供应商未回传 {@code usage} 时的兜底）。
 *
 * <p>规则与 {@code agent-worker} 的 {@code ContextBudgetTrimmer.estimateTokens} 完全一致：
 * CJK 码点按 1 token，其余字符约 3 字符/token 向上取整。本类刻意自包含——
 * {@code agent-infrastructure} 不能反向依赖 {@code agent-worker}。
 *
 * <p>⚠️ 估算值只用于成本/预算的近似口径，调用方须把 {@code ModelUsage.estimated()} 置真，
 * 不得把它当成供应商实测用量。
 */
final class TokenEstimate {

    private static final int CHARS_PER_TOKEN = 3;

    private TokenEstimate() {
    }

    /** 估算一段文本的 token 数；空文本返回 0。 */
    static long ofText(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int codePoints = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            codePoints++;
            if (isWideScript(cp)) {
                cjk++;
            }
        }
        int others = codePoints - cjk;
        return cjk + (others + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * 估算请求侧 prompt token：系统提示 + 全部生效消息内容 + 工具 Schema 文本。
     */
    static long ofPrompt(@NonNull ModelRequest request) {
        StringBuilder sb = new StringBuilder(request.systemPrompt());
        for (ModelMessage message : request.effectiveMessages()) {
            sb.append('\n').append(message.content());
        }
        if (!request.toolSchemas().isEmpty()) {
            sb.append('\n').append(request.toolSchemas());
        }
        return ofText(sb.toString());
    }

    private static boolean isWideScript(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        };
    }
}

package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 外部不可信输出的<b>信封化</b>公共实现（MCP 输出与远程 HTTP 响应共用）。
 *
 * <p>⚠️ <b>为什么抽成公共实现</b>：
 * MCP Server 的返回值与远程 HTTP 的响应体<b>都是外部数据</b>，
 * 都要「加显式边界 + 限体积」。两处各写一遍截断逻辑，迟早漂移 ——
 * 而漂移的方向通常是「某一处忘了按字节截断」，
 * 那正是「一次响应撑爆结果事件表与账本载荷」的成因。
 * 故本仓库<b>只有这一处</b>实现。
 *
 * <p>⚠️ <b>本类不做清洗</b>：不删除、不改写内容里的任何字符
 * （删字符会改变语义，且攻击者总能绕过）。
 * 它只保证「边界可见 + 体积有界」。
 */
final class UntrustedOutputEnvelope {

    /**
     * 受控输出。
     *
     * @param payload        加边界后的载荷
     * @param truncated      是否被截断
     * @param originalLength 原始长度（字符数）
     */
    record Guarded(@NonNull String payload,
                   boolean truncated,
                   int originalLength) {
    }

    private UntrustedOutputEnvelope() {
    }

    /**
     * 把外部输出包裹成「显式外部数据 + 体积有界」的载荷。
     *
     * <p>⚠️ <b>先按字节预算、字符边界截断，再拼信封</b>：
     * <ul>
     *   <li><b>按字节</b>：信封本身也占体积，若按字符数截断，
     *       中文按 UTF-8 编码后可能超限；</li>
     *   <li><b>按字符边界</b>：直接砍字节数组会把多字节字符砍成半个 →
     *       解码出替换字符 U+FFFD，载荷内容被悄悄改坏。</li>
     * </ul>
     *
     * @param headerLine    完整的第一行（含换行）
     * @param footer        结束标记
     * @param truncatedMark 截断标记
     * @param raw           原始文本（可为 null）
     * @param maxBytes      体积上限（字节）
     * @param reservedBytes 信封自身占用的字节预算
     * @return 受控输出
     */
    static @NonNull Guarded wrap(@NonNull String headerLine,
                                 @NonNull String footer,
                                 @NonNull String truncatedMark,
                                 @Nullable String raw,
                                 int maxBytes,
                                 int reservedBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须为正数");
        }
        String content = raw == null ? "" : raw;
        int originalLength = content.length();

        int envelopeBudget = Math.max(0, maxBytes - reservedBytes);
        int used = 0;
        int end = 0;
        while (end < content.length()) {
            int codePoint = content.codePointAt(end);
            int charCount = Character.charCount(codePoint);
            int byteLength = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (used + byteLength > envelopeBudget) {
                break;
            }
            used += byteLength;
            end += charCount;
        }
        boolean truncated = end < content.length();
        String body = truncated ? content.substring(0, end) + truncatedMark : content;

        StringBuilder payload = new StringBuilder(body.length() + 64);
        payload.append(headerLine);
        payload.append(body);
        if (!body.endsWith("\n")) {
            payload.append('\n');
        }
        payload.append(footer);
        return new Guarded(payload.toString(), truncated, originalLength);
    }
}

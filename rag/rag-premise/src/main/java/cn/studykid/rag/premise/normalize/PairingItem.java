package cn.studykid.rag.premise.normalize;

import java.util.Arrays;
import java.util.Locale;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 配对候选：一个片段 + 它的规范题号 + 作用域锚点（RAG-D28 §3.3 P2-3）。
 *
 * <p>⚠️ 题号与作用域由调用方显式给出，不从 {@code Chunk.exerciseRef} 反推：
 * 现有 {@code exerciseRef} 是「同块继承的原始题号」，正是本工序要修正的对象，
 * 拿它当配对输入等于用待修复数据自检。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 *
 * @param chunk  原始片段（题目或答案）
 * @param key    规范化题号
 * @param scope  作用域锚点（edition 五轴 + 章节，或试卷）
 * @param answer 是否为答案侧（false = 题目侧）
 */
public record PairingItem(
        @NonNull Chunk chunk,
        @NonNull ExerciseKey key,
        @NonNull ExerciseScope scope,
        boolean answer) {

    public PairingItem {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为空");
        }
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
    }

    /**
     * 由片段构造题目侧候选（作用域取片段的版本坐标 + 章节路径）。
     *
     * @param chunk 题目片段
     * @param key   规范题号
     * @return 候选
     */
    public static @NonNull PairingItem question(@NonNull Chunk chunk, @NonNull ExerciseKey key) {
        return new PairingItem(chunk, key, scopeOf(chunk), false);
    }

    /**
     * 由片段构造答案侧候选（作用域取片段的版本坐标 + 章节路径）。
     *
     * @param chunk 答案片段
     * @param key   规范题号
     * @return 候选
     */
    public static @NonNull PairingItem answer(@NonNull Chunk chunk, @NonNull ExerciseKey key) {
        return new PairingItem(chunk, key, scopeOf(chunk), true);
    }

    private static ExerciseScope scopeOf(Chunk chunk) {
        // ⚠️ EditionCoordinate 无单独 id 字段，display() 是五轴的稳定只读标识；
        //    用它做锚点保证「同五轴同章节」才算同作用域（RAG-D16 版本坐标系）。
        return ExerciseScope.ofEdition(chunk.edition().display(),
                stripAnswerZoneTail(chunk.locator().sectionPath()));
    }

    /**
     * 剥除章节路径尾部的答案区标题（答案册按章编排时形如「第3章 > 参考答案」）。
     *
     * <p>⚠️ 不剥会让答案册的标题「参考答案」被当成章节，与教材「第3章」
     * 永远不同作用域 → 全部 UNMATCHED。单段路径（整条就是「参考答案」）
     * <b>不剥</b>：此时没有可映射的章节，保持不同作用域即 fail-closed。
     */
    private static String stripAnswerZoneTail(String sectionPath) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return sectionPath;
        }
        String[] parts = sectionPath.split(" > ");
        if (parts.length < 2 || !isAnswerZoneLabel(parts[parts.length - 1].trim())) {
            return sectionPath;
        }
        return String.join(" > ", Arrays.copyOf(parts, parts.length - 1));
    }

    private static boolean isAnswerZoneLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.startsWith("answer key") || lower.startsWith("answers to")
                || lower.startsWith("answer sheet") || lower.equals("answers")
                || label.startsWith("参考答案") || label.startsWith("答案与解析")
                || label.startsWith("练习答案") || label.startsWith("习题答案")
                || label.startsWith("答案");
    }
}

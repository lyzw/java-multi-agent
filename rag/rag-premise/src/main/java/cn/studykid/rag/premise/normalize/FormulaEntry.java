package cn.studykid.rag.premise.normalize;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 受控公式词典条目（P1-6）。
 *
 * <p>⚠️ 顶层类型（不得嵌套）：{@code PremiseTopLevelTypeGuardTest} 禁止 premise 层出现
 * 嵌套类型（规避 JDK 21.0.12 javac 符号解析缺陷，见 RAG-D09 §6）。
 *
 * @param latex           规范化 LaTeX（写入中间产物的权威文本）
 * @param naturalLanguage 人工审核的自然语言描述（唯一语义来源，禁运行时拼装）
 * @param symbols         符号 → 含义（供词法通道/别名使用）
 */
public record FormulaEntry(
        @NonNull String latex,
        @NonNull String naturalLanguage,
        @NonNull Map<String, String> symbols) {

    public FormulaEntry {
        if (latex == null || naturalLanguage == null || naturalLanguage.isBlank()
                || symbols == null) {
            throw new IllegalArgumentException("词典条目字段不可为空");
        }
    }
}

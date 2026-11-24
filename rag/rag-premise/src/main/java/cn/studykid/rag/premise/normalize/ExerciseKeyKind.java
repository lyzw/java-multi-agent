package cn.studykid.rag.premise.normalize;

/**
 * 规范化题号的形态（RAG-D28 §3.3 P2-2）。
 *
 * <p>⚠️ 四种形态是<b>互斥</b>的：题组不能被压平成单级，子问不能被当成另一道题。
 * 压平会让「第 5-7 题」与「第 5 题」在配对时互相误命中，属于静默强绑。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 */
public enum ExerciseKeyKind {
    /** 单级题：Q003。 */
    SINGLE,
    /** 多级子问：Q003.S02。 */
    SUB,
    /** 题组：GRP:Q005-Q007。 */
    GROUP,
    /** 无法可靠规范化：UNMATCHED（禁止猜题强绑）。 */
    UNMATCHED
}
